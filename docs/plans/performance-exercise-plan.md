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

### Session 5: Profiling Setup and First Flame Graphs — COMPLETED

**Objectives:** Set up async-profiler for Docker containers, generate CPU and allocation flame graphs, identify top 5 hotspots.

**Deliverables:**
- `docker/profiling/Dockerfile.profiled` — Dockerfile with async-profiler baked in
- `docker/docker-compose-profiling.yml` — Override file with SYS_PTRACE, JVM flags, 768M memory
- `scripts/perf/profile-service.sh` — Bash 3.2-compatible profiling orchestrator (warmup → k6 background → capture → extract)
- `docker/profiling/download-async-profiler.sh` — Auto-downloads async-profiler v4.3 for correct arch
- `docs/perf/flamegraph-analysis-session5.md` — Full analysis with subsystem breakdowns
- `docs/perf/flamegraphs/order-service-cpu-*.html` and `order-service-alloc-*.html`

**Profiling configuration:** async-profiler v4.3, itimer mode (perf_events unavailable in Docker Desktop on macOS), 30s captures at 25 TPS with 30s warmup.

**Key findings (order-service at 25 TPS):**

| Subsystem | CPU % | Allocation % |
|-----------|-------|-------------|
| Outbox Publisher (`IMap.values()` full scan) | ~24% inclusive | ~28% |
| Hazelcast IMap operations | 28% | 45% |
| Compact Serialization | 10% | 39% (byte[] buffers) |
| Spring/Tomcat HTTP | 32% | 34% |
| Jet pipeline | 9% | 14% |
| JIT compilation | 14% | — |

**Top CPU hotspot (self time):** `pthread_cond_signal` (8.9%) — thread unparking after async IMap ops.

**#1 optimization target identified:** Outbox polling via `IMap.values(predicate)` without an index forces full partition scan + deserialization every poll cycle → ~25% of CPU and allocation.

**Success:** Flame graphs generated, top hotspots identified, clear optimization target for Session 6.

---

### Session 6: First Optimization Iteration — COMPLETED

**Objectives:** Fix the #1 bottleneck (outbox polling) from Session 5, re-measure, document before/after.

**Deliverables:**
- `docs/plans/perf-session6-optimization-iteration-1.md` — Optimization plan
- `docs/perf/optimization-iteration-1.md` — Before/after results template
- 3 code changes in `framework-core` (all tests pass)

**Optimizations applied (3 changes):**

1. **Outbox map indexes** (`HazelcastConfig.java`): Added `IndexConfig(HASH, "status")` and `IndexConfig(SORTED, "createdAt")` to the `framework_OUTBOX` MapConfig. Eliminates full partition scan for equality predicates and enables server-side sort.

2. **PagingPredicate in pollPending()** (`HazelcastOutboxStore.java`): Replaced `values(predicate)` + Java `.sorted().limit()` with `PagingPredicate` that pushes sort + page-size limit to the Hazelcast query engine. Also switched `pendingCount()` from `values().size()` to `keySet().size()` to avoid deserializing full entry values.

3. **Timer fix + empty-poll counter** (`OutboxPublisher.java`): Wrapped entire publish cycle in try/finally so `outbox.publish.duration` records all poll cycles (not just non-empty ones). Added `outbox.poll.empty` counter for idle cycles.

**Verification:** `mvn clean test` — all 10 modules pass, 0 failures.

**Success:** All three optimizations implemented and verified. Re-profiling pending to measure improvement.

---

### Session 7: A-B Testing Framework — COMPLETED

**Objectives:** Build general-purpose A-B test harness, compare HD Memory vs On-Heap and TPC vs default threading (Enterprise features).

**Deliverables:**
- `scripts/perf/ab-test.sh` — Automated A-B orchestrator (config profiles, health checks, k6 runs, Docker stats capture, manifest JSON for retained results)
- `scripts/perf/ab-compare.sh` — Comparison engine (jq + bc, markdown report with ASCII bar charts)
- `scripts/perf/ab-chart.py` — Optional matplotlib PNG chart generator (graceful fallback)
- `scripts/perf/ab-configs/` — 6 config profiles (community-baseline, hd-memory, tpc, hd-memory-tpc, high-memory, profiling)
- `docker/docker-compose-hd-memory.yml` — HD Memory override (Enterprise image, native-memory config, 1024M limits)
- `docker/docker-compose-tpc.yml` — TPC override (Enterprise image, thread-per-core networking)
- `docker/hazelcast/hazelcast-hd-memory.yaml` — HD Memory Hazelcast config (native-memory enabled, NATIVE in-memory-format)
- `.claude/skills/ab-test/SKILL.md` — Claude `/ab-test` skill for guided A-B test setup

