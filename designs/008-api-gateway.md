# Gold Standard: API Gateway (Azure API Management / Kong / AWS API Gateway)

## 0. Why This Matters

An API Gateway is the **front door to your entire backend**. Every single request — from mobile apps, SPAs, third-party integrations, internal services — flows through it. It is simultaneously a **security boundary**, a **traffic management layer**, a **protocol translator**, and an **observability collection point**.

At scale, this is not a simple reverse proxy. It is a **programmable, distributed request processing pipeline** that must:
- Handle **millions of requests per second** with sub-millisecond overhead
- Never be the bottleneck or single point of failure
- Be extensible without redeployment (plugin architecture)
- Enforce security, rate limiting, and routing policies consistently across hundreds of backend services

Get this wrong, and you've built a centralized chokepoint that takes down your entire platform. Get it right, and you've built the most leveraged piece of infrastructure in your organization.

---

## 1. Scope & Requirements

### Functional

| Requirement | Detail |
|---|---|
| **Request routing** | Route requests to backend services based on path, headers, method, query params, weighted rules |
| **Authentication & authorization** | Validate API keys, JWT, OAuth 2.0, mTLS; enforce RBAC/ABAC policies |
| **Rate limiting** | Per-client, per-API, per-plan quotas; global and local rate limiting |
| **Request/response transformation** | Header injection/removal, body rewriting, protocol translation (REST↔gRPC, REST↔SOAP) |
| **Load balancing** | Distribute requests across backend instances with health-aware routing |
| **Circuit breaking** | Detect backend failures and fail fast; prevent cascade failures |
| **Caching** | Response caching at the gateway layer for eligible endpoints |
| **API versioning** | Route to different backend versions based on path prefix, header, or query param |
| **Plugin/middleware architecture** | Extensible pipeline — custom logic without modifying core gateway |
| **Developer portal** | Self-service API key provisioning, documentation hosting, usage analytics |
| **Observability** | Structured logging, distributed tracing, metrics emission for every request |
| **WebSocket & streaming support** | Proxy long-lived connections and server-sent events |

### Non-Functional

| Dimension | Target |
|---|---|
| **Throughput** | 2M+ requests/sec per cluster (horizontally scalable) |
| **Latency overhead** | < 2ms p50, < 10ms p99 added latency (gateway processing only) |
| **Availability** | 99.999% (five nines — this is the front door) |
| **Deployment** | Zero-downtime config updates; hot-reload of routing rules and plugins |
| **Scalability** | Linear horizontal scaling; no shared mutable state in hot path |
| **Config propagation** | < 5s from config change to all gateway nodes enforcing it |
| **Failure isolation** | One misbehaving backend cannot degrade gateway for other backends |

### Out of Scope
- Service mesh (east-west traffic between services) — that's Istio/Linkerd territory
- Full WAF (web application firewall) — specialized product, though basic protections included
- API design/linting — development-time concern, not runtime

---

## 2. Capacity Estimation

```
Assume a large platform (Azure API Management / Stripe-scale):

Peak request rate: 2M req/sec
Average request size: ~2KB (headers + body)
Average response size: ~5KB

Inbound bandwidth:  2M × 2KB = ~4 GB/sec
Outbound bandwidth: 2M × 5KB = ~10 GB/sec

Connection pool to backends:
  ~500 backend services
  ~50 instances per service = 25,000 backend endpoints
  Connection pool: ~100 conns per endpoint = 2.5M persistent connections

Active API consumers:
  ~100K registered applications (API keys)
  ~10M unique client IPs per day

Rate limit state:
  ~100K API keys × sliding window counters = ~100K entries
  ~10M IP-level counters (if IP-based limiting)
  Total rate limit state: ~500MB (fits in memory / Redis)

Config size:
  ~5,000 API route definitions
  ~500 rate limit policies
  ~200 auth policies
  ~100 plugins
  Total config: ~50MB serialized

Gateway fleet:
  Target: 100K req/sec per node (after auth + rate limit + transform)
  Nodes needed: 2M / 100K = ~20 nodes (with 3x headroom = ~60 nodes)
  Distributed across 3+ availability zones
```

**Key insight**: The gateway is **CPU-bound** (TLS termination, JWT validation, regex routing, body transformation) not I/O-bound. Optimize for compute efficiency. Every microsecond of per-request overhead multiplied by 2M req/sec = real cost.

---

