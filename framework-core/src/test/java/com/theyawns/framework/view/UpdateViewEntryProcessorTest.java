package com.theyawns.framework.view;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UpdateViewEntryProcessor - Atomic view updates via Hazelcast EntryProcessor")
class UpdateViewEntryProcessorTest {

    private static HazelcastInstance hazelcast;
    private IMap<String, GenericRecord> testMap;

    @BeforeAll
    static void setUpClass() {
        Config config = new Config();
        config.setClusterName("processor-test-cluster-" + System.currentTimeMillis());
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
        testMap = hazelcast.getMap("test-processor-map");
    }

    @AfterEach
    void tearDown() {
        testMap.clear();
    }

    @Test
    @DisplayName("should apply event to existing entry")
    void shouldApplyEventToExistingEntry() {
        // Arrange
        testMap.put("key1", createRecord("key1", "Original Name", 100));

        UpdateViewEntryProcessor<String> processor = new UpdateViewEntryProcessor<>(
                current -> {
                    int currentValue = current.getInt32("value");
                    return createRecord(
                            current.getString("key"),
                            "Updated Name",
                            currentValue + 50
                    );
                }
        );

        // Act
        GenericRecord result = testMap.executeOnKey("key1", processor);

        // Assert
        assertNotNull(result);
        assertEquals("Updated Name", result.getString("name"));
        assertEquals(150, result.getInt32("value"));

        // Verify persistence
        GenericRecord stored = testMap.get("key1");
        assertEquals("Updated Name", stored.getString("name"));
        assertEquals(150, stored.getInt32("value"));
    }

    @Test
    @DisplayName("should create new entry when current is null")
    void shouldCreateNewEntryWhenCurrentIsNull() {
        // Arrange
        UpdateViewEntryProcessor<String> processor = new UpdateViewEntryProcessor<>(
                current -> {
                    assertNull(current);
                    return createRecord("newKey", "New Entry", 42);
                }
        );

        // Act
        GenericRecord result = testMap.executeOnKey("newKey", processor);

        // Assert
        assertNotNull(result);
        assertEquals("New Entry", result.getString("name"));
        assertEquals(42, result.getInt32("value"));
        assertTrue(testMap.containsKey("newKey"));
    }

    @Test
    @DisplayName("should delete entry when applier returns null")
    void shouldDeleteEntryWhenApplierReturnsNull() {
        // Arrange
        testMap.put("key1", createRecord("key1", "To Delete", 0));
        assertTrue(testMap.containsKey("key1"));

        UpdateViewEntryProcessor<String> processor = new UpdateViewEntryProcessor<>(
                current -> null
        );

        // Act
        GenericRecord result = testMap.executeOnKey("key1", processor);

        // Assert
        assertNull(result);
        assertFalse(testMap.containsKey("key1"));
    }

    @Test
    @DisplayName("should store event record for debugging")
    void shouldStoreEventRecordForDebugging() {
        // Arrange
        GenericRecord eventRecord = createEventRecord("CustomerUpdated", "key1");

        UpdateViewEntryProcessor<String> processor = new UpdateViewEntryProcessor<>(
                current -> createRecord("key1", "Name", 1),
                eventRecord
        );

        // Assert
        assertNotNull(processor.getEventRecord());
        assertEquals("CustomerUpdated", processor.getEventRecord().getString("eventType"));
    }

    @Test
    @DisplayName("should return null for event record when not set")
    void shouldReturnNullForEventRecordWhenNotSet() {
        // Arrange
        UpdateViewEntryProcessor<String> processor = new UpdateViewEntryProcessor<>(
                current -> current
        );

        // Assert
        assertNull(processor.getEventRecord());
    }

    @Test
    @DisplayName("should create processor from domain event")
    void shouldCreateProcessorFromDomainEvent() {
        // Arrange
        TestDomainEvent event = new TestDomainEvent("key1", "Event Data");

        // Act
        UpdateViewEntryProcessor<String> processor = UpdateViewEntryProcessor.fromEvent(event);

        // Assert
        assertNotNull(processor);
        assertNotNull(processor.getEventRecord());
        assertEquals("TestDomainEvent", processor.getEventRecord().getString("eventType"));
    }

