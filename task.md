# System Design Interview Prep — Principal / Sr. Staff Engineer

## Session Directive (paste at conversation start)

> You are my System Design interview coach targeting **Principal / Sr. Staff Engineer** level roles at top-tier companies (FAANG, Stripe, Databricks, etc.). Every session must be ruthlessly high-signal. No hand-holding. Challenge my thinking. Point out gaps immediately. Operate at the level of a bar-raiser interviewer who has rejected 80% of candidates.

---

## 1. Candidate Profile & Calibration

| Field | Value |
|---|---|
| **Target Level** | L7 / L8 (Principal / Sr. Staff) |
| **Years of Experience** | 20 Years |
| **Strongest Domains** |  distributed systems, backend , data intensive applications, real-time |
| **Weakest Domains** | ML infra, mobile, CDN internals |
| **Past Systems Built** | Fill in (brief bullets of production systems you owned) |
| **Target Companies** | MAANG, STRIPE , DATABRCIKS |
| **Interview Timeline** | 15 days |

---

## 2. What Principal / Sr. Staff Level Demands (Non-Negotiable)

At this level, interviewers are NOT looking for "a working design." They are evaluating:

### 2.1 — Thinking Dimensions Assessed

1. **Ambiguity Navigation** — You drive scoping. You don't ask "what should I do?" You propose constraints and negotiate.
2. **Multi-System Orchestration** — You design across service boundaries, org boundaries, and failure domains.
3. **Trade-off Articulation** — Every decision has a cost. You name it unprompted. You quantify when possible.
4. **Depth on Demand** — You can zoom into any box on your diagram and go 3 levels deep (data structures, algorithms, kernel behavior, protocol specifics).
5. **Operational Maturity** — Deployment, rollback, observability, incident response, capacity planning are PART of the design, not afterthoughts.
6. **Business-to-Tech Translation** — You connect technical choices to business outcomes (cost, time-to-market, reliability SLAs, regulatory compliance).
7. **Influence Without Authority** — Your design accounts for how multiple teams will adopt, migrate, and operate it.

### 2.2 — Common Rejection Reasons at This Level

- Design was "correct" but shallow — no depth beyond boxes and arrows.
- Couldn't articulate WHY a specific technology/pattern over alternatives.
- Ignored failure modes, data consistency edge cases.
- Couldn't estimate capacity / do back-of-envelope math confidently.
- Designed for Day 1 only — no evolution story (v1 -> v2 -> v3).
- Talked about systems in abstract — never referenced real-world experience or battle scars.

---

## 3. The Framework — SACRED

Use this structure for EVERY design session. Internalize it until it's reflexive.

### S — Scope & Scenarios
- Clarify functional requirements (core use cases, out-of-scope)
- Clarify non-functional requirements (latency, throughput, availability, consistency, durability)
- Identify users/actors and access patterns
- Define SLAs explicitly (e.g., p99 < 200ms, 99.99% availability)
- **Staff+ move**: Propose constraints yourself, don't wait to be told

### A — API & Interface Design
- Define external APIs (REST/gRPC/GraphQL + specific endpoints)
- Define internal service interfaces / contracts
- Define event schemas if event-driven
- Idempotency, versioning, pagination, rate limiting
- **Staff+ move**: Show how API evolves without breaking consumers

### C — Capacity & Constraints
- Back-of-envelope estimation (DAU, QPS, storage, bandwidth)
- Read:Write ratio and its implications
- Peak vs. steady state traffic
- Data growth over 1yr, 3yr, 5yr
- **Staff+ move**: Let capacity math DRIVE your architecture choices, not validate them after

### R — Raw Architecture
- High-level component diagram
- Data flow for primary use cases (happy path)
- Data flow for failure/edge cases
- Technology choices with explicit justification
- **Staff+ move**: Draw the ORGANIZATIONAL boundary, not just the system boundary

### E — Elaborate (Deep Dives)
- Data model and storage engine choices
- Consistency model (strong, eventual, causal — and WHY)
- Caching strategy (what, where, invalidation, thundering herd)
- Partitioning / sharding strategy
- Replication and failover
- Async processing, queues, backpressure
- Security, AuthN/AuthZ, encryption at rest and in transit
- **Staff+ move**: Pick the hardest part of the system and go 3 levels deep unprompted

### D — Defend & Evolve
- Failure scenarios and mitigations (network partition, node failure, poison pill messages, split brain)
- Monitoring, alerting, SLOs/SLIs
- Deployment strategy (canary, blue-green, feature flags)
- Cost analysis and optimization levers
- Migration path from current state (if applicable)
- Evolution roadmap: v1 (MVP) -> v2 (scale) -> v3 (global/multi-region)
- **Staff+ move**: Proactively discuss what you'd CHANGE if requirements shifted (10x traffic, new region, new compliance requirement)

