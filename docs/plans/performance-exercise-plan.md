# Performance Exercise Plan: Hazelcast Microservices Framework

**Date:** 2026-02-17
**Type:** Performance Exploration (not a development phase)

---

## Context

With Phases 1-3 complete, the framework has a full event sourcing pipeline, saga orchestration, persistence, monitoring stack (Prometheus/Grafana/Jaeger), and Kubernetes deployment. The existing `load-test.sh` is a basic bash/curl script that only measures aggregate TPS and success/fail counts — no per-request latency, no percentile distributions, no sustained-load capability. Sample data is tiny (4 customers, 6 products). We need proper performance engineering tooling, measurements, and iterative optimization before considering new features.

---

## Metrics of Interest

Beyond TPS and latency, the framework's architecture demands tracking:

| Category | Metrics |
|----------|---------|
| **Request-Level** | TPS by operation type, p50/p95/p99/p99.9 latency, error rate by HTTP status, concurrent requests |
| **Pipeline-Level** | Queue wait time (submission to Jet pickup), per-stage duration (Enrich/Persist/ViewUpdate/Publish/Complete), end-to-end pipeline latency, events in flight, Event Journal fill ratio |
| **Cross-Service (Saga)** | ITopic publish latency, saga end-to-end duration, saga step durations, outbox delivery latency, circuit breaker state transitions |
| **Persistence** | Write-behind batch size/duration, write-behind queue depth, store/load/delete rates |
| **Hazelcast Internals** | IMap operation latency, Near Cache hit ratio, serialization time, GC pauses |
| **JVM/System** | Heap usage, GC pressure, thread count, CPU utilization, Docker memory vs limits |
| **Vector Store** | Search latency by limit, index build time, memory per embedding |

---

## Tool Recommendations

| Tool | Role | Rationale |
|------|------|-----------|
| **k6** (primary) | HTTP load generation | JS scripting, native Prometheus/Grafana integration, coordinated-omission handling, `constant-arrival-rate` executor, lightweight (Go-based) |
| **wrk2** (secondary) | Quick A-B comparisons | Constant-rate load, extremely lightweight, fixes coordinated omission |
| **async-profiler** | CPU & allocation profiling | Low overhead, flame graph output, works in Docker containers (full perf_events on Linux), wall-clock on macOS |
| **JMH** | Micro-benchmarks | Framework-internal hot paths (serialization, IMap ops, FlakeIdGenerator) in isolation |
| **Hazelcast Simulator** | Hazelcast-level testing | Direct IMap/ITopic throughput testing against the shared cluster — deferred to cloud sessions |

**Not recommended:** Gatling (JVM overhead competes for laptop resources), JMeter (heavy, poor DX), custom harness (unnecessary when k6 covers HTTP-level needs and JMH covers internal paths).

---

## Session Plan (12 Sessions)

### Session 1: Metrics Inventory and Baseline — COMPLETED

**Objectives:** Verify all Micrometer metrics are scraped, establish baseline numbers, identify instrumentation gaps.

**Deliverables:**
- `docs/perf/metrics-inventory.md` — Complete list of all registered metrics
- `docs/perf/baseline-measurements.md` — TPS, p50/p95/p99 at 10, 25, 50 TPS

**Work completed:**
- Enumerated all Prometheus metrics via `label/__name__/values`
- Cross-referenced with PipelineMetrics (9), SagaMetrics (21), PersistenceMetrics (9), RequestTimingFilter (1), Controller (2) — 42 application metrics total
- Identified 10 metric gaps with priorities (documented in metrics-inventory.md)
- Added 3 new gauges to `EventSourcingController.java`: `eventsourcing.pending.completions`, `eventsourcing.pending.events`, `eventsourcing.completions.orphaned`

**Baseline Results (k6 constant-arrival-rate, 3 min per test):**

| TPS Target | Iterations | Error Rate | Order p95 | Saga e2e avg | Result |
|:----------:|:----------:|:----------:|:---------:|:------------:|:------:|
| 10 | 1,801 | 0.00% | 30.0ms | 1.55s | PASS |
| 25 | 4,501 | 0.01% | 22.9ms | 2.18s | PASS |
| 50 | 8,990 | 2.59% | 22.5ms | 8.89s (timeout) | PASS* |

