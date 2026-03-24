# Gold Standard: Multi-Tenant SaaS Platform (Azure-Scale)

## 0. Why This Matters

Multi-tenancy is the **economic engine of cloud computing**. Without it, SaaS is just hosted software — no margin advantage, no operational leverage. Every Azure service, every Salesforce org, every Snowflake warehouse is a multi-tenant system under the hood.

The core tension: **maximize resource sharing (cost efficiency) while providing isolation guarantees (security, performance, compliance) that make each tenant believe they have their own dedicated system.**

Get isolation wrong → data breach, career-ending security incident. Get sharing wrong → you're running dedicated infrastructure per customer and your margins are negative.

---

## 1. Scope & Requirements

### Functional

| Requirement | Detail |
|---|---|
| **Tenant onboarding** | Self-service signup → provisioned tenant in <30 seconds |
| **Tenant isolation** | Data, compute, network isolation between tenants; zero cross-tenant data leakage |
| **Quota & rate limiting** | Per-tenant resource quotas (API calls, storage, compute) enforced in real-time |
| **Billing & metering** | Usage-based billing; accurate metering of every billable dimension |
| **Tiered plans** | Free / Standard / Premium / Enterprise with different limits and features |
| **Tenant admin** | Self-service management: users, roles, API keys, usage dashboards |
| **Custom domains** | Enterprise tenants can use their own domain (vanity URLs) |
| **Data residency** | Tenant data pinned to a geographic region (GDPR, sovereignty) |
| **Tenant lifecycle** | Create, suspend, reactivate, delete with full data cleanup |

### Non-Functional

| Dimension | Target |
|---|---|
| **Tenants** | 1M+ tenants (long tail: 95% are small, 1% generate 50% of load) |
| **Availability** | 99.99% per tenant SLA; blast radius of any failure < 5% of tenants |
| **Latency** | Tenant resolution overhead: < 1ms per request |
| **Isolation** | Zero cross-tenant data access (cryptographic guarantee) |
| **Noisy neighbor** | No single tenant can degrade another tenant's p99 by > 10% |
| **Metering accuracy** | < 0.1% error in billing-grade metering |
| **Onboarding** | < 30s from signup to first API call |

---

## 2. Capacity Estimation

```
1M tenants, power-law distribution:
  Top 1% (10K tenants):    50% of total load → "whale" tenants
  Next 9% (90K tenants):   30% of total load → "medium" tenants
  Bottom 90% (900K):       20% of total load → "long tail"

Aggregate platform traffic:
  500K req/sec peak across all tenants
  Top tenant: ~5K req/sec (1% of total)
  Median tenant: ~0.5 req/sec

Storage:
  Average tenant data: 500MB
  Total: 1M × 500MB = 500 TB
  Top 1% tenants: avg 50GB each → 500 TB (equals rest combined)
  Total platform storage: ~1 PB

Metering events:
  Every API call = 1 metering event
  500K events/sec → ~43B events/day → ~1.3T events/month
  At 100 bytes per event: ~130 TB/month raw metering data

Tenant metadata:
  1M tenants × ~10KB metadata = ~10 GB (fits in memory)
```

**Key insight**: The distribution is extremely skewed. Your architecture must handle a tenant doing 0.5 req/sec AND a tenant doing 5,000 req/sec on the **same infrastructure** without the whale starving the minnow.

---

## 3. The Core Decision: Tenancy Models

This is the section that defines your architecture. There are three models. A production system uses a **hybrid**.

```
┌─────────────────────────────────────────────────────────────────┐
│              TENANCY MODEL SPECTRUM                              │
│                                                                 │
│  SILO                    BRIDGE                    POOL         │
│  (dedicated)             (hybrid)                  (shared)     │
│                                                                 │
│  ┌─────┐ ┌─────┐       ┌──────────────┐       ┌────────────┐  │
│  │ DB  │ │ DB  │       │   DB Cluster  │       │  Single DB  │  │
│  │ T-1 │ │ T-2 │       │ ┌────┐┌────┐ │       │ tenant_id   │  │
│  └─────┘ └─────┘       │ │Sch1││Sch2│ │       │ on every    │  │
│  ┌─────┐ ┌─────┐       │ └────┘└────┘ │       │ row         │  │
│  │Comp │ │Comp │       │ Shared infra, │       │             │  │
│  │ T-1 │ │ T-2 │       │ separate      │       │ Shared      │  │
│  └─────┘ └─────┘       │ schemas/tables│       │ everything  │  │
│                         └──────────────┘       └────────────┘  │
│                                                                 │
│  Isolation: ★★★★★       Isolation: ★★★☆☆       Isolation: ★★☆☆☆│
│  Cost:      ★☆☆☆☆       Cost:      ★★★☆☆       Cost:      ★★★★★│
│  Scale:     ★★☆☆☆       Scale:      ★★★★☆       Scale:      ★★★★★│
│  Onboard:   ★☆☆☆☆       Onboard:    ★★★☆☆       Onboard:   ★★★★★│
│  Ops:       ★☆☆☆☆       Ops:        ★★★☆☆       Ops:       ★★★★★│
└─────────────────────────────────────────────────────────────────┘
```

