# Observability in Event-Sourced Systems

*Part 4 of 6 in the "Building Event-Driven Microservices with Hazelcast" series*

---

## Introduction

In [Part 1](01-event-sourcing-with-hazelcast-introduction.md), we built an event sourcing framework. In [Part 2](02-building-event-pipeline-with-hazelcast-jet.md), we constructed the Jet pipeline. In [Part 3](03-materialized-views-for-fast-queries.md), we optimized queries with materialized views.

Now we tackle a different challenge: **How do you observe what's happening inside a distributed, event-driven system?**

Event sourcing changes the observability game. Traditional request-response applications have straightforward metrics — request rate, error rate, latency. But in an event-sourced system, a single API call triggers an asynchronous pipeline that writes to an event store, updates a materialized view, publishes to subscribers, and potentially kicks off a multi-service saga. A latency spike could be hiding in any of those stages.

In this article, we'll build a complete observability stack with three pillars:

1. **Metrics** — Prometheus + Micrometer for quantitative measurement
2. **Dashboards** — Grafana for visualization and alerting
3. **Tracing** — Jaeger for distributed request tracking

---

## The Three Pillars in Practice

Here's how the observability stack fits into our architecture:

```
┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│   Account    │ │  Inventory   │ │    Order     │ │   Payment    │
│   Service    │ │   Service    │ │   Service    │ │   Service    │
│   :8081      │ │   :8082      │ │   :8083      │ │   :8084      │
└──────┬───────┘ └──────┬───────┘ └──────┬───────┘ └──────┬───────┘
       │                │                │                │
       │  /actuator/prometheus           │  OTLP traces   │
       │                │                │                │
       ▼                ▼                ▼                ▼
┌──────────────┐                                  ┌──────────────┐
│  Prometheus  │                                  │    Jaeger    │
│   :9090      │                                  │   :16686     │
└──────┬───────┘                                  └──────────────┘
       │
       ▼
┌──────────────┐
│   Grafana    │
│   :3000      │
└──────────────┘
```

Each service exposes a `/actuator/prometheus` endpoint. Prometheus scrapes all four services every 15 seconds. Grafana reads from Prometheus and renders dashboards. Jaeger collects distributed traces via OTLP.

---

## Instrumenting the Framework

### Multi-Level Metrics

Our framework instruments at two levels — the event pipeline and the saga layer — each with its own metrics class.

#### Pipeline Metrics

The `PipelineMetrics` class tracks every event through the 6-stage pipeline:

```java
public class PipelineMetrics {

    private final MeterRegistry registry;
    private final String domainName;

    // Events entering and leaving the pipeline
    Counter eventsReceived;   // "eventsourcing.pipeline.events.received"
    Counter eventsProcessed;  // "eventsourcing.pipeline.events.processed"
    Counter eventsFailed;     // "eventsourcing.pipeline.events.failed"

    // End-to-end latency histogram with percentiles
    Timer endToEndLatency;    // "eventsourcing.pipeline.latency.end_to_end"
    Timer queueWaitLatency;   // "eventsourcing.pipeline.latency.queue_wait"

    // Per-stage timing
    Timer stageDuration;      // "eventsourcing.pipeline.stage.duration"
                              // Tagged with stage: persist, update_view, publish
}
```

Every metric is tagged with `domain` (e.g., "Customer", "Order") and `eventType` (e.g., "CustomerCreated"), enabling fine-grained filtering:

```java
Counter.builder("eventsourcing.pipeline.events.processed")
    .tag("domain", domainName)
    .tag("eventType", eventType)
    .register(registry);
```

The per-stage timer is particularly valuable for debugging. If your P99 latency spikes, you can see *which stage* is the bottleneck — is it the event store write, the view update, or the publication step?

```java
public enum PipelineStage {
    SOURCE("source"),
    ENRICH("enrich"),
    PERSIST("persist"),
    UPDATE_VIEW("update_view"),
    PUBLISH("publish"),
    COMPLETE("complete")
}

public void recordStageTiming(PipelineStage stage, Instant start) {
    Timer.builder("eventsourcing.pipeline.stage.duration")
        .tag("domain", domainName)
        .tag("stage", stage.getLabel())
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(registry)
        .record(Duration.between(start, Instant.now()));
}
```