**Pipeline latency (Prometheus, p50/p95/p99):**
- End-to-end: 8.7ms / 16.8ms / 21.7ms
- Queue wait: 0.7ms / 2.1ms / 3.1ms
- Persist stage: 1.9ms / 6.5ms / 7.5ms
- View update: 1.9ms / 6.4ms / 7.0ms
- Publish stage: 0.5ms / 1.0ms / 2.0ms

**Key finding:** Saga completion is the primary bottleneck — 1,933 sagas stuck active (79.1% completion rate). HTTP request latency is excellent (p95 < 30ms all levels).

---

### Session 2: k6 Load Test Scripts — COMPLETED

**Objectives:** Install k6, create proper load test scripts with per-request latency tracking and Prometheus output.

**Deliverables:**
- `scripts/perf/k6-load-test.js` — Main script with smoke, ramp_up, and constant_rate scenarios
- `scripts/perf/k6-config.js` — Shared config (URLs, thresholds, helpers)
- `scripts/perf/k6-scenarios/create-orders.js` — Focused order creation at constant TPS
- `scripts/perf/k6-scenarios/mixed-workload.js` — 60/25/15 mixed workload at constant TPS
- `scripts/perf/k6-scenarios/saga-e2e.js` — End-to-end saga latency with polling
- `scripts/perf/run-perf-test.sh` — Bash 3.2-compatible wrapper script

**Key Design:**
- `constant-arrival-rate` executor (fixes coordinated omission)
- Custom k6 Trend metrics: `order_create_duration`, `stock_reserve_duration`, `customer_create_duration`, `saga_e2e_duration`
- `SharedArray` loading from `data/customer-ids.json` and `data/product-ids.json` (100 IDs each)
- Ramp-up scenario: 0 -> 10 -> 50 -> 100 VUs over 5.5 minutes
- Constant-rate scenario: configurable TPS, 3 min default, up to 200 VUs
- `handleSummary()` writes JSON to `scripts/perf/results/`
- `setup()` health-checks all 4 services before starting

**Usage:**
```bash
./scripts/perf/run-perf-test.sh --scenario smoke          # Quick sanity
./scripts/perf/run-perf-test.sh --scenario constant --tps 50  # Steady-state
./scripts/perf/run-perf-test.sh --scenario saga --vus 10   # Saga e2e
./scripts/perf/run-perf-test.sh --scenario mixed --gateway # Via gateway
```

**Infrastructure change:** Added `--web.enable-remote-write-receiver` to Prometheus in `docker-compose.yml` for k6 Prometheus remote-write output.

---

### Session 3: Enhanced Metrics Instrumentation — COMPLETED

**Objectives:** Fill metric gaps from Session 1, create "Performance Testing" Grafana dashboard.

**Deliverables:**
- 3 new gauges added to `EventSourcingController.java`:
  - `eventsourcing.pending.completions` — pendingCompletions ConcurrentMap size
  - `eventsourcing.pending.events` — pendingEventsMap IMap size
  - `eventsourcing.completions.orphaned` — orphaned completion counter
- `docker/grafana/dashboards/performance-testing.json` — 20 panels, 4 rows

**Dashboard panels (4 rows x 4 panels):**

| Row | Panels |
|-----|--------|
| **Request Throughput & Latency** | TPS by Operation, Gateway Duration p50/p95/p99, Error Rate, HTTP Status Distribution (pie) |
| **Pipeline Performance** | Stage Duration (stacked), E2E Latency Heatmap, Queue Wait p50/p95/p99, Events in Flight (gauge) |
| **Saga Performance** | Active Sagas (stat), Saga Duration p50/p95/p99, Saga Completion Rate, Orphaned Completions (stat) |
| **Persistence & System** | Write-Behind Duration p95, Persistence Error Rate, JVM Heap Used, GC Pause Duration |

**Remaining metric gaps (for future sessions):**
- `hazelcast.imap.operation.duration` timer
- `hazelcast.itopic.publish.duration` timer
- `gateway.request.concurrent` gauge
- Event Journal fill ratio
- Near Cache hit ratio

---

### Session 4: Scale Up Sample Data — COMPLETED

**Objectives:** Generate ~100 customers and ~100 products with rich descriptions for meaningful vector similarity.