---

## 4. Back-of-Envelope Cheat Sheet

### 4.1 — Latency Numbers (order of magnitude)

| Operation | Time |
|---|---|
| L1 cache reference | 1 ns |
| L2 cache reference | 4 ns |
| Main memory reference | 100 ns |
| SSD random read | 16 us |
| HDD random read | 2 ms |
| Round trip same datacenter | 0.5 ms |
| Round trip cross-continent | 150 ms |
| TCP handshake | 1 RTT |
| TLS handshake | 2 RTT |

### 4.2 — Throughput / Capacity Baselines

| Resource | Approximate Capacity |
|---|---|
| Single MySQL/Postgres instance | 5K-10K QPS (simple queries) |
| Redis (single node) | 100K+ QPS |
| Kafka (single partition) | 10K-100K msg/s (depends on size) |
| Kafka (cluster, many partitions) | millions msg/s |
| Single web server (CPU-bound) | 1K-10K req/s |
| Single web server (I/O-bound, async) | 10K-50K req/s |
| S3 PUT | 3,500 req/s per prefix |
| S3 GET | 5,500 req/s per prefix |
| DynamoDB (on-demand) | virtually unlimited (per-partition limits: 3K RCU, 1K WCU) |

### 4.3 — Storage Quick Math

| Fact | Value |
|---|---|
| 1 million seconds | ~11.5 days |
| 1 billion seconds | ~31.7 years |
| 1 char (ASCII) | 1 byte |
| 1 char (UTF-8 avg) | 2-3 bytes |
| UUID | 16 bytes (binary), 36 bytes (string) |
| Typical tweet-sized text | ~300 bytes |
| Typical JSON API response | 1-10 KB |
| 1080p image (compressed) | 200 KB - 2 MB |
| 1 min video (720p) | ~50 MB |
| 1 TB | ~1 billion KB |
| Seconds in a day | 86,400 (~10^5) |
| Seconds in a year | ~31.5 million (~3 * 10^7) |

---

## 5. Master Question Bank — 50 System Design Problems

> **Legend**: Each question is tagged with the primary skill it tests.
> `[SCALE]` = capacity planning & horizontal scaling | `[CONSISTENCY]` = distributed consistency & transactions | `[REAL-TIME]` = low-latency, streaming, live data | `[DATA]` = storage engine internals, data modeling | `[RELIABILITY]` = fault tolerance, failure modes, SLAs | `[PLATFORM]` = API design, multi-tenancy, developer experience | `[SECURITY]` = encryption, auth, compliance | `[ML]` = model serving, feature engineering
>
> **Difficulty**: Each question indicates the expected depth for Principal/Sr. Staff level in the description.

---

### Category A: Core Distributed Infrastructure (8 questions)

- [ ] **Q01. Distributed Key-Value Store** `[DATA]` `[CONSISTENCY]`
  LSM-tree vs B-tree internals, tunable consistency (quorum reads/writes), anti-entropy with Merkle trees, read repair, hinted handoff, gossip-based membership. Go to storage engine level — WAL, memtable, SSTable compaction strategies (size-tiered vs leveled). Compare Dynamo vs Cassandra vs etcd trade-offs.

- [ ] **Q02. Distributed Cache** `[SCALE]` `[RELIABILITY]`
  Consistent hashing with virtual nodes, eviction policies (LRU/LFU/W-TinyLFU), cache-aside vs write-through vs write-behind, thundering herd mitigation (request coalescing, probabilistic early expiration), hot-key handling, cache warming strategies, replica consistency. Discuss Memcached vs Redis Cluster architecture differences.

- [ ] **Q03. Distributed Message Queue** `[SCALE]` `[RELIABILITY]`
  Kafka-level depth: partitioning strategies, ISR (in-sync replicas), acks semantics, consumer groups, rebalancing protocol, exactly-once with idempotent producers + transactional consumers, compacted topics, tiered storage. Compare Kafka vs Pulsar vs Redpanda. When would you NOT use Kafka?

- [ ] **Q04. Distributed Locking Service** `[CONSISTENCY]` `[RELIABILITY]`
  Chubby/ZooKeeper-level: consensus-backed lock acquisition, fencing tokens to prevent stale locks, session management with heartbeats, lock contention strategies (queued locks, try-lock with backoff). Discuss Redlock controversy — Martin Kleppmann's critique. When are distributed locks the wrong abstraction?