#### Saga Metrics

The `SagaMetrics` class tracks the lifecycle of distributed sagas:

```java
public class SagaMetrics {

    private static final String PREFIX = "saga";

    // Lifecycle counters (tagged by sagaType)
    "saga.started"               // Sagas initiated
    "saga.completed"             // Sagas completed successfully
    "saga.compensated"           // Sagas that required compensation
    "saga.failed"                // Sagas that failed
    "saga.timedout"              // Sagas that exceeded their deadline

    // Step-level counters
    "saga.steps.completed"       // Individual steps completed
    "saga.steps.failed"          // Individual steps failed
    "saga.compensation.started"  // Compensation processes initiated
    "saga.compensation.steps"    // Compensation steps executed

    // Duration timers (p50, p95, p99)
    "saga.duration"              // End-to-end saga duration
    "saga.compensation.duration" // Compensation duration
}
```

#### Performance: Caching Metric Instances

At 100,000+ events per second, looking up a Counter in Micrometer's registry on every event is measurable overhead. The `SagaMetrics` class caches metric instances in a `ConcurrentHashMap`:

```java
private final ConcurrentMap<String, Counter> counterCache;
private final ConcurrentMap<String, Timer> timerCache;

private Counter getCounter(String name, String sagaType) {
    String key = name + ":" + sagaType;
    return counterCache.computeIfAbsent(key, k ->
            Counter.builder(PREFIX + "." + name)
                    .tag("sagaType", sagaType)
                    .register(meterRegistry)
    );
}
```

This is a small but important detail. At high throughput, every nanosecond in the hot path matters.

---

## JVM and System Metrics

Beyond application-specific metrics, the framework auto-registers JVM metrics via `MetricsConfig`:

```java
@Configuration
public class MetricsConfig {

    @Bean public JvmMemoryMetrics jvmMemoryMetrics()   { return new JvmMemoryMetrics(); }
    @Bean public JvmGcMetrics jvmGcMetrics()           { return new JvmGcMetrics(); }
    @Bean public JvmThreadMetrics jvmThreadMetrics()    { return new JvmThreadMetrics(); }
    @Bean public ClassLoaderMetrics classLoaderMetrics() { return new ClassLoaderMetrics(); }
    @Bean public ProcessorMetrics processorMetrics()    { return new ProcessorMetrics(); }
}
```

Common tags are applied to every metric for cross-service filtering:

```java
registry.config().commonTags(Arrays.asList(
    Tag.of("application", applicationName),
    Tag.of("version", applicationVersion)
));
```

This means every metric — from JVM heap usage to saga completion rates — can be filtered by service name in Grafana.

---

## Configuring Prometheus

### Service-Side: Spring Boot Actuator

Each service exposes metrics via Spring Boot Actuator with Prometheus export:

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics,info
  endpoint:
    health:
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}
```

This creates a `/actuator/prometheus` endpoint that Prometheus scrapes.

### Prometheus-Side: Scrape Configuration

Prometheus is configured to scrape all four services and the Hazelcast cluster:

```yaml
# docker/prometheus/prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s
  external_labels:
    monitor: 'ecommerce-demo'

scrape_configs:
  - job_name: 'account-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['account-service:8081']

  - job_name: 'inventory-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['inventory-service:8082']

  - job_name: 'order-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['order-service:8083']

  - job_name: 'payment-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['payment-service:8084']

  - job_name: 'hazelcast'
    metrics_path: '/hazelcast/rest/cluster'
    static_configs:
      - targets:
          - 'hazelcast-1:5701'
          - 'hazelcast-2:5701'
          - 'hazelcast-3:5701'
