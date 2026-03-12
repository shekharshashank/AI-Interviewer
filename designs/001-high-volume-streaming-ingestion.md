# Gold Standard: High-Volume Streaming Data Ingestion into DB

## 0. Why This Matters

This is not one interview question — it is a **sub-system that appears in 60%+ of system design problems**: metrics ingestion, event sourcing, activity feeds, IoT telemetry, clickstream, payment ledgers, log aggregation. Master this pattern and you have a reusable building block.

---

## 1. Scope & Requirements

### Functional
- Ingest structured/semi-structured events from thousands of producers
- Persist durably into a queryable database
- Support both real-time and batch consumers downstream
- Exactly-once or at-least-once semantics (configurable per use case)

### Non-Functional
- **Throughput**: 1M+ events/sec sustained, 5M+ burst
- **Latency**: < 5s end-to-end (producer publish to DB queryable)
- **Durability**: zero data loss after acknowledgment
- **Availability**: 99.99% write availability
- **Ordering**: per-partition ordering guaranteed; global ordering NOT guaranteed (and you should explain why not)

### Back-of-Envelope

```
1M events/sec x 1KB avg event = 1 GB/sec ingest
Per day:  ~86 TB raw
Per year: ~31 PB raw (before compression/compaction)
Compression ratio (typical): 5-10x -> 3-6 PB/year stored
```

This math immediately tells you: **single-node anything is out of the question. This is a distributed systems problem from minute one.**

---

## 2. High-Level Architecture

```
                          ┌─────────────────────────────────────────────┐
                          │            PRODUCERS (thousands)            │
                          │  (services, IoT devices, mobile, clickstream)│
                          └──────────────┬──────────────────────────────┘
                                         │
                                         ▼
                          ┌──────────────────────────────┐
                          │     INGESTION GATEWAY         │
                          │  (Load Balancer + API Fleet)  │
                          │  - Schema validation          │
                          │  - Authentication              │
                          │  - Rate limiting               │
                          │  - Backpressure signaling      │
                          └──────────────┬───────────────┘
                                         │
                                         ▼
                    ┌────────────────────────────────────────┐
                    │       DURABLE MESSAGE BUFFER           │
                    │            (Apache Kafka)               │
                    │  - Partitioned by entity_id/tenant_id  │
                    │  - Replication factor = 3               │
                    │  - Retention = 7 days (replay window)  │
                    └────────┬──────────────┬────────────────┘
                             │              │
                    ┌────────┘              └────────┐
                    ▼                                ▼
        ┌───────────────────┐           ┌───────────────────────┐
        │  STREAM PROCESSOR  │           │  STREAM PROCESSOR     │
        │  (Real-time path)  │           │  (Batch/micro-batch)  │
        │  Flink / KStreams   │           │  Flink / Spark        │
        │  - Deduplication   │           │  - Aggregation        │
        │  - Enrichment      │           │  - Compaction         │
        │  - Transformation  │           │  - Late data handling │
        └────────┬──────────┘           └──────────┬────────────┘
                 │                                  │
                 ▼                                  ▼
        ┌────────────────┐              ┌────────────────────┐
        │   HOT STORE     │              │   COLD/WARM STORE   │
        │  (recent data)  │              │  (historical data)  │
        │                 │              │                     │
        │  Cassandra /    │              │  ClickHouse /       │
        │  ScyllaDB /     │              │  Apache Druid /     │
        │  DynamoDB       │              │  Parquet on S3 +    │
        │                 │              │  query engine        │
        └────────────────┘              └────────────────────┘
                 │                                  │
                 └──────────┬───────────────────────┘
                            ▼
                 ┌───────────────────────┐
                 │    QUERY / SERVING     │
                 │    LAYER              │
                 │  - Unified API        │
                 │  - Routes to hot/cold │
                 │  - Caching (Redis)    │
                 └───────────────────────┘
```

---

## 3. Component-by-Component Deep Dive

### 3.1 — Ingestion Gateway

**What it does**: Front door. Accepts events, validates, and produces to Kafka.

**Design decisions**:

| Decision | Choice | Why |
|---|---|---|
| Protocol | gRPC with HTTP/2 | Multiplexed streams, binary framing, backpressure via flow control. REST is fine for low-throughput producers. |
| Schema enforcement | Schema Registry (Avro/Protobuf) | Reject malformed events at the gate. Producers register schemas. Backward/forward compatibility enforced. |
| Batching | Client-side batching (linger.ms = 5-20ms) | Amortize network overhead. Single event per request is 10-50x worse throughput. |
| Backpressure | HTTP 429 / gRPC RESOURCE_EXHAUSTED + client retry with exponential backoff + jitter | Without this, a traffic spike cascades into Kafka, then into DB. The gateway is your pressure valve. |
| Authentication | mTLS for service-to-service, API key + JWT for external | At 1M events/sec, per-request auth DB lookup is not viable. Use asymmetric JWT validation (no network call). |

