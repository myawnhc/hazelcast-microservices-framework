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
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for PartitionedSequenceKey operations.
 * This key is on every IMap operation's hot path — creation, serialization,
 * hashCode(), and equals() are called for every event store access.
 *
 * <p>Six methods: creation, toGenericRecord, fromGenericRecord, round-trip,
 * hashCode, equals. All pure compute — no Hazelcast instance needed.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 2, jvmArgs = {"-Xms512m", "-Xmx512m",
        "-Dhazelcast.logging.type=slf4j"})
public class PartitionedSequenceKeyBenchmark {

    @State(Scope.Thread)
    public static class KeyState {
        PartitionedSequenceKey<String> key;
        PartitionedSequenceKey<String> equalKey;
        PartitionedSequenceKey<String> differentKey;
        GenericRecord keyRecord;

        @Setup(Level.Trial)
        public void setUp() {
            key = new PartitionedSequenceKey<>(100_000L, "customer-12345");
            equalKey = new PartitionedSequenceKey<>(100_000L, "customer-12345");
            differentKey = new PartitionedSequenceKey<>(200_000L, "customer-67890");
            keyRecord = key.toGenericRecord();
        }
    }

    @Benchmark
    public PartitionedSequenceKey<String> creation() {
        return new PartitionedSequenceKey<>(42L, "customer-99999");
    }

    @Benchmark
    public GenericRecord toGenericRecord(KeyState state) {
        return state.key.toGenericRecord();
    }

    @Benchmark
    public PartitionedSequenceKey<String> fromGenericRecord(KeyState state) {
        return PartitionedSequenceKey.fromGenericRecord(state.keyRecord);
    }

    @Benchmark
    public PartitionedSequenceKey<String> roundTrip(KeyState state) {
        GenericRecord record = state.key.toGenericRecord();
        return PartitionedSequenceKey.fromGenericRecord(record);
    }

    @Benchmark
    public void hashCode(KeyState state, Blackhole bh) {
        bh.consume(state.key.hashCode());
    }

    @Benchmark
    public void equals(KeyState state, Blackhole bh) {
        bh.consume(state.key.equals(state.equalKey));
        bh.consume(state.key.equals(state.differentKey));
    }
}