## 3. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                 CLIENTS                                         │
│  Mobile Apps, SPAs, Third-party Integrations, Internal Services, Partners       │
└───────────────────────────────────┬─────────────────────────────────────────────┘
                                    │ HTTPS / gRPC / WebSocket
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           GLOBAL LOAD BALANCER                                  │
│                    (Anycast DNS + L4 LB per region)                             │
│                                                                                 │
│  - GeoDNS routes to nearest region                                             │
│  - L4 LB (NLB/DPDK) distributes across gateway nodes                          │
│  - Health checks on gateway fleet                                              │
│  - DDoS mitigation (SYN flood, amplification)                                  │
└───────────────────────────────────┬─────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          API GATEWAY CLUSTER                                    │
│                                                                                 │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │                     GATEWAY NODE (stateless)                             │   │
│  │                                                                          │   │
│  │  ┌─────────────────────────────────────────────────────────────────┐     │   │
│  │  │                  REQUEST PROCESSING PIPELINE                    │     │   │
│  │  │                                                                 │     │   │
│  │  │  Ingress          Plugin Chain              Egress              │     │   │
│  │  │  ┌──────┐  ┌──┐ ┌──┐ ┌──┐ ┌──┐ ┌──┐  ┌──────────┐           │     │   │
│  │  │  │TLS   │→│P1│→│P2│→│P3│→│P4│→│P5│→│ Upstream  │           │     │   │
│  │  │  │Parse │  │  │ │  │ │  │ │  │ │  │  │ Proxy    │           │     │   │
│  │  │  │Route │  └──┘ └──┘ └──┘ └──┘ └──┘  │          │           │     │   │
│  │  │  │Match │                              └──────────┘           │     │   │
│  │  │  └──────┘                                                     │     │   │
│  │  │                                                                │     │   │
│  │  │  P1: IP Whitelist/Blacklist     P4: Request Transform         │     │   │
│  │  │  P2: Authentication (JWT/Key)   P5: Response Transform        │     │   │
│  │  │  P3: Rate Limiting              (order is configurable)       │     │   │
│  │  └─────────────────────────────────────────────────────────────────┘     │   │
│  │                                                                          │   │
│  │  ┌───────────────┐  ┌───────────────┐  ┌──────────────────────┐         │   │
│  │  │ Route Table    │  │ Plugin Runtime │  │ Circuit Breaker      │         │   │
│  │  │ (in-memory     │  │ (WASM / Lua /  │  │ State Machine        │         │   │
│  │  │  radix trie)   │  │  native)       │  │ (per upstream)       │         │   │
│  │  └───────────────┘  └───────────────┘  └──────────────────────┘         │   │
│  │                                                                          │   │
│  │  ┌───────────────┐  ┌───────────────┐  ┌──────────────────────┐         │   │
│  │  │ Connection Pool│  │ Local Cache    │  │ Metrics Emitter      │         │   │
│  │  │ Manager        │  │ (response +    │  │ (StatsD/OTel)        │         │   │
│  │  │ (per upstream) │  │  auth tokens)  │  │                      │         │   │
│  │  └───────────────┘  └───────────────┘  └──────────────────────┘         │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                                                                 │
│  Nodes: 60+ across 3 AZs (auto-scaling group)                                 │
│  Each node: 32-core, 64GB RAM, 25Gbps NIC                                     │
└────────┬──────────────────┬─────────────────────┬───────────────────────────────┘
         │                  │                     │
         ▼                  ▼                     ▼
┌─────────────────┐ ┌──────────────────┐ ┌──────────────────────────────────────┐
│ Control Plane    │ │ Rate Limit Store │ │ Backend Services                      │
│                  │ │ (Redis Cluster)  │ │                                      │
│ ┌──────────────┐│ │                  │ │  ┌────────┐ ┌────────┐ ┌────────┐   │
│ │ Config Store  ││ │ - Sliding window │ │  │ Svc A  │ │ Svc B  │ │ Svc C  │   │
│ │ (etcd)       ││ │   counters       │ │  │ (3 inst)│ │(10 inst)│ │(5 inst)│   │
│ ├──────────────┤│ │ - Quota tracking │ │  └────────┘ └────────┘ └────────┘   │
│ │ Admin API    ││ │ - Token buckets  │ │                                      │
│ ├──────────────┤│ └──────────────────┘ │  ... 500 services, 25K instances     │
│ │ Config Push  ││                      │                                      │
│ │ (watch/push) ││                      └──────────────────────────────────────┘
│ └──────────────┘│
│                  │  ┌─────────────────────────────────────────┐
│ ┌──────────────┐│  │ Observability Stack                      │
│ │ Service      ││  │                                          │
│ │ Registry     ││  │  ┌──────────┐ ┌────────┐ ┌───────────┐ │
│ │ (Consul/K8s) ││  │  │Prometheus│ │ Jaeger │ │ ELK/Loki  │ │
│ └──────────────┘│  │  │ (metrics)│ │(traces)│ │ (logs)    │ │
└─────────────────┘  │  └──────────┘ └────────┘ └───────────┘ │
                     └─────────────────────────────────────────┘
```

---

## 4. The Request Processing Pipeline (The Core Abstraction)

This is the most important architectural decision. The gateway is a **programmable pipeline** — an ordered chain of middleware/plugins that each request flows through.

### 4a. Pipeline Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                     REQUEST LIFECYCLE                                │
│                                                                     │
│  ═══════════════ INBOUND PHASE ═══════════════                     │
│                                                                     │
│  1. TCP Accept + TLS Handshake                                     │
│  2. HTTP Parse (method, path, headers, body)                       │
│  3. Route Matching (radix trie lookup)                             │
│  4. Route-specific plugin chain resolved                           │
│                                                                     │
│  ═══════════════ PLUGIN CHAIN (configurable order) ════════════    │
│                                                                     │
│  ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────────┐       │
│  │ Plugin 1 │──►│ Plugin 2 │──►│ Plugin 3 │──►│ Plugin N     │      │
│  │ (Auth)   │   │ (RateL)  │   │ (Xform)  │   │ (Custom)    │      │
│  │          │   │          │   │          │   │             │       │
│  │ on_req ──┼──►│ on_req ──┼──►│ on_req ──┼──►│ on_req ───┐│       │
│  │          │   │          │   │          │   │            ││       │
│  │          │   │          │   │          │   │    ┌───────┘│       │
│  │ on_res◄──┼───│ on_res◄──┼───│ on_res◄──┼───│ on_res     │       │
│  └─────────┘   └─────────┘   └─────────┘   └─────────────┘       │
│       │                                           │                 │
│       │        Any plugin can short-circuit:       │                │
│       │        - Auth fails → 401 immediately     │                 │
│       │        - Rate limit → 429 immediately     │                 │
│       │        - Cache hit → 200 immediately      │                 │
│                                                                     │
│  ═══════════════ UPSTREAM PHASE ══════════════                     │
│                                                                     │
│  5. Select backend instance (load balancing)                       │
│  6. Circuit breaker check                                          │
│  7. Forward request (connection pool)                              │
│  8. Receive response                                               │
│                                                                     │
│  ═══════════════ OUTBOUND PHASE ═════════════                      │
│                                                                     │
│  9. Plugin chain (reverse order) — response transforms             │
│  10. Emit metrics + trace spans                                    │
│  11. Write access log entry                                        │
│  12. Send response to client                                       │
└─────────────────────────────────────────────────────────────────────┘
```

### 4b. Plugin Interface