### Decision: Tier-Based Hybrid Model

```
┌────────────────────────────────────────────────────────────┐
│ Tenant Tier    │ Data         │ Compute       │ Rationale   │
├────────────────┼──────────────┼───────────────┼─────────────┤
│ Enterprise     │ SILO         │ SILO          │ Compliance, │
│ ($50K+/yr)     │ Dedicated DB │ Dedicated     │ SLA, custom │
│ ~100 tenants   │ instance     │ node pool     │ requirements│
├────────────────┼──────────────┼───────────────┼─────────────┤
│ Premium        │ BRIDGE       │ POOL          │ Balance of  │
│ ($5K+/yr)      │ Shared DB,   │ Shared nodes, │ isolation   │
│ ~10K tenants   │ separate     │ resource      │ and cost    │
│                │ schema/table │ quotas        │             │
├────────────────┼──────────────┼───────────────┼─────────────┤
│ Standard/Free  │ POOL         │ POOL          │ Maximum     │
│ ($0-5K/yr)     │ Shared tables│ Shared nodes, │ density,    │
│ ~990K tenants  │ tenant_id col│ rate limited  │ lowest cost │
└────────────────┴──────────────┴───────────────┴─────────────┘
```

---

## 4. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                            CLIENTS                                      │
│  Tenant A (Enterprise)  │  Tenant B (Premium)  │  Tenant C (Free)      │
└────────────┬────────────┴──────────┬───────────┴──────────┬────────────┘
             │                       │                      │
             ▼                       ▼                      ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      API GATEWAY / EDGE                                 │
│                                                                         │
│  1. TLS termination + custom domain routing                            │
│  2. Tenant resolution (from subdomain / header / API key / JWT)        │
│  3. Tenant-aware rate limiting                                         │
│  4. Request tagging: X-Tenant-Id, X-Tenant-Tier injected              │
│                                                                         │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     TENANT CONTEXT SERVICE                              │
│                                                                         │
│  In-memory cache of ALL tenant metadata (~10GB, fits on every node)    │
│                                                                         │
│  Resolves: request → { tenant_id, tier, plan, quotas, db_endpoint,     │
│                         feature_flags, data_region, encryption_key }    │
│                                                                         │
│  Updated via: CDC stream from Tenant DB → broadcast to all nodes       │
│  Lookup time: < 0.1ms (hash map)                                       │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │
                    ┌─────────────┼──────────────┐
                    ▼             ▼              ▼
┌──────────────────────┐ ┌──────────────┐ ┌──────────────────────────┐
│   APPLICATION TIER    │ │  ASYNC TIER   │ │  METERING & BILLING      │
│                       │ │              │ │                          │
│  Stateless services   │ │  Job queues  │ │  Usage aggregation       │
│  Tenant context       │ │  Tenant-     │ │  Quota enforcement       │
│  propagated via       │ │  partitioned │ │  Invoice generation      │
│  request headers      │ │  workers     │ │                          │
│                       │ │              │ │  (see Section 7)         │
│  Per-tenant resource  │ │  Priority    │ │                          │
│  limits enforced      │ │  queues by   │ │                          │
│  at this layer        │ │  tier        │ │                          │
└──────────┬────────────┘ └──────┬───────┘ └──────────────────────────┘
           │                     │
           ▼                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        DATA TIER                                        │