- [ ] **Q05. Distributed Task Scheduler** `[SCALE]` `[RELIABILITY]`
  Priority queues with fairness, exactly-once execution with lease-based claiming, dead-letter handling, cron-like scheduling at scale, task dependency DAGs, multi-tenant resource isolation (noisy neighbor prevention). How do you handle scheduler failover without duplicate execution? Compare Temporal vs Airflow vs custom.

- [ ] **Q06. Consensus Protocol — Raft Implementation** `[CONSISTENCY]`
  Pseudo-code level walkthrough: leader election (randomized timeouts, split vote handling), log replication (AppendEntries RPC, commit index advancement), membership changes (joint consensus), snapshot and log compaction. Discuss single-decree vs multi-decree Paxos vs Raft trade-offs. How does etcd use Raft?

- [ ] **Q07. Service Mesh** `[PLATFORM]` `[RELIABILITY]`
  Control plane vs data plane, sidecar proxy architecture (Envoy), mTLS certificate rotation, traffic shaping (canary, mirror, fault injection), distributed tracing propagation, retry budgets, circuit breaking with outlier detection. When is a service mesh overkill? Discuss the latency tax and operational complexity.

- [ ] **Q08. Distributed Configuration & Feature Flag Platform** `[PLATFORM]` `[RELIABILITY]`
  Propagation latency guarantees, consistent reads vs eventual consistency for flag evaluation, percentage rollouts with sticky bucketing, kill switches, mutual exclusion between experiments, audit trail. How do you ensure a bad flag doesn't take down production? Compare LaunchDarkly architecture vs homegrown.

---

### Category B: Data Systems & Storage (7 questions)

- [ ] **Q09. Object Storage System (S3-like)** `[DATA]` `[RELIABILITY]`
  Metadata service (partitioned index, strong consistency), data path (chunking, content-addressed storage), durability math (erasure coding — Reed-Solomon — vs 3x replication, calculate N nines), placement policy (rack/AZ-aware), garbage collection of orphaned chunks. Multipart upload, versioning, lifecycle policies. How does S3 achieve 11 nines?

- [ ] **Q10. Data Lake / Lakehouse Architecture** `[DATA]` `[SCALE]`
  Schema evolution (Avro, Parquet), ACID transactions on object storage (Delta Lake / Iceberg — how do they implement snapshot isolation on S3?), query federation across structured and unstructured data, partition pruning, Z-ordering, time travel, compaction. How do you handle late-arriving data? Compare Delta Lake vs Apache Iceberg.

- [ ] **Q11. Time-Series Database** `[DATA]` `[SCALE]`
  Write-optimized storage (columnar, compression, delta-of-delta encoding, gorilla encoding for floats), downsampling strategies (pre-aggregation vs on-read), retention policies with tiered storage, high-cardinality label indexing, cross-series aggregation query patterns. Compare InfluxDB vs Prometheus vs TimescaleDB vs ClickHouse for TSDB workloads.

- [ ] **Q12. Distributed SQL Database** `[DATA]` `[CONSISTENCY]`
  Query planning across shards, distributed transactions (2PC with coordinator, Percolator model), automatic shard splitting and rebalancing, global secondary indexes (synchronous vs asynchronous), hybrid clock synchronization (TrueTime vs HLC). Compare CockroachDB vs Spanner vs YugabyteDB. When is distributed SQL the wrong choice?

- [ ] **Q13. Change Data Capture Pipeline** `[DATA]` `[REAL-TIME]`
  Log-based CDC (MySQL binlog, Postgres WAL, Debezium), schema evolution handling, exactly-once delivery semantics, handling DDL changes, initial snapshot + streaming merge, multi-consumer fan-out with schema registry, outbox pattern for microservices. How do you handle a 10TB initial snapshot without impacting production?

- [ ] **Q14. Data Warehouse / OLAP Engine** `[DATA]` `[SCALE]`
  Columnar storage and vectorized execution, materialized view maintenance (incremental vs full refresh), query optimization (predicate pushdown, join reordering, bloom filter joins), cost-based vs rule-based optimizer, separation of storage and compute, concurrency scaling. Compare Snowflake vs BigQuery vs Redshift architectures.

- [ ] **Q15. Multi-Region Active-Active Database** `[CONSISTENCY]` `[RELIABILITY]`
  Conflict resolution strategies (LWW, vector clocks, CRDTs, application-level merge), cross-region replication topologies (star vs mesh), consistency models per-operation (strong for account balance, eventual for profile updates), network partition handling, RPO/RTO targets, split-brain prevention. When is active-active not worth the complexity?