```

Each service gets its own job, which means Prometheus automatically labels metrics with `job="account-service"` etc. The Hazelcast cluster nodes are scraped separately.

---

## Grafana Dashboards

### Dashboard Strategy

We provide four pre-provisioned Grafana dashboards, each focused on a different operational concern:

| Dashboard | Focus | Key Question It Answers |
|-----------|-------|------------------------|
| **System Overview** | Service health and throughput | "Is everything running? How much traffic?" |
| **Event Flow** | Pipeline performance | "How fast are events processing? Where are bottlenecks?" |
| **Materialized Views** | View update performance | "Are views keeping up with events?" |
| **Saga Dashboard** | Distributed transaction health | "Are sagas completing? Any failures or timeouts?" |

The System Overview is set as the home dashboard — it's the first thing you see when you open Grafana.

### Auto-Provisioning

Dashboards, datasources, and alerts are all provisioned automatically. When Grafana starts, it reads configuration from mounted volumes:

```
docker/grafana/
├── dashboards/                    # Dashboard JSON files
│   ├── system-overview.json       # Auto-loaded as home dashboard
│   ├── event-flow.json
│   ├── materialized-views.json
│   └── saga-dashboard.json
└── provisioning/
    ├── datasources/
    │   └── datasources.yml        # Points to Prometheus
    ├── dashboards/
    │   └── dashboards.yml         # Tells Grafana where to find JSONs
    └── alerting/
        ├── alerts.yml             # Alert rule definitions
        ├── contactpoints.yml      # Notification channels
        └── policies.yml           # Routing policies
```

No manual setup required. Run `docker-compose up` and the dashboards are ready.

### Key Dashboard Panels

#### System Overview

The System Overview dashboard provides at-a-glance health:

- **Service health indicators**: Green/red for each service based on `up{job="..."}` metric
- **Event throughput**: `rate(eventsourcing_events_submitted_total[$__rate_interval])` by service
- **HTTP request rate**: `sum by (application) (rate(http_server_requests_seconds_count))` per service
- **Pipeline P95 latency**: `histogram_quantile(0.95, rate(eventsourcing_pipeline_latency_end_to_end_seconds_bucket))`
- **Saga summary**: Total started, completed, failed, and timed out

#### Saga Dashboard

The Saga Dashboard provides deep visibility into distributed transactions:

- **Active saga count** and **compensating count** — how many sagas are in flight right now
- **Saga throughput**: Start, complete, and compensate rates over time, filterable by `sagaType`
- **Duration percentiles**: P50, P95, P99 saga duration — how long are transactions taking?
- **Success rate**: `completed / (completed + compensated + failed + timedout)` as a percentage
- **Timeout detection rate**: Are the timeout detectors firing? How often?
- **Compensation breakdown**: How many compensation steps are executing, and are any failing?

The dashboard supports a `$sagaType` variable — you can filter to just "OrderFulfillment" or view all saga types at once.

#### Event Flow

The Event Flow dashboard answers pipeline performance questions:

- **Events published per second by service** — which services are most active?
- **End-to-end latency percentiles** (P50/P95/P99) — is the pipeline keeping up?
- **Queue wait latency** — are events waiting too long before processing begins?
- **Stage duration breakdown** (stacked) — persist, update_view, and publish timing at P95
- **Failed events by stage and type** — where are failures occurring?

---

## Alerting

### Pre-Configured Alerts

The framework ships with six alerts that cover the most critical failure modes:

#### Saga Alerts

| Alert | Severity | Condition | For |
|-------|----------|-----------|-----|
| High Saga Failure Rate | Critical | `increase(saga_failed_total[5m]) > 0` | 2 min |
| Saga Timeouts Detected | Warning | `increase(saga_timeouts_detected_total[5m]) > 0` | 2 min |
| Saga Compensation Failures | Critical | `increase(saga_compensations_failed_total[5m]) > 0` | 1 min |
| Low Saga Success Rate | Warning | Success rate < 90% over 10 minutes | 5 min |

#### Service Health Alerts

| Alert | Severity | Condition | For |
|-------|----------|-----------|-----|
| Service Down | Critical | `up < 1` for any service | 1 min |
| High Event Processing Error Rate | Warning | Error rate > 5% over 5 minutes | 3 min |

The "For" duration prevents flapping — a brief network blip won't page you at 3am. Compensation failures fire fastest (1 minute) because a failed compensation means money or inventory is in an inconsistent state.

---

## Distributed Tracing with Jaeger

### Why Tracing Matters for Event Sourcing

Metrics tell you *that* something is slow. Tracing tells you *why*.

In our system, a single order placement can touch four services: Order creates the order, Inventory reserves stock, Payment processes the charge, and Order confirms. With metrics alone, you'd see "P99 saga duration increased." With tracing, you'd see "Payment Service is taking 2 seconds to respond to StockReserved events."

### Configuration

Tracing is enabled via Spring Boot's OpenTelemetry integration:

```yaml
management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0   # Sample 100% of requests (reduce in production)
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4317}
```

Jaeger runs as an all-in-one container in the Docker stack, receiving traces via OTLP on port 4317. In the Jaeger UI (http://localhost:16686), you can:

1. Select a service from the dropdown
2. Find traces for a specific time window
3. Click into a trace to see the span waterfall across all four services
4. Identify which service or operation is contributing to latency

### What to Trace

The most valuable traces in an event-sourced system:

- **API request → pipeline completion**: End-to-end from REST call to completion signal
- **Saga flows**: OrderCreated → StockReserved → PaymentProcessed → OrderConfirmed
- **Compensation flows**: Where did the failure occur, and how long did compensation take?

---

## Useful PromQL Queries

Here are queries you can run directly in Prometheus or use in custom Grafana panels:

### Service Health

```promql
# Are all services up?
up{job=~".*-service"}

