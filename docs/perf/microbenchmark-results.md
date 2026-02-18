# JMH Micro-Benchmark Results

**Date:** 2026-02-18
**Session:** Performance Exercise Session 9
**JMH Version:** 1.37
**JVM:** OpenJDK 24.0.2, 64-Bit Server VM
**Hardware:** Apple Silicon (macOS), 10 cooperative threads
**Heap:** 512MB (pure compute), 1GB (IMap benchmarks)
**Configuration:** 3 warmup iterations x 3s, 5 measurement iterations x 5s, 2 forks

---

## Results Summary

| Benchmark | Score (us/op) | Error (99.9% CI) | Category |
|-----------|:------------:|:-----------------:|----------|
| **PartitionedSequenceKey.equals** | **0.001** | +/- 0.001 | Key ops |
| PartitionedSequenceKey.creation | 0.004 | +/- 0.001 | Key ops |
| PartitionedSequenceKey.hashCode | 0.006 | +/- 0.001 | Key ops |
| PartitionedSequenceKey.fromGenericRecord | 0.009 | +/- 0.001 | Key serialization |
| DomainObj.fromGenericRecord | 0.038 | +/- 0.001 | Serialization |
| PartitionedSequenceKey.roundTrip | 0.231 | +/- 0.025 | Key serialization |
| PartitionedSequenceKey.toGenericRecord | 0.218 | +/- 0.010 | Key serialization |
| Event.fromGenericRecord (14 fields) | 0.289 | +/- 0.014 | Serialization |
| DomainObj.toGenericRecord (6 fields) | 0.447 | +/- 0.014 | Serialization |
| DomainObj.roundTrip | 0.488 | +/- 0.022 | Serialization |
| Event.toGenericRecord (14 fields) | 1.107 | +/- 0.028 | Serialization |
| Event.roundTrip | 1.413 | +/- 0.022 | Serialization |
| ViewStore.directPut | 7.424 | +/- 1.668 | IMap write |
| ViewStore.executeOnKeyUpdate | 9.259 | +/- 1.334 | IMap entry processor |
| ViewStore.executeOnKeyCreation | 10.149 | +/- 0.967 | IMap entry processor |
| EventStore.appendPreBuiltRecord | 11.237 | +/- 2.031 | IMap write |
| EventStore.appendWithSerialization | 11.512 | +/- 1.570 | IMap write + ser |
| **FlakeIdGenerator.singleThread** | **15.603** | +/- 0.014 | ID generation |
| FlakeIdGenerator.fourThreads | 69.216 | +/- 6.060 | ID generation |
| **FlakeIdGenerator.eightThreads** | **150.600** | +/- 22.250 | ID generation |

---

## Analysis

### Cost Breakdown Per Event

An event flowing through the framework pipeline touches these operations in sequence:

| Operation | Cost (us) | Cumulative |
|-----------|:---------:|:----------:|
| FlakeIdGenerator.newId() | ~15.6 | 15.6 |
| PartitionedSequenceKey creation | ~0.004 | 15.6 |
| Event.toGenericRecord() | ~1.1 | 16.7 |
| EventStore.append (IMap.set) | ~11.2 | 27.9 |
| ViewStore.executeOnKey | ~9.3 | 37.2 |
| **Total per-event internal cost** | | **~37 us** |

At 50 TPS, this is ~1.9 ms/s of CPU time spent on framework internals — well under 1% of a core, confirming that **application and I/O overhead dominate** at our current scale.

### Key Findings

#### 1. Most Expensive Operation: FlakeIdGenerator (15.6 us/call, 42% of total)

FlakeIdGenerator.newId() is the single most expensive per-event operation at 15.6 us — more than the IMap.set() it precedes. This is because FlakeIdGenerator maintains a batch-allocated ID pool with internal synchronization:
- **Single thread:** 15.6 us — baseline batch allocation cost
- **4 threads:** 69.2 us — 4.4x slowdown (lock contention on batch refill)
- **8 threads:** 150.6 us — 9.7x slowdown (severe contention)

