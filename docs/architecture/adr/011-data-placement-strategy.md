# ADR 011: Data Placement Strategy — Local, Remote, or Hybrid

## Status

**Proposed** — February 2026

Supersedes aspects of: ADR 008 (Dual-Instance Hazelcast Architecture), ADR 010 (Single-Replica Scaling Strategy)

## Context

ADR 008 established the dual-instance architecture: an embedded standalone Hazelcast instance for local Jet pipeline processing, and a client connection to a shared 3-node cluster for cross-service communication (ITopic, saga state). All domain-specific maps (`_PENDING`, `_ES`, `_VIEW`, `_COMPLETIONS`) live on the embedded instance; only cross-service coordination data lives on the shared cluster.

This architecture solved the Jet lambda serialization problem but created two consequences identified in ADR 010:

1. **No horizontal scaling**: Each service pod holds an independent, isolated copy of its domain data. Scaling to multiple replicas produces split-brain views.
2. **No data resilience**: If a service pod dies, all in-memory event store and materialized view data is lost. Recovery requires replaying from an external persistence layer (PostgreSQL — not yet implemented).

The question: **Can we improve data resilience and unlock horizontal scaling by moving some or all service maps to the shared cluster, while keeping Jet compute local?**

### Current Data Placement

| Map | Location | Purpose | Access Pattern |
|-----|----------|---------|----------------|
| `{Domain}_PENDING` | Embedded | Pipeline trigger (event journal) | Write once, read by Jet source |
| `{Domain}_ES` | Embedded | Append-only event history | Write in pipeline, read for replay |
| `{Domain}_VIEW` | Embedded | Materialized query projections | Write in pipeline, read by REST API |
| `{Domain}_COMPLETIONS` | Embedded | Pipeline completion tracking | Write in pipeline, read by listener |
| saga-state | Shared cluster | Cross-service saga coordination | Read/write by saga listeners |
| ITopic events | Shared cluster | Cross-service event pub/sub | Publish after pipeline, subscribe in listeners |
| idempotency | Shared cluster | Duplicate event detection | Read/write by saga listeners |
| DLQ | Shared cluster | Failed event storage | Write on failure, read by admin API |

### Technical Constraint: Jet ServiceFactory

The pipeline's intermediate stages (persist to EventStore, update ViewStore) use `ServiceFactories.sharedService()`. The factory function receives a `ProcessorSupplier.Context` whose `hazelcastInstance()` always returns the **local embedded instance**. To access remote maps, the ServiceFactory must create its own `HazelcastClient` connection using a serializable `ClientConfig`.

Jet does provide `Sources.remoteMapJournal()` and `Sinks.remoteMap()` that accept `ClientConfig` for sources and sinks, but intermediate stages require the ServiceFactory approach.

### Why Not Lite Members?

An alternative to remote sources/sinks is having services join the shared cluster as **lite members** (no data ownership, compute only). However:

- Lite members participate in Jet job execution by default in Hazelcast 5.x
- A Jet job submitted from order-service could execute on inventory-service's lite member
- That member lacks `OrderViewUpdater.class` → same `ClassCastException` as ADR 008
- No `JobConfig` API exists to restrict execution to a single member
- This reintroduces the exact problem ADR 008 was designed to solve

---

## Options Evaluated

### Option A: Current Architecture (Data-Local) — Status Quo

All domain maps on the embedded standalone instance. Shared cluster holds only cross-service coordination data.

```
┌─────────────────────────────────────┐
│          Order Service Pod          │
│                                     │
│  ┌───────────────────────────────┐  │
│  │   Embedded Hazelcast (local)  │  │
│  │   Order_PENDING  (journal)    │  │
│  │   Order_ES       (events)     │  │
│  │   Order_VIEW     (projections)│  │
│  │   Order_COMPLETIONS           │  │
│  │   Jet Pipeline ← all local    │  │
│  └───────────────────────────────┘  │
│                                     │
│  ┌───────────────────────────────┐  │
│  │   HZ Client → shared cluster  │──┼──→ ITopic, saga-state, DLQ
│  │   (republish after pipeline)   │  │
│  └───────────────────────────────┘  │
└─────────────────────────────────────┘
```

**Pipeline stages**: All local IMap operations (sub-millisecond).

**Resilience**: Data lost on pod restart. No horizontal scaling.

**Complexity**: Low. Current working implementation.

