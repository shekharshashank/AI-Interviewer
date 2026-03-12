# System Design Interview Prep

A comprehensive system design interview preparation platform targeting **Principal / Sr. Staff Engineer (L7/L8)** level candidates at companies like Stripe, FAANG, and Databricks.

## Overview

The project has three pillars:

1. **Interactive Portal** — A React web app with an embedded whiteboard and AI-powered interview coaching
2. **Coding Questions** — 10 self-contained Java implementations of Stripe-style coding problems
3. **Design Documents** — Gold-standard system design writeups following the SACRED framework

## Project Structure

```
.
├── portal/                  # React/TypeScript web application
│   └── src/
│       ├── components/      # ExcalidrawCanvas, QuestionSidebar, QuestionDetail, ReviewPanel
│       ├── data/            # 50 built-in system design questions
│       ├── services/        # AWS Bedrock / Claude integration
│       ├── types/           # TypeScript type definitions
│       └── utils/           # Excalidraw helpers, file I/O, localStorage persistence
├── coding-questions/        # 10 standalone Java interview problems
├── designs/                 # Detailed system design writeups
└── task.md                  # Master curriculum and SACRED framework definition
```

## Portal

A 3-column React SPA that mirrors a real interview setup: pick a question, draw an architecture diagram, and get AI feedback.

**Features:**
- 50 system design questions across 10 categories (distributed infra, data systems, financial/transactional, real-time/streaming, APIs, search, content/media, geospatial, identity/security, observability)
- Embedded [Excalidraw](https://excalidraw.com/) whiteboard for drawing architecture diagrams
- AI interview coach powered by Claude Sonnet via AWS Bedrock that reviews diagrams and asks probing follow-up questions
- Evaluation using the **SACRED framework** (Scope, API, Capacity, Raw Architecture, Elaborate, Defend & Evolve)
- Export/import diagrams as PNG or JSON
- Add custom questions beyond the built-in set
- All state persisted in localStorage

### Tech Stack

- React 18 + TypeScript + Vite
- @excalidraw/excalidraw for the whiteboard
- AWS Bedrock (`@aws-sdk/client-bedrock-runtime`) with streaming via SSE
- Custom Vite plugin for proxying requests to Bedrock

### Setup

```bash
cd portal
npm install
```

Copy the environment file and configure your AWS credentials:

```bash
cp .env.example .env
```

Required variables in `.env`:

| Variable | Description |
|----------|-------------|
| `AWS_REGION` | AWS region (e.g. `us-east-1`) |
| `BEDROCK_MODEL_ID` | Model ID (default: `us.anthropic.claude-sonnet-4-20250514-v1:0`) |
| `AWS_ACCESS_KEY_ID` | AWS access key (optional if using default credential chain) |
| `AWS_SECRET_ACCESS_KEY` | AWS secret key (optional if using default credential chain) |
| `AWS_SESSION_TOKEN` | STS session token (optional) |

Start the dev server:

```bash
npm run dev
```

## Coding Questions

Ten standalone Java files implementing Stripe-style interview problems. Each file is self-contained with data models, full implementation, and inline tests runnable via `main()`.

| # | File | Problem |
|---|------|---------|
| 1 | `RateLimiter.java` | Sliding window, token bucket, hierarchical limits |
| 2 | `RetryQueue.java` | Priority queue with circuit breaker, task dependencies, dead-letter queue |
| 3 | `WebhookDelivery.java` | HMAC-SHA256 signing, circuit breaker per endpoint, deduplication |
| 4 | `FraudDetector.java` | Weighted rule engine, per-merchant thresholds, allowlist/blocklist |
| 5 | `DebtSettler.java` | Minimum transactions via bitmask DP, multi-currency |
| 6 | `DoubleEntryLedger.java` | Multi-leg transactions, zero-sum validation, reversals, reconciliation |
| 7 | `CurrencyExchange.java` | Graph-based exchange, Dijkstra best-path, Bellman-Ford arbitrage detection |
| 8 | `ApiVersionRouter.java` | Stripe-style version transformation chain, merchant pinning |
| 9 | `IdempotencyKeyStore.java` | Per-key locking, fingerprint mismatch detection, TTL cleanup |
| 10 | `SubscriptionProration.java` | Plan change proration, tiered usage billing, invoice generation |

Run any file directly:

```bash
java coding-questions/RateLimiter.java
```

## Design Documents

Detailed writeups following the SACRED framework, serving as reference answers.

| # | Topic | Scale Target |
|---|-------|-------------|
| 001 | High-Volume Streaming Ingestion | 1M events/sec |
| 003 | Distributed Scheduler at Global Scale | 120K jobs/sec |
| 004 | Payment Ledger System | $1T/year, 1.8M entries/sec |
| 005 | Webhook Delivery System | 2-5M deliveries/sec |

Each document covers scope & requirements, API design, capacity estimation, architecture with diagrams, failure modes, and evolution roadmaps.

## SACRED Framework

The evaluation methodology used throughout the project:

- **S**cope — Clarify requirements, define boundaries
- **A**PI — Design external and internal interfaces
- **C**apacity — Back-of-envelope estimation (storage, throughput, bandwidth)
- **R**aw Architecture — High-level component diagram and data flow
- **E**laborate — Deep-dive into critical components and failure modes
- **D**efend & Evolve — Justify tradeoffs, discuss scaling and future evolution