│                                                                         │
│  ┌─────────────────┐  ┌────────────────────┐  ┌─────────────────────┐  │
│  │ SILO (Enterprise)│  │ BRIDGE (Premium)    │  │ POOL (Standard/Free)│  │
│  │                  │  │                     │  │                     │  │
│  │ Tenant-A DB      │  │  Shared Cluster     │  │  Shared Cluster    │  │
│  │ (dedicated       │  │  ┌───────────────┐  │  │  ┌───────────────┐ │  │
│  │  Postgres/MySQL) │  │  │ Schema: t_B   │  │  │  │ All pool      │ │  │
│  │                  │  │  │ Schema: t_C   │  │  │  │ tenants in    │ │  │
│  │ Tenant-A's own   │  │  │ Schema: t_D   │  │  │  │ shared tables │ │  │
│  │ encryption key   │  │  │ ...           │  │  │  │               │ │  │
│  │                  │  │  │ ~500 tenants  │  │  │  │ tenant_id PK  │ │  │
│  │ Dedicated        │  │  │ per cluster   │  │  │  │ prefix on all │ │  │
│  │ connection pool  │  │  └───────────────┘  │  │  │ queries       │ │  │
│  └─────────────────┘  └────────────────────┘  │  │               │ │  │
│                                                │  │ RLS enforced  │ │  │
│                                                │  └───────────────┘ │  │
│                                                └─────────────────────┘  │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ BLOB STORAGE (S3/GCS) — all tiers                               │   │
│  │ Bucket: platform-data/{region}/{tenant_id}/...                  │   │
│  │ Encrypted: per-tenant KMS key (envelope encryption)             │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Tenant Resolution & Context Propagation

Every request must be attributed to a tenant **before any business logic runs**. This is the most critical path in the system.

### 5a. Tenant Resolution Strategies

```
┌──────────────────────────────────────────────────────────────┐
│               TENANT RESOLUTION (in priority order)          │
│                                                              │
│  1. SUBDOMAIN: acme.app.example.com → tenant_id="acme"      │
│     Pros: Clean URLs, natural isolation                      │
│     Used by: Slack, Notion, Shopify                          │
│                                                              │
│  2. JWT CLAIM: Authorization: Bearer <jwt>                   │
│     → decode → claims.tenant_id = "t_abc123"                 │
│     Pros: Stateless, works for APIs                          │
│     Used by: Auth0, most B2B SaaS APIs                       │
│                                                              │
│  3. API KEY: X-API-Key: sk_live_abc123                       │
│     → lookup → key.tenant_id = "t_abc123"                    │
│     Pros: Simple for integrations                            │
│     Used by: Stripe, Twilio                                  │
│                                                              │
│  4. PATH PREFIX: /api/v1/tenants/{tenant_id}/resources       │
│     Pros: Explicit, easy to debug                            │
│     Cons: Verbose, easy to misroute                          │
│                                                              │
│  5. CUSTOM DOMAIN: acme-corp.com → tenant_id="acme"          │
│     → CNAME lookup table (custom_domain → tenant_id)         │
│     Pros: White-label experience                             │
│     Used by: Enterprise customers                            │
└──────────────────────────────────────────────────────────────┘
```

### 5b. Tenant Context Object

```go
// TenantContext is resolved once per request and propagated everywhere
type TenantContext struct {
    // Identity
    TenantID    string    // "t_abc123"
    TenantName  string    // "Acme Corp"
    Tier        Tier      // FREE, STANDARD, PREMIUM, ENTERPRISE

    // Data routing
    DataRegion  string    // "us-east-1", "eu-west-1"
    DBEndpoint  string    // Resolved database connection string
    DBStrategy  Strategy  // SILO, BRIDGE, POOL
    SchemaName  string    // For BRIDGE: "tenant_abc123"

    // Security
    EncryptionKeyID string // KMS key ARN for this tenant's data
    IsolationLevel  string // "shared", "dedicated"

    // Quotas (from plan)
    Quotas      QuotaSet  // { api_rpm: 1000, storage_gb: 50, ... }

    // Feature flags
    Features    map[string]bool // { "advanced_analytics": true, ... }

    // Billing
    PlanID      string
    BillingID   string    // Stripe customer ID
}

// Propagation: serialized into request headers at the gateway,
// deserialized by every downstream service.
//
// X-Tenant-Id: t_abc123
// X-Tenant-Tier: premium
// X-Tenant-DB: bridge://cluster-7.db:5432/tenant_abc123
// X-Tenant-Region: us-east-1
//
// For async (queues): embedded in message envelope.
// For background jobs: loaded from tenant metadata store.
```

### 5c. Tenant Metadata Store

