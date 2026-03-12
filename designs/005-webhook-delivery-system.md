# Gold Standard: Webhook Delivery System (Stripe Sr. Staff)

## 0. Why This Matters at Stripe

Webhooks are **Stripe's primary mechanism for asynchronous communication** with merchants. Every payment, refund, dispute, subscription change, and payout generates webhook events that merchants rely on to run their businesses — updating order status, fulfilling products, sending receipts, triggering downstream workflows.

At Stripe's scale:
- **Reliability is revenue**: A missed `payment_intent.succeeded` webhook means a merchant doesn't fulfill an order. Customers complain. Merchants churn.
- **At-least-once is mandatory**: Merchants build critical business logic on webhooks. Missing one is unacceptable. Duplicating one is tolerable (merchants must be idempotent).
- **Latency matters**: A 30-second webhook delay means a customer stares at a "processing" screen. A 5-minute delay means support tickets.
- **The long tail is brutal**: 99% of endpoints are healthy. The 1% that are down, slow, or misconfigured consume 90% of system resources through retries.

This is not a simple HTTP POST system — it's a **distributed, fault-tolerant, at-least-once delivery pipeline** that must handle millions of deliveries per second while being resilient to arbitrarily misbehaving consumer endpoints.

---

## 1. Scope & Requirements

### Functional

| Requirement | Detail |
|---|---|
| **Event ingestion** | Accept events from internal services (Payments, Billing, Connect, etc.) |
| **Fan-out** | One event → multiple endpoint subscriptions (merchant may have N endpoints) |
| **Reliable delivery** | At-least-once delivery with automatic retries on failure |
| **Retry with backoff** | Exponential backoff over hours/days for failed deliveries |
| **Cryptographic signing** | HMAC-SHA256 signature on every payload so merchants can verify authenticity |
| **Event filtering** | Merchants subscribe to specific event types per endpoint |
| **Manual retry / replay** | Merchants can trigger re-delivery of past events via Dashboard/API |
| **Endpoint management** | CRUD for webhook endpoints, including URL, event filters, API version |
| **Delivery logs** | Full history of delivery attempts, status codes, latencies per event |
| **API versioning** | Event payloads rendered in the merchant's pinned API version |
| **Ordering** | Best-effort ordering per object (not strict — at-least-once breaks strict ordering) |

### Non-Functional

| Dimension | Target |
|---|---|
| **Throughput** | 2-5M deliveries/sec peak (after fan-out) |
| **Ingestion rate** | ~500K events/sec peak |
| **First-attempt latency** | < 5s p99 from event creation to first HTTP POST |
| **Retry duration** | Up to 72 hours with exponential backoff |
| **Availability** | 99.99% for event ingestion (we never lose an event) |
| **Delivery success rate** | > 99.5% within first attempt; > 99.95% within retry window |
| **Event retention** | 30 days (queryable for replay) |
| **Durability** | Zero event loss after acknowledgment |

