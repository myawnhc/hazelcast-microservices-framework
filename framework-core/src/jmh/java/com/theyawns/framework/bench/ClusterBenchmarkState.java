package com.theyawns.framework.bench;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.hazelcast.topic.ITopic;
import com.hazelcast.topic.MessageListener;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared benchmark state that manages a HazelcastClient connected to the Docker Compose cluster.
 * Scope.Benchmark means one client shared across all threads in a benchmark run.
 *
 * <p>Connects to the 3-node cluster at localhost:5701,5702,5703 (Docker port mappings).
 * Provides IMap and ITopic for cluster-level benchmarks.
 *
 * <p>Key differences from {@link HazelcastBenchmarkState}:
 * <ul>
 *   <li>Client mode (not embedded) — all operations cross the network</li>
 *   <li>Connects to real 3-node cluster (must be running via Docker Compose)</li>
 *   <li>MessageListener registered for ITopic round-trip benchmarks</li>
 * </ul>
 */
@State(Scope.Benchmark)
public class ClusterBenchmarkState {

    /** IMap for saga-state-style benchmarks. */
    public IMap<String, GenericRecord> benchMap;

    /** IMap matching *_PENDING wildcard (event journal enabled on cluster). */
    public IMap<String, GenericRecord> journaledMap;

    /** IMap without event journal (plain map for comparison). */
    public IMap<String, GenericRecord> plainMap;

    /** ITopic for pub/sub benchmarks. */
    public ITopic<GenericRecord> benchTopic;

    /** Latch for round-trip ITopic benchmarks — reset per invocation by ThreadState. */
    public final AtomicReference<CountDownLatch> topicLatch = new AtomicReference<>(new CountDownLatch(1));

    /** Unique key counter across threads. */
    public final AtomicLong keyCounter = new AtomicLong(0);

    private HazelcastInstance client;
    private UUID listenerRegistration;

    @Setup(Level.Trial)
    public void setUp() {
        ClientConfig config = new ClientConfig();
        config.setClusterName("ecommerce-cluster");

        // When running inside Docker (--docker flag), use internal hostnames with smart
        // routing enabled. Smart routing opens direct connections to all cluster members,
        // spreading load and reducing hops — this is the production-accurate path.
        //
        // When running on the host, smart routing is disabled because cluster members
        // advertise Docker-internal hostnames (hazelcast-1:5701 etc.) that aren't
        // resolvable from the host. All ops route through a single gateway member.
        // Note: Hazelcast's public-address (HZ_NETWORK_PUBLICADDRESS) cannot be used
        // here because it affects member-to-member communication too, causing
        // "Connecting to self!" errors inside Docker.
        boolean dockerMode = Boolean.getBoolean("benchmark.docker");
        if (dockerMode) {
            config.getNetworkConfig().addAddress(
                    "hazelcast-1:5701", "hazelcast-2:5701", "hazelcast-3:5701");
            // Smart routing enabled (default) — direct connections to all members
        } else {
            config.getNetworkConfig().addAddress(
                    "localhost:5701", "localhost:5702", "localhost:5703");
            config.getNetworkConfig().setSmartRouting(false);
        }

        // Fast fail if cluster is down
        config.getConnectionStrategyConfig().getConnectionRetryConfig()
                .setClusterConnectTimeoutMillis(10_000);

        // Suppress noisy logging
        config.setProperty("hazelcast.logging.type", "slf4j");

        client = HazelcastClient.newHazelcastClient(config);

        benchMap = client.getMap("Bench_SAGA_STATE");
        journaledMap = client.getMap("Bench_PENDING");  // matches *_PENDING wildcard → journal enabled
        plainMap = client.getMap("Bench_PLAIN");

        benchTopic = client.getTopic("Bench_TOPIC");

        // Register message listener for round-trip benchmarks
        MessageListener<GenericRecord> listener = message -> {
            CountDownLatch latch = topicLatch.get();
            if (latch != null) {
                latch.countDown();
            }
        };
        listenerRegistration = benchTopic.addMessageListener(listener);
    }

    /**
     * Clears benchmark maps between iterations to prevent unbounded growth.
     */
    @Setup(Level.Iteration)
    public void clearMaps() {
        if (benchMap != null) {
            benchMap.clear();
        }
        if (journaledMap != null) {
            journaledMap.clear();
        }
        if (plainMap != null) {
            plainMap.clear();
        }
        keyCounter.set(0);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (benchTopic != null && listenerRegistration != null) {
            benchTopic.removeMessageListener(listenerRegistration);
        }
        if (client != null) {
            client.shutdown();
        }
    }

    /**
     * Returns a unique key string for this benchmark iteration.
     */
    public String nextKey() {
        return "bench-" + keyCounter.incrementAndGet();
    }

    /**
     * Builds a small (~200B) GenericRecord with 6 fields, matching BenchmarkDomainObj.
     */
    public static GenericRecord buildSmallRecord(String key) {
        return GenericRecordBuilder.compact(BenchmarkDomainObj.SCHEMA_NAME)
                .setString("id", key)
                .setString("name", "Benchmark Widget")
                .setString("category", "ELECTRONICS")
                .setString("description", "A widget for cluster benchmarking")
                .setString("status", "ACTIVE")
                .setInt64("updatedAt", Instant.now().toEpochMilli())
                .build();
    }

    /**
     * Builds a large (~1KB) GenericRecord with 14 fields, matching saga-state complexity.
     */
    public static GenericRecord buildLargeRecord(String key) {
        return GenericRecordBuilder.compact("SagaStateRecord")
                .setString("sagaId", key)
                .setString("sagaType", "OrderPlacementSaga")
                .setString("orderId", "ORD-" + key)
                .setString("customerId", "CUST-" + key)
                .setString("status", "IN_PROGRESS")
                .setInt32("currentStep", 2)
                .setInt32("totalSteps", 5)
                .setInt64("createdAt", Instant.now().toEpochMilli())
                .setInt64("updatedAt", Instant.now().toEpochMilli())
                .setString("inventoryReservationId", "INV-RES-" + key)
                .setString("paymentTransactionId", "PAY-TXN-" + key)
                .setString("correlationId", "CORR-" + key)
                .setBoolean("compensating", false)
                .setString("errorMessage", "")
                .build();
    }
}
