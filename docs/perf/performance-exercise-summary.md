# Performance Exercise Summary

> Compact reference for the 12-session performance engineering exercise conducted on the Hazelcast Microservices Framework (Feb 17–21, 2026).

---

## Sessions at a Glance

| Session | Topic | Key Deliverable | Key Result |
|:-------:|-------|-----------------|------------|
| 1 | Metrics Inventory & Baseline | `metrics-inventory.md`, `baseline-measurements.md` | 46 metrics cataloged; saga completion is the bottleneck (79% completion rate at 50 TPS) |
| 2 | k6 Load Test Scripts | 4 k6 scenarios + wrapper script | `constant-arrival-rate` executor, per-operation latency tracking, saga e2e polling |
| 3 | Enhanced Metrics | 3 new gauges, Performance Testing dashboard | `pending.completions`, `pending.events`, `completions.orphaned` gauges; 20-panel Grafana dashboard |
| 4 | Sample Data Scale-Up | 100 customers + 100 products | 8 product categories with similarity-friendly descriptions; 14s data load |
| 5 | Profiling & Flame Graphs | `flamegraph-analysis-session5.md` | Outbox polling = #1 hotspot (~24% CPU, ~28% allocation) |
| 6 | Optimization Iteration 1 | Outbox indexes + PagingPredicate | CPU halved (24% → 12%), total allocations down 34% |
| 7 | A-B Testing Framework | `ab-test.sh`, 3 A-B tests | Enterprise HD Memory/TPC provide marginal benefit at current scale |
| 8 | Sustained Load & Stability | `stability-analysis.md`, 6 dashboard screenshots | 30 min at 50 TPS — no OOM; services hit 512MiB ceiling at 12 min; fixed to 1Gi |
| 9 | JMH Micro-Benchmarks | `microbenchmark-results.md` | Per-event framework cost = ~37 us (< 0.5% of pipeline p50); FlakeIdGenerator = most expensive op |
| 10 | Cluster Benchmarks | `cluster-benchmark-results.md` | IMap ops ~310 us over network (28-42x embedded); Hazelcast = 28% of saga latency |
| 11 | Multi-Deployment Testing | `deployment-comparison-final.md` | 4 tiers tested; per-service clustering = -95% saga E2E; 200 TPS sustained |
| 12 | Documentation & Guide | This summary, tuning guide, checklist, blog post | Final synthesis of all findings |

---

## Session Details

### Session 1: Metrics Inventory & Baseline

Cataloged all 46 application-level Micrometer metrics across 5 components (Pipeline: 9, Saga: 21, Persistence: 9, Gateway: 1, Controller: 6). Identified 10 instrumentation gaps. Established baseline at 10/25/50 TPS using k6 `constant-arrival-rate`. Key finding: HTTP latency excellent (p95 < 30ms) but saga completion rate only 79.1% at 50 TPS — root cause was unregistered `SagaTimeoutAutoConfiguration`.

**Deliverables:** [`metrics-inventory.md`](metrics-inventory.md), [`baseline-measurements.md`](baseline-measurements.md)

### Session 2: k6 Load Test Scripts

Built the load testing infrastructure: main k6 script with smoke/ramp-up/constant-rate scenarios, mixed-workload and saga-e2e specialized scripts, and a Bash 3.2-compatible wrapper. Added Prometheus remote-write to k6 for real-time Grafana integration. Scripts handle customer/product ID loading via `SharedArray`.

**Deliverables:** `scripts/perf/k6-load-test.js`, `scripts/perf/run-perf-test.sh`, 3 scenario scripts

### Session 3: Enhanced Metrics Instrumentation

Filled high-priority metric gaps: added `eventsourcing.pending.completions` (pipeline backpressure gauge), `eventsourcing.pending.events` (IMap queue depth), and `eventsourcing.completions.orphaned` (late-completion detector). Built a 20-panel Performance Testing Grafana dashboard with 4 rows covering throughput, pipeline, saga, and persistence metrics.

**Deliverables:** 3 new gauges in `EventSourcingController.java`, `docker/grafana/dashboards/performance-testing.json`

### Session 4: Sample Data Scale-Up

