# Metrics Inventory -- Hazelcast Microservices Framework

> Comprehensive catalog of all Micrometer metrics registered across the framework.
> Last updated: 2026-02-17

---

## Summary

| Component | Source File | Counters | Gauges | Timers | Total |
|-----------|------------|----------|--------|--------|-------|
| Pipeline Metrics | `PipelineMetrics.java` | 6 | 0 | 3 | 9 |
| Saga Metrics | `SagaMetrics.java` | 11 | 7 | 3 | 21 |
| Persistence Metrics | `PersistenceMetrics.java` | 6 | 0 | 3 | 9 |
| Gateway Metrics | `RequestTimingFilter.java` | 0 | 0 | 1 | 1 |
| Controller Inline Metrics | `EventSourcingController.java` | 2 | 3 | 1 | 6 |
| JVM / System Metrics | `MetricsConfig.java` | -- | -- | -- | auto |
| **Total (application-level)** | | **25** | **10** | **11** | **46** |

---

## 1. Pipeline Metrics (`PipelineMetrics.java`)

**Source:** `framework-core/src/main/java/com/theyawns/framework/pipeline/PipelineMetrics.java`
**Prefix:** `eventsourcing.pipeline` (most counters); `eventsourcing.events` (submitted counter)
**Instantiated by:** `EventSourcingController` constructor (one instance per domain)

### Counters

| Metric Name | Tags | Description |
|-------------|------|-------------|
| `eventsourcing.events.submitted` | `domain`, `eventType` | Events submitted to the controller for processing. NOTE: This counter name does **not** carry the `eventsourcing.pipeline` prefix -- it uses `eventsourcing.events` instead. |
| `eventsourcing.pipeline.events.processed` | `domain`, `eventType` | Events that completed all pipeline stages successfully. |
| `eventsourcing.pipeline.events.failed` | `domain`, `eventType`, `stage` | Events that failed at a specific pipeline stage. Stage values: `source`, `enrich`, `persist`, `update_view`, `publish`, `complete`. |
| `eventsourcing.pipeline.events.received` | `domain`, `eventType` | Events that entered the pipeline (picked up by the Jet source). |
| `eventsourcing.pipeline.views.updated` | `domain`, `eventType` | Materialized view updates triggered by events. |
| `eventsourcing.pipeline.events.published` | `domain`, `eventType` | Events published to subscribers (ITopic or event bus). |

### Timers (with p50 / p95 / p99 percentiles and histogram)

| Metric Name | Tags | Description |
|-------------|------|-------------|
| `eventsourcing.pipeline.stage.duration` | `domain`, `stage`, `eventType` | Duration of individual pipeline stages. Stage values match `PipelineStage` enum: `source`, `enrich`, `persist`, `update_view`, `publish`, `complete`. |
| `eventsourcing.pipeline.latency.end_to_end` | `domain`, `eventType` | Total wall-clock time from event submission (`submittedAt`) to pipeline completion (`completionMs`). |
| `eventsourcing.pipeline.latency.queue_wait` | `domain`, `eventType` | Time spent waiting in the pending events map before the pipeline picks up the event (`pipelineEntryMs - submittedAt`). |

---

## 2. Saga Metrics (`SagaMetrics.java`)

**Source:** `framework-core/src/main/java/com/theyawns/framework/saga/SagaMetrics.java`
**Prefix:** `saga` (counters/timers); `sagas` (active gauges)

### Counters (tagged)

| Metric Name | Tags | Description |
|-------------|------|-------------|
| `saga.started` | `sagaType` | Saga instances started. |
| `saga.completed` | `sagaType` | Saga instances completed successfully. |
| `saga.compensated` | `sagaType` | Saga instances that finished compensation. |
| `saga.failed` | `sagaType` | Saga instances that failed (unrecoverable). |
| `saga.timedout` | `sagaType` | Saga instances that exceeded their timeout. |
| `saga.steps.completed` | `sagaType` | Individual saga steps completed. |
| `saga.steps.failed` | `sagaType` | Individual saga steps that failed. |
| `saga.compensation.started` | `sagaType` | Compensation processes initiated. |
| `saga.compensation.completed` | `sagaType` | Compensation processes finished successfully. |
| `saga.compensation.failed` | `sagaType` | Compensation processes that failed. |
| `saga.compensation.steps` | `sagaType` | Individual compensation steps executed. |

### Gauges (cumulative totals, no tags)