**A-B flow:** Clean slate (`docker compose down -v`) → compose up from config profile → health check (180s timeout) → load 100 customers + 100 products → 10s stabilization → k6 at constant 50 TPS for 3m → capture Docker stats → save results + manifest JSON → tear down → repeat for variant B → generate comparison report.

**A-B test results (3 tests run, all at 50 TPS for 3 minutes):**

| Test | Winner | Key Finding |
|------|--------|-------------|
| Baseline vs High Memory (512M→1024M) | Baseline 3/4 p95 | No meaningful difference — services not memory-constrained at this scale |
| Baseline vs TPC (Enterprise) | TPC 3/4 p95 | Marginal gains (0.5-1.1ms faster p95), 14% lower max latency, fewer errors |
| Baseline vs HD Memory (Enterprise) | Baseline 4/4 p95 | HD Memory adds serialization overhead crossing JVM heap boundary; not justified at small scale (~200 entries) |

**Key findings:**
- At 50 TPS with ~200 entries, Enterprise features provide marginal or no benefit — the workload is neither I/O-bound enough for TPC nor memory-intensive enough for HD Memory
- HD Memory has a measurable per-access cost (+3-24% p95 latency) from native off-heap serialization that only pays off with large datasets causing GC pressure
- TPC shows promise for higher concurrency workloads where its event-loop architecture outperforms traditional I/O threading
- The A-B framework itself is reusable for any configuration comparison (not limited to Enterprise features)

**Reports:** `docs/performance/ab-baseline-vs-high-memory-*.md`, `ab-baseline-vs-tpc-*.md`, `ab-baseline-vs-hd-memory-*.md`

**Success:** A-B script runs unattended (~9 min per test), results retained with manifest JSON for cross-run comparison, clear reports showing where Enterprise features help and don't.

---

### Session 8: Sustained Load and Stability Testing — COMPLETED

**Objectives:** Run 30-minute sustained tests to find memory leaks, GC accumulation, unbounded growth. Capture automated Grafana dashboard screenshots.

**Deliverables:**
- `scripts/perf/k6-scenarios/sustained-load.js` — k6 script with checkpoint logging (every 5 min)
- `scripts/perf/run-sustained-test.sh` — Master orchestrator (setup, load + periodic capture, summary)
- `scripts/perf/capture-dashboards.sh` — Automated Grafana dashboard PNG capture (all 6 dashboards)
- `docker/docker-compose-renderer.yml` — Grafana Image Renderer overlay
- `docs/perf/stability-analysis.md` — Full analysis with memory trends and fix recommendations
- `docs/perf/screenshots/` — 6 showcase-quality PNG dashboard screenshots from 30-minute run
- 2 fixes applied (memory limits + completions eviction)

**30-minute test results (50 TPS):**

| Metric | Value |
|--------|-------|
| Total iterations | 89,916 |
| Orders created | 53,756 |
| Customers created | 13,532 |
| OOM kills | None |
| k6 exit code | 0 (PASS) |
| Order create p95 | 29.72ms |
| Screenshots captured | 18 (3 checkpoints x 6 dashboards) |

**Key findings:**
- Services hit 512MiB memory ceiling within 12-21 minutes, running under GC pressure for remaining test
- Order service peaked at 99.8% memory with CPU spikes to 59% (GC thrashing)
- No OOM kills — JVM GC kept services alive but under stress
- Latency remained excellent (p95 < 30ms) despite memory pressure
- 25% error rate was entirely from stock depletion (functional, not stability)
- `pendingCompletions` ConcurrentHashMap remained bounded (30s timeout works)

**Fixes applied:**
1. **Docker memory limits**: 512MiB → 1024MiB for all 4 microservices (JVM heap: 512m → 768m)
2. **Completions map eviction**: Added `MaxSizePolicy.PER_NODE` with 50K entry limit as safety net

**Automated dashboard capture pipeline:**
- `capture-dashboards.sh` uses Grafana Image Renderer for server-side PNG rendering
- Periodic capture loop in orchestrator captures every N minutes during test
- Final full-window capture provides showcase-quality images for sharing

**Success:** 30 min at 50 TPS, no OOM, stability issues documented and fixed, showcase screenshots committed.

