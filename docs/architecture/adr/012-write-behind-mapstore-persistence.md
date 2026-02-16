# ADR 012: Write-Behind MapStore for Event Persistence

## Status

**Decided** — February 2026

Related ADRs: ADR 011 (Data Placement Strategy), ADR 008 (Dual-Instance Hazelcast Architecture), ADR 004 (Six-Stage Pipeline Design)

## Context

ADR 011 concluded that data-local IMaps + PostgreSQL persistence is the recommended strategy for durability. The question remaining is **how** events and view state flow from Hazelcast to PostgreSQL.

Three approaches were evaluated:

1. **Dual-write (application-level)**: The application explicitly writes to both IMap and PostgreSQL
2. **Write-through MapStore**: Hazelcast synchronously writes to PostgreSQL on every `IMap.put()`
3. **Write-behind MapStore**: Hazelcast writes to IMap immediately, then asynchronously batches writes to PostgreSQL

### Performance Context from ADR 011

The ADR 011 benchmark demonstrated that moving IMap operations off the local embedded instance incurs a ~3x throughput reduction (84-105K TPS local vs 27-40K TPS remote). Any persistence strategy that adds latency to the pipeline hot path would produce a similar or worse degradation, since PostgreSQL writes (~1-5ms) are slower than even remote IMap operations (~0.1ms).

### Hazelcast MapStore/MapLoader

Hazelcast provides native persistence integration via the `MapStore` and `MapLoader` interfaces:

- **`MapStore.store(key, value)`**: Called by Hazelcast when a map entry is written
- **`MapStore.storeAll(map)`**: Batch variant for multiple entries
- **`MapLoader.load(key)`**: Called on cache miss to load from the backing store
- **`MapLoader.loadAllKeys()`**: Called on startup to populate the cache

MapStore supports two write modes:
- **Write-through**: `store()` is called synchronously during `put()` — the `put()` blocks until PostgreSQL write completes
- **Write-behind**: `store()` is called asynchronously after `put()` returns — Hazelcast buffers writes and flushes in batches on a background thread

Both modes are Community Edition features.

---

## Options Evaluated

### Option 1: Dual-Write (Application-Level)

Create a `DurableEventStore` wrapper that writes to `HazelcastEventStore` first, then asynchronously writes to `EventStorePersistence` via a background thread or completion callback.

```
Event → DurableEventStore.append()
          ├─ HazelcastEventStore.append() (IMap.put)  ← fast path
          └─ CompletableFuture → EventStorePersistence.persist()  ← async
```

**Startup recovery**: A custom `EventReplayService` reads from PostgreSQL and replays events into the IMap.

**Pros:**
- Full control over write timing and error handling
- No Hazelcast configuration changes
- Provider interface stays completely Hazelcast-agnostic

**Cons:**
- Must build async write queue, batching, retry, and backpressure from scratch
- Must build cold-start replay logic manually
- Two parallel write paths to test and maintain
- Failure handling is complex (what if IMap succeeds but PG queue overflows?)
- Reinvents functionality Hazelcast already provides

---

### Option 2: Write-Through MapStore

Configure `MapStoreConfig` on each IMap with `writeDelaySeconds = 0`. Every `IMap.put()` synchronously calls `MapStore.store()`, which writes to PostgreSQL.

```
Event → IMap.put()
          ├─ Write to local IMap  ← fast
          └─ MapStore.store() → PostgreSQL  ← blocks, ~1-5ms
```

**Startup recovery**: `MapLoader.loadAllKeys()` + `MapLoader.loadAll()` automatically called by Hazelcast.

**Pros:**
- Strong consistency — write only succeeds if both IMap and PostgreSQL succeed
- No data loss window
- Automatic cold-start loading via MapLoader
- Simple to reason about

**Cons:**
- **Adds ~1-5ms to every `IMap.put()` in the Jet pipeline** — this is the same problem ADR 011 identified with the hybrid approach. Pipeline stages 3 (event store) and 4 (view update) would go from sub-millisecond to multi-millisecond.
- MapStore runs in Hazelcast partition threads. Blocking PostgreSQL writes risk partition thread starvation, affecting all IMap operations on that partition.
- No batching — each `put()` is an individual `INSERT` statement
- Under load, PostgreSQL becomes the bottleneck for the entire pipeline