**Implication:** At high concurrency, FlakeIdGenerator contention could become a bottleneck. The batch size (default 100) amortizes the cost, but contention grows super-linearly. This matches Session 5's flame graph finding where thread unparking was the #1 CPU hotspot by self-time.

#### 2. Serialization Is Cheap (< 1.5 us per event)

GenericRecordBuilder-based serialization is fast:
- **toGenericRecord** (14 fields): 1.1 us — dominated by builder allocation
- **fromGenericRecord** (14 fields): 0.3 us — field reads are fast
- **Round-trip**: 1.4 us total

The asymmetry (write 3.8x more expensive than read) is expected: `GenericRecordBuilder.compact()` allocates a new builder, schema, and backing arrays, while `getXxx()` reads from existing memory.

**Domain objects** (6 fields) are proportionally cheaper: 0.45 us to serialize, 0.04 us to deserialize.

#### 3. IMap Operations Are 7-11 us (network-like even in embedded mode)

Even with an embedded standalone Hazelcast instance (no network), IMap operations cost 7-11 us due to:
- Partition routing (hash → partition → thread dispatch)
- Serialization of the value to Hazelcast's internal storage format
- Entry processor overhead adds ~2 us over direct put (9.3 vs 7.4)

**EventStore append** (11.2 us for pre-built record) vs **ViewStore put** (7.4 us) — the difference is the `PartitionAware` key routing overhead in EventStore.

#### 4. PartitionedSequenceKey Is Negligible

Key creation, hashCode, and equals are all sub-nanosecond to single-digit nanoseconds — effectively free relative to the IMap operations they support.

#### 5. Entry Processor Overhead Is Modest

`executeOnKey` costs ~1.8 us more than `directPut` (9.3 vs 7.4 us = ~25% overhead). This is acceptable given the atomicity guarantee — entry processors eliminate race conditions in view updates that would otherwise require distributed locks.

Creation vs update paths are similar (10.1 vs 9.3 us), indicating the null-check in the entry processor adds minimal overhead.

### Comparison to System-Level Measurements

| Metric | JMH (this session) | System-level (Sessions 1,5) |
|--------|:-------------------:|:---------------------------:|
| Per-event framework cost | ~37 us | — |
| Pipeline end-to-end p50 | — | 8,700 us |
| Pipeline persist stage p50 | — | 1,900 us |
| HTTP request p95 | — | 22,500-30,000 us |

The ~37 us framework internal cost represents **< 0.5%** of the pipeline p50 (8.7 ms) and **< 0.2%** of HTTP request p95 (22-30 ms). This confirms that the dominant latency contributors are:
1. Jet pipeline scheduling and batching overhead
2. Thread context switching and queue wait
3. HTTP/Spring/Tomcat request processing
4. Cross-service saga coordination (ITopic publish/subscribe)

---

## Execution Details

```bash
# Build benchmark JAR
mvn clean package -pl framework-core -Pbenchmark -DskipTests

# Run all benchmarks (~23 min)
java -jar framework-core/target/benchmarks.jar \
    -rf json -rff framework-core/target/jmh-results.json

# Run single benchmark quickly (~2 min)
java -jar framework-core/target/benchmarks.jar \
    SerializationBenchmark -f 1 -wi 1 -i 2
```

**Benchmark JAR:** `framework-core/target/benchmarks.jar` (built only with `-Pbenchmark` profile)
**JSON results:** `framework-core/target/jmh-results.json`
**Total run time:** 23 minutes 9 seconds

---

## Recommendations

1. **FlakeIdGenerator tuning:** If scaling beyond ~50 TPS per service, increase the FlakeIdGenerator batch size via `FlakeIdGeneratorConfig.setPrefetchCount()` (default: 100). Larger batches reduce lock contention at the cost of ID gaps on service restart.

2. **Serialization is not a bottleneck:** The 10% CPU and 39% allocation flame graph contribution from Session 5 is driven by volume (thousands of events) not per-event cost. No optimization needed.

3. **Entry processor overhead is justified:** The ~25% overhead vs direct put is worth the atomicity guarantee for view updates.

4. **Focus optimization efforts on system-level:** Pipeline scheduling, Jet batching, and thread coordination are 100-200x more expensive than the framework's per-event internal operations.
