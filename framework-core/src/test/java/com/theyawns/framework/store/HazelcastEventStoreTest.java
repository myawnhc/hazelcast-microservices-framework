package com.theyawns.framework.store;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.framework.domain.DomainObject;
import com.theyawns.framework.event.DomainEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HazelcastEventStore - Event persistence using Hazelcast IMap")
class HazelcastEventStoreTest {

    private static HazelcastInstance hazelcast;
    private HazelcastEventStore<TestDomainObject, String, TestEvent> eventStore;

    @BeforeAll
    static void setUpClass() {
        Config config = new Config();
        config.setClusterName("test-cluster-" + System.currentTimeMillis());
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true).addMember("127.0.0.1");
        hazelcast = Hazelcast.newHazelcastInstance(config);
    }

    @AfterAll
    static void tearDownClass() {
        if (hazelcast != null) {
            hazelcast.shutdown();
        }
    }

    @BeforeEach
    void setUp() {
        eventStore = new HazelcastEventStore<>(hazelcast, "TestDomain");
    }

    @AfterEach
    void tearDown() {
        eventStore.getUnderlyingMap().clear();
    }

    @Test
    @DisplayName("should append and retrieve event by key")
    void shouldAppendAndRetrieveEventByKey() {
        // Arrange
        PartitionedSequenceKey<String> key = new PartitionedSequenceKey<>(1000L, "entity-1");
        GenericRecord eventRecord = createTestEventRecord("TestCreated", "entity-1");

        // Act
        eventStore.append(key, eventRecord);
        Optional<GenericRecord> retrieved = eventStore.get(key);

        // Assert
        assertTrue(retrieved.isPresent());
        assertEquals("TestCreated", retrieved.get().getString("eventType"));
        assertEquals("entity-1", retrieved.get().getString("key"));
    }

    @Test
    @DisplayName("should return empty when key not found")
    void shouldReturnEmptyWhenKeyNotFound() {
        // Arrange
        PartitionedSequenceKey<String> key = new PartitionedSequenceKey<>(9999L, "nonexistent");

        // Act
        Optional<GenericRecord> result = eventStore.get(key);

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should get events by domain object key ordered by sequence")
    void shouldGetEventsByDomainObjectKeyOrderedBySequence() {
        // Arrange
        String entityKey = "customer-123";
        eventStore.append(new PartitionedSequenceKey<>(300L, entityKey),
                createTestEventRecord("Event3", entityKey));
        eventStore.append(new PartitionedSequenceKey<>(100L, entityKey),
                createTestEventRecord("Event1", entityKey));
        eventStore.append(new PartitionedSequenceKey<>(200L, entityKey),
                createTestEventRecord("Event2", entityKey));

        // Act
        List<GenericRecord> events = eventStore.getEventsByKey(entityKey);

        // Assert
        assertEquals(3, events.size());
        assertEquals("Event1", events.get(0).getString("eventType"));
        assertEquals("Event2", events.get(1).getString("eventType"));
        assertEquals("Event3", events.get(2).getString("eventType"));
    }

    @Test
    @DisplayName("should get events by key from sequence")
    void shouldGetEventsByKeyFromSequence() {
        // Arrange
        String entityKey = "order-456";
        eventStore.append(new PartitionedSequenceKey<>(100L, entityKey),
                createTestEventRecord("Event1", entityKey));
        eventStore.append(new PartitionedSequenceKey<>(200L, entityKey),
                createTestEventRecord("Event2", entityKey));
        eventStore.append(new PartitionedSequenceKey<>(300L, entityKey),
                createTestEventRecord("Event3", entityKey));

        // Act
        List<GenericRecord> events = eventStore.getEventsByKeyFromSequence(entityKey, 150L);

        // Assert
        assertEquals(2, events.size());
        assertEquals("Event2", events.get(0).getString("eventType"));
        assertEquals("Event3", events.get(1).getString("eventType"));
    }

    @Test
    @DisplayName("should return empty list when no events for key")
    void shouldReturnEmptyListWhenNoEventsForKey() {
        // Act
        List<GenericRecord> events = eventStore.getEventsByKey("nonexistent-key");

        // Assert
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("should count all events")
    void shouldCountAllEvents() {
        // Arrange
        eventStore.append(new PartitionedSequenceKey<>(1L, "a"), createTestEventRecord("E1", "a"));
        eventStore.append(new PartitionedSequenceKey<>(2L, "b"), createTestEventRecord("E2", "b"));
        eventStore.append(new PartitionedSequenceKey<>(3L, "a"), createTestEventRecord("E3", "a"));

        // Act
        long count = eventStore.count();

        // Assert
        assertEquals(3, count);
    }

    @Test
    @DisplayName("should count events by key")
    void shouldCountEventsByKey() {
        // Arrange
        eventStore.append(new PartitionedSequenceKey<>(1L, "key1"), createTestEventRecord("E1", "key1"));
        eventStore.append(new PartitionedSequenceKey<>(2L, "key2"), createTestEventRecord("E2", "key2"));
        eventStore.append(new PartitionedSequenceKey<>(3L, "key1"), createTestEventRecord("E3", "key1"));
        eventStore.append(new PartitionedSequenceKey<>(4L, "key1"), createTestEventRecord("E4", "key1"));

        // Act
        long countKey1 = eventStore.countByKey("key1");
        long countKey2 = eventStore.countByKey("key2");

        // Assert
        assertEquals(3, countKey1);
        assertEquals(1, countKey2);
    }

    @Test
    @DisplayName("should replay all events in sequence order")
    void shouldReplayAllEventsInSequenceOrder() {
        // Arrange
        eventStore.append(new PartitionedSequenceKey<>(300L, "c"), createTestEventRecord("E3", "c"));
        eventStore.append(new PartitionedSequenceKey<>(100L, "a"), createTestEventRecord("E1", "a"));
        eventStore.append(new PartitionedSequenceKey<>(200L, "b"), createTestEventRecord("E2", "b"));

        List<String> replayedTypes = new ArrayList<>();

        // Act
        eventStore.replayAll(record -> replayedTypes.add(record.getString("eventType")));

        // Assert
        assertEquals(3, replayedTypes.size());
        assertEquals("E1", replayedTypes.get(0));
        assertEquals("E2", replayedTypes.get(1));
        assertEquals("E3", replayedTypes.get(2));
    }

    @Test
    @DisplayName("should replay events by key")
    void shouldReplayEventsByKey() {
        // Arrange
        String targetKey = "target";
        eventStore.append(new PartitionedSequenceKey<>(1L, targetKey), createTestEventRecord("T1", targetKey));
        eventStore.append(new PartitionedSequenceKey<>(2L, "other"), createTestEventRecord("O1", "other"));
        eventStore.append(new PartitionedSequenceKey<>(3L, targetKey), createTestEventRecord("T2", targetKey));

        AtomicInteger count = new AtomicInteger(0);

        // Act
        eventStore.replayByKey(targetKey, record -> count.incrementAndGet());

        // Assert
        assertEquals(2, count.get());
    }

    @Test
    @DisplayName("should get latest sequence")
    void shouldGetLatestSequence() {
        // Arrange
        eventStore.append(new PartitionedSequenceKey<>(100L, "a"), createTestEventRecord("E1", "a"));
        eventStore.append(new PartitionedSequenceKey<>(500L, "b"), createTestEventRecord("E2", "b"));
        eventStore.append(new PartitionedSequenceKey<>(300L, "c"), createTestEventRecord("E3", "c"));

        // Act
        long latest = eventStore.getLatestSequence();

        // Assert
        assertEquals(500L, latest);
    }

    @Test
    @DisplayName("should return -1 for latest sequence when empty")
    void shouldReturnMinusOneForLatestSequenceWhenEmpty() {
        // Act
        long latest = eventStore.getLatestSequence();

        // Assert
        assertEquals(-1L, latest);
    }

    @Test
    @DisplayName("should get latest sequence by key")
    void shouldGetLatestSequenceByKey() {
        // Arrange
        String key = "my-entity";
        eventStore.append(new PartitionedSequenceKey<>(100L, key), createTestEventRecord("E1", key));
        eventStore.append(new PartitionedSequenceKey<>(300L, key), createTestEventRecord("E2", key));
        eventStore.append(new PartitionedSequenceKey<>(200L, key), createTestEventRecord("E3", key));
        eventStore.append(new PartitionedSequenceKey<>(999L, "other"), createTestEventRecord("E4", "other"));

        // Act
        long latest = eventStore.getLatestSequenceByKey(key);

        // Assert
        assertEquals(300L, latest);
    }

    @Test
    @DisplayName("should return store name")
    void shouldReturnStoreName() {
        // Assert
        assertEquals("TestDomain_ES", eventStore.getStoreName());
    }

    @Test
    @DisplayName("should throw exception when hazelcast is null")
    void shouldThrowExceptionWhenHazelcastIsNull() {
        // Act & Assert
        assertThrows(NullPointerException.class, () ->
                new HazelcastEventStore<>(null, "Test")
        );
    }

    @Test
    @DisplayName("should throw exception when domain name is null")
    void shouldThrowExceptionWhenDomainNameIsNull() {
        // Act & Assert
        assertThrows(NullPointerException.class, () ->
                new HazelcastEventStore<>(hazelcast, null)
        );
    }

    // Helper methods

    private GenericRecord createTestEventRecord(String eventType, String key) {
        return GenericRecordBuilder.compact("TestEvent")
                .setString("eventId", java.util.UUID.randomUUID().toString())
                .setString("eventType", eventType)
                .setString("eventVersion", "1.0")
                .setString("key", key)
                .setInt64("timestamp", Instant.now().toEpochMilli())
                .build();
    }

    // Test domain classes

    static class TestDomainObject implements DomainObject<String> {
        private final String id;

        TestDomainObject(String id) {
            this.id = id;
        }

        @Override
        public String getKey() {
            return id;
        }

        @Override
        public GenericRecord toGenericRecord() {
            return GenericRecordBuilder.compact("TestDomainObject")
                    .setString("id", id)
                    .build();
        }

        @Override
        public String getSchemaName() {
            return "TestDomainObject";
        }
    }

    static class TestEvent extends DomainEvent<TestDomainObject, String> {
        TestEvent(String key) {
            super(key);
            this.eventType = "TestEvent";
        }

        @Override
        public GenericRecord toGenericRecord() {
            return GenericRecordBuilder.compact("TestEvent")
                    .setString("eventId", eventId)
                    .setString("eventType", eventType)
                    .setString("key", key)
                    .build();
        }

        @Override
        public GenericRecord apply(GenericRecord domainObjectRecord) {
            return domainObjectRecord;
        }

        @Override
        public String getSchemaName() {
            return "TestEvent";
        }
    }
}