---

### Category C: Financial & Transactional Systems (5 questions)

- [ ] **Q16. Payment Processing System** `[CONSISTENCY]` `[SECURITY]`
  Full lifecycle: authorization, capture, void, refund. Idempotency keys for exactly-once charging, payment state machine, dual-write problem (DB + payment gateway), reconciliation with bank settlements (T+1, T+2), PCI-DSS compliance (tokenization, network segmentation), handling partial failures mid-payment. How does Stripe ensure you're never double-charged?

- [ ] **Q17. Double-Entry Ledger System** `[CONSISTENCY]` `[DATA]`
  Immutable append-only journal, debit/credit invariant enforcement, multi-currency with exchange rate snapshots, account balance materialization (event-sourced vs running balance), audit trail with tamper detection (hash chaining), high-throughput batched writes, cross-ledger transfers. How does this system handle a rollback 3 days after a transaction?

- [ ] **Q18. Distributed Transaction Coordinator** `[CONSISTENCY]` `[RELIABILITY]`
  2PC (blocking problem, coordinator failure), 3PC (non-blocking but network partition issues), Saga pattern (choreography vs orchestration, compensating transactions, semantic rollback), TCC (Try-Confirm-Cancel reservation pattern). When to use which? Design a Saga orchestrator that handles partial failures across 5 microservices. Discuss the "saga" of Sagas — real-world failure stories.

- [ ] **Q19. Billing & Subscription Platform** `[PLATFORM]` `[CONSISTENCY]`
  Recurring billing engine (cron vs event-driven), proration on plan changes, usage-based billing (metering pipeline, aggregation windows, idempotent event processing), dunning management (retry strategies for failed payments, grace periods), invoice generation, tax calculation integration, revenue recognition. How do you handle a customer disputing a charge from 6 months ago?

- [ ] **Q20. Real-Time Fraud Detection System** `[ML]` `[REAL-TIME]`
  Feature store (real-time and batch features), feature computation pipeline (sliding window aggregations: "# transactions in last 5 min"), ML model serving at p99 < 50ms, rule engine for hard blocks (velocity checks, geo-impossible travel), feedback loop for model retraining, manual review queue, false positive rate vs fraud loss trade-off. How do you deploy a new model without increasing false positives?

---

### Category D: Real-Time & Streaming Systems (5 questions)

- [ ] **Q21. Chat System (WhatsApp-scale)** `[REAL-TIME]` `[SCALE]`
  1:1 and group messaging, message ordering (per-conversation sequence numbers), presence/online status (heartbeat-based, gossip propagation), E2E encryption (Signal Protocol: X3DH key agreement, Double Ratchet), offline message queuing, read receipts, media sharing with thumbnail generation, message search. How do you handle a 10K-member group?

- [ ] **Q22. Real-Time Collaborative Editor (Google Docs)** `[REAL-TIME]` `[CONSISTENCY]`
  OT (Operational Transformation) vs CRDT (Conflict-free Replicated Data Types) — deep comparison with examples. Cursor and selection synchronization, undo/redo in a collaborative context, access control per-document, comment threads anchored to text ranges, offline editing and resync. Why did Google choose OT? Why are CRDTs gaining favor?

- [ ] **Q23. Live Video Streaming Platform (Twitch-scale)** `[REAL-TIME]` `[SCALE]`
  Ingest (RTMP/SRT/WebRTC), transcoding farm (adaptive bitrate ladder: 1080p/720p/480p/360p), packaging (HLS/DASH segmentation), CDN distribution with edge caching, low-latency mode (< 3s glass-to-glass), live chat at scale (fan-out per channel), DVR/rewind, stream health monitoring. How do you handle a streamer with 500K concurrent viewers?

- [ ] **Q24. Event Sourcing / CQRS System** `[DATA]` `[CONSISTENCY]`
  Event store design (append-only, partitioned by aggregate ID), projection/read-model builders (catch-up subscriptions, checkpointing), snapshotting for long-lived aggregates, idempotent event handlers, schema evolution of events (upcasting), CQRS query side (eventually consistent, tunable lag). When does event sourcing become a liability? How do you handle "delete my data" (GDPR) in an append-only store?

- [ ] **Q25. Real-Time Multiplayer Game Backend** `[REAL-TIME]` `[RELIABILITY]`
  Authoritative server model, client-side prediction + server reconciliation, lag compensation (rewinding server state), interest management (spatial partitioning for relevance filtering), tick rate and bandwidth budgeting, matchmaking service (skill-based, latency-based region selection), anti-cheat server-side validation. How do you handle 100-player battle royale state synchronization?