---

### Option 3: Write-Behind MapStore

Configure `MapStoreConfig` with `writeDelaySeconds > 0` (e.g., 5 seconds). `IMap.put()` returns immediately at local speed. Hazelcast accumulates writes in an in-memory buffer and flushes them to PostgreSQL in batches on a background thread.

```
Event → IMap.put()  ← returns immediately (local speed)
          ├─ Write to local IMap
          └─ Entry queued in write-behind buffer
                    │
                    ▼  (background thread, every N seconds)
              MapStore.storeAll(batch) → JDBC batch INSERT into PostgreSQL
```

**Startup recovery**: Same as write-through — `MapLoader.loadAllKeys()` + `MapLoader.loadAll()` automatically called by Hazelcast.

**Pros:**
- **Zero pipeline latency impact** — `put()` returns at local IMap speed, identical to today
- **Automatic JDBC batching** — Hazelcast coalesces multiple writes into a single `storeAll()` call, which maps to a JDBC batch insert. Far more efficient than individual inserts.
- **Built-in retry** — Hazelcast retries failed `storeAll()` calls
- **Coalescing** — for view maps, rapid updates to the same key coalesce to a single write (only the latest state is persisted). For event stores, each event has a unique key, so no data is lost.
- **MapLoader for cold start** — automatic cache population on restart, no manual replay code
- **Community Edition** — MapStore/MapLoader are core Hazelcast features
- **Background thread isolation** — PostgreSQL writes don't block partition threads or pipeline execution

**Cons:**
- **Data loss window**: If the embedded instance crashes before the write-behind buffer is flushed, pending writes are lost. The window equals `writeDelaySeconds` (configurable, default 5s).
- **Write-behind buffer is in-memory** — if PostgreSQL is down for extended periods, the buffer grows unbounded. Needs monitoring.
- **Configuration tuning** — `writeDelaySeconds`, `writeBatchSize`, `writeCoalescing` affect behavior
- **MapStore interface works with the map's value type** — our maps store `GenericRecord`, so the MapStore must convert between `GenericRecord` and JDBC rows

---

## Comparison Matrix

| Dimension | Dual-Write | Write-Through | Write-Behind |
|-----------|-----------|---------------|-------------|
| **Pipeline latency impact** | None (async) | ~1-5ms per `put()` | None |
| **Batching** | Must build ourselves | None (individual inserts) | Automatic (JDBC batch) |
| **Cold-start rebuild** | Must build ourselves | Automatic (MapLoader) | Automatic (MapLoader) |
| **Failure/retry handling** | Must build ourselves | Hazelcast handles | Hazelcast handles |
| **Data loss window** | Custom (depends on impl) | None | `writeDelaySeconds` |
| **Coalescing for views** | Must implement | No | Automatic |
| **Partition thread impact** | None | Blocks partition threads | None (background thread) |
| **Code to maintain** | Significant | Minimal (MapStore impl) | Minimal (MapStore impl) |
| **Hazelcast-native** | No | Yes | Yes |
| **PostgreSQL efficiency** | Depends on impl | Poor (individual inserts) | Good (batch inserts) |

---

## Decision

**Adopt Option 3: Write-Behind MapStore.**

Write-behind preserves the pipeline's local IMap performance (the key outcome from ADR 011), while leveraging Hazelcast's built-in persistence infrastructure for batching, retry, cold-start loading, and coalescing. Building these capabilities from scratch via dual-write would be significant engineering effort for an inferior result.

### Architecture

```
                    framework-core (no DB dependencies)
                    ┌─────────────────────────────────────┐
                    │  EventStorePersistence (interface)   │
                    │    persist(PersistableEvent)         │
                    │    persistBatch(List<...>)           │
                    │    loadEvents(aggregateId)           │
                    │    loadAllKeys()                     │
                    │    isAvailable()                     │
                    └──────────────┬──────────────────────┘
                                   │ implements
                    ┌──────────────▼──────────────────────┐
                    │  PostgresEventStorePersistence       │
                    │    (Spring Data JPA + PostgreSQL)    │
                    └──────────────┬──────────────────────┘
                                   │ delegates to
                    ┌──────────────▼──────────────────────┐
                    │  EventStoreMapStore                  │
                    │    implements MapStore<String,       │
                    │                       GenericRecord> │
                    │    implements MapLoader<String,      │
                    │                        GenericRecord>│
                    └──────────────┬──────────────────────┘
                                   │ configured on
                    ┌──────────────▼──────────────────────┐
                    │  IMap "{Domain}_ES"                  │
                    │    write-behind delay: 5s            │
                    │    write batch size: 100             │
                    │    write coalescing: false           │
                    │    (events are append-only,          │
                    │     each key is unique)              │
                    └─────────────────────────────────────┘
```