```
┌──────────────────────────────────────────────────────────┐
│            TENANT METADATA STORE                          │
│                                                          │
│  Source of truth: PostgreSQL (strong consistency)         │
│                                                          │
│  Tables:                                                 │
│  ┌────────────────────────────────────────────┐          │
│  │ tenants                                     │          │
│  │   id, name, slug, tier, plan_id, status,    │          │
│  │   data_region, created_at, settings (JSONB) │          │
│  ├────────────────────────────────────────────┤          │
│  │ tenant_quotas                               │          │
│  │   tenant_id, resource, limit, current_usage │          │
│  ├────────────────────────────────────────────┤          │
│  │ tenant_db_mappings                          │          │
│  │   tenant_id, strategy, endpoint, schema,    │          │
│  │   pool_id, shard_key                        │          │
│  ├────────────────────────────────────────────┤          │
│  │ tenant_encryption_keys                      │          │
│  │   tenant_id, kms_key_arn, created_at,       │          │
│  │   rotation_status                           │          │
│  ├────────────────────────────────────────────┤          │
│  │ custom_domains                              │          │
│  │   domain, tenant_id, tls_cert_arn, status   │          │
│  └────────────────────────────────────────────┘          │
│                                                          │
│  Caching:                                                │
│  - Full dataset (~10GB) loaded into every app node       │
│  - CDC (Change Data Capture) via Debezium → Kafka        │
│  - App nodes consume CDC stream, update local cache      │
│  - Cache refresh latency: < 2 seconds                    │
│  - Fallback: direct DB query on cache miss               │
│                                                          │
│  Why not Redis?                                          │
│  - 1M tenants × 10KB = 10GB fits in app memory          │
│  - Eliminates network hop for every request              │
│  - No Redis availability dependency in hot path          │
└──────────────────────────────────────────────────────────┘
```

---

## 6. Data Isolation — The Deep Dive

### 6a. Pool Model (Shared Tables)

```sql
-- Every table has tenant_id as the FIRST column in the primary key.
-- This ensures data locality and efficient partition pruning.

CREATE TABLE orders (
    tenant_id   UUID NOT NULL,
    order_id    UUID NOT NULL,
    customer_id UUID NOT NULL,
    amount      DECIMAL(12,2),
    status      VARCHAR(20),
    created_at  TIMESTAMP,
    PRIMARY KEY (tenant_id, order_id)
);

-- CRITICAL: Row-Level Security (RLS)
-- Defense-in-depth: even if application code has a bug,
-- the database itself prevents cross-tenant access.

ALTER TABLE orders ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON orders
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);

-- Every connection sets the tenant context:
SET app.current_tenant_id = 't_abc123';

-- Now even "SELECT * FROM orders" only returns tenant's own data.
-- No WHERE clause needed (but best practice to include it anyway).
```

**Query pattern enforcement**:

```
Application-level safeguard:

1. Middleware sets tenant context on DB connection from pool
2. Every query is intercepted by a query analyzer
3. If any query lacks tenant_id in WHERE clause → REJECT + alert
4. RLS is the backstop if the application-level check fails

class TenantAwareRepository:
    def query(self, sql, params):
        # Set session-level tenant context
        self.conn.execute(
            "SET app.current_tenant_id = %s", [self.tenant_id]
        )
        # Verify tenant_id is in query (belt-and-suspenders)
        if 'tenant_id' not in sql and not self.is_exempt(sql):
            raise TenantIsolationViolation(sql)
        return self.conn.execute(sql, params)
```

### 6b. Bridge Model (Schema-per-Tenant)

```
Shared PostgreSQL cluster, separate schema per tenant:

Cluster: premium-db-cluster-07.db.internal
├── Schema: tenant_acme
│   ├── orders
│   ├── customers
│   └── products
├── Schema: tenant_globex
│   ├── orders
│   ├── customers
│   └── products
└── ... (~500 schemas per cluster)

Pros:
  - Natural isolation (schemas are separate namespaces)
  - Easy per-tenant backup/restore
  - Tenant can be migrated to SILO by pg_dump of their schema

Cons:
  - Schema creation on onboarding (~2-5 seconds)
  - 500+ schemas per cluster is the practical limit
  - DDL migrations must run per-schema (use parallel migration runner)

Connection routing:
  app → pgbouncer → SET search_path = tenant_acme → query
```

### 6c. Silo Model (Dedicated Instance)

```
Enterprise tenant gets:
  - Dedicated DB instance (or cluster)
  - Own connection pool
  - Own encryption key (CMK in KMS)
  - Can be in a tenant-specified region
  - Dedicated compute nodes (optional)

Provisioning: Terraform/Pulumi template triggered on tier upgrade
  1. Provision RDS instance in tenant's region
  2. Create KMS key, grant access
  3. Run schema migrations
  4. Update tenant_db_mappings in metadata store
  5. Migrate data from pool/bridge → silo (background job)
  6. Switch traffic (update routing, drain old connections)

Total provisioning time: 5-15 minutes (async, tenant notified)
```