```go
// Core plugin interface — every plugin implements this
type Plugin interface {
    // Name returns the plugin identifier
    Name() string

    // Priority determines default execution order (lower = earlier)
    Priority() int

    // OnRequest is called during the inbound phase.
    // Return (nil, nil) to continue the chain.
    // Return (response, nil) to short-circuit with a response.
    // Return (nil, err) to abort with an error.
    OnRequest(ctx *RequestContext) (*Response, error)

    // OnResponse is called during the outbound phase (reverse order).
    // Can modify the response before it's sent to the client.
    OnResponse(ctx *RequestContext, resp *Response) (*Response, error)
}

// RequestContext carries all state through the pipeline
type RequestContext struct {
    // Immutable after creation
    Request     *http.Request
    Route       *RouteMatch
    ClientIP    net.IP
    TLSInfo     *tls.ConnectionState
    RequestID   string          // Unique trace ID (generated or from X-Request-Id)
    StartTime   time.Time

    // Mutable — plugins can attach metadata
    Metadata    map[string]interface{}  // Auth info, rate limit quota, etc.

    // Set by auth plugin
    Consumer    *Consumer       // Authenticated API consumer (nil if anonymous)

    // Tracing
    Span        trace.Span
}

// Consumer represents an authenticated API client
type Consumer struct {
    ID          string
    AppName     string
    Plan        string          // "free", "pro", "enterprise"
    Scopes      []string        // Authorized API scopes
    Metadata    map[string]string
}
```

### 4c. Plugin Execution Model — Performance Considerations

```
Option A: Sequential in-process (chosen)
  Each plugin runs in the same goroutine/thread as the request.
  Pros: Zero serialization overhead, shared memory, ~microseconds per plugin
  Cons: A buggy plugin can crash the process

Option B: Out-of-process (gRPC sidecar per plugin)
  Each plugin is a separate process; gateway calls it via gRPC/Unix socket.
  Pros: Fault isolation, language-agnostic plugins
  Cons: ~0.5-1ms overhead PER PLUGIN PER REQUEST (unacceptable at 2M req/sec)

Option C: WASM sandbox (hybrid approach for untrusted plugins)
  Compile plugins to WebAssembly; run in a sandboxed WASM runtime (Envoy + Wasm).
  Pros: Near-native speed (~10-50μs overhead), memory isolation, language-agnostic
  Cons: Limited WASM ecosystem, debugging is harder

DECISION: Use Option A (in-process) for core plugins (auth, rate-limit, routing)
          with Option C (WASM) for user-provided custom plugins.
          This matches Envoy's production model.

Pipeline overhead budget:
  TLS termination:     ~0.1ms (with session resumption)
  Route matching:      ~0.005ms (radix trie)
  Auth (JWT validate): ~0.05ms (cached public key, no network call)
  Rate limit check:    ~0.1ms (local counter + async Redis sync)
  Request transform:   ~0.02ms (header manipulation)
  Total overhead:      ~0.3ms p50 — well within our 2ms budget
```

---

## 5. Component Deep Dives

### 5a. Request Routing

The router must match an incoming request to a backend service configuration in **microseconds**.

```
Data structure: Compressed Radix Trie (also called Patricia Trie)

Example route table:
  /api/v1/users              → user-service
  /api/v1/users/:id          → user-service
  /api/v1/users/:id/orders   → order-service
  /api/v2/users              → user-service-v2
  /api/v1/payments           → payment-service
  /api/v1/payments/:id       → payment-service
  /health                    → local-handler

Trie structure:
                        (root)
                       /      \
                   /api         /health → local
                   /
                 /v1            /v2
                / | \            \
          /users /pay..      /users → user-svc-v2
           /  \      \
        (leaf) /:id  /:id → payment-svc
         ↓      / \
   user-svc  (leaf) /orders
               ↓        ↓
          user-svc  order-svc

Lookup: O(length of path) — typically ~50-100 chars = O(1) effective
Memory: ~50KB for 5,000 routes
```

**Route matching supports multiple strategies**:

```yaml
# Route configuration example (YAML → stored in etcd)
routes:
  - id: "user-service-v1"
    match:
      paths:
        - "/api/v1/users"
        - "/api/v1/users/*"
      methods: ["GET", "POST", "PUT", "DELETE"]
      headers:
        X-Api-Version: "2024-01-01"     # Optional header match
    upstream:
      service: "user-service"
      load_balancing: "round-robin"
      timeout_ms: 3000
      retries: 2
    plugins:
      - name: "jwt-auth"
        config: { required_scopes: ["users:read", "users:write"] }
      - name: "rate-limit"
        config: { requests_per_second: 100, burst: 200 }
      - name: "circuit-breaker"
        config: { failure_threshold: 50, window_sec: 60, recovery_sec: 30 }

  - id: "payment-canary"
    match:
      paths: ["/api/v1/payments/*"]
    upstream:
      targets:
        - service: "payment-service-v2"
          weight: 10                     # 10% canary traffic
        - service: "payment-service-v1"
          weight: 90                     # 90% stable
```

**Hot reload**: When route config changes in etcd, gateway nodes receive a watch notification and atomically swap the trie pointer. Zero downtime, zero dropped requests.

```
Config update flow:
  Admin API → etcd write → etcd watch fires on all nodes
  → each node builds new trie in background
  → atomic pointer swap (single CAS operation)
  → old trie garbage collected

Time from write to enforcement: < 2 seconds
```

---

### 5b. Authentication & Authorization

The gateway handles **AuthN** (who are you?) and coarse-grained **AuthZ** (are you allowed to call this API?). Fine-grained AuthZ remains in backend services.