**Staff+ insight**: The gateway is NOT just a passthrough. It's your **blast radius limiter**. Per-tenant rate limiting here prevents one noisy tenant from starving others. Implement token bucket per tenant, with a global circuit breaker.

### 3.2 — Durable Message Buffer (Kafka)

**Why Kafka and not directly into the DB**:

This is the single most important architectural decision in this design. Interviewers will probe this.

| Without Kafka (direct to DB) | With Kafka |
|---|---|
| DB must absorb raw traffic spikes | Kafka absorbs spikes; DB consumes at its own pace |
| Producer blocked if DB is slow/down | Producer succeeds as long as Kafka is up |
| No replay capability | Replay from any offset (incident recovery) |
| Schema evolution = downtime | Consumers evolve independently |
| Single consumer | Fan-out to N consumers (real-time, batch, audit, ML) |

**Kafka configuration for this workload**:

```
Partitions:          256-1024 (based on parallelism needs)
Replication factor:  3 (tolerates 1 broker failure, 2 with unclean leader election disabled)
acks:                all (no data loss after producer ack)
min.insync.replicas: 2 (write succeeds if 2 of 3 replicas confirm)
Retention:           7 days time-based (replay window for incidents)
Compression:         lz4 (best throughput/compression ratio for streaming)
Partition key:        entity_id or tenant_id (ensures ordering per entity)
```

**Staff+ insight**: Partition count is a one-way door in Kafka (you can add but not remove). Over-partition early. 256 partitions at 1M events/sec = ~4K events/sec per partition, well within Kafka's per-partition throughput. Under-partitioning is a scaling cliff you can't fix without data migration.

**Staff+ insight**: `acks=all` + `min.insync.replicas=2` is the durability sweet spot. `acks=1` risks data loss on leader failure. `acks=all` with `min.insync.replicas=3` means a single replica failure makes the topic unavailable for writes.

### 3.3 — Stream Processor

**Two paths, one framework (Flink preferred)**:

#### Real-time path:
```
Kafka → Flink → Transformations → Hot Store (Cassandra/ScyllaDB)
```

What the processor does:
1. **Deduplication** — Idempotency key (producer_id + sequence_number) checked against a Bloom filter + RocksDB state store. At 1M events/sec, you cannot afford a remote lookup per event.
2. **Schema evolution** — Deserialize with Schema Registry, transform to internal canonical format.
3. **Enrichment** — Join with dimension data (e.g., user profile, device metadata). Use Flink's broadcast state for small dimensions, async I/O for large lookups.
4. **Partitioned write batching** — Accumulate 100-500ms micro-batches per DB partition, then flush. This converts 1M individual inserts into ~2K-10K batched writes.

#### Batch/compaction path:
```
Kafka → Flink/Spark → Aggregation → Cold Store (ClickHouse/Parquet+S3)
```

What this path does:
1. **Time-window aggregation** — Roll up raw events into minute/hour/day granularity.
2. **Late data handling** — Allowed lateness window (e.g., 1 hour). Late events trigger incremental re-aggregation, not full recomputation.
3. **Compaction** — Merge small files into optimally-sized columnar files (128-256 MB Parquet files).

**Staff+ insight**: The reason for two paths is not just "real-time vs batch." It's about **write amplification vs query efficiency**. The hot store is optimized for write throughput (LSM-tree, append-only). The cold store is optimized for read throughput (columnar, compressed, pre-aggregated). Trying to do both in one store is the #1 mistake candidates make.

### 3.4 — Hot Store (Recent Data)

**Choice: ScyllaDB (or Cassandra / DynamoDB)**

Why a wide-column / LSM-tree store:

| Property | Why it matters |
|---|---|
| Append-only writes (LSM) | No random I/O on write path. Sustained write throughput 100K-500K writes/sec per node. |
| Tunable consistency | Write with CL=LOCAL_QUORUM for durability. Read with CL=ONE for speed when eventual is acceptable. |
| Horizontal scaling | Add nodes, data rebalances automatically via consistent hashing + vnodes. |
| TTL support | Auto-expire data after N days. Hot store only holds recent window (e.g., 7-30 days). |
| Time-series friendly | Partition key = entity_id, clustering key = timestamp DESC. Recent-first access pattern. |

**Data model example**:

