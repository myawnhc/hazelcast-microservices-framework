# Phase 1 Day 12: Demo Scenarios & Jet Pipeline Serialization Fixes (Reconstructed)

> Reconstructed from git history and implementation plan. May not capture all details from the original session.

## Context
With Docker infrastructure in place from Day 11, Day 12 focused on creating demo scripts and sample data for demonstrating the system end-to-end. However, running the services in Docker exposed Jet pipeline serialization issues that had to be resolved first -- lambdas and service-specific classes could not serialize across the Hazelcast cluster. This made Day 12 both a demo creation day and a critical bug-fix day.

## What Was Built

### Demo Scripts
- `load-sample-data.sh` — shell script (284 lines) that loads sample customers, products, and orders via the REST APIs. Provides a realistic dataset for demonstrations.
- `demo-scenarios.sh` — comprehensive shell script (673 lines) implementing three demo scenarios:
  1. Happy path: create customer, create product, place order, query enriched order
  2. Order cancellation flow
  3. View rebuilding demonstration

### Demo Documentation
- `docs/demo/demo-walkthrough.md` — 473-line walkthrough guide explaining how to run each demo scenario with expected outputs and explanations.

### Jet Pipeline Serialization Fixes
The core work of Day 12 was fixing serialization issues that appeared when running Jet pipelines in a distributed environment:

- **`ViewUpdaterServiceCreator`** — new class using reflection to instantiate ViewUpdater instances on remote Jet nodes, avoiding lambda serialization of Spring-managed beans.
- **`EventStoreServiceCreator`** — new class for instantiating EventStore on remote Jet nodes via reflection.
- **`ViewUpdaterFactory`** — factory for creating ViewUpdater instances without requiring Spring context.
- **Moved ViewUpdaters to ecommerce-common** — `CustomerViewUpdater`, `ProductViewUpdater`, and `OrderViewUpdater` were moved from individual services into `ecommerce-common` so that all services could load these classes when Jet distributes pipeline stages.
- **`EventSourcingPipeline.java`** — significantly refactored (153 lines changed) to fix Stage 6 completion sink, avoiding `EventContext` serialization issues.

### Service Config Updates
- `AccountServiceConfig`, `InventoryServiceConfig`, and `OrderServiceConfig` all updated to use the common ViewUpdaters from `ecommerce-common`.
- Enabled Jet and event journals for all pending maps across the cluster.

### Controller Enhancement
- `EventSourcingController.java` — 17 lines added (likely additional methods or configuration for pipeline integration).

## Key Decisions
- **ViewUpdaters moved to ecommerce-common**: This was a significant architectural decision. Originally, each service had its own ViewUpdater. Moving them to the common module solved the class-loading problem when Jet distributes work across cluster members, but it also meant all services share the same ViewUpdater implementations.
- **Reflection-based service creators**: Rather than relying on Java serialization of Spring beans (which fails across JVMs), the solution uses reflection to instantiate pipeline service dependencies on the target node.
- **Stage 6 completion sink reworked**: The completion tracking stage was redesigned to avoid serializing `EventContext`, which contained non-serializable references.

## Test Results
No new test files were added in this commit. The fixes were validated through the demo scenarios running successfully in Docker.

## Files Changed
| File | Change |
|------|--------|
| `.gitignore` | Modified -- additional ignore patterns |
| `account-service/.../AccountServiceConfig.java` | Modified -- use common ViewUpdaters |
| `demo-data/.gitkeep` | Created -- placeholder for demo data directory |
| `docker/README.md` | Modified -- additional Docker documentation (71 lines added) |
| `docs/demo/demo-walkthrough.md` | Created -- demo walkthrough guide (473 lines) |
| `ecommerce-common/.../common/view/CustomerViewUpdater.java` | Created -- moved from account-service (142 lines) |
| `ecommerce-common/.../common/view/OrderViewUpdater.java` | Created -- moved from order-service (157 lines) |
| `ecommerce-common/.../common/view/ProductViewUpdater.java` | Created -- moved from inventory-service (155 lines) |
| `framework-core/.../controller/EventSourcingController.java` | Modified -- additional pipeline integration |
| `framework-core/.../pipeline/EventSourcingPipeline.java` | Modified -- major refactor for serialization fixes |
| `framework-core/.../store/EventStoreServiceCreator.java` | Created -- reflection-based EventStore instantiation |
| `framework-core/.../view/ViewUpdaterFactory.java` | Created -- factory for ViewUpdater instances |
| `framework-core/.../view/ViewUpdaterServiceCreator.java` | Created -- reflection-based ViewUpdater instantiation |
| `inventory-service/.../InventoryServiceConfig.java` | Modified -- use common ViewUpdaters |
| `order-service/.../OrderServiceConfig.java` | Modified -- use common ViewUpdaters |
| `scripts/demo-scenarios.sh` | Created -- demo scenario scripts (673 lines) |
| `scripts/load-sample-data.sh` | Created -- sample data loading script (284 lines) |

**Totals**: 17 files changed, 2,247 insertions, 127 deletions.