Generated 100 customers (10 US cities, 5 email domains) and 100 products (8 categories, $9.99–$2,999.99) with 2–4 sentence descriptions creating meaningful similarity clusters for vector search. Deterministic generation for reproducibility. Data loads in 14 seconds.

**Deliverables:** `scripts/perf/generate-sample-data.sh`, JSON data files, k6-compatible ID arrays

### Session 5: Profiling & Flame Graphs

Set up async-profiler v4.3 (itimer mode) in Docker containers with JVM flags for frame pointer preservation. Captured CPU and allocation flame graphs of order-service at 25 TPS. Outbox polling (`IMap.values(predicate)` without indexes) identified as the #1 optimization target at ~24% inclusive CPU and ~28% of allocations.

**Deliverables:** [`flamegraph-analysis-session5.md`](flamegraph-analysis-session5.md), profiling infrastructure, HTML flame graphs

### Session 6: Optimization Iteration 1

Applied three targeted optimizations: HASH index on outbox `status` field, SORTED index on `createdAt`, and `PagingPredicate` replacing `values()` + Java sort. Outbox CPU cost halved (24% → 12%), total allocation samples dropped 34% (1,093 → 719). No new dominant hotspot emerged — CPU profile well-distributed.

**Deliverables:** [`optimization-iteration-1.md`](optimization-iteration-1.md), 3 code changes in `framework-core`

### Session 7: A-B Testing Framework

Built automated A-B test harness (`ab-test.sh` + `ab-compare.sh`) with config profiles, health checks, k6 runs, and markdown reports with ASCII charts. Tested three Enterprise Edition features at 50 TPS: High Memory (no benefit — not memory-constrained), TPC (marginal 0.5–1.1ms p95 improvement), HD Memory (worse — serialization overhead outweighs benefit at small scale).

**Deliverables:** `scripts/perf/ab-test.sh`, `scripts/perf/ab-compare.sh`, 6 config profiles, 3 A-B test reports

### Session 8: Sustained Load & Stability

Ran 30-minute sustained load test at 50 TPS (89,916 iterations). No OOM kills but services hit 512MiB ceiling within 12–21 minutes, running under GC pressure for the remainder. Order-service peaked at 99.9% memory with CPU spikes to 59%. Latency remained excellent (p95 < 30ms). Fixed: memory limits 512MiB → 1GiB, completions map 50K entry safety net.

**Deliverables:** [`stability-analysis.md`](stability-analysis.md), 6 dashboard screenshots, 2 fixes

### Session 9: JMH Micro-Benchmarks

Created 20 JMH benchmarks across 5 classes measuring framework internals in isolation. Per-event cost breakdown: FlakeIdGenerator 15.6 us (42%), EventStore.append 11.2 us (30%), ViewStore.executeOnKey 9.3 us (25%), serialization 1.1 us (3%). Total ~37 us per event — less than 0.5% of pipeline p50 (8.7 ms). FlakeIdGenerator contention scales super-linearly: 8 threads = 151 us (9.7x single-thread).

**Deliverables:** [`microbenchmark-results.md`](microbenchmark-results.md), 8 JMH classes, Maven benchmark profile

### Session 10: Cluster Benchmarks

Benchmarked the shared 3-node Hazelcast cluster via JMH with HazelcastClient. IMap operations cost ~270–340 us over the network (28–42x embedded). A full saga cycle incurs ~2.4 ms of Hazelcast network cost — 28% of pipeline p50. Event journal overhead is negligible (4.7%). Evaluated and rejected Hazelcast Simulator (4.82 GB image, complex setup, designed for 50+ node clouds).

**Deliverables:** [`cluster-benchmark-results.md`](cluster-benchmark-results.md), 3 JMH benchmark classes, orchestration script

### Session 11: Multi-Deployment Testing

Tested 4 deployment tiers: Local (Docker Desktop), AWS Small (2x t3.xlarge), AWS Medium (3x c7i.2xlarge), AWS Large (5x c7i.4xlarge). The breakthrough was per-service embedded clustering (ADR 013): same-service replicas form their own Hazelcast cluster via K8s DNS discovery. Results: saga E2E improved 95% (10s timeout → 513ms), HPA scaled 2→5 replicas, 200 TPS sustained with sub-1s saga completion.

**Deliverables:** [`deployment-comparison-final.md`](deployment-comparison-final.md), K8s perf infrastructure, ADR 013, 5 deployment fixes