```sql
CREATE TABLE events (
    tenant_id    UUID,
    entity_id    UUID,
    event_date   DATE,          -- partition bucketing (prevents unbounded partitions)
    event_time   TIMEUUID,      -- clustering key, gives ordering + uniqueness
    event_type   TEXT,
    payload      BLOB,          -- or frozen<map<text, text>> for semi-structured
    PRIMARY KEY ((tenant_id, entity_id, event_date), event_time)
) WITH CLUSTERING ORDER BY (event_time DESC)
  AND default_time_to_live = 2592000   -- 30 days
  AND compaction = {'class': 'TimeWindowCompactionStrategy',
                    'compaction_window_size': 1,
                    'compaction_window_unit': 'DAYS'};
```

**Staff+ insight**: The `event_date` in the partition key is critical. Without it, a high-volume entity creates an ever-growing partition (known as a "wide partition" anti-pattern). Cassandra partitions > 100MB degrade significantly. Date-bucketing caps partition size.

**Staff+ insight**: `TimeWindowCompactionStrategy` is purpose-built for time-series. It avoids rewriting old SSTables during compaction, reducing write amplification by 5-10x compared to `SizeTieredCompactionStrategy` for time-ordered data.

### 3.5 — Cold/Warm Store (Historical + Analytics)

**Choice: ClickHouse (or Apache Druid, or Parquet on S3 + Trino/Presto)**

| If you need... | Choose |
|---|---|
| Sub-second analytics on structured data | ClickHouse |
| Real-time OLAP with ingestion | Apache Druid |
| Cheapest storage, query latency flexible (seconds) | Parquet on S3 + Trino |
| Fully managed on AWS | Redshift Serverless or Athena |

**ClickHouse design**:

```sql
CREATE TABLE events_historical
ENGINE = MergeTree()
PARTITION BY toYYYYMM(event_time)
ORDER BY (tenant_id, entity_id, event_time)
TTL event_time + INTERVAL 3 YEAR
SETTINGS index_granularity = 8192;
```

Why ClickHouse:
- Columnar storage: only reads columns needed for the query
- Vectorized execution: processes data in SIMD batches
- Compression: 10-20x on typical event data (LZ4 + delta encoding on timestamps)
- 31 PB/year raw -> ~2-3 PB stored with compression

### 3.6 — Query / Serving Layer

```
Client → Query Router → { Hot Store, Cold Store, Cache }
```

- **Time-range routing**: query for last 24h -> Hot Store. Query for last 6 months -> Cold Store. Query spanning both -> fan-out + merge.
- **Caching**: Redis for frequently-accessed dashboards / aggregations. TTL = aggregation window (e.g., 1-minute aggregations cached for 1 minute).
- **Pagination**: Cursor-based (not offset). Cursor = last `event_time` seen. Offset pagination on billions of rows is O(n).

---

## 4. Critical Trade-offs Table

| Decision | Option A | Option B | This Design Chose | Why |
|---|---|---|---|---|
| Kafka acks | `acks=1` (faster) | `acks=all` (durable) | `acks=all` | Zero data loss after ack is a hard requirement. Throughput cost: ~20% — acceptable. |
| Processing | Event-at-a-time | Micro-batch | Micro-batch (100-500ms) | 10-50x better DB throughput via batched writes. Adds 100-500ms latency — acceptable for < 5s SLA. |
| Hot store consistency | Strong (QUORUM read) | Eventual (CL=ONE read) | Eventual for reads | At this volume, QUORUM reads double tail latency. Stale reads for < 1s is acceptable. |
| Dedup strategy | Exact (DB lookup per event) | Probabilistic (Bloom filter) | Bloom filter + periodic exact reconciliation | Exact dedup at 1M/sec is not feasible in real-time. Bloom filter with 0.1% FPR + hourly exact pass catches the rest. |
| Hot/cold split | Single store | Separate stores | Separate | Different access patterns need different storage engines. One store = compromise on both reads and writes. |
| Ordering | Global | Per-partition | Per-partition | Global ordering at 1M/sec requires single-partition = single-writer bottleneck. Per-entity ordering is what the business actually needs. |

---

## 5. Failure Modes & Mitigations

### 5.1 — Producer can't reach Gateway
- **Mitigation**: Client-side durable buffer (local disk queue). SDK retries with exponential backoff + jitter. Events are timestamped at creation, not ingestion — late arrival doesn't lose time accuracy.

### 5.2 — Kafka broker failure
- **Impact**: With RF=3, single broker failure = no data loss, no downtime. Leader election completes in seconds.
- **Mitigation**: `min.insync.replicas=2` ensures writes still succeed. Monitor under-replicated partitions. Auto-replace failed brokers.

### 5.3 — Stream processor crash
- **Impact**: Processing pauses. No data loss (events still in Kafka).
- **Mitigation**: Flink checkpointing to S3 every 30-60s. On restart, resume from last checkpoint. Exactly-once semantics via Kafka transactions + Flink's two-phase commit.

