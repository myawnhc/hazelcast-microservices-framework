# Phase 3, Day 23: Persistence Interface & PostgreSQL Implementation

## Status: Implemented

## Summary

Implemented the full persistence stack for write-behind MapStore as described in ADR 012:
- Provider-agnostic interfaces in `framework-core` (no database dependencies)
- MapStore/MapLoader adapters bridging Hazelcast to persistence providers
- New `framework-postgres` module with PostgreSQL implementation
- Service config wiring for all 4 microservices
- Docker Compose with PostgreSQL service

## Files Created (~20)

### framework-core (persistence package)
1. `EventStorePersistence.java` — Provider-agnostic interface
2. `ViewStorePersistence.java` — Provider-agnostic interface
3. `PersistableEvent.java` — Record for event transfer
4. `PersistableView.java` — Record for view transfer
5. `PersistenceProperties.java` — Configuration properties
6. `PersistenceAutoConfiguration.java` — Auto-configuration
7. `mapstore/EventStoreMapStore.java` — MapStore adapter for events
8. `mapstore/ViewStoreMapStore.java` — MapStore adapter for views
9. `mapstore/GenericRecordJsonConverter.java` — GenericRecord ↔ JSON conversion

### framework-postgres (new module)
10. `pom.xml` — Module definition with JPA, PostgreSQL, Flyway deps
11. `PostgresEventStorePersistence.java` — PostgreSQL event persistence
12. `PostgresViewStorePersistence.java` — PostgreSQL view persistence
13. `PostgresPersistenceAutoConfiguration.java` — Auto-configuration
14. `entity/EventStoreEntity.java` — JPA entity
15. `entity/ViewStoreEntity.java` — JPA entity
16. `repository/EventStoreRepository.java` — JPA repository
17. `repository/ViewStoreRepository.java` — JPA repository
18. `db/migration/V1__create_event_store_table.sql` — Flyway migration
19. `db/migration/V2__create_view_store_table.sql` — Flyway migration
20. `AutoConfiguration.imports` — Spring Boot auto-config registration

### Tests
21. `PersistencePropertiesTest.java` — Unit test
22. `EventStoreMapStoreTest.java` — Unit test
23. `GenericRecordJsonConverterTest.java` — Unit test
24. `PostgresEventStorePersistenceTest.java` — Integration test (Testcontainers)
25. `PostgresViewStorePersistenceTest.java` — Integration test (Testcontainers)

## Files Modified (7)
1. `pom.xml` (root) — Added `framework-postgres` module, PostgreSQL/Flyway dependencies
2. `docker/docker-compose.yml` — Added `postgres` service, persistence env vars
3. `AutoConfiguration.imports` (framework-core) — Added `PersistenceAutoConfiguration`
4. `AccountServiceConfig.java` — Injected MapStore beans, configured write-behind
5. `InventoryServiceConfig.java` — Same pattern
6. `OrderServiceConfig.java` — Same pattern
7. `PaymentServiceConfig.java` — Same pattern
