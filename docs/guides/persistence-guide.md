# Persistence Guide

This guide explains how the Hazelcast Microservices Framework persists event store and materialized view data to durable storage using write-behind MapStores.

## Overview

In a pure in-memory event sourcing system, all data lives in Hazelcast IMaps. This is fast but volatile — a full cluster restart loses everything. The persistence layer adds durable storage behind the IMaps so that:

- **Events survive restarts** — the append-only event log is written to PostgreSQL (or any provider)
- **Views rebuild on cold start** — materialized views are loaded from the database via MapLoader
- **Memory stays bounded** — IMap eviction keeps entries within limits while the database holds the full dataset
- **No code changes needed** — persistence is configured via `application.yml`, not code

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                    Microservice JVM                       │
│                                                          │
│  ┌─────────────┐        ┌──────────────────────────┐     │
│  │   Service    │───────▶│  EventSourcingController  │     │
│  │  (REST API)  │        └──────────┬───────────────┘     │
│  └─────────────┘                    │                     │
│                                     ▼                     │
│                           ┌─────────────────┐             │
│                           │  Jet Pipeline    │             │
│                           └────────┬────────┘             │
│                    ┌───────────────┼───────────────┐      │
│                    ▼               ▼               ▼      │
│           ┌──────────────┐ ┌──────────────┐ ┌──────────┐ │
│           │  _ES IMap    │ │  _VIEW IMap  │ │  ITopic  │ │
│           │  (events)    │ │  (views)     │ │  (pub)   │ │
│           └──────┬───────┘ └──────┬───────┘ └──────────┘ │
│                  │                │                       │
│           ┌──────▼───────┐ ┌──────▼───────┐              │
│           │ EventStore   │ │ ViewStore    │              │
│           │ MapStore     │ │ MapStore     │              │
│           │ (write-behind│ │ (write-behind│              │
│           │  + MapLoader)│ │  + MapLoader)│              │
│           └──────┬───────┘ └──────┬───────┘              │
│                  │                │                       │
│           ┌──────▼───────┐ ┌──────▼───────┐              │
│           │ EventStore   │ │ ViewStore    │              │
│           │ Persistence  │ │ Persistence  │  (interface) │
│           └──────┬───────┘ └──────┬───────┘              │
└──────────────────┼────────────────┼──────────────────────┘
                   │                │
            ┌──────▼────────────────▼──────┐
            │        PostgreSQL 16         │
            │  ┌──────────────────────┐    │
            │  │   domain_events      │    │
            │  │   domain_views       │    │
            │  └──────────────────────┘    │
            └──────────────────────────────┘
```

## Components

| Component | Module | Description |
|-----------|--------|-------------|
| `EventStorePersistence` | framework-core | Provider-agnostic interface for event persistence |
| `ViewStorePersistence` | framework-core | Provider-agnostic interface for view persistence |
| `PersistableEvent` | framework-core | Portable event record (decoupled from GenericRecord) |
| `PersistableView` | framework-core | Portable view record (decoupled from GenericRecord) |
| `EventStoreMapStore` | framework-core | Hazelcast MapStore adapter for events |
| `ViewStoreMapStore` | framework-core | Hazelcast MapStore adapter for views |
| `GenericRecordJsonConverter` | framework-core | Converts GenericRecord to/from JSON |
| `PersistenceProperties` | framework-core | Spring Boot configuration properties |
| `PersistenceAutoConfiguration` | framework-core | Auto-config for MapStore and fallback beans |
| `PersistenceMetrics` | framework-core | Micrometer instrumentation for MapStore operations |
| `InMemoryEventStorePersistence` | framework-core | In-memory fallback (dev/test, no database) |
| `InMemoryViewStorePersistence` | framework-core | In-memory fallback (dev/test, no database) |
| `PostgresEventStorePersistence` | framework-postgres | PostgreSQL implementation |
| `PostgresViewStorePersistence` | framework-postgres | PostgreSQL implementation |

## Configuration

### Full YAML Reference

```yaml
framework:
  persistence:
    enabled: true                  # Master switch (default: false)
    write-delay-seconds: 5         # Batch window in seconds (default: 5)
    write-batch-size: 100          # Max entries per batch (default: 100)
    write-coalescing: false        # Coalesce writes to same key (default: false)
    initial-load-mode: LAZY        # LAZY or EAGER (default: LAZY)

    # Eviction for event store IMaps (bounded hot cache)
    event-store-eviction:
      enabled: true                # Enable eviction (default: true)
      max-size: 10000              # Max entries per node (default: 10000)
      max-size-policy: PER_NODE    # PER_NODE, USED_HEAP_PERCENTAGE, etc.
      eviction-policy: LRU         # LRU, LFU, RANDOM, NONE
      max-idle-seconds: 0          # Evict after idle (0 = disabled)

    # Eviction for view store IMaps
    view-store-eviction:
      enabled: true
      max-size: 10000
      max-size-policy: PER_NODE
      eviction-policy: LRU
      max-idle-seconds: 3600       # Views idle > 1hr are evicted (default)