```
┌─────────────────────────────────────────────────────────────────┐
│                   AUTHENTICATION FLOW                            │
│                                                                 │
│  Request arrives with credentials                               │
│       │                                                         │
│       ▼                                                         │
│  ┌──────────────────┐                                          │
│  │ Credential        │  Detect credential type from request:    │
│  │ Extractor         │  - Authorization: Bearer <jwt>           │
│  │                   │  - X-API-Key: <key>                      │
│  └────────┬─────────┘  - mTLS client certificate               │
│           │             - OAuth2 token                           │
│           ▼                                                     │
│  ┌──────────────────────────────────────────────┐              │
│  │              VALIDATION                       │              │
│  │                                               │              │
│  │  JWT Path:                                    │              │
│  │  1. Decode header (no verification yet)        │              │
│  │  2. Lookup JWKS by issuer (cached locally,    │              │
│  │     refresh every 5 min or on kid miss)       │              │
│  │  3. Verify signature (RS256/ES256)            │              │
│  │  4. Check exp, nbf, aud, iss claims           │              │
│  │  5. Extract scopes/roles from claims          │              │
│  │                                               │              │
│  │  API Key Path:                                │              │
│  │  1. Hash key: SHA256(key)                     │              │
│  │  2. Lookup in local cache (LRU, TTL=60s)      │              │
│  │  3. Cache miss → query key store (DynamoDB)   │              │
│  │  4. Verify key is active, not revoked         │              │
│  │  5. Load consumer metadata + plan             │              │
│  │                                               │              │
│  │  mTLS Path:                                   │              │
│  │  1. Client cert validated during TLS handshake│              │
│  │  2. Extract CN/SAN from cert                  │              │
│  │  3. Map to service identity                   │              │
│  └────────┬─────────────────────────────────────┘              │
│           │                                                     │
│           ▼                                                     │
│  ┌──────────────────┐                                          │
│  │ Authorization     │  Check: consumer's scopes ⊇ route's     │
│  │ Check             │  required scopes?                        │
│  │                   │                                          │
│  │                   │  Evaluation: O(1) bitset intersection    │
│  │                   │  (scopes mapped to bit positions at       │
│  │                   │   config load time)                      │
│  └────────┬─────────┘                                          │
│           │                                                     │
│           ├─── PASS → set ctx.Consumer, continue pipeline       │
│           └─── FAIL → return 401/403 immediately                │
└─────────────────────────────────────────────────────────────────┘
```

**Performance critical detail — JWKS caching**:

```
JWT validation is CPU-intensive (crypto signature verification).
Optimization layers:

1. JWKS Public Key Cache
   - Cache JWKS per issuer in-memory
   - Refresh: background goroutine every 5 min + on unknown kid
   - Eliminates network call on every request

2. Validated Token Cache (controversial but necessary at scale)
   - LRU cache: SHA256(token) → validated claims
   - TTL: min(token_exp, 60 seconds)
   - Hit rate: ~80% (clients reuse tokens across requests)
   - Saves ~0.05ms of crypto verification per cache hit
   - Risk: revoked tokens valid until cache TTL expires
   - Mitigation: short TTL + revocation event flushes cache

3. Pre-computed Scope Bitmasks
   - At config load: each scope string → bit position
   - Consumer scopes → 64-bit bitmask
   - Route required scopes → 64-bit bitmask
   - Auth check: (consumer_mask & required_mask) == required_mask
   - O(1) instead of O(n) set intersection
```

---

### 5c. Rate Limiting

Rate limiting must be **fast** (hot path), **accurate** (don't allow significant overshoot), and **fair** (don't penalize well-behaved clients because of noisy neighbors).

#### Algorithm: Sliding Window Counter (Hybrid)

```
Why not simpler algorithms?

Fixed Window Counter:
  Problem: Boundary burst. 100 req/min limit.
  Client sends 100 requests at 0:59, then 100 at 1:00.
  200 requests in 2 seconds — 2x the intended rate.

Token Bucket:
  Problem: Allows sustained bursts. Good for smoothing, but
  hard to reason about cumulative quotas (e.g., 10,000 req/day).

Sliding Window Log:
  Problem: Memory-intensive. Stores timestamp of every request.
  At 1M req/sec, that's 1M entries per window per key.

Sliding Window Counter (chosen):
  Best of both worlds. Fixed-window memory efficiency +
  sliding-window accuracy.
```

**How it works**:

```
Window size: 60 seconds
Current time: 75 seconds into current minute (01:15)
Rate limit: 100 requests/minute

Previous window (00:00-01:00): 84 requests
Current window  (01:00-02:00): 36 requests so far

Weighted count = prev_count × overlap_fraction + curr_count
               = 84 × (45/60) + 36
               = 84 × 0.75 + 36
               = 63 + 36
               = 99

99 < 100 → ALLOW (but very close to limit)

This gives ~99.97% accuracy compared to true sliding window,
with O(1) memory per key (just two counters + two timestamps).
```

#### Architecture: Two-Tier Rate Limiting

```
┌──────────────────────────────────────────────────────────────────┐
│                   TWO-TIER RATE LIMITING                         │
│                                                                  │
│  TIER 1: LOCAL (per gateway node) — sub-microsecond             │
│  ┌────────────────────────────────────────┐                     │
│  │ In-memory sliding window counters       │                     │
│  │                                         │                     │
│  │ Key: (consumer_id, route_id)            │                     │
│  │ Value: {prev_count, curr_count, ts}     │                     │
│  │                                         │                     │
│  │ Per-node limit = global_limit / N_nodes │                     │
│  │                                         │                     │
│  │ Fast path: increment + check locally    │                     │
│  │ No network call. ~100ns per check.      │                     │
│  └───────────────────┬────────────────────┘                     │
│                      │                                           │
│                      │ Async sync every 1 second                 │
│                      ▼                                           │
│  TIER 2: GLOBAL (Redis Cluster) — accurate cross-node           │
│  ┌────────────────────────────────────────┐                     │
│  │ Redis sliding window counters           │                     │
│  │                                         │                     │
│  │ Lua script (atomic):                    │                     │
│  │   MULTI                                 │                     │
│  │     INCRBY key:{window} delta           │                     │
│  │     EXPIRE key:{window} 2*window_size   │                     │
│  │   EXEC                                  │                     │
│  │   return current_count                  │                     │
│  │                                         │                     │
│  │ Node receives actual global count       │                     │
│  │ Adjusts local threshold accordingly     │                     │
│  └────────────────────────────────────────┘                     │
│                                                                  │
│  WHY TWO TIERS:                                                 │
│  - Local-only: inaccurate (each node sees partial traffic)       │
│  - Redis-only: too slow for hot path (adds ~0.5ms per request)   │
│  - Two-tier: fast local decisions + periodic global correction   │
│  - Accuracy: within ~5% of true global limit at all times       │
│                                                                  │
│  FAILURE MODE:                                                  │
│  - If Redis is down: fall back to local-only limiting            │
│  - Local limit = global_limit / N_nodes (conservative)          │
│  - Accept slight inaccuracy over blocking all traffic            │
└──────────────────────────────────────────────────────────────────┘
```