---

### Category E: APIs, Gateways & Developer Platforms (5 questions)

- [ ] **Q26. API Gateway** `[PLATFORM]` `[RELIABILITY]`
  Request routing (path-based, header-based, weighted), authentication (JWT validation, OAuth2 token introspection), rate limiting (per-key, per-endpoint, sliding window), request/response transformation, circuit breaking per upstream, canary routing, API versioning strategy, request logging and analytics. How do you handle a gateway deployment without dropping any in-flight requests?

- [ ] **Q27. Webhook Delivery System** `[RELIABILITY]` `[PLATFORM]`
  At-least-once delivery guarantee, retry with exponential backoff + jitter, dead-letter queue with manual retry UI, payload signing (HMAC), idempotency guidance for consumers, delivery SLA (99.9% within 5 min), event ordering per-resource, customer-facing debugging dashboard (delivery logs, response codes), endpoint health scoring and automatic disabling. How does Stripe's webhook system work?

- [ ] **Q28. Rate Limiter (Distributed, Multi-Tenant)** `[SCALE]` `[RELIABILITY]`
  Token bucket vs sliding window vs sliding window counter, distributed counting (Redis-based vs gossip-based), multi-tier limits (per-user, per-API-key, per-endpoint, global), rate limit headers (X-RateLimit-*), graceful degradation under Redis failure (fail-open vs fail-closed), burst allowance, priority-based exemptions. How do you rate-limit at 1M+ requests/sec across 50 edge nodes with < 1% error margin?

- [ ] **Q29. Multi-Tenant SaaS Platform** `[PLATFORM]` `[SECURITY]`
  Isolation models (shared DB/shared schema, shared DB/separate schema, separate DB), noisy neighbor prevention (per-tenant resource quotas, connection pooling), tenant-aware routing, data residency compliance (EU tenant data stays in EU), per-tenant billing metering, tenant onboarding/offboarding automation, cross-tenant analytics for platform operator. How do you migrate a high-value tenant from shared to dedicated infrastructure with zero downtime?

- [ ] **Q30. CI/CD Pipeline at Scale** `[PLATFORM]` `[RELIABILITY]`
  Build scheduling (DAG-based dependency resolution, remote build caching — Bazel/Gradle), artifact management (content-addressed storage, promotion across environments), test infrastructure (hermetic tests, flaky test quarantine, test impact analysis), deployment strategies (canary with automatic rollback on SLO violation, progressive delivery), monorepo support (affected target analysis). How does Google's CI/CD work for a monorepo with 1B+ lines of code?

---

### Category F: Search, Discovery & Recommendations (5 questions)

- [ ] **Q31. Full-Text Search Engine (Elasticsearch-scale)** `[DATA]` `[SCALE]`
  Inverted index construction (tokenization, stemming, stop words), TF-IDF vs BM25 ranking, distributed search (scatter-gather across shards, coordinating node merge), near-real-time indexing (refresh interval, segment merging), faceted search and aggregations, fuzzy matching (edit distance, n-gram indexing), index lifecycle management. How do you handle a re-index of 10B documents without impacting search latency?

- [ ] **Q32. Search Autocomplete / Typeahead** `[REAL-TIME]` `[SCALE]`
  Trie-based vs inverted index approach, prefix matching with ranking (popularity, recency, personalization), response time budget (p99 < 100ms), precomputed top-K per prefix, real-time popularity updates, handling multi-language and Unicode, abuse prevention (filtering offensive suggestions). How do you personalize autocomplete without adding latency?

- [ ] **Q33. Recommendation System** `[ML]` `[SCALE]`
  Candidate generation (collaborative filtering: user-user vs item-item, content-based, embedding-based ANN search), ranking model (learning-to-rank, features: user history, item metadata, context), real-time personalization (feature store, online model serving), cold-start problem (new users, new items), A/B testing of recommendation models, feedback loops (popularity bias, filter bubbles). How does Netflix's recommendation system work?

- [ ] **Q34. Ad Serving & Real-Time Bidding Platform** `[REAL-TIME]` `[SCALE]`
  Ad request lifecycle (< 100ms end-to-end), bid request fan-out to DSPs, auction mechanism (second-price vs first-price), pacing algorithms (budget smoothing across day), click-through rate prediction, frequency capping (per-user impression limits), attribution modeling, advertiser reporting pipeline. How do you serve 1M+ ad requests/sec with personalized targeting?

