# Performance Tuning Guide

This guide synthesizes findings from 12 performance engineering sessions into an actionable reference for adopters of the Hazelcast Microservices Framework. It covers metrics, profiling, benchmarking, configuration tuning, and scaling — everything you need to measure, understand, and optimize your event-sourced system.

## Quick Reference

Key metrics from our testing (Docker Compose, Community Edition, 25 TPS):

| Metric | Value | Where Measured |
|--------|-------|----------------|
| HTTP request p95 | < 30ms | k6 `constant-arrival-rate` |
| Pipeline end-to-end p50 | 8.7ms | Prometheus |
| Pipeline queue wait p95 | 2.1ms | Prometheus |
| Saga end-to-end avg | 2.18s (25 TPS) | k6 saga polling |
| Per-event framework cost | ~37 us | JMH micro-benchmark |
| Hazelcast cluster ops per saga | ~2.4 ms | JMH cluster benchmark |
| System overhead (Jet + HTTP) | ~6.3 ms | Derived (72% of pipeline p50) |
| Outbox CPU % (after optimization) | ~12% | async-profiler flame graph |
| FlakeIdGenerator.newId() | 15.6 us | JMH (single thread) |
| IMap.set over network | ~311 us | JMH cluster benchmark |
| Event journal overhead | 4.7% | JMH (journaled vs plain map) |
| Memory ceiling at 512MiB | 12 minutes | Docker stats (50 TPS sustained) |

---

## Metrics Taxonomy

The framework registers **46 application-level Micrometer metrics** across 5 components. Understanding what each measures is essential for interpreting performance data.

### Pipeline Metrics (9 metrics)

Source: `PipelineMetrics.java` — prefix `eventsourcing.pipeline`

| Type | Key Metrics | What They Tell You |
|------|------------|-------------------|
| Counters (6) | `events.submitted`, `events.processed`, `events.failed`, `events.received`, `views.updated`, `events.published` | Throughput and error rate per domain/event type |
| Timers (3) | `stage.duration`, `latency.end_to_end`, `latency.queue_wait` | Where time is spent in the pipeline |

**Key diagnostic patterns:**
- `events.submitted` growing faster than `events.processed` → pipeline can't keep up
- `latency.queue_wait` > 5ms → Jet pipeline is the bottleneck (events waiting to be picked up)
- `stage.duration{stage="persist"}` spiking → database or serialization issue

### Saga Metrics (21 metrics)

Source: `SagaMetrics.java` — prefix `saga`

| Type | Key Metrics | What They Tell You |
|------|------------|-------------------|
| Counters (11) | `started`, `completed`, `compensated`, `failed`, `timedout`, `steps.*`, `compensation.*` | Saga health and failure modes |
| Gauges (7) | `sagas.active.count`, `sagas.compensating.count`, `.total` variants | Instantaneous saga pressure |
| Timers (3) | `saga.duration`, `saga.step.duration`, `saga.compensation.duration` | End-to-end and per-step timing |

**Key diagnostic patterns:**
- `sagas.active.count` trending upward → saga backlog (not completing fast enough)
- `saga.timedout` incrementing → timeout detector working, but sagas taking too long
- `saga.compensated` > 0 → failures occurring and being handled

### Persistence Metrics (9 metrics)

Source: `PersistenceMetrics.java` — prefix `persistence`

| Type | Key Metrics | What They Tell You |
|------|------------|-------------------|
| Counters (7) | `store.count`, `store.batch.count`, `store.batch.entries`, `load.count`, `load.miss`, `delete.count`, `errors` | Write-behind throughput and failure rate |
| Timers (3) | `store.duration`, `store.batch.duration`, `load.duration` | Database I/O timing |

**Key diagnostic patterns:**
- `persistence.errors` > 0 → database connectivity or schema issue
- `store.batch.entries` / `store.batch.count` → average batch size (tune `write-batch-size`)
- `load.miss` rate high → IMap eviction too aggressive (entries evicted before read)

### Controller & Gateway Metrics (7 metrics)

Source: `EventSourcingController.java`, `RequestTimingFilter.java`

| Metric | What It Tells You |
|--------|-------------------|
| `eventsourcing.pending.completions` | Pipeline backpressure (entries waiting for completion callback) |
| `eventsourcing.pending.events` | IMap queue depth (events waiting for Jet pickup) |
| `eventsourcing.completions.orphaned` | Late completions (after 30s timeout) — indicates pipeline stall |
| `gateway.request.duration` | API gateway routing overhead (typically +2–5ms avg, +5–8ms p95) |

