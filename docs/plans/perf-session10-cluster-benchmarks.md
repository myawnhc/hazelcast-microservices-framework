# Session 10: Hazelcast Cluster Benchmarks & Simulator Evaluation

**Date:** 2026-02-18
**Type:** Performance Exercise — Session 10

---

## Objective

Benchmark the shared 3-node Hazelcast cluster (ITopic pub/sub and saga state IMap) in isolation to answer: **"Is the shared Hazelcast cluster a significant contributor to saga latency, or does system-level overhead (Jet scheduling, thread coordination, HTTP) dominate?"**

Secondary goal: evaluate Hazelcast Simulator as a tool for this project.

---

## Context

Session 9's JMH micro-benchmarks showed per-event framework cost is ~37 us (< 0.5% of pipeline p50 8.7ms), proving that embedded Hazelcast operations are not the bottleneck. However, the shared 3-node cluster (used for ITopic pub/sub and saga state IMap) was never benchmarked in isolation.

---

## Approach

Extend the existing JMH infrastructure with cluster-aware benchmarks (HazelcastClient connecting to Docker Compose cluster). Try Hazelcast Simulator's Docker image for evaluation only.

---

## Deliverables

### New Files
1. `ClusterBenchmarkState.java` — JMH state managing HazelcastClient lifecycle
2. `ClusterIMapBenchmark.java` — 5 IMap benchmarks over the network
3. `ClusterITopicBenchmark.java` — 3 ITopic benchmarks (fire-and-forget + round-trip)
4. `ClusterEventJournalBenchmark.java` — 2 benchmarks comparing journaled vs plain map writes
5. `scripts/perf/run-cluster-benchmarks.sh` — Orchestration script
6. `scripts/perf/simulator/try-simulator.sh` — Simulator evaluation script

### Results
7. `docs/perf/cluster-benchmark-results.md` — Full results with analysis

### Updated
8. `docs/plans/performance-exercise-plan.md` — Session 10 marked COMPLETED

---

## Verification

1. `mvn clean test` passes (benchmark sources ignored without `-Pbenchmark`)
2. `mvn clean package -pl framework-core -Pbenchmark -DskipTests` builds `benchmarks.jar`
3. With Docker cluster running: `java -jar framework-core/target/benchmarks.jar "Cluster.*" -f 1 -wi 1 -i 1` completes
4. `./scripts/perf/run-cluster-benchmarks.sh --quick` runs end-to-end
5. Results document has all benchmark numbers filled in