### 5.4 — Hot store (Cassandra) overloaded
- **Mitigation**: Backpressure from Flink — slow down consumption from Kafka. Kafka buffers the delta (7-day retention = massive buffer). Add Cassandra nodes and rebalance. Short-term: increase write batching window to reduce request rate.

### 5.5 — Poison pill message (corrupt event)
- **Mitigation**: Dead letter queue (DLQ). After N retries, route to DLQ topic. Alert. Do NOT block the pipeline for one bad event. DLQ has its own consumer for manual inspection / automated reprocessing after fix.

### 5.6 — Thundering herd after recovery
- **Mitigation**: Consumer rate limiting on restart. Consume at X% of max throughput, ramp up over 5-10 minutes. Prevents overwhelming the DB with a burst of replayed events.

### 5.7 — Data center / region failure
- **Mitigation**: Kafka MirrorMaker 2 (or Confluent Cluster Linking) for cross-region replication. Active-passive for the DB. RPO < 30s (replication lag). RTO < 5 minutes (DNS failover + consumer reconnect).

---

## 6. Observability & Operational Design

### Key Metrics (SLIs)

| Metric | Where | Alert Threshold |
|---|---|---|
| **Ingestion lag** | Kafka consumer group lag | > 100K messages for > 5 min |
| **E2E latency** | Producer timestamp → DB write timestamp | p99 > 5s |
| **Error rate** | Gateway 5xx / total | > 0.1% |
| **DLQ depth** | Dead letter queue message count | > 0 (alert on any) |
| **DB write latency** | Cassandra client-side | p99 > 50ms |
| **Disk usage** | Kafka broker, Cassandra nodes | > 70% |
| **Under-replicated partitions** | Kafka | > 0 |
| **Checkpoint duration** | Flink | > 60s |

### Deployment
- **Kafka**: Rolling restart, one broker at a time. No downtime if RF >= 3.
- **Flink jobs**: Savepoint → deploy new version → restore from savepoint. Zero event loss.
- **DB schema changes**: Backward-compatible only (add columns, never rename/remove). Use Avro schema evolution.

---

## 7. Evolution Story

### V1 — MVP (week 1-2)
- Single region, single Kafka cluster
- One stream processor writing directly to Cassandra
- No cold store, queries only on hot data
- Handles 100K events/sec

### V2 — Scale (month 2-3)
- Add cold store (ClickHouse) for historical analytics
- Add hot/cold query router
- Add DLQ and monitoring
- Handles 1M events/sec

### V3 — Multi-region (month 6+)
- Cross-region Kafka replication
- Active-active ingestion, active-passive DB
- Add CDC (Change Data Capture) from hot store to cold store instead of separate Flink job
- Add data governance (PII detection, encryption, retention policies)
- Handles 5M+ events/sec across regions

---

## 8. Common Interviewer Challenges & How to Respond

**"Why not write directly to the database?"**
> The database becomes the bottleneck and the single point of failure. Kafka decouples producers from consumers, absorbs traffic spikes, enables replay, and allows fan-out to multiple consumers. The 86 TB/day volume would overwhelm any single database's write path without a buffering layer.

**"Why not use a simpler queue like RabbitMQ / SQS?"**
> RabbitMQ is a message broker optimized for task distribution (message consumed once, then deleted). We need a distributed commit log — ordered, replayable, partitioned, with multi-consumer support. Kafka's append-only log with configurable retention is architecturally aligned. SQS could work for lower throughput but has no ordering guarantee (except FIFO queues, which cap at 3K msg/s per group).

**"How do you guarantee exactly-once?"**
> True exactly-once is achieved end-to-end via: (1) Idempotent Kafka producer (producer_id + sequence dedup at broker), (2) Flink's exactly-once via distributed snapshots (Chandy-Lamport) + Kafka transactions, (3) Idempotent writes to DB (upsert on natural key or idempotency token). Any single component alone is not sufficient.

**"What if a tenant sends 10x their expected volume?"**
> Per-tenant rate limiting at the gateway (token bucket). If exceeded, HTTP 429 with Retry-After header. Kafka partitioning by tenant_id means one tenant's volume doesn't affect other tenants' partitions. Consumer-side, per-tenant priority queuing if needed.

**"What happens to data consistency if the stream processor crashes mid-batch?"**
> Flink checkpoints atomically: Kafka offsets + operator state + sink pre-commit are all part of one consistent snapshot. On recovery, we roll back to the last checkpoint. Any events between checkpoint and crash are re-read from Kafka and reprocessed. The DB sink uses idempotent writes, so reprocessing doesn't create duplicates.