### JVM & System Metrics (auto-registered)

5 Micrometer binders provide JVM memory, GC, threads, class loading, and CPU metrics. Common tags (`application`, `version`) are applied to all metrics.

**Key gauges to watch:**
- `jvm.memory.used{area="heap"}` — should stay below 80% of `-Xmx`
- `jvm.gc.pause` — p95 > 100ms indicates GC pressure
- `process.cpu.usage` — > 80% sustained indicates compute saturation

---

## Establishing a Baseline

A baseline gives you a reference point for all future comparisons. Without one, you can't tell if a change helped or hurt.

### Prerequisites

1. **k6 installed** (v1.6+, Go-based, fixes coordinated omission)
2. **Docker Compose running** with all 4 services + Hazelcast cluster + Prometheus/Grafana
3. **Sample data loaded**: 100 customers, 100 products via `scripts/perf/generate-sample-data.sh`

### Running the Baseline

```bash
# Quick sanity check (10 TPS, 1 minute)
./scripts/perf/run-perf-test.sh --scenario smoke

# Full baseline at three TPS levels (3 minutes each)
./scripts/perf/run-perf-test.sh --scenario constant --tps 10
./scripts/perf/run-perf-test.sh --scenario constant --tps 25
./scripts/perf/run-perf-test.sh --scenario constant --tps 50

# Saga end-to-end measurement
./scripts/perf/run-perf-test.sh --scenario saga --vus 10

# Via API gateway (add +2-5ms overhead)
./scripts/perf/run-perf-test.sh --scenario mixed --gateway
```

### What "Good" Looks Like

| TPS | HTTP p95 | Saga E2E avg | Error Rate | Status |
|-----|----------|-------------|------------|--------|
| 10 | < 30ms | < 2s | < 0.1% | Healthy |
| 25 | < 25ms | < 3s | < 0.1% | Healthy |
| 50 | < 25ms | < 5s | < 1% | Acceptable |
| 50 | < 25ms | > 10s (timeout) | > 2% | Investigate |

### Important: Use `constant-arrival-rate`

Always use k6's `constant-arrival-rate` executor, not iteration-based executors. Iteration-based executors reduce the request rate when the system slows down, masking latency issues (coordinated omission). The `constant-arrival-rate` executor maintains a fixed request rate regardless of response time, showing you the true latency distribution under load.

---

## Known Bottlenecks and Solutions

### 1. Outbox Polling Full Scan (Session 5 — Fixed in Session 6)

**Symptom:** High CPU usage on services with outbox enabled, `OutboxPublisher` dominating flame graphs.

**Root cause:** `IMap.values(predicate)` without indexes triggers a full partition scan every poll cycle. At 25 TPS, this consumed ~24% of CPU and ~28% of allocations.

**Fix:** Three changes in `framework-core`:
1. Add `IndexConfig(HASH, "status")` to outbox MapConfig
2. Add `IndexConfig(SORTED, "createdAt")` to outbox MapConfig
3. Replace `values(predicate)` + Java sort with `PagingPredicate` (server-side sort + limit)

**Result:** CPU halved (24% → 12%), total allocations down 34%.

### 2. Saga Timeout Detection Not Running (Session 1 — Fixed)

**Symptom:** `sagas.active.count` growing monotonically, sagas never timing out, saga completion rate below 100%.

**Root cause:** `SagaTimeoutAutoConfiguration` was not registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. The timeout detector never started.

**Fix:** Register both `SagaCompensationConfig` and `SagaTimeoutAutoConfiguration` in auto-configuration imports.

### 3. Memory Ceiling at 512MiB (Session 8 — Fixed)

**Symptom:** Services hit 99.5% memory within 12–21 minutes at 50 TPS. CPU spikes to 59% from GC thrashing. Max latency spikes (5.6s) from Full GC pauses.

**Root cause:** IMap data grows monotonically while JVM heap is capped at 512MiB.

**Fix:** Increase container memory to 1GiB, JVM heap to 768MiB. Enable IMap eviction for event store and view maps when persistence is active.

### 4. Saga State Poll Miss in Multi-Replica K8s (Session 11 — Fixed)