---

### Session 9: JMH Micro-Benchmarks — COMPLETED

**Objectives:** Create JMH benchmarks for framework internals, measure serialization/IMap/pipeline in isolation.

**Deliverables:**
- `framework-core/src/jmh/java/com/theyawns/framework/bench/` — 8 benchmark classes (3 fixtures + 5 benchmarks)
- Maven `benchmark` profile in `framework-core/pom.xml` (build-helper, shade, compiler annotation processing)
- `docs/perf/microbenchmark-results.md` — Full results with analysis

**Benchmarks (20 methods across 5 classes):**
- SerializationBenchmark (6): event/domain obj toGenericRecord, fromGenericRecord, roundTrip
- EventStoreAppendBenchmark (2): preBuilt record vs with serialization
- ViewStoreUpdateBenchmark (3): executeOnKey update/creation, directPut baseline
- FlakeIdGeneratorBenchmark (3): 1/4/8 thread scaling
- PartitionedSequenceKeyBenchmark (6): creation, serialization, hashCode, equals

**Key results (JDK 24, Apple Silicon, 2 forks x 5 iterations x 5s):**

| Operation | Cost (us/op) |
|-----------|:------------:|
| FlakeIdGenerator.newId() | 15.6 |
| EventStore.append (IMap.set) | 11.2 |
| ViewStore.executeOnKey | 9.3 |
| Event.toGenericRecord (14 fields) | 1.1 |
| Event.fromGenericRecord | 0.3 |
| PartitionedSequenceKey.creation | 0.004 |

**Most expensive operation:** FlakeIdGenerator.newId() at 15.6 us (42% of per-event cost), with severe contention scaling: 4 threads = 69 us, 8 threads = 151 us.

**Key finding:** Total per-event framework internal cost is ~37 us — less than 0.5% of pipeline p50 (8.7 ms). System-level overhead (Jet scheduling, thread coordination, HTTP) dominates by 100-200x.

**Success:** `mvn clean test` unaffected (benchmark sources ignored without `-Pbenchmark`), benchmarks.jar built and run, results documented, FlakeIdGenerator identified as most expensive per-event operation.

---

### Session 10: Hazelcast Cluster Benchmarks & Simulator Evaluation — COMPLETED

**Objectives:** Benchmark the shared 3-node Hazelcast cluster (ITopic pub/sub and saga state IMap) in isolation; evaluate Hazelcast Simulator as a tool.

**Approach:** Extended existing JMH infrastructure with cluster-aware benchmarks (HazelcastClient connecting to Docker Compose cluster). Tried Hazelcast Simulator Docker image for evaluation.

**Deliverables:**
- `framework-core/src/jmh/java/.../bench/ClusterBenchmarkState.java` — JMH state managing HazelcastClient lifecycle
- `framework-core/src/jmh/java/.../bench/ClusterIMapBenchmark.java` — 5 IMap benchmarks over the network
- `framework-core/src/jmh/java/.../bench/ClusterITopicBenchmark.java` — 3 ITopic benchmarks (fire-and-forget + round-trip)
- `framework-core/src/jmh/java/.../bench/ClusterEventJournalBenchmark.java` — 2 benchmarks comparing journaled vs plain map
- `scripts/perf/run-cluster-benchmarks.sh` — Orchestration script (cluster health check → build → run → results)
- `scripts/perf/simulator/try-simulator.sh` — Simulator evaluation script
- `docs/perf/cluster-benchmark-results.md` — Full results with analysis

**Key Results (10 benchmark methods, JDK 24, Apple Silicon):**

| Operation | Avg (us/op) | Error |
|-----------|:-----------:|:-----:|
| IMap.set (small ~200B) | 311.3 | ± 8.4 |
| IMap.set (large ~1KB) | 313.8 | ± 6.6 |
| IMap.get (hit) | 268.0 | ± 18.7 |
| IMap.get (miss) | 267.3 | ± 10.7 |
| IMap.set + get round-trip | 568.3 | ± 27.4 |
| ITopic.publish (small) | 307.5 | ± 8.5 |
| ITopic.publish (large) | 355.0 | ± 44.5 |
| ITopic round-trip | 185.6 | ± 24.1 |
| Event Journal write | 338.2 | ± 32.1 |
| Plain map write | 322.9 | ± 16.2 |

**Embedded vs Cluster overhead:** IMap.set is **28-42x** slower over the network (7-11 us embedded → 311-314 us cluster).

