# ADR 010: Single-Replica Scaling Strategy

## Status

**Accepted** - February 2026

## Context

Each microservice in the framework currently runs as a single replica. The dual-instance Hazelcast architecture (ADR 008) gives each pod an **isolated embedded Hazelcast instance** for Jet pipeline processing and a **client connection** to the shared cluster for cross-service communication.

This raises two questions:

1. **What throughput can a single replica handle?**
2. **Can we scale to multiple replicas if needed?**

### Current Completion Flow

The `EventSourcingController` uses a `ConcurrentHashMap<String, PendingCompletion>` to track in-flight events:

1. HTTP request arrives → `handleEvent()` writes to the local `_PENDING` IMap and stores a `CompletableFuture` in `pendingCompletions`
2. The Jet pipeline (on the same embedded Hazelcast) processes the event through 6 stages
3. Stage 6 writes to the local `_COMPLETIONS` IMap
4. An `EntryAddedListener` on `_COMPLETIONS` resolves the `CompletableFuture` and republishes the event to the shared cluster via the outbox

All steps execute on the same JVM. The `ConcurrentHashMap` is the correct choice — it only holds in-flight requests (typically <100 entries) and requires no cross-pod coordination. Using a distributed `IMap` would add network overhead for zero benefit.

## Decision

### Single replica per service is the supported deployment model

Until PostgreSQL persistence is implemented, each service runs as exactly one replica. The Helm charts set `replicaCount: 1` and HPA is disabled by default.

### Why multiple replicas break today

If order-service were scaled to 2 replicas, each pod would have completely independent maps:

| Map | Pod A | Pod B |
|-----|-------|-------|
| `Order_PENDING` | Pod A's events only | Pod B's events only |
| `Order_ES` | Pod A's event history | Pod B's event history |
| `Order_VIEW` | Pod A's materialized view | Pod B's materialized view |
| `Order_COMPLETIONS` | Pod A's completions | Pod B's completions |

Consequences:
- An order created on Pod A is invisible to Pod B
- A GET request hitting Pod B returns 404 for Pod A's orders
- Views diverge permanently — each pod holds a partial, inconsistent view
- No mechanism exists to merge or reconcile the split state

This is a direct consequence of ADR 008's isolated embedded instances, which exist to prevent Jet lambda serialization failures across service boundaries.

## Single-Node Capacity

With a 1Gi memory limit (~500-600MB usable heap after Spring Boot, embedded Hazelcast, and Jet baseline):

| Metric | Estimate |
|--------|----------|
| GenericRecord event size | ~0.5-2 KB |
| View entry size | ~1-2 KB |
| Event store + view capacity | ~200K-500K entries |
| At demo load (~0.5 TPS) | Days of continuous operation |
| At moderate load (~50 TPS) | 1-3 hours before memory pressure |
| At high load (~500 TPS) | Minutes without eviction |

**CPU is not the bottleneck.** Hazelcast Jet is extremely efficient for single-node streaming. The constraint is **unbounded IMap growth** in the event store and view maps.

Verified by load test (Phase 3 Day 19): 5 minutes at ~2 req/s (mix of reads and writes) with 0 errors, 0 OOM kills, 0 restarts on 1Gi pods.

## Path to Horizontal Scaling

PostgreSQL persistence is the key enabler. When implemented, the following multi-replica architecture becomes possible:

### Phase 1: PostgreSQL as Durable Event Store

1. **`EventStorePersistence` interface** (already planned) writes events to PostgreSQL
2. **IMap eviction policies** keep each pod's in-memory maps bounded (max-size or TTL with LRU)
3. PostgreSQL becomes the source of truth; Hazelcast IMaps become a bounded hot cache
4. Pod restarts no longer lose data — the `rebuildView()` method replays from PostgreSQL

### Phase 2: Multi-Replica with Sticky Routing

1. **Sticky sessions** route all requests for a given entity key to the same pod (e.g., consistent hash on `customerId` or `orderId` at the API Gateway)
2. Each pod processes a partition of the keyspace, avoiding split-brain views
3. Writes are local; reads hit the pod that owns the key
4. Failover: if a pod dies, its keys are re-routed; the new pod rebuilds views from PostgreSQL

### Phase 3: CQRS Read Replicas (Optional)

1. **Write pods** process events via Jet pipeline and persist to PostgreSQL
2. **Read-only pods** maintain views by tailing the PostgreSQL event log (CDC or polling)
3. Reads scale independently of writes
4. Eventual consistency between write and read replicas (typically <100ms)

### Alternatives Considered for Multi-Replica

| Approach | Why Deferred |
|----------|-------------|
| Join embedded instances into a per-service cluster | Jet distributes jobs across members — increases memory footprint with no benefit for single-domain processing |
| Shared IMap for event store and views | Requires all pods to join the shared cluster — violates ADR 008 |
| Redis or external cache for views | Adds operational complexity; PostgreSQL + local cache achieves the same goal |
| CRDTs for view merge | Over-engineered for the current use case; materialized views are derived state, not user-editable |

## Consequences

### Positive

- **Simple operational model**: one pod per service, no split-brain concerns
- **Predictable performance**: all processing is JVM-local, no network hops in the pipeline
- **Sufficient for demo and moderate production**: single node handles hundreds of TPS
- **Clear upgrade path**: PostgreSQL persistence unlocks multi-replica without redesigning the pipeline

### Negative

- **No horizontal scaling** for a single service until PostgreSQL persistence is implemented
- **Memory-bound**: long-running demos or sustained high throughput require IMap eviction (tracked as a future task alongside PostgreSQL work)
- **Single point of failure**: if the pod dies, in-memory state is lost (mitigated by pod restart + future PostgreSQL replay)

### Neutral

- `ConcurrentHashMap` for `pendingCompletions` remains correct regardless of scaling model — it tracks in-flight requests local to the pod that accepted them
- The shared Hazelcast cluster (cross-service ITopic, saga state) already scales independently via its StatefulSet replica count

## Related Decisions

- **ADR 004**: Six-stage pipeline design (Jet pipeline architecture)
- **ADR 008**: Dual-instance Hazelcast architecture (why embedded instances are isolated)
- **ADR 005**: Community Edition default (no Enterprise-only scaling features required)