- [ ] **Q35. Notification System (Multi-Channel, Global)** `[SCALE]` `[PLATFORM]`
  Multi-channel orchestration (push, SMS, email, in-app), user preference management, template engine with i18n, delivery prioritization (transactional > marketing), throttling (per-user rate limits to prevent spam), deduplication across channels, delivery tracking and analytics, provider failover (if Twilio is down, fall back to Vonage). How do you handle sending 100M push notifications for a flash sale without overwhelming APNs/FCM?

---

### Category G: Content & Media Systems (4 questions)

- [ ] **Q36. YouTube / Netflix Video Platform** `[SCALE]` `[DATA]`
  Upload pipeline (chunked upload, resumable), transcoding farm (DAG of transcoding tasks, adaptive bitrate ladder generation, codec selection: H.264 vs H.265 vs AV1), content delivery (CDN architecture, origin shielding, cache fill optimization), adaptive streaming (HLS/DASH, bandwidth estimation, buffer management), content recommendation, copyright detection (Content ID / audio fingerprinting). How does Netflix pre-position content at ISP edge nodes?

- [ ] **Q37. Image Processing Pipeline (Instagram-scale)** `[SCALE]` `[PLATFORM]`
  Upload with client-side pre-processing, server-side validation (format, size, safety scanning), eager vs lazy resize generation, CDN integration with on-the-fly transformation (Thumbor/Imgproxy), content-addressed storage for deduplication, EXIF stripping for privacy, progressive JPEG for perceived performance. How do you handle a viral post that generates 10M thumbnail requests/sec?

- [ ] **Q38. Content Delivery Network** `[SCALE]` `[RELIABILITY]`
  Edge PoP architecture, cache hierarchy (L1 edge / L2 regional / origin shield), cache key design, purge propagation (how fast can you invalidate globally?), TLS termination at edge (certificate management at scale), DDoS mitigation layers, origin health checking and failover, request coalescing for cache misses, stale-while-revalidate. How does Cloudflare serve 50M+ requests/sec?

- [ ] **Q39. Web Crawler (Google-scale)** `[SCALE]` `[DATA]`
  URL frontier with priority scheduling (importance scoring, recrawl frequency), politeness policy (per-domain rate limiting, robots.txt compliance), distributed coordination (partitioning URLs across crawler nodes), deduplication (URL normalization, content fingerprinting with SimHash), handling dynamic content (headless browser rendering), DNS resolution caching, trap detection (infinite URL spaces). How do you crawl 1B pages/day?

---

### Category H: Location & Geospatial Systems (3 questions)

- [ ] **Q40. Google Maps** `[DATA]` `[SCALE]`
  Geospatial indexing (S2 cells, geohash, Hilbert curve), routing engine (Contraction Hierarchies, A* with landmarks, precomputed transit nodes), map tile serving (vector tiles vs raster, zoom-level-dependent detail, tile caching), ETA computation (real-time traffic overlay, ML-based prediction), offline maps (selective region download, delta updates), place search (geocoding, reverse geocoding). How does Google compute a route in < 200ms across a continental road network?

- [ ] **Q41. Ride-Sharing Platform (Uber-scale)** `[REAL-TIME]` `[SCALE]`
  Real-time driver location tracking (geospatial index: H3 hexagonal grid or S2 cells, 5s update interval), matching algorithm (bipartite matching, ETA-based ranking), surge pricing (supply-demand model per H3 cell), trip state machine (request → match → pickup → in-progress → complete → payment), ETA service, dispatch at scale (1M+ concurrent drivers). How do you handle the "thundering herd" at midnight on New Year's Eve?

- [ ] **Q42. Location-Based Feed / Nearby Search (Yelp-scale)** `[DATA]` `[SCALE]`
  Spatial indexing (R-tree, quadtree, geohash with prefix search), proximity queries with ranking (distance + relevance + ratings), real-time updates (new businesses, closing, hours changes), search-on-map-move pattern (bounding box queries with debouncing), caching geospatial queries (geohash-aligned cache keys). How do you serve "restaurants near me" in < 100ms across 200M+ business listings?

---

### Category I: Identity, Security & Compliance (4 questions)

- [ ] **Q43. Identity & Access Management System** `[SECURITY]` `[PLATFORM]`
  OAuth2 / OIDC flows (authorization code + PKCE, client credentials, device flow), token management (short-lived access tokens, refresh token rotation, token revocation), RBAC vs ABAC vs ReBAC (relationship-based), session management (sliding expiration, concurrent session limits), MFA (TOTP, WebAuthn/FIDO2), account recovery, SSO federation. How do you handle token revocation across 100K+ microservice instances without adding latency to every request?