#### Rate Limit Response Headers

```
HTTP/1.1 429 Too Many Requests
Retry-After: 2
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1703001300
Content-Type: application/json

{
  "error": {
    "code": "rate_limit_exceeded",
    "message": "API rate limit exceeded. Retry after 2 seconds.",
    "retry_after_ms": 2000
  }
}
```

---

### 5d. Circuit Breaker

Prevents a failing backend from consuming gateway resources and cascading failures to other services.

#### State Machine

```
                     failure_count < threshold
                    ┌──────────────────────┐
                    │                      │
                    ▼                      │
              ┌──────────┐                │
     ────────►│  CLOSED   │────────────────┘
              │ (normal)  │
              └─────┬─────┘
                    │ failure_count >= threshold
                    │ within window
                    ▼
              ┌──────────┐
              │   OPEN    │  All requests fail fast with 503
              │ (tripped) │  No traffic sent to backend
              └─────┬─────┘
                    │ recovery_timeout expires
                    ▼
              ┌──────────┐
              │ HALF-OPEN │  Allow probe_count requests through
              │ (probing) │
              └─────┬─────┘
                   / \
                  /   \
          success     failure
            /           \
           ▼             ▼
      ┌──────────┐  ┌──────────┐
      │  CLOSED   │  │   OPEN    │
      │ (recover) │  │ (re-trip) │
      └──────────┘  └──────────┘
```

#### Implementation

```go
type CircuitBreaker struct {
    mu              sync.RWMutex
    state           State           // CLOSED, OPEN, HALF_OPEN
    failureCount    int64
    successCount    int64
    lastFailureTime time.Time
    config          CircuitBreakerConfig
}

type CircuitBreakerConfig struct {
    FailureThreshold    int           // Failures to trip (e.g., 50)
    FailureWindow       time.Duration // Window for counting failures (e.g., 60s)
    RecoveryTimeout     time.Duration // Time in OPEN before probing (e.g., 30s)
    ProbeCount          int           // Requests to allow in HALF_OPEN (e.g., 5)
    SuccessThreshold    int           // Successes in HALF_OPEN to close (e.g., 3)
    // What counts as failure:
    FailureStatusCodes  []int         // e.g., [500, 502, 503, 504]
    TimeoutMs           int           // Backend response timeout
}

func (cb *CircuitBreaker) Allow() (bool, error) {
    cb.mu.RLock()
    defer cb.mu.RUnlock()

    switch cb.state {
    case CLOSED:
        return true, nil
    case OPEN:
        if time.Since(cb.lastFailureTime) > cb.config.RecoveryTimeout {
            cb.transitionTo(HALF_OPEN)
            return true, nil // Allow probe
        }
        return false, ErrCircuitOpen
    case HALF_OPEN:
        if cb.probeCount < cb.config.ProbeCount {
            return true, nil
        }
        return false, ErrCircuitOpen
    }
}

func (cb *CircuitBreaker) RecordResult(success bool) {
    cb.mu.Lock()
    defer cb.mu.Unlock()

    if success {
        if cb.state == HALF_OPEN {
            cb.successCount++
            if cb.successCount >= cb.config.SuccessThreshold {
                cb.transitionTo(CLOSED)
            }
        }
        // Reset failure count on success in CLOSED state
        if cb.state == CLOSED {
            cb.failureCount = 0
        }
    } else {
        cb.failureCount++
        cb.lastFailureTime = time.Now()
        if cb.state == CLOSED && cb.failureCount >= cb.config.FailureThreshold {
            cb.transitionTo(OPEN)
            cb.emitAlert("circuit_breaker_opened", cb.upstream)
        }
        if cb.state == HALF_OPEN {
            cb.transitionTo(OPEN) // Back to open on any failure
        }
    }
}
```

**Circuit breaker is PER UPSTREAM SERVICE, not global.** If payment-service is down, user-service traffic is unaffected.

**Response when circuit is open**:
```
HTTP/1.1 503 Service Unavailable
Retry-After: 30
X-Circuit-State: open
X-Circuit-Backend: payment-service

{
  "error": {
    "code": "service_unavailable",
    "message": "Backend service temporarily unavailable. The gateway is protecting the service from overload.",
    "retry_after_ms": 30000
  }
}
```

---

### 5e. Load Balancing to Backends

```
Supported strategies:

1. Round Robin (default)
   Simple, fair distribution. Works well when backends are homogeneous.

2. Weighted Round Robin
   For heterogeneous instances (e.g., different instance sizes during migration).

3. Least Connections
   Route to the instance with fewest active requests.
   Best for variable-latency backends.

4. Consistent Hashing
   Route based on hash of a request attribute (e.g., user_id).
   Provides session affinity + optimal cache hit rates on backends.

5. P2C (Power of Two Choices) — RECOMMENDED for most cases
   Pick 2 random backends, route to the one with fewer active connections.
   Near-optimal distribution with O(1) decision time.
   No coordination needed. Used by Envoy and gRPC internally.

Health checking:
  - Active:  HTTP GET /health every 5s per backend instance
  - Passive: Track 5xx responses; if >50% in 30s window → mark unhealthy
  - Unhealthy instances removed from rotation
  - Re-added after 3 consecutive healthy active checks
```

