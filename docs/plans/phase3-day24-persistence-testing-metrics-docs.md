# Phase 3, Day 24: Persistence Testing, Metrics & Documentation

## Context

Day 23 implemented the full persistence stack: provider-agnostic interfaces, PostgreSQL implementation, MapStore adapters, and service wiring. Day 24 completes Area 6 by filling the remaining gaps: in-memory persistence for contract testing, missing unit tests, Micrometer metrics, IMap eviction for bounded hot cache, a persistence guide, and README updates.

## Blocks

1. **In-Memory Persistence** — `InMemoryEventStorePersistence`, `InMemoryViewStorePersistence`, tests, auto-config fallback
2. **MapStore Test Parity** — `ViewStoreMapStoreTest` (new), `EventStoreMapStoreTest` gap coverage
3. **PersistenceMetrics** — Micrometer instrumentation for MapStore operations, tests
4. **IMap Eviction** — `EvictionConfig` in `PersistenceProperties`, wired into 4 service configs
5. **Grafana Dashboard** — `persistence-dashboard.json`
6. **Documentation** — `persistence-guide.md`, README updates

## File Summary

| Block | New Files | Modified Files |
|-------|-----------|----------------|
| 1 | 4 (2 src + 2 test) | 1 (PersistenceAutoConfiguration) |
| 2 | 1 (ViewStoreMapStoreTest) | 1 (EventStoreMapStoreTest) |
| 3 | 2 (PersistenceMetrics + test) | 3 (EventStoreMapStore, ViewStoreMapStore, PersistenceAutoConfiguration) |
| 4 | 0 | 6 (PersistenceProperties, PersistencePropertiesTest, 4 service configs) |
| 5 | 1 (dashboard JSON) | 0 |
| 6 | 2 (guide + plan) | 1 (README) |
| **Total** | **10 new** | **~9 unique modified** |

## Verification

```bash
mvn install -pl framework-core -DskipTests
mvn install -pl framework-postgres -DskipTests
mvn clean test
```
