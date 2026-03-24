# Gold Standard: Real-Time Collaborative Editing System (Google Docs / Word Online)

## 0. Why This Matters

Real-time collaborative editing is one of the **hardest distributed systems problems disguised as a product feature**. It sits at the intersection of distributed consensus, eventual consistency, low-latency networking, and rich-text data modeling. Every tech company building a productivity suite — Google, Microsoft, Notion, Figma, Coda — must solve this.

The core impossibility: **N users concurrently mutating a shared, ordered data structure (a document) with sub-100ms perceived latency, while maintaining convergence guarantees — even across network partitions and offline edits.**

This is not CRUD. This is distributed state machine replication with real-time UX constraints.

---

## 1. Scope & Requirements

### Functional

| Requirement | Detail |
|---|---|
| **Real-time co-editing** | Multiple users edit the same document simultaneously; all see changes within ~100ms |
| **Conflict resolution** | Concurrent edits at the same position resolve deterministically without data loss |
| **Cursor/selection sync** | Each user's cursor position and selection range is visible to all collaborators |
| **Presence awareness** | Show who is currently viewing/editing the document (avatars, colors) |
| **Offline editing** | Users can edit while disconnected; changes merge when reconnected |
| **Version history** | Full revision history with ability to view/restore any prior version |
| **Rich text support** | Bold, italic, headings, lists, tables, images, comments, suggestions |
| **Permissions** | Owner / Editor / Commenter / Viewer roles per document |
| **Comments & suggestions** | Anchored to document ranges; survive edits to surrounding text |

### Non-Functional

| Dimension | Target |
|---|---|
| **Edit latency (local)** | < 16ms (must not block UI rendering at 60fps) |
| **Edit propagation** | < 200ms p99 between collaborators (same region) |
| **Concurrent editors** | Up to 100 per document (Google Docs limit: ~100) |
| **Total documents** | Billions (Google Docs scale) |
| **Document size** | Up to 1.5M characters (~500 pages) |
| **Offline duration** | Hours to days; merge on reconnect |
| **Availability** | 99.99% for document access; 99.9% for real-time sync |
| **Durability** | Zero data loss after server acknowledgment |
| **Consistency** | Strong eventual consistency — all clients converge to identical state |

### Out of Scope
- Spreadsheet formula engine (different computational model)
- Real-time voice/video (separate system)
- Full desktop Office feature parity (e.g., macros, mail merge)

---

## 2. Capacity Estimation

```
Assume Google Docs scale:
- 1B+ documents stored
- 300M monthly active users
- ~50M documents edited per day
- Average 1.5 concurrent editors per active document
- Peak concurrent editing sessions: ~5M

Per edit operation:
- Average operation size: ~100 bytes (insert/delete a few chars + metadata)
- Active typist generates: ~5 ops/sec (burst: 15 ops/sec with fast typing)

Peak operation throughput:
  5M sessions × 0.3 (fraction actively typing) × 5 ops/sec = ~7.5M ops/sec

Bandwidth:
  7.5M ops/sec × 100 bytes = ~750 MB/sec inbound
  Fan-out to collaborators: avg 1.5 peers × 750 MB/sec = ~1.1 GB/sec outbound

Storage:
  Document content: 1B docs × avg 50KB = ~50 PB
  Operation history (30 days): 7.5M ops/sec × 100B × 86400 × 30 ≈ 1.9 PB
  Snapshots/checkpoints: ~10 PB (periodic compaction)

WebSocket connections:
  Peak: ~5M concurrent persistent connections
  Distributed across edge PoPs globally
```

**Key insight**: The throughput challenge is NOT storage — it's **fan-out latency**. Each keystroke must reach all collaborators in <200ms. This is a **real-time messaging problem** on top of a **distributed data structure problem**.

---

## 3. The Core Problem: Concurrent Edit Resolution

This is the section that separates a Senior Staff answer from everyone else. There are two families of algorithms. You must understand both and make a reasoned choice.

### 3a. Operational Transformation (OT)

**Used by**: Google Docs (Jupiter/Wave), Microsoft Word Online (Fluid Framework originally used OT)

**Core idea**: Represent every edit as an **operation** (insert, delete, format). When concurrent operations arrive, **transform** them against each other so that applying them in any order produces the same result.

```
User A: insert('X', pos=3)    ──────►  Server receives A first
User B: insert('Y', pos=1)    ──────►  Server receives B second

Without transform:
  A sees: "abcXdefg" then applies B's insert at pos 1 → "aYbcXdefg" ✓
  B sees: "aYbcdefg" then applies A's insert at pos 3 → "aYbXcdefg" ✗  DIVERGED!

With OT:
  Server transforms B against A:
    B' = insert('Y', pos=1)  (pos < A's pos, no shift needed)
  Server transforms A against B:
    A' = insert('X', pos=4)  (pos shifted right by 1 because B inserted before)

  Both clients converge to: "aYbcXdefg" ✓
```

**The transform function** `T(op_a, op_b) → (op_a', op_b')` such that:
```
apply(apply(state, op_a), op_b') == apply(apply(state, op_b), op_a')
```