**Deliverables:**
- `scripts/perf/generate-sample-data.sh` — Creates 100 customers + 100 products via REST API
- `scripts/perf/data/customers.json` — 100 customers (10 US cities, 5 email domains, diverse names)
- `scripts/perf/data/products.json` — 100 products (8 categories, SKU format CAT-NNN, $9.99-$2999.99)
- `scripts/perf/data/perf-data-ids.sh` — Shell-format IDs for wrapper scripts
- `scripts/perf/data/customer-ids.json` / `product-ids.json` — JSON arrays for k6 SharedArray

**Product categories (8):**
LAP (13), PER (13), STO (12), NET (12), AUD (12), ACC (13), DIS (12), FUR (13)

**Product data strategy:** 2-4 sentence descriptions with vocabulary creating meaningful similarity clusters for vector search. Products targeting similar audiences share vocabulary across categories (e.g., "creative professional", "enterprise IT", "gaming").

**Customer data strategy:** Round-robin across 10 US cities, 5 email domains (20 each), diverse names across ethnicities. Deterministic for reproducibility.

**Performance:** 100 customers + 100 products loaded in 14 seconds.

---

### Session 5: Profiling Setup and First Flame Graphs — PENDING

**Objectives:** Set up async-profiler for Docker containers, generate CPU and allocation flame graphs, identify top 5 hotspots.

**Deliverables:**
- `docker/profiling/Dockerfile.profiled` — Dockerfile with async-profiler baked in
- `docker/docker-compose-profiling.yml` — Override file
- `scripts/perf/profile-service.sh` — Attach profiler, capture flame graph
- `docs/perf/flamegraph-analysis-session5.md` — Analysis
- Flame graph HTML files in `docs/perf/flamegraphs/`

**Profiling workflow:** Start profiled services -> run k6 constant-rate 3 min -> capture 60s CPU flame graph from order-service -> repeat with allocation profiling -> analyze.

**Expected hotspot areas:** GenericRecord serialization, FlakeIdGenerator, IMap.set(), CompletableFuture completion, HTTP parsing, Jet pipeline processing.

**Priority investigation:** Saga completion bottleneck (1,933 stuck active sagas from baseline).

**Success:** Flame graphs generated from Docker containers, top 5 CPU and allocation hotspots identified and documented.

---

### Session 6: First Optimization Iteration — PENDING

**Objectives:** Fix top 2-3 bottlenecks from Session 5, re-measure, document before/after.

**Deliverables:**
- Code changes for bottleneck fixes
- `docs/perf/optimization-iteration-1.md` — Before/after measurements
- Updated flame graphs showing improvement

**Likely optimizations** (confirmed by profiling):
1. Custom CompactSerializers for hot-path event types (if serialization is a hotspot)
2. Event Journal capacity tuning (default 10K may be too small)
3. Near Cache for view maps (eliminate network round-trips for repeated reads)
4. Completion notification optimization (if EntryAddedListener is a bottleneck)

**Methodology:** Run k6 at the load where p99 exceeds target -> record baseline -> apply one change -> re-run identical test -> record improvement -> revert if <5%.

**Success:** At least one measurable improvement (>10% p95/p99 reduction or >10% TPS increase), clear before/after documentation.

---

### Session 7: A-B Testing Framework — PENDING

**Objectives:** Build A-B test harness, compare HD Memory vs On-Heap and TPC vs default threading (Enterprise features).

**Deliverables:**
- `scripts/perf/ab-test.sh` — Automated A-B runner
- `docker/docker-compose-hd-memory.yml` — HD Memory override
- `docker/docker-compose-tpc.yml` — TPC override
- `docs/perf/ab-test-results.md`

**A-B flow:** Start config A -> health check -> load data -> run k6 -> save results -> stop -> start config B -> repeat -> compare.

**Configs:** A=Community baseline, B1=HD Memory, B2=TPC, B3=Both.

**Measures:** TPS diff, latency percentiles diff, memory usage, GC pause frequency, thread count.

**Caveats to document:** Enterprise license required; laptop may not show TPC benefit (multi-core advantage); 100 products may not stress heap enough for HD Memory.

**Success:** A-B script runs unattended, results clearly show where Enterprise features help/don't.

---

### Session 8: Sustained Load and Stability Testing — PENDING

