package com.theyawns.framework.bench;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.framework.view.UpdateViewEntryProcessor;
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

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JMH benchmarks for ViewStore update operations.
 * Compares EntryProcessor-based atomic updates vs direct IMap.set().
 *
 * <p>Three methods:
 * <ul>
 *   <li>{@code executeOnKeyUpdate}: EntryProcessor on an existing entry (steady-state path)</li>
 *   <li>{@code executeOnKeyCreation}: EntryProcessor on a missing key (creation path)</li>
 *   <li>{@code directPut}: Baseline IMap.set without EntryProcessor overhead</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 2, jvmArgs = {"-Xms1g", "-Xmx1g",
        "-Dhazelcast.logging.type=slf4j"})
public class ViewStoreUpdateBenchmark {

    @State(Scope.Thread)
    public static class ThreadState {
        /** Key that always has an existing entry in the view. */
        String existingKey;
        /** Counter for generating unique missing keys. */
        AtomicLong missingKeyCounter;
        GenericRecord updateRecord;

        @Setup(Level.Trial)
        public void setUp() {
            existingKey = "view-existing";
            missingKeyCounter = new AtomicLong(0);
            updateRecord = GenericRecordBuilder.compact(BenchmarkDomainObj.SCHEMA_NAME)
                    .setString("id", existingKey)
                    .setString("name", "Updated Widget")
                    .setString("category", "ELECTRONICS")
                    .setString("description", "An updated benchmark domain object")
                    .setString("status", "ACTIVE")
                    .setInt64("updatedAt", Instant.now().toEpochMilli())
                    .build();
        }

        public String nextMissingKey() {
            return "view-missing-" + missingKeyCounter.incrementAndGet();
        }
    }

    /**
     * Pre-populate the view with an entry for the existing key before each iteration.
     * Using Level.Iteration ensures the entry exists at the start of each measurement batch.
     */
    @State(Scope.Benchmark)
    public static class ViewSetupState {
        @Setup(Level.Iteration)
        public void seedEntry(HazelcastBenchmarkState hzState) {
            GenericRecord seed = GenericRecordBuilder.compact(BenchmarkDomainObj.SCHEMA_NAME)
                    .setString("id", "view-existing")
                    .setString("name", "Original Widget")
                    .setString("category", "ELECTRONICS")
                    .setString("description", "The original benchmark domain object")
                    .setString("status", "ACTIVE")
                    .setInt64("updatedAt", Instant.now().toEpochMilli())
                    .build();
            hzState.viewStore.put("view-existing", seed);
        }
    }

    @Benchmark
    public GenericRecord executeOnKeyUpdate(HazelcastBenchmarkState hzState,
                                            ThreadState threadState,
                                            ViewSetupState setupState) {
        UpdateViewEntryProcessor<String> processor =
                new UpdateViewEntryProcessor<>(current -> threadState.updateRecord);
        return hzState.viewStore.executeOnKey(threadState.existingKey, processor);
    }

    @Benchmark
    public GenericRecord executeOnKeyCreation(HazelcastBenchmarkState hzState,
                                              ThreadState threadState,
                                              ViewSetupState setupState) {
        String missingKey = threadState.nextMissingKey();
        GenericRecord newRecord = GenericRecordBuilder.compact(BenchmarkDomainObj.SCHEMA_NAME)
                .setString("id", missingKey)
                .setString("name", "New Widget")
                .setString("category", "ELECTRONICS")
                .setString("description", "A newly created benchmark domain object")
                .setString("status", "ACTIVE")
                .setInt64("updatedAt", Instant.now().toEpochMilli())
                .build();
        UpdateViewEntryProcessor<String> processor =
                new UpdateViewEntryProcessor<>(current -> newRecord);
        return hzState.viewStore.executeOnKey(missingKey, processor);
    }

    @Benchmark
    public void directPut(HazelcastBenchmarkState hzState, ThreadState threadState) {
        hzState.viewStore.put(threadState.existingKey, threadState.updateRecord);
    }
}