---

### Option B: Full-Remote — All Maps on Shared Cluster

Move every domain map to the shared 3-node cluster. Services become pure compute nodes.

```
┌─────────────────────────────────────┐
│          Order Service Pod          │
│                                     │
│  ┌───────────────────────────────┐  │
│  │   Embedded Hazelcast (empty)  │  │
│  │   Jet Pipeline                │  │
│  │     Source: remoteMapJournal   │──┼──┐
│  │     Persist: remote client    │──┼──┤
│  │     View: remote client       │──┼──┤
│  │     Publish: remote ITopic    │──┼──┤
│  │     Complete: remoteMap sink  │──┼──┤
│  └───────────────────────────────┘  │  │
└─────────────────────────────────────┘  │
                                         │
    ┌────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│       Shared Cluster (3 nodes)          │
│  Order_PENDING, Order_ES, Order_VIEW    │
│  Product_PENDING, Product_ES, ...       │
│  ITopic, saga-state, DLQ, idempotency   │
│  ALL data centralized                   │
└─────────────────────────────────────────┘
```

**Pipeline stages**: Every IMap read/write is a network round-trip (~1-2ms each). Six stages × network hop = significant throughput reduction.

**Implementation changes required**:

1. `Sources.mapJournal()` → `Sources.remoteMapJournal(mapName, clientConfig, ...)`
2. `EventStoreServiceCreator` → creates `HazelcastClient` from `ClientConfig`, manages lifecycle
3. `ViewUpdaterServiceCreator` → same pattern
4. `EventTopicPublisherServiceCreator` → publishes to remote ITopic (eliminates republish step)
5. `Sinks.map()` → `Sinks.remoteMap(mapName, clientConfig)`
6. `EventSourcingController` → pending map writes go to shared cluster; completion listener on shared cluster map
7. All REST API reads (view queries) go through network to shared cluster
8. Event journal configuration already exists on shared cluster (`hazelcast.yaml`)

**Resilience**: Full — data survives pod restart, cluster tolerates 1 node failure.

**Scaling**: Multiple service replicas can coexist since all share the same cluster-hosted maps.

**Performance impact**: The 100K+ ops/sec materialized view benchmark would drop to network-limited throughput (estimated 10-30K ops/sec). Every pipeline stage adds ~1-2ms of network latency. REST API view reads add ~1-2ms per query.

**Complexity**: High. Three ServiceFactory implementations need client lifecycle management. The embedded Hazelcast instance becomes an empty Jet runtime with no local data.

---

### Option C: Hybrid — Durable State Remote, Transient State Local

Keep transient pipeline coordination maps local. Move durable domain state (event stores, materialized views) to the shared cluster.

```
┌─────────────────────────────────────┐
│          Order Service Pod          │
│                                     │
│  ┌───────────────────────────────┐  │
│  │   Embedded Hazelcast (local)  │  │
│  │   Order_PENDING  (journal) ◄──┼──┼── Jet source (local, fast)
│  │   Order_COMPLETIONS        ◄──┼──┼── Jet sink (local, fast)
│  │   Jet Pipeline                │  │
│  │     Persist: remote client  ──┼──┼──┐
│  │     View: remote client     ──┼──┼──┤
│  │     Publish: remote ITopic  ──┼──┼──┤
│  └───────────────────────────────┘  │  │
└─────────────────────────────────────┘  │
                                         │
    ┌────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│       Shared Cluster (3 nodes)          │
│  Order_ES, Order_VIEW                   │
│  Product_ES, Product_VIEW               │
│  Customer_ES, Customer_VIEW             │
│  Payment_ES, Payment_VIEW               │
│  ITopic, saga-state, DLQ, idempotency   │
│  (durable state centralized)            │
└─────────────────────────────────────────┘
```

**What stays local (transient)**:
- `_PENDING`: Pipeline trigger. Events are consumed immediately by Jet; no need for durability.
- `_COMPLETIONS`: Tracks in-flight request futures. TTL-based, ephemeral.

**What moves to shared cluster (durable)**:
- `_ES`: Append-only event history. The source of truth.
- `_VIEW`: Materialized projections. Derived from events, but expensive to rebuild.
- `ITopic`: Already on shared cluster. Pipeline publishes directly (no more republish step).