**Objectives:** Run 30-minute and 1-hour tests to find memory leaks, GC accumulation, unbounded growth.

**Deliverables:**
- `scripts/perf/k6-scenarios/sustained-load.js`
- `docs/perf/stability-analysis.md`
- Fixes for any stability issues

**What to watch:** Heap trend (sawtooth vs monotonic growth), completions map size, pending events map, PostgreSQL connection pool, Event Journal wrap, Docker memory, `pendingCompletions` ConcurrentHashMap growth.

**Key risks:** `pendingCompletions` orphans from 30s timeouts; completions map 1h TTL with no max-size (50 TPS = 180K entries/hr); event store unbounded without eviction.

**Success:** 30 min at 50 TPS, no OOM, error rate <1%, stability issues documented and fixed.

---

### Session 9: JMH Micro-Benchmarks — PENDING

**Objectives:** Create JMH benchmarks for framework internals, measure serialization/IMap/pipeline in isolation.

**Deliverables:**
- `framework-core/src/jmh/java/com/theyawns/framework/bench/` — Benchmark classes
- Maven `benchmark` profile in `framework-core/pom.xml`
- `docs/perf/microbenchmark-results.md`

**Benchmarks:** GenericRecord serialization (toGenericRecord/fromGenericRecord), EventStore append (IMap.set), ViewStore update (executeOnKey), FlakeIdGenerator throughput, PartitionedSequenceKey creation.

**Success:** Benchmarks run via `mvn verify -Pbenchmark`, results documented, most expensive per-event operation identified.

---

### Session 10: Hazelcast Simulator Evaluation (Optional) — PENDING

**Objectives:** Evaluate Simulator for IMap/ITopic throughput testing against shared cluster; determine if Hazelcast or application code is the primary bottleneck.

**Deliverables:**
- `scripts/perf/simulator/` — Simulator configs
- `docs/perf/simulator-evaluation.md`

**Tests:** IMap.put on `*_PENDING` maps, IMap.get on `*_VIEW` maps, ITopic.publish, Event Journal read throughput.

**Decision point:** If Hazelcast ops are sub-millisecond at our scale -> focus optimization on application code. If Hazelcast IS the bottleneck -> Enterprise features become more relevant.

**Success:** Simulator runs against local cluster, throughput numbers documented, bottleneck source identified.

---

### Session 11: Multi-Deployment Testing — PENDING

**Objectives:** Run performance tests on Kubernetes, demonstrate vertical scaling, compare Docker Compose vs K8s.

**Deliverables:**
- `scripts/perf/k8s-perf-test.sh`
- `docs/perf/deployment-comparison.md`

**Work:** Deploy via Helm -> run k6 against K8s services -> compare with Docker Compose baseline. Scaling test: 1 replica vs increased resources. Cloud testing if available: EKS/GKE/AKS at 100/200/500 TPS.

**Note:** ADR 010 is single-replica; multi-replica requires PostgreSQL state sharing. Test validates vertical scaling (more CPU/memory helps).

**Success:** Performance numbers from Docker Compose and K8s, comparison table, vertical scaling demonstrated.

---

### Session 12: Documentation, Guide, and Blog Post — PENDING

**Objectives:** Compile findings into performance tuning guide, write blog post, create production checklist.

**Deliverables:**
- `docs/guides/performance-tuning-guide.md` — Comprehensive adopter guide
- `docs/perf/performance-exercise-summary.md` — All sessions summary
- `docs/perf/production-checklist.md` — Quick-reference
- Blog post draft

**Guide structure:** Metrics taxonomy -> Baseline methodology -> Known bottlenecks & solutions -> Config tuning -> Enterprise features -> Profiling with async-profiler -> Load testing with k6 -> JMH benchmarks -> Scaling -> Production checklist.

**Blog:** "Performance Engineering for Event Sourcing with Hazelcast" — architecture recap, measurement methodology, flame graph stories, optimization cycle, Enterprise vs Community, results table, key takeaways.

**Success:** Guide sufficient for new adopter to reproduce measurements, blog post publication-ready.

---

## Session Dependencies

