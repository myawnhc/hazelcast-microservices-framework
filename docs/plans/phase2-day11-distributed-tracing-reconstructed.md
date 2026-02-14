# Phase 2 Day 11: Distributed Tracing, Pipeline Bug Fixes & Management Center (Reconstructed)

> Reconstructed from git history and implementation plan. May not capture all details from the original session.

## Context

Day 11 was planned as a pure distributed tracing setup day, but also became a critical bug-fix day. Two major pipeline issues were discovered and fixed: Stage 5 was not actually publishing events to event-type ITopic (breaking saga listener delivery), and the EventSourcingController was completing futures immediately rather than waiting for pipeline completion. Additionally, Jaeger, Management Center, and the payment-service were added to Docker Compose.

## What Was Built

### Distributed Tracing
- `TracingConfig`: Spring configuration for Micrometer Tracing with OpenTelemetry bridge
- `EventSpanDecorator`: Creates custom spans for event processing with event.id, event.type, correlation.id, and saga.id attributes
- Tracing spans added to EventSourcingController and all saga listeners (Inventory, Order, Payment)
- OpenTelemetry dependencies added to framework-core and root pom.xml

### Pipeline Bug Fixes
- **Stage 5 fix**: Created `EventTopicPublisherServiceCreator` to actually publish processed events to event-type-specific ITopic channels, enabling saga listener delivery
- **EventSourcingController fix**: Changed from completing futures immediately to waiting for pipeline completion via EntryAddedListener on the completions map

### Docker Infrastructure
- Jaeger all-in-one container added to docker-compose.yml (ports 16686 UI, 4317 OTLP gRPC)
- Hazelcast Management Center added to docker-compose.yml (port 8080)
- Payment-service added to docker-compose.yml
- Prometheus configuration updated to scrape all four services
- Application.yml files updated across all services for tracing configuration

## Key Decisions

- **Micrometer Tracing over direct OpenTelemetry SDK**: Uses Micrometer as the facade with OpenTelemetry as the bridge, consistent with Spring Boot ecosystem patterns.
- **EventTopicPublisherServiceCreator for Stage 5**: Rather than inline ITopic publishing, a ServiceCreator pattern is used so the Hazelcast instance is properly obtained within the Jet pipeline context.
- **EntryAddedListener for completion tracking**: Controller registers an entry listener on the completions map so it is notified when the pipeline finishes processing each event, rather than polling or completing optimistically.
- **Management Center for operational visibility**: Added alongside Jaeger to provide Hazelcast-specific monitoring (cluster health, map sizes, pipeline jobs).

## Test Coverage

EventSpanDecoratorTest added with tests for span creation and attribute verification.

## Files Changed

| File | Change |
|------|--------|
| `framework-core/pom.xml` | Modified -- Added OpenTelemetry and Micrometer Tracing dependencies |
| `framework-core/src/main/java/com/theyawns/framework/config/TracingConfig.java` | Created -- Spring tracing configuration |
| `framework-core/src/main/java/com/theyawns/framework/tracing/EventSpanDecorator.java` | Created -- Custom span creation for events |
| `framework-core/src/main/java/com/theyawns/framework/controller/EventSourcingController.java` | Modified -- Wait for pipeline completion via EntryAddedListener |
| `framework-core/src/main/java/com/theyawns/framework/pipeline/EventSourcingPipeline.java` | Modified -- Stage 5 publishes to event-type ITopic |
| `framework-core/src/main/java/com/theyawns/framework/pipeline/EventTopicPublisherServiceCreator.java` | Created -- Service creator for ITopic publishing in Jet pipeline |
| `framework-core/src/test/java/com/theyawns/framework/tracing/EventSpanDecoratorTest.java` | Created -- Span decorator tests |
| `account-service/src/main/resources/application.yml` | Modified -- Added tracing configuration |
| `inventory-service/src/main/java/.../inventory/saga/InventorySagaListener.java` | Modified -- Added tracing spans |
| `inventory-service/src/main/resources/application.yml` | Modified -- Added tracing configuration |
| `order-service/src/main/java/.../order/saga/OrderSagaListener.java` | Modified -- Added tracing spans |
| `order-service/src/main/resources/application.yml` | Modified -- Added tracing configuration |
| `payment-service/src/main/java/.../payment/saga/PaymentSagaListener.java` | Modified -- Added tracing spans |
| `payment-service/src/main/resources/application.yml` | Modified -- Added tracing configuration |
| `docker/docker-compose.yml` | Modified -- Added Jaeger, Management Center, payment-service |
| `docker/prometheus/prometheus.yml` | Modified -- Added scrape targets for all services |
| `docker/README.md` | Modified -- Updated documentation |
| `docs/SETUP.md` | Modified -- Updated setup instructions |
| `docs/implementation/phase2-implementation-plan.md` | Modified -- Added notes |
| `pom.xml` | Modified -- Added OpenTelemetry BOM and dependencies |

## Commit

- **Hash**: `5e6570a`
- **Date**: 2026-02-03
- **Stats**: 20 files changed, 993 insertions(+), 32 deletions(-)