| Metric Name | Backing Field | Description |
|-------------|---------------|-------------|
| `saga.started.total` | `AtomicLong sagasStartedTotal` | Cumulative count of all sagas started (monotonically increasing). |
| `saga.completed.total` | `AtomicLong sagasCompletedTotal` | Cumulative count of all sagas completed. |
| `saga.compensated.total` | `AtomicLong sagasCompensatedTotal` | Cumulative count of all sagas compensated. |
| `saga.failed.total` | `AtomicLong sagasFailedTotal` | Cumulative count of all sagas failed. |
| `saga.timedout.total` | `AtomicLong sagasTimedOutTotal` | Cumulative count of all sagas timed out. |

### Gauges (instantaneous, no tags)

| Metric Name | Backing Field | Description |
|-------------|---------------|-------------|
| `sagas.active.count` | `AtomicLong sagasActiveCount` | Current number of in-progress sagas (incremented on start, decremented on complete/fail/timeout/compensated). |
| `sagas.compensating.count` | `AtomicLong sagasCompensatingCount` | Current number of sagas undergoing compensation (incremented on compensation started, decremented on saga compensated). |

### Timers (with p50 / p95 / p99 percentiles)

| Metric Name | Tags | Description |
|-------------|------|-------------|
| `saga.duration` | `sagaType` | Overall duration of a saga from start to completion. |
| `saga.step.duration` | `sagaType`, `stepName` | Duration of an individual saga step. |
| `saga.compensation.duration` | `sagaType` | Duration of the compensation process for a saga. |

---

## 3. Persistence Metrics (`PersistenceMetrics.java`)

**Source:** `framework-core/src/main/java/com/theyawns/framework/persistence/PersistenceMetrics.java`
**Prefix:** `persistence`
**Used by:** Write-behind MapStore implementations for PostgreSQL persistence.

### Counters

| Metric Name | Tags | Description |
|-------------|------|-------------|
| `persistence.store.count` | `mapName`, `storeType` | Single write (store) operations. `storeType` is `"event"` or `"view"`. |
| `persistence.store.batch.count` | `mapName`, `storeType` | Batch write operations invoked by Hazelcast write-behind. |
| `persistence.store.batch.entries` | `mapName`, `storeType` | Total number of entries written across all batches (incremented by `batchSize` per call). |
| `persistence.load.count` | `mapName` | Load (read) operations from the backing store. |
| `persistence.load.miss` | `mapName` | Load misses -- key not found in the backing store. |
| `persistence.delete.count` | `mapName` | Delete operations on the backing store. |
| `persistence.errors` | `mapName`, `operation` | Errors by operation type (e.g., `"store"`, `"load"`, `"delete"`). |

### Timers (with p50 / p95 / p99 percentiles)

| Metric Name | Tags | Description |
|-------------|------|-------------|
| `persistence.store.duration` | `mapName`, `storeType` | Duration of single write operations. |
| `persistence.store.batch.duration` | `mapName`, `storeType` | Duration of batch write operations. |
| `persistence.load.duration` | `mapName` | Duration of load (read) operations. |

---

## 4. Gateway Metrics (`RequestTimingFilter.java`)

**Source:** `api-gateway/src/main/java/com/theyawns/ecommerce/gateway/filter/RequestTimingFilter.java`
**Filter order:** -3 (runs after correlation ID filter, before rate limit filter)

### Timers

| Metric Name | Tags | Description |
|-------------|------|-------------|
| `gateway.request.duration` | `method`, `path`, `status` | Full round-trip duration for each HTTP request through the API gateway. `path` is normalized (UUIDs and numeric IDs replaced with `{id}` to control cardinality). Also sets `X-Response-Time` response header. |

---

## 5. Controller Inline Metrics (`EventSourcingController.java`)

**Source:** `framework-core/src/main/java/com/theyawns/framework/controller/EventSourcingController.java`
**Registered via:** Direct `meterRegistry` calls in constructor and `handleEvent()`.

### Counters

| Metric Name | Tags | Description |
|-------------|------|-------------|
| `eventsourcing.events.submitted` | `eventType`, `domain` | Events submitted to the controller. **NOTE:** This is the same metric name as `PipelineMetrics.recordEventSubmitted()`. Both register counters with the same name and tags, so they will merge into the same Micrometer counter if called for the same domain/eventType combination. In practice, `handleEvent()` increments this directly, and `PipelineMetrics.recordEventSubmitted()` is also available but may not be called separately. |
| `eventsourcing.events.failed` | `eventType`, `domain` | Events that failed during `handleEvent()` submission (before entering the pipeline). Captures exceptions in event metadata setup, sequence assignment, or pending map write. |

### Gauges