### Out of Scope
- Webhook consumption (merchant's responsibility)
- Transformation / mapping of payloads (Stripe sends a fixed schema per API version)
- Real-time streaming (WebSockets, SSE) — separate system

---

## 2. Capacity Estimation

```
Stripe processes ~$1T/year → ~10B payments/year
Each payment lifecycle generates ~4 webhook events:
  payment_intent.created, charge.succeeded,
  payment_intent.succeeded, balance.available
Plus subscriptions, invoices, disputes, payouts → ~3x multiplier

Total events: ~120B events/year → ~4K events/sec avg, ~500K/sec peak

Average endpoints per merchant: ~1.5 (some have 5-10 for different environments)
Active webhook endpoints: ~10M
Fan-out ratio: avg ~1.8 deliveries per event

Deliveries: ~220B/year → ~7K/sec avg, ~1M/sec peak
With retries (assuming 3% failure rate, avg 3 retries): ~1.2M/sec peak

Payload size: ~2KB average (JSON event object)
Bandwidth: 1.2M/sec × 2KB = ~2.4 GB/sec outbound

Storage (30-day retention):
  Events: 500K/sec × 86400 × 30 × 2KB ≈ 2.6 PB raw
  Delivery attempts: ~3KB per attempt (headers, response, timing)
  → ~4 PB total for 30 days (compressed: ~800 TB)

Connection overhead:
  1.2M deliveries/sec to ~10M unique endpoints
  Average endpoint receives ~10 events/sec
  Need efficient connection pooling — can't open 10M concurrent TCP connections
```

**Key insight**: This is an **outbound-heavy** system. The bottleneck is not internal processing — it's the network I/O and the behavior of millions of external endpoints you don't control.

---

## 3. API Design

### External APIs (Merchant-facing)

```
# Endpoint Management
POST   /v1/webhook_endpoints
  { url, enabled_events[], api_version, description, metadata }
  → { id: "we_xxx", secret: "whsec_xxx", ... }

GET    /v1/webhook_endpoints/:id
PATCH  /v1/webhook_endpoints/:id
DELETE /v1/webhook_endpoints/:id
GET    /v1/webhook_endpoints          # list all

# Event Browsing
GET    /v1/events                     # list events (paginated, filterable)
GET    /v1/events/:id                 # get single event with delivery attempts

# Manual Retry
POST   /v1/webhook_endpoints/:id/retry
  { event_id }                        # re-deliver a specific event
```

### Internal APIs (Service-to-service)

```
# Event Ingestion (from Payments, Billing, Connect, etc.)
POST   /internal/events
  {
    type: "payment_intent.succeeded",
    api_version: "2025-01-15",
    account_id: "acct_xxx",
    object_id: "pi_xxx",             # for ordering
    data: { ... },                    # the event payload
    idempotency_key: "evt_xxx",      # deduplicate
    created_at: 1705312200
  }
  → { event_id: "evt_xxx" }

# Health / Operational
GET    /internal/endpoints/:id/health  # circuit breaker state
POST   /internal/endpoints/:id/disable # auto-disable unhealthy endpoint
```

### Webhook Payload (what merchants receive)

```http
POST https://merchant.com/webhooks HTTP/1.1
Content-Type: application/json
Stripe-Signature: t=1705312200,v1=5257a869e7ecebeda32affa62cdca3fa51cad7e77a0e56ff536d0ce8e108d8bd
User-Agent: Stripe/1.0 (+https://stripe.com/docs/webhooks)
Stripe-Webhook-Id: evt_xxx
Stripe-Webhook-Timestamp: 1705312200

{
  "id": "evt_1ABC",
  "object": "event",
  "api_version": "2025-01-15",
  "type": "payment_intent.succeeded",
  "created": 1705312200,
  "data": {
    "object": {
      "id": "pi_xxx",
      "object": "payment_intent",
      "amount": 10000,
      "currency": "usd",
      "status": "succeeded",
      ...
    }
  },
  "livemode": true
}
```

**Signature verification**:
```
signed_payload = timestamp + "." + json_body
expected_sig  = HMAC-SHA256(endpoint_secret, signed_payload)
# Merchant compares expected_sig with v1= value in Stripe-Signature header
# Timestamp prevents replay attacks (reject if > 5 min old)
```

**Staff+ insight**: The `api_version` field is critical. Stripe evolves its API and event schemas over time. When rendering an event payload, the system must serialize the object using the **merchant's pinned API version**, not the latest version. This means the event store stores the canonical internal representation, and a versioned serializer renders it at delivery time. This is a subtle but significant architectural requirement.

---

## 4. Data Model

### Core Entities

```sql
-- Events: the source of truth for what happened
CREATE TABLE events (
    event_id        VARCHAR(64) PRIMARY KEY,    -- "evt_xxx"
    account_id      VARCHAR(64) NOT NULL,       -- merchant account
    type            VARCHAR(255) NOT NULL,      -- "payment_intent.succeeded"
    object_id       VARCHAR(64),                -- "pi_xxx" for ordering
    api_version     VARCHAR(16) NOT NULL,       -- "2025-01-15"
    data            JSONB NOT NULL,             -- canonical internal representation
    created_at      BIGINT NOT NULL,            -- unix timestamp
    idempotency_key VARCHAR(255) NOT NULL,

    INDEX idx_events_account_type (account_id, type, created_at DESC),
    INDEX idx_events_object (object_id, created_at)
) PARTITION BY HASH (account_id);

-- Webhook Endpoints: where to deliver
CREATE TABLE webhook_endpoints (
    endpoint_id     VARCHAR(64) PRIMARY KEY,    -- "we_xxx"
    account_id      VARCHAR(64) NOT NULL,
    url             VARCHAR(2048) NOT NULL,
    secret          VARCHAR(255) NOT NULL,      -- "whsec_xxx" for HMAC signing
    enabled_events  TEXT[] NOT NULL,            -- ["payment_intent.*", "charge.succeeded"]
    api_version     VARCHAR(16),               -- override; NULL = use account default
    status          VARCHAR(16) NOT NULL DEFAULT 'enabled',  -- enabled/disabled
    created_at      BIGINT NOT NULL,
    metadata        JSONB,

    INDEX idx_endpoints_account (account_id)
);

-- Delivery Attempts: full audit trail
CREATE TABLE delivery_attempts (
    attempt_id      BIGINT GENERATED ALWAYS AS IDENTITY,
    event_id        VARCHAR(64) NOT NULL,
    endpoint_id     VARCHAR(64) NOT NULL,
    attempt_number  SMALLINT NOT NULL,          -- 1, 2, 3, ...
    status          VARCHAR(16) NOT NULL,       -- pending/success/failed/skipped
    http_status     SMALLINT,                   -- 200, 500, NULL if timeout
    response_body   TEXT,                       -- first 1KB of response (for debugging)
    latency_ms      INT,
    error_message   TEXT,                       -- timeout, connection_refused, etc.
    created_at      BIGINT NOT NULL,
    next_retry_at   BIGINT,                     -- NULL if terminal

    -- Composite PK IS the idempotency key for delivery attempts.
    -- No separate idempotency_key needed: (event_id, endpoint_id, attempt_number)
    -- uniquely identifies every attempt. If a worker crashes after HTTP POST
    -- but before recording, the retry inserts the same PK → duplicate rejected.
    PRIMARY KEY (event_id, endpoint_id, attempt_number),
    INDEX idx_delivery_pending (status, next_retry_at)
        WHERE status = 'pending'               -- partial index for retry picker
) PARTITION BY RANGE (created_at);             -- daily partitions for TTL

-- Endpoint Health: circuit breaker state
CREATE TABLE endpoint_health (
    endpoint_id         VARCHAR(64) PRIMARY KEY,
    consecutive_failures INT NOT NULL DEFAULT 0,
    circuit_state       VARCHAR(16) NOT NULL DEFAULT 'closed',  -- closed/open/half_open
    last_success_at     BIGINT,
    last_failure_at     BIGINT,
    failure_rate_1h     REAL,                   -- rolling 1-hour failure rate
    disabled_at         BIGINT,                 -- NULL if active
    disabled_reason     TEXT
);
```

### Delivery State Machine

```
                         ┌──────────┐
              event      │          │
            created ────▶│ PENDING  │
                         │          │
                         └────┬─────┘
                              │
                      first delivery
                        attempt
                              │
                  ┌───────────┼───────────┐
                  ▼                        ▼
           ┌────────────┐          ┌────────────┐
           │ SUCCEEDED  │          │  FAILED    │
           │            │          │ (attempt)  │
           │ HTTP 2xx   │          │            │
           └────────────┘          └──────┬─────┘
                                          │
                                   retry? │
                              ┌───────────┤
                              ▼           ▼
                       ┌────────────┐  ┌──────────┐
                       │  PENDING   │  │ EXHAUSTED │
                       │ (retry N)  │  │ (max      │
                       │            │  │  retries) │
                       └────────────┘  └──────────┘
                                          │
                                     endpoint
                                    disabled?
                                          │
                                   ┌──────┴──────┐
                                   ▼              ▼
                            ┌──────────┐  ┌───────────┐
                            │ DEAD     │  │ ENDPOINT  │
                            │ LETTERED │  │ DISABLED  │
                            └──────────┘  └───────────┘
```

---

## 5. High-Level Architecture

```
  ┌──────────────────────────────────────────────────────────────────────────┐
  │                     INTERNAL STRIPE SERVICES                             │
  │         (Payments, Billing, Connect, Issuing, Identity, ...)            │
  └───────────────────────────────┬──────────────────────────────────────────┘
                                  │ Internal event publish
                                  │ (idempotency_key, type, data)
                                  ▼
  ┌──────────────────────────────────────────────────────────────────────────┐
  │                        EVENT INGESTION SERVICE                           │
  │  - Idempotent event creation (dedup by idempotency_key)                 │
  │  - Validate schema, enrich metadata                                     │
  │  - Write to Event Store                                                 │
  │  - Publish to Fan-out Queue                                             │
  └────────────────┬─────────────────────────────┬──────────────────────────┘
                   │                             │
                   ▼                             ▼
  ┌─────────────────────────┐     ┌──────────────────────────────────────┐
  │     EVENT STORE          │     │          FAN-OUT QUEUE               │
  │  (PostgreSQL / Scylla)   │     │  (Kafka — partitioned by            │
  │                          │     │   account_id for ordering)           │
  │  - Source of truth       │     │                                      │
  │  - 30-day retention      │     │  - Durable, replayable              │
  │  - Query for Dashboard   │     │  - Partitioned for parallelism      │
  └─────────────────────────┘     └──────────────────┬───────────────────┘
                                                      │
                                                      ▼
                                  ┌──────────────────────────────────────┐
                                  │          FAN-OUT SERVICE              │
                                  │                                      │
                                  │  - Read event from Kafka             │
                                  │  - Lookup matching endpoints         │
                                  │    (account_id + event type filter)  │
                                  │  - Create delivery task per endpoint │
                                  │  - Push to Delivery Queue            │
                                  └──────────────────┬───────────────────┘
                                                      │
                                                      ▼
                                  ┌──────────────────────────────────────┐
                                  │         DELIVERY QUEUE               │
                                  │  (Kafka / SQS — partitioned by      │
                                  │   endpoint_id for per-endpoint       │
                                  │   ordering & rate limiting)          │
                                  └──────────────────┬───────────────────┘
                                                      │
                                                      ▼
  ┌──────────────────────────────────────────────────────────────────────────┐
  │                       DELIVERY WORKER FLEET                              │
  │                                                                          │
  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐    │
  │  │  Worker 1   │  │  Worker 2   │  │  Worker 3   │  │  Worker N   │    │
  │  │             │  │             │  │             │  │             │    │
  │  │ - Render    │  │ - Render    │  │ - Render    │  │ - Render    │    │
  │  │   payload   │  │   payload   │  │   payload   │  │   payload   │    │
  │  │   (version) │  │   (version) │  │   (version) │  │   (version) │    │
  │  │ - Sign      │  │ - Sign      │  │ - Sign      │  │ - Sign      │    │
  │  │   (HMAC)    │  │   (HMAC)    │  │   (HMAC)    │  │   (HMAC)    │    │
  │  │ - HTTP POST │  │ - HTTP POST │  │ - HTTP POST │  │ - HTTP POST │    │
  │  │ - Record    │  │ - Record    │  │ - Record    │  │ - Record    │    │
  │  │   attempt   │  │   attempt   │  │   attempt   │  │   attempt   │    │
  │  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘    │
  │                                                                          │
  │  Per-worker: Connection Pool (TCP connections are process-local)          │
  │  Shared via Redis: Circuit Breaker state (survives partition rebalance)  │
  └───────────────────────────────┬──────────────────────────────────────────┘
                                  │
                      ┌───────────┼──────────────┐
                      ▼           ▼              ▼
               ┌───────────┐ ┌──────────┐ ┌───────────────┐
               │ Delivery  │ │  Retry   │ │  Endpoint     │
               │ Attempt   │ │  Queue   │ │  Health       │
               │ Log Store │ │(delayed) │ │  Tracker      │
               └───────────┘ └──────────┘ └───────────────┘
                                  │
                                  │ scheduled retry
                                  ▼
                        (back to Delivery Workers)
```

---

## 6. Component Deep Dive

### 6.1 — Event Ingestion Service

The entry point for all webhook events. Every internal Stripe service that generates customer-visible state changes publishes through this.

```
Request flow:

1. Receive event from internal service:
   {
     idempotency_key: "evt_pi_xxx_succeeded",
     type: "payment_intent.succeeded",
     account_id: "acct_abc",
     object_id: "pi_xxx",
     data: { <canonical internal object> }
   }

2. Idempotency check:
   - Lookup idempotency_key in event store
   - If exists → return existing event_id (no re-processing)
   - If not → proceed

3. Persist event to Event Store (durable — this is the commit point)

4. Publish to Fan-out Kafka topic:
   - Partition key = account_id (preserves per-account ordering)
   - Message = { event_id, account_id, type }
   - Uses transactional outbox pattern (same DB txn as event insert)

5. Return event_id to caller
```

**Why transactional outbox here**: The event store write and Kafka publish must be atomic. If we crash between the two, the event exists but never gets delivered. The outbox pattern (insert event + outbox row in same transaction, separate publisher reads outbox) eliminates this gap.

**Staff+ insight**: The ingestion service should be **extremely simple and fast**. Its only job is to durably accept the event. All the complexity (fan-out, rendering, delivery, retries) happens downstream. This separation means ingestion never slows down even when delivery is backed up — events keep accumulating safely.

### 6.2 — Fan-out Service

Determines which endpoints should receive each event.

```
For each event consumed from Kafka:

1. Lookup account's webhook endpoints:
   SELECT endpoint_id, url, secret, enabled_events, api_version, status
   FROM webhook_endpoints
   WHERE account_id = :account_id AND status = 'enabled'
   CACHE: 60s TTL, invalidated on endpoint CRUD

2. Filter by event type:
   For each endpoint:
     if event.type matches any pattern in endpoint.enabled_events:
       → create delivery task

   Pattern matching:
     "payment_intent.succeeded"  → exact match
     "payment_intent.*"          → wildcard suffix match
     "*"                         → all events

3. Create delivery tasks:
   For each matched endpoint:
     Produce to Delivery Queue:
       partition_key = endpoint_id  (per-endpoint ordering)
       message = {
         event_id, endpoint_id, url, secret,
         api_version, attempt_number: 1
       }

4. Record delivery intent in delivery_attempts table (status = 'pending')
```

**Why partition delivery queue by endpoint_id**:
- Ensures events for the same endpoint are delivered in order (within a partition)
- Enables per-endpoint rate limiting (one consumer handles all deliveries for a set of endpoints)
- Prevents a slow endpoint from blocking deliveries to other endpoints

**Staff+ insight**: Fan-out is a **read-heavy, latency-sensitive** operation. Endpoint lookups must be cached aggressively. The cache invalidation path (endpoint created/updated/deleted) must be fast — typically via a separate Kafka topic that the fan-out service subscribes to. Stale cache = delivering to deleted endpoints (wasted work) or missing new endpoints (missed events). A 60s TTL with explicit invalidation is a reasonable balance.

### 6.3 — Delivery Workers

The heart of the system. These make the actual HTTP POST calls to merchant endpoints.

```
Delivery Worker loop:

1. Consume delivery task from Delivery Queue

2. Check circuit breaker for endpoint:
   if circuit_state == OPEN:
     → schedule retry at circuit_reopen_time
     → skip delivery (don't waste resources on a known-dead endpoint)
     → continue

3. Render payload:
   - Fetch canonical event data from Event Store
   - Serialize using merchant's pinned api_version
   - This is where API version transformation happens:
     v2024-01-01 might have field "amount" as integer
     v2025-01-15 might have field "amount" as object {value, currency}

4. Sign payload:
   timestamp = current_unix_time
   signed_payload = f"{timestamp}.{json_body}"
   signature = HMAC-SHA256(endpoint_secret, signed_payload)
   header = f"t={timestamp},v1={signature}"

5. HTTP POST with strict timeouts:
   - Connect timeout: 5s
   - Read timeout: 15s
   - Total timeout: 20s
   - No redirects followed (security: prevents SSRF)
   - No response body beyond 1KB read (prevent resource exhaustion)

6. Record attempt:
   INSERT INTO delivery_attempts (
     event_id, endpoint_id, attempt_number,
     status, http_status, latency_ms, response_body
   )

7. Handle response:
   HTTP 2xx → SUCCESS → done
   HTTP 410 → endpoint explicitly gone → auto-disable endpoint
   HTTP 4xx → FAILED → retry (may be transient misconfiguration)
   HTTP 5xx → FAILED → retry
   Timeout  → FAILED → retry
   Conn err → FAILED → retry + update circuit breaker
```

#### Connection Pool Management

```
Problem: 1M deliveries/sec to 10M unique endpoints.
         Cannot maintain 10M persistent connections.

Solution: Tiered connection management

Tier 1 — Hot endpoints (>10 events/min): ~100K endpoints
  → Persistent HTTP/2 connections in connection pool
  → Multiplexed requests over single TCP connection
  → Connection keepalive = 5 minutes

Tier 2 — Warm endpoints (1-10 events/min): ~1M endpoints
  → Connection pooled with shorter keepalive (30s)
  → Reuse connections when available, create on demand

Tier 3 — Cold endpoints (<1 event/min): ~9M endpoints
  → Fresh connection per delivery
  → Most endpoints fall here (long-tail merchants)

Workers share a connection pool sharded by endpoint domain.
Each worker host manages connections for its assigned endpoint partition.
```

**Staff+ insight**: The connection pool is one of the most underestimated components. At Stripe's scale, DNS resolution alone for 10M unique domains is a significant load. Workers should cache DNS results (respecting TTL) and use async DNS resolution. TLS handshake cost (~50ms) dominates cold-start delivery latency — this is why persistent connections for hot endpoints matter enormously.

### 6.4 — Retry Engine

The retry strategy is the difference between a toy system and a production webhook platform.

```
Retry Schedule (exponential backoff with jitter):

  Attempt │ Base Delay     │ With Jitter (±25%)
  ────────┼────────────────┼─────────────────────
     1    │ immediate      │ 0s (first attempt)
     2    │ 1 minute       │ 45s - 75s
     3    │ 5 minutes      │ 3m 45s - 6m 15s
     4    │ 30 minutes     │ 22m - 37m
     5    │ 2 hours        │ 1h 30m - 2h 30m
     6    │ 5 hours        │ 3h 45m - 6h 15m
     7    │ 12 hours       │ 9h - 15h
     8    │ 24 hours       │ 18h - 30h
  ────────┴────────────────┴─────────────────────
  Total window: ~72 hours

  After attempt 8: mark as EXHAUSTED, stop retrying.
  Merchant can manually retry via API/Dashboard after fixing their endpoint.
```

**Why jitter**: Without jitter, when a merchant's endpoint recovers from a 2-hour outage, ALL queued retries fire simultaneously (thundering herd). Jitter spreads the load over a window, giving the recovering endpoint time to warm up.

#### Retry Queue Implementation

```
Option A: Kafka with delayed consumption
  - Produce retry messages with a "deliver_after" timestamp
  - Consumer polls, skips messages where deliver_after > now
  - Problem: Kafka is not designed for delayed delivery, wastes poll cycles

Option B: Scheduled job table (PostgreSQL)
  - INSERT INTO retry_queue (event_id, endpoint_id, deliver_at)
  - Polling worker: SELECT ... WHERE deliver_at <= NOW() ORDER BY deliver_at LIMIT 1000
  - Problem: polling overhead, index pressure at scale

Option C: SQS with delayed message (AWS) / Redis sorted sets ← This design
  - Redis ZSET: score = deliver_at timestamp, member = delivery_task_json
  - Worker: ZRANGEBYSCORE retry_queue -inf <now> LIMIT 1000
  - Atomic: ZPOPMIN avoids double-processing
  - For durability: Redis Cluster with AOF + Kafka backup

  Alternatively: Amazon SQS with message delay (up to 15 min)
  + SQS dead letter queue for messages exceeding delay
  For delays > 15 min: re-enqueue with updated delay iteratively,
  or use a scheduling table for long delays (hours-scale).
```

**This design: Hybrid approach**
- Short retries (< 15 min): SQS with message delay / Redis sorted sets
- Long retries (> 15 min): Scheduling table polled every minute
- Rationale: short retries need low-latency dequeue; long retries are less time-sensitive

### 6.5 — Circuit Breaker (Endpoint Health Tracker)

Protects the system from wasting resources on consistently failing endpoints.

```
                    ┌────────────────────────────────────┐
                    │         CIRCUIT BREAKER FSM         │
                    │         (per endpoint_id)           │
                    └────────────────────────────────────┘

                              success
              ┌──────────────────────────────────────┐
              │                                      │
              ▼                                      │
       ┌────────────┐     5 consecutive      ┌──────┴─────┐
       │   CLOSED   │──── failures ─────────▶│   OPEN     │
       │ (healthy)  │                        │ (no sends) │
       │            │                        │            │
       │ All events │                        │ Retries    │
       │ delivered  │     success            │ queued,    │
       │ normally   │◀────────────────┐      │ not sent   │
       └────────────┘                 │      └──────┬─────┘
                                      │             │
                                      │     after 5 min cooldown
                                      │             │
                                ┌─────┴──────┐      │
                                │ HALF-OPEN  │◀─────┘
                                │            │
                                │ Allow 1    │
                                │ test req   │──── failure ──▶ back to OPEN
                                └────────────┘                 (reset cooldown)
```

**Escalation ladder**:
1. **5 consecutive failures** → Circuit OPEN (5 min cooldown)
2. **OPEN for 1 hour** (repeated half-open failures) → Reduce retry frequency
3. **OPEN for 24 hours** → Send email notification to merchant
4. **OPEN for 72 hours** → Auto-disable endpoint, email merchant, stop retries
5. **Merchant re-enables** → Reset circuit, replay failed events from last 72h

**Why circuit breaker state is in Redis, not in-process**:
The delivery queue is partitioned by `endpoint_id`, so at any moment one worker owns a given endpoint's traffic. But Kafka rebalances happen (deploy, crash, scale-out). When partition P moves from Worker A to Worker B, Worker B needs to know the circuit is already open — otherwise it wastes attempts re-discovering a dead endpoint. Redis gives sub-ms reads and survives worker restarts. The `endpoint_health` table in PostgreSQL is the durable backing store; Redis is the hot cache workers read on every delivery attempt.

**Staff+ insight**: The circuit breaker must be **per-endpoint, not per-domain**. A merchant might have `https://api.merchant.com/webhooks/live` (healthy) and `https://api.merchant.com/webhooks/test` (broken). Breaking the circuit for the whole domain would be wrong. However, we do track per-domain connection errors (DNS failure, TLS errors) separately since those affect all endpoints on that domain.

### 6.6 — Payload Rendering & API Versioning

```
Event Store holds canonical internal representation:
{
  "object": "payment_intent",
  "id": "pi_xxx",
  "amount_cents": 10000,           // internal: always cents
  "currency_code": "usd",         // internal: always lowercase
  "status_internal": "succeeded",  // internal enum
  "charges": [...],                // full internal charge objects
  ...
}

API Version v2024-01-01 renders as:
{
  "object": "payment_intent",
  "id": "pi_xxx",
  "amount": 10000,                 // cents as integer
  "currency": "usd",
  "status": "succeeded",
  "charges": { "data": [...] }     // wrapped in list object
}

API Version v2025-06-01 renders as:
{
  "object": "payment_intent",
  "id": "pi_xxx",
  "amount": { "value": 10000, "currency": "usd" },  // structured amount
  "status": "succeeded",
  "latest_charge": { ... },        // renamed field, single object
  "charges": { "data": [...] }     // still present for compat
}

Implementation:
  VersionedSerializer.render(event.data, api_version)
  → Chain of version transformers applied in order
  → Each version defines additions, renames, removals
  → Transformations are composable and tested per version pair
```

---

## 7. Ordering & Consistency

### The Ordering Problem

Merchants expect events in logical order:
```
payment_intent.created → charge.succeeded → payment_intent.succeeded
```

But distributed systems don't guarantee this:
- Different internal services emit these events independently
- Kafka partitioning ensures per-account ordering, but events originate from different services
- Network delays can reorder delivery even with queue ordering

### Ordering Strategy

```
Level 1: Per-object ordering (best-effort)
  - Events for the same object_id (e.g., pi_xxx) are ordered by created_at
  - Fan-out queue partitioned by account_id (coarse ordering)
  - Delivery queue partitioned by endpoint_id
  - Within a partition, events for the same object_id maintain FIFO order

Level 2: Sequence numbers
  - Each event includes a per-object sequence number
  - payment_intent.created  → sequence: 1
  - charge.succeeded        → sequence: 2
  - payment_intent.succeeded → sequence: 3
  - Merchants can detect out-of-order delivery and re-query the API

Level 3: NOT guaranteed across objects
  - Events for pi_xxx and pi_yyy have no ordering guarantee
  - This is fine — they're independent business operations
```

### The "New Enemy" Consistency Problem

```
Scenario:
  T=0: User has access to resource (ACL allows)
  T=1: Admin revokes access
  T=2: Webhook for T=0 action delivered
  T=3: Webhook for T=1 revocation delivered

If merchant processes T=2 webhook after seeing T=3:
  → They might re-grant access that was revoked

Solution for webhook systems:
  - Include object version/updated_at in every webhook payload
  - Document: "always fetch the latest object state via API if ordering matters"
  - This is why Stripe's docs say: "use webhooks as triggers, not as source of truth"
```

**Staff+ insight**: Strict ordering in a distributed webhook system is effectively impossible without sacrificing availability and latency. The correct design is to make ordering **best-effort** and give merchants the tools to handle out-of-order delivery: sequence numbers, `created_at` timestamps, and the advice to re-fetch via the API. Attempting strict ordering (e.g., holding back events until predecessors are delivered) creates head-of-line blocking and dramatically reduces throughput.

---

## 8. Security

### SSRF Prevention

Webhook delivery means Stripe's infrastructure makes HTTP requests to **arbitrary user-provided URLs**. This is a massive SSRF (Server-Side Request Forgery) attack surface.

```
Mitigations:

1. URL validation on endpoint creation:
   - Must be HTTPS (no HTTP, no other schemes)
   - Must be a public IP (block RFC 1918, link-local, loopback)
   - Must not resolve to internal Stripe IPs
   - Must not be a cloud metadata endpoint (169.254.169.254)

2. DNS resolution validation at delivery time:
   - Re-validate resolved IP is not internal/private
   - DNS rebinding protection: resolve DNS ONCE, use that IP for the connection
   - Don't follow redirects (redirect to internal IP = bypass)

3. Network isolation:
   - Delivery workers run in a dedicated VPC/network segment
   - NO access to internal Stripe services (separate from the service mesh)
   - Egress-only: can reach the internet, cannot reach internal APIs
   - Separate IAM roles with minimal permissions

4. Rate limiting per endpoint:
   - Max 10K deliveries/hour per endpoint (prevents abuse)
   - Merchant can request higher limits

5. Response handling:
   - Read max 1KB of response body (prevent memory exhaustion)
   - Timeout aggressively (20s max)
   - Don't parse response body (irrelevant — only status code matters)
```

**Staff+ insight**: The delivery worker fleet should be treated as a **hostile execution environment** from an internal security perspective. Even though it's Stripe's own infrastructure, it processes untrusted URLs and handles responses from arbitrary servers. Network segmentation is non-negotiable — a compromised delivery worker must not be able to reach internal APIs, databases, or secrets beyond what it needs for delivery.

---

## 9. Scalability Deep Dive

### Horizontal Scaling Strategy

```
Component            │ Scaling Axis              │ Bottleneck
─────────────────────┼───────────────────────────┼──────────────────────
Event Ingestion      │ Stateless, scale by CPU   │ DB write throughput
Fan-out Service      │ Scale by Kafka partitions │ Endpoint lookup cache
Delivery Workers     │ Scale by endpoint volume  │ Outbound network I/O
Retry Queue          │ Scale by pending retries  │ Redis memory / polling
Event Store          │ Sharded by account_id     │ Storage capacity
Delivery Log Store   │ Time-partitioned          │ Write throughput
```

### Handling the Thundering Herd

```
Scenario: A major platform (Shopify, with 1M+ merchants) has a single
webhook endpoint that receives events for all their sub-merchants.

Problem:
  - One endpoint receives 100K+ events/sec
  - Single TCP connection can handle ~1K requests/sec
  - Need 100 parallel connections to one endpoint

Solution: Per-endpoint adaptive concurrency
  - Start with concurrency = 5 parallel in-flight requests
  - If all succeed < 1s: increase concurrency (additive increase)
  - If failures or slow responses: decrease concurrency (multiplicative decrease)
  - Cap at configurable max (default: 50, enterprise: 500)
  - This is essentially TCP AIMD congestion control, applied to webhook delivery

  ┌─────────────────────────────────────────────┐
  │        Adaptive Concurrency Controller       │
  │                                              │
  │  current_concurrency = 5                     │
  │                                              │
  │  on_success(latency):                        │
  │    if latency < target_latency:              │
  │      current_concurrency += 1  (additive)    │
  │    current_concurrency = min(current, max)   │
  │                                              │
  │  on_failure():                               │
  │    current_concurrency *= 0.5 (multiplicat.) │
  │    current_concurrency = max(current, 1)     │
  │                                              │
  └─────────────────────────────────────────────┘
```

### Backpressure

```
When downstream can't keep up:

1. Delivery Queue depth > threshold
   → Stop consuming from Fan-out Queue (Kafka consumer pause)
   → Events buffer in Kafka (durable, no data loss)
   → Fan-out Kafka has days of retention

2. Retry Queue depth > threshold
   → Increase retry delays (adaptive backoff)
   → Prioritize first attempts over retries

3. Event Store write latency increasing
   → Shed load: return 429 to internal event publishers
   → Internal publishers buffer in their own queues
   → This is the last resort — means the entire system is saturated

Priority order:
  First attempts > Short retries > Long retries > Manual replays
```

---

## 10. Failure Modes & Mitigations

### 10.1 — Event Store Failure
- **Impact**: New events can't be persisted. Delivery continues for already-stored events.
- **Mitigation**: Synchronous replication within AZ. Automatic failover (< 30s). Internal publishers retry with idempotency keys. **No event is ever lost** — publishers hold events until ingestion acknowledges.

### 10.2 — Kafka Broker Failure
- **Impact**: Fan-out or delivery queue paused for affected partitions.
- **Mitigation**: Kafka replication factor = 3, min.insync.replicas = 2. Partition leadership failover is automatic (< 10s). Events buffer in the outbox table and drain when Kafka recovers.

### 10.3 — Delivery Worker Fleet Partial Failure
- **Impact**: Reduced delivery throughput; events queue up.
- **Mitigation**: Auto-scaling based on queue depth. Kafka rebalances partitions across surviving workers. Individual worker failure = partition reassignment, no data loss (Kafka offset tracking).

### 10.4 — Mass Endpoint Failure (Internet Outage / Major Provider Down)
- **Impact**: Millions of deliveries fail simultaneously, retry queue explodes.
- **Mitigation**:
  - Circuit breakers trip per-endpoint, stopping wasted attempts
  - Retry queue has bounded memory — overflow spills to durable backing store
  - Adaptive: detect correlated failures (e.g., all endpoints on AWS us-east-1) and batch-pause retries for that cohort
  - **Proactive**: monitor global delivery success rate. If it drops below 90%, escalate to incident response.

### 10.5 — Poison Event (Causes Worker Crash)
- **Impact**: Worker crash loop on one event blocks all events in that partition.
- **Mitigation**: Per-event crash counter. If a single event causes 3 worker crashes, quarantine it to a dead-letter topic. Alert on-call. Process remaining events normally.

### 10.6 — Endpoint Returns 200 But Doesn't Process
- **Impact**: Merchant loses events silently. We think delivery succeeded.
- **Mitigation**: This is the merchant's problem (they returned 200). But we help:
  - Dashboard shows delivery log with timing and response
  - Manual replay API lets merchants re-request events
  - Documentation strongly advises: "return 200 only after durably processing the event"

**Staff+ insight**: The most insidious failure mode is **silent data loss** — events that are delivered successfully (HTTP 200) but the merchant's system doesn't process them (crashed after responding, bug in handler). Stripe can't detect this. The mitigation is defensive documentation, the replay API, and the advice to use webhook events as triggers to fetch the latest state via the REST API.

---

## 11. Observability

### System Metrics

| Metric | Alert Threshold | Why |
|---|---|---|
| **Event ingestion rate** | Sudden drop > 50% | Upstream service may have stopped publishing |
| **First-attempt delivery latency (p99)** | > 10s | Workers saturated or connection pool exhausted |
| **First-attempt success rate** | < 95% | Systemic issue (not just one bad endpoint) |
| **Overall delivery success rate (incl retries)** | < 99.5% | Retry strategy not recovering enough failures |
| **Retry queue depth** | > 10M and growing | Mass endpoint failure or retry backlog |
| **Fan-out lag (Kafka consumer lag)** | > 60s | Fan-out service can't keep up |
| **Circuit breakers OPEN** | > 5% of endpoints | Correlated failure (provider outage?) |
| **DLQ depth** | > 0 | Poison events need investigation |

### Per-Endpoint Metrics (for merchant Dashboard)

```
For each webhook endpoint, expose:
- Delivery success rate (1h, 24h, 7d)
- Average delivery latency
- Last successful delivery timestamp
- Last failed delivery (with error details)
- Number of pending retries
- Circuit breaker state

This is what merchants see in the Stripe Dashboard under
Developers → Webhooks → [endpoint] → Overview
```

### Distributed Tracing

```
Every webhook delivery carries trace context:

  Internal service (payment creation)
    → Event ingestion (trace_id: abc)
      → Fan-out (trace_id: abc, span: fanout)
        → Delivery attempt (trace_id: abc, span: deliver)

Trace includes:
  - Event creation to first delivery attempt (end-to-end latency)
  - Time spent in each queue
  - Rendering/signing time
  - HTTP connection setup time
  - Response time from merchant endpoint
  - Retry chain (linked spans across attempts)
```

---

## 12. Trade-off Analysis

| Decision | Option A | Option B | This Design | Why |
|---|---|---|---|---|
| **Delivery guarantee** | Exactly-once | At-least-once | **At-least-once** | Exactly-once requires merchant coordination (2PC over HTTP — impractical). Merchants must be idempotent. |
| **Ordering** | Strict per-object | Best-effort | **Best-effort + sequence numbers** | Strict ordering requires head-of-line blocking. A slow delivery blocks all subsequent events for that object. Unacceptable at scale. |
| **Payload rendering** | At ingestion (pre-render all versions) | At delivery time | **At delivery time** | Pre-rendering N API versions × M endpoints = storage explosion. Most events are delivered once. Render on demand. Cache for high-fanout events. |
| **Retry storage** | In-memory (Redis) | Durable (DB) | **Hybrid**: Redis for < 15min, DB for longer | Short retries need sub-second scheduling precision. Long retries (hours) tolerate minute-level precision. |
| **Circuit breaker scope** | Per domain | Per endpoint | **Per endpoint** + per-domain connection errors | Same domain can have healthy and unhealthy paths. But DNS/TLS errors affect all endpoints on a domain. |
| **Event store** | Same DB as delivery logs | Separate stores | **Separate** | Different access patterns: events are read-heavy (rendering), delivery logs are write-heavy (recording attempts). Different retention needs. |
| **Queue technology** | Single Kafka pipeline | Kafka fan-out + SQS delivery | **Kafka + SQS/Redis** | Kafka excels at ordered fan-out. SQS/Redis excels at delayed retry with visibility timeout. Use each for its strength. |
| **Failed endpoint policy** | Keep retrying forever | Auto-disable after N days | **Auto-disable after 72h** + email notification | Infinite retries waste resources. But disabling silently = lost events. Email notification + easy re-enable + 30-day replay window = good balance. |

---

## 13. Evolution Story

### V1 — Single-Region MVP
- Single PostgreSQL for events + delivery log
- Single Kafka topic for fan-out and delivery
- Fixed retry schedule (5 attempts over 24h)
- No circuit breaker (just retry until exhausted)
- Synchronous payload rendering
- Handles 10K deliveries/sec
- **Validates the delivery model and API contracts**

### V2 — Scale + Reliability
- Separate event store (Scylla/DynamoDB) from delivery log
- Two-stage queue: Kafka fan-out → SQS delivery
- Circuit breaker per endpoint
- Exponential backoff with jitter (8 attempts over 72h)
- Connection pooling for hot endpoints
- SSRF protection (network isolation)
- API versioned rendering
- Handles 500K deliveries/sec

### V3 — Global + Enterprise Features
- Multi-region deployment (events replicated, delivery local to merchant's region)
- Adaptive concurrency per endpoint
- Per-endpoint rate limiting (configurable by enterprise merchants)
- Real-time delivery status via WebSocket (dashboard live view)
- Webhook simulation/testing endpoint (`POST /v1/webhook_endpoints/:id/test`)
- Delivery analytics (latency percentiles, failure categorization per endpoint)
- Custom retry policies for enterprise accounts
- Handles 5M+ deliveries/sec across regions

### V4 — Advanced Platform
- Webhook transformations (merchant-defined payload mappings)
- Event filtering with complex predicates (not just type matching)
- Batch delivery mode (multiple events in one HTTP POST for high-volume endpoints)
- Webhook-to-queue bridge (deliver directly to merchant's SQS/Pub-Sub instead of HTTP)
- Multi-destination routing (same event → HTTP + SQS + email)

---

## 14. Stripe-Specific Interview Angles

### "How does this interact with the Payment Ledger?"

Every ledger write emits domain events. These domain events are the **source** for webhook events:

```
Ledger: charge.captured (internal)
  → Event Ingestion: creates "charge.succeeded" webhook event
  → Fan-out: finds matching endpoints
  → Delivery: POST to merchant

The webhook system does NOT read from the ledger directly.
Internal services translate domain events into webhook events
with appropriate API-version-specific schemas.
```

### "How does this support Stripe Connect?"

Connect adds complexity: a single payment generates events for **multiple accounts**:
```
Customer pays on marketplace → events generated for:
  1. Platform account  → "payment_intent.succeeded" (platform's endpoint)
  2. Connected account → "payment_intent.succeeded" (merchant's endpoint)
  3. Stripe account    → internal events only (no webhook)

The fan-out service must resolve account hierarchy:
  Platform has direct charge → only platform gets webhook
  Platform has destination charge → both platform and connected account
  Platform has separate charges → connected account gets its own event
```

### "What about Stripe's real-time events (Server-Sent Events)?"

Thin Events / real-time stream is a **parallel delivery channel**, not a replacement:

```
Event created
  ├── Webhook pipeline (HTTP POST, retries, guaranteed delivery)
  └── Real-time stream (SSE, best-effort, no retries)

Real-time is for Dashboard live updates and low-latency integrations.
Webhooks remain the reliable, guaranteed delivery mechanism.
Same event store feeds both channels.
```

### "How do you handle a merchant endpoint that's slow but not failing?"

Slow endpoints (responding in 10-19s, just under timeout) are worse than failing endpoints because they:
- Consume worker threads for longer
- Don't trigger circuit breakers (they eventually succeed)
- Reduce effective throughput

```
Mitigation:
  1. Track p99 latency per endpoint
  2. If p99 > 10s sustained for 1 hour:
     → Reduce concurrency to 1 (prevent resource drain)
     → Route to "slow lane" worker pool (isolated from fast endpoints)
     → Notify merchant: "your endpoint is slow, consider optimizing"
  3. Slow-lane workers have higher timeouts but lower priority
  4. Prevents slow endpoints from degrading fast-endpoint delivery
```

### "Why not use a push notification service (SNS, Pub/Sub) instead of building this?"

- **API versioning**: SNS can't render payloads in merchant-specific API versions
- **Signing**: Stripe's HMAC signature scheme is custom and must be tightly controlled
- **Circuit breaking**: SNS retry behavior isn't configurable enough
- **Observability**: Merchants need per-event delivery status in Dashboard
- **SSRF control**: Managed services don't give enough control over outbound network security
- **Ordering**: We need per-object best-effort ordering; generic pub/sub doesn't provide this

That said, **internally** the fan-out and delivery queues can use managed services (SQS, Kafka) — just not for the final HTTP POST delivery.

---

## 15. Common Interviewer Challenges

**"How do you guarantee no event loss?"**
> Three layers: (1) Internal publishers retry until ingestion acknowledges. (2) Ingestion uses transactional outbox — the event is durably stored before anything else happens. (3) Fan-out reads from Kafka with committed offsets — if a consumer crashes, it replays from the last committed offset. The invariant: once `Event Ingestion` returns success, the event **will** be delivered (eventually, within the retry window). Before that, the publishing service is responsible for retrying.

**"What if a merchant changes their endpoint URL while events are in-flight?"**
> Events already in the delivery queue have the old URL baked in. Two options: (1) Deliver to old URL (simple, but merchant may have already decommissioned it). (2) Re-resolve URL at delivery time from endpoint config (extra DB read, but always current). This design uses option 2 — the delivery task carries endpoint_id, not the URL. Workers resolve the URL at delivery time. This adds one cache lookup but handles URL changes correctly.

**"How do you prevent webhook abuse (merchant sets endpoint to a victim's URL for DDoS)?"**
> (1) Endpoint URL validation requires the merchant to verify ownership (typically by responding to a test webhook with a specific response, or by placing a verification file at the URL). (2) Per-endpoint rate limiting (10K/hour default). (3) Signing: the victim would receive requests with an HMAC they don't have the secret for, making it easy to ignore. (4) Monitoring: detect endpoints receiving webhooks for many different accounts (anomalous pattern).

**"How do you handle schema migrations in the event store?"**
> The event store holds the **canonical internal representation**, which evolves with internal services. When the internal schema changes: (1) New fields are added (backward compatible). (2) Removed fields are kept in storage for the retention window. (3) The versioned serializer chain handles transformation from any internal version to any API version. This means the serializer is the compatibility layer, not the storage schema. Old events in storage can always be rendered in any API version.

**"What happens at exactly midnight on New Year's when every subscription renews?"**
> Predictable traffic spikes are handled by: (1) Pre-scaling delivery workers based on the billing schedule (we know exactly how many subscriptions renew when). (2) Staggering subscription renewal times (not all at midnight — spread across the hour). (3) Per-endpoint adaptive concurrency prevents overwhelming any single merchant. (4) Priority: first-attempt deliveries > retries during peak. The billing system itself also staggers invoice generation, so the event spike is more of a wave than a cliff.

---

## 16. Interview Scorecard

### What Interviewers Look For By Level

| Signal | Senior | Staff | Sr. Staff |
|---|---|---|---|
| **At-least-once semantics** | Mentions retries | Explains idempotency requirement on consumer side; transactional outbox | Designs the full guarantee chain from publisher → ingestion → delivery with no gaps |
| **Retry strategy** | Fixed retries | Exponential backoff with jitter | Adaptive backoff + circuit breaker + thundering herd mitigation + slow endpoint isolation |
| **Ordering** | "Events are ordered" | Acknowledges ordering is hard in distributed systems | Designs best-effort ordering with escape hatches (sequence numbers, API re-fetch); explains why strict ordering is wrong |
| **Fan-out** | Single queue | Separate fan-out and delivery stages | Per-endpoint partitioning, connection pooling tiers, adaptive concurrency |
| **Security** | HTTPS + signing | SSRF awareness | Full SSRF mitigation (network isolation, DNS rebinding, redirect blocking, IP validation) |
| **Failure modes** | "We retry on failure" | Circuit breaker, DLQ | Correlated failure detection, poison event quarantine, slow endpoint isolation, backpressure propagation |
| **Scale** | "Add more workers" | Partitioned queues, connection pooling | Adaptive concurrency (AIMD), tiered connection management, backpressure across pipeline stages |
| **API versioning** | Not mentioned | Mentions payload versioning | Designs canonical storage + versioned serializer chain; explains why render-at-delivery-time beats pre-rendering |
| **Observability** | Basic logging | Per-endpoint metrics | End-to-end tracing, merchant-facing delivery dashboard, global success rate monitoring, anomaly detection |
| **Trade-offs articulated** | 1-2 | 3-5 with clear reasoning | Full trade-off table with "why" for each decision; discusses what you'd change at 10x scale |