    @Test
    @DisplayName("should create setValue processor")
    void shouldCreateSetValueProcessor() {
        // Arrange
        testMap.put("key1", createRecord("key1", "Original", 0));
        GenericRecord newValue = createRecord("key1", "Replaced", 999);

        UpdateViewEntryProcessor<String> processor = UpdateViewEntryProcessor.setValue(newValue);

        // Act
        GenericRecord result = testMap.executeOnKey("key1", processor);

        // Assert
        assertNotNull(result);
        assertEquals("Replaced", result.getString("name"));
        assertEquals(999, result.getInt32("value"));
    }

    @Test
    @DisplayName("should create delete processor")
    void shouldCreateDeleteProcessor() {
        // Arrange
        testMap.put("key1", createRecord("key1", "To Delete", 0));

        UpdateViewEntryProcessor<String> processor = UpdateViewEntryProcessor.delete();

        // Act
        GenericRecord result = testMap.executeOnKey("key1", processor);

        // Assert
        assertNull(result);
        assertFalse(testMap.containsKey("key1"));
    }

    @Test
    @DisplayName("should throw exception when eventApplier is null")
    void shouldThrowExceptionWhenEventApplierIsNull() {
        assertThrows(NullPointerException.class, () ->
                new UpdateViewEntryProcessor<>(null)
        );
    }

    @Test
    @DisplayName("should throw exception when event is null for fromEvent")
    void shouldThrowExceptionWhenEventIsNullForFromEvent() {
        assertThrows(NullPointerException.class, () ->
                UpdateViewEntryProcessor.fromEvent(null)
        );
    }

    @Test
    @DisplayName("should throw exception when value is null for setValue")
    void shouldThrowExceptionWhenValueIsNullForSetValue() {
        assertThrows(NullPointerException.class, () ->
                UpdateViewEntryProcessor.setValue(null)
        );
    }

    @Test
    @DisplayName("should handle concurrent updates atomically")
    void shouldHandleConcurrentUpdatesAtomically() throws InterruptedException {
        // Arrange
        testMap.put("counter", createRecord("counter", "Counter", 0));
        int numThreads = 10;
        int incrementsPerThread = 100;

        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < incrementsPerThread; j++) {
                    UpdateViewEntryProcessor<String> processor = new UpdateViewEntryProcessor<>(
                            current -> {
                                int currentValue = current.getInt32("value");
                                return createRecord(
                                        current.getString("key"),
                                        current.getString("name"),
                                        currentValue + 1
                                );
                            }
                    );
                    testMap.executeOnKey("counter", processor);
                }
            });
        }

        // Act
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        // Assert
        GenericRecord result = testMap.get("counter");
        assertEquals(numThreads * incrementsPerThread, result.getInt32("value"));
    }

    // Helper methods

    private GenericRecord createRecord(String key, String name, int value) {
        return GenericRecordBuilder.compact("TestRecord")
                .setString("key", key)
                .setString("name", name)
                .setInt32("value", value)
                .build();
    }

    private GenericRecord createEventRecord(String eventType, String key) {
        return GenericRecordBuilder.compact("TestEvent")
                .setString("eventType", eventType)
                .setString("key", key)
                .setInt64("timestamp", System.currentTimeMillis())
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

    static class TestDomainEvent extends DomainEvent<TestDomainObject, String> {
        private final String data;

        TestDomainEvent(String key, String data) {
            super(key);
            this.data = data;
            this.eventType = "TestDomainEvent";
        }

        @Override
        public GenericRecord toGenericRecord() {
            return GenericRecordBuilder.compact("TestDomainEvent")
                    .setString("eventId", eventId)
                    .setString("eventType", eventType)
                    .setString("key", key)
                    .setString("data", data)
                    .setInt64("timestamp", timestamp.toEpochMilli())
                    .build();
        }

        @Override
        public GenericRecord apply(GenericRecord domainObjectRecord) {
            return GenericRecordBuilder.compact("TestDomainObject")
                    .setString("id", key)
                    .setString("data", data)
                    .build();
        }

        @Override
        public String getSchemaName() {
            return "TestDomainEvent";
        }
    }
}