---

## 6. Control Plane

The control plane manages configuration, service discovery, and provides the admin API. It is **not in the request hot path**.

```
┌─────────────────────────────────────────────────────────────┐
│                     CONTROL PLANE                            │
│                                                             │
│  ┌───────────────┐    ┌──────────────┐   ┌──────────────┐  │
│  │ Admin API      │    │ Config Store  │   │ Service       │  │
│  │ (REST + gRPC)  │───►│ (etcd cluster)│   │ Registry      │  │
│  │                │    │              │   │ (Consul/K8s)  │  │
│  │ CRUD:          │    │ Watch-based  │   │              │  │
│  │ - Routes       │    │ push to all  │   │ Real-time     │  │
│  │ - Consumers    │    │ gateway nodes│   │ backend       │  │
│  │ - Plugins      │    │              │   │ discovery     │  │
│  │ - Rate policies│    │ Versioned    │   │              │  │
│  │ - Certificates │    │ configs with │   │ Health-aware  │  │
│  │                │    │ rollback     │   │              │  │
│  └───────────────┘    └──────┬───────┘   └──────┬───────┘  │
│                              │                   │           │
│                              └─────────┬─────────┘          │
│                                        │                     │
│                              ┌─────────▼────────┐           │
│                              │ Config Compiler    │           │
│                              │                    │           │
│                              │ Merges:            │           │
│                              │ - Route definitions│           │
│                              │ - Service endpoints│           │
│                              │ - Plugin configs   │           │
│                              │ - TLS certificates │           │
│                              │                    │           │
│                              │ Produces:          │           │
│                              │ Immutable config   │           │
│                              │ snapshot (versioned)│          │
│                              └─────────┬─────────┘          │
│                                        │                     │
│                                        ▼                     │
│                           Push to all gateway nodes          │
│                           (etcd watch / gRPC stream)        │
│                                                             │
│  Config update safety:                                      │
│  1. Validate config (schema + semantic checks)              │
│  2. Canary to 1 node, observe error rates for 30s           │
│  3. Roll out to 10%, then 50%, then 100%                    │
│  4. Auto-rollback if error rate spikes > 2x baseline       │
└─────────────────────────────────────────────────────────────┘
```

### Config Data Model

```yaml
# Stored in etcd as versioned JSON, shown here as YAML for readability

# /config/routes/user-service-v1
route:
  id: "user-service-v1"
  version: 42                          # Monotonic version for CAS updates
  created_at: "2024-01-15T..."
  updated_at: "2024-03-01T..."
  match:
    hosts: ["api.example.com"]
    paths: ["/api/v1/users", "/api/v1/users/*"]
    methods: ["GET", "POST", "PUT", "DELETE"]
  upstream:
    service_name: "user-service"       # Resolved via service registry
    load_balancing: "p2c"
    timeout_ms: 3000
    connect_timeout_ms: 500
    retries: 2
    retry_on: ["502", "503", "reset"]
  plugins:
    - name: "jwt-auth"
      enabled: true
      config:
        issuers: ["https://auth.example.com"]
        required_scopes: ["users:read"]
        clock_skew_seconds: 30
    - name: "rate-limit"
      enabled: true
      config:
        default_limit:
          requests: 100
          window: "1m"
        plan_overrides:
          enterprise:
            requests: 10000
            window: "1m"
    - name: "circuit-breaker"
      enabled: true
      config:
        failure_threshold: 50
        failure_window: "60s"
        recovery_timeout: "30s"

# /config/consumers/acme-corp
consumer:
  id: "acme-corp"
  api_key_hash: "sha256:a1b2c3..."     # Never store plaintext keys
  plan: "enterprise"
  scopes: ["users:read", "users:write", "payments:read"]
  rate_limit_override: null              # Uses plan default
  metadata:
    company: "Acme Corp"
    contact: "dev@acme.com"
```

---

## 7. Observability

An API gateway has **unique observability leverage** — it sees every request, making it the ideal place to collect platform-wide metrics and traces.

### 7a. Structured Access Log

```json
{
  "timestamp": "2024-03-01T12:00:00.123Z",
  "request_id": "req_abc123def456",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",

  "client": {
    "ip": "203.0.113.42",
    "consumer_id": "acme-corp",
    "user_agent": "AcmeApp/2.1.0"
  },

  "request": {
    "method": "POST",
    "path": "/api/v1/payments",
    "host": "api.example.com",
    "content_length": 256,
    "protocol": "HTTP/2"
  },

  "route": {
    "id": "payment-service-v1",
    "upstream": "payment-service"
  },

  "response": {
    "status": 201,
    "content_length": 512,
    "content_type": "application/json"
  },

  "timing": {
    "total_ms": 45.2,
    "gateway_overhead_ms": 1.8,
    "upstream_connect_ms": 0.5,
    "upstream_response_ms": 42.9,
    "tls_handshake_ms": 0.0
  },

  "plugins": {
    "auth": { "method": "jwt", "duration_us": 120 },
    "rate_limit": { "remaining": 847, "limit": 1000, "duration_us": 15 },
    "circuit_breaker": { "state": "closed" }
  },

  "upstream": {
    "address": "10.0.5.23:8080",
    "attempts": 1
  }
}
```

### 7b. Key Metrics (Prometheus / OpenTelemetry)

