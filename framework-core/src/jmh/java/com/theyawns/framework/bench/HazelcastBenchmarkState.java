package com.theyawns.framework.bench;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.flakeidgen.FlakeIdGenerator;
import com.hazelcast.map.IMap;
import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.theyawns.framework.store.HazelcastEventStore;
import com.theyawns.framework.store.PartitionedSequenceKey;
import com.theyawns.framework.view.HazelcastViewStore;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared benchmark state that manages a real embedded Hazelcast instance.
 * Scope.Benchmark means one instance shared across all threads in a benchmark run.
 *
 * <p>Provides pre-configured:
 * <ul>
 *   <li>Standalone HazelcastInstance (no cluster join)</li>
 *   <li>HazelcastEventStore for append benchmarks</li>
 *   <li>HazelcastViewStore for update benchmarks</li>
 *   <li>FlakeIdGenerator for ID generation benchmarks</li>
 *   <li>AtomicLong counter for unique keys across threads</li>
 * </ul>
 */
@State(Scope.Benchmark)
public class HazelcastBenchmarkState {

    public HazelcastInstance hazelcast;
    public HazelcastEventStore<BenchmarkDomainObj, String, BenchmarkEvent> eventStore;
    public HazelcastViewStore<String> viewStore;
    public FlakeIdGenerator flakeIdGenerator;
    public AtomicLong keyCounter;

    @Setup(Level.Trial)
    public void setUp() {
        Config config = new Config();
        config.setInstanceName("jmh-benchmark-" + System.nanoTime());
        config.setClusterName("jmh-bench");

        // Standalone — no cluster join
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);

        // Enable Jet for completeness (some pipelines may reference it)
        config.getJetConfig().setEnabled(true);

        // Suppress noisy logging
        config.setProperty("hazelcast.logging.type", "slf4j");
        config.setProperty("hazelcast.phone.home.enabled", "false");

        hazelcast = Hazelcast.newHazelcastInstance(config);
        eventStore = new HazelcastEventStore<>(hazelcast, "Benchmark");
        viewStore = new HazelcastViewStore<>(hazelcast, "Benchmark");
        flakeIdGenerator = hazelcast.getFlakeIdGenerator("benchmark-seq");
        keyCounter = new AtomicLong(0);
    }

    /**
     * Clears IMap data between measurement iterations to prevent OOM.
     * Each iteration gets a fresh empty map while reusing the HZ instance.
     */
    @Setup(Level.Iteration)
    public void clearMaps() {
        if (eventStore != null) {
            eventStore.getUnderlyingMap().clear();
        }
        if (viewStore != null) {
            viewStore.getUnderlyingMap().clear();
        }
        keyCounter.set(0);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (hazelcast != null) {
            hazelcast.shutdown();
        }
    }

    /**
     * Returns a unique key string for this benchmark iteration.
     */
    public String nextKey() {
        return "bench-" + keyCounter.incrementAndGet();
    }

    /**
     * Returns the view IMap for direct operations.
     */
    public IMap<String, GenericRecord> getViewMap() {
        return viewStore.getUnderlyingMap();
    }

    /**
     * Returns the event store IMap for direct operations.
     */
    public IMap<PartitionedSequenceKey<String>, GenericRecord> getEventMap() {
        return eventStore.getUnderlyingMap();
    }
}
