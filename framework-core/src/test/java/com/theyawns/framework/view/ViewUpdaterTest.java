package com.theyawns.framework.view;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.framework.domain.DomainObject;
import com.theyawns.framework.event.DomainEvent;
import com.theyawns.framework.store.HazelcastEventStore;
import com.theyawns.framework.store.PartitionedSequenceKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ViewUpdater - Abstract base class for updating views from events")
class ViewUpdaterTest {

    private static HazelcastInstance hazelcast;
    private HazelcastViewStore<String> viewStore;
    private TestViewUpdater viewUpdater;
    private HazelcastEventStore<TestDomainObject, String, TestEvent> eventStore;

    @BeforeAll
    static void setUpClass() {
        Config config = new Config();
        config.setClusterName("updater-test-cluster-" + System.currentTimeMillis());
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
        viewStore = new HazelcastViewStore<>(hazelcast, "ViewUpdaterTest");
        viewUpdater = new TestViewUpdater(viewStore);
        eventStore = new HazelcastEventStore<>(hazelcast, "ViewUpdaterTestEvents");
    }

    @AfterEach
    void tearDown() {
        viewStore.clear();
        eventStore.getUnderlyingMap().clear();
    }

    @Test
    @DisplayName("should update view for create event")
    void shouldUpdateViewForCreateEvent() {
        // Arrange
        GenericRecord eventRecord = createEventRecord("EntityCreated", "entity1", "New Entity", 100);

        // Act
        GenericRecord result = viewUpdater.update(eventRecord);

        // Assert
        assertNotNull(result);
        assertEquals("New Entity", result.getString("name"));
        assertEquals(100, result.getInt32("value"));

        // Verify persistence
        Optional<GenericRecord> stored = viewStore.get("entity1");
        assertTrue(stored.isPresent());
        assertEquals("New Entity", stored.get().getString("name"));
    }

    @Test
    @DisplayName("should update view for update event")
    void shouldUpdateViewForUpdateEvent() {
        // Arrange - Create initial entry
        viewStore.put("entity1", createViewRecord("entity1", "Original", 50));

        // Arrange - Update event
        GenericRecord eventRecord = createEventRecord("EntityUpdated", "entity1", "Updated", 75);

        // Act
        GenericRecord result = viewUpdater.update(eventRecord);

        // Assert
        assertNotNull(result);
        assertEquals("Updated", result.getString("name"));
        assertEquals(75, result.getInt32("value"));
    }

    @Test
    @DisplayName("should delete view entry for delete event")
    void shouldDeleteViewEntryForDeleteEvent() {
        // Arrange - Create initial entry
        viewStore.put("entity1", createViewRecord("entity1", "To Delete", 0));
        assertTrue(viewStore.containsKey("entity1"));

        // Arrange - Delete event
        GenericRecord eventRecord = createEventRecord("EntityDeleted", "entity1", null, 0);

        // Act
        GenericRecord result = viewUpdater.update(eventRecord);

        // Assert
        assertNull(result);
        assertFalse(viewStore.containsKey("entity1"));
    }

    @Test
    @DisplayName("should handle unknown event type by keeping current state")
    void shouldHandleUnknownEventType() {
        // Arrange
        viewStore.put("entity1", createViewRecord("entity1", "Original", 100));
        GenericRecord eventRecord = createEventRecord("UnknownEvent", "entity1", "Ignored", 999);

        // Act
        GenericRecord result = viewUpdater.update(eventRecord);

        // Assert - Current state should be preserved
        assertNotNull(result);
        assertEquals("Original", result.getString("name"));
        assertEquals(100, result.getInt32("value"));
    }

    @Test
    @DisplayName("should return null when key cannot be extracted")
    void shouldReturnNullWhenKeyCannotBeExtracted() {
        // Arrange - Event without key field
        GenericRecord invalidEvent = GenericRecordBuilder.compact("InvalidEvent")
                .setString("eventType", "SomeEvent")
                .build();

        // Create updater that returns null for key extraction
        ViewUpdater<String> badKeyUpdater = new ViewUpdater<>(viewStore) {
            @Override
            protected String extractKey(GenericRecord eventRecord) {
                return null;
            }

            @Override
            protected GenericRecord applyEvent(GenericRecord eventRecord, GenericRecord currentState) {
                return currentState;
            }
        };

        // Act
        GenericRecord result = badKeyUpdater.update(invalidEvent);

        // Assert
        assertNull(result);
    }