**Pipeline stages**:
1. **Source** (local): `Sources.mapJournal("Order_PENDING")` — unchanged, sub-millisecond
2. **Enrich** (local): Static method, no IMap access — unchanged
3. **Persist** (remote): `EventStoreServiceCreator` uses `HazelcastClient` → shared cluster `_ES` map — ~1-2ms
4. **View Update** (remote): `ViewUpdaterServiceCreator` uses `HazelcastClient` → shared cluster `_VIEW` map — ~1-2ms
5. **Publish** (remote): `EventTopicPublisherServiceCreator` uses `HazelcastClient` → shared cluster ITopic — ~1ms
6. **Complete** (local): `Sinks.map("Order_COMPLETIONS")` — unchanged, sub-millisecond

**Implementation changes required**:

1. `EventSourcingPipeline.Builder` — add optional `ClientConfig sharedClusterConfig` parameter
2. `EventStoreServiceCreator` — when `ClientConfig` provided, create `HazelcastClient` and use for EventStore; manage lifecycle via `ServiceFactory`'s destroy callback
3. `ViewUpdaterServiceCreator` — same pattern
4. `EventTopicPublisherServiceCreator` — same pattern, publishes directly to shared cluster ITopic
5. `EventSourcingController` — skip `republishToSharedCluster()` when pipeline publishes directly; `EventStore` and `ViewStore` beans created with shared cluster client
6. Service configs — create `HazelcastEventStore` and `HazelcastViewStore` with `hazelcastClient` instead of `hazelcastInstance`
7. REST API reads — view queries go through shared cluster (adds ~1-2ms latency)

**Resilience**:
- Event stores and materialized views survive pod restarts (on 3-node cluster with 1 backup)
- Pending events and completions are lost on restart (acceptable — pending events represent in-flight requests that will timeout anyway)
- Shared cluster tolerates 1 node failure

**Scaling**:
- Multiple service replicas can safely share the same `_ES` and `_VIEW` maps
- Each replica's local `_PENDING` and `_COMPLETIONS` maps are independent (correct — they track that replica's in-flight requests)
- View reads are consistent across replicas since all read from the shared cluster

**Performance impact**:
- Pipeline trigger (source) and completion (sink) remain local — fast path preserved
- Persist and view update add ~1-2ms each (network to shared cluster)
- REST API view reads add ~1-2ms (was in-process, now remote)
- The 100K+ ops/sec benchmark for view updates would decrease, but the pipeline source → enrichment → completion path stays local

**Complexity**: Medium. Three ServiceFactory implementations need client lifecycle management, but source and sink stages are unchanged. Backward-compatible — omitting `ClientConfig` preserves current behavior.

---

## Comparison Matrix

| Dimension | A: Data-Local (Current) | B: Full-Remote | C: Hybrid |
|-----------|------------------------|----------------|-----------|
| **Data resilience** | None — lost on pod restart | Full — 3-node cluster with backups | Partial — durable state survives, transient lost |
| **Horizontal scaling** | Blocked — split-brain views | Enabled — shared maps | Enabled — shared ES and VIEW |
| **Pipeline source latency** | Sub-ms (local journal) | ~1-2ms (remote journal) | Sub-ms (local journal) |
| **Persist/view latency** | Sub-ms (local maps) | ~1-2ms (remote maps) | ~1-2ms (remote maps) |
| **REST API read latency** | Sub-ms (local view) | ~1-2ms (remote view) | ~1-2ms (remote view) |
| **End-to-end pipeline** | ~1ms total | ~8-12ms total | ~4-6ms total |
| **Cross-service publish** | Two-hop (pipeline → republish) | Single-hop (pipeline direct) | Single-hop (pipeline direct) |
| **Implementation effort** | None (status quo) | High (all stages remote) | Medium (3 stages remote) |
| **Backward compatible** | N/A | No | Yes (ClientConfig optional) |
| **Embedded instance purpose** | Data + compute | Compute only (empty) | Transient data + compute |
| **Shared cluster load** | Light (ITopic, saga) | Heavy (all maps + ITopic) | Moderate (ES, VIEW, ITopic) |
| **PostgreSQL dependency** | Required for scaling | Not required for scaling | Not required for scaling |

## Decision

**Adopt Option C (Hybrid)** as the recommended path forward, with the following implementation strategy:

### Phase 1: Prototype on Feature Branch

