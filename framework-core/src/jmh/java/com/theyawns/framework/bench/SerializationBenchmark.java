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
 * JMH benchmarks for GenericRecord serialization round-trips.
 * Measures the per-event cost of toGenericRecord() and fromGenericRecord()
 * for both events (14 fields) and domain objects (6 fields).
 *
 * <p>Pure compute — no Hazelcast instance needed. Uses @State(Scope.Thread)
 * to avoid contention between benchmark threads.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 2, jvmArgs = {"-Xms512m", "-Xmx512m",
        "-Dhazelcast.logging.type=slf4j"})
public class SerializationBenchmark {

    @State(Scope.Thread)
    public static class EventState {
        BenchmarkEvent event;
        GenericRecord eventRecord;
        BenchmarkDomainObj domainObj;
        GenericRecord domainRecord;

        @Setup(Level.Trial)
        public void setUp() {
            event = new BenchmarkEvent("bench-key-1", "Benchmark Widget",
                    "ELECTRONICS", "A high-performance widget for benchmarking serialization throughput");
            event.setSource("benchmark-service");
            event.setCorrelationId("corr-bench-001");
            event.setSagaId("saga-bench-001");
            event.setSagaType("BenchmarkSaga");
            event.setStepNumber(1);
            event.setIsCompensating(false);

            eventRecord = event.toGenericRecord();

            domainObj = new BenchmarkDomainObj("obj-1", "Benchmark Widget",
                    "ELECTRONICS", "A benchmark domain object for testing", "ACTIVE");
            domainRecord = domainObj.toGenericRecord();
        }
    }

    // --- Event serialization benchmarks ---

    @Benchmark
    public GenericRecord eventToGenericRecord(EventState state) {
        return state.event.toGenericRecord();
    }

    @Benchmark
    public BenchmarkEvent eventFromGenericRecord(EventState state) {
        return BenchmarkEvent.fromGenericRecord(state.eventRecord);
    }

    @Benchmark
    public BenchmarkEvent eventRoundTrip(EventState state) {
        GenericRecord record = state.event.toGenericRecord();
        return BenchmarkEvent.fromGenericRecord(record);
    }

    // --- Domain object serialization benchmarks ---

    @Benchmark
    public GenericRecord domainObjToGenericRecord(EventState state) {
        return state.domainObj.toGenericRecord();
    }

    @Benchmark
    public BenchmarkDomainObj domainObjFromGenericRecord(EventState state) {
        return BenchmarkDomainObj.fromGenericRecord(state.domainRecord);
    }

    @Benchmark
    public BenchmarkDomainObj domainObjRoundTrip(EventState state) {
        GenericRecord record = state.domainObj.toGenericRecord();
        return BenchmarkDomainObj.fromGenericRecord(record);
    }
}