**Symptom:** Saga E2E times out at 10+ seconds even with HPA-scaled replicas. Replicas idle at 9–10% CPU while primary pod saturates.

**Root cause:** Without per-service clustering, each pod's embedded HazelcastInstance is a cluster-of-1. K8s load-balances saga polls to replicas that don't own the saga state.

**Fix:** Enable per-service embedded clustering (ADR 013). Same-service replicas form their own Hazelcast cluster via K8s DNS discovery. All replicas share the distributed IMap and participate in Jet pipeline processing.

**Result:** Saga E2E improved 95% (10s timeout → 513ms at 10 TPS). HPA scaled 2→5 replicas effectively.

---

## Configuration Tuning Reference

### Hazelcast Configuration

| Setting | Default | Tuned | Why |
|---------|---------|-------|-----|
| Event journal capacity | 10,000 | 10,000+ | Must be larger than max events in flight. Too small → events lost before pipeline reads them |
| Outbox HASH index on `status` | None | **Required** | Eliminates full partition scan on polling (~24% CPU reduction) |
| Outbox SORTED index on `createdAt` | None | **Required** | Enables server-side sort in PagingPredicate |
| FlakeIdGenerator prefetch count | 100 | 500+ at high concurrency | 8-thread contention grows to 151 us/op (9.7x single-thread) |
| Backup count (shared cluster) | 1 | 1 | Negligible overhead (4.7% per benchmark). Increase only for HA |
| Near Cache for saga state | Disabled | Consider for read-heavy | Reduces IMap.get latency for frequently-read saga state |

### JVM Settings

| Setting | Minimum | Recommended | Notes |
|---------|---------|-------------|-------|
| `-Xmx` | 512m | 768m (1Gi container) | 75% of container limit |
| Container memory | 1Gi | 1Gi–3Gi | 512MiB hits ceiling at 50 TPS in 12 min |
| GC | G1GC (default) | G1GC | Good for mixed heap (short events + long views) |
| Frame pointer | — | `-XX:+PreserveFramePointer` | Only for profiling (1–2% JIT cost) |

### Spring / Framework Properties

| Property | Default | Description |
|----------|---------|-------------|
| `framework.persistence.enabled` | false | Enable PostgreSQL write-behind |
| `framework.persistence.write-delay-seconds` | 5 | Batch window for write-behind |
| `framework.persistence.write-batch-size` | 100 | Max entries per batch |
| `framework.persistence.event-store-eviction.enabled` | true | Enable LRU eviction on event store |
| `framework.persistence.event-store-eviction.max-size` | 10000 | Max entries per node |
| `hazelcast.embedded.clustering.enabled` | false | Enable per-service clustering (K8s only) |

---

## Enterprise Edition Features

Three Enterprise features were A-B tested at 50 TPS in Session 7. Results:

| Feature | Impact | Verdict |
|---------|--------|---------|
| **HD Memory** (native off-heap) | +3–24% p95 latency | **Not recommended** at small scale. Serialization overhead crossing JVM heap boundary outweighs benefit until datasets are large enough to cause GC pressure |
| **Thread-Per-Core (TPC)** | -0.5–1.1ms p95, -14% max latency | **Marginal benefit** at 50 TPS. Shows promise for higher concurrency where its event-loop architecture outperforms traditional I/O threading |
| **HD Memory + TPC** | Mixed | Combined didn't compound benefits |

**When Enterprise helps:**
- HD Memory: Datasets > 1 GB per service causing frequent GC pauses
- TPC: High-concurrency workloads (100+ TPS per service) where thread scheduling is a bottleneck
- CP Subsystem: Strong consistency requirements for saga state (not needed with current choreography model)

**When Community is sufficient:**
- Datasets < 1 GB per service
- Throughput < 100 TPS per service
- Docker Compose or small Kubernetes deployments

The A-B test framework (`scripts/perf/ab-test.sh`) is reusable for testing any configuration variation — not limited to Enterprise features.

---

## Profiling with async-profiler

### Setup

```bash
# Build profiled Docker image (async-profiler v4.3 baked in)
docker compose -f docker/docker-compose.yml \
  -f docker/docker-compose-profiling.yml build

# Start with profiling-enabled JVM flags
docker compose -f docker/docker-compose.yml \
  -f docker/docker-compose-profiling.yml up -d
```