### 6d. Encryption Isolation

```
┌──────────────────────────────────────────────────────────────┐
│              PER-TENANT ENCRYPTION                            │
│                                                              │
│  Every tenant gets a unique encryption key (even in pool):   │
│                                                              │
│  ┌─────────────────────────────────────────────┐            │
│  │ Envelope Encryption                          │            │
│  │                                              │            │
│  │  Master Key (KMS)                            │            │
│  │       │                                      │            │
│  │       ▼                                      │            │
│  │  Tenant Key (encrypted by master)            │            │
│  │       │ Key: tenant_t_abc123_dek_v3          │            │
│  │       │ Encrypted with: aws:kms:key/abc      │            │
│  │       ▼                                      │            │
│  │  Data (encrypted by tenant key)              │            │
│  │       Columns: PII fields, file contents     │            │
│  │                                              │            │
│  └─────────────────────────────────────────────┘            │
│                                                              │
│  Benefits:                                                   │
│  - Tenant offboarding: destroy tenant key → data is          │
│    cryptographically irrecoverable (crypto-shredding)        │
│  - Key rotation per-tenant without re-encrypting all data    │
│  - Compliance: each tenant's data encrypted with unique key  │
│  - Enterprise tenants can bring their own KMS key (BYOK)     │
└──────────────────────────────────────────────────────────────┘
```

---

## 7. Noisy Neighbor Mitigation

This is the hardest problem in multi-tenancy. A single tenant running an expensive query or burst of API calls can degrade the entire platform.

### 7a. Multi-Layer Defense

```
┌─────────────────────────────────────────────────────────────────┐
│              NOISY NEIGHBOR MITIGATION LAYERS                    │
│                                                                 │
│  LAYER 1: API GATEWAY — Rate Limiting (first line of defense)  │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ Per-tenant rate limits based on plan:                      │  │
│  │   Free:       10 req/sec,     100 req/min                 │  │
│  │   Standard:   100 req/sec,    5,000 req/min               │  │
│  │   Premium:    1,000 req/sec,  50,000 req/min              │  │
│  │   Enterprise: custom (e.g., 10,000 req/sec)               │  │
│  │                                                            │  │
│  │ Sliding window counter (local + Redis sync)               │  │
│  │ Response: 429 with Retry-After header                     │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  LAYER 2: APPLICATION — Request Costing & Concurrency Limits   │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ Not all requests are equal:                                │  │
│  │   GET /api/users/123        → cost: 1 unit                │  │
│  │   GET /api/reports/generate → cost: 50 units              │  │
│  │   POST /api/bulk-import     → cost: 500 units             │  │
│  │                                                            │  │
│  │ Per-tenant concurrency limit:                              │  │
│  │   Max 50 concurrent requests (semaphore per tenant)       │  │
│  │   Prevents one tenant from consuming all worker threads   │  │
│  │                                                            │  │
│  │ Request queuing with tenant-fair scheduling:              │  │
│  │   Weighted fair queue — each tenant gets proportional      │  │
│  │   share of capacity based on their plan tier               │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  LAYER 3: DATABASE — Query Governance                          │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ Per-tenant connection pool limits:                         │  │
│  │   Pool tenants: max 5 connections from shared pool        │  │
│  │   Bridge tenants: max 20 connections                      │  │
│  │   Silo tenants: full connection pool                      │  │
│  │                                                            │  │
│  │ Query timeout: 30s hard kill for pool tenants             │  │
│  │ Statement-level resource limits (Postgres: statement_timeout) │
│  │                                                            │  │
│  │ Expensive query detection:                                │  │
│  │   Monitor pg_stat_statements per tenant_id                │  │
│  │   Auto-kill queries > 10s with > 100M rows scanned       │  │
│  │   Alert tenant admin on repeated expensive queries        │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  LAYER 4: COMPUTE — Resource Isolation                         │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ Kubernetes resource quotas per tenant namespace:          │  │
│  │   (for premium/enterprise tenants with dedicated pods)    │  │
│  │                                                            │  │
│  │ For shared compute (pool tenants):                        │  │
│  │   - cgroups limit CPU/memory per request handler          │  │
│  │   - Tenant-aware thread pool (fair share scheduler)       │  │
│  │                                                            │  │
│  │ Background job isolation:                                 │  │
│  │   Separate job queues per tenant tier:                    │  │
│  │   ┌──────────────┐ ┌──────────────┐ ┌──────────────┐    │  │
│  │   │ enterprise   │ │ premium      │ │ pool (shared) │    │  │
│  │   │ queue        │ │ queue        │ │ queue         │    │  │
│  │   │ dedicated    │ │ priority: hi │ │ priority: lo  │    │  │
│  │   │ workers      │ │ workers: 40% │ │ workers: 30%  │    │  │
│  │   └──────────────┘ └──────────────┘ └──────────────┘    │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  LAYER 5: NETWORK — Bandwidth & Connection Limits              │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │ Per-tenant bandwidth limits at the gateway                │  │
│  │ Per-tenant max WebSocket connections                      │  │
│  │ Response payload size limits by plan tier                 │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### 7b. Tenant-Fair Scheduling (Weighted Fair Queue)

```
Problem: 100 tenants share a pool of 200 worker threads.
         Tenant X sends 5,000 requests in a burst.
         Without fairness: Tenant X consumes all 200 threads for seconds,
         all other tenants get 503s.