```
Session 1 (Baseline) ──┬──> Session 2 (k6) ──┬──> Session 5 (Profiling)
                        │                     │
                        └──> Session 3 (Metrics) ──┘──> Session 6 (Optimize)
                                              │
Session 4 (Data) ────────────────────────────>┘

Session 6 ──> Session 7 (A-B Tests)
Session 6 ──> Session 8 (Sustained Load)
Session 6 ──> Session 9 (JMH Benchmarks)

Session 7,8,9 ──> Session 10 (Simulator, optional)
Session 7,8,9 ──> Session 11 (K8s/Cloud)

Session 10,11 ──> Session 12 (Documentation & Blog)
```

Sessions 1-4 are sequential foundation (COMPLETE). Sessions 5-6 form the core profiling loop. Sessions 7-9 are independent. Session 10 is optional. Session 12 depends on having results.

---

## Directory Structure

```
scripts/perf/
  k6-load-test.js              # Main k6 script (smoke, ramp_up, constant_rate)
  k6-config.js                 # Shared config (URLs, thresholds, helpers)
  run-perf-test.sh             # Bash wrapper (scenario selection, ID loading)
  generate-sample-data.sh      # Load 100 customers + 100 products
  k6-scenarios/
    create-orders.js            # Focused order creation
    mixed-workload.js           # 60/25/15 mixed workload
    saga-e2e.js                 # Saga completion polling
    sustained-load.js           # (Session 8)
  data/
    customers.json              # 100 pre-generated customers
    products.json               # 100 pre-generated products (8 categories)
    perf-data-ids.sh            # Shell-format IDs (generated at runtime)
    customer-ids.json           # JSON array for k6 SharedArray
    product-ids.json            # JSON array for k6 SharedArray
  results/                      # k6 JSON output files
  ab-test.sh                   # (Session 7)
  profile-service.sh           # (Session 5)
  simulator/                   # (Session 10, optional)

docs/perf/
  metrics-inventory.md          # 42 metrics cataloged, 10 gaps identified
  baseline-measurements.md      # TPS/latency at 10/25/50 TPS with Prometheus data
  flamegraph-analysis-session5.md   # (Session 5)
  optimization-iteration-1.md       # (Session 6)
  ab-test-results.md                # (Session 7)
  stability-analysis.md             # (Session 8)
  microbenchmark-results.md         # (Session 9)
  simulator-evaluation.md           # (Session 10, optional)
  deployment-comparison.md          # (Session 11)
  performance-exercise-summary.md   # (Session 12)
  production-checklist.md            # (Session 12)
  flamegraphs/                       # HTML files (Session 5)

docker/grafana/dashboards/
  performance-testing.json      # 20 panels, 4 rows (Session 3)

docker/profiling/               # (Session 5)
  Dockerfile.profiled
docker/docker-compose-profiling.yml  # (Session 5)
docker/docker-compose-hd-memory.yml  # (Session 7)
docker/docker-compose-tpc.yml       # (Session 7)

framework-core/src/jmh/        # (Session 9)
```

---

## Critical Files (existing)

- `framework-core/.../controller/EventSourcingController.java` — Primary profiling/optimization target (3 new gauges added Session 3)
- `framework-core/.../pipeline/EventSourcingPipeline.java` — 6-stage Jet pipeline
- `framework-core/.../pipeline/PipelineMetrics.java` — 9 pipeline metrics
- `framework-core/.../saga/SagaMetrics.java` — 21 saga metrics
- `framework-core/.../persistence/PersistenceMetrics.java` — 9 persistence metrics
- `framework-core/.../store/HazelcastEventStore.java` — IMap operation timing
- `framework-core/.../view/HazelcastViewStore.java` — View update timing
- `framework-core/.../config/HazelcastConfig.java` — Map configs, journal capacity
- `api-gateway/.../filter/RequestTimingFilter.java` — Gateway request duration timer
- `docker/docker-compose.yml` — Base Docker config (Prometheus remote-write enabled)
- `docker/grafana/dashboards/` — 6 dashboards (including new performance-testing.json)
- `docker/prometheus/prometheus.yml` — 7 scrape jobs at 15s interval

## Verification

After each session, verify by:
1. Running the full test suite (`mvn clean test`) for any code changes
2. Starting Docker Compose and running the session's test/tool to confirm it produces expected output
3. Checking Grafana dashboards reflect new data points
4. Comparing measurements against prior session's baseline
