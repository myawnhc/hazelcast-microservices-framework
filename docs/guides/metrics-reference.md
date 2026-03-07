# Metrics Reference Guide

Comprehensive catalog of every metric in the Hazelcast Microservices Framework, organized by subsystem.

## Overview

### How Metrics Flow

```
Java Service → Micrometer → Prometheus Actuator Endpoint → Prometheus Scrape → Grafana Dashboard
```

1. **Micrometer** (in-process): Metrics are registered via `MeterRegistry` in Java code
2. **Prometheus endpoint** (`/actuator/prometheus`): Spring Boot Actuator exposes metrics in Prometheus format
3. **Prometheus server**: Scrapes each service's `/actuator/prometheus` endpoint every 15 seconds
4. **Grafana**: Queries Prometheus using PromQL for dashboard visualization

### Naming Conventions

| Micrometer Name | Prometheus Name | Rule |
|-----------------|-----------------|------|
| `order.revenue` | `order_revenue_total` | Dots → underscores, `_total` suffix for counters |
| `saga.duration` | `saga_duration_seconds` | Timers get `_seconds` suffix |
| `saga.duration` (histogram) | `saga_duration_seconds_bucket` | Histogram buckets for quantile calculation |
| `sagas.active.count` | `sagas_active_count` | Gauges keep their name (no suffix) |

### Scrape Configuration

All services expose metrics on their HTTP port at `/actuator/prometheus`:

| Service | Port | Endpoint |
|---------|------|----------|
| Account Service | 8081 | `http://account-service:8081/actuator/prometheus` |
| Inventory Service | 8082 | `http://inventory-service:8082/actuator/prometheus` |
| Order Service | 8083 | `http://order-service:8083/actuator/prometheus` |
| Payment Service | 8084 | `http://payment-service:8084/actuator/prometheus` |
| API Gateway | 8080 | `http://api-gateway:8080/actuator/prometheus` |

### Global Tags

Every metric includes the tag `application={spring.application.name}` automatically (configured in `application.yml`).

---

## 1. Pipeline Metrics

Source: `com.theyawns.framework.pipeline.PipelineMetrics`

### Counters

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `eventsourcing.pipeline.events.submitted` | `eventsourcing_pipeline_events_submitted_total` | `domain`, `eventType` | Events submitted to the pipeline pending map |
| `eventsourcing.pipeline.events.received` | `eventsourcing_pipeline_events_received_total` | `domain`, `eventType` | Events received by the Jet pipeline from the event journal |
| `eventsourcing.pipeline.events.processed` | `eventsourcing_pipeline_events_processed_total` | `domain`, `eventType` | Events fully processed through all pipeline stages |
| `eventsourcing.pipeline.events.failed` | `eventsourcing_pipeline_events_failed_total` | `domain`, `eventType`, `stage` | Events that failed at a specific pipeline stage |
| `eventsourcing.pipeline.views.updated` | `eventsourcing_pipeline_views_updated_total` | `domain`, `eventType` | Materialized view updates completed |
| `eventsourcing.pipeline.events.published` | `eventsourcing_pipeline_events_published_total` | `domain`, `eventType` | Events published to outbox or ITopic |

### Timers

| Metric | Prometheus Name | Tags | Percentiles | Description |
|--------|-----------------|------|-------------|-------------|
| `eventsourcing.pipeline.stage.duration` | `eventsourcing_pipeline_stage_duration_seconds` | `domain`, `stage`, `eventType` | p50, p95, p99 | Duration of individual pipeline stages (persist, update_view, publish) |
| `eventsourcing.pipeline.latency.end_to_end` | `eventsourcing_pipeline_latency_end_to_end_seconds` | `domain`, `eventType` | p50, p95, p99 | Total time from event submission to pipeline completion |
| `eventsourcing.pipeline.latency.queue_wait` | `eventsourcing_pipeline_latency_queue_wait_seconds` | `domain`, `eventType` | p50, p95, p99 | Time event waited in pending map before Jet picked it up |

**Dashboard**: Event Flow, System Overview, Materialized Views

**Stage Values**: `persist`, `update_view`, `publish`, `complete`

**Troubleshooting**:
- No data → Pipeline not started (check `controller.start()` was called)
- High `queue_wait` → Jet pipeline backpressure or slow processing
- High `stage.duration` for `persist` → MapStore/database write latency

---

## 2. Event Sourcing Controller Metrics

