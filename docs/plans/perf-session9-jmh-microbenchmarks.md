# Session 9: JMH Micro-Benchmarks

**Date:** 2026-02-18
**Status:** COMPLETED

## Context

Sessions 1-8 measured system-level performance (HTTP throughput, pipeline latency, saga completion). Session 5's flame graphs identified serialization and IMap operations as significant CPU/allocation consumers, but we didn't have isolated per-operation costs. JMH benchmarks measure the framework's internal hot-path operations in isolation, answering: "What is the per-event cost of serialization, storage, and view updates?"

## Implementation

### Maven Setup

Added `benchmark` profile to `framework-core/pom.xml`:
- `build-helper-maven-plugin` adds `src/jmh/java` as source directory
- `maven-compiler-plugin` with `annotationProcessorPaths` for JMH annotation processor (required on Java 23+)
- `maven-shade-plugin` produces `framework-core/target/benchmarks.jar`
- JMH 1.37 dependencies (`jmh-core`, `jmh-generator-annprocess`)
- Spring Boot parent's shade execution disabled via `<phase>none</phase>` to avoid config merge conflicts

### Files Created

**Fixtures (3 files):**
- `BenchmarkEvent.java` — 14-field event extending `DomainEvent<BenchmarkDomainObj, String>`
- `BenchmarkDomainObj.java` — 6-field domain object implementing `DomainObject<String>`
- `HazelcastBenchmarkState.java` — `@State(Scope.Benchmark)` with embedded HZ lifecycle and iteration-level map clearing

**Benchmarks (5 files, 20 methods):**
- `SerializationBenchmark.java` — 6 methods: event/domain toGenericRecord, fromGenericRecord, roundTrip
- `EventStoreAppendBenchmark.java` — 2 methods: preBuilt record vs with serialization
- `ViewStoreUpdateBenchmark.java` — 3 methods: executeOnKey update/creation, directPut baseline
- `FlakeIdGeneratorBenchmark.java` — 3 methods: 1/4/8 thread scaling
- `PartitionedSequenceKeyBenchmark.java` — 6 methods: creation, serialization, hashCode, equals

### Issues Encountered & Resolved

1. **Spring Boot parent shade plugin merge:** Spring Boot 3.2's parent POM defines a shade plugin execution with `AppendingTransformer` elements containing `<resource>` tags. Maven 3.9 merged these into our `ManifestResourceTransformer` config, causing `Cannot find 'resource'` errors. Fix: use matching version (3.5.1), disable inherited execution via `<phase>none</phase>`, define fresh `jmh-shade` execution.

2. **JMH annotation processor not running on Java 25:** Java 23+ disables annotation processing by default. The `BenchmarkList` resource wasn't generated, causing `Unable to find /META-INF/BenchmarkList` at runtime. Fix: explicit `annotationProcessorPaths` in `maven-compiler-plugin`.

3. **OOM on IMap benchmarks at 512MB:** EventStoreAppendBenchmark writes millions of entries across measurement iterations. Fix: increased heap to 1GB for IMap-using benchmarks, added `@Setup(Level.Iteration)` map clearing in `HazelcastBenchmarkState`.

## Results

See `docs/perf/microbenchmark-results.md` for full results table and analysis.

**Key finding:** Per-event framework internal cost is ~37 us total. FlakeIdGenerator.newId() is the most expensive single operation at 15.6 us (42% of total), with severe contention scaling at multi-threaded use (9.7x at 8 threads). Total framework cost represents < 0.5% of pipeline p50, confirming system-level overhead dominates.

## Verification

1. `mvn clean test` — all 10 modules pass (benchmark profile not active, src/jmh/java ignored)
2. `mvn clean package -pl framework-core -Pbenchmark -DskipTests` — produces `benchmarks.jar`
3. Smoke test: `java -jar framework-core/target/benchmarks.jar SerializationBenchmark -f 1 -wi 1 -i 2` — completes with results
4. Full run: 20 benchmarks completed in 23 minutes, JSON results saved
5. Results documented in `docs/perf/microbenchmark-results.md`
6. `docs/plans/performance-exercise-plan.md` updated — Session 9 marked COMPLETED
