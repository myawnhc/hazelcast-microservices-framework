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

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks comparing IMap.set() cost with and without event journal enabled.
 *
 * <p>The Docker Compose cluster config ({@code hazelcast-docker.yaml}) enables event journal
 * on all maps matching {@code *_PENDING}. This benchmark measures the overhead delta of
 * event journal bookkeeping by comparing:
 * <ul>
 *   <li>{@code writeToJournaledMap}: Write to {@code Bench_PENDING} (event journal enabled)</li>
 *   <li>{@code writeToPlainMap}: Write to {@code Bench_PLAIN} (no event journal)</li>
 * </ul>
 *
 * <p>The difference reveals how much event journal overhead contributes to pipeline
 * event submission cost in production.
 *
 * <p>Requires the Docker Compose cluster to be running (3 Hazelcast nodes on ports 5701-5703).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 2, jvmArgs = {"-Xms1g", "-Xmx1g",
        "-Dhazelcast.logging.type=slf4j"})
public class ClusterEventJournalBenchmark {

    /**
     * Per-thread state holding a pre-built GenericRecord.
     */
    @State(Scope.Thread)
    public static class ThreadState {
        GenericRecord record;

        @Setup(Level.Trial)
        public void setUp() {
            record = ClusterBenchmarkState.buildSmallRecord("journal-bench");
        }
    }

    /**
     * IMap.set to a map matching *_PENDING (event journal enabled).
     * Measures the cost of write + event journal bookkeeping.
     */
    @Benchmark
    public void writeToJournaledMap(ClusterBenchmarkState state, ThreadState threadState) {
        String key = state.nextKey();
        state.journaledMap.set(key, threadState.record);
    }

    /**
     * IMap.set to a plain map (no event journal).
     * Baseline cost for comparison against journaled map.
     */
    @Benchmark
    public void writeToPlainMap(ClusterBenchmarkState state, ThreadState threadState) {
        String key = state.nextKey();
        state.plainMap.set(key, threadState.record);
    }
}