Source: `com.theyawns.framework.controller.EventSourcingController`

### Gauges

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `eventsourcing.pending.completions` | `eventsourcing_pending_completions` | `domain` | Number of pending async completion handlers awaiting pipeline results |
| `eventsourcing.pending.events` | `eventsourcing_pending_events` | `domain` | Number of events in the pending map awaiting pipeline processing |
| `eventsourcing.completions.orphaned` | `eventsourcing_completions_orphaned` | `domain` | Completion handlers that had no matching pending event (indicates race condition) |

### Timers

| Metric | Prometheus Name | Tags | Percentiles | Description |
|--------|-----------------|------|-------------|-------------|
| `eventsourcing.itopic.publish.duration` | `eventsourcing_itopic_publish_duration_seconds` | `domain` | p50, p95, p99 | Time to publish an event to the shared cluster's ITopic |

**Dashboard**: System Overview

**Troubleshooting**:
- High `pending.events` → Pipeline is falling behind (check Jet job health)
- Growing `completions.orphaned` → Events completing before CompletableFuture is registered (timing issue)
- High `itopic.publish.duration` → Shared cluster is slow or unreachable

---

## 3. Saga Metrics

Source: `com.theyawns.framework.saga.SagaMetrics`, `SagaTimeoutDetector`, `DefaultSagaCompensator`

### Lifecycle Counters

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `saga.started` | `saga_started_total` | `sagaType` | Sagas initiated |
| `saga.completed` | `saga_completed_total` | `sagaType` | Sagas that completed successfully (all steps done) |
| `saga.compensated` | `saga_compensated_total` | `sagaType` | Sagas that triggered compensation (rollback) |
| `saga.failed` | `saga_failed_total` | `sagaType` | Sagas that failed without recovery |
| `saga.timedout` | `saga_timedout_total` | `sagaType` | Sagas that exceeded their timeout duration |

### Step Counters

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `saga.steps.completed` | `saga_steps_completed_total` | `sagaType` | Individual saga steps completed |
| `saga.steps.failed` | `saga_steps_failed_total` | `sagaType` | Individual saga steps that failed |
| `saga.compensation.steps` | `saga_compensation_steps_total` | `sagaType` | Compensation steps executed |
| `saga.compensation.started` | `saga_compensation_started_total` | `sagaType` | Compensation sequences started |
| `saga.compensation.completed` | `saga_compensation_completed_total` | `sagaType` | Compensation sequences completed |
| `saga.compensation.failed` | `saga_compensation_failed_total` | `sagaType` | Compensation sequences that failed |

### Gauges

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `saga.started.total` | `saga_started_total` | — | Running total of sagas started (gauge mirror) |
| `saga.completed.total` | `saga_completed_total` | — | Running total completed |
| `saga.compensated.total` | `saga_compensated_total` | — | Running total compensated |
| `saga.failed.total` | `saga_failed_total` | — | Running total failed |
| `saga.timedout.total` | `saga_timedout_total` | — | Running total timed out |
| `sagas.active.count` | `sagas_active_count` | — | Current number of in-flight sagas |
| `sagas.compensating.count` | `sagas_compensating_count` | — | Current number of sagas in compensation |

### Timers

| Metric | Prometheus Name | Tags | Percentiles | Description |
|--------|-----------------|------|-------------|-------------|
| `saga.duration` | `saga_duration_seconds` | `sagaType` | p50, p95, p99 | Total saga duration (start → complete/compensate) |
| `saga.compensation.duration` | `saga_compensation_duration_seconds` | `sagaType` | p50, p95, p99 | Duration of the compensation sequence |
| `saga.step.duration` | `saga_step_duration_seconds` | `sagaType`, `stepName` | p50, p95, p99 | Duration of individual saga steps |

### Timeout Detection

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `saga.timeouts.detected` | `saga_timeouts_detected_total` | — | Sagas detected as timed out by the detector |
| `saga.timeouts.compensations.triggered` | `saga_timeouts_compensations_triggered_total` | — | Compensation sequences triggered by timeout |
| `saga.purge.removed` | `saga_purge_removed_total` | — | Timed-out saga records purged from state store |
| `saga.timeout.check.duration` | `saga_timeout_check_duration_seconds` | — | Duration of the timeout detection check cycle |
| `saga.timeout.detector.running` | `saga_timeout_detector_running` | — | Is timeout detector currently running (0/1) |
| `saga.timeouts.total` | `saga_timeouts_total` | — | Total timeouts detected (gauge) |