**Saga latency breakdown:**

| Layer | Cost | % of Pipeline p50 |
|-------|:----:|:-----------------:|
| Framework internals (Session 9) | ~37 us | 0.4% |
| Hazelcast cluster ops (Session 10) | ~2,400 us | 27.6% |
| System overhead (Jet + HTTP + threads) | ~6,263 us | 72.0% |

**Decision: Hazelcast is a significant contributor (~28%) but not the bottleneck.** System-level overhead (Jet scheduling, thread coordination, HTTP) dominates at ~72%.

**Simulator evaluation:** Image is 4.82 GB, requires coordinator/agent/worker setup, designed for cloud-scale (50+ nodes). **Not recommended** for this project's 3-node Docker Compose cluster. JMH via HazelcastClient provides equivalent measurements with far less setup.

**Technical fix discovered:** HazelcastClient smart routing must be disabled when connecting from host to Docker cluster (cluster members advertise Docker-internal hostnames).

**Success:** All 10 benchmarks run, bottleneck source identified, Simulator evaluated and documented.

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

Sessions 1-10 are complete. Session 11-12 depend on having results.

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
  ab-test.sh                   # A-B test orchestrator (Session 7)
  ab-compare.sh                # Comparison engine — jq + bc + ASCII charts (Session 7)
  ab-chart.py                  # Optional matplotlib PNG charts (Session 7)
  ab-configs/                  # A-B config profiles (Session 7)
    community-baseline.conf
    hd-memory.conf
    tpc.conf
    hd-memory-tpc.conf
    high-memory.conf
    profiling.conf
  ab-results/                  # A-B run results with manifests (gitignored)
  capture-dashboards.sh        # Grafana dashboard PNG capture (Session 8)
  run-sustained-test.sh        # Sustained test orchestrator (Session 8)
  sustained-results/           # Sustained test output (gitignored)
  profile-service.sh           # (Session 5)
  run-cluster-benchmarks.sh    # Cluster JMH orchestrator (Session 10)
  simulator/                   # Simulator evaluation (Session 10)
    try-simulator.sh

docs/perf/
  metrics-inventory.md          # 42 metrics cataloged, 10 gaps identified
  baseline-measurements.md      # TPS/latency at 10/25/50 TPS with Prometheus data
  flamegraph-analysis-session5.md   # (Session 5)
  optimization-iteration-1.md       # (Session 6)
  ab-baseline-vs-high-memory-*.md   # (Session 7) — no meaningful difference
  ab-baseline-vs-tpc-*.md           # (Session 7) — TPC wins 3/4 p95 metrics
  ab-baseline-vs-hd-memory-*.md     # (Session 7) — baseline wins 4/4 p95 metrics
  stability-analysis.md             # (Session 8)
  microbenchmark-results.md         # (Session 9)
  cluster-benchmark-results.md      # (Session 10)
  deployment-comparison.md          # (Session 11)
  performance-exercise-summary.md   # (Session 12)
  production-checklist.md            # (Session 12)
  flamegraphs/                       # HTML files (Session 5)

docker/grafana/dashboards/
  performance-testing.json      # 20 panels, 4 rows (Session 3)

docker/profiling/               # (Session 5)
  Dockerfile.profiled
docker/docker-compose-profiling.yml  # (Session 5)
docker/docker-compose-hd-memory.yml  # (Session 7) — Enterprise image, native-memory, 1024M limits
docker/docker-compose-tpc.yml       # (Session 7) — Enterprise image, TPC networking
docker/docker-compose-renderer.yml  # (Session 8) — Grafana Image Renderer for dashboard screenshots
docker/hazelcast/hazelcast-hd-memory.yaml  # (Session 7) — native-memory config

framework-core/src/jmh/        # (Session 9 embedded + Session 10 cluster benchmarks)
  java/com/theyawns/framework/bench/
    BenchmarkEvent.java                 # (Session 9)
    BenchmarkDomainObj.java             # (Session 9)
    HazelcastBenchmarkState.java        # (Session 9) embedded HZ state
    ClusterBenchmarkState.java          # (Session 10) HazelcastClient state
    ClusterIMapBenchmark.java           # (Session 10) 5 IMap benchmarks
    ClusterITopicBenchmark.java         # (Session 10) 3 ITopic benchmarks
    ClusterEventJournalBenchmark.java   # (Session 10) 2 journal benchmarks
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