JVM flags added: `-XX:+PreserveFramePointer -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`

### Capturing Flame Graphs

```bash
# Automated: warmup + k6 load + capture + extract
./scripts/perf/profile-service.sh order-service cpu 30
./scripts/perf/profile-service.sh order-service alloc 30

# Manual: inside the container
docker exec -it order-service \
  /opt/async-profiler/bin/asprof -d 30 -e itimer \
  -f /tmp/flamegraph.html 1
docker cp order-service:/tmp/flamegraph.html .
```

### Reading Flame Graphs

**CPU flame graph** shows where CPU time is spent:
- Wide frames at the top = self time (actual work)
- Wide frames in the middle = inclusive time (this function + everything it calls)
- Look for: `IMap.values()` (outbox polling), `pthread_cond_signal` (thread contention), `CompactInternalGenericRecord` (serialization)

**Allocation flame graph** shows where objects are created:
- `byte[]` from Compact serialization = inherent cost of GenericRecord
- `String` from `readUTFInternal` = string field deserialization
- High allocation rate = GC pressure

**Our findings (order-service, 25 TPS after optimization):**

| Subsystem | CPU % | What's Normal |
|-----------|-------|---------------|
| Hazelcast (total) | ~59% | Expected — IMap ops, Jet, serialization |
| Spring/Tomcat | ~35–41% | Expected — HTTP stack |
| Jet pipeline | ~19% | Expected — event processing |
| Outbox polling | ~12% | Acceptable after optimization (was 24%) |
| Serialization | ~18% | Inherent to GenericRecord model |

**Note:** On macOS Docker Desktop, use `itimer` mode (wall-clock) — `perf_events` is unavailable. Frames involving `epoll_wait`, `read`, `write` include I/O wait time, not just CPU work. The allocation profile is unaffected and more actionable.

---

## Load Testing with k6

### Script Architecture

```
scripts/perf/
├── k6-load-test.js          # Main: smoke, ramp_up, constant_rate
├── k6-config.js             # Shared: URLs, thresholds, helpers
├── k6-scenarios/
│   ├── create-orders.js     # Focused order creation
│   ├── mixed-workload.js    # 60% orders / 25% stock / 15% customers
│   ├── saga-e2e.js          # Saga completion with polling
│   └── sustained-load.js    # 30-minute test with checkpoints
├── run-perf-test.sh         # Bash 3.2 wrapper
└── data/
    ├── customer-ids.json    # 100 IDs for k6 SharedArray
    └── product-ids.json     # 100 IDs for k6 SharedArray
```

### Key Scenarios

| Scenario | Use Case | Command |
|----------|----------|---------|
| Smoke | Quick sanity (10 TPS, 1 min) | `--scenario smoke` |
| Constant | Steady-state at target TPS | `--scenario constant --tps 50` |
| Mixed | Realistic workload mix | `--scenario mixed --tps 25` |
| Saga E2E | Saga completion timing | `--scenario saga --vus 10` |
| Sustained | Long-duration stability | `run-sustained-test.sh` (30 min) |
| Gateway | Measure proxy overhead | Add `--gateway` flag |

### Understanding the Workload

Each "1 TPS" sends 1 HTTP request, but 60% of those trigger a multi-service saga generating ~3 additional internal cross-service messages. At 100 TPS:
- ~100 external HTTP requests/second
- ~180 internal ITopic saga messages/second
- Total system load: ~280 messages/second

### Running A-B Tests

```bash
# Compare two configurations (automated, ~9 min per test)
./scripts/perf/ab-test.sh community-baseline high-memory --tps 50

# Generate comparison report
./scripts/perf/ab-compare.sh results-baseline results-high-memory
```

The A-B harness handles: clean slate teardown, compose up, health checks, data loading, stabilization, k6 run, Docker stats capture, manifest JSON, and teardown — all automated.

---

## JMH Micro-Benchmarks

### Building and Running

```bash
# Build benchmark JAR (requires -Pbenchmark profile)
mvn clean package -pl framework-core -Pbenchmark -DskipTests

# Run all benchmarks (~23 min)
java -jar framework-core/target/benchmarks.jar

# Run a single benchmark class (~2 min)
java -jar framework-core/target/benchmarks.jar SerializationBenchmark -f 1 -wi 1 -i 2
```

### Per-Event Cost Breakdown