| Metric Name | Tags | Description |
|-------------|------|-------------|
| `eventsourcing.pending.completions` | `domain` | Size of the `pendingCompletions` ConcurrentHashMap. Indicates how many events are waiting for pipeline completion. Growth indicates pipeline backpressure. |
| `eventsourcing.pending.events` | `domain` | Size of the `{domain}_PENDING` IMap. Events waiting to be picked up by the Jet pipeline. |
| `eventsourcing.completions.orphaned` | `domain` | Cumulative count of pipeline completions received without a matching `pendingCompletions` entry. Indicates late completions (after 30s timeout). |

### Timers (with p50 / p95 / p99 percentiles)

| Metric Name | Tags | Description |
|-------------|------|-------------|
| `eventsourcing.itopic.publish.duration` | `domain` | Duration of ITopic publish operations to the shared cluster for cross-service event propagation. Only measured on the direct publish path (when outbox is disabled). |

---

## 6. JVM / System Metrics (`MetricsConfig.java`)

**Source:** `framework-core/src/main/java/com/theyawns/framework/config/MetricsConfig.java`
**Condition:** `@ConditionalOnClass(MeterRegistry.class)` -- active whenever Micrometer is on the classpath.

### Common Tags (applied to ALL metrics)

| Tag | Source | Description |
|-----|--------|-------------|
| `application` | `spring.application.name` (default: `event-sourcing-framework`) | Identifies the service. |
| `version` | `spring.application.version` (default: `1.0.0`) | Application version. |

### Auto-Registered Metric Binders

| Bean | Micrometer Binder | Key Metrics Exposed |
|------|-------------------|---------------------|
| `jvmMemoryMetrics()` | `JvmMemoryMetrics` | `jvm.memory.used`, `jvm.memory.committed`, `jvm.memory.max`, `jvm.buffer.memory.used`, `jvm.buffer.count`, `jvm.buffer.total.capacity` (tagged by `area`: heap/non-heap, `id`: memory pool name) |
| `jvmGcMetrics()` | `JvmGcMetrics` | `jvm.gc.pause` (timer), `jvm.gc.memory.allocated`, `jvm.gc.memory.promoted`, `jvm.gc.max.data.size`, `jvm.gc.live.data.size` |
| `jvmThreadMetrics()` | `JvmThreadMetrics` | `jvm.threads.live`, `jvm.threads.daemon`, `jvm.threads.peak`, `jvm.threads.states` (tagged by `state`) |
| `classLoaderMetrics()` | `ClassLoaderMetrics` | `jvm.classes.loaded`, `jvm.classes.unloaded` |
| `processorMetrics()` | `ProcessorMetrics` | `system.cpu.count`, `system.cpu.usage`, `process.cpu.usage` |

---

## 7. Grafana Dashboards

Five pre-built dashboards are provided under `docker/grafana/dashboards/`. The Kubernetes monitoring chart also ships copies under `k8s/hazelcast-microservices/charts/monitoring/dashboards/`.

| Dashboard | File | Metrics Referenced |
|-----------|------|--------------------|
| System Overview | `system-overview.json` | `jvm.memory.*`, `jvm.gc.*`, `jvm.threads.*`, `system.cpu.*`, `process.cpu.*` |
| Event Flow | `event-flow.json` | `eventsourcing.events.submitted`, `eventsourcing.pipeline.events.processed`, `eventsourcing.pipeline.events.failed`, `eventsourcing.pipeline.stage.duration`, `eventsourcing.pipeline.latency.end_to_end`, `eventsourcing.pipeline.latency.queue_wait` |
| Saga Dashboard | `saga-dashboard.json` | `saga.started`, `saga.completed`, `saga.compensated`, `saga.failed`, `saga.timedout`, `sagas.active.count`, `sagas.compensating.count`, `saga.duration`, `saga.step.duration`, `saga.compensation.duration` |
| Materialized Views | `materialized-views.json` | `eventsourcing.pipeline.views.updated`, `eventsourcing.pipeline.events.published` |
| Persistence Dashboard | `persistence-dashboard.json` | `persistence.store.count`, `persistence.store.batch.count`, `persistence.store.batch.entries`, `persistence.store.duration`, `persistence.store.batch.duration`, `persistence.load.count`, `persistence.load.miss`, `persistence.load.duration`, `persistence.errors` |

---

## 8. Prometheus Scrape Configuration

**Source:** `docker/prometheus/prometheus.yml`
**Global scrape interval:** 15s
**External label:** `monitor: ecommerce-demo`

