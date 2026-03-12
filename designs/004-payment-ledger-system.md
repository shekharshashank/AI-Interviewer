# Gold Standard: Payment Ledger System (Stripe Staff/Sr Staff)

## 0. Why This Matters at Stripe

A payment ledger is **the financial source of truth** for every dollar that moves through the platform. At Stripe's scale (~$1T+ annual processing volume), the ledger must be:
- **Correct to the penny** — a 0.001% error rate on $1T = $10M in discrepancies
- **Auditable** — regulators (SEC, PCI-DSS, SOX) require complete, immutable transaction trails
- **Eventually consistent with real-time aspirations** — merchants want instant balance visibility
- **Multi-currency, multi-entity** — Stripe operates across 40+ countries, each with its own regulatory and currency requirements

This is not a generic database design — it's a **financial system of record** that underpins every Stripe product: Payments, Connect, Issuing, Treasury, and Billing.

---

## 1. Scope & Requirements

### Functional
- Record every financial movement as **double-entry bookkeeping** (every debit has an equal credit)
- Support core transaction types: charges, refunds, payouts, transfers, disputes, fees, adjustments
- Provide **real-time balance queries** for merchants, connected accounts, and Stripe's own accounts
- Support **multi-currency** with exchange rate tracking
- Enable **reconciliation** between internal ledger, bank records, and card network settlements
- Support **idempotent writes** — retried API calls must not create duplicate entries
- Provide **point-in-time balance reconstruction** for auditing