Implement the hybrid approach on `feature/hybrid-data-placement` with:
- Framework-level support for optional `ClientConfig` in pipeline builder
- Modified ServiceFactory creators with remote client lifecycle management
- Updated service configurations
- Performance benchmark comparing current vs hybrid pipeline throughput

### Phase 2: Evaluate Benchmark Results

Compare current (data-local) vs hybrid:
- Pipeline throughput (events/sec)
- End-to-end latency (P50, P99)
- REST API view read latency
- Memory footprint per service pod

If the performance impact is acceptable for the framework's target use case (demo/educational + moderate production):
- Merge hybrid as the default configuration
- Update ADR 008 status to "Superseded by ADR 011"
- Update ADR 010 to reflect that horizontal scaling is now possible without PostgreSQL

If the performance impact is too severe:
- Keep hybrid as an opt-in configuration (`framework.data-placement=hybrid`)
- Default remains data-local
- Users choose based on their resilience vs performance requirements

### Why Not Full-Remote?

Option B (full-remote) offers the maximum resilience but:
- Makes the embedded instance pointless — it's an empty Jet runtime with no local state
- Adds network latency to the pipeline's hot path (source and completion)
- Loses Hazelcast's core value proposition of data locality
- The hybrid approach captures 90% of the resilience benefit at ~50% of the performance cost

### Why Not Status Quo?

Option A (data-local) is the safest choice but:
- Horizontal scaling remains blocked until PostgreSQL persistence is implemented
- Data loss on pod restart is a real operational pain point
- The shared cluster already exists and has capacity — not using it for durable state wastes infrastructure

## Consequences

### Positive

- **Data resilience without PostgreSQL**: Event stores and views survive pod restarts via the shared cluster's partition replication
- **Horizontal scaling unlocked**: Multiple service replicas safely share `_ES` and `_VIEW` maps
- **Simplified cross-service publish**: Pipeline publishes directly to shared cluster ITopic — no more two-hop republish
- **Backward compatible**: Omitting `ClientConfig` preserves current data-local behavior
- **Reduced memory per pod**: Event stores and views no longer consume embedded instance memory

### Negative

- **Increased latency**: Persist, view update, and REST reads add ~1-2ms network latency each
- **Client lifecycle complexity**: ServiceFactory implementations must create and destroy `HazelcastClient` connections
- **Shared cluster load increases**: All domain event stores and views now partition across the 3-node cluster
- **Shared cluster becomes critical path**: If the shared cluster is down, the pipeline can't persist or update views (currently only cross-service publish fails)

### Mitigations

- **Latency**: For the framework's target throughput (~300 TPS end-to-end), ~4-6ms pipeline latency is acceptable
- **Client lifecycle**: Use `ServiceFactory`'s built-in destroy callback for clean shutdown
- **Cluster load**: The 3-node cluster at 512MB per node has ample capacity for demo workloads; production would scale cluster nodes
- **Critical path**: Circuit breakers on remote IMap operations; fall back to local-only mode if shared cluster is unreachable (future enhancement)

## Relationship to Other ADRs

| ADR | Impact |
|-----|--------|
| **ADR 008** (Dual-Instance) | Partially superseded. The dual-instance architecture remains, but the embedded instance's role changes from "data + compute" to "transient data + compute". The client instance becomes the primary data access path for durable state. |
| **ADR 010** (Single-Replica Scaling) | Partially superseded. Horizontal scaling is now possible without PostgreSQL, since event stores and views are shared across replicas via the cluster. |
| **ADR 004** (Six-Stage Pipeline) | Extended. Stages 3, 4, and 5 can now operate on remote maps via `ClientConfig`. The pipeline structure is unchanged. |
| **ADR 005** (Community Edition) | No impact. All changes use Community Edition APIs (`HazelcastClient`, `Sources.remoteMapJournal`, `Sinks.remoteMap`). |

## References

- ADR 008: Dual-Instance Hazelcast Architecture
- ADR 010: Single-Replica Scaling Strategy
- ADR 004: Six-Stage Pipeline Design
- [Hazelcast Remote Sources and Sinks](https://docs.hazelcast.com/hazelcast/5.6/pipelines/sources-sinks#remote-imaps)
- [Hazelcast Jet ServiceFactory](https://docs.hazelcast.com/hazelcast/5.6/pipelines/transforms#mapusingservice)
- Prototype branch: `feature/hybrid-data-placement`
