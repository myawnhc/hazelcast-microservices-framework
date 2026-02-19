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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for ITopic pub/sub operations over the network to the shared cluster.
 *
 * <p>Saga orchestration relies heavily on ITopic for cross-service event publishing.
 * These benchmarks measure:
 * <ul>
 *   <li>Fire-and-forget publish cost (one-way)</li>
 *   <li>Full round-trip latency (publish + listener callback)</li>
 * </ul>
 *
 * <p>The {@code publishAndReceive} benchmark uses {@code @Fork(1)} because the message
 * listener is registered in {@link ClusterBenchmarkState}'s {@code @Setup(Level.Trial)}.
 * Network variance dominates JVM warmup variance, so a single fork is sufficient.
 *
 * <p>Requires the Docker Compose cluster to be running (3 Hazelcast nodes on ports 5701-5703).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
public class ClusterITopicBenchmark {

    /**
     * Per-thread state holding pre-built GenericRecords.
     */
    @State(Scope.Thread)
    public static class ThreadState {
        GenericRecord smallRecord;
        GenericRecord largeRecord;

        @Setup(Level.Trial)
        public void setUp() {
            smallRecord = ClusterBenchmarkState.buildSmallRecord("topic-small");
            largeRecord = ClusterBenchmarkState.buildLargeRecord("topic-large");
        }
    }

    /**
     * Fire-and-forget ITopic.publish with a small (~200B) GenericRecord.
     * Measures the cost of submitting a message to the cluster (does not wait for delivery).
     */
    @Benchmark
    @Fork(value = 2, jvmArgs = {"-Xms1g", "-Xmx1g",
            "-Dhazelcast.logging.type=slf4j"})
    public void publishSmallRecord(ClusterBenchmarkState state, ThreadState threadState) {
        state.benchTopic.publish(threadState.smallRecord);
    }

    /**
     * Fire-and-forget ITopic.publish with a large (~1KB) GenericRecord.
     * Measures the additional cost of transmitting larger payloads.
     */
    @Benchmark
    @Fork(value = 2, jvmArgs = {"-Xms1g", "-Xmx1g",
            "-Dhazelcast.logging.type=slf4j"})
    public void publishLargeRecord(ClusterBenchmarkState state, ThreadState threadState) {
        state.benchTopic.publish(threadState.largeRecord);
    }

    /**
     * Publish + await MessageListener callback — full round-trip ITopic latency.
     *
     * <p>Uses a {@link CountDownLatch} that the listener (registered in
     * {@link ClusterBenchmarkState}) counts down upon message receipt.
     * 5-second timeout prevents benchmark hangs if messages are lost.
     *
     * <p>Single fork: the listener registration in @Setup(Level.Trial) must persist.
     */
    @Benchmark
    @Fork(value = 1, jvmArgs = {"-Xms1g", "-Xmx1g",
            "-Dhazelcast.logging.type=slf4j"})
    public void publishAndReceive(ClusterBenchmarkState state, ThreadState threadState)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        state.topicLatch.set(latch);

        state.benchTopic.publish(threadState.smallRecord);

        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new RuntimeException("ITopic round-trip timed out after 5 seconds");
        }
    }
}
