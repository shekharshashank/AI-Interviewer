import type { Question } from "../types";

export const CATEGORIES = [
  { id: "A", name: "Core Distributed Infrastructure" },
  { id: "B", name: "Data Systems & Storage" },
  { id: "C", name: "Financial & Transactional Systems" },
  { id: "D", name: "Real-Time & Streaming Systems" },
  { id: "E", name: "APIs, Gateways & Developer Platforms" },
  { id: "F", name: "Search, Discovery & Recommendations" },
  { id: "G", name: "Content & Media Systems" },
  { id: "H", name: "Location & Geospatial Systems" },
  { id: "I", name: "Identity, Security & Compliance" },
  { id: "J", name: "Observability, Reliability & ML Infrastructure" },
] as const;

export const questions: Question[] = [
  // --- Category A: Core Distributed Infrastructure ---
  {
    id: "Q01",
    title: "Distributed Key-Value Store",
    category: "A",
    categoryName: "Core Distributed Infrastructure",
    tags: ["DATA", "CONSISTENCY"],
    description:
      "LSM-tree vs B-tree internals, tunable consistency (quorum reads/writes), anti-entropy with Merkle trees, read repair, hinted handoff, gossip-based membership. Go to storage engine level — WAL, memtable, SSTable compaction strategies (size-tiered vs leveled). Compare Dynamo vs Cassandra vs etcd trade-offs.",
  },
  {
    id: "Q02",
    title: "Distributed Cache",
    category: "A",
    categoryName: "Core Distributed Infrastructure",
    tags: ["SCALE", "RELIABILITY"],
    description:
      "Consistent hashing with virtual nodes, eviction policies (LRU/LFU/W-TinyLFU), cache-aside vs write-through vs write-behind, thundering herd mitigation (request coalescing, probabilistic early expiration), hot-key handling, cache warming strategies, replica consistency. Discuss Memcached vs Redis Cluster architecture differences.",
  },
  {
    id: "Q03",
    title: "Distributed Message Queue",
    category: "A",
    categoryName: "Core Distributed Infrastructure",
    tags: ["SCALE", "RELIABILITY"],
    description:
      "Kafka-level depth: partitioning strategies, ISR (in-sync replicas), acks semantics, consumer groups, rebalancing protocol, exactly-once with idempotent producers + transactional consumers, compacted topics, tiered storage. Compare Kafka vs Pulsar vs Redpanda. When would you NOT use Kafka?",
  },
  {
    id: "Q04",
    title: "Distributed Locking Service",
    category: "A",
    categoryName: "Core Distributed Infrastructure",
    tags: ["CONSISTENCY", "RELIABILITY"],
    description:
      "Chubby/ZooKeeper-level: consensus-backed lock acquisition, fencing tokens to prevent stale locks, session management with heartbeats, lock contention strategies (queued locks, try-lock with backoff). Discuss Redlock controversy — Martin Kleppmann's critique. When are distributed locks the wrong abstraction?",
  },
  {
    id: "Q05",
    title: "Distributed Task Scheduler",
    category: "A",
    categoryName: "Core Distributed Infrastructure",
    tags: ["SCALE", "RELIABILITY"],
    description:
      "Priority queues with fairness, exactly-once execution with lease-based claiming, dead-letter handling, cron-like scheduling at scale, task dependency DAGs, multi-tenant resource isolation (noisy neighbor prevention). How do you handle scheduler failover without duplicate execution? Compare Temporal vs Airflow vs custom.",
  },
  {
    id: "Q06",
    title: "Consensus Protocol — Raft Implementation",
    category: "A",
    categoryName: "Core Distributed Infrastructure",
    tags: ["CONSISTENCY"],
    description:
      "Pseudo-code level walkthrough: leader election (randomized timeouts, split vote handling), log replication (AppendEntries RPC, commit index advancement), membership changes (joint consensus), snapshot and log compaction. Discuss single-decree vs multi-decree Paxos vs Raft trade-offs. How does etcd use Raft?",
  },
  {
    id: "Q07",
    title: "Service Mesh",
    category: "A",
    categoryName: "Core Distributed Infrastructure",
    tags: ["PLATFORM", "RELIABILITY"],
    description:
      "Control plane vs data plane, sidecar proxy architecture (Envoy), mTLS certificate rotation, traffic shaping (canary, mirror, fault injection), distributed tracing propagation, retry budgets, circuit breaking with outlier detection. When is a service mesh overkill? Discuss the latency tax and operational complexity.",
  },
  {
    id: "Q08",
    title: "Distributed Configuration & Feature Flag Platform",
    category: "A",
    categoryName: "Core Distributed Infrastructure",
    tags: ["PLATFORM", "RELIABILITY"],
    description:
      "Propagation latency guarantees, consistent reads vs eventual consistency for flag evaluation, percentage rollouts with sticky bucketing, kill switches, mutual exclusion between experiments, audit trail. How do you ensure a bad flag doesn't take down production? Compare LaunchDarkly architecture vs homegrown.",
  },

  // --- Category B: Data Systems & Storage ---
  {
    id: "Q09",
    title: "Object Storage System (S3-like)",
    category: "B",
    categoryName: "Data Systems & Storage",
    tags: ["DATA", "RELIABILITY"],
    description:
      "Metadata service (partitioned index, strong consistency), data path (chunking, content-addressed storage), durability math (erasure coding — Reed-Solomon — vs 3x replication, calculate N nines), placement policy (rack/AZ-aware), garbage collection of orphaned chunks. Multipart upload, versioning, lifecycle policies. How does S3 achieve 11 nines?",
  },
  {
    id: "Q10",
    title: "Data Lake / Lakehouse Architecture",
    category: "B",
    categoryName: "Data Systems & Storage",
    tags: ["DATA", "SCALE"],
    description:
      "Schema evolution (Avro, Parquet), ACID transactions on object storage (Delta Lake / Iceberg — how do they implement snapshot isolation on S3?), query federation across structured and unstructured data, partition pruning, Z-ordering, time travel, compaction. How do you handle late-arriving data? Compare Delta Lake vs Apache Iceberg.",
  },
  {
    id: "Q11",
    title: "Time-Series Database",
    category: "B",
    categoryName: "Data Systems & Storage",
    tags: ["DATA", "SCALE"],
    description:
      "Write-optimized storage (columnar, compression, delta-of-delta encoding, gorilla encoding for floats), downsampling strategies (pre-aggregation vs on-read), retention policies with tiered storage, high-cardinality label indexing, cross-series aggregation query patterns. Compare InfluxDB vs Prometheus vs TimescaleDB vs ClickHouse for TSDB workloads.",
  },
  {
    id: "Q12",
    title: "Distributed SQL Database",
    category: "B",
    categoryName: "Data Systems & Storage",
    tags: ["DATA", "CONSISTENCY"],
    description:
      "Query planning across shards, distributed transactions (2PC with coordinator, Percolator model), automatic shard splitting and rebalancing, global secondary indexes (synchronous vs asynchronous), hybrid clock synchronization (TrueTime vs HLC). Compare CockroachDB vs Spanner vs YugabyteDB. When is distributed SQL the wrong choice?",
  },
  {
    id: "Q13",
    title: "Change Data Capture Pipeline",
    category: "B",
    categoryName: "Data Systems & Storage",
    tags: ["DATA", "REAL-TIME"],
    description:
      "Log-based CDC (MySQL binlog, Postgres WAL, Debezium), schema evolution handling, exactly-once delivery semantics, handling DDL changes, initial snapshot + streaming merge, multi-consumer fan-out with schema registry, outbox pattern for microservices. How do you handle a 10TB initial snapshot without impacting production?",
  },
  {
    id: "Q14",
    title: "Data Warehouse / OLAP Engine",
    category: "B",
    categoryName: "Data Systems & Storage",
    tags: ["DATA", "SCALE"],
    description:
      "Columnar storage and vectorized execution, materialized view maintenance (incremental vs full refresh), query optimization (predicate pushdown, join reordering, bloom filter joins), cost-based vs rule-based optimizer, separation of storage and compute, concurrency scaling. Compare Snowflake vs BigQuery vs Redshift architectures.",
  },
  {
    id: "Q15",
    title: "Multi-Region Active-Active Database",
    category: "B",
    categoryName: "Data Systems & Storage",
    tags: ["CONSISTENCY", "RELIABILITY"],
    description:
      "Conflict resolution strategies (LWW, vector clocks, CRDTs, application-level merge), cross-region replication topologies (star vs mesh), consistency models per-operation (strong for account balance, eventual for profile updates), network partition handling, RPO/RTO targets, split-brain prevention. When is active-active not worth the complexity?",
  },

  // --- Category C: Financial & Transactional Systems ---
  {
    id: "Q16",
    title: "Payment Processing System",
    category: "C",
    categoryName: "Financial & Transactional Systems",
    tags: ["CONSISTENCY", "SECURITY"],
    description:
      "Full lifecycle: authorization, capture, void, refund. Idempotency keys for exactly-once charging, payment state machine, dual-write problem (DB + payment gateway), reconciliation with bank settlements (T+1, T+2), PCI-DSS compliance (tokenization, network segmentation), handling partial failures mid-payment. How does Stripe ensure you're never double-charged?",
  },
  {
    id: "Q17",
    title: "Double-Entry Ledger System",
    category: "C",
    categoryName: "Financial & Transactional Systems",
    tags: ["CONSISTENCY", "DATA"],
    description:
      "Immutable append-only journal, debit/credit invariant enforcement, multi-currency with exchange rate snapshots, account balance materialization (event-sourced vs running balance), audit trail with tamper detection (hash chaining), high-throughput batched writes, cross-ledger transfers. How does this system handle a rollback 3 days after a transaction?",
  },
  {
    id: "Q18",
    title: "Distributed Transaction Coordinator",
    category: "C",
    categoryName: "Financial & Transactional Systems",
    tags: ["CONSISTENCY", "RELIABILITY"],
    description:
      "2PC (blocking problem, coordinator failure), 3PC (non-blocking but network partition issues), Saga pattern (choreography vs orchestration, compensating transactions, semantic rollback), TCC (Try-Confirm-Cancel reservation pattern). When to use which? Design a Saga orchestrator that handles partial failures across 5 microservices.",
  },
  {
    id: "Q19",
    title: "Billing & Subscription Platform",
    category: "C",
    categoryName: "Financial & Transactional Systems",
    tags: ["PLATFORM", "CONSISTENCY"],
    description:
      "Recurring billing engine (cron vs event-driven), proration on plan changes, usage-based billing (metering pipeline, aggregation windows, idempotent event processing), dunning management (retry strategies for failed payments, grace periods), invoice generation, tax calculation integration, revenue recognition. How do you handle a customer disputing a charge from 6 months ago?",
  },
  {
    id: "Q20",
    title: "Real-Time Fraud Detection System",
    category: "C",
    categoryName: "Financial & Transactional Systems",
    tags: ["ML", "REAL-TIME"],
    description:
      "Feature store (real-time and batch features), feature computation pipeline (sliding window aggregations: '# transactions in last 5 min'), ML model serving at p99 < 50ms, rule engine for hard blocks (velocity checks, geo-impossible travel), feedback loop for model retraining, manual review queue, false positive rate vs fraud loss trade-off. How do you deploy a new model without increasing false positives?",
  },

  // --- Category D: Real-Time & Streaming Systems ---
  {
    id: "Q21",
    title: "Chat System (WhatsApp-scale)",
    category: "D",
    categoryName: "Real-Time & Streaming Systems",
    tags: ["REAL-TIME", "SCALE"],
    description:
      "1:1 and group messaging, message ordering (per-conversation sequence numbers), presence/online status (heartbeat-based, gossip propagation), E2E encryption (Signal Protocol: X3DH key agreement, Double Ratchet), offline message queuing, read receipts, media sharing with thumbnail generation, message search. How do you handle a 10K-member group?",
  },
  {
    id: "Q22",
    title: "Real-Time Collaborative Editor (Google Docs)",
    category: "D",
    categoryName: "Real-Time & Streaming Systems",
    tags: ["REAL-TIME", "CONSISTENCY"],
    description:
      "OT (Operational Transformation) vs CRDT (Conflict-free Replicated Data Types) — deep comparison with examples. Cursor and selection synchronization, undo/redo in a collaborative context, access control per-document, comment threads anchored to text ranges, offline editing and resync. Why did Google choose OT? Why are CRDTs gaining favor?",
  },
  {
    id: "Q23",
    title: "Live Video Streaming Platform (Twitch-scale)",
    category: "D",
    categoryName: "Real-Time & Streaming Systems",
    tags: ["REAL-TIME", "SCALE"],
    description:
      "Ingest (RTMP/SRT/WebRTC), transcoding farm (adaptive bitrate ladder: 1080p/720p/480p/360p), packaging (HLS/DASH segmentation), CDN distribution with edge caching, low-latency mode (< 3s glass-to-glass), live chat at scale (fan-out per channel), DVR/rewind, stream health monitoring. How do you handle a streamer with 500K concurrent viewers?",
  },
  {
    id: "Q24",
    title: "Event Sourcing / CQRS System",
    category: "D",
    categoryName: "Real-Time & Streaming Systems",
    tags: ["DATA", "CONSISTENCY"],
    description:
      "Event store design (append-only, partitioned by aggregate ID), projection/read-model builders (catch-up subscriptions, checkpointing), snapshotting for long-lived aggregates, idempotent event handlers, schema evolution of events (upcasting), CQRS query side (eventually consistent, tunable lag). When does event sourcing become a liability? How do you handle 'delete my data' (GDPR) in an append-only store?",
  },
  {
    id: "Q25",
    title: "Real-Time Multiplayer Game Backend",
    category: "D",
    categoryName: "Real-Time & Streaming Systems",
    tags: ["REAL-TIME", "RELIABILITY"],
    description:
      "Authoritative server model, client-side prediction + server reconciliation, lag compensation (rewinding server state), interest management (spatial partitioning for relevance filtering), tick rate and bandwidth budgeting, matchmaking service (skill-based, latency-based region selection), anti-cheat server-side validation. How do you handle 100-player battle royale state synchronization?",
  },

  // --- Category E: APIs, Gateways & Developer Platforms ---
  {
    id: "Q26",
    title: "API Gateway",
    category: "E",
    categoryName: "APIs, Gateways & Developer Platforms",
    tags: ["PLATFORM", "RELIABILITY"],
    description:
      "Request routing (path-based, header-based, weighted), authentication (JWT validation, OAuth2 token introspection), rate limiting (per-key, per-endpoint, sliding window), request/response transformation, circuit breaking per upstream, canary routing, API versioning strategy, request logging and analytics. How do you handle a gateway deployment without dropping any in-flight requests?",
  },
  {
    id: "Q27",
    title: "Webhook Delivery System",
    category: "E",
    categoryName: "APIs, Gateways & Developer Platforms",
    tags: ["RELIABILITY", "PLATFORM"],
    description:
      "At-least-once delivery guarantee, retry with exponential backoff + jitter, dead-letter queue with manual retry UI, payload signing (HMAC), idempotency guidance for consumers, delivery SLA (99.9% within 5 min), event ordering per-resource, customer-facing debugging dashboard (delivery logs, response codes), endpoint health scoring and automatic disabling. How does Stripe's webhook system work?",
  },
  {
    id: "Q28",
    title: "Rate Limiter (Distributed, Multi-Tenant)",
    category: "E",
    categoryName: "APIs, Gateways & Developer Platforms",
    tags: ["SCALE", "RELIABILITY"],
    description:
      "Token bucket vs sliding window vs sliding window counter, distributed counting (Redis-based vs gossip-based), multi-tier limits (per-user, per-API-key, per-endpoint, global), rate limit headers (X-RateLimit-*), graceful degradation under Redis failure (fail-open vs fail-closed), burst allowance, priority-based exemptions. How do you rate-limit at 1M+ requests/sec across 50 edge nodes with < 1% error margin?",
  },
  {
    id: "Q29",
    title: "Multi-Tenant SaaS Platform",
    category: "E",
    categoryName: "APIs, Gateways & Developer Platforms",
    tags: ["PLATFORM", "SECURITY"],
    description:
      "Isolation models (shared DB/shared schema, shared DB/separate schema, separate DB), noisy neighbor prevention (per-tenant resource quotas, connection pooling), tenant-aware routing, data residency compliance (EU tenant data stays in EU), per-tenant billing metering, tenant onboarding/offboarding automation, cross-tenant analytics for platform operator. How do you migrate a high-value tenant from shared to dedicated infrastructure with zero downtime?",
  },
  {
    id: "Q30",
    title: "CI/CD Pipeline at Scale",
    category: "E",
    categoryName: "APIs, Gateways & Developer Platforms",
    tags: ["PLATFORM", "RELIABILITY"],
    description:
      "Build scheduling (DAG-based dependency resolution, remote build caching — Bazel/Gradle), artifact management (content-addressed storage, promotion across environments), test infrastructure (hermetic tests, flaky test quarantine, test impact analysis), deployment strategies (canary with automatic rollback on SLO violation, progressive delivery), monorepo support (affected target analysis). How does Google's CI/CD work for a monorepo with 1B+ lines of code?",
  },

  // --- Category F: Search, Discovery & Recommendations ---
  {
    id: "Q31",
    title: "Full-Text Search Engine (Elasticsearch-scale)",
    category: "F",
    categoryName: "Search, Discovery & Recommendations",
    tags: ["DATA", "SCALE"],
    description:
      "Inverted index construction (tokenization, stemming, stop words), TF-IDF vs BM25 ranking, distributed search (scatter-gather across shards, coordinating node merge), near-real-time indexing (refresh interval, segment merging), faceted search and aggregations, fuzzy matching (edit distance, n-gram indexing), index lifecycle management. How do you handle a re-index of 10B documents without impacting search latency?",
  },
  {
    id: "Q32",
    title: "Search Autocomplete / Typeahead",
    category: "F",
    categoryName: "Search, Discovery & Recommendations",
    tags: ["REAL-TIME", "SCALE"],
    description:
      "Trie-based vs inverted index approach, prefix matching with ranking (popularity, recency, personalization), response time budget (p99 < 100ms), precomputed top-K per prefix, real-time popularity updates, handling multi-language and Unicode, abuse prevention (filtering offensive suggestions). How do you personalize autocomplete without adding latency?",
  },
  {
    id: "Q33",
    title: "Recommendation System",
    category: "F",
    categoryName: "Search, Discovery & Recommendations",
    tags: ["ML", "SCALE"],
    description:
      "Candidate generation (collaborative filtering: user-user vs item-item, content-based, embedding-based ANN search), ranking model (learning-to-rank, features: user history, item metadata, context), real-time personalization (feature store, online model serving), cold-start problem (new users, new items), A/B testing of recommendation models, feedback loops (popularity bias, filter bubbles). How does Netflix's recommendation system work?",
  },
  {
    id: "Q34",
    title: "Ad Serving & Real-Time Bidding Platform",
    category: "F",
    categoryName: "Search, Discovery & Recommendations",
    tags: ["REAL-TIME", "SCALE"],
    description:
      "Ad request lifecycle (< 100ms end-to-end), bid request fan-out to DSPs, auction mechanism (second-price vs first-price), pacing algorithms (budget smoothing across day), click-through rate prediction, frequency capping (per-user impression limits), attribution modeling, advertiser reporting pipeline. How do you serve 1M+ ad requests/sec with personalized targeting?",
  },
  {
    id: "Q35",
    title: "Notification System (Multi-Channel, Global)",
    category: "F",
    categoryName: "Search, Discovery & Recommendations",
    tags: ["SCALE", "PLATFORM"],
    description:
      "Multi-channel orchestration (push, SMS, email, in-app), user preference management, template engine with i18n, delivery prioritization (transactional > marketing), throttling (per-user rate limits to prevent spam), deduplication across channels, delivery tracking and analytics, provider failover (if Twilio is down, fall back to Vonage). How do you handle sending 100M push notifications for a flash sale without overwhelming APNs/FCM?",
  },

  // --- Category G: Content & Media Systems ---
  {
    id: "Q36",
    title: "YouTube / Netflix Video Platform",
    category: "G",
    categoryName: "Content & Media Systems",
    tags: ["SCALE", "DATA"],
    description:
      "Upload pipeline (chunked upload, resumable), transcoding farm (DAG of transcoding tasks, adaptive bitrate ladder generation, codec selection: H.264 vs H.265 vs AV1), content delivery (CDN architecture, origin shielding, cache fill optimization), adaptive streaming (HLS/DASH, bandwidth estimation, buffer management), content recommendation, copyright detection (Content ID / audio fingerprinting). How does Netflix pre-position content at ISP edge nodes?",
  },
  {
    id: "Q37",
    title: "Image Processing Pipeline (Instagram-scale)",
    category: "G",
    categoryName: "Content & Media Systems",
    tags: ["SCALE", "PLATFORM"],
    description:
      "Upload with client-side pre-processing, server-side validation (format, size, safety scanning), eager vs lazy resize generation, CDN integration with on-the-fly transformation (Thumbor/Imgproxy), content-addressed storage for deduplication, EXIF stripping for privacy, progressive JPEG for perceived performance. How do you handle a viral post that generates 10M thumbnail requests/sec?",
  },
  {
    id: "Q38",
    title: "Content Delivery Network",
    category: "G",
    categoryName: "Content & Media Systems",
    tags: ["SCALE", "RELIABILITY"],
    description:
      "Edge PoP architecture, cache hierarchy (L1 edge / L2 regional / origin shield), cache key design, purge propagation (how fast can you invalidate globally?), TLS termination at edge (certificate management at scale), DDoS mitigation layers, origin health checking and failover, request coalescing for cache misses, stale-while-revalidate. How does Cloudflare serve 50M+ requests/sec?",
  },
  {
    id: "Q39",
    title: "Web Crawler (Google-scale)",
    category: "G",
    categoryName: "Content & Media Systems",
    tags: ["SCALE", "DATA"],
    description:
      "URL frontier with priority scheduling (importance scoring, recrawl frequency), politeness policy (per-domain rate limiting, robots.txt compliance), distributed coordination (partitioning URLs across crawler nodes), deduplication (URL normalization, content fingerprinting with SimHash), handling dynamic content (headless browser rendering), DNS resolution caching, trap detection (infinite URL spaces). How do you crawl 1B pages/day?",
  },

  // --- Category H: Location & Geospatial Systems ---
  {
    id: "Q40",
    title: "Google Maps",
    category: "H",
    categoryName: "Location & Geospatial Systems",
    tags: ["DATA", "SCALE"],
    description:
      "Geospatial indexing (S2 cells, geohash, Hilbert curve), routing engine (Contraction Hierarchies, A* with landmarks, precomputed transit nodes), map tile serving (vector tiles vs raster, zoom-level-dependent detail, tile caching), ETA computation (real-time traffic overlay, ML-based prediction), offline maps (selective region download, delta updates), place search (geocoding, reverse geocoding). How does Google compute a route in < 200ms across a continental road network?",
  },
  {
    id: "Q41",
    title: "Ride-Sharing Platform (Uber-scale)",
    category: "H",
    categoryName: "Location & Geospatial Systems",
    tags: ["REAL-TIME", "SCALE"],
    description:
      "Real-time driver location tracking (geospatial index: H3 hexagonal grid or S2 cells, 5s update interval), matching algorithm (bipartite matching, ETA-based ranking), surge pricing (supply-demand model per H3 cell), trip state machine (request -> match -> pickup -> in-progress -> complete -> payment), ETA service, dispatch at scale (1M+ concurrent drivers). How do you handle the 'thundering herd' at midnight on New Year's Eve?",
  },
  {
    id: "Q42",
    title: "Location-Based Feed / Nearby Search (Yelp-scale)",
    category: "H",
    categoryName: "Location & Geospatial Systems",
    tags: ["DATA", "SCALE"],
    description:
      "Spatial indexing (R-tree, quadtree, geohash with prefix search), proximity queries with ranking (distance + relevance + ratings), real-time updates (new businesses, closing, hours changes), search-on-map-move pattern (bounding box queries with debouncing), caching geospatial queries (geohash-aligned cache keys). How do you serve 'restaurants near me' in < 100ms across 200M+ business listings?",
  },

  // --- Category I: Identity, Security & Compliance ---
  {
    id: "Q43",
    title: "Identity & Access Management System",
    category: "I",
    categoryName: "Identity, Security & Compliance",
    tags: ["SECURITY", "PLATFORM"],
    description:
      "OAuth2 / OIDC flows (authorization code + PKCE, client credentials, device flow), token management (short-lived access tokens, refresh token rotation, token revocation), RBAC vs ABAC vs ReBAC (relationship-based), session management (sliding expiration, concurrent session limits), MFA (TOTP, WebAuthn/FIDO2), account recovery, SSO federation. How do you handle token revocation across 100K+ microservice instances without adding latency to every request?",
  },
  {
    id: "Q44",
    title: "Secrets Management System (Vault-like)",
    category: "I",
    categoryName: "Identity, Security & Compliance",
    tags: ["SECURITY", "RELIABILITY"],
    description:
      "Encryption as a service (envelope encryption, key hierarchy: master key -> data encryption keys), dynamic secrets (short-lived DB credentials, cloud IAM credentials), secret rotation without downtime, access policies (path-based ACL), audit logging (tamper-proof), unsealing ceremony (Shamir's Secret Sharing), HA architecture (Raft-backed storage). How do you rotate a database credential used by 500 microservice instances without causing an outage?",
  },
  {
    id: "Q45",
    title: "URL Shortener (Staff+ Depth)",
    category: "I",
    categoryName: "Identity, Security & Compliance",
    tags: ["SCALE", "SECURITY"],
    description:
      "Beyond the basics: globally distributed redirect service (< 10ms p99), analytics pipeline (click tracking, referrer, geo, device), abuse prevention (phishing URL detection, rate limiting link creation), custom domains, link expiration, A/B testing different destinations, pre-warming CDN cache for viral links. How do you handle 1M redirects/sec? How do you prevent your service from being a vector for phishing?",
  },
  {
    id: "Q46",
    title: "End-to-End Encrypted Messaging (Signal-level)",
    category: "I",
    categoryName: "Identity, Security & Compliance",
    tags: ["SECURITY", "REAL-TIME"],
    description:
      "Signal Protocol deep dive: X3DH key agreement (identity keys, signed pre-keys, one-time pre-keys), Double Ratchet Algorithm (symmetric key ratchet + DH ratchet), multi-device support (Sesame protocol), key transparency/verification (safety numbers), group messaging encryption (Sender Keys), sealed sender (metadata protection). How does the server facilitate E2E encryption without ever having access to plaintext? How do you handle key recovery when a user loses their device?",
  },

  // --- Category J: Observability, Reliability & ML Infrastructure ---
  {
    id: "Q47",
    title: "Metrics & Monitoring System (Datadog-scale)",
    category: "J",
    categoryName: "Observability, Reliability & ML Infrastructure",
    tags: ["DATA", "SCALE"],
    description:
      "High-volume metric ingestion (1M+ data points/sec), time-series storage (gorilla compression, downsampling, retention tiers), query engine (aggregation across 10K+ hosts in < 1s), alerting engine (threshold, anomaly detection, composite alerts, alert routing), dashboard rendering, SLO tracking (error budgets, burn rate alerts). How do you handle a monitoring system that itself needs to be monitored? What's the architecture of a system that can't afford downtime?",
  },
  {
    id: "Q48",
    title: "Distributed Tracing System (Jaeger/Zipkin-level)",
    category: "J",
    categoryName: "Observability, Reliability & ML Infrastructure",
    tags: ["RELIABILITY", "SCALE"],
    description:
      "Span collection (OpenTelemetry SDK, baggage propagation), sampling strategies (head-based probabilistic, tail-based: keep all traces with errors or high latency), storage backend (column-store for trace search, object store for raw spans), trace assembly across async boundaries (message queues, cron jobs), trace-to-log correlation, service dependency graph generation. How do you implement tail-based sampling that captures all error traces across 1000+ microservices?",
  },
  {
    id: "Q49",
    title: "Unique ID Generator (Globally Distributed)",
    category: "J",
    categoryName: "Observability, Reliability & ML Infrastructure",
    tags: ["CONSISTENCY", "SCALE"],
    description:
      "Snowflake architecture (timestamp + datacenter + machine + sequence), clock skew handling (NTP drift, backward clock jump), k-ordering (IDs are roughly time-sorted), ULID (lexicographically sortable, monotonic within millisecond), UUID v7 (time-ordered), coordination-free approaches. Compare against auto-increment (single point of failure), database sequence (cross-shard uniqueness). How do you generate 10M IDs/sec across 5 regions with rough time ordering and zero coordination?",
  },
  {
    id: "Q50",
    title: "ML Model Serving Platform",
    category: "J",
    categoryName: "Observability, Reliability & ML Infrastructure",
    tags: ["ML", "RELIABILITY"],
    description:
      "Model registry (versioning, lineage, metadata), serving infrastructure (online: gRPC + model server, batch: Spark/Flink), latency optimization (model compilation, quantization, GPU batching, model distillation), A/B testing framework (traffic splitting, statistical significance), shadow mode (dual-scoring without affecting production), feature store (online + offline consistency), model monitoring (data drift detection, prediction quality degradation). How do you safely roll out a new fraud model that could block legitimate payments if it's wrong?",
  },
];

export function getQuestionsByCategory(): Map<string, Question[]> {
  const grouped = new Map<string, Question[]>();
  for (const q of questions) {
    const key = `${q.category}. ${q.categoryName}`;
    if (!grouped.has(key)) grouped.set(key, []);
    grouped.get(key)!.push(q);
  }
  return grouped;
}
