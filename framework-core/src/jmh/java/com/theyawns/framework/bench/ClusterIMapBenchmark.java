package com.theyawns.framework.bench;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for IMap operations over the network to the shared 3-node cluster.
 *
 * <p>Measures the real cost of IMap.set() and IMap.get() when crossing the network boundary,
 * compared to Session 9's embedded-only benchmarks. This reveals the network overhead factor
 * that contributes to saga latency.
 *
 * <p>5 benchmark methods:
 * <ul>
 *   <li>{@code putSmallValue}: IMap.set with ~200B GenericRecord (6-field BenchmarkDomainObj)</li>
 *   <li>{@code putLargeValue}: IMap.set with ~1KB GenericRecord (14-field saga-state-like record)</li>
 *   <li>{@code getExistingKey}: IMap.get of pre-seeded entry</li>
 *   <li>{@code getMissingKey}: IMap.get of nonexistent key (cache miss baseline)</li>
 *   <li>{@code putThenGet}: set + get round-trip (saga write-read pattern)</li>
 * </ul>
 *
 * <p>Requires the Docker Compose cluster to be running (3 Hazelcast nodes on ports 5701-5703).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 2, jvmArgs = {"-Xms1g", "-Xmx1g",
        "-Dhazelcast.logging.type=slf4j"})
public class ClusterIMapBenchmark {

    /**
     * Per-thread state holding pre-built GenericRecords to isolate serialization from IMap cost.
     */
    @State(Scope.Thread)
    public static class ThreadState {
        GenericRecord smallRecord;
        GenericRecord largeRecord;
        String seededKey;

        @Setup(Level.Trial)
        public void setUp(ClusterBenchmarkState clusterState) {
            smallRecord = ClusterBenchmarkState.buildSmallRecord("thread-seed");
            largeRecord = ClusterBenchmarkState.buildLargeRecord("thread-seed");

            // Pre-seed one entry for getExistingKey
            seededKey = "seeded-" + Thread.currentThread().getId();
            clusterState.benchMap.set(seededKey, smallRecord);
        }
    }

    /**
     * IMap.set with a small (~200B) GenericRecord.
     * Measures network round-trip + server-side deserialization + storage.
     */
    @Benchmark
    public void putSmallValue(ClusterBenchmarkState state, ThreadState threadState) {
        String key = state.nextKey();
        state.benchMap.set(key, threadState.smallRecord);
    }

    /**
     * IMap.set with a large (~1KB) GenericRecord matching saga-state complexity.
     * Measures the additional cost of serializing/transmitting larger payloads.
     */
    @Benchmark
    public void putLargeValue(ClusterBenchmarkState state, ThreadState threadState) {
        String key = state.nextKey();
        state.benchMap.set(key, threadState.largeRecord);
    }

    /**
     * IMap.get of a pre-seeded key.
     * Measures read latency including server-side lookup + network response + deserialization.
     */
    @Benchmark
    public void getExistingKey(ClusterBenchmarkState state, ThreadState threadState, Blackhole bh) {
        GenericRecord result = state.benchMap.get(threadState.seededKey);
        bh.consume(result);
    }

    /**
     * IMap.get of a nonexistent key (cache miss).
     * Establishes the baseline cost of a miss — no deserialization, just network + lookup.
     */
    @Benchmark
    public void getMissingKey(ClusterBenchmarkState state, Blackhole bh) {
        GenericRecord result = state.benchMap.get("nonexistent-key-12345");
        bh.consume(result);
    }

    /**
     * IMap.set followed by IMap.get — simulates the saga write-then-read pattern.
     * Combined cost represents a single saga state update cycle.
     */
    @Benchmark
    public void putThenGet(ClusterBenchmarkState state, ThreadState threadState, Blackhole bh) {
        String key = state.nextKey();
        state.benchMap.set(key, threadState.smallRecord);
        GenericRecord result = state.benchMap.get(key);
        bh.consume(result);
    }
}
