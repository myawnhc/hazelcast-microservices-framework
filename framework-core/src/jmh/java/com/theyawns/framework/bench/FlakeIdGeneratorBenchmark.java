package com.theyawns.framework.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for Hazelcast FlakeIdGenerator.newId() throughput.
 * FlakeIdGenerator is called on every event append for sequence allocation.
 * Measures scaling behavior at 1, 4, and 8 threads.
 *
 * <p>FlakeIdGenerator pre-allocates batches of IDs locally, so per-call cost
 * is expected to be sub-microsecond with occasional batch refill spikes.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(value = 2, jvmArgs = {"-Xms1g", "-Xmx1g",
        "-Dhazelcast.logging.type=slf4j"})
public class FlakeIdGeneratorBenchmark {

    @Benchmark
    @Threads(1)
    public long singleThread(HazelcastBenchmarkState state) {
        return state.flakeIdGenerator.newId();
    }

    @Benchmark
    @Threads(4)
    public long fourThreads(HazelcastBenchmarkState state) {
        return state.flakeIdGenerator.newId();
    }

    @Benchmark
    @Threads(8)
    public long eightThreads(HazelcastBenchmarkState state) {
        return state.flakeIdGenerator.newId();
    }
}