Solution: Weighted Fair Queuing (WFQ)

┌────────────────────────────────────────────┐
│ Incoming requests are placed in per-tenant │
│ virtual queues:                            │
│                                            │
│ Tenant A (enterprise, weight=10): ███      │
│ Tenant B (premium, weight=5):     █████    │
│ Tenant C (free, weight=1):        ██       │
│ Tenant X (standard, weight=2):    ██████████████  (burst)
│                                            │
│ Scheduler picks next request using         │
│ deficit round-robin:                       │
│                                            │
│ Each tenant has a "deficit counter"        │
│ Each round: deficit += weight              │
│ Tenant with highest deficit gets served    │
│ On serve: deficit -= 1                     │
│                                            │
│ Result: Tenant X gets 2/(10+5+1+2) = 11%  │
│ of capacity, NOT 100%. Other tenants are   │
│ barely impacted.                           │
└────────────────────────────────────────────┘
```

---

## 8. Metering & Billing

### 8a. Metering Pipeline

```
┌─────────────────────────────────────────────────────────────────┐
│                    METERING PIPELINE                             │
│                                                                 │
│  Every request generates a metering event:                      │
│                                                                 │
│  ┌──────────┐     ┌─────────────┐     ┌───────────────────┐   │
│  │ App/Gate  │────►│ Kafka Topic  │────►│ Metering          │   │
│  │ emits     │     │ "metering"  │     │ Aggregator        │   │
│  │ event     │     │             │     │ (Flink)           │   │
│  └──────────┘     │ Partitioned │     │                   │   │
│                    │ by tenant_id│     │ Windows:          │   │
│  Event:           └─────────────┘     │ - 1-min tumbling  │   │
│  {                                     │ - 1-hour rollup   │   │
│    tenant_id,                          │ - 1-day rollup    │   │
│    timestamp,                          │                   │   │
│    resource: "api_calls",              └────────┬──────────┘   │
│    quantity: 1,                                 │              │
│    dimensions: {                                ▼              │
│      endpoint: "/v1/users",    ┌──────────────────────────┐   │
│      method: "GET",            │ Usage Store (TimescaleDB) │   │
│      response_code: 200,       │                          │   │
│      bytes: 1234               │ tenant_usage_hourly       │   │
│    }                           │ tenant_usage_daily        │   │
│  }                             │ tenant_usage_monthly      │   │
│                                │                          │   │
│  Volume: 500K events/sec       └────────────┬─────────────┘   │
│  Accuracy: exactly-once                     │                  │
│  (Kafka + Flink checkpointing)              ▼                  │
│                                ┌──────────────────────────┐   │
│                                │ Billing Service           │   │
│                                │                          │   │
│                                │ Monthly:                 │   │
│                                │ 1. Read usage aggregates │   │
│                                │ 2. Apply pricing rules   │   │
│                                │ 3. Generate invoice      │   │
│                                │ 4. Charge via Stripe     │   │
│                                │                          │   │
│                                │ Real-time:               │   │
│                                │ 1. Quota threshold alerts│   │
│                                │ 2. Overage warnings      │   │
│                                │ 3. Auto-suspend on       │   │
│                                │    unpaid invoices       │   │
│                                └──────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 8b. Quota Enforcement