```
# REQUEST METRICS (RED method)
gateway_requests_total{route, method, status, consumer}           # Rate
gateway_request_duration_seconds{route, method, status}           # Duration (histogram)
gateway_request_errors_total{route, method, error_type}           # Errors

# UPSTREAM METRICS
gateway_upstream_request_duration_seconds{upstream, instance}
gateway_upstream_connect_duration_seconds{upstream}
gateway_upstream_active_connections{upstream, instance}
gateway_upstream_health{upstream, instance}                       # 1=healthy, 0=unhealthy

# RATE LIMITING
gateway_rate_limit_decisions_total{consumer, route, decision}     # allowed/rejected
gateway_rate_limit_remaining{consumer, route}                     # Current headroom

# CIRCUIT BREAKER
gateway_circuit_breaker_state{upstream}                           # 0=closed, 1=open, 2=half_open
gateway_circuit_breaker_transitions_total{upstream, from, to}

# PLUGIN PERFORMANCE
gateway_plugin_duration_seconds{plugin_name, phase}               # request/response phase

# INFRASTRUCTURE
gateway_active_connections{}                                      # Current WebSocket + HTTP connections
gateway_config_version{}                                          # Current config version (detect skew)
gateway_config_reload_duration_seconds{}
```

### 7c. Distributed Tracing

```
The gateway creates the ROOT SPAN for every trace:

Trace: req_abc123
├── [gateway] route-match          0.005ms
├── [gateway] plugin:jwt-auth      0.120ms
├── [gateway] plugin:rate-limit    0.015ms
├── [gateway] upstream-request     42.9ms
│   ├── [gateway] connect          0.5ms
│   ├── [payment-svc] process      41.2ms  ← backend adds child spans
│   │   ├── [payment-svc] db-query 12.3ms
│   │   └── [payment-svc] stripe   28.1ms
│   └── [gateway] read-response    1.2ms
└── [gateway] plugin:response-xform 0.02ms

Total: 45.2ms

Propagation: W3C Trace Context headers
  traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
  tracestate: gateway=req_abc123

The gateway injects these headers into upstream requests,
enabling end-to-end traces across all services.
```

### 7d. Alerting Priorities

```
P0 (Page immediately):
  - gateway_request_errors_total spike > 5x baseline (5 min window)
  - gateway_upstream_health == 0 for ALL instances of a service
  - gateway_config_reload failures
  - gateway_active_connections approaching system limit (ulimit)

P1 (Alert within 15 min):
  - circuit_breaker_state == OPEN for any upstream
  - p99 latency > 5x baseline for any route
  - rate_limit rejection rate > 20% for any consumer
  - TLS certificate expiring within 7 days

P2 (Daily review):
  - Config version skew between nodes > 5 minutes
  - Upstream retry rate > 10%
  - Unused routes (0 requests in 7 days)
  - Consumers approaching rate limit (> 80% utilization)
```

---

## 8. Failure Modes & Resilience

```
┌──────────────────────┬──────────────────────────────────────────────────┐
│ Failure              │ Mitigation                                       │
├──────────────────────┼──────────────────────────────────────────────────┤
│ Gateway node crash   │ LB detects via health check (2s), drains        │
│                      │ traffic. Stateless nodes — no data loss.         │
│                      │ Auto-scaling replaces instance.                  │
├──────────────────────┼──────────────────────────────────────────────────┤
│ Redis (rate limit)   │ Fall back to local-only rate limiting.           │
│ down                 │ Per-node limit = global / N. Conservative but    │
│                      │ never blocks legitimate traffic.                 │
├──────────────────────┼──────────────────────────────────────────────────┤
│ etcd (config store)  │ Gateway continues with last-known config.       │
│ down                 │ Config updates paused. No impact on request      │
│                      │ processing (config is cached in-memory).        │
├──────────────────────┼──────────────────────────────────────────────────┤
│ Backend service down │ Circuit breaker trips → fast 503 responses.     │
│                      │ Other backends unaffected (isolated breakers).   │
│                      │ Retry on alternate instances if available.       │
├──────────────────────┼──────────────────────────────────────────────────┤
│ Backend slow (brown  │ Timeout at configured threshold (e.g., 3s).     │
│ out, not down)       │ Circuit breaker tracks timeouts as failures.    │
│                      │ Prevents thread/connection pool exhaustion.      │
├──────────────────────┼──────────────────────────────────────────────────┤
│ DDoS / traffic spike │ Multi-layer defense:                             │
│                      │ 1. L4 LB: SYN flood protection, connection limit│
│                      │ 2. Gateway: IP-level rate limiting               │
│                      │ 3. Gateway: consumer-level rate limiting         │
│                      │ 4. Auto-scaling based on CPU/connection count   │
├──────────────────────┼──────────────────────────────────────────────────┤
│ Bad config pushed    │ Canary rollout (1 node → 10% → 100%).          │
│                      │ Auto-rollback if error rate increases.           │
│                      │ Config validation before apply.                  │
├──────────────────────┼──────────────────────────────────────────────────┤
│ TLS cert expiry      │ Automated cert rotation (Let's Encrypt / ACME). │
│                      │ Alert at 30d, 7d, 1d before expiry.            │
│                      │ Cert stored in config store, hot-reloaded.      │
├──────────────────────┼──────────────────────────────────────────────────┤
│ Plugin crash         │ WASM sandbox catches panics, returns 500.       │
│                      │ Core plugins run in-process with recovery.       │
│                      │ Plugin error rate metric triggers auto-disable. │
└──────────────────────┴──────────────────────────────────────────────────┘

CRITICAL DESIGN PRINCIPLE:
  The gateway must NEVER be less available than the backends it protects.
  Every dependency (Redis, etcd, service registry) must have a
  degraded-but-functional fallback mode.
```

---

## 9. Security Considerations