### Non-Functional
- **Throughput**: 100K+ ledger entries/sec (each payment = 4-8 ledger entries)
- **Correctness**: Absolute zero tolerance for balance inconsistency. Double-entry invariant must NEVER be violated.
- **Latency**: Balance reads < 50ms p99. Ledger writes < 200ms p99.
- **Durability**: Zero data loss. Period. This is money.
- **Availability**: 99.999% for writes (downtime = merchants can't get paid)
- **Auditability**: Complete, immutable history. No physical deletes. Ever.
- **Compliance**: PCI-DSS, SOX, GAAP/IFRS alignment

### Back-of-Envelope

```
Stripe processes ~$1T/year
Average payment = ~$100 → ~10B payments/year → ~300K payments/sec peak
Each payment generates ~6 ledger entries (charge, fee, net, platform fee, etc.)
→ ~1.8M ledger entries/sec peak

Entry size: ~500 bytes (accounts, amounts, currency, metadata, timestamps)
Daily: 1.8M * 86,400 * 500B ≈ 78 TB/day raw ledger entries
Yearly: ~28 PB raw (before compression)

Active accounts needing real-time balances: ~5M merchants
Balance query rate: ~50K reads/sec sustained, 200K peak
```

**Key insight**: This is a write-heavy system (30:1 write-to-read ratio on the ledger) but with strict correctness requirements that rule out most "eventually consistent" shortcuts.

---

## 2. Core Concepts: Double-Entry Bookkeeping

Before the architecture — the model. **If you don't nail this, nothing else matters.**

### Why Double-Entry

Every financial movement is recorded as **two or more entries that sum to zero**. This is not a nice-to-have — it's a 700-year-old accounting invariant that makes errors self-detecting.

```
Example: Customer pays $100, Stripe takes 2.9% + $0.30 fee

  Account                  | Debit    | Credit   |
  ─────────────────────────┼──────────┼──────────┤
  Customer Payment Source   | $100.00  |          |  ← money comes in
  Merchant Pending Balance  |          | $97.10   |  ← merchant's share
  Stripe Revenue            |          |  $2.90   |  ← Stripe's fee
  ─────────────────────────┼──────────┼──────────┤
  TOTAL                     | $100.00  | $100.00  |  ← must always balance
```

### The Invariants

1. **Zero-sum**: For every transaction, `SUM(debits) = SUM(credits)`. Always.
2. **Immutability**: Entries are never updated or deleted. Corrections are new entries (reversals).
3. **Idempotency**: The same logical operation must produce the same entries, exactly once.
4. **Temporal ordering**: Entries have a strict causal ordering within an account.

### Account Hierarchy

```
                       ┌──────────────────┐
                       │   Stripe Master   │
                       │   (Root Entity)   │
                       └────────┬──────────┘
                                │
              ┌─────────────────┼─────────────────┐
              ▼                 ▼                  ▼
    ┌──────────────┐  ┌──────────────┐   ┌──────────────┐
    │   Assets      │  │  Liabilities  │   │   Revenue     │
    │  (what Stripe │  │  (what Stripe │   │  (Stripe's    │
    │   holds)      │  │   owes)       │   │   earnings)   │
    └───────┬───────┘  └───────┬───────┘   └───────┬───────┘
            │                  │                    │
     ┌──────┴──────┐    ┌─────┴──────┐      ┌─────┴──────┐
     │ Bank accounts│    │ Merchant    │      │ Processing │
     │ Card network │    │  balances   │      │   fees     │
     │  receivables │    │ Connected   │      │ Platform   │
     │ Reserve funds│    │  acct bal   │      │   fees     │
     └─────────────┘    │ Dispute     │      │ FX margin  │
                        │  reserves   │      └────────────┘
                        └─────────────┘
```

**Staff+ insight**: The account hierarchy maps to the Chart of Accounts (CoA) in traditional accounting. At Stripe's scale, there are millions of leaf accounts (one per merchant per currency), but they roll up into ~50-100 account types. This hierarchy enables both granular per-merchant queries and aggregate financial reporting.

---

## 3. High-Level Architecture

```
                    ┌─────────────────────────────────────┐
                    │        PAYMENT API SERVICES          │
                    │  (Charges, Refunds, Payouts, etc.)   │
                    └──────────────┬───────────────────────┘
                                   │ Ledger write requests
                                   │ (idempotency_key, entries[])
                                   ▼
                    ┌──────────────────────────────────────┐
                    │         LEDGER WRITE SERVICE          │
                    │  - Idempotency enforcement            │
                    │  - Double-entry validation            │
                    │  - Optimistic locking on balances     │
                    │  - Transaction serialization          │
                    └─────────┬──────────────┬─────────────┘
                              │              │
                   ┌──────────┘              └──────────┐
                   ▼                                    ▼
        ┌─────────────────┐                 ┌─────────────────────┐
        │  LEDGER STORE    │                 │  BALANCE STORE       │
        │  (Immutable Log) │                 │  (Materialized View) │
        │                  │                 │                      │
        │  - Append-only   │──── CDC ───────▶│  - Current balances  │
        │  - Partitioned   │                 │  - Available/Pending │
        │    by account    │                 │  - Real-time reads   │
        │  - Full history  │                 │  - Optimistic lock   │
        └────────┬─────────┘                 └──────────┬──────────┘
                 │                                      │
                 ▼                                      ▼
        ┌─────────────────┐                 ┌─────────────────────┐
        │  EVENT BUS       │                 │  BALANCE QUERY API   │
        │  (Kafka)         │                 │  - Merchant dashboard│
        │  - Downstream    │                 │  - API responses     │
        │    consumers     │                 │  - Internal services │
        └───────┬──────────┘                 └─────────────────────┘
                │
      ┌─────────┼──────────────┬─────────────────┐
      ▼         ▼              ▼                  ▼
┌──────────┐ ┌──────────┐ ┌──────────────┐ ┌──────────────┐
│Reconciler│ │ Reporting│ │  Audit Log   │ │ Analytics /  │
│  Service │ │  (GL)    │ │  (Compliance)│ │  ML Fraud    │
└──────────┘ └──────────┘ └──────────────┘ └──────────────┘
```

---

## 4. Component Deep Dive

### 4.1 — Ledger Write Service

The **most critical component** in the entire system. This is where money moves.

#### Write Path (Single Transaction)

```
1. Receive request:
   {
     idempotency_key: "pay_abc123_charge",
     effective_at: "2025-01-15T10:30:00Z",
     entries: [
       { account: "merchant_m1_usd_pending", direction: "credit", amount: 9710, currency: "usd" },
       { account: "stripe_revenue_usd",      direction: "credit", amount: 290,  currency: "usd" },
       { account: "card_network_receivable",  direction: "debit",  amount: 10000, currency: "usd" }
     ],
     metadata: { payment_id: "pay_abc123", type: "charge" }
   }

2. Idempotency check:
   - Lookup idempotency_key in idempotency store
   - If found → return cached response (no re-execution)
   - If not found → proceed

3. Validation:
   - SUM(debits) == SUM(credits)?  → if not, REJECT (hard invariant)
   - All accounts exist and are active?
   - Currency matches account currency?
   - Amount > 0 for all entries?

4. Balance pre-check (optimistic):
   - For debit entries: does the source account have sufficient balance?
   - Read current balance version (for OCC)

5. Atomic write:
   - BEGIN TRANSACTION
   -   INSERT ledger_entries (all entries in the transaction)
   -   UPDATE balances SET amount = amount +/- delta, version = version + 1
   -       WHERE account_id = X AND version = expected_version
   -   INSERT idempotency_record
   - COMMIT

6. If version conflict (OCC failure) → RETRY (bounded, typically 3 attempts)

7. Emit event to Kafka (after commit, via transactional outbox)
```

#### Why Optimistic Concurrency Control (OCC), Not Pessimistic Locks

| Pessimistic (SELECT FOR UPDATE) | Optimistic (version check) |
|---|---|
| Holds row lock for entire txn | No lock held during reads |
| High contention on hot accounts → deadlocks | Retry on conflict, no deadlocks |
| Lock duration = network RTT + processing | Conflict detected at commit time only |
| Doesn't scale past ~10K TPS per account | Scales to 100K+ TPS with low conflict rate |

**Staff+ insight**: Most accounts have low contention (individual merchants). But Stripe's own revenue accounts are **extremely hot** — every payment credits them. Solution: **sharded aggregate accounts**. Stripe's revenue account becomes `stripe_revenue_usd_shard_{0..255}`. Each payment writes to a random shard. Aggregate balance = SUM(all shards). This converts one hot row into 256 cool rows.

#### Idempotency Implementation

```
Table: idempotency_keys
─────────────────────────────────────
key          VARCHAR(255) PRIMARY KEY
response     JSONB
created_at   TIMESTAMP
expires_at   TIMESTAMP  -- TTL: 24-72 hours
```

**Design decisions**:
- Key = caller-provided (e.g., `pay_abc123_charge`), NOT server-generated
- TTL exists because unbounded growth is a storage problem, but 24-72h covers all reasonable retry windows
- The idempotency check and the ledger write MUST be in the same database transaction. Otherwise, crash between idempotency insert and ledger write = permanent data inconsistency.

**Staff+ insight**: Idempotency at the ledger level is your **last line of defense**. Every upstream service (Payments API, Connect, etc.) should also be idempotent, but bugs happen. The ledger's idempotency guarantee means that even if an upstream service double-fires, money doesn't move twice.

### 4.2 — Ledger Store (Immutable Log)

**Choice: PostgreSQL (with partitioning) or purpose-built ledger DB (like TigerBeetle)**

#### Schema

```sql
-- Core ledger entries table (append-only, never updated)
CREATE TABLE ledger_entries (
    entry_id          BIGINT GENERATED ALWAYS AS IDENTITY,
    transaction_id    UUID NOT NULL,          -- groups entries that must balance
    idempotency_key   VARCHAR(255) NOT NULL,
    account_id        BIGINT NOT NULL,
    direction         SMALLINT NOT NULL,      -- 1 = debit, -1 = credit
    amount            BIGINT NOT NULL,        -- integer cents (NEVER float)
    currency          CHAR(3) NOT NULL,       -- ISO 4217
    effective_at      TIMESTAMPTZ NOT NULL,   -- when the money "logically" moved
    posted_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(), -- when we recorded it
    metadata          JSONB,

    PRIMARY KEY (account_id, entry_id),       -- partition-local PK

    CONSTRAINT positive_amount CHECK (amount > 0),
    CONSTRAINT valid_direction CHECK (direction IN (1, -1))
) PARTITION BY HASH (account_id);

-- Create 64 hash partitions (distributes writes across files/tablespaces)
-- In production, this would be 256-1024 partitions across shards

-- Transaction-level integrity check (enforced at application layer + async)
-- SUM(amount * direction) GROUP BY transaction_id = 0 for ALL transactions

-- Index for transaction-level lookups
CREATE INDEX idx_ledger_txn ON ledger_entries (transaction_id);

-- Index for time-range queries per account (audit, statements)
CREATE INDEX idx_ledger_account_time ON ledger_entries (account_id, effective_at DESC);
```

**Critical design decisions**:

| Decision | Choice | Why |
|---|---|---|
| Amount type | `BIGINT` (integer cents) | Floating point is **forbidden** in financial systems. `0.1 + 0.2 ≠ 0.3` in IEEE 754. Integer arithmetic is exact. $100.00 = `10000` cents. |
| Separate debit/credit columns vs direction | Single `amount` + `direction` | Simpler aggregation: `SUM(amount * direction)` gives signed balance. Two columns invite bugs where both are non-zero. |
| Partitioning strategy | Hash by `account_id` | Even write distribution. Time-based partitioning creates hot partitions (all writes go to "today"). Hash distributes evenly. |
| Effective_at vs posted_at | Both stored | `effective_at` = business time (when the charge happened). `posted_at` = system time (when ledger recorded it). Enables backdated entries and accurate financial reporting. |
| Mutable fields | **None** | Append-only. To "undo" a charge, you post a **reversal entry**, not a DELETE. This is non-negotiable for auditability. |

**Staff+ insight**: The difference between `effective_at` and `posted_at` is subtle but critical. During bank reconciliation, the bank says "we settled $X on date D." We need to find all entries where `effective_at` falls on date D, regardless of when we recorded them. Backdated entries (e.g., dispute received 3 days late) use a past `effective_at` but current `posted_at`. Financial reports use `effective_at`; system debugging uses `posted_at`.

### 4.3 — Balance Store (Materialized View)

The ledger is the source of truth, but you can't compute a balance by scanning millions of entries on every API call. The balance store is a **materialized projection** of the ledger.

#### Schema

```sql
CREATE TABLE account_balances (
    account_id        BIGINT PRIMARY KEY,
    currency          CHAR(3) NOT NULL,

    -- Balance breakdown
    available_amount  BIGINT NOT NULL DEFAULT 0,   -- can be paid out NOW
    pending_amount    BIGINT NOT NULL DEFAULT 0,    -- authorized but not captured
    reserved_amount   BIGINT NOT NULL DEFAULT 0,    -- held for disputes/reserves

    -- Optimistic concurrency
    version           BIGINT NOT NULL DEFAULT 0,

    -- Audit
    last_entry_id     BIGINT NOT NULL DEFAULT 0,    -- watermark for CDC
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- The critical invariant:
-- available_amount + pending_amount + reserved_amount =
--   SUM(amount * direction) FROM ledger_entries WHERE account_id = X
```

#### Balance Update Strategy

Two approaches, both valid. The right choice depends on consistency requirements.

**Approach A: Synchronous (in the same transaction as ledger write)**

```
BEGIN;
  INSERT INTO ledger_entries (...) VALUES (...);
  UPDATE account_balances
    SET available_amount = available_amount + delta,
        version = version + 1,
        last_entry_id = new_entry_id
    WHERE account_id = X AND version = expected_version;
  -- OCC check: if 0 rows updated, ROLLBACK and retry
COMMIT;
```

Pros: Balance is always consistent with ledger. Simple.
Cons: Ledger and balance in same DB = same failure domain. Write amplification.

**Approach B: Asynchronous (CDC from ledger to separate balance store)**

```
Ledger DB → CDC (Debezium) → Kafka → Balance Updater → Balance DB
```

Pros: Ledger and balance are decoupled. Each can scale independently.
Cons: Balances are eventually consistent (lag = 50-500ms). Complexity.

**This design: Approach A for balances that gate money movement (payout decisions), Approach B for non-critical balances (dashboard display).**

**Staff+ insight**: Stripe uses the concept of **balance transactions** — each ledger entry creates a corresponding balance transaction that's visible to merchants via the API. This is the public-facing projection of the internal double-entry ledger. The internal ledger has multi-leg entries (debit + credit across accounts); the merchant sees a single-leg view of their account.

### 4.4 — Balance Categories and State Machine

Not all money in an account is immediately available. The balance has states:

```
                      authorize
  [Available] ────────────────────▶ [Pending]
       ▲                                │
       │                     capture    │    void
       │              ┌─────────────────┘    │
       │              ▼                      ▼
       │        [Available]             [Released]
       │              │
       │              │ payout
       │              ▼
       │        [In Transit]
       │              │
       │              │ settled
       │              ▼
       │         [Paid Out]
       │
       │   dispute
       ├──────────────────────▶ [Reserved / Held]
       │                              │
       │          won                 │ lost
       └──────────────────────────────┘
```

Each state transition = a ledger entry (or pair of entries). This is how you model the **lifecycle of a payment** in the ledger:

```
Lifecycle of a $100 charge with 2.9% + $0.30 fee:

1. AUTHORIZE:
   Debit:  merchant_m1_pending        $100.00
   Credit: card_network_auth_hold     $100.00

2. CAPTURE:
   Debit:  card_network_auth_hold     $100.00   (release hold)
   Credit: merchant_m1_pending        $100.00   (release pending)
   Debit:  card_network_receivable    $100.00   (money coming from network)
   Credit: merchant_m1_available       $97.10   (merchant's share)
   Credit: stripe_revenue              $2.90    (Stripe's fee)

3. PAYOUT:
   Debit:  merchant_m1_available       $97.10
   Credit: merchant_m1_in_transit      $97.10

4. SETTLED:
   Debit:  merchant_m1_in_transit      $97.10
   Credit: bank_settlement_account     $97.10
```

**Staff+ insight**: Notice that authorization doesn't actually move money — it records an intent. The pending balance is a **memo entry** that prevents double-spending the same authorized funds. This separation of authorization from settlement is fundamental to card payment ledgers and is often missed by candidates who model it as a simple transfer.

### 4.5 — Sharding Strategy

A single PostgreSQL instance handles ~10-50K writes/sec. We need 100K+. Sharding is mandatory.

#### Shard Key: `account_id`

```
                     ┌─────────────────────┐
                     │   Shard Router       │
                     │  shard = hash(       │
                     │    account_id) % N   │
                     └──────────┬───────────┘
                                │
          ┌─────────────────────┼─────────────────────┐
          ▼                     ▼                      ▼
   ┌──────────────┐    ┌──────────────┐     ┌──────────────┐
   │  Shard 0     │    │  Shard 1     │     │  Shard N     │
   │              │    │              │     │              │
   │ ledger_      │    │ ledger_      │     │ ledger_      │
   │   entries    │    │   entries    │     │   entries    │
   │ account_     │    │ account_     │     │ account_     │
   │   balances   │    │   balances   │     │   balances   │
   │ idempotency_ │    │ idempotency_ │    │ idempotency_ │
   │   keys       │    │   keys       │     │   keys       │
   └──────────────┘    └──────────────┘     └──────────────┘
```

**Why shard by account_id**:
- All entries for one account are co-located → balance computation is shard-local
- Most transactions involve accounts that can be on the same shard (merchant + stripe revenue shard)
- No cross-shard joins needed for the hot path

**The cross-shard transaction problem**:

A payment touches multiple accounts: `card_network_receivable` (shard A) and `merchant_balance` (shard B) and `stripe_revenue` (shard C).

Solutions (ordered by preference):

| Approach | How | Trade-off |
|---|---|---|
| **Shard co-location** | Pin related accounts (merchant + its sub-accounts) to same shard | Doesn't help for global accounts (stripe_revenue) |
| **Account sharding** | Per-merchant accounts on one shard. Global accounts (stripe_revenue) are sharded into N virtual accounts | Best balance of consistency and throughput |
| **Saga / 2-phase approach** | Write to each shard independently with a saga coordinator | Eventual consistency between shards; needs compensation on failure |
| **Distributed transactions (2PC)** | XA transactions across shards | Correct but high latency, poor availability (any shard down = all blocked) |

**This design uses a hybrid**:
1. Per-merchant accounts and their entries are on the same shard (**shard-local transaction**)
2. Global high-contention accounts (stripe_revenue, card_network_receivable) are **virtually sharded**: `stripe_revenue_usd_shard_042` lives on shard 42
3. The double-entry invariant is enforced **per-shard** for shard-local entries and **asynchronously validated** for cross-shard transactions via a reconciliation job

**Staff+ insight**: This is the key trade-off in the entire design. Strict double-entry within a single transaction requires all affected accounts on the same shard (or distributed transactions). At Stripe's scale, distributed transactions are a non-starter for the hot path. The pragmatic solution: enforce the invariant synchronously within a shard, and verify it asynchronously across shards with aggressive reconciliation (every minute). Any cross-shard imbalance triggers an alert within seconds, and automatic corrective entries within minutes.

### 4.6 — Transactional Outbox Pattern

After a ledger write commits, downstream systems need to know. But dual-writes (write to DB + publish to Kafka) are unsafe — the process can crash between the two.

```
┌──────────────────────────────────────────┐
│              SINGLE DB TRANSACTION        │
│                                          │
│  1. INSERT INTO ledger_entries (...)     │
│  2. UPDATE account_balances SET ...      │
│  3. INSERT INTO outbox_events (          │
│       event_id, aggregate_id,           │
│       event_type, payload,              │
│       created_at, published = FALSE     │
│     )                                    │
│                                          │
│  COMMIT                                  │
└──────────────────────────────┬───────────┘
                               │
                               │ CDC / Polling
                               ▼
                    ┌──────────────────────┐
                    │  Outbox Publisher     │
                    │  (Debezium CDC or    │
                    │   polling worker)     │
                    │                      │
                    │  Reads unpublished   │
                    │  events, publishes   │
                    │  to Kafka, marks     │
                    │  published = TRUE    │
                    └──────────┬───────────┘
                               │
                               ▼
                    ┌──────────────────────┐
                    │       Kafka           │
                    │  topic: ledger.events │
                    └──────────────────────┘
```

**Why not just use Kafka as the ledger?**
Kafka is an append-only log, which sounds perfect. But:
1. No transactional balance updates (can't atomically read-modify-write a balance in Kafka)
2. No efficient point queries ("what is account X's balance?")
3. No relational integrity constraints
4. Compaction semantics don't align with accounting requirements

Kafka is the **event bus**, not the **system of record**.

### 4.7 — Reconciliation Engine

The reconciliation engine is what separates a toy ledger from a production financial system. It runs **continuously** and validates multiple levels:

#### Level 1: Internal Consistency (every minute)

```
-- Every transaction must sum to zero
SELECT transaction_id, SUM(amount * direction) as balance
FROM ledger_entries
WHERE posted_at > NOW() - INTERVAL '5 minutes'
GROUP BY transaction_id
HAVING SUM(amount * direction) != 0;

-- Result must ALWAYS be empty. Any rows = P0 incident.
```

#### Level 2: Balance Accuracy (every 5 minutes)

```
-- Recompute balance from ledger entries, compare to materialized balance
SELECT
    a.account_id,
    a.available_amount as materialized,
    COALESCE(SUM(e.amount * e.direction), 0) as computed,
    a.available_amount - COALESCE(SUM(e.amount * e.direction), 0) as drift
FROM account_balances a
LEFT JOIN ledger_entries e ON e.account_id = a.account_id
GROUP BY a.account_id, a.available_amount
HAVING ABS(a.available_amount - COALESCE(SUM(e.amount * e.direction), 0)) > 0;
```

#### Level 3: External Reconciliation (daily / per settlement cycle)

```
Bank settlement file (T+1):
  "We settled $1,234,567.89 to merchant M on 2025-01-15"

Ledger query:
  SELECT SUM(amount) FROM ledger_entries
  WHERE account_id = merchant_M_settlement
  AND effective_at::date = '2025-01-15'
  AND entry_type = 'settlement';

Compare. Differences go to a reconciliation exceptions queue
for manual review by Stripe's finance operations team.
```

#### Level 4: Card Network Reconciliation (daily)

```
Visa/Mastercard send TC33/IPM files with:
  - Each transaction they processed
  - Interchange fees
  - Chargebacks

Match against internal ledger by:
  - ARN (Acquirer Reference Number)
  - Amount + currency
  - Date

Unmatched items → exceptions → investigation
```

**Staff+ insight**: Reconciliation is not a "nice-to-have background job." At Stripe's scale, recon catches bugs that no amount of testing prevents: race conditions, timezone edge cases, currency rounding, network file parsing errors. Stripe likely runs multiple reconciliation loops at different granularities, and the recon system itself is a distributed system with its own SLAs.

### 4.8 — Multi-Currency Handling

```sql
-- FX conversion is a first-class ledger operation
-- Customer pays €85 for a $100 product (merchant's currency is USD)

-- Step 1: Record the EUR charge
Debit:  card_network_receivable_eur    €85.00
Credit: fx_conversion_eur              €85.00

-- Step 2: Record the FX conversion at locked rate (1 EUR = 1.1765 USD)
Debit:  fx_conversion_eur              €85.00
Credit: fx_conversion_usd             $100.00

-- Step 3: Record the USD credit to merchant
Debit:  fx_conversion_usd             $100.00
Credit: merchant_m1_available_usd      $97.10
Credit: stripe_revenue_usd             $2.90

-- FX rate is stored as metadata on the transaction
-- The fx_conversion accounts are transient — they must always net to zero
-- within a transaction. They exist to create an audit trail of the conversion.
```

**Key rules**:
- **Never mix currencies in a single account**. `merchant_m1_usd` and `merchant_m1_eur` are different accounts.
- **All amounts are in minor units** (cents, pence, yen). `$100.00` = `10000`. `¥1000` = `1000` (JPY has no minor unit).
- **FX rate is locked at transaction time** and stored immutably. Never retroactively adjust.
- **FX margin** (the spread Stripe charges) is a separate ledger entry to `stripe_fx_revenue`.

**Staff+ insight**: Currency handling is where most ledger implementations break. The two biggest traps: (1) Using floating point for FX rates (use DECIMAL(20,10) or rational number representation), (2) Not handling zero-decimal currencies (JPY, KRW). JPY amount of 1000 means 1000 yen, not 10.00 yen. Your amount field interpretation depends on the currency.

---

## 5. Data Flow for Key Operations

### 5.1 — Charge (Happy Path)

```
┌──────┐     ┌──────────┐     ┌──────────────┐     ┌──────────┐     ┌───────┐
│Client│────▶│Payments  │────▶│Ledger Write  │────▶│Ledger DB │────▶│ Kafka │
│      │     │API       │     │Service       │     │          │     │       │
└──────┘     └──────────┘     └──────────────┘     └──────────┘     └───┬───┘
                                    │                                    │
                                    │ sync                               │ async
                                    ▼                                    ▼
                              ┌──────────┐                    ┌──────────────┐
                              │Balance   │                    │Reconciler,   │
                              │Store     │                    │Reporting,    │
                              │(updated) │                    │Notifications │
                              └──────────┘                    └──────────────┘
```

Latency budget:
```
API validation:          5ms
Idempotency check:      10ms
Ledger write (DB):      50ms  (includes OCC retry budget)
Balance update:         20ms  (same transaction)
Kafka publish:          15ms  (async via outbox, not on critical path)
────────────────────────────
Total:                 ~85ms p50, ~150ms p99
```

### 5.2 — Refund

```
Original charge created entries:
  Debit:  card_network_receivable    $100.00  (entry_id: 1001)
  Credit: merchant_m1_available       $97.10  (entry_id: 1002)
  Credit: stripe_revenue              $2.90   (entry_id: 1003)

Refund creates REVERSAL entries (not updates/deletes):
  Credit: card_network_receivable    $100.00  (reverses entry 1001)
  Debit:  merchant_m1_available       $97.10  (reverses entry 1002)
  Debit:  stripe_revenue              $2.90   (reverses entry 1003)

Metadata links:  refund_entry.references = [original_entry_id]
```

**Partial refund ($30 of $100)**: Same structure, pro-rated amounts. Fee refund policy is a business decision (Stripe refunds the fee proportionally in some cases).

### 5.3 — Dispute / Chargeback

```
1. DISPUTE OPENED (card network notifies Stripe):
   Debit:  merchant_m1_available        $100.00  (withdraw from merchant)
   Credit: dispute_reserve_merchant_m1   $100.00  (hold in reserve)

2a. DISPUTE WON (merchant wins):
   Debit:  dispute_reserve_merchant_m1   $100.00  (release reserve)
   Credit: merchant_m1_available         $100.00  (return to merchant)

2b. DISPUTE LOST (merchant loses):
   Debit:  dispute_reserve_merchant_m1   $100.00  (release reserve)
   Credit: card_network_payable          $100.00  (pay back to network)
   Debit:  merchant_m1_available          $15.00  (dispute fee)
   Credit: stripe_revenue                 $15.00  (Stripe earns dispute fee)
```

**Staff+ insight**: Disputes are where the ledger design is truly tested. A dispute can happen 120 days after the original charge. The merchant's balance may have already been paid out. If the merchant has insufficient balance, the dispute creates a **negative balance**, and Stripe is on the hook until they recover the funds. The ledger must support negative balances and the associated risk accounting.

---

## 6. Database Technology Choice

### Primary Recommendation: PostgreSQL (Citus / Vitess sharding) for Ledger + Balance

| Criteria | PostgreSQL | TigerBeetle | CockroachDB |
|---|---|---|---|
| ACID transactions | Full | Full (purpose-built) | Full (distributed) |
| Throughput | 50K TPS/node, scales with sharding | 1M+ TPS (single cluster) | 10-30K TPS/node |
| Operational maturity | 25+ years, vast ecosystem | New (2023), limited tooling | Mature, but complex ops |
| Sharding | Manual (Citus) or app-level | Built-in | Built-in |
| Financial-grade correctness | Proven at scale (banks use it) | Designed for it (no floats, no surprises) | Proven |
| Point-in-time recovery | pg_pitr, WAL archiving | Snapshot-based | Built-in |
| Team expertise | Widely available | Rare | Moderate |

**This design: PostgreSQL with Citus for horizontal sharding**

Rationale: Operational maturity matters enormously for a financial system. PostgreSQL's ACID guarantees, tooling ecosystem, and decades of production hardening outweigh the theoretical performance advantages of newer systems. Citus adds transparent sharding while maintaining PostgreSQL semantics.

**Staff+ insight**: At Stripe's actual scale, they likely have a custom-built storage engine optimized for ledger workloads (append-only writes, balance aggregation, immutability). In an interview, PostgreSQL is the right starting point — it demonstrates you understand the requirements before optimizing. Mentioning TigerBeetle shows awareness of the bleeding edge, but recommending it as primary for a trillion-dollar system would raise concerns about operational risk.

---

## 7. Critical Trade-offs Table

| Decision | Option A | Option B | This Design | Why |
|---|---|---|---|---|
| Amount representation | Float/Double | Integer (cents) | **Integer** | Floating point arithmetic is non-deterministic. $0.10 + $0.20 = $0.30000000000000004. Unacceptable. |
| Balance update | Synchronous (same txn) | Async (CDC) | **Sync for gating, async for display** | Payout decisions need real-time accurate balances. Dashboard can tolerate 500ms lag. |
| Cross-shard consistency | Distributed 2PC | Saga + async recon | **Saga + async recon** | 2PC on the hot path at 100K TPS is not feasible. Async recon catches drift within minutes. |
| Ledger mutability | Allow updates for corrections | Append-only + reversal entries | **Append-only** | Auditability is non-negotiable. Every correction must be traceable. |
| Sharding key | transaction_id | account_id | **account_id** | Balance computation must be shard-local. Transaction-level integrity is validated async. |
| Hot account mitigation | Queue + batch | Virtual sharding | **Virtual sharding** | Queuing adds latency. Virtual shards (stripe_revenue_shard_N) maintain sub-ms reads. |
| DB technology | NewSQL (CockroachDB) | Proven RDBMS (PostgreSQL) | **PostgreSQL + Citus** | Operational maturity for financial systems > distributed convenience. |

---

## 8. Failure Modes & Mitigations

### 8.1 — Ledger DB Primary Failure
- **Impact**: All writes stop.
- **Mitigation**: Synchronous replication to standby. Automatic failover via Patroni/Stolon (< 30s). During failover, upstream services retry with idempotency keys — no duplicate entries after recovery. **RPO = 0** (sync replication). **RTO < 30s**.

### 8.2 — OCC Contention Storm (Hot Account)
- **Impact**: Retry loops consume CPU, latency spikes.
- **Mitigation**: Virtual sharding for known hot accounts. Adaptive backoff on retry. Circuit breaker: if retry count > 5, queue the entry for async processing with priority. Monitor per-account contention rate.

### 8.3 — Double-Entry Invariant Violation
- **Impact**: This is a **P0 financial incident**. Money was created or destroyed.
- **Mitigation**: DB-level CHECK constraint on transaction insertion. Application-level validation. Reconciliation job detects within 60 seconds. Automatic alert to on-call + finance team. **Affected transactions are frozen** (no payouts) until investigated. Post-mortem mandatory.
- **Prevention**: The validation `SUM(debits) == SUM(credits)` runs in three places: API layer, write service, and DB constraint. Triple redundancy for the most critical invariant.

### 8.4 — Idempotency Key Collision
- **Impact**: Different operations sharing an idempotency key → one operation silently dropped.
- **Mitigation**: Idempotency key includes operation type + entity ID (e.g., `charge_pay_abc123`), not just a random UUID. On collision detection (same key, different payload), return HTTP 409 Conflict, not a cached response.

### 8.5 — FX Rate Stale During Transaction
- **Impact**: Customer charged at wrong rate.
- **Mitigation**: FX rate locked at intent time (when the payment intent is created, not when it's confirmed). Rate valid for a window (e.g., 10 minutes). If expired, re-quote. The locked rate is stored in the ledger entry metadata — never re-fetched.

### 8.6 — Reconciliation Detects Mismatch
- **Impact**: Internal ledger doesn't match bank/network settlement.
- **Mitigation**: Classify by severity:
  - < $1: Log, auto-reconcile in next cycle (rounding, timing differences)
  - $1 - $1000: Create exception, auto-route to ops team, 24h SLA
  - \> $1000: Immediate alert, freeze affected account payouts, 4h SLA
  - Systematic (many accounts): Circuit break payouts, escalate to engineering

### 8.7 — Region Failure
- **Mitigation**: Active-passive with synchronous replication within region, async across regions. Ledger is too critical for active-active (split-brain = money duplication). Cross-region failover: DNS update + connection drain. RTO < 5 minutes. During failover, the system returns HTTP 503 rather than risk inconsistency.

---

## 9. Observability

### Financial SLIs (not just system SLIs)

| Metric | Alert Threshold | Why |
|---|---|---|
| **Double-entry violations** | > 0 | Existential. Money integrity. |
| **Reconciliation drift** | Any non-zero cross-shard imbalance > 5 min old | Cross-shard saga didn't complete |
| **Balance recomputation delta** | Materialized balance ≠ computed balance for ANY account | Balance store is wrong |
| **Idempotency collision rate** | > 0.01% | Possible client-side key generation bug |
| **OCC retry rate** | > 5% per shard | Hot account or contention problem |
| **Ledger write latency** | p99 > 200ms | DB saturation or lock contention |
| **Payout-to-settlement match rate** | < 99.99% | Bank integration issue |
| **DLQ depth (outbox events)** | > 0 for > 5 min | Downstream consumers failing |

### Audit Trail

Every ledger entry automatically creates an audit record:
- Who initiated (API key, user, system)
- When (posted_at, with microsecond precision)
- What changed (the entry itself is immutable)
- Why (metadata: payment_id, refund_reason, dispute_id)
- IP address, request_id, trace_id

This is not optional — it's a regulatory requirement (SOX Section 302/404, PCI-DSS Req 10).

---

## 10. Evolution Story

### V1 — Single-Region MVP
- Single PostgreSQL instance (no sharding)
- Synchronous balance updates
- Basic reconciliation (Level 1 only)
- Single currency (USD)
- Handles 10K TPS
- **Validates the double-entry model and API contracts**

### V2 — Scale + Multi-Currency
- Citus sharding (64 shards)
- Multi-currency support with FX ledger entries
- Virtual sharding for hot accounts
- All 4 reconciliation levels
- Transactional outbox + Kafka
- Handles 100K TPS

### V3 — Multi-Region + Advanced Features
- Active-passive cross-region deployment
- Point-in-time balance reconstruction API
- Real-time financial reporting pipeline (OLAP replica)
- Sub-ledgers for Stripe products (Connect, Issuing, Treasury)
- Programmable ledger API (let merchants define custom account hierarchies)
- Handles 500K+ TPS across regions

---

## 11. Stripe-Specific Interview Angles

### "How does this support Stripe Connect?"

Connect introduces **multi-party payments** where a platform, connected accounts, and Stripe all take a cut:

```
Customer pays $100 on a marketplace:
  Debit:  card_network_receivable     $100.00
  Credit: seller_account_available     $80.00  (seller gets 80%)
  Credit: platform_account_available   $17.10  (platform gets 20% minus fees)
  Credit: stripe_revenue                $2.90  (Stripe's processing fee)
```

The ledger handles this with the same double-entry model — more legs per transaction, but the invariant holds. Connect accounts are just more leaf nodes in the account hierarchy.

### "How does this support Stripe Issuing?"

Issuing (Stripe's card issuing product) reverses the flow — Stripe's merchants are now the **spenders**, not the receivers:

```
Cardholder spends $50:
  Debit:  cardholder_available         $50.00  (reduce their balance)
  Credit: card_network_payable         $50.00  (Stripe owes the network)
```

Same ledger, opposite direction. The account hierarchy just adds new account types for issued cards.

### "What about Stripe Treasury?"

Treasury turns merchant balances into **actual stored-value accounts** with FDIC insurance (via partner banks). The ledger now needs to track:
- **Held-at-bank** amounts (actual cash at partner bank)
- **Ledger balance** (what Stripe's ledger says)
- **Available vs hold** (just like a checking account)

This is where the ledger evolves from a payments ledger to a **banking ledger**, with additional regulatory requirements (BSA/AML, Reg E).

### "How do you handle clock skew across distributed nodes?"

- `effective_at` is set by the business logic (payment creation time), not the DB node
- `posted_at` uses the DB node's local clock but is only used for system ordering, not financial ordering
- For causal ordering within an account, we use monotonically increasing `entry_id` (sequence), not timestamps
- Hybrid Logical Clocks (HLC) can provide cross-shard causal ordering if needed, but for the ledger, per-account ordering is sufficient

### "Why not blockchain / distributed ledger?"

- Private blockchain adds latency (consensus) without adding trust (Stripe already trusts its own infrastructure)
- The immutable append-only log IS a ledger — that's what we built
- Blockchain's value prop is trustless consensus among adversarial parties. Within Stripe's infrastructure, all parties are trusted.
- Reconciliation + audit trail provides verifiability without the overhead

---

## 12. Common Interviewer Challenges

**"How do you handle a payment that partially fails?"**
> Each step of the payment lifecycle is a separate ledger transaction. If capture fails after authorization, the auth entry remains but the pending balance is eventually released (via an expiry entry after auth timeout). The ledger records reality — including partial states. It doesn't pretend failed operations didn't happen; it records the failure as a new entry.

**"What if two services try to debit the same account simultaneously and the balance would go negative?"**
> OCC prevents this. Both read the current balance and version. The first to commit wins. The second sees a version mismatch, retries, reads the (now lower) balance, and if insufficient, returns an error. For accounts that MUST allow negative balances (e.g., dispute debits), the balance check is skipped and negative balances are permitted — these are tracked separately for risk management.

**"How do you support point-in-time balance queries?"**
> `SELECT SUM(amount * direction) FROM ledger_entries WHERE account_id = X AND effective_at <= '2025-01-15T00:00:00Z'`. This is expensive for accounts with millions of entries. Optimization: periodic balance snapshots. Store `(account_id, snapshot_at, balance)` daily. Point-in-time query becomes: last snapshot before target time + SUM of entries between snapshot and target time. Reduces scan from millions to thousands.

**"How does this scale to handle Black Friday traffic (10x spike)?"**
> Three layers of absorption: (1) API gateway rate limiting prevents unbounded growth. (2) The ledger write service auto-scales horizontally (stateless, shard-aware routing). (3) Each DB shard handles its own partition of accounts independently. A 10x spike on one merchant doesn't affect other merchants (different shards). Global spikes: pre-scale shards based on historical patterns, add read replicas for balance queries, and temporarily increase the OCC retry budget.

**"Why PostgreSQL and not a purpose-built ledger database?"**
> Three reasons: (1) Operational maturity — PostgreSQL's failure modes are well-understood after 25 years. A financial system's worst day is an unknown failure mode. (2) Ecosystem — monitoring, backup, replication, tooling are battle-tested. (3) Talent — every engineer can debug PostgreSQL. That said, as the system matures and the team builds domain expertise, migrating critical hot-path components to a purpose-built engine (like TigerBeetle or a custom storage layer) is a reasonable V3/V4 evolution.

**"How do you test a ledger system?"**
> (1) Property-based testing: generate random sequences of operations, assert double-entry invariant holds after every operation. (2) Chaos testing: inject DB failures, network partitions, clock skew — verify no balance corruption. (3) Shadow mode: run new ledger version in parallel with production, compare outputs for every operation. (4) Reconciliation as continuous testing: the recon engine is not just operational — it's the ultimate integration test running 24/7 on real data.