    @Test
    @DisplayName("should update direct without entry processor")
    void shouldUpdateDirectWithoutEntryProcessor() {
        // Arrange
        GenericRecord eventRecord = createEventRecord("EntityCreated", "entity1", "Direct Create", 42);

        // Act
        GenericRecord result = viewUpdater.updateDirect(eventRecord);

        // Assert
        assertNotNull(result);
        assertEquals("Direct Create", result.getString("name"));
        assertTrue(viewStore.containsKey("entity1"));
    }

    @Test
    @DisplayName("should rebuild entire view from event store")
    void shouldRebuildEntireViewFromEventStore() {
        // Arrange - Add events to store
        eventStore.append(new PartitionedSequenceKey<>(1L, "a"),
                createEventRecord("EntityCreated", "a", "Entity A", 10));
        eventStore.append(new PartitionedSequenceKey<>(2L, "b"),
                createEventRecord("EntityCreated", "b", "Entity B", 20));
        eventStore.append(new PartitionedSequenceKey<>(3L, "a"),
                createEventRecord("EntityUpdated", "a", "Entity A Updated", 15));

        // Arrange - Pre-populate view with stale data
        viewStore.put("stale", createViewRecord("stale", "Stale Data", 0));

        // Act
        long count = viewUpdater.rebuild(eventStore);

        // Assert
        assertEquals(3, count);
        assertFalse(viewStore.containsKey("stale")); // Stale data cleared
        assertTrue(viewStore.containsKey("a"));
        assertTrue(viewStore.containsKey("b"));
        assertEquals("Entity A Updated", viewStore.get("a").get().getString("name"));
        assertEquals(15, viewStore.get("a").get().getInt32("value"));
        assertEquals("Entity B", viewStore.get("b").get().getString("name"));
    }

    @Test
    @DisplayName("should rebuild view for single key")
    void shouldRebuildViewForSingleKey() {
        // Arrange - Add events for multiple entities
        eventStore.append(new PartitionedSequenceKey<>(1L, "a"),
                createEventRecord("EntityCreated", "a", "Entity A", 10));
        eventStore.append(new PartitionedSequenceKey<>(2L, "b"),
                createEventRecord("EntityCreated", "b", "Entity B", 20));
        eventStore.append(new PartitionedSequenceKey<>(3L, "a"),
                createEventRecord("EntityUpdated", "a", "Entity A v2", 15));

        // Pre-populate view
        viewStore.put("a", createViewRecord("a", "Stale A", 0));
        viewStore.put("b", createViewRecord("b", "Existing B", 25));

        // Act - Rebuild only key "a"
        Optional<GenericRecord> result = viewUpdater.rebuildForKey("a", eventStore);

        // Assert
        assertTrue(result.isPresent());
        assertEquals("Entity A v2", result.get().getString("name"));
        assertEquals(15, result.get().getInt32("value"));

        // Key "b" should be untouched
        Optional<GenericRecord> bRecord = viewStore.get("b");
        assertTrue(bRecord.isPresent());
        assertEquals("Existing B", bRecord.get().getString("name"));
        assertEquals(25, bRecord.get().getInt32("value"));
    }

    @Test
    @DisplayName("should return empty when rebuilding key with no events")
    void shouldReturnEmptyWhenRebuildingKeyWithNoEvents() {
        // Act
        Optional<GenericRecord> result = viewUpdater.rebuildForKey("nonexistent", eventStore);

        // Assert
        assertTrue(result.isEmpty());
        assertFalse(viewStore.containsKey("nonexistent"));
    }

    @Test
    @DisplayName("should return view store from getter")
    void shouldReturnViewStoreFromGetter() {
        assertSame(viewStore, viewUpdater.getViewStore());
    }

    @Test
    @DisplayName("should throw exception when viewStore is null")
    void shouldThrowExceptionWhenViewStoreIsNull() {
        assertThrows(NullPointerException.class, () ->
                new TestViewUpdater(null)
        );
    }