**Dashboard**: Saga Dashboard, Business Overview (success rate, duration)

**sagaType Values**: `ORDER_FULFILLMENT` (currently the only saga type)

**stepName Values**: `OrderCreated`, `StockReserved`, `PaymentProcessed`, `OrderConfirmed`

**Troubleshooting**:
- No saga data → Check that saga listeners are registered and `sagaStateStore` bean exists
- High `sagas.active.count` → Sagas not completing; check downstream services and timeouts
- High `saga.timedout` → Increase `SAGA_TIMEOUT` or investigate slow services

---

## 4. Outbox Metrics

Source: `com.theyawns.framework.outbox.OutboxStore`, `OutboxPublisher`

### Counters

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `outbox.entries.written` | `outbox_entries_written_total` | — | Events appended to the outbox IMap |
| `outbox.entries.claimed` | `outbox_entries_claimed_total` | — | Entries claimed by the outbox publisher for delivery |
| `outbox.claims.released` | `outbox_claims_released_total` | — | Claims released back (delivery failure, re-queued) |
| `outbox.poll.empty` | `outbox_poll_empty_total` | — | Publisher poll cycles with no entries to deliver |
| `outbox.entries.failed` | `outbox_entries_failed_total` | — | Entries that failed delivery (moved to DLQ after retries) |
| `outbox.entries.delivered` | `outbox_entries_delivered_total` | — | Entries successfully delivered to ITopic |

### Timers

| Metric | Prometheus Name | Tags | Percentiles | Description |
|--------|-----------------|------|-------------|-------------|
| `outbox.publish.duration` | `outbox_publish_duration_seconds` | — | — | Duration of one outbox publish cycle |

**Dashboard**: Saga Dashboard (outbox section)

**Troubleshooting**:
- `entries.failed` increasing → Check shared cluster connectivity
- High `poll.empty` → Normal when idle; concerning under load (events not reaching outbox)
- `entries.written` > `entries.delivered` → Delivery lag; check `outbox.publish.duration`

---

## 5. Dead Letter Queue Metrics

Source: `com.theyawns.framework.dlq.HazelcastDeadLetterQueue`

### Counters

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `dlq.entries.added` | `dlq_entries_added_total` | — | Events moved to the dead letter queue |
| `dlq.entries.replayed` | `dlq_entries_replayed_total` | — | DLQ entries replayed for retry |
| `dlq.entries.discarded` | `dlq_entries_discarded_total` | — | DLQ entries permanently discarded |

**Dashboard**: Saga Dashboard (DLQ section)

**Troubleshooting**:
- `dlq.entries.added` increasing → Events failing repeatedly; check error logs
- `dlq.entries.replayed` = 0 → No replay mechanism configured or invoked

---

## 6. Idempotency Metrics

Source: `com.theyawns.framework.idempotency.HazelcastIdempotencyGuard`

### Counters

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `idempotency.checks` | `idempotency_checks_total` | `result` (hit/miss) | Duplicate detection checks: `hit` = duplicate blocked, `miss` = new event processed |

**Dashboard**: Saga Dashboard (idempotency section)

**Troubleshooting**:
- High `result=hit` ratio → Duplicate events being sent (check outbox at-least-once delivery)
- Only `result=miss` → Idempotency guard working but no duplicates detected (normal)

---

## 7. Persistence Metrics

Source: `com.theyawns.framework.persistence.PersistenceMetrics`

### Counters

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `persistence.store.count` | `persistence_store_count_total` | `mapName`, `storeType` | Individual store (write) operations |
| `persistence.store.batch.count` | `persistence_store_batch_count_total` | `mapName`, `storeType` | Batch write operations |
| `persistence.store.batch.entries` | `persistence_store_batch_entries_total` | `mapName`, `storeType` | Total entries written across batches |
| `persistence.load.count` | `persistence_load_count_total` | `mapName` | Load (read) operations that found data |
| `persistence.load.miss` | `persistence_load_miss_total` | `mapName` | Load operations that missed (no data in DB) |
| `persistence.delete.count` | `persistence_delete_count_total` | `mapName` | Delete operations |
| `persistence.errors` | `persistence_errors_total` | `mapName`, `operation` | Persistence layer errors |

### Timers