An event flowing through the pipeline touches these operations:

| Operation | Cost | Cumulative | % of Total |
|-----------|:----:|:----------:|:----------:|
| FlakeIdGenerator.newId() | 15.6 us | 15.6 us | 42% |
| Event.toGenericRecord() | 1.1 us | 16.7 us | 3% |
| EventStore.append (IMap.set) | 11.2 us | 27.9 us | 30% |
| ViewStore.executeOnKey | 9.3 us | 37.2 us | 25% |
| **Total** | **~37 us** | | **100%** |

At 50 TPS, this is ~1.9 ms/s of CPU — well under 1% of a single core.

### FlakeIdGenerator Contention

| Threads | Cost (us) | Slowdown |
|:-------:|:---------:|:--------:|
| 1 | 15.6 | 1x |
| 4 | 69.2 | 4.4x |
| 8 | 150.6 | 9.7x |

If scaling beyond ~50 TPS per service, increase `FlakeIdGeneratorConfig.setPrefetchCount()` from the default of 100.

### Embedded vs Cluster Operations

| Operation | Embedded | Cluster | Overhead |
|-----------|:--------:|:-------:|:--------:|
| IMap.set (200B) | 7.4 us | 311 us | 42x |
| IMap.set (1KB) | 11.2 us | 314 us | 28x |
| IMap.get | ~7 us | 268 us | 38x |
| ITopic.publish | N/A | 308 us | — |

The ~270–310 us network overhead per operation comes from TCP round-trip, Compact serialization, backup replication, and (in test mode) single-gateway routing. In production with smart routing enabled, latency is lower.

### Event Journal Overhead

| Map Type | Cost | Overhead |
|----------|:----:|:--------:|
| Plain map write | 323 us | — |
| Journaled map write | 338 us | +4.7% |

Event journal bookkeeping is effectively negligible.

---

## Cluster Benchmarks

### Saga Latency Decomposition

A typical order placement saga involves 3 IMap.set, 3 ITopic.publish, and 2 IMap.get operations:

| Layer | Cost | % of Pipeline p50 |
|-------|:----:|:-----------------:|
| Framework internals | ~37 us | 0.4% |
| Hazelcast cluster ops | ~2,400 us | 27.6% |
| System overhead (Jet + HTTP + threads) | ~6,263 us | 72.0% |
| **Pipeline p50 total** | **8,700 us** | **100%** |

**Conclusion:** Hazelcast is a significant contributor (~28%) but not the bottleneck. System-level overhead (Jet scheduling, thread coordination, Tomcat HTTP handling) dominates at ~72%. Optimization efforts should focus on system-level tuning, not Hazelcast operations.

### Key Observations

- **Payload size has minimal impact:** Small (311 us) vs large (314 us) IMap writes — only 1% difference
- **Gets are faster than puts:** ~268 us vs ~311 us (14% — no server-side write or replication)
- **ITopic fire-and-forget:** ~308–355 us, comparable to IMap.set
- **Smart routing matters:** All benchmarks used single-gateway mode (smart routing disabled). Production deployments with smart routing enabled will have lower per-op latency.

---

## Scaling: Docker Compose to AWS EKS

### Four Deployment Tiers

| Tier | Infrastructure | Cost/hr | Max TPS | Saga p95 |
|------|---------------|---------|---------|----------|
| **Local** | Docker Desktop | $0 | 50 | < 300ms |
| **AWS Small** | 2x t3.xlarge | ~$0.76 | 100 | < 650ms |
| **AWS Medium** | 3x c7i.2xlarge | ~$1.18 | 200 | Timeout at 50+ |
| **AWS Large** | 5x c7i.4xlarge | ~$3.50 | 200 | **< 1s** |

### Choosing a Tier

- **Development / demos:** Local Docker Compose. Zero cost, sub-300ms saga at 50 TPS.
- **HTTP throughput priority:** AWS Medium. Best value at $0.016/10K transactions. Handles 200 TPS HTTP but saga completion degrades above 25 TPS without clustering.
- **Saga-critical production:** AWS Large with clustering. Only tier sustaining 200 TPS with sub-1s saga completion.

### The Per-Service Clustering Breakthrough (ADR 013)

The single most impactful scaling change: enabling per-service embedded clustering so same-service K8s replicas form their own Hazelcast cluster via DNS discovery.