# HTTP request rate by service
sum by (application) (rate(http_server_requests_seconds_count[5m]))
```

### Event Pipeline

```promql
# Event throughput
rate(eventsourcing_pipeline_events_processed_total[5m])

# End-to-end P99 latency
histogram_quantile(0.99, rate(eventsourcing_pipeline_latency_end_to_end_seconds_bucket[5m]))

# Queue wait time (events waiting to be processed)
histogram_quantile(0.95, rate(eventsourcing_pipeline_latency_queue_wait_seconds_bucket[5m]))
```

### Sagas

```promql
# Saga completion rate
rate(saga_completed_total[5m])

# Saga success rate (percentage)
sum(saga_completed_total) /
  (sum(saga_completed_total) + sum(saga_compensated_total) +
   sum(saga_failed_total) + sum(saga_timedout_total))

# Saga duration P99
histogram_quantile(0.99, rate(saga_duration_seconds_bucket[5m]))

# Active timeouts in last 5 minutes
increase(saga_timeouts_detected_total[5m])
```

### JVM

```promql
# Heap memory usage
jvm_memory_used_bytes{area="heap"}

# GC pause time
rate(jvm_gc_pause_seconds_sum[5m])
```

---

## Lessons Learned

### 1. Instrument at the Right Level

Don't just measure HTTP latency. In an event-sourced system, the interesting latency is inside the pipeline — from event submission to view update. HTTP latency includes this but hides where the time is spent.

### 2. Tag Everything

Multi-dimensional tags (`domain`, `eventType`, `sagaType`, `stage`) are essential. A P99 spike in "pipeline latency" is useless without knowing which domain and stage are affected.

### 3. Cache Metric Instances

At high throughput, registry lookups add up. Cache your Counter and Timer instances. The `ConcurrentHashMap.computeIfAbsent` pattern works well for this.

### 4. Provision Everything as Code

Don't create dashboards by hand. Provision them from JSON files. This means your observability stack is version-controlled, reproducible, and deploys automatically. When a new team member clones the repo and runs `docker-compose up`, they get the same dashboards as everyone else.

### 5. Alert on Business Outcomes

"Service Down" is an infrastructure alert. "Saga Failure Rate" is a business outcome alert. Both are important, but the business alerts are what catch problems that don't manifest as service crashes — like a payment gateway returning errors, causing saga compensations.

---

## Summary

Observability in event-sourced systems requires metrics at multiple levels:

- **Pipeline metrics**: Event throughput, per-stage latency, failure rates
- **Saga metrics**: Lifecycle tracking, compensation rates, timeout detection
- **JVM metrics**: Memory, GC, threads
- **HTTP metrics**: Request rate, response latency

Combined with auto-provisioned Grafana dashboards, pre-configured alerts, and distributed tracing via Jaeger, this gives you complete visibility into a system where a single API call can trigger asynchronous processing across four services.

The key insight is that event sourcing makes observability both harder and more important. Events are asynchronous, distributed, and flow through multiple stages. Without good metrics and dashboards, you're flying blind.

---

*Next: [Part 5 - The Saga Pattern for Distributed Transactions](05-saga-pattern-for-distributed-transactions.md)*

*Previous: [Part 3 - Materialized Views for Fast Queries](03-materialized-views-for-fast-queries.md)*