| Metric | Prometheus Name | Tags | Percentiles | Description |
|--------|-----------------|------|-------------|-------------|
| `persistence.store.duration` | `persistence_store_duration_seconds` | `mapName`, `storeType` | p50, p95, p99 | Individual store operation latency |
| `persistence.store.batch.duration` | `persistence_store_batch_duration_seconds` | `mapName`, `storeType` | p50, p95, p99 | Batch store operation latency |
| `persistence.load.duration` | `persistence_load_duration_seconds` | `mapName` | p50, p95, p99 | Load operation latency |

**Dashboard**: Persistence Layer

**storeType Values**: `event` (event store), `view` (materialized view)

**mapName Values**: `Customer_ES`, `Customer_VIEW`, `Order_ES`, `Order_VIEW`, `Product_ES`, `Product_VIEW`

**operation Values** (for errors): `store`, `load`, `delete`

**Troubleshooting**:
- No persistence data → `FRAMEWORK_PERSISTENCE_ENABLED=false` (demo mode) or no PostgreSQL
- High `store.duration` → Database write latency; check connection pool and DB health
- Growing `errors` → Check database connectivity and schema migrations

---

## 8. Resilience Metrics

Source: `com.theyawns.framework.resilience.RetryEventListener`, Resilience4j auto-configuration

### Framework Counters

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `framework.resilience.retry.ignored` | `framework_resilience_retry_ignored_total` | — | Non-retryable exceptions ignored by retry logic |

### Resilience4j Auto-Metrics (Circuit Breaker)

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| (auto) | `resilience4j_circuitbreaker_state` | `name` | Circuit breaker state (0=CLOSED, 1=OPEN, 2=HALF_OPEN) |
| (auto) | `resilience4j_circuitbreaker_failure_rate` | `name` | Current failure rate percentage |
| (auto) | `resilience4j_circuitbreaker_calls_total` | `name`, `kind` | Calls by outcome: successful, failed, not_permitted |

### Resilience4j Auto-Metrics (Retry)

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| (auto) | `resilience4j_retry_calls_total` | `name`, `kind` | Retry outcomes: successful_without_retry, successful_with_retry, failed_with_retry |

**Dashboard**: Saga Dashboard (resilience section)

**Circuit Breaker Names**: `inventory-stock-reservation`, `inventory-stock-release`, `payment-processing`, `payment-refund`, `order-confirmation`, `order-cancellation`

**Troubleshooting**:
- Circuit breaker OPEN → Downstream service failing; check service health
- High `failed_with_retry` → Transient failures being retried; may indicate network instability

---

## 9. Business Metrics

Source: `com.theyawns.ecommerce.order.service.OrderService`, `com.theyawns.ecommerce.account.config.AccountServiceConfig`

### Order Metrics (Counters)

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `order.revenue` | `order_revenue_total` | — | Cumulative revenue from created orders (sum of unitPrice * quantity) |
| `order.items.count` | `order_items_count_total` | — | Cumulative count of line items across all orders |
| `order.items.quantity` | `order_items_quantity_total` | — | Cumulative total quantity of items ordered |

### Account Metrics (Gauges)

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `account.customers.total` | `account_customers_total` | — | Current number of customers in the materialized view |

**Dashboard**: Business Overview

**Troubleshooting**:
- `order.revenue` = 0 → Orders have null `unitPrice`; check product availability cache
- `account.customers.total` = 0 → Customer view map is empty; create customers first

---

## 10. Inventory Metrics

Source: `com.theyawns.ecommerce.inventory.service.StockReplenishmentMonitor`

### Counters

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `inventory.stock.replenished` | `inventory_stock_replenished_total` | — | Auto-replenishment events triggered when stock falls below threshold |

**Dashboard**: None (available for custom dashboards)

---

## 11. API Gateway Metrics

Source: `com.theyawns.ecommerce.gateway.filter.RequestTimingFilter`

### Timers

| Metric | Prometheus Name | Tags | Percentiles | Description |
|--------|-----------------|------|-------------|-------------|
| `gateway.request.duration` | `gateway_request_duration_seconds` | `method`, `path`, `status` | — | End-to-end gateway request latency |

**Dashboard**: None (available for custom dashboards)

**Tag Values**:
- `method`: GET, POST, PUT, DELETE
- `path`: Normalized URI path (e.g., `/api/orders`, `/api/customers`)
- `status`: HTTP status code (200, 201, 400, 404, 500)

---

## 12. Spring Boot Auto-Configured Metrics