| Metric | Without Clustering | With Clustering | Impact |
|--------|-------------------|-----------------|--------|
| Saga E2E p95 at 10 TPS | 10,161ms (timeout) | 513ms | **-95%** |
| Saga E2E p95 at 50 TPS | 10,197ms (timeout) | 644ms | **-94%** |
| 200 TPS with sub-1s saga | Impossible | Yes (1,036ms p95) | New capability |
| HPA effectiveness | Replicas idle at 9-10% CPU | 2→5 replicas, 86-98% CPU | Resources used |

**What changed:** Pre-clustering, each pod's embedded Hazelcast was a cluster-of-1. K8s load-balanced saga state polls to replicas that didn't own the data. With clustering, all same-service replicas share the distributed IMap and participate in Jet pipeline processing.

### HPA Auto-Scaling Behavior

| TPS | account | inventory | order | payment |
|-----|:-------:|:---------:|:-----:|:-------:|
| 10 | 2 | 2 | 2 | 2 |
| 25 | 2 | **5** | **5** | **4** |
| 50+ | 2 | **5** | **5** | **5** |

HPA correctly identifies inventory and order services as the first bottlenecks (60% + 25% of traffic). Account service stays at minimum (only 15% of traffic). With clustering enabled, HPA scaling is effective — all replicas do real work.

---

## Production Checklist

See the [Production Checklist](../perf/production-checklist.md) for a quick-reference checklist covering:

- Memory & JVM (5 items)
- Hazelcast Configuration (6 items)
- Monitoring (5 items)
- Load Testing (6 items)
- Persistence (4 items)
- Kubernetes / Scaling (7 items)
- Resilience (5 items)

---

## References

### Performance Documentation

| Document | Description |
|----------|-------------|
| [`docs/perf/metrics-inventory.md`](../perf/metrics-inventory.md) | All 46 metrics with descriptions and gap analysis |
| [`docs/perf/baseline-measurements.md`](../perf/baseline-measurements.md) | Baseline results at 10/25/50 TPS with Prometheus data |
| [`docs/perf/flamegraph-analysis-session5.md`](../perf/flamegraph-analysis-session5.md) | CPU and allocation flame graph analysis |
| [`docs/perf/optimization-iteration-1.md`](../perf/optimization-iteration-1.md) | Outbox optimization before/after results |
| [`docs/perf/stability-analysis.md`](../perf/stability-analysis.md) | 30-minute sustained load test findings |
| [`docs/perf/microbenchmark-results.md`](../perf/microbenchmark-results.md) | JMH per-operation cost breakdown |
| [`docs/perf/cluster-benchmark-results.md`](../perf/cluster-benchmark-results.md) | Cluster IMap/ITopic benchmarks and saga latency analysis |
| [`docs/perf/deployment-comparison-final.md`](../perf/deployment-comparison-final.md) | 4-tier deployment comparison with cost analysis |
| [`docs/perf/production-checklist.md`](../perf/production-checklist.md) | Quick-reference deployment checklist |
| [`docs/perf/performance-exercise-summary.md`](../perf/performance-exercise-summary.md) | All 12 sessions at a glance |

### Architecture Decision Records

| ADR | Decision |
|-----|----------|
| [ADR 008](../architecture/adr/008-dual-instance-hazelcast-architecture.md) | Dual-instance Hazelcast (embedded + shared cluster client) |
| [ADR 013](../architecture/adr/013-per-service-embedded-clustering.md) | Per-service embedded clustering for K8s multi-replica |
| [ADR 005](../architecture/adr/005-community-edition-default.md) | Community Edition default with Enterprise opt-in |

### Tools

| Tool | Version | Purpose |
|------|---------|---------|
| k6 | 1.6+ | HTTP load generation with `constant-arrival-rate` |
| async-profiler | 4.3 | CPU and allocation flame graphs |
| JMH | 1.37 | Per-operation micro-benchmarks |
| Prometheus | — | Metrics scraping (15s interval) |
| Grafana | — | 6 dashboards (System, Event Flow, Saga, Views, Persistence, Perf Testing) |

---

*This guide was produced during the [Performance Exercise](../perf/performance-exercise-summary.md) (12 sessions, Feb 17–21, 2026). For the step-by-step exercise plan, see [`docs/plans/performance-exercise-plan.md`](../plans/performance-exercise-plan.md).*