```

### Write-Behind vs Write-Through

- **Write-behind** (`write-delay-seconds > 0`): Hazelcast batches writes and flushes asynchronously. Best for throughput.
- **Write-through** (`write-delay-seconds: 0`): Every IMap put synchronously calls the MapStore. Best for durability guarantees.

### Event Store vs View Store Behavior

| Aspect | Event Store (`_ES`) | View Store (`_VIEW`) |
|--------|--------------------|--------------------|
| Coalescing | `false` — each event is unique | `true` — only latest state matters |
| Initial Load | `LAZY` — events loaded on demand | `EAGER` — all keys loaded on cold start |
| Write Semantics | `INSERT` (append-only) | `UPSERT` (latest wins) |

## Custom Provider

To implement a custom persistence provider (e.g., MySQL, CockroachDB):

### Step 1: Implement the Interfaces

```java
public class MySqlEventStorePersistence implements EventStorePersistence {

    @Override
    public void persist(String mapName, PersistableEvent event) {
        // INSERT INTO domain_events ...
    }

    @Override
    public void persistBatch(String mapName, List<PersistableEvent> events) {
        // Batch INSERT
    }

    @Override
    public Optional<PersistableEvent> loadEvent(String mapName, String mapKey) {
        // SELECT ... WHERE map_name = ? AND map_key = ?
    }

    @Override
    public Iterable<String> loadAllKeys(String mapName) {
        // SELECT map_key FROM domain_events WHERE map_name = ?
    }

    @Override
    public void delete(String mapName, String mapKey) {
        // DELETE FROM domain_events WHERE map_name = ? AND map_key = ?
    }

    @Override
    public boolean isAvailable() {
        // SELECT 1
    }
}
```

### Step 2: Register as a Spring Bean

Create an auto-configuration class with `@AutoConfigureBefore(PersistenceAutoConfiguration.class)` so it takes priority over the in-memory fallback:

```java
@AutoConfiguration
@AutoConfigureBefore(PersistenceAutoConfiguration.class)
@ConditionalOnProperty(name = "framework.persistence.enabled", havingValue = "true")
public class MySqlPersistenceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EventStorePersistence.class)
    public EventStorePersistence mySqlEventStorePersistence() {
        return new MySqlEventStorePersistence();
    }
}
```

### Step 3: Add to AutoConfiguration.imports

```
com.example.persistence.MySqlPersistenceAutoConfiguration
```

## PostgreSQL Setup

### Docker Compose

The framework includes a Docker Compose service for PostgreSQL:

```yaml
postgres:
  image: postgres:16-alpine
  environment:
    POSTGRES_DB: ecommerce
    POSTGRES_USER: ecommerce
    POSTGRES_PASSWORD: ecommerce
  ports:
    - "5432:5432"
  volumes:
    - postgres-data:/var/lib/postgresql/data
```

### Flyway Migrations

Schema is managed by Flyway. The `framework-postgres` module includes migrations:

- `V1__create_domain_events.sql` — creates `domain_events` table with composite PK
- `V2__create_domain_views.sql` — creates `domain_views` table with composite PK

### Service Configuration

Add `framework-postgres` as a dependency and configure the datasource:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/ecommerce
    username: ecommerce
    password: ecommerce
  flyway:
    enabled: true

framework:
  persistence:
    enabled: true
```

## In-Memory Mode