### Session 12: Documentation & Guide

Compiled all findings into adopter-facing documentation: production checklist, exercise summary, performance tuning guide, and blog post. (This session.)

**Deliverables:** [`production-checklist.md`](production-checklist.md), this summary, [`performance-tuning-guide.md`](../guides/performance-tuning-guide.md), blog post

---

## Key Results Summary

### Where Time Goes (Pipeline p50 = 8.7 ms)

```
┌──────────────────────────────────────────────────────────────┐
│                    Pipeline p50: 8,700 us                     │
├──────────┬───────────────────────────────────────┬───────────┤
│ Framework│     Hazelcast Cluster Ops             │  System   │
│  ~37 us  │       ~2,400 us                       │ ~6,263 us │
│  (0.4%)  │       (27.6%)                         │  (72.0%)  │
├──────────┼───────────────────────────────────────┼───────────┤
│ FlakeId  │ 3x IMap.set  = 936 us                │ Jet sched │
│ 15.6 us  │ 3x ITopic    = 930 us                │ Thread ctx│
│ Ser/Des  │ 2x IMap.get  = 536 us                │ HTTP/MVC  │
│ 1.4 us   │                                       │ Queue wait│
│ IMap ops │                                       │           │
│ 20.5 us  │                                       │           │
└──────────┴───────────────────────────────────────┴───────────┘
```

### Top Optimizations (by impact)

| Optimization | Impact | Session |
|-------------|--------|:-------:|
| Per-service embedded clustering (ADR 013) | Saga E2E: -95% (10s → 513ms) | 11 |
| Register SagaTimeoutAutoConfiguration | Saga completion: 79% → 100% | 1 |
| Outbox IMap indexes + PagingPredicate | CPU: -51%, allocations: -34% | 6 |
| Memory limit 512MiB → 1GiB | Eliminated GC thrashing at 12+ min | 8 |
| Completions map 50K safety net | Defense-in-depth for multi-hour runs | 8 |

### Cost-Performance Tiers

| Tier | Cost/hr | Max TPS | Saga p95 | Best For |
|------|---------|---------|----------|----------|
| Local (Docker Compose) | $0 | 50 | < 300ms | Development, demos |
| AWS Small (2x t3.xlarge) | ~$0.76 | 100 | < 650ms | Staging, light production |
| AWS Medium (3x c7i.2xlarge) | ~$1.18 | 200 | Timeout at 50+ | HTTP throughput (no saga SLA) |
| AWS Large + clustering (5x c7i.4xlarge) | ~$3.50 | 200 | < 1s | Production with saga SLA |

---

## Tools and Artifacts

| Tool | Location | Purpose |
|------|----------|---------|
| k6 load test scripts | `scripts/perf/k6-*.js` | HTTP load generation with `constant-arrival-rate` |
| Run wrapper | `scripts/perf/run-perf-test.sh` | Scenario selection, ID loading, results directory |
| A-B test harness | `scripts/perf/ab-test.sh`, `ab-compare.sh` | Automated config comparison with markdown reports |
| Profiling orchestrator | `scripts/perf/profile-service.sh` | async-profiler capture with warmup and k6 background load |
| Dashboard capture | `scripts/perf/capture-dashboards.sh` | Automated Grafana PNG screenshots |
| Sustained test runner | `scripts/perf/run-sustained-test.sh` | 30-minute test with periodic memory/dashboard capture |
| JMH benchmarks | `framework-core/src/jmh/` | Per-operation micro-benchmarks (embedded + cluster) |
| K8s perf sweep | `scripts/perf/k8s-perf-test.sh` | Multi-TPS sweep with per-level metrics capture |
| Sample data generator | `scripts/perf/generate-sample-data.sh` | 100 customers + 100 products via REST API |
| Profiling Dockerfile | `docker/profiling/Dockerfile.profiled` | async-profiler v4.3 baked into service image |
| Grafana dashboards | `docker/grafana/dashboards/` | 6 dashboards (including Performance Testing) |

---

*Generated from the [Performance Exercise Plan](../plans/performance-exercise-plan.md). For the full tuning reference, see the [Performance Tuning Guide](../guides/performance-tuning-guide.md).*