- [ ] **Q44. Secrets Management System (Vault-like)** `[SECURITY]` `[RELIABILITY]`
  Encryption as a service (envelope encryption, key hierarchy: master key → data encryption keys), dynamic secrets (short-lived DB credentials, cloud IAM credentials), secret rotation without downtime, access policies (path-based ACL), audit logging (tamper-proof), unsealing ceremony (Shamir's Secret Sharing), HA architecture (Raft-backed storage). How do you rotate a database credential used by 500 microservice instances without causing an outage?

- [ ] **Q45. URL Shortener (Staff+ Depth)** `[SCALE]` `[SECURITY]`
  Beyond the basics: globally distributed redirect service (< 10ms p99), analytics pipeline (click tracking, referrer, geo, device), abuse prevention (phishing URL detection, rate limiting link creation), custom domains, link expiration, A/B testing different destinations, pre-warming CDN cache for viral links. How do you handle 1M redirects/sec? How do you prevent your service from being a vector for phishing?

- [ ] **Q46. End-to-End Encrypted Messaging (Signal-level)** `[SECURITY]` `[REAL-TIME]`
  Signal Protocol deep dive: X3DH key agreement (identity keys, signed pre-keys, one-time pre-keys), Double Ratchet Algorithm (symmetric key ratchet + DH ratchet), multi-device support (Sesame protocol), key transparency/verification (safety numbers), group messaging encryption (Sender Keys), sealed sender (metadata protection). How does the server facilitate E2E encryption without ever having access to plaintext? How do you handle key recovery when a user loses their device?

---

### Category J: Observability, Reliability & ML Infrastructure (4 questions)

- [ ] **Q47. Metrics & Monitoring System (Datadog-scale)** `[DATA]` `[SCALE]`
  High-volume metric ingestion (1M+ data points/sec), time-series storage (gorilla compression, downsampling, retention tiers), query engine (aggregation across 10K+ hosts in < 1s), alerting engine (threshold, anomaly detection, composite alerts, alert routing), dashboard rendering, SLO tracking (error budgets, burn rate alerts). How do you handle a monitoring system that itself needs to be monitored? What's the architecture of a system that can't afford downtime?

- [ ] **Q48. Distributed Tracing System (Jaeger/Zipkin-level)** `[RELIABILITY]` `[SCALE]`
  Span collection (OpenTelemetry SDK, baggage propagation), sampling strategies (head-based probabilistic, tail-based: keep all traces with errors or high latency), storage backend (column-store for trace search, object store for raw spans), trace assembly across async boundaries (message queues, cron jobs), trace-to-log correlation, service dependency graph generation. How do you implement tail-based sampling that captures all error traces across 1000+ microservices?

- [ ] **Q49. Unique ID Generator (Globally Distributed)** `[CONSISTENCY]` `[SCALE]`
  Snowflake architecture (timestamp + datacenter + machine + sequence), clock skew handling (NTP drift, backward clock jump), k-ordering (IDs are roughly time-sorted), ULID (lexicographically sortable, monotonic within millisecond), UUID v7 (time-ordered), coordination-free approaches. Compare against auto-increment (single point of failure), database sequence (cross-shard uniqueness). How do you generate 10M IDs/sec across 5 regions with rough time ordering and zero coordination?

- [ ] **Q50. ML Model Serving Platform** `[ML]` `[RELIABILITY]`
  Model registry (versioning, lineage, metadata), serving infrastructure (online: gRPC + model server, batch: Spark/Flink), latency optimization (model compilation, quantization, GPU batching, model distillation), A/B testing framework (traffic splitting, statistical significance), shadow mode (dual-scoring without affecting production), feature store (online + offline consistency), model monitoring (data drift detection, prediction quality degradation). How do you safely roll out a new fraud model that could block legitimate payments if it's wrong?

---

### Cross-Cutting Concepts (weave into ANY design above)

These are not standalone design problems but concepts you must demonstrate mastery of within the 50 questions above. Track your comfort level.

- [ ] CAP / PACELC theorem — applied to a real design, not just stated
- [ ] Consistency models (linearizability, sequential, causal, eventual) — know when each is needed
- [ ] Distributed consensus (Paxos, Raft) — at least one at pseudo-code level
- [ ] Clock synchronization (NTP, vector clocks, hybrid logical clocks) — when each matters
- [ ] Backpressure and flow control — end-to-end, not just at one layer
- [ ] Circuit breakers, retries, exponential backoff, jitter — implementation-level understanding
- [ ] Probabilistic data structures (Bloom filter, HyperLogLog, Count-Min Sketch) — know the math
- [ ] Merkle trees (anti-entropy, data sync verification)
- [ ] Gossip protocols (crux of membership, failure detection)
- [ ] Leader election patterns (bully, ring, consensus-backed)
- [ ] Consistent hashing with virtual nodes — rebalancing math
- [ ] Write-ahead logs (WAL) — durability and recovery mechanics
- [ ] LSM trees vs B+ trees — when to pick which, compaction strategies

---

## 6. Session Modes (tell the coach which mode)

### Mode A: Mock Interview (45-60 min simulated)
> "Run a mock interview. Give me a problem. I'll drive. Interrupt me if I go off track. Score me at the end on each dimension from Section 2.1."

### Mode B: Deep Dive on a Topic
> "I want to go deep on [TOPIC]. Teach me what I'm missing. Then quiz me. Be adversarial."

### Mode C: Concept Drill
> "Drill me on [CONCEPT, e.g., consistency models]. Ask me rapid-fire questions. Correct me instantly."

### Mode D: Review My Design
> "Here's my design for [PROBLEM]. Tear it apart. What would a Principal-level interviewer push back on?"

### Mode E: Back-of-Envelope Drill
> "Give me estimation problems. I'll solve them out loud. Check my math and assumptions."

### Mode F: Compare & Contrast
> "Compare [X] vs [Y] (e.g., Kafka vs. RabbitMQ, DynamoDB vs. Cassandra). When would you pick each? What are the non-obvious trade-offs?"

---

## 7. Scoring Rubric (use after mock interviews)

| Dimension | Does Not Meet (1-2) | Meets (3) | Exceeds / Staff+ (4-5) |
|---|---|---|---|
| **Scoping** | Waited to be told requirements | Asked good clarifying questions | Proposed constraints, defined SLAs, identified hidden requirements |
| **API Design** | Vague or missing | Clean, functional | Versioned, idempotent, backward-compatible, evolution story |
| **Estimation** | Skipped or wildly off | Reasonable numbers | Math drove architecture decisions; identified bottleneck from numbers |
| **Architecture** | Missing components, unclear flow | All components present, data flows clear | Org-aware, multi-team, evolutionary (v1/v2/v3) |
| **Deep Dive** | Surface-level only | Went deep on 1 area when prompted | Proactively dove deep on hardest part; 3+ levels of detail |
| **Trade-offs** | Didn't mention alternatives | Named alternatives | Quantified trade-offs, referenced real-world experience |
| **Failure Handling** | Ignored failures | Mentioned some failure modes | Comprehensive failure analysis, graceful degradation, blast radius |
| **Operational** | No mention of ops | Mentioned monitoring | Full observability, deployment strategy, runbook-level thinking |
| **Communication** | Disorganized, rambling | Clear and structured | Adjusted depth to audience, whiteboard was clean, told a story |

---

## 8. Rules of Engagement for the Coach

1. **Never accept a surface-level answer.** Always ask "why not X?" or "what happens when Y fails?"
2. **Inject chaos.** Mid-design, change a requirement: "Now it needs to work across 3 regions" or "Traffic just 10x'd."
3. **Demand numbers.** If I say "it's fast," ask "how fast? prove it."
4. **Simulate interviewer pushback.** "I'm not convinced this scales. Walk me through what happens at 1M QPS."
5. **Call out hand-waving.** If I say "we can use a cache here," demand: what cache, what eviction policy, what's the hit ratio assumption, how do you handle invalidation.
6. **Score honestly.** After mock interviews, give scores per dimension. Be brutal. I'd rather fail here than in the real interview.
7. **Track progress.** Note which topics/dimensions are improving and which are stuck.

---

## 9. Session Log

Track progress after each session:

| Date | Mode | Topic | Score (1-5) | Key Gaps Identified | Action Items |
|---|---|---|---|---|---|
| | | | | | |

---

## 10. Quick-Start Prompts

Copy-paste one of these to begin a session:

**Cold start (first session):**
> I'm preparing for Principal/Sr. Staff system design interviews. Read task.md in this repo for full context on how I want you to coach me. Let's start with Mode A — give me a mock interview problem.

**Continuing prep:**
> Resuming system design prep. Read task.md for context. Last session I struggled with [X]. Today I want to do Mode [A/B/C/D/E/F] on [TOPIC].

**Pre-interview cram:**
> I have an interview in [N] days. Read task.md. Focus on my weakest areas: [LIST]. Run rapid-fire Mode C drills, then one full Mode A mock.

---

*Last updated: 2026-02-25*