    @Test
    @DisplayName("should throw exception when eventRecord is null for update")
    void shouldThrowExceptionWhenEventRecordIsNullForUpdate() {
        assertThrows(NullPointerException.class, () ->
                viewUpdater.update(null)
        );
    }

    @Test
    @DisplayName("should throw exception when eventStore is null for rebuild")
    void shouldThrowExceptionWhenEventStoreIsNullForRebuild() {
        assertThrows(NullPointerException.class, () ->
                viewUpdater.rebuild(null)
        );
    }

    @Test
    @DisplayName("should safely extract event type using helper")
    void shouldSafelyExtractEventType() {
        GenericRecord eventRecord = createEventRecord("TestType", "key", "name", 0);
        assertEquals("TestType", viewUpdater.testGetEventType(eventRecord));

        // Test with missing field
        GenericRecord noTypeRecord = GenericRecordBuilder.compact("NoType")
                .setString("key", "key")
                .build();
        assertEquals("unknown", viewUpdater.testGetEventType(noTypeRecord));
    }

    @Test
    @DisplayName("should safely extract string field using helper")
    void shouldSafelyExtractStringField() {
        GenericRecord record = createEventRecord("Type", "myKey", "myName", 0);
        assertEquals("myKey", viewUpdater.testGetStringField(record, "key"));
        assertNull(viewUpdater.testGetStringField(record, "nonexistent"));
    }

    @Test
    @DisplayName("should safely extract numeric fields using helpers")
    void shouldSafelyExtractNumericFields() {
        GenericRecord record = createViewRecord("key", "name", 42);
        assertEquals(42, viewUpdater.testGetIntField(record, "value"));
        assertEquals(0, viewUpdater.testGetIntField(record, "nonexistent"));
    }

    // Helper methods

    private GenericRecord createEventRecord(String eventType, String key, String name, int value) {
        GenericRecordBuilder builder = GenericRecordBuilder.compact("TestEvent")
                .setString("eventType", eventType)
                .setString("key", key)
                .setInt32("value", value)
                .setInt64("timestamp", System.currentTimeMillis());

        if (name != null) {
            builder.setString("name", name);
        }

        return builder.build();
    }

    private GenericRecord createViewRecord(String key, String name, int value) {
        return GenericRecordBuilder.compact("TestView")
                .setString("key", key)
                .setString("name", name)
                .setInt32("value", value)
                .build();
    }

    // Test implementation of ViewUpdater

    static class TestViewUpdater extends ViewUpdater<String> {

        TestViewUpdater(HazelcastViewStore<String> viewStore) {
            super(viewStore);
        }

        @Override
        protected String extractKey(GenericRecord eventRecord) {
            return getStringField(eventRecord, "key");
        }

        @Override
        protected GenericRecord applyEvent(GenericRecord eventRecord, GenericRecord currentState) {
            String eventType = getEventType(eventRecord);

            return switch (eventType) {
                case "EntityCreated" -> createNewEntity(eventRecord);
                case "EntityUpdated" -> updateEntity(eventRecord, currentState);
                case "EntityDeleted" -> null;
                default -> currentState;
            };
        }

        private GenericRecord createNewEntity(GenericRecord eventRecord) {
            return GenericRecordBuilder.compact("TestView")
                    .setString("key", eventRecord.getString("key"))
                    .setString("name", getStringField(eventRecord, "name"))
                    .setInt32("value", getIntField(eventRecord, "value"))
                    .build();
        }

        private GenericRecord updateEntity(GenericRecord eventRecord, GenericRecord currentState) {
            return GenericRecordBuilder.compact("TestView")
                    .setString("key", eventRecord.getString("key"))
                    .setString("name", getStringField(eventRecord, "name"))
                    .setInt32("value", getIntField(eventRecord, "value"))
                    .build();
        }

        // Expose protected methods for testing
        String testGetEventType(GenericRecord record) {
            return getEventType(record);
        }

        String testGetStringField(GenericRecord record, String field) {
            return getStringField(record, field);
        }

        int testGetIntField(GenericRecord record, String field) {
            return getIntField(record, field);
        }
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