When `framework.persistence.enabled=true` but no external provider (e.g., PostgreSQL) is on the classpath, the framework automatically falls back to `InMemoryEventStorePersistence` and `InMemoryViewStorePersistence`.

This is useful for:
- **Local development** without running PostgreSQL
- **Unit tests** that need the MapStore pipeline but not a real database
- **CI pipelines** where database setup is not practical

The in-memory providers are backed by `ConcurrentHashMap` and are fully thread-safe.

## Eviction & Bounded Cache

When persistence is enabled, IMaps become **bounded hot caches** backed by the database. This prevents unbounded memory growth during long-running demos or production use.

### How It Works

1. Events/views are written to the IMap (and asynchronously to the database via write-behind)
2. When the IMap reaches `max-size` entries, the LRU eviction policy removes the least recently used entries
3. If a subsequent `get()` request hits an evicted key, the MapLoader transparently reloads it from the database
4. View maps also evict entries idle longer than `max-idle-seconds` (default: 1 hour)

### Tuning

- **Increase `max-size`** if you have ample heap and want fewer database round-trips
- **Decrease `max-idle-seconds`** for view maps if memory is constrained
- **Set `eviction-policy: NONE`** to disable eviction (use only if heap is large enough for the full dataset)

## Monitoring

### Metric Names

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `persistence.store.count` | Counter | mapName, storeType | Single write operations |
| `persistence.store.batch.count` | Counter | mapName, storeType | Batch write operations |
| `persistence.store.batch.entries` | Counter | mapName, storeType | Total entries in batches |
| `persistence.load.count` | Counter | mapName | Load operations |
| `persistence.load.miss` | Counter | mapName | Load misses (not found in DB) |
| `persistence.delete.count` | Counter | mapName | Delete operations |
| `persistence.errors` | Counter | mapName, operation | Errors by operation |
| `persistence.store.duration` | Timer | mapName, storeType | Single write latency (p50/p95/p99) |
| `persistence.store.batch.duration` | Timer | mapName, storeType | Batch write latency |
| `persistence.load.duration` | Timer | mapName | Load latency |

### Grafana Dashboard

A pre-built Grafana dashboard (`persistence-dashboard.json`) is included in `docker/grafana/dashboards/`. It auto-provisions when Grafana starts and shows:

- **Overview**: Event/view write rates, error count, average batch size
- **Throughput**: Write rate by map name
- **Latency**: p95 latency for store, batch, and load operations
- **Errors**: Error rate by operation type

### Alerting Suggestions

- Alert on `persistence.errors` rate > 0 for more than 1 minute
- Alert on `persistence.store.duration` p99 > 1 second (indicates database performance issues)
- Alert on `persistence.load.miss` rate significantly higher than `persistence.load.count` (indicates poor cache hit ratio)

## Troubleshooting

### Events not appearing in PostgreSQL

1. Check `framework.persistence.enabled=true` in `application.yml`
2. Verify PostgreSQL is running and the datasource URL is correct
3. Check logs for `Persistence enabled for {}_ES map` message on startup
4. With `write-delay-seconds: 5`, events are batched — wait at least 5 seconds

### Cold start loading slowly

- View maps use `EAGER` initial load — all keys are loaded on startup
- For large datasets, increase JVM heap or switch to `LAZY` initial load
- Check MapLoader performance with `persistence.load.duration` metrics

### OOMKill during long demos

- Enable eviction (enabled by default when persistence is active)
- Reduce `max-size` from 10000 to a smaller value
- Reduce `max-idle-seconds` for view maps

### Write-behind batch size too small

- Increase `write-delay-seconds` to allow more entries to accumulate
- Increase `write-batch-size` to flush more entries per batch
- Monitor `persistence.store.batch.entries` / `persistence.store.batch.count` for actual average batch size

## References

- [ADR 012: Write-Behind MapStore for Event Persistence](../architecture/adr/012-write-behind-mapstore-persistence.md)
- [Hazelcast MapStore Documentation](https://docs.hazelcast.com/hazelcast/5.6/data-structures/working-with-external-data)
- [Hazelcast Eviction Documentation](https://docs.hazelcast.com/hazelcast/5.6/data-structures/map#eviction)