```
1. TLS EVERYWHERE
   - TLS 1.3 termination at gateway (TLS 1.2 minimum)
   - mTLS to backend services (zero-trust internal network)
   - Certificate pinning for critical upstreams
   - OCSP stapling for fast revocation checks

2. INPUT VALIDATION
   - Request size limits (body: 10MB default, configurable per route)
   - Header count/size limits (prevent slowloris-style attacks)
   - Path traversal protection (normalize /api/../admin → reject)
   - SQL injection / XSS detection (basic patterns — defer to WAF for full protection)

3. SECRET MANAGEMENT
   - API keys stored as SHA256 hashes (never plaintext)
   - TLS private keys in HSM or Vault, loaded at startup
   - Plugin configs may contain secrets → encrypted at rest in etcd
   - Admin API requires separate auth (not API key — mTLS or IAM)

4. AUDIT LOGGING
   - All config changes logged with who/what/when
   - All admin API calls logged
   - Rate limit overrides logged
   - Circuit breaker state changes logged

5. MULTI-TENANCY ISOLATION
   - Consumer quotas enforced at gateway (prevents noisy neighbor)
   - Per-consumer connection limits to backends
   - Request isolation: one consumer's slow request doesn't
     block another consumer's fast request (async I/O / goroutines)
```

---

## 10. Trade-off Summary

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| **Architecture** | Stateless nodes + external state | Stateful nodes | Horizontal scaling, simple failover, no data loss on crash |
| **Plugin model** | In-process (core) + WASM (custom) | Out-of-process (gRPC) | Sub-ms overhead for core plugins; WASM gives isolation for untrusted code without gRPC latency |
| **Rate limiting** | Two-tier (local + Redis) | Redis-only | Eliminates Redis from hot path; local handles 99% of decisions; Redis for global accuracy |
| **Config store** | etcd with watch-based push | Polling config file | Sub-second propagation; consistent view; built-in HA |
| **Routing** | Radix trie | Linear scan / regex list | O(path length) lookup vs O(n routes); critical at 5000+ routes |
| **Load balancing** | P2C (Power of Two Choices) | Round robin / least-conn | Near-optimal with O(1) decision, no global state, handles heterogeneous latencies |
| **Circuit breaker** | Per-upstream state machine | Global breaker / none | Fault isolation — one bad backend doesn't affect others |
| **Protocol** | HTTP/2 to clients, gRPC to backends | HTTP/1.1 throughout | Multiplexing reduces connection overhead; gRPC for efficient internal communication |

---

## 11. Evolution Path

```
Phase 1: Foundation
  - HTTP reverse proxy with path-based routing
  - API key authentication
  - Fixed-window rate limiting (in-memory only)
  - Basic access logging
  - 5-10 backend services
  - Single region deployment

Phase 2: Production
  - JWT + OAuth 2.0 authentication
  - Sliding window rate limiting (local + Redis)
  - Circuit breakers
  - Health-aware load balancing (P2C)
  - etcd-based config with hot reload
  - Prometheus metrics + Jaeger tracing
  - Multi-AZ deployment
  - 50+ backend services

Phase 3: Platform
  - WASM plugin runtime for custom plugins
  - Developer portal with self-service API key management
  - Per-plan rate limiting and quota management
  - gRPC + WebSocket proxying
  - Canary config deployments with auto-rollback
  - Multi-region with geo-routing
  - 200+ backend services

Phase 4: Enterprise
  - GraphQL gateway (schema stitching / federation)
  - API marketplace (third-party API monetization)
  - Advanced analytics (usage patterns, anomaly detection)
  - Compliance: SOC2 audit logging, data residency routing
  - Custom SLA enforcement per consumer tier
  - AI-powered anomaly detection for abuse patterns
```

---

## 12. Interview Navigation Guide

**If interviewer asks "Why not just use Nginx/HAProxy?"**
> Nginx and HAProxy are excellent L4/L7 proxies, but they lack programmable middleware, dynamic config without reload, consumer-aware rate limiting, and native observability integration. An API gateway is an *application-layer* concern — it understands API semantics (auth, quotas, versioning), not just HTTP routing. That said, many gateways (Kong, APISIX) are built *on top of* Nginx (OpenResty) to get its performance while adding the application layer.

**If interviewer asks "How do you prevent the gateway from being a single point of failure?"**
> Three mechanisms: (1) Stateless design — any node can handle any request, no session affinity needed. (2) Multi-AZ deployment with health-aware LB — traffic automatically shifts away from failing AZs. (3) Graceful degradation — every external dependency (Redis, etcd, service registry) has a fallback mode where the gateway continues functioning with cached/local data. The gateway should never be less available than the services behind it.

**If interviewer asks "How does rate limiting work across multiple gateway nodes?"**
> Two-tier approach. Tier 1: local in-memory counters on each node for sub-microsecond decisions (per-node limit = global/N). Tier 2: periodic async sync to Redis for global accuracy. This gives us speed (no Redis in hot path) with accuracy (within 5% of true global limit). On Redis failure, we fall back to local-only with conservative limits.

**If interviewer asks "How do you handle config updates without downtime?"**
> Config stored in etcd. Gateway nodes subscribe via watch. On change: (1) validate new config, (2) build new routing trie / plugin chain in background, (3) atomic pointer swap — old requests complete on old config, new requests use new config. For safety: canary to one node first, monitor error rates for 30s, then gradual rollout. Auto-rollback if error rate spikes.

**If interviewer asks "What about WebSocket and streaming?"**
> The gateway upgrades HTTP connections to WebSocket and maintains a long-lived bidirectional proxy to the backend. Auth is validated during the initial HTTP upgrade request. Rate limiting applies to the connection establishment, not individual frames. For SSE (Server-Sent Events), the gateway proxies the long-lived response stream. Circuit breakers track connection failures, not individual messages. Key challenge: connection-level load balancing (can't round-robin individual messages), so we use consistent hashing by connection ID.

**If interviewer asks "How do you handle API versioning?"**
> Three strategies, configurable per route: (1) Path-based: `/v1/users` vs `/v2/users` route to different backends — simplest, most common. (2) Header-based: `X-Api-Version: 2024-01-01` routes to the appropriate backend version — cleaner URLs. (3) Content negotiation: `Accept: application/vnd.api+json;version=2`. The gateway resolves the version and routes accordingly. Old versions can be sunset by returning 410 Gone with a deprecation message.