```
Two levels of enforcement:

SOFT QUOTAS (warn, don't block):
  - Storage approaching limit → email notification at 80%, 90%
  - API usage approaching limit → warning header in responses
  - X-Quota-Remaining: 1523
  - X-Quota-Limit: 10000
  - X-Quota-Reset: 2024-04-01T00:00:00Z

HARD QUOTAS (block):
  - API rate limit exceeded → 429
  - Storage limit exceeded → 413 on new writes
  - Compute quota exceeded → 503 for expensive operations

Fast-path enforcement (per-request, <0.1ms):
  - Local counter on gateway node (synced with Redis periodically)
  - Check: current_count < plan.limit
  - No DB call, no network call for 99% of checks

Slow-path reconciliation (async, every 1 minute):
  - Flink aggregated counts → Redis → gateway nodes sync
  - Corrects drift between local counters and actual usage
  - Accuracy: within 2% of true usage at all times
```

---

## 9. Tenant Lifecycle

```
┌────────────────────────────────────────────────────────────────────┐
│                    TENANT STATE MACHINE                             │
│                                                                    │
│                                                                    │
│  ┌──────────────┐    provision     ┌──────────────┐               │
│  │ PROVISIONING  │───────────────►│   ACTIVE      │               │
│  │               │   (<30 sec)     │              │               │
│  │ - Create row  │                │ - Normal      │               │
│  │ - Assign DB   │                │   operations  │               │
│  │ - Create KMS  │                │ - Billing     │               │
│  │   key         │                │   active      │               │
│  │ - Init schema │                │              │               │
│  └──────────────┘                └──────┬───────┘               │
│                                    ▲     │                        │
│                           reactivate│     │ payment_failed ||     │
│                                    │     │ admin_suspend ||      │
│                                    │     │ abuse_detected        │
│                                    │     ▼                        │
│                                ┌──────────────┐                  │
│                                │  SUSPENDED    │                  │
│                                │              │                  │
│                                │ - Read-only  │                  │
│                                │   access     │                  │
│                                │ - No writes  │                  │
│                                │ - No API     │                  │
│                                │   calls      │                  │
│                                │ - 30-day     │                  │
│                                │   grace      │                  │
│                                └──────┬───────┘                  │
│                                       │ grace_period_expired ||   │
│                                       │ tenant_requests_delete   │
│                                       ▼                           │
│                                ┌──────────────┐                  │
│                                │  DELETING     │                  │
│                                │              │                  │
│                                │ - Async data │                  │
│                                │   deletion   │                  │
│                                │ - Crypto-    │                  │
│                                │   shred keys │                  │
│                                │ - Audit log  │                  │
│                                │   retained   │                  │
│                                └──────┬───────┘                  │
│                                       │                           │
│                                       ▼                           │
│                                ┌──────────────┐                  │
│                                │   DELETED     │                  │
│                                │  (terminal)   │                  │
│                                └──────────────┘                  │
└────────────────────────────────────────────────────────────────────┘

Data deletion process (GDPR/CCPA compliant):
  1. Destroy tenant encryption key in KMS → data is crypto-shredded
  2. Delete all rows with tenant_id (pool model) — background job
  3. Drop schema (bridge model) or terminate DB (silo model)
  4. Delete S3 prefix: s3://platform/{region}/{tenant_id}/
  5. Purge from all caches and search indices
  6. Retain: audit logs (anonymized), billing records (legal requirement)
  7. Generate deletion certificate with timestamp + checksums
```

---

## 10. Tenant-Aware Observability

```
EVERY metric, log, and trace is tagged with tenant_id.
This is non-negotiable — without it, debugging tenant issues is impossible.

Metrics (Prometheus with tenant_id label):
  request_duration_seconds{tenant_id, endpoint, status}
  db_query_duration_seconds{tenant_id, query_type}
  storage_bytes{tenant_id}
  active_connections{tenant_id}
  quota_utilization_ratio{tenant_id, resource}

⚠️ CARDINALITY WARNING: 1M tenants × 100 endpoints = 100M series.
   Solution: Only emit per-tenant metrics for premium+ tenants.
   Pool tenants: aggregate by tenant_tier, sample 1%.
   Dashboard queries: use tenant_id as a filter, never as a group-by
   on the full dataset.

Logs:
  Every log line includes tenant_id in structured fields.
  Log aggregation partitioned by tenant for fast lookup.
  Enterprise tenants can access their own logs via API.

Traces:
  Distributed traces tagged with tenant_id as a span attribute.
  Allows: "show me all traces for tenant X with p99 > 500ms"

Per-tenant health dashboard (exposed to tenant admins):
  - API call volume and error rates
  - Quota utilization gauges
  - Latency percentiles (their traffic only)
  - Recent errors with request IDs
```

---