The same pattern applies to view maps (`{Domain}_VIEW`), with one difference:
- **View MapStore**: `writeCoalescing = true` — only the latest view state is persisted, which is correct since views are derived projections.

### Provider-Agnostic Design

The `EventStorePersistence` interface remains in `framework-core` with no database dependencies. The `MapStore` adapter is a thin Hazelcast-specific bridge:

```java
public class EventStoreMapStore implements MapStore<String, GenericRecord>,
                                           MapLoader<String, GenericRecord> {
    private final EventStorePersistence persistence;

    @Override
    public void store(String key, GenericRecord value) {
        persistence.persist(toPersistableEvent(key, value));
    }

    @Override
    public void storeAll(Map<String, GenericRecord> map) {
        List<PersistableEvent> batch = map.entrySet().stream()
                .map(e -> toPersistableEvent(e.getKey(), e.getValue()))
                .toList();
        persistence.persistBatch(batch);
    }

    @Override
    public GenericRecord load(String key) {
        return persistence.loadEvent(key)
                .map(this::toGenericRecord)
                .orElse(null);
    }

    @Override
    public Iterable<String> loadAllKeys() {
        return persistence.loadAllKeys();
    }
}
```

Swapping PostgreSQL for another provider requires only:
1. Implement `EventStorePersistence`
2. Register the bean via auto-configuration
3. No changes to `EventStoreMapStore`, pipeline, or service code

### Configuration

```yaml
framework:
  persistence:
    enabled: true                    # false = Hazelcast-only (no MapStore configured)
    provider: postgresql             # informational; auto-detected from classpath
    write-delay-seconds: 5           # write-behind flush interval
    write-batch-size: 100            # max entries per batch flush
    write-coalescing: false          # false for event stores (append-only)
    initial-load-mode: LAZY          # LAZY or EAGER — controls MapLoader behavior
```

### MapLoader Strategy for Cold Start

`MapLoader.loadAllKeys()` can be expensive for large event stores (millions of events). The strategy depends on the IMap's purpose:

| Map | Load Strategy | Rationale |
|-----|--------------|-----------|
| `{Domain}_ES` | **Lazy** (`initial-load-mode: LAZY`) | Events are loaded on-demand via `MapLoader.load(key)` when accessed. Most events are never re-read after pipeline processing. |
| `{Domain}_VIEW` | **Eager** (`initial-load-mode: EAGER`) | Views are queried by REST APIs immediately after startup. Load all view entries to avoid cache misses on the first request. |
| `{Domain}_PENDING` | **No MapStore** | Transient pipeline trigger. Lost on restart — new events will be submitted via the REST API. |
| `{Domain}_COMPLETIONS` | **No MapStore** | Transient completion tracking for in-flight requests. TTL-based, ephemeral. |

### IMap Eviction

With MapStore backing, IMaps can safely evict entries knowing they're durable in PostgreSQL:

- **Event store maps**: `max-size policy: USED_HEAP_PERCENTAGE` or entry count limit with LRU eviction. Evicted events are still in PostgreSQL and can be reloaded via `MapLoader.load()`.
- **View maps**: Less aggressive eviction (views are hot read targets). Consider `max-idle-seconds` for infrequently accessed aggregates.

### Why Not Dual-Write?

Dual-write was the original plan (Day 24 in the Phase 3 implementation plan). After analyzing the MapStore alternative:

1. **Dual-write reinvents MapStore**: Async write queue, batching, retry, backpressure, cold-start replay — all of these exist in Hazelcast's write-behind implementation, battle-tested across thousands of production deployments.
2. **MapLoader eliminates replay code**: The custom `EventReplayService` planned for Day 24 becomes unnecessary. Hazelcast calls `loadAllKeys()` + `loadAll()` automatically on startup.
3. **Less code to maintain**: The `DurableEventStore` wrapper class, async write queue, and replay service are replaced by a single `MapStore` implementation class per map type.
4. **Same data loss characteristics**: Both dual-write (async) and write-behind have a window where a crash loses in-flight writes. Write-behind makes the window explicit and configurable (`writeDelaySeconds`).

### Why Not Write-Through?

Write-through adds ~1-5ms to every `IMap.put()` in the Jet pipeline — the same throughput penalty that ADR 011's benchmark showed makes the framework less viable for production use. Write-behind avoids this entirely.

---

## Consequences

### Positive

- **Pipeline performance preserved**: `IMap.put()` returns at local speed. PostgreSQL writes happen on a background thread.
- **Efficient PostgreSQL usage**: Batch inserts via `storeAll()` instead of individual inserts. Coalescing for view maps reduces write volume further.
- **Automatic cold-start recovery**: `MapLoader` rebuilds IMaps from PostgreSQL without custom replay code.
- **Reduced codebase**: No `DurableEventStore`, no `EventReplayService`, no async write queue. The MapStore implementation is the single integration point.
- **Provider-agnostic interface preserved**: `EventStorePersistence` stays clean. MapStore is a thin adapter.
- **Hazelcast-native**: Leverages battle-tested infrastructure rather than custom implementation.

### Negative

- **Data loss window**: Writes buffered in the write-behind queue are lost on crash. Window equals `writeDelaySeconds` (default 5s).
- **GenericRecord ↔ JDBC conversion**: MapStore must convert between `GenericRecord` and database rows. Requires a schema-aware conversion layer.
- **MapStore per-map configuration**: Each IMap that needs persistence requires its own `MapStoreConfig`. This is configured programmatically in the service config classes.
- **MapLoader.loadAllKeys() for eager loading**: Must be implemented efficiently (e.g., `SELECT DISTINCT aggregate_id FROM domain_events` for event stores, or `SELECT key FROM view_state` for views).

### Mitigations

- **Data loss window**: Configurable `writeDelaySeconds` (can be set to 1s for tighter guarantees at the cost of more frequent flushes). Batch flush on graceful shutdown. For the framework's demo use case, 5s is acceptable.
- **GenericRecord conversion**: The `PersistableEvent` record serves as the intermediate format. `GenericRecord` → `PersistableEvent` conversion uses `GenericRecord.getFieldKind()` to dynamically extract fields. This is a one-time implementation per map type.
- **Per-map configuration**: Auto-configuration creates `MapStoreConfig` for all `_ES` and `_VIEW` maps based on the domain name. Services don't configure MapStore manually.
- **loadAllKeys() efficiency**: For event stores (lazy load), `loadAllKeys()` returns an empty iterable. For view maps (eager load), it returns all view keys from a single indexed query.

---

## Relationship to Other ADRs

| ADR | Impact |
|-----|--------|
| **ADR 011** (Data Placement) | Implements. ADR 011 chose data-local + PostgreSQL. This ADR specifies write-behind MapStore as the mechanism. |
| **ADR 008** (Dual-Instance) | Compatible. MapStore is configured on the embedded instance's IMaps. The shared cluster client is unaffected. |
| **ADR 004** (Six-Stage Pipeline) | No impact. Pipeline stages continue to call `IMap.put()` as before. MapStore is transparent to pipeline code. |
| **ADR 005** (Community Edition) | Compatible. MapStore/MapLoader are Community Edition features. |
| **ADR 010** (Single-Replica Scaling) | Superseded (along with ADR 011). PostgreSQL enables horizontal scaling via independent cache rebuilds. |

## References

- ADR 011: Data Placement Strategy
- ADR 008: Dual-Instance Hazelcast Architecture
- [Hazelcast MapStore/MapLoader](https://docs.hazelcast.com/hazelcast/5.6/data-structures/working-with-external-data)
- [Write-Behind Configuration](https://docs.hazelcast.com/hazelcast/5.6/data-structures/map-store#setting-write-behind-persistence)
- Phase 3 Implementation Plan: Area 6 (Days 23-25)