**OT Architecture — The Jupiter Protocol (Google's approach)**:

```
                    ┌─────────────────────────┐
                    │     CENTRAL SERVER       │
                    │                          │
                    │  Canonical op log         │
                    │  Server state vector      │
                    │  Transform engine          │
                    │                          │
                    │  For each client:         │
                    │    - client state vector  │
                    │    - pending transform    │
                    │      bridge               │
                    └──────┬──────────┬────────┘
                           │          │
              ┌────────────┘          └────────────┐
              ▼                                    ▼
    ┌──────────────────┐                ┌──────────────────┐
    │   CLIENT A        │                │   CLIENT B        │
    │                   │                │                   │
    │  Local op buffer  │                │  Local op buffer  │
    │  State vector     │                │  State vector     │
    │  Pending queue    │                │  Pending queue    │
    │  Ack tracking     │                │  Ack tracking     │
    └──────────────────┘                └──────────────────┘
```

**OT state diagram per client:**

```
                    ┌──────────────┐
                    │  SYNCHRONIZED │◄──────────────────────────┐
                    │  (no pending) │                            │
                    └──────┬───────┘                            │
                           │ user edits locally                 │
                           ▼                                    │
                ┌─────────────────────┐   server acks    ┌─────┘
                │  AWAITING CONFIRM    │──────────────────┘
                │  (1 op in flight)    │   (no buffer)
                └──────┬──────────────┘
                       │ user edits again
                       ▼
            ┌──────────────────────────┐
            │  AWAITING CONFIRM +       │
            │  BUFFERING               │
            │  (1 in flight + N buffer) │
            └──────────────────────────┘
                       │ server acks in-flight
                       │ send buffer as next op
                       ▼
                (back to AWAITING CONFIRM)
```

**Pros of OT**:
- Battle-tested at Google scale (15+ years in production)
- Server is authoritative → straightforward access control
- Compact wire format (operations are small)
- Well-understood undo/redo semantics

**Cons of OT**:
- Transform functions are **notoriously hard to get right** — combinatorial explosion with rich text (bold + insert + delete + split-paragraph = dozens of transform pairs)
- Requires a **central server per document** (single point of serialization)
- **Does not support true peer-to-peer** — without a central server, OT requires O(n²) transform pairs
- Extending to new operation types requires writing new transform functions

---

### 3b. Conflict-free Replicated Data Types (CRDTs)

**Used by**: Figma, Notion (partially), Apple Notes, Ink & Switch research, Automerge, Yjs

**Core idea**: Design the data structure itself so that concurrent operations **commute by construction** — no transformation needed. Every replica can apply operations in any order and converge.

**How it works — the RGA (Replicated Growable Array) approach**:

Each character gets a globally unique, immutable ID:

```
Logical structure (not what user sees):

  ID: (A,1) → (A,2) → (A,3) → (B,1) → (A,4)
  Char:  H       e       l       X       o
  Tomb:  ✗       ✗       ✗       ✗       ✗

User A types "Hello" → IDs: (A,1), (A,2), (A,3), (A,4), (A,5)
User B inserts 'X' after position 2 → ID: (B,1), parent: (A,3)

Insert rule: new char is placed after its parent.
Tie-breaking: if two inserts have the same parent,
              order by (lamport_timestamp DESC, replica_id DESC)

Delete: mark as tombstone (don't remove — needed for consistency)
```

**CRDT Document Tree (for rich text — Peritext approach)**:

```
Document
├── Paragraph (block-level CRDT)
│   ├── Span: "Hello " [bold: ON@(A,ts=5)]
│   ├── Span: "world"  [bold: OFF@(A,ts=7), italic: ON@(B,ts=3)]
│   └── Span: "!"
├── Paragraph
│   └── Span: "Second paragraph"
└── Image (block-level element)

Formatting is stored as CRDT metadata on character ranges.
Peritext algorithm handles concurrent formatting conflicts:
- Expand/contract semantics for range boundaries
- Last-writer-wins per formatting attribute per character
```

**Pros of CRDTs**:
- **True offline support** — edits merge automatically without server
- **Peer-to-peer capable** — no central server required
- **Mathematically proven convergence** — no edge cases in merge logic
- **Easier to extend** — new operations don't require new transform pairs
- **Scales horizontally** — no single serialization point

**Cons of CRDTs**:
- **Metadata overhead** — each character carries a unique ID + tombstones
  - A 100KB document might use 1-10MB of CRDT metadata in memory
- **Tombstone accumulation** — deleted characters leave markers (garbage collection is hard)
- **Rich text is still an active research area** (Peritext paper: 2021)
- **Undo is harder** — no canonical operation log to reverse
- **Server authority model is less natural** — access control requires additional layers

---

### 3c. Decision: Hybrid Architecture

**For a production system at Google Docs scale, use OT with CRDT-inspired offline support.**

| Aspect | Choice | Rationale |
|---|---|---|
| **Online editing** | OT (Jupiter protocol) | Proven at scale, server-authoritative, compact ops |
| **Offline editing** | CRDT merge on reconnect | OT cannot merge diverged histories spanning hours |
| **Data model** | CRDT-backed document tree | Rich text structure benefits from CRDT's composability |
| **Wire protocol** | OT operations | Smaller payloads, lower bandwidth |

This is exactly what Google Docs does internally: OT for real-time with the server as the single serialization point, but CRDT-like merge semantics for offline/slow-network scenarios.

---

## 4. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLIENTS                                        │
│                                                                             │
│  ┌────────────────┐  ┌────────────────┐  ┌────────────────┐                │
│  │  Browser/App A  │  │  Browser/App B  │  │  Browser/App C  │  ...         │
│  │                 │  │                 │  │                 │               │
│  │ ┌─────────────┐│  │ ┌─────────────┐│  │ ┌─────────────┐│               │
│  │ │ Local Doc    ││  │ │ Local Doc    ││  │ │ Local Doc    ││               │
│  │ │ Model (CRDT) ││  │ │ Model (CRDT) ││  │ │ Model (CRDT) ││               │
│  │ ├─────────────┤│  │ ├─────────────┤│  │ ├─────────────┤│               │
│  │ │ OT Client    ││  │ │ OT Client    ││  │ │ OT Client    ││               │
│  │ │ Engine       ││  │ │ Engine       ││  │ │ Engine       ││               │
│  │ ├─────────────┤│  │ ├─────────────┤│  │ ├─────────────┤│               │
│  │ │ Offline      ││  │ │ Offline      ││  │ │ Offline      ││               │
│  │ │ Queue (IDB)  ││  │ │ Queue (IDB)  ││  │ │ Queue (IDB)  ││               │
│  │ └─────────────┘│  │ └─────────────┘│  │ └─────────────┘│               │
│  └───────┬────────┘  └───────┬────────┘  └───────┬────────┘               │
└──────────┼───────────────────┼───────────────────┼──────────────────────────┘
           │ WebSocket         │ WebSocket         │ WebSocket
           ▼                   ▼                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        EDGE / CONNECTION LAYER                              │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────┐       │
│  │                  WebSocket Gateway Fleet                         │       │
│  │  - TLS termination                                               │       │
│  │  - Auth token validation                                         │       │
│  │  - Session affinity (sticky to collab server for document)       │       │
│  │  - Connection draining on deploy                                 │       │
│  │  - Heartbeat / ping-pong                                         │       │
│  │  Nodes: ~500+ globally (behind Anycast / regional LB)            │       │
│  └──────────────────────────┬──────────────────────────────────────┘       │
└─────────────────────────────┼───────────────────────────────────────────────┘
                              │ gRPC / internal TCP
                              ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                     COLLABORATION SERVICE LAYER                             │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────┐       │
│  │              Collaboration Session Servers                       │       │
│  │                                                                  │       │
│  │  One "active session" per document, hosted on ONE server          │       │
│  │  (consistent hashing or coordination service for assignment)     │       │
│  │                                                                  │       │
│  │  Per document session:                                           │       │
│  │  ┌─────────────────────────────────────────────────┐            │       │
│  │  │ • OT Transform Engine (server-side)              │            │       │
│  │  │ • Canonical operation log (in-memory + WAL)      │            │       │
│  │  │ • Connected client registry                      │            │       │
│  │  │ • Cursor/presence state                          │            │       │
│  │  │ • Revision counter                               │            │       │
│  │  │ • Periodic snapshot trigger                      │            │       │
│  │  └─────────────────────────────────────────────────┘            │       │
│  │                                                                  │       │
│  │  Scale: ~50K servers, each handling ~1000 active documents       │       │
│  └──────────────┬────────────────────────┬─────────────────────────┘       │
│                 │                        │                                   │
│       ┌─────────┘                        └──────────┐                       │
│       ▼                                             ▼                       │
│  ┌──────────────────┐                 ┌──────────────────────────┐          │
│  │ Presence Service  │                 │ Operation Persistence    │          │
│  │ (Redis Cluster)   │                 │ Pipeline                 │          │
│  │                   │                 │                          │          │
│  │ - Cursor positions│                 │ WAL → Kafka → Storage   │          │
│  │ - User colors     │                 │                          │          │
│  │ - Typing status   │                 └────────────┬─────────────┘         │
│  │ - Pub/sub for     │                              │                       │
│  │   cross-server    │                              ▼                       │
│  │   presence        │                 ┌──────────────────────────┐         │
│  └──────────────────┘                 │ Snapshot / Compaction     │         │
│                                        │ Service                   │         │
│                                        │                           │         │
│                                        │ - Periodic full snapshots │         │
│                                        │ - Op log compaction       │         │
│                                        │ - Version history         │         │
│                                        └────────────┬──────────────┘        │
└─────────────────────────────────────────────────────┼───────────────────────┘
                                                      │
                              ┌────────────────────────┼──────────────────┐
                              ▼                        ▼                  ▼
                   ┌──────────────────┐  ┌──────────────────┐  ┌────────────┐
                   │  Document Store   │  │  Operation Log    │  │  Blob Store │
                   │  (Spanner/CDB)    │  │  (Bigtable/DDB)   │  │  (GCS/S3)  │
                   │                   │  │                    │  │            │
                   │  - Doc metadata   │  │  - Full op history │  │  - Images  │
                   │  - Latest snapshot│  │  - Indexed by      │  │  - Embeds  │
                   │  - Permissions    │  │    (doc_id, rev)   │  │  - Exports │
                   │  - Sharing config │  │  - TTL old ops     │  │            │
                   └──────────────────┘  └──────────────────┘  └────────────┘
```

---

## 5. Component Deep Dive

### 5a. Client-Side Architecture

This is where the user experience is made or broken. The client must feel instant — every keystroke must render locally before the network round-trip completes.

```
┌──────────────────────────────────────────────────────────────┐
│                     CLIENT ARCHITECTURE                       │
│                                                              │
│  User Input (keystrokes, formatting, paste)                  │
│       │                                                      │
│       ▼                                                      │
│  ┌────────────────┐                                          │
│  │ Input Handler   │  Converts DOM events → abstract ops     │
│  └───────┬────────┘                                          │
│          │                                                    │
│          ▼                                                    │
│  ┌────────────────┐     ┌─────────────────┐                 │
│  │ Local Document  │◄───│ OT Client Engine │                 │
│  │ Model           │    │                  │                 │
│  │                 │    │ States:          │                 │
│  │ - Apply op      │    │  SYNCED          │                 │
│  │   immediately   │    │  AWAITING_ACK    │                 │
│  │ - Render to DOM │    │  AWAITING_ACK +  │                 │
│  │                 │    │   BUFFERING      │                 │
│  └───────┬────────┘    └──┬──────────────┘                  │
│          │                │                                   │
│          ▼                │    ┌───────────────────┐         │
│  ┌────────────────┐      │    │ Offline Queue      │         │
│  │ Rendering       │      │    │ (IndexedDB)        │         │
│  │ Engine          │      │    │                    │         │
│  │                 │      │    │ - Ops during       │         │
│  │ Virtual DOM /   │      │    │   disconnect       │         │
│  │ ContentEditable │      │    │ - CRDT merge on    │         │
│  │ reconciliation  │      │    │   reconnect        │         │
│  └────────────────┘      │    └───────────────────┘         │
│                           │                                   │
│                           ▼                                   │
│                    ┌─────────────┐                            │
│                    │ WebSocket    │                            │
│                    │ Connection   │                            │
│                    │              │                            │
│                    │ - Send ops   │                            │
│                    │ - Receive    │                            │
│                    │   acks +     │                            │
│                    │   remote ops │                            │
│                    │ - Presence   │                            │
│                    └─────────────┘                            │
└──────────────────────────────────────────────────────────────┘
```

**Critical client-side principle: Optimistic local apply.**

```
1. User types 'A'
2. Client IMMEDIATELY applies to local model + renders (< 16ms)
3. Client sends op to server
4. Server transforms, broadcasts, acks
5. Client receives ack — op is now committed
   OR
   Client receives remote ops — transforms local pending ops against them
```

The user **never waits** for the server. This is what makes it feel real-time.

### 5b. Collaboration Session Server (The Brain)

Each active document has exactly ONE session server that acts as the **serialization point**. This is the OT server.

```python
class CollaborationSession:
    def __init__(self, doc_id):
        self.doc_id = doc_id
        self.revision = 0               # Monotonic counter
        self.op_log = []                 # In-memory recent ops (ring buffer)
        self.clients = {}                # client_id → ClientState
        self.document_state = None       # Latest doc snapshot (lazy loaded)

    def handle_client_op(self, client_id, client_op, client_revision):
        """
        Core OT server loop:
        1. Client sends (op, revision) where revision = last server rev client saw
        2. Server transforms op against all ops between client_revision and current
        3. Server applies transformed op, increments revision
        4. Server broadcasts transformed op to all OTHER clients
        5. Server sends ACK to originating client
        """
        # Step 1: Get ops the client hasn't seen
        missed_ops = self.op_log[client_revision:]

        # Step 2: Transform client's op against each missed op
        transformed_op = client_op
        for server_op in missed_ops:
            transformed_op, _ = transform(transformed_op, server_op)

        # Step 3: Apply to canonical state
        self.revision += 1
        self.op_log.append(transformed_op)
        self.persist_op(transformed_op, self.revision)  # Async WAL write

        # Step 4: Broadcast to other clients
        for cid, client_state in self.clients.items():
            if cid != client_id:
                # Transform against client's pending ops if needed
                self.send_op(cid, transformed_op, self.revision)

        # Step 5: ACK originating client
        self.send_ack(client_id, self.revision)
```

**Session assignment**: Use a coordination service (ZooKeeper / etcd) to assign documents to session servers.

```
doc_id → hash(doc_id) mod N → session_server_shard

With consistent hashing:
- Adding/removing servers only migrates ~1/N documents
- Session state is reconstructed from op log on failover
```

**Failover**:
```
1. Session server dies
2. Coordination service detects via heartbeat timeout (~3s)
3. New server assigned for doc_id
4. New server loads latest snapshot + replays ops from persistent log
5. Clients reconnect (WebSocket gateway routes to new server)
6. Clients re-sync from their last known server revision
```

### 5c. Operation Types and Transform Functions

```
OperationType = Insert | Delete | Retain | Format

Operation = {
    type: OperationType,
    position: number,          # For positional ops
    content: string,           # For insert
    length: number,            # For delete/retain
    attributes: Map<str, any>, # For format (bold, italic, etc.)
    author: string,
    timestamp: number,
    revision: number           # Server revision client is based on
}
```

**Transform matrix** (the hard part):

```
                    Insert          Delete          Format
         ┌──────────────────┬──────────────────┬──────────────────┐
Insert   │ Tie-break by     │ Shift delete pos │ Split format     │
         │ author_id if     │ if after insert  │ range around     │
         │ same position    │                  │ insert point     │
         ├──────────────────┼──────────────────┼──────────────────┤
Delete   │ Shift insert pos │ Handle overlap:  │ Shrink format    │
         │ if after delete  │ - Identical: noop│ range by deleted │
         │                  │ - Overlap: split │ characters       │
         ├──────────────────┼──────────────────┼──────────────────┤
Format   │ Extend format    │ Shrink format    │ LWW on same attr │
         │ range if insert  │ range by deleted │ Merge different  │
         │ is within range  │ characters       │ attrs             │
         └──────────────────┴──────────────────┴──────────────────┘
```

**In practice, use a delta-based representation** (like Quill's Delta or Google's operational model):

```json
// "Hello World" → User A bolds "Hello", User B inserts "Beautiful " before "World"

// User A's op (based on rev 5):
{ "ops": [
    { "retain": 5, "attributes": { "bold": true } },  // Bold "Hello"
    { "retain": 6 }                                      // Skip " World"
]}

// User B's op (based on rev 5):
{ "ops": [
    { "retain": 6 },             // Skip "Hello "
    { "insert": "Beautiful " }   // Insert before "World"
]}

// Transformed result (both applied):
// "**Hello** Beautiful World"
```

---

## 6. Cursor & Presence Synchronization

Presence is lower priority than document ops — it's acceptable to have slightly stale cursor positions. This allows us to use a separate, more relaxed consistency channel.

### Architecture

```
┌──────────┐    presence updates     ┌────────────────────┐
│ Client A  │ ─────(every 50ms)────► │                    │
│           │                        │  Presence Service   │
│ Client B  │ ◄──────────────────── │  (Redis Pub/Sub)    │
│           │    fan-out updates     │                    │
│ Client C  │ ◄──────────────────── │  Key: doc:{id}:     │
│           │                        │    presence         │
└──────────┘                        └────────────────────┘
```

### Presence Message Format

```json
{
  "user_id": "u_abc123",
  "display_name": "Alice",
  "avatar_url": "...",
  "color": "#4285f4",
  "cursor": {
    "index": 142,
    "length": 0              // 0 = caret, >0 = selection
  },
  "anchor_context": {
    "before": "the quick ",   // For position recovery after transforms
    "after": "brown fox"
  },
  "last_active": 1703001234567,
  "status": "editing"         // editing | viewing | idle
}
```

### Cursor Position Transform

When document operations arrive, cursor positions for all remote users must be adjusted:

```
transformCursor(cursor_pos, op):
  if op.type == INSERT:
    if op.position <= cursor_pos:
      cursor_pos += op.content.length
  elif op.type == DELETE:
    if op.position < cursor_pos:
      cursor_pos -= min(op.length, cursor_pos - op.position)
  return cursor_pos
```

### Throttling Strategy

```
- Cursor updates: throttle to max 20/sec per client (50ms debounce)
- Presence heartbeat: every 30 seconds
- Stale timeout: remove presence after 60 seconds of no heartbeat
- Transmission: piggyback on document op messages when possible
- Rendering: interpolate cursor movement client-side for smoothness
```

---

## 7. Offline Editing & Conflict Resolution

This is where the CRDT component becomes essential. OT alone cannot handle hours-long divergence.

### Offline Strategy

```
┌──────────────────────────────────────────────────────────────┐
│                    OFFLINE FLOW                               │
│                                                              │
│  1. Connection lost detected (WebSocket close/timeout)        │
│                                                              │
│  2. Client switches to OFFLINE mode:                         │
│     - Ops continue to be generated from user edits           │
│     - Ops are stored in IndexedDB with local timestamps      │
│     - Local document model continues to update               │
│     - UI shows "offline" indicator                           │
│                                                              │
│  3. Periodically attempt reconnection (exponential backoff)   │
│                                                              │
│  4. On reconnection:                                         │
│     a. Client sends its last known server revision            │
│     b. Server sends all ops since that revision               │
│     c. MERGE PHASE:                                          │
│        - If divergence is small (< 1000 ops):                │
│            → Use OT: transform local ops against server ops   │
│        - If divergence is large (offline for hours):          │
│            → Use CRDT merge: treat both branches as CRDT      │
│              states, compute a merged document                │
│            → Generate a diff between merged result and        │
│              server state                                     │
│            → Send diff as a single compound operation         │
│     d. Server applies merged ops, broadcasts to others        │
│     e. Client receives ACK, switches to ONLINE mode          │
│                                                              │
│  5. Clear offline queue from IndexedDB                        │
└──────────────────────────────────────────────────────────────┘
```

### Merge Algorithm (CRDT-based for large divergence)

```
merge(server_doc, local_doc, common_ancestor):
    // Three-way merge using CRDT semantics

    server_changes = diff(common_ancestor, server_doc)
    local_changes  = diff(common_ancestor, local_doc)

    for each conflicting region:
        if changes are to different ranges → apply both
        if changes are to same range:
            - Both insert at same position → interleave by author_id order
            - One inserts, one deletes → keep insertion, apply delete
              to non-inserted text
            - Both delete same text → idempotent (no conflict)
            - Conflicting formats → last-writer-wins by timestamp

    return merged_document
```

---

## 8. Storage & Persistence

### Operation Log (Bigtable / DynamoDB)

```
Table: operation_log

Row key: {doc_id}#{revision:zero-padded-20-digit}

Columns:
  - op_data:       serialized operation (protobuf, ~100 bytes)
  - author_id:     who made the edit
  - timestamp:     server timestamp
  - client_rev:    client's base revision
  - session_id:    which collab session processed this

Access patterns:
  - Write: append-only (high throughput, sequential keys per doc)
  - Read:  range scan from revision N to latest (for client catch-up)
  - Read:  full scan (for snapshot rebuilding)
```

### Document Snapshots (Spanner / CockroachDB)

```
Table: document_snapshots

Primary key: (doc_id, snapshot_revision)

Columns:
  - content:          full document state (compressed protobuf)
  - revision:         the revision this snapshot represents
  - created_at:       timestamp
  - size_bytes:       for quota tracking
  - checksum:         integrity verification

Snapshot frequency:
  - Every 1000 operations OR every 5 minutes of activity
  - Always on session close (all editors leave)

Compaction:
  - Keep snapshots at: latest, every 1000th, every 10000th
  - Discard intermediate snapshots after 30 days
```

### Version History

```
For user-facing "version history" (like Google Docs):
  - Aggregate ops into "sessions" (group by author + time gap < 5 min)
  - Store named checkpoints that users can title
  - Auto-create checkpoints at significant events:
    - Major paste operations
    - Bulk formatting changes
    - After 30+ minutes of continuous editing

Storage: reuse snapshot infrastructure with additional metadata:

Table: version_history
  doc_id | version_id | snapshot_revision | author | title | created_at
```

---

## 9. Security & Access Control

### Permission Model

```
Document permissions (stored in document metadata):

OWNER    → Full control: edit, share, delete, transfer ownership
EDITOR   → Edit document content, add comments
COMMENTER → Add comments only (no content edits)
VIEWER   → Read-only access

Enforcement points:
1. API Gateway  → validates auth token, checks permission on connect
2. Collab Server → re-validates on every operation
                   (defense in depth — client could be compromised)
3. Storage Layer → row-level security for direct DB access

Operation filtering:
  - VIEWER receives ops (to see real-time changes) but cannot send
  - COMMENTER can send comment ops but not content ops
  - Server rejects unauthorized ops and logs violations
```

### Wire Security

```
- All WebSocket connections over TLS 1.3
- Auth via short-lived JWT (rotated every 15 min via refresh token)
- Document access tokens scoped to specific doc_id
- Rate limiting: max 100 ops/sec per client (prevents abuse/flooding)
- Operation size limit: 1MB per op (prevents memory attacks)
- Document size limit: 10MB content (enforced server-side)
```

---

## 10. Scaling & Performance Considerations

### Hot Documents Problem

A viral document (company all-hands notes, breaking news collaboration) might have 100+ simultaneous editors, generating 500+ ops/sec on a single session server.

```
Mitigation strategies:

1. Vertical scaling of session server
   - Collab session is CPU-bound (transform computation)
   - Use high-core-count instances for hot documents
   - Monitor ops/sec per session, trigger migration if threshold exceeded

2. Operation batching
   - Client batches rapid keystrokes into compound ops (every 50ms)
   - Reduces server transforms from 15/sec to 3/sec per client
   - No perceivable latency impact

3. Transform optimization
   - Cache transform results for identical op patterns
   - Use optimized C++/Rust transform engine (not interpreted JS)
   - Pre-allocate position index structures for O(log n) transforms

4. Read-only fan-out
   - Viewers don't send ops, only receive
   - Use a separate broadcast channel (pub/sub) for viewers
   - Session server only handles editors; viewers get a delayed stream

   ┌──────────────┐     ops      ┌───────────────┐    broadcast    ┌────────────┐
   │ Editors (100) │────────────►│ Session Server │───────────────►│ Fan-out    │
   │               │◄────────────│               │                │ Service    │
   └──────────────┘    acks      └───────────────┘                └─────┬──────┘
                                                                        │
                                                              ┌─────────┼─────────┐
                                                              ▼         ▼         ▼
                                                          Viewer 1  Viewer 2  Viewer N
                                                          (10,000+)
```

### Session Server Failover

```
Time budget: < 5 seconds total disruption

T+0s:     Session server crashes
T+0-3s:   Coordination service detects heartbeat failure
T+3s:     New server assigned via consistent hash ring
T+3-4s:   New server loads latest snapshot from storage
T+4-5s:   New server replays ops from op log since snapshot
T+5s:     New server ready; WebSocket gateway routes new connections
T+5s:     Clients reconnect, send their last revision
T+5-6s:   Catch-up sync completes; editing resumes

Data loss window: ops between last WAL flush and crash
Mitigation: WAL fsync every 100ms; clients can re-send unacked ops
```

### Global Latency

```
Problem: Users in Tokyo and NYC editing the same document.
         Round-trip: ~200ms. OT requires server round-trip for convergence.

Solution: Regional edge + single authoritative server

┌────────────┐      ┌──────────────┐      ┌─────────────────┐
│ Tokyo User  │─30ms─│ Tokyo Edge    │─90ms─│ Session Server   │
│             │      │ WS Gateway    │      │ (us-central1)    │
└────────────┘      └──────────────┘      └─────────────────┘

┌────────────┐      ┌──────────────┐
│ NYC User    │─10ms─│ NYC Edge      │─30ms─┘
│             │      │ WS Gateway    │
└────────────┘      └──────────────┘

Total latency (Tokyo): ~240ms round-trip
Total latency (NYC):   ~80ms round-trip

Acceptable because:
1. Local apply is instant (user doesn't wait)
2. Cursor sync can tolerate 200ms staleness
3. Convergence still guaranteed by OT

Optimization for extreme cases:
- Place session server in region of majority editors
- Migrate session server if editor distribution shifts
- Use edge prediction/speculation for common patterns
```

---

## 11. Data Model Summary

```
┌─────────────────────────────────────────────────────────────┐
│                   DATA MODEL OVERVIEW                        │
│                                                             │
│  Document (Spanner)                                         │
│  ├── doc_id: UUID                                           │
│  ├── title: string                                          │
│  ├── owner_id: UUID                                         │
│  ├── created_at: timestamp                                  │
│  ├── updated_at: timestamp                                  │
│  ├── current_revision: int64                                │
│  ├── latest_snapshot_revision: int64                        │
│  └── settings: JSON (locale, default_style, etc.)           │
│                                                             │
│  DocumentPermission (Spanner, interleaved in Document)      │
│  ├── doc_id: UUID                                           │
│  ├── principal_id: UUID (user or group)                     │
│  ├── role: OWNER | EDITOR | COMMENTER | VIEWER              │
│  └── granted_at: timestamp                                  │
│                                                             │
│  OperationLog (Bigtable)                                    │
│  ├── row_key: "{doc_id}#{revision:020d}"                    │
│  ├── op_data: bytes (protobuf)                              │
│  ├── author_id: UUID                                        │
│  ├── timestamp: int64                                       │
│  └── session_id: UUID                                       │
│                                                             │
│  Snapshot (Bigtable + GCS for large docs)                   │
│  ├── row_key: "{doc_id}#{snapshot_revision:020d}"           │
│  ├── content: bytes (compressed protobuf)                   │
│  ├── revision: int64                                        │
│  └── checksum: bytes                                        │
│                                                             │
│  Presence (Redis — ephemeral)                               │
│  ├── key: "presence:{doc_id}:{user_id}"                     │
│  ├── cursor_index: int                                      │
│  ├── selection_length: int                                  │
│  ├── color: string                                          │
│  └── ttl: 60s                                               │
│                                                             │
│  Comment (Spanner)                                          │
│  ├── comment_id: UUID                                       │
│  ├── doc_id: UUID                                           │
│  ├── anchor_revision: int64                                 │
│  ├── anchor_start: int                                      │
│  ├── anchor_end: int                                        │
│  ├── content: string                                        │
│  ├── author_id: UUID                                        │
│  ├── created_at: timestamp                                  │
│  └── resolved: bool                                         │
│                                                             │
│  Note: Comment anchors are transformed alongside document   │
│  ops to stay attached to the correct text range as the      │
│  document evolves.                                          │
└─────────────────────────────────────────────────────────────┘
```

---

## 12. API Design

### WebSocket Protocol

```
// Connection handshake
CONNECT wss://docs.example.com/ws/doc/{doc_id}
Headers:
  Authorization: Bearer {jwt_token}
  X-Client-Id: {client_uuid}

// Server → Client: Initial sync
{
  "type": "doc_init",
  "snapshot": { ... },           // Full document state
  "revision": 48291,             // Current server revision
  "collaborators": [             // Current presence list
    { "user_id": "...", "name": "Alice", "color": "#4285f4", "cursor": {...} }
  ]
}

// Client → Server: Send operation
{
  "type": "op",
  "client_id": "c_abc123",
  "revision": 48291,            // Last server revision client has seen
  "ops": [
    { "retain": 10 },
    { "insert": "Hello", "attributes": { "bold": true } },
    { "retain": 50 }
  ]
}

// Server → Client (originator): Acknowledgment
{
  "type": "ack",
  "revision": 48292              // New server revision for this op
}

// Server → Client (others): Remote operation
{
  "type": "remote_op",
  "author": { "user_id": "u_xyz", "name": "Bob", "color": "#ea4335" },
  "revision": 48292,
  "ops": [
    { "retain": 10 },
    { "insert": "Hello", "attributes": { "bold": true } },
    { "retain": 50 }
  ]
}

// Bidirectional: Presence updates
{
  "type": "presence",
  "user_id": "u_abc",
  "cursor": { "index": 42, "length": 10 },
  "status": "editing"
}
```

### REST APIs (for non-real-time operations)

```
# Document CRUD
POST   /api/v1/documents                          → Create document
GET    /api/v1/documents/{doc_id}                  → Get document metadata
DELETE /api/v1/documents/{doc_id}                  → Delete document

# Sharing & Permissions
POST   /api/v1/documents/{doc_id}/share            → Share with user/group
PATCH  /api/v1/documents/{doc_id}/permissions/{id}  → Update permission
DELETE /api/v1/documents/{doc_id}/permissions/{id}  → Revoke access

# Version History
GET    /api/v1/documents/{doc_id}/versions          → List versions
GET    /api/v1/documents/{doc_id}/versions/{ver_id}  → Get specific version
POST   /api/v1/documents/{doc_id}/versions/{ver_id}/restore → Restore version

# Comments
POST   /api/v1/documents/{doc_id}/comments          → Create comment
PATCH  /api/v1/documents/{doc_id}/comments/{id}      → Edit/resolve comment
DELETE /api/v1/documents/{doc_id}/comments/{id}      → Delete comment

# Export
GET    /api/v1/documents/{doc_id}/export?format=pdf|docx|md  → Export document
```

---

## 13. Observability & Monitoring

```
Key metrics to track:

REAL-TIME HEALTH:
  - op_propagation_latency_p99:  < 200ms (ALERT if > 500ms)
  - transform_computation_ms:    < 5ms per op (ALERT if > 50ms)
  - websocket_connection_count:  per gateway, per region
  - active_sessions_per_server:  target < 1000 (ALERT if > 2000)
  - ops_per_second_per_document: track hot documents
  - unacked_ops_per_client:      > 50 indicates stalled client

CONVERGENCE:
  - checksum_mismatch_rate:      MUST be 0% (CRITICAL ALERT if > 0)
    (Periodically, clients report document checksum at revision N.
     If any client disagrees → convergence bug → highest severity incident)

STORAGE:
  - op_log_write_latency_p99:    < 10ms
  - snapshot_creation_latency:   < 2s for 99th percentile doc size
  - op_log_size_per_document:    alert if > 1M ops without compaction

AVAILABILITY:
  - session_failover_duration:   < 5s (ALERT if > 10s)
  - client_reconnection_rate:    spike indicates infrastructure issue
  - offline_merge_conflict_rate: track for UX improvement
```

---

## 14. Trade-off Summary & Why Not Alternatives

| Decision | Chosen | Alternative | Why |
|---|---|---|---|
| **Concurrency model** | OT (online) + CRDT (offline) | Pure CRDT | OT is proven at Google scale; CRDT metadata overhead is high for large docs; hybrid gets best of both |
| **Session topology** | Single server per doc | Multi-leader per doc | Simplicity; single serialization point eliminates distributed OT complexity; acceptable because local apply hides latency |
| **Connection protocol** | WebSocket | SSE + HTTP POST | Bidirectional needed; WebSocket is lower overhead than two separate channels |
| **Presence store** | Redis Pub/Sub | Piggyback on doc ops | Decoupling prevents presence storms from affecting document consistency |
| **Op log store** | Bigtable | Kafka | Need indexed lookups by (doc_id, revision range); Kafka is append-only without efficient seek |
| **Snapshot store** | Spanner + GCS | Just Bigtable | Spanner for transactional metadata (permissions, sharing); GCS for large binary snapshots |
| **Offline merge** | CRDT 3-way merge | OT replay | OT cannot efficiently transform thousands of diverged ops; CRDT merge is O(n) in changes |

---

## 15. Evolution Path

```
Phase 1: MVP
  - Single-region deployment
  - Text-only editing with basic formatting (bold, italic, headers)
  - OT with up to 10 concurrent editors
  - No offline support (reconnect = reload)
  - PostgreSQL for everything

Phase 2: Scale
  - Multi-region edge gateways
  - Full rich text (tables, images, embeds)
  - Bigtable for op log, Spanner for metadata
  - 100 concurrent editors
  - Basic offline (queue ops, replay on reconnect)

Phase 3: Enterprise
  - Full offline CRDT merge
  - Suggestion mode (track changes)
  - Real-time comments with anchoring
  - Version history with named checkpoints
  - Compliance: audit logs, data residency

Phase 4: Platform
  - Collaborative editing SDK (embed in other apps)
  - Custom document schemas (beyond rich text)
  - Plugin/extension system
  - AI-assisted editing (suggestions, summarization)
```

---

## 16. Interview Navigation Guide

**If interviewer asks "Why not pure CRDT?":**
> CRDTs have metadata overhead (each character needs a unique ID + tombstones). For a 100KB document, CRDT state can be 1-10MB. At billions of documents, this is significant. OT with a central server is more compact and battle-tested. However, we use CRDT semantics for offline merge where OT breaks down.

**If interviewer asks "How do you handle undo?":**
> Undo in collaborative editing is non-trivial. We use "undo transformation" — when user A undoes their last op, we don't revert the document state. Instead, we compute the *inverse* of A's op, transform it against all subsequent ops from other users, and apply it as a new forward operation. This preserves everyone else's work.

**If interviewer asks "What about intent preservation?":**
> OT's core challenge. Example: User A types "cat" at position 5. User B deletes the word at position 5. After transform, should A's "cat" survive? Yes — because A's *intent* was to insert, not to replace. Our transform functions preserve insertion intent even when the surrounding context is deleted. This is codified in the transform rules.

**If interviewer asks "How do comments survive edits?":**
> Comments are anchored to a (start_revision, start_pos, end_pos) tuple. As operations are applied, we transform the anchor positions using the same transform functions used for cursor positions. If the anchored text is entirely deleted, the comment becomes "orphaned" and is shown in a sidebar without anchor.

**If interviewer asks "CAP theorem implications?":**
> We choose AP for the editing experience (clients apply locally without waiting for server — availability over consistency). But we guarantee eventual consistency through OT convergence. The session server is a CP component (single serialization point). If the session server is unavailable, clients buffer locally (A) and merge later. We never block the user from typing.