## 11. Trade-off Summary

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| **Tenancy model** | Tier-based hybrid (pool + bridge + silo) | Pure pool | Enterprise customers demand dedicated resources and compliance. Pure pool can't satisfy data residency. Hybrid captures 95% of tenants cheaply while serving whales properly. |
| **Tenant resolution** | Subdomain + JWT + API key (layered) | Path prefix only | Subdomains give clean UX; JWT is stateless for APIs; API keys for integrations. Flexibility for different client types. |
| **Data isolation (pool)** | RLS + tenant_id PK + app-level checks | Application-only filtering | Defense-in-depth. RLS is the last line of defense if app code has a bug. One missed WHERE clause = data breach without RLS. |
| **Metadata cache** | In-memory on every node via CDC | Redis / per-request DB lookup | Eliminates network hop. 10GB fits in memory. CDC keeps it fresh within 2s. Redis failure can't degrade tenant resolution. |
| **Rate limiting** | Local counters + periodic Redis sync | Redis on every request | Same as API gateway design — hot path stays local, global accuracy via periodic sync. |
| **Metering** | Kafka → Flink → TimescaleDB | Synchronous DB write per request | Async pipeline handles 500K events/sec without adding latency. Exactly-once via Flink checkpoints. |
| **Encryption** | Per-tenant envelope encryption | Single platform key | Enables crypto-shredding on tenant delete. BYOK for enterprise. Per-tenant key rotation. Compliance requirement for many industries. |
| **Noisy neighbor** | Multi-layer (rate limit + fair queue + DB limits + compute isolation) | Rate limiting only | Rate limiting alone doesn't handle slow queries, memory hogs, or background job monopolization. Need defense at every layer. |

---

## 12. Interview Navigation Guide

**If interviewer asks "Why not just one model — pool for everyone?"**
> Pool is optimal for cost and operational simplicity, but fails for three reasons: (1) Compliance — regulated industries (healthcare, finance) require dedicated infrastructure and auditable isolation. (2) Performance guarantees — SLA-paying enterprise customers can't share database connection pools with free-tier tenants. (3) Data residency — GDPR requires some tenants' data to stay in EU. You can't pin rows in a shared table to a geographic region. The tier-based hybrid captures 95% of tenants in the cheap pool model while giving the 1% that pays 50% of revenue the isolation they need.

**If interviewer asks "How do you prevent cross-tenant data leakage?"**
> Four layers of defense: (1) Application-level: every query includes `tenant_id` in the WHERE clause, enforced by a query interceptor that rejects queries missing it. (2) Database-level: PostgreSQL Row-Level Security policies that filter by `tenant_id` set on the session. (3) Encryption: per-tenant encryption keys — even if data is somehow exposed, it's encrypted with a key only that tenant's requests can access. (4) Network: tenant-scoped API tokens can only generate requests for their own tenant_id; the gateway rejects mismatches. A bug must bypass all four layers to cause a leak.

**If interviewer asks "How do you handle tenant migration between tiers?"**
> Tenant upgrades from pool → bridge: (1) Create new schema on bridge cluster, (2) Background job copies data from shared tables filtering by tenant_id, (3) Once caught up, briefly pause writes (~seconds), copy delta, switch tenant_db_mapping, resume. Similar to a database migration with minimal downtime. Bridge → silo: same pattern but target is a dedicated instance. The tenant metadata store update propagates via CDC within 2 seconds, and all new requests route to the new location.

**If interviewer asks "What about the onboarding cold-start for pool tenants?"**
> Pool model onboarding is nearly instant because there's nothing to provision — the shared database and compute already exist. Steps: (1) Insert row into tenants table, (2) Assign to a pool shard via consistent hashing, (3) Create KMS encryption key (async, <1s), (4) CDC propagates to all app nodes (<2s). Total: under 5 seconds. The tenant can make their first API call immediately. Compare to silo provisioning which takes 5-15 minutes (DB instance spin-up), which is why we only use silo for enterprise tenants who are onboarded via a sales process anyway.

**If interviewer asks "How do you handle noisy neighbor at the database layer?"**
> Three mechanisms: (1) Per-tenant connection limits — pool tenants get max 5 connections from pgbouncer, preventing one tenant from exhausting the pool. (2) Statement timeout — 30-second hard kill for queries from pool tenants. (3) Active monitoring — pg_stat_statements tracked by tenant_id; queries scanning >100M rows are auto-killed and the tenant admin is notified. For premium tenants on the bridge model, each schema has its own connection allocation. Enterprise silo tenants have full database resources and can run whatever they want.