| Job Name | Target(s) | Metrics Path | Notes |
|----------|-----------|--------------|-------|
| `prometheus` | `localhost:9090` | `/metrics` (default) | Prometheus self-monitoring. |
| `account-service` | `account-service:8081` | `/actuator/prometheus` | Relabeled instance: `account-service`. |
| `inventory-service` | `inventory-service:8082` | `/actuator/prometheus` | Relabeled instance: `inventory-service`. |
| `order-service` | `order-service:8083` | `/actuator/prometheus` | Relabeled instance: `order-service`. |
| `payment-service` | `payment-service:8084` | `/actuator/prometheus` | Relabeled instance: `payment-service`. |
| `api-gateway` | `api-gateway:8080` | `/actuator/prometheus` | Relabeled instance: `api-gateway`. |
| `hazelcast` | `hazelcast-1:5701`, `hazelcast-2:5701`, `hazelcast-3:5701` | `/hazelcast/rest/cluster` | Hazelcast REST API. NOTE: This endpoint returns cluster state JSON, not Prometheus-format metrics. Hazelcast metrics export requires enabling `metrics.jmx` or `metrics.management-center` in Hazelcast config for Prometheus-compatible scraping. |

---

## 9. Identified Gaps

The following metrics are not currently instrumented but would improve observability.

| # | Gap | Category | Description | Priority | Status |
|---|-----|----------|-------------|----------|--------|
| 1 | Events in flight | Gauge | No gauge for `pendingEventsMap.size()` or `pendingCompletions.size()` in `EventSourcingController`. | High | **RESOLVED** — `eventsourcing.pending.events` and `eventsourcing.pending.completions` gauges added |
| 2 | Orphaned completions | Counter | No counter for completion map entries received without a matching `pendingCompletions` entry. | Medium | **RESOLVED** — `eventsourcing.completions.orphaned` gauge added |
| 3 | IMap operation duration | Timer | No timer wrapping individual `IMap.set()`, `IMap.get()`, or `IMap.executeOnKey()` calls. | Medium | Deferred — pipeline stage timers already cover the hot path; add targeted timers if profiling reveals IMap-level bottlenecks |
| 4 | ITopic publish duration | Timer | No timer for `sharedHazelcast.getTopic().publish()` in the cross-cluster republish path. | Medium | **RESOLVED** — `eventsourcing.itopic.publish.duration` timer added |
| 5 | Concurrent requests gauge | Gauge | No gauge for in-flight HTTP requests at the API gateway. | Low | Open |
| 6 | Event journal fill ratio | Gauge | No metric for how full the event journal is relative to its configured capacity. | High | Deferred — no clean public API in Hazelcast 5.x to read journal fill level |
| 7 | pendingCompletions map size | Gauge | `ConcurrentHashMap` with 30-second timeout but no size gauge. | High | **RESOLVED** — `eventsourcing.pending.completions` gauge (same as gap #1) |
| 8 | Outbox delivery latency | Timer | No timer from outbox entry write to ITopic publish. | Medium | Open |
| 9 | Near cache metrics | Gauge/Counter | Near cache hit/miss/eviction metrics not exposed to Micrometer. | Low | Open |
| 10 | Serialization timing | Timer | No measurement of `toGenericRecord()` / `fromGenericRecord()` duration. Compact serialization is fast but serialization overhead in the hot path is invisible. | Low |

---

## 10. Metric Naming Conventions

All application-level metrics in this framework follow these conventions:

| Convention | Pattern | Example |
|------------|---------|---------|
| Dot-separated hierarchy | `{component}.{category}.{measurement}` | `eventsourcing.pipeline.events.processed` |
| Snake_case for multi-word segments | `end_to_end`, `update_view`, `queue_wait` | `eventsourcing.pipeline.latency.end_to_end` |
| Tags for dimensions | Cardinality controlled via tags, not metric names | `domain=Customer`, `eventType=CustomerCreated` |
| Prometheus export format | Dots become underscores, suffixed with `_total` (counters) or `_seconds` (timers) | `eventsourcing_pipeline_events_processed_total` |

### Tag Cardinality Notes

| Tag | Expected Cardinality | Risk |
|-----|---------------------|------|
| `domain` | 4 (Customer, Product, Order, Payment) | Low |
| `eventType` | ~15-20 distinct event types across all services | Low |
| `sagaType` | 1-3 (e.g., OrderFulfillment) | Low |
| `stage` | 6 (source, enrich, persist, update_view, publish, complete) | Low |
| `stepName` | 3-5 per saga type | Low |
| `mapName` | ~8-10 maps across services | Low |
| `path` (gateway) | Normalized -- potentially ~20-30 route patterns | Medium (controlled by `normalizePath()`) |
| `status` (gateway) | ~5 (200, 201, 400, 404, 500) | Low |
| `method` (gateway) | ~4 (GET, POST, PUT, DELETE) | Low |
