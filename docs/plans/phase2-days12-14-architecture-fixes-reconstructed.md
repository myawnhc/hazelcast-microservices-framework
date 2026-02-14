# Phase 2 Days 12-14: Architecture Fixes, Dual-Instance Hazelcast & Grafana Dashboards (Reconstructed)

> Reconstructed from git history and implementation plan. May not capture all details from the original session.

## Context

Days 12-14 were originally planned as Grafana dashboard development (System Overview, Events & Views, Saga dashboards). In practice, these days became a critical architecture refinement period. The first Docker deployment attempt revealed that the original single-cluster architecture caused Jet pipeline lambda serialization failures across services. This led to the most significant architectural decision in the project: the dual-instance Hazelcast architecture (ADR 008). Multiple bug fixes, dashboard creation, and script updates were also completed across 8 commits.

## What Was Built

### Architecture: Dual-Instance Hazelcast (commit e9bf729 -- the defining change)
**Problem**: When all services joined a shared Hazelcast cluster, Jet distributed pipeline jobs to all cluster members. Members from other services lacked the required service-specific classes (e.g., OrderViewUpdater), causing `ClassCastException: cannot assign instance of java.lang.invoke.SerializedLambda`.

**Solution**: Each microservice now runs two Hazelcast instances:
1. **Embedded standalone instance** (`@Primary`): Runs Jet pipeline locally with no cluster join
2. **Client instance** (`hazelcastClient`): Connects to the shared 3-node Hazelcast cluster for cross-service ITopic pub/sub and shared saga state IMap

After local pipeline processing completes, EventSourcingController republishes events to the shared cluster's ITopic for saga listeners on other services.

- `EventSourcingController`: Added `sharedHazelcast` field and `republishToSharedCluster()` method
- All ServiceConfigs (Account, Inventory, Order, Payment): Added `hazelcastClient()` bean, `@Primary` on `hazelcastInstance()`
- All SagaListeners: Updated to use `@Qualifier("hazelcastClient")` for topic subscriptions
- SagaStateStore beans: Use `hazelcastClient` for shared saga state
- `ADR 008`: Full documentation of the decision, alternatives evaluated, and rationale
- `CLAUDE.md`: Updated with prominent warning not to revert to single-cluster architecture

### Pipeline Fix: Completion Tracking (commit 971d72b)
- Pipeline Stage 6: Changed from `PartitionedSequenceKey` (which does not survive serialization round-trips) to `eventId` (String) as the completions map key
- Controller: Matches completions by eventId, added timeout logging
- All service configs: Disabled multicast and auto-detection for cluster isolation
- Added SagaStateStore bean to inventory, order, and payment service configs
- Created PaymentServiceConfig (was missing) and payment-service Dockerfile
- Docker: Gave each service its own cluster name, removed shared cluster members

### Demo Script Fix (commit a123ba8)
- Fixed order cancellation in demo scenario 2: added missing JSON request body with reason and cancelledBy fields

### Pipeline Metrics Enhancement (commit 7399a49)
- EventSourcingPipeline Stage 6: Built PipelineCompletion GenericRecord with timing fields (pipelineEntryMs, persistStartMs, viewUpdateStartMs, etc.)
- PipelineMetrics: Added Duration-based overloads for recordStageTiming(), recordEndToEndLatency(), recordQueueWaitTime()

### Inventory Compensation Methods (commit 49e7035)
- InventoryService: Added `releaseReservedStockForOrder()` for direct cancellations
- ProductService: Added `releaseStockForSaga()` and `releaseReservedStockForOrder()` interface methods

### Grafana Dashboards & Docker (commit 214fea1)
- Docker Compose: Standardized HAZELCAST_CLUSTER_NAME and HAZELCAST_CLUSTER_MEMBERS across all services
- `system-overview.json`: Pipeline throughput and latency metrics dashboard
- `event-flow.json`: Event processing visualization dashboard
- `materialized-views.json`: View update monitoring dashboard
- `saga-dashboard.json`: Updated for saga completion metrics
- Grafana provisioning configuration: dashboards.yml, datasources.yml
- Alert configuration: alerts.yml, contactpoints.yml, policies.yml

### Demo Script Updates (commit 56094d5)
- demo-scenarios.sh: Updated to use saga-driven `wait_for_order_status` instead of manual step confirmations; fixed order cancellation endpoint (PATCH)
- load-sample-data.sh: Updated for current API structure
- load-test.sh: Aligned with saga completion timing expectations

### Order Service Fixes (commit da78acf)
- OrderLineItem: Minor fixes
- OrderService: Additional saga-related fixes (46 lines added)

## Key Decisions

- **Dual-instance Hazelcast architecture (ADR 008)**: This is the most important architectural decision from this period. It was driven by a runtime failure that could not be solved with simpler approaches. See ADR 008 for the full list of alternatives that were evaluated and rejected (single shared cluster, separate clusters per service, User Code Deployment, User Code Namespaces, JobConfig.addClass()).
- **EventId as completion map key**: PartitionedSequenceKey does not round-trip through Hazelcast serialization; simple String eventId is reliable and sufficient.
- **Republish to shared cluster**: After local Jet processing, events are republished to the shared cluster's ITopic. This is the bridge between local processing and cross-service communication.
- **Service-per-cluster-name for isolation**: Each service's embedded Hazelcast uses a unique cluster name to prevent accidental cluster formation in Docker.