These metrics are provided by Spring Boot Actuator automatically.

### HTTP Server Metrics

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| (auto) | `http_server_requests_seconds_count` | `method`, `uri`, `status`, `application` | Total HTTP request count |
| (auto) | `http_server_requests_seconds_sum` | `method`, `uri`, `status`, `application` | Total HTTP request duration |
| (auto) | `http_server_requests_seconds_bucket` | `method`, `uri`, `status`, `application`, `le` | HTTP request duration histogram buckets |

**Dashboard**: System Overview, Business Overview

### JVM Metrics

| Metric | Prometheus Name | Description |
|--------|-----------------|-------------|
| (auto) | `jvm_memory_used_bytes` | JVM memory usage |
| (auto) | `jvm_gc_pause_seconds` | GC pause durations |
| (auto) | `jvm_threads_live_threads` | Live thread count |
| (auto) | `process_cpu_usage` | Process CPU utilization |

### Service Health

| Metric | Prometheus Name | Description |
|--------|-----------------|-------------|
| (auto) | `up` | Service availability (1 = up, 0 = down) |

---

## Mode Behavior Summary

Which metric categories are active in each operational mode:

| Metric Category | DEMO | PRODUCTION | PERF_TEST |
|----------------|------|------------|-----------|
| Pipeline (events, latency) | Active | Active | Active |
| Saga (lifecycle, duration) | Active | Active | Active |
| Business (revenue, customers) | Active | Active | Active |
| Outbox (delivery, failures) | Active | Active | Active |
| DLQ (added, replayed) | Active | Active | Active |
| Idempotency (checks) | Active | Active | Active |
| Resilience (circuit breaker) | Active | Active | Active |
| Persistence (store, load) | Inactive | Active | Active |
| Tracing (OTEL spans) | Inactive | 10% sampled | 1% sampled |
| HTTP Server (auto) | Active | Active | Active |
| JVM (auto) | Active | Active | Active |
| Gateway (request duration) | Active | Active | Active |
| Inventory (replenishment) | Active | Active | Active |

**Key Differences**:
- **DEMO**: No persistence (PostgreSQL disabled), no tracing — all Hazelcast-only metrics still active
- **PRODUCTION**: Full persistence + 10% trace sampling — all metrics active
- **PERF_TEST**: Full persistence, 1% trace sampling, rate limiting disabled

---

## Dashboard Reference

Which dashboards display which metrics:

| Dashboard | Key Metrics |
|-----------|-------------|
| **Business Overview** | `order_revenue_total`, `account_customers_total`, `saga_completed_total`, `saga_compensated_total`, `http_server_requests_seconds_count`, `saga_duration_seconds_bucket` |
| **Event Flow** | `eventsourcing_pipeline_events_*_total`, `eventsourcing_pipeline_latency_*_seconds`, `eventsourcing_pipeline_stage_duration_seconds` |
| **Saga Dashboard** | `saga_*_total`, `saga_duration_seconds`, `sagas_active_count`, `resilience4j_*`, `outbox_*_total`, `dlq_*_total`, `idempotency_checks_total` |
| **System Overview** | `up`, `http_server_requests_seconds_*`, `eventsourcing_pipeline_*`, `saga_duration_seconds` |
| **Materialized Views** | `eventsourcing_pipeline_views_updated_total`, `eventsourcing_pipeline_latency_end_to_end_seconds`, `eventsourcing_pipeline_stage_duration_seconds` |
| **Persistence Layer** | `persistence_*_total`, `persistence_*_seconds` |
| **Performance Testing** | `http_server_requests_seconds_*`, `eventsourcing_pipeline_latency_*`, `saga_duration_seconds` |

---

## Adding Custom Metrics

To add a new metric to the framework:

```java
// 1. Inject MeterRegistry
@Autowired
private MeterRegistry meterRegistry;

// 2. Register a counter
meterRegistry.counter("my.custom.metric", "tag1", "value1").increment();

// 3. Register a gauge
Gauge.builder("my.gauge.metric", myObject, MyObject::getValue)
    .description("Description here")
    .register(meterRegistry);

// 4. Register a timer
Timer.builder("my.timer.metric")
    .publishPercentiles(0.5, 0.95, 0.99)
    .publishPercentileHistogram()
    .register(meterRegistry)
    .record(() -> doWork());
```

The metric will automatically appear at `/actuator/prometheus` and can be queried in Grafana.
