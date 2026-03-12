# Distributed Job Scheduler at Global Scale

## Gold-Standard System Design — Principal / Sr. Staff Level

---

## Table of Contents

1. [Requirements & Scope](#1-requirements--scope)
2. [Capacity Estimation](#2-capacity-estimation)
3. [API Design](#3-api-design)
4. [Data Model](#4-data-model)
5. [High-Level Architecture](#5-high-level-architecture)
6. [Deep Dive: Scheduling Engine](#6-deep-dive-scheduling-engine)
7. [Deep Dive: DAG & Dependency Resolution](#7-deep-dive-dag--dependency-resolution)
8. [Deep Dive: Execution Layer](#8-deep-dive-execution-layer)
9. [Deep Dive: Global Distribution](#9-deep-dive-global-distribution)
10. [Failure Modes & Recovery](#10-failure-modes--recovery)
11. [Consistency & Exactly-Once Semantics](#11-consistency--exactly-once-semantics)
12. [Observability & Operational Maturity](#12-observability--operational-maturity)
13. [Trade-Off Analysis](#13-trade-off-analysis)
14. [Interview Scorecard](#14-interview-scorecard)

---

## 1. Requirements & Scope

### Functional Requirements

| Requirement | Detail |
|---|---|
| **One-time jobs** | Schedule a job to run at a specific time |
| **Recurring jobs** | Cron-like schedules (e.g., `*/5 * * * *`) |
| **Dependency chains (DAGs)** | Job B waits for Job A to succeed before executing |
| **Complex DAGs** | Fan-out (A → B,C,D), fan-in (B,C,D → E), diamond patterns |
| **Priority levels** | P0 (critical) through P3 (best-effort) |
| **Job lifecycle** | Create, pause, resume, cancel, retry |
| **Idempotent execution** | Same job scheduled twice produces same outcome |
| **Callback / webhook** | Notify callers on completion, failure |
| **Tenancy** | Multi-tenant with per-tenant rate limits and quotas |

### Non-Functional Requirements

| Dimension | Target |
|---|---|
| **Scale** | 10B+ job executions/day globally |
| **Latency (scheduling)** | < 100ms p99 from trigger time to dispatch |
| **Latency (firing accuracy)** | < 1s jitter from scheduled time |
| **Availability** | 99.99% (52 min downtime/year) |
| **Durability** | Zero job loss — every accepted job must eventually execute or be explicitly failed |
| **Regions** | 5+ regions globally (US-East, US-West, EU-West, AP-South, AP-Northeast) |
| **Consistency** | At-least-once delivery with idempotent execution guarantee |

### Out of Scope

- Job execution runtime (we dispatch; user provides execution environment)
- Data pipeline orchestration (Airflow-level DSL complexity)
- Sub-second scheduling (this is seconds-to-days granularity)

---

## 2. Capacity Estimation

### Traffic

```
Jobs created:       ~200K/sec peak globally
Jobs executed:      ~120K/sec peak (10B/day ÷ 86400 ≈ 115K, burst 2x)
Recurring jobs:     ~500M active recurring definitions
DAG definitions:    ~50M active DAGs
Avg nodes per DAG:  ~8 (some have 50+)
```

### Storage

```
Job definition:     ~2KB (metadata, schedule, deps, config)
Job execution log:  ~500B per execution
DAG definition:     ~5KB (adjacency list + per-node config)

Active job defs:    500M × 2KB = 1TB
Execution history:  10B/day × 500B × 30 days retention = 150TB
DAG defs:           50M × 5KB = 250GB
```

### Compute

```
Scheduling decisions:  120K/sec → need distributed scheduling across ~20 partitions
DAG evaluations:       ~50K/sec (triggered by job completions)
Worker fleet:          varies by tenant — scheduler only dispatches
```

---

## 3. API Design

### Job Management

```
POST   /v1/jobs                    — Create a one-time or recurring job
GET    /v1/jobs/{jobId}            — Get job definition + status
PUT    /v1/jobs/{jobId}            — Update job (schedule, payload, priority)
DELETE /v1/jobs/{jobId}            — Cancel/delete job
POST   /v1/jobs/{jobId}/pause      — Pause recurring job
POST   /v1/jobs/{jobId}/resume     — Resume paused job
GET    /v1/jobs/{jobId}/executions — List execution history
```

### DAG Management

```
POST   /v1/dags                     — Create a DAG definition
GET    /v1/dags/{dagId}             — Get DAG + current run status
POST   /v1/dags/{dagId}/trigger     — Manually trigger a DAG run
PUT    /v1/dags/{dagId}             — Update DAG (nodes, edges, schedule)
DELETE /v1/dags/{dagId}             — Delete DAG
GET    /v1/dags/{dagId}/runs/{runId} — Get specific DAG run status
```

### Job Creation Payload

```json
{
  "jobId": "order-cleanup-daily",           // Idempotency key
  "type": "RECURRING",                       // ONE_TIME | RECURRING
  "schedule": "0 2 * * *",                  // Cron expression (UTC)
  "timezone": "America/New_York",
  "payload": {
    "endpoint": "https://orders.internal/cleanup",
    "method": "POST",
    "headers": { "Authorization": "Bearer {{secret:cleanup-token}}" },
    "body": { "olderThanDays": 90 }
  },
  "priority": "P1",
  "retryPolicy": {
    "maxRetries": 3,
    "backoffMs": [1000, 5000, 30000]
  },
  "timeout": "PT30M",                       // ISO 8601 duration
  "deadlineAfter": "PT2H",                  // Kill if not done in 2 hours
  "callbackUrl": "https://notify.internal/scheduler-callback",
  "tags": { "team": "orders", "env": "prod" },
  "region": "us-east-1"                     // Preferred execution region
}
```

### DAG Creation Payload

```json
{
  "dagId": "etl-pipeline-daily",
  "schedule": "0 4 * * *",
  "nodes": [
    { "id": "extract",   "jobTemplate": { "endpoint": "..." } },
    { "id": "transform", "jobTemplate": { "endpoint": "..." } },
    { "id": "validate",  "jobTemplate": { "endpoint": "..." } },
    { "id": "load",      "jobTemplate": { "endpoint": "..." } },
    { "id": "notify",    "jobTemplate": { "endpoint": "..." } }
  ],
  "edges": [
    { "from": "extract",   "to": "transform" },
    { "from": "extract",   "to": "validate" },
    { "from": "transform", "to": "load" },
    { "from": "validate",  "to": "load" },
    { "from": "load",      "to": "notify" }
  ],
  "failurePolicy": "FAIL_FAST",             // FAIL_FAST | CONTINUE_ON_FAILURE | RETRY_NODE
  "maxConcurrentNodes": 10
}
```

This DAG looks like:
```
    extract
    /     \
transform  validate
    \     /
     load
      |
    notify
```

---

## 4. Data Model

### Core Tables (Sharded by tenant + jobId hash)

```sql
-- Job definitions
CREATE TABLE jobs (
    tenant_id       VARCHAR(64),
    job_id          VARCHAR(128),
    type            ENUM('ONE_TIME', 'RECURRING'),
    status          ENUM('ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED'),
    schedule_expr   VARCHAR(256),           -- Cron expression
    timezone        VARCHAR(64),
    next_fire_time  BIGINT,                 -- Epoch ms, indexed
    payload         JSONB,
    priority        TINYINT,
    retry_policy    JSONB,
    timeout_ms      BIGINT,
    callback_url    VARCHAR(512),
    region_affinity VARCHAR(32),
    tags            JSONB,
    version         BIGINT,                 -- Optimistic concurrency
    created_at      BIGINT,
    updated_at      BIGINT,
    PRIMARY KEY (tenant_id, job_id)
);

-- Partition index for the scheduler to poll
CREATE INDEX idx_next_fire ON jobs (next_fire_time, priority, status)
    WHERE status = 'ACTIVE';

-- Job execution records
CREATE TABLE job_executions (
    tenant_id       VARCHAR(64),
    job_id          VARCHAR(128),
    execution_id    VARCHAR(128),           -- UUID
    dag_run_id      VARCHAR(128),           -- NULL for standalone jobs
    status          ENUM('PENDING', 'DISPATCHED', 'RUNNING',
                         'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'),
    scheduled_time  BIGINT,
    dispatch_time   BIGINT,
    start_time      BIGINT,
    end_time        BIGINT,
    attempt         INT,
    worker_id       VARCHAR(128),
    result          JSONB,
    error           TEXT,
    PRIMARY KEY (tenant_id, job_id, execution_id)
);

-- DAG definitions
CREATE TABLE dags (
    tenant_id       VARCHAR(64),
    dag_id          VARCHAR(128),
    status          ENUM('ACTIVE', 'PAUSED', 'DELETED'),
    schedule_expr   VARCHAR(256),
    nodes           JSONB,                  -- Array of node definitions
    edges           JSONB,                  -- Adjacency list
    failure_policy  VARCHAR(32),
    max_concurrent  INT,
    version         BIGINT,
    PRIMARY KEY (tenant_id, dag_id)
);

-- DAG run instances
CREATE TABLE dag_runs (
    tenant_id       VARCHAR(64),
    dag_id          VARCHAR(128),
    run_id          VARCHAR(128),
    status          ENUM('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'),
    trigger_type    ENUM('SCHEDULED', 'MANUAL', 'RETRY'),
    -- Per-node status tracked as JSONB for atomic updates
    node_statuses   JSONB,
    started_at      BIGINT,
    completed_at    BIGINT,
    PRIMARY KEY (tenant_id, dag_id, run_id)
);
```

### Why Not a Graph DB for DAGs?

At this scale, DAGs are small (avg 8 nodes) but there are millions of them. A relational store with JSONB adjacency lists is simpler operationally, and the DAG traversal is done in-memory after loading the definition. Graph DBs add operational complexity without meaningful benefit here.

---

## 5. High-Level Architecture

```
                                   ┌──────────────────────────────────────────────┐
                                   │              GLOBAL LAYER                     │
                                   │                                               │
                                   │   ┌─────────────────────────────────────┐     │
                                   │   │     Global Control Plane (GCP)      │     │
                                   │   │  - DAG registration                 │     │
                                   │   │  - Cross-region job routing         │     │
                                   │   │  - Quota management                 │     │
                                   │   │  - Region health monitoring         │     │
                                   │   └─────────────────────────────────────┘     │
                                   └──────────────────┬───────────────────────────┘
                                                      │
                        ┌─────────────────────────────┼─────────────────────────────┐
                        │                             │                             │
               ┌────────▼────────┐           ┌───────▼────────┐           ┌────────▼────────┐
               │   US-EAST       │           │   EU-WEST       │           │   AP-SOUTH      │
               │                 │           │                 │           │                 │
               │  ┌───────────┐  │           │  ┌───────────┐  │           │  ┌───────────┐  │
               │  │ API GW    │  │           │  │ API GW    │  │           │  │ API GW    │  │
               │  └─────┬─────┘  │           │  └─────┬─────┘  │           │  └─────┬─────┘  │
               │        │        │           │        │        │           │        │        │
               │  ┌─────▼─────┐  │           │  ┌─────▼─────┐  │           │  ┌─────▼─────┐  │
               │  │ Scheduler │  │           │  │ Scheduler │  │           │  │ Scheduler │  │
               │  │  Cluster  │  │           │  │  Cluster  │  │           │  │  Cluster  │  │
               │  └─────┬─────┘  │           │  └─────┬─────┘  │           │  └─────┬─────┘  │
               │        │        │           │        │        │           │        │        │
               │  ┌─────▼─────┐  │           │  ┌─────▼─────┐  │           │  ┌─────▼─────┐  │
               │  │   DAG     │  │           │  │   DAG     │  │           │  │   DAG     │  │
               │  │  Engine   │  │           │  │  Engine   │  │           │  │  Engine   │  │
               │  └─────┬─────┘  │           │  └─────┬─────┘  │           │  └─────┬─────┘  │
               │        │        │           │        │        │           │        │        │
               │  ┌─────▼─────┐  │           │  ┌─────▼─────┐  │           │  ┌─────▼─────┐  │
               │  │ Dispatch  │  │           │  │ Dispatch  │  │           │  │ Dispatch  │  │
               │  │  Queue    │  │           │  │  Queue    │  │           │  │  Queue    │  │
               │  └─────┬─────┘  │           │  └─────┬─────┘  │           │  └─────┬─────┘  │
               │        │        │           │        │        │           │        │        │
               │  ┌─────▼─────┐  │           │  ┌─────▼─────┐  │           │  ┌─────▼─────┐  │
               │  │  Worker   │  │           │  │  Worker   │  │           │  │  Worker   │  │
               │  │  Fleet    │  │           │  │  Fleet    │  │           │  │  Fleet    │  │
               │  └───────────┘  │           │  └───────────┘  │           │  └───────────┘  │
               │                 │           │                 │           │                 │
               │  ┌───────────┐  │           │  ┌───────────┐  │           │  ┌───────────┐  │
               │  │ Job Store │  │           │  │ Job Store │  │           │  │ Job Store │  │
               │  │ (CockroachDB) │          │  │ (CockroachDB) │          │  │ (CockroachDB) │
               │  └───────────┘  │           │  └───────────┘  │           │  └───────────┘  │
               └─────────────────┘           └─────────────────┘           └─────────────────┘
```

### Component Responsibilities

| Component | Role |
|---|---|
| **API Gateway** | Auth, rate limiting, request validation, tenant isolation |
| **Scheduler Cluster** | Polls for due jobs, acquires locks, dispatches to queue |
| **DAG Engine** | Evaluates DAG state on job completion, resolves next runnable nodes |
| **Dispatch Queue** | Kafka/SQS — durable buffer between scheduler and workers |
| **Worker Fleet** | Executes jobs (HTTP calls, gRPC, Lambda invocations, etc.) |
| **Job Store** | CockroachDB — strongly consistent, multi-region |
| **Global Control Plane** | Cross-region coordination, failover decisions |

---

## 6. Deep Dive: Scheduling Engine

This is the heart of the system. The scheduler must efficiently find and fire jobs whose `next_fire_time ≤ now`.

### The Partition-Based Polling Architecture

Polling a single table of 500M+ jobs is not viable. We **partition the time-space into buckets**.

```
Time Wheel Architecture:

    ┌──────────────────────────────────────────────┐
    │              Time Wheel (60 slots)            │
    │                                               │
    │   Slot 0: jobs due at :00                     │
    │   Slot 1: jobs due at :01                     │
    │   Slot 2: jobs due at :02                     │
    │   ...                                         │
    │   Slot 59: jobs due at :59                    │
    │                                               │
    │   Each slot is an ordered set of (priority,   │
    │   fire_time, job_id) sorted entries            │
    │                                               │
    │   Current pointer advances every second        │
    └──────────────────────────────────────────────┘
```

#### Two-Level Time Wheel

```
Level 1 — Minute Wheel (60 slots):
  Each slot holds jobs firing in that second of the current minute.
  Scanned by the "immediate scheduler" — tight loop, low latency.

Level 2 — Hour Wheel (3600 slots):
  Jobs firing in the next hour.
  Every minute, Level 2 drains the next 60 entries into Level 1.

Level 3 — Persistent Store:
  Jobs firing > 1 hour from now.
  A background "promoter" moves jobs from DB → Level 2 on a rolling basis.
```

#### Why a Time Wheel Over DB Polling?

| Approach | Throughput | Latency | Complexity |
|---|---|---|---|
| DB poll every second | ~10K/s (index scan bottleneck) | 1-2s jitter | Low |
| DB poll + partition by minute | ~50K/s | ~1s jitter | Medium |
| **Time wheel + DB backing** | **500K+/s** | **< 100ms jitter** | High |
| Redis sorted set | ~200K/s | < 50ms | Medium |

We use the **time wheel with DB as source of truth**. The wheel is an in-memory acceleration structure; the DB is the durable store.

### Scheduler Partitioning

With 120K jobs/sec to schedule, a single node can't handle this. We partition:

```
                    ┌──────────────────┐
                    │  Partition Map   │
                    │  (ZooKeeper /    │
                    │   etcd)          │
                    └────────┬─────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
     ┌────────▼──────┐  ┌───▼──────────┐  ┌▼──────────────┐
     │ Scheduler P0  │  │ Scheduler P1 │  │ Scheduler P2  │
     │ Token: 0-333  │  │ Token: 334-  │  │ Token: 667-   │
     │               │  │   666        │  │   999         │
     │ Owns jobs     │  │ Owns jobs    │  │ Owns jobs     │
     │ hashing to    │  │ hashing to   │  │ hashing to    │
     │ range 0-333   │  │ range 334-   │  │ range 667-    │
     └───────────────┘  │  666         │  │   999         │
                        └──────────────┘  └───────────────┘
```

**Partition assignment**: `partition = murmur3(tenantId + jobId) % numPartitions`

Each scheduler instance:
1. Owns a range of the hash ring
2. Maintains its own time wheel for those jobs
3. Registers a lease in etcd (30s TTL, heartbeat every 10s)
4. On lease expiry, another scheduler takes over the partition

### Scheduler Tick Loop (per partition)

```python
every 100ms:
    current_time = now()

    # 1. Drain time wheel slots up to current_time
    due_jobs = time_wheel.drain_until(current_time)

    # 2. For each due job, attempt to claim it
    for job in due_jobs:
        # Optimistic lock via version column
        success = db.update(
            SET status = 'DISPATCHED', version = version + 1
            WHERE job_id = ? AND version = ? AND status = 'ACTIVE'
        )
        if success:
            dispatch_queue.enqueue(job, priority=job.priority)

            # 3. Compute next fire time for recurring jobs
            if job.type == 'RECURRING':
                next_time = cron_next(job.schedule_expr, current_time)
                db.update(SET next_fire_time = next_time WHERE job_id = ?)
                time_wheel.insert(next_time, job)

            # 4. If job is part of a DAG, notify DAG engine
            if job.dag_run_id:
                dag_engine.notify_dispatched(job.dag_run_id, job.node_id)
```

### Hot Partition Problem

If a single tenant creates millions of jobs all firing at midnight, one scheduler partition gets overwhelmed.

**Solution: Virtual partitioning within tenants**

```
job_id "tenant-123:cleanup-job" → hash → partition 42
job_id "tenant-123:report-job"  → hash → partition 187

Same tenant's jobs spread across all partitions.
```

For DAGs where all nodes belong to the same tenant, we ensure the DAG Engine can coordinate across partitions (see Section 7).

---

## 7. Deep Dive: DAG & Dependency Resolution

### DAG Execution Model

A DAG Run is a single execution instance of a DAG definition. Each node in the run maps to a job execution.

```
DAG Definition (template):
    extract → transform → load → notify
                ↘ validate ↗

DAG Run Instance (run-id: run-001):
    extract [SUCCEEDED]
       ├── transform [RUNNING]
       └── validate [SUCCEEDED]
           load [BLOCKED]         ← waiting on transform
           notify [BLOCKED]       ← waiting on load
```

### Node State Machine

```
                    ┌─────────┐
                    │ BLOCKED │ ←── initial state (has unmet dependencies)
                    └────┬────┘
                         │ all parents SUCCEEDED
                         ▼
                    ┌─────────┐
                    │  READY  │ ←── initial state (no dependencies)
                    └────┬────┘
                         │ scheduler picks up
                         ▼
                    ┌──────────┐
                    │ DISPATCHED│
                    └────┬─────┘
                         │ worker starts
                         ▼
                    ┌─────────┐
                    │ RUNNING │
                    └────┬────┘
                    ╱         ╲
                   ▼           ▼
            ┌───────────┐  ┌────────┐
            │ SUCCEEDED │  │ FAILED │
            └───────────┘  └────┬───┘
                                │ retry?
                                ▼
                          ┌──────────┐
                          │ RETRYING │ → back to DISPATCHED
                          └──────────┘
```

### DAG Engine: Event-Driven Evaluation

The DAG engine does **not** poll. It reacts to events.

```
                    Job Completion Events
                          │
                          ▼
                ┌───────────────────┐
                │    DAG Engine     │
                │                   │
                │  1. Load dag_run  │
                │  2. Update node   │
                │     status        │
                │  3. Evaluate      │
                │     children      │
                │  4. Enqueue newly │
                │     READY nodes   │
                └───────────────────┘
```

#### Evaluation Algorithm

```python
def on_job_completed(dag_run_id: str, node_id: str, result: Status):
    """Called when any job in a DAG finishes."""

    # 1. Load DAG run with lock (distributed lock keyed on dag_run_id)
    with distributed_lock(f"dag-run:{dag_run_id}", ttl=10s):
        dag_run = db.load(dag_run_id)
        dag_def = db.load(dag_run.dag_id)

        # 2. Update this node's status
        dag_run.node_statuses[node_id] = result

        # 3. If FAILED, apply failure policy
        if result == FAILED:
            if dag_def.failure_policy == 'FAIL_FAST':
                cancel_all_running_nodes(dag_run)
                dag_run.status = 'FAILED'
                db.save(dag_run)
                notify_callback(dag_run)
                return
            elif dag_def.failure_policy == 'RETRY_NODE':
                if node_attempts[node_id] < max_retries:
                    enqueue_job(dag_run, node_id)  # Retry this node
                    return

        # 4. Find children whose ALL parents are now SUCCEEDED
        newly_ready = []
        for child in dag_def.children_of(node_id):
            parents = dag_def.parents_of(child)
            if all(dag_run.node_statuses[p] == SUCCEEDED for p in parents):
                newly_ready.append(child)

        # 5. Respect max_concurrent limit
        running_count = count(dag_run.node_statuses, status=RUNNING)
        slots = dag_def.max_concurrent - running_count
        to_dispatch = newly_ready[:slots]
        to_wait = newly_ready[slots:]

        # 6. Dispatch ready nodes
        for node in to_dispatch:
            dag_run.node_statuses[node] = DISPATCHED
            enqueue_job(dag_run, node)

        # Mark remaining as READY (will be dispatched when slots open)
        for node in to_wait:
            dag_run.node_statuses[node] = READY

        # 7. Check if DAG is complete
        if all(s in (SUCCEEDED, SKIPPED) for s in dag_run.node_statuses.values()):
            dag_run.status = 'SUCCEEDED'
            notify_callback(dag_run)
        elif all(s in (SUCCEEDED, FAILED, SKIPPED) for s in dag_run.node_statuses.values()):
            dag_run.status = 'FAILED'
            notify_callback(dag_run)

        db.save(dag_run)
```

### DAG Validation (on creation)

```python
def validate_dag(nodes, edges):
    """Reject if DAG has cycles — would cause infinite wait."""
    # Kahn's algorithm: topological sort
    in_degree = {n.id: 0 for n in nodes}
    adj = defaultdict(list)
    for e in edges:
        adj[e.from_].append(e.to)
        in_degree[e.to] += 1

    queue = [n for n, d in in_degree.items() if d == 0]
    visited = 0
    while queue:
        node = queue.pop(0)
        visited += 1
        for child in adj[node]:
            in_degree[child] -= 1
            if in_degree[child] == 0:
                queue.append(child)

    if visited != len(nodes):
        raise ValidationError("DAG contains a cycle")
```

### Handling Diamond Dependencies

```
         A
        / \
       B   C          ← B and C run in parallel after A
        \ /
         D             ← D runs only after BOTH B and C succeed
```

The evaluation algorithm naturally handles this: when B completes, it checks D's parents (B, C). C hasn't finished, so D stays BLOCKED. When C completes, all parents are SUCCEEDED, so D becomes READY.

### Cross-Partition DAG Coordination

A DAG's nodes may hash to different scheduler partitions. The DAG Engine is a **separate service** (not embedded in the scheduler) to avoid cross-partition coupling.

```
Scheduler P0 ──→ completes node "extract" ──→ Kafka topic: "dag-events"
                                                       │
DAG Engine (consumes dag-events) ─────────────────────┘
    │
    ├── Updates dag_run in DB
    ├── Determines "transform" and "validate" are now READY
    ├── Enqueues "transform" to dispatch queue
    └── Enqueues "validate" to dispatch queue
                                                       │
Scheduler P1 picks up "transform"  ◄───────────────────┘
Scheduler P2 picks up "validate"   ◄───────────────────┘
```

The DAG Engine partitions by `dag_run_id` so that all events for a given run go to the same engine instance, avoiding race conditions.

---

## 8. Deep Dive: Execution Layer

### Dispatch Queue Design

```
Kafka Topic: "job-dispatch"

  Partition 0 (P0 priority):  [job-a, job-b, ...]
  Partition 1 (P1 priority):  [job-c, job-d, ...]
  Partition 2 (P2 priority):  [job-e, job-f, ...]
  Partition 3 (P3 priority):  [job-g, job-h, ...]
```

**Priority implementation**: Separate Kafka topics per priority level. Workers consume from P0 first, then P1, etc. — weighted consumption ratio 8:4:2:1.

### Worker Execution Flow

```
Worker:
    1. Poll dispatch queue (priority-weighted)
    2. Deserialize job payload
    3. Execute:
       - HTTP webhook: POST to job.payload.endpoint
       - gRPC call: invoke target service
       - Lambda: invoke function
       - Container: submit to K8s job
    4. Report result:
       - SUCCESS → Kafka "job-results" topic
       - FAILURE → Kafka "job-results" topic (with error)
       - TIMEOUT → Kafka "job-results" topic (triggered by deadline)
    5. Ack message from dispatch queue
```

### Timeout Enforcement

Workers can crash without reporting. A **deadline tracker** runs separately:

```
Deadline Tracker (per region):
    Every 10s:
        overdue = SELECT * FROM job_executions
                  WHERE status = 'RUNNING'
                  AND dispatch_time + timeout_ms < now()

        for job in overdue:
            mark TIMED_OUT
            publish to "job-results" topic
            (triggers retry or DAG failure handling)
```

### Idempotent Execution

Every job execution gets a unique `execution_id`. The worker passes this to the target service:

```
POST https://orders.internal/cleanup
Headers:
    Idempotency-Key: exec-abc-123-def
    X-Scheduler-Attempt: 2
```

The target service is responsible for deduplication using this key. The scheduler guarantees at-least-once delivery; the target guarantees exactly-once processing.

---

## 9. Deep Dive: Global Distribution

### Region Assignment

```
Job created in US-East:
    1. If region_affinity = "us-east-1" → schedule in US-East
    2. If region_affinity = null → schedule in region closest to target endpoint
    3. If region is down → failover to next closest region

DAG:
    All nodes in a DAG run execute in the SAME region (data locality).
    Cross-region DAGs are explicitly modeled as separate DAGs with triggers.
```

### Multi-Region Data Architecture

```
                     CockroachDB Multi-Region
              ┌──────────────────────────────────┐
              │                                  │
              │   US-East (Leaseholder for       │
              │   tenants A-M)                   │
              │          │                       │
              │   EU-West (Leaseholder for       │
              │   tenants N-Z)                   │
              │          │                       │
              │   AP-South (Follower reads,      │
              │   can become leaseholder)         │
              │                                  │
              └──────────────────────────────────┘
```

**Why CockroachDB?**

| Requirement | CockroachDB fit |
|---|---|
| Strong consistency | Raft-based replication, serializable isolation |
| Multi-region | Native geo-partitioning, follower reads |
| No job loss | Synchronous replication within survival goals |
| SQL + JSONB | Full SQL with JSONB for flexible schemas |

**Alternative: DynamoDB Global Tables** — Eventually consistent cross-region. Acceptable if we tolerate rare double-fires (mitigated by idempotent execution). Lower operational burden but weaker consistency.

### Cross-Region Failover

```
Normal operation:
    US-East scheduler handles tenants A-M
    EU-West scheduler handles tenants N-Z

US-East goes down:
    1. Global Control Plane detects (health checks fail 3x)
    2. GCP reassigns US-East partitions to EU-West and AP-South
    3. New scheduler instances load time wheels from DB
    4. Jobs resume within ~30s (lease expiry + wheel rebuild)
    5. Jobs that were in-flight may be re-dispatched (at-least-once)
```

### Time Synchronization

Global scheduling requires tight clock sync. Use:
- **NTP** with Chrony (< 1ms accuracy within a region)
- **TrueTime-style** bounds if using Spanner (< 7ms globally)
- **CockroachDB HLC** (Hybrid Logical Clocks) for database ordering

Fire times use UTC epoch milliseconds everywhere. Timezone conversion happens only at the API layer for display.

---

## 10. Failure Modes & Recovery

### Failure Matrix

| Failure | Impact | Detection | Recovery |
|---|---|---|---|
| **Scheduler node crash** | Owned partitions stop firing | etcd lease expires (30s) | Other nodes claim orphaned partitions |
| **Worker crash mid-execution** | Job stuck in RUNNING | Deadline tracker (timeout) | Re-dispatch with incremented attempt |
| **Kafka broker down** | Dispatch queue unavailable | Producer timeout | Retry produce; fallback to direct DB dispatch |
| **DB node failure** | Writes fail in region | CockroachDB Raft | Automatic Raft leader election (~5s) |
| **Entire region down** | All jobs in region stop | Global health check | GCP failover to surviving regions |
| **DAG Engine crash** | DAG events pile up | Consumer lag alert | New instance resumes from Kafka offset |
| **Clock skew > 1s** | Jobs fire early/late | NTP monitoring | Alert + manual correction |
| **Stuck DAG (deadlock)** | DAG never completes | Watchdog (no progress in X hours) | Alert ops; force-fail stuck nodes |
| **Split brain (2 schedulers own same partition)** | Double dispatch | Fencing token on DB writes | Lower-fenced scheduler's writes rejected |

### Fencing Tokens for Split-Brain Prevention

```
Scheduler acquires partition lease:
    lease_id = etcd.grant(ttl=30s)
    fence_token = lease_id  (monotonically increasing)

When dispatching a job:
    UPDATE jobs
    SET status = 'DISPATCHED', fence_token = ?
    WHERE job_id = ? AND (fence_token IS NULL OR fence_token < ?)

    → Old scheduler's writes are rejected because its fence_token is stale.
```

### Exactly-Once DAG Node Execution

Even with at-least-once job delivery, we need each DAG node to execute exactly once per run:

```python
# In DAG Engine, when marking a node as DISPATCHED:
UPDATE dag_runs
SET node_statuses = jsonb_set(node_statuses, '{transform}', '"DISPATCHED"')
WHERE dag_id = ? AND run_id = ?
AND node_statuses->>'transform' = 'READY'   -- CAS: only if still READY
```

If two DAG Engine instances race, only one CAS succeeds.

---

## 11. Consistency & Exactly-Once Semantics

### The Consistency Spectrum

```
                    Consistency Level
         ◄──────────────────────────────────────►
         Weak                                Strong

    At-most-once    At-least-once    Exactly-once
    (fire & forget)  (retry on fail)  (idempotent exec)

    This system: ──────────────────────────────┤
                  At-least-once delivery      │
                  + Idempotent execution       │
                  = Effectively exactly-once   │
```

### Why Not True Exactly-Once?

In a distributed system, exactly-once is impossible without 2PC (which doesn't scale). Our approach:

1. **Scheduler → Queue**: At-least-once (scheduler retries if ack not received)
2. **Queue → Worker**: At-least-once (Kafka consumer commits after processing)
3. **Worker → Target**: At-least-once (worker retries on timeout)
4. **Target**: Idempotent (deduplicates on `execution_id`)

This gives end-to-end effectively-exactly-once semantics without the performance cost of distributed transactions.

### Ordering Guarantees

- **Within a single job**: Executions are ordered (execution N must complete before N+1)
- **Within a DAG**: Topological order guaranteed by dependency resolution
- **Across independent jobs**: No ordering guarantee (and none needed)
- **Recurring job overlap prevention**: If execution N is still running when N+1 is due, configurable behavior: `SKIP`, `QUEUE`, or `ALLOW_CONCURRENT`

---

## 12. Observability & Operational Maturity

### Key Metrics

```
Scheduler Health:
    scheduler.jobs.due                  — Gauge: jobs past their fire time but not dispatched
    scheduler.jobs.dispatched.rate      — Counter: jobs dispatched per second
    scheduler.tick.latency.p99          — Histogram: time from fire_time to dispatch
    scheduler.partition.ownership       — Gauge per node: owned partitions
    scheduler.time_wheel.size           — Gauge: jobs in time wheel

DAG Engine:
    dag.runs.active                     — Gauge: currently running DAG runs
    dag.node.transition.rate            — Counter per transition type
    dag.evaluation.latency.p99          — Histogram: time to evaluate dependencies
    dag.run.duration.p99                — Histogram: total DAG run time

Execution:
    execution.success.rate              — Counter: successful executions/sec
    execution.failure.rate              — Counter: failed executions/sec
    execution.retry.rate                — Counter: retries/sec
    execution.latency.p99               — Histogram: execution duration
    execution.queue.depth               — Gauge per priority: pending in dispatch queue

Global:
    region.health                       — Boolean per region
    cross_region.replication.lag        — Gauge: CockroachDB replication lag
    tenant.quota.utilization            — Gauge per tenant: % of quota used
```

### Alerting Thresholds

```
CRITICAL:
    scheduler.jobs.due > 10000 for 2 min       — Scheduler falling behind
    execution.queue.depth.p0 > 5000 for 1 min  — P0 jobs backing up
    dag.runs.stuck > 0 for 30 min              — DAG with no progress
    region.health = false for 1 min            — Region failure

WARNING:
    scheduler.tick.latency.p99 > 500ms          — Scheduling getting slow
    execution.failure.rate > 5% for 5 min       — Elevated failure rate
    tenant.quota.utilization > 80%              — Tenant approaching limits
```

### Operational Runbooks

**Scenario: Scheduler partition stuck**
```
1. Check scheduler.jobs.due metric → identify which partition
2. nodetool describe-partition <partition> → find owning scheduler
3. Check scheduler health → if unresponsive, force-revoke etcd lease
4. New scheduler claims partition → verify jobs.due drops
```

**Scenario: DAG stuck in RUNNING**
```
1. Query dag_runs WHERE status = 'RUNNING' AND started_at < now() - max_duration
2. For each stuck run, check node_statuses → find the blocked node
3. If node is RUNNING with no heartbeat → force timeout
4. If node is BLOCKED with parent SUCCEEDED → DAG engine bug, force re-evaluate
5. If circular dependency detected → data corruption, force-fail DAG
```

---

## 13. Trade-Off Analysis

### Key Trade-Offs Made

| Decision | Alternative | Why this choice |
|---|---|---|
| **Time wheel + DB backing** | Pure DB polling | Latency: 100ms vs 1-2s. Worth the complexity at this scale. |
| **CockroachDB** | DynamoDB Global Tables | Need strong consistency for job deduplication. DynamoDB's eventual consistency risks double-fire. |
| **Separate DAG Engine** | DAG logic embedded in scheduler | Separation of concerns. DAG evaluation spans scheduler partitions. Easier to scale independently. |
| **Kafka for dispatch** | Direct RPC to workers | Durability, backpressure, replay. Workers can be heterogeneous (Lambda, K8s, EC2). |
| **At-least-once + idempotency** | 2PC for exactly-once | 2PC doesn't scale globally. Idempotency pushes dedup to the edge where it belongs. |
| **Partition by hash(tenantId+jobId)** | Partition by time bucket | Even distribution. Time-bucket partitioning creates hot spots at boundaries. |
| **Per-region scheduler fleet** | Global scheduler | Latency. Jobs fire from the region closest to the target. Cross-region adds 50-200ms. |
| **JSONB for dag_run node_statuses** | Separate node_status table | Atomic update of entire DAG state in one row. Avoids multi-row transactions for state changes. |

### What This Design Does NOT Handle Well

| Limitation | Why | Mitigation |
|---|---|---|
| **Sub-second scheduling** | Time wheel granularity + DB round-trip | Use a dedicated real-time system (e.g., in-memory event loop) |
| **Very large DAGs (1000+ nodes)** | JSONB row size, lock contention on dag_run | Shard DAG runs into sub-DAGs, or use a graph-native engine |
| **Cross-region DAGs** | Data locality, latency | Model as separate DAGs with cross-region triggers |
| **Job execution content** | We're a scheduler, not an executor | Workers are customer-provided; we just dispatch |

### Technology Alternatives Comparison

| Component | Our Choice | Alternative 1 | Alternative 2 |
|---|---|---|---|
| Job Store | CockroachDB | Spanner (GCP-only) | Vitess + MySQL (more ops) |
| Dispatch Queue | Kafka | SQS (simpler, per-region) | Redis Streams (lower latency, less durable) |
| Coordination | etcd | ZooKeeper (battle-tested) | Consul (combined with service discovery) |
| DAG Engine | Custom event-driven | Temporal workflows (heavier) | Airflow (not real-time enough) |

---

## 14. Interview Scorecard

### How an Interviewer Would Evaluate This Design

| Dimension | What they're looking for | Key talking points |
|---|---|---|
| **Scoping** | Did you clarify DAG vs simple jobs? Recurring vs one-time? Scale numbers? | "I'd first separate the problem into three sub-systems: time-based scheduling, dependency resolution, and reliable execution." |
| **API Design** | Clean REST API, idempotency keys, proper error codes | Show the DAG creation payload. Discuss why jobId is an idempotency key. |
| **Capacity** | 10B/day → 120K/sec. Storage for 30-day retention. | Show the math. Mention that the bottleneck is scheduling decisions, not storage. |
| **Architecture Depth** | Time wheel, partition-based scheduling, event-driven DAG engine | Draw the partition diagram. Explain why DB polling doesn't work at 120K/sec. |
| **Consistency** | At-least-once + idempotent = effectively exactly-once | Explain the fencing token. Show the CAS operation for DAG node transitions. |
| **Failure Handling** | Every component failure has a recovery path | Walk through the failure matrix. Emphasize: "no job loss" means durability before ack. |
| **Trade-offs** | CockroachDB vs DynamoDB, embedded vs separate DAG engine | Be ready to defend choices. "If I had to simplify, I'd drop the time wheel and use DB polling with partitioned minute-buckets." |
| **Operational Maturity** | Metrics, alerts, runbooks | Mention scheduler.jobs.due as the #1 health metric. |

### Common Interviewer Follow-Up Questions

**Q: "What if a DAG has 10,000 nodes?"**
A: JSONB on a single row won't scale. I'd shard the DAG run into sub-graphs of ~100 nodes each, with a coordinator DAG-of-DAGs. Each sub-graph runs as an independent dag_run, connected by cross-DAG triggers.

**Q: "How do you handle timezone changes (DST)?"**
A: All internal times are UTC epoch ms. The cron expression includes a timezone field. When computing `next_fire_time`, we use the IANA timezone database (via a library like `java.time.ZonedDateTime`). DST transitions are handled by the library — a job scheduled at "2:30 AM ET" during spring-forward simply fires at 3:30 AM.

**Q: "What happens during a rolling deploy of the scheduler?"**
A: Graceful shutdown: scheduler stops acquiring new jobs, finishes dispatching in-flight ones, releases etcd leases. Other schedulers claim the orphaned partitions. Zero-downtime because partitions are always owned by exactly one live scheduler.

**Q: "Traffic just 10x'd. What breaks first?"**
A: The dispatch queue consumer lag spikes first (workers can't keep up). Then scheduler.jobs.due rises (scheduler is dispatching but jobs queue up). Mitigation: auto-scale workers based on queue depth. If scheduling throughput is the bottleneck, add more scheduler partitions (increase numPartitions and rebalance).

**Q: "The CockroachDB cluster is slow. Now what?"**
A: Short-term: the time wheel serves as a buffer — jobs that are already in the wheel still fire on time. Long-term: add read replicas for follower reads, check for hot ranges (a single tenant with huge job counts), and consider promoting the time wheel to a Redis-backed persistent structure to reduce DB dependency.

**Q: "A customer accidentally created a DAG with a cycle. How do you prevent this?"**
A: Validation at creation time using Kahn's algorithm (topological sort). If the sorted count != node count, there's a cycle. Reject with a 400 error showing which nodes form the cycle. This is O(V+E) and runs in milliseconds even for large DAGs.

---

## Appendix: Reference Implementations

| System | What to study |
|---|---|
| **Uber Peloton** | Multi-region job scheduler, priority queues |
| **Airflow** | DAG definition and evaluation model (but not real-time) |
| **Temporal** | Workflow execution with durability guarantees |
| **Google Borg / Kubernetes CronJob** | Cluster-level job scheduling |
| **AWS Step Functions** | DAG execution as a service, state machine model |
| **Quartz Scheduler** | JVM-based, single-node reference for time wheel concepts |
| **Kafka + exactly-once semantics** | Idempotent producer, transactional consumers |