## Commits (in chronological order)

| Hash | Date | Summary |
|------|------|---------|
| `971d72b` | 2026-02-03 | Fix pipeline completion tracking and service isolation |
| `a123ba8` | 2026-02-03 | Fix demo script missing request body |
| `e9bf729` | 2026-02-05 | Implement dual-instance Hazelcast architecture (ADR 008) |
| `7399a49` | 2026-02-05 | Enrich completion signal with timing metadata |
| `49e7035` | 2026-02-05 | Add inventory stock release methods for saga compensation |
| `214fea1` | 2026-02-05 | Add Grafana dashboards and fix cluster configuration |
| `56094d5` | 2026-02-05 | Update demo scripts for saga-driven order flow |
| `da78acf` | 2026-02-05 | Order service saga fixes |

## Files Changed

| File | Change |
|------|--------|
| `CLAUDE.md` | Modified -- Added dual-instance architecture warning (e9bf729) |
| `account-service/src/main/java/.../config/AccountServiceConfig.java` | Modified -- Added hazelcastClient(), @Primary, cluster isolation (971d72b, e9bf729) |
| `docs/architecture/adr/008-dual-instance-hazelcast-architecture.md` | Created -- ADR documenting the decision (e9bf729) |
| `docker/docker-compose.yml` | Modified -- Cluster isolation, env vars, Grafana config (971d72b, 214fea1) |
| `docker/grafana/dashboards/system-overview.json` | Created -- Pipeline throughput dashboard (214fea1) |
| `docker/grafana/dashboards/event-flow.json` | Created -- Event processing dashboard (214fea1) |
| `docker/grafana/dashboards/materialized-views.json` | Created -- View monitoring dashboard (214fea1) |
| `docker/grafana/dashboards/saga-dashboard.json` | Modified -- Updated for completion metrics (214fea1) |
| `docker/grafana/provisioning/dashboards/dashboards.yml` | Created -- Dashboard auto-provisioning (214fea1) |
| `docker/grafana/provisioning/datasources/datasources.yml` | Created -- Prometheus datasource (214fea1) |
| `docker/grafana/provisioning/alerting/alerts.yml` | Created -- Alert rules (214fea1) |
| `docker/grafana/provisioning/alerting/contactpoints.yml` | Created -- Alert contact points (214fea1) |
| `docker/grafana/provisioning/alerting/policies.yml` | Created -- Alert policies (214fea1) |
| `framework-core/src/main/java/.../controller/EventSourcingController.java` | Modified -- sharedHazelcast, republish, eventId completion (971d72b, e9bf729) |
| `framework-core/src/main/java/.../pipeline/EventSourcingPipeline.java` | Modified -- Stage 6 eventId key, timing metadata (971d72b, 7399a49) |
| `framework-core/src/main/java/.../pipeline/PipelineMetrics.java` | Modified -- Duration-based overloads (7399a49) |
| `framework-core/src/test/java/.../pipeline/PipelineMetricsTest.java` | Modified -- Updated for new method signatures (7399a49) |
| `inventory-service/src/main/java/.../config/InventoryServiceConfig.java` | Modified -- Dual-instance config, SagaStateStore bean (971d72b, e9bf729) |
| `inventory-service/src/main/java/.../saga/InventorySagaListener.java` | Modified -- @Qualifier("hazelcastClient") (e9bf729) |
| `inventory-service/src/main/java/.../service/InventoryService.java` | Modified -- releaseReservedStockForOrder() (49e7035) |
| `inventory-service/src/main/java/.../service/ProductService.java` | Modified -- Compensation method signatures (49e7035) |
| `order-service/src/main/java/.../config/OrderServiceConfig.java` | Modified -- Dual-instance config, SagaStateStore bean (971d72b, e9bf729) |
| `order-service/src/main/java/.../saga/OrderSagaListener.java` | Modified -- @Qualifier("hazelcastClient") (e9bf729) |
| `order-service/src/main/java/.../service/OrderService.java` | Modified -- Saga fixes (da78acf) |
| `ecommerce-common/src/main/java/.../common/domain/OrderLineItem.java` | Modified -- Minor fixes (da78acf) |
| `payment-service/Dockerfile` | Created -- Docker build config (971d72b) |
| `payment-service/src/main/java/.../config/PaymentServiceConfig.java` | Created then modified -- Full dual-instance config (971d72b, e9bf729) |
| `payment-service/src/main/java/.../saga/PaymentSagaListener.java` | Modified -- @Qualifier("hazelcastClient") (e9bf729) |
| `scripts/demo-scenarios.sh` | Modified -- Saga-driven flow, cancellation fix (a123ba8, 56094d5) |
| `scripts/load-sample-data.sh` | Modified -- Current API structure (56094d5) |
| `scripts/load-test.sh` | Modified -- Saga timing expectations (56094d5) |

## Total Stats (across all 8 commits)

Approximately 35+ files changed, ~3,700 lines inserted, ~300 lines deleted.
