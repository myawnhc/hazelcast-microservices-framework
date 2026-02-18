package com.theyawns.framework.bench;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.theyawns.framework.store.PartitionedSequenceKey;
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
 * JMH benchmarks for EventStore append operations.
 * Measures the per-event cost of IMap.set() with and without serialization overhead.
 *
 * <p>Two methods:
 * <ul>
 *   <li>{@code appendPreBuiltRecord}: Pre-serialized GenericRecord → pure IMap.set cost</li>
 *   <li>{@code appendWithSerialization}: Event object → serialization + IMap.set combined cost</li>
 * </ul>
 *
 * <p>Uses real embedded Hazelcast via {@link HazelcastBenchmarkState}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 2, jvmArgs = {"-Xms1g", "-Xmx1g",
        "-Dhazelcast.logging.type=slf4j"})
public class EventStoreAppendBenchmark {

    @State(Scope.Thread)
    public static class ThreadState {
        BenchmarkEvent event;
        GenericRecord preBuiltRecord;

        @Setup(Level.Trial)
        public void setUp() {
            event = new BenchmarkEvent("bench-key", "Benchmark Widget",
                    "ELECTRONICS", "A widget for benchmarking event store append operations");
            event.setSource("benchmark-service");
            event.setCorrelationId("corr-bench-append");
            preBuiltRecord = event.toGenericRecord();
        }
    }

    @Benchmark
    public void appendPreBuiltRecord(HazelcastBenchmarkState hzState, ThreadState threadState) {
        String key = hzState.nextKey();
        long seq = hzState.flakeIdGenerator.newId();
        PartitionedSequenceKey<String> psk = new PartitionedSequenceKey<>(seq, key);
        hzState.eventStore.append(psk, threadState.preBuiltRecord);
    }

    @Benchmark
    public void appendWithSerialization(HazelcastBenchmarkState hzState, ThreadState threadState) {
        String key = hzState.nextKey();
        long seq = hzState.flakeIdGenerator.newId();
        PartitionedSequenceKey<String> psk = new PartitionedSequenceKey<>(seq, key);
        hzState.eventStore.append(psk, threadState.event);
    }
}
