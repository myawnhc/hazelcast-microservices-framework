package com.theyawns.framework.view;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.framework.domain.DomainObject;
import com.theyawns.framework.event.DomainEvent;
import com.theyawns.framework.store.EventStore;
import com.theyawns.framework.store.HazelcastEventStore;
import com.theyawns.framework.store.PartitionedSequenceKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HazelcastViewStore - Materialized view implementation using Hazelcast IMap")
class HazelcastViewStoreTest {

    private static HazelcastInstance hazelcast;
    private HazelcastViewStore<String> viewStore;

    @BeforeAll
    static void setUpClass() {
        Config config = new Config();
        config.setClusterName("view-test-cluster-" + System.currentTimeMillis());
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
        viewStore = new HazelcastViewStore<>(hazelcast, "TestDomain");
    }

    @AfterEach
    void tearDown() {
        viewStore.clear();
    }

    @Test
    @DisplayName("should put and retrieve entry by key")
    void shouldPutAndRetrieveEntryByKey() {
        // Arrange
        GenericRecord record = createTestRecord("key1", "John Doe", "john@example.com");

        // Act
        viewStore.put("key1", record);
        Optional<GenericRecord> retrieved = viewStore.get("key1");

        // Assert
        assertTrue(retrieved.isPresent());
        assertEquals("John Doe", retrieved.get().getString("name"));
        assertEquals("john@example.com", retrieved.get().getString("email"));
    }

    @Test
    @DisplayName("should return empty when key not found")
    void shouldReturnEmptyWhenKeyNotFound() {
        // Act
        Optional<GenericRecord> result = viewStore.get("nonexistent");

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should remove entry and return removed value")
    void shouldRemoveEntryAndReturnRemovedValue() {
        // Arrange
        GenericRecord record = createTestRecord("key1", "Jane Doe", "jane@example.com");
        viewStore.put("key1", record);

        // Act
        GenericRecord removed = viewStore.remove("key1");

        // Assert
        assertNotNull(removed);
        assertEquals("Jane Doe", removed.getString("name"));
        assertTrue(viewStore.get("key1").isEmpty());
    }

    @Test
    @DisplayName("should return null when removing missing key")
    void shouldReturnNullWhenRemovingMissingKey() {
        // Act
        GenericRecord removed = viewStore.remove("nonexistent");

        // Assert
        assertNull(removed);
    }

    @Test
    @DisplayName("should check if key exists")
    void shouldCheckIfKeyExists() {
        // Arrange
        viewStore.put("key1", createTestRecord("key1", "Test", "test@test.com"));

        // Assert
        assertTrue(viewStore.containsKey("key1"));
        assertFalse(viewStore.containsKey("key2"));
    }

    @Test
    @DisplayName("should return all keys")
    void shouldReturnAllKeys() {
        // Arrange
        viewStore.put("key1", createTestRecord("key1", "Name1", "e1@test.com"));
        viewStore.put("key2", createTestRecord("key2", "Name2", "e2@test.com"));
        viewStore.put("key3", createTestRecord("key3", "Name3", "e3@test.com"));

        // Act
        Collection<String> keys = viewStore.keys();

        // Assert
        assertEquals(3, keys.size());
        assertTrue(keys.contains("key1"));
        assertTrue(keys.contains("key2"));
        assertTrue(keys.contains("key3"));
    }

    @Test
    @DisplayName("should return all values")
    void shouldReturnAllValues() {
        // Arrange
        viewStore.put("key1", createTestRecord("key1", "Name1", "e1@test.com"));
        viewStore.put("key2", createTestRecord("key2", "Name2", "e2@test.com"));

        // Act
        Collection<GenericRecord> values = viewStore.values();

        // Assert
        assertEquals(2, values.size());
    }

    @Test
    @DisplayName("should query with predicate filter")
    void shouldQueryWithPredicateFilter() {
        // Arrange
        viewStore.put("key1", createTestRecord("key1", "Alice", "alice@example.com"));
        viewStore.put("key2", createTestRecord("key2", "Bob", "bob@test.com"));
        viewStore.put("key3", createTestRecord("key3", "Amy", "amy@example.com"));

        // Act
        Collection<GenericRecord> results = viewStore.query(
                record -> record.getString("name").startsWith("A")
        );

        // Assert
        assertEquals(2, results.size());
    }

    @Test
    @DisplayName("should return correct size")
    void shouldReturnCorrectSize() {
        // Assert initial state
        assertEquals(0, viewStore.size());

        // Act & Assert after adding entries
        viewStore.put("key1", createTestRecord("key1", "N1", "e1@t.com"));
        assertEquals(1, viewStore.size());

        viewStore.put("key2", createTestRecord("key2", "N2", "e2@t.com"));
        assertEquals(2, viewStore.size());

        viewStore.remove("key1");
        assertEquals(1, viewStore.size());
    }

    @Test
    @DisplayName("should clear all entries")
    void shouldClearAllEntries() {
        // Arrange
        viewStore.put("key1", createTestRecord("key1", "N1", "e1@t.com"));
        viewStore.put("key2", createTestRecord("key2", "N2", "e2@t.com"));
        assertEquals(2, viewStore.size());

        // Act
        viewStore.clear();

        // Assert
        assertEquals(0, viewStore.size());
        assertTrue(viewStore.get("key1").isEmpty());
        assertTrue(viewStore.get("key2").isEmpty());
    }

    @Test
    @DisplayName("should return view name with _VIEW suffix")
    void shouldReturnViewNameWithSuffix() {
        assertEquals("TestDomain_VIEW", viewStore.getViewName());
    }

    @Test
    @DisplayName("should execute entry processor on key")
    void shouldExecuteEntryProcessorOnKey() {
        // Arrange
        viewStore.put("key1", createTestRecord("key1", "Original", "original@test.com"));

        // Act
        UpdateViewEntryProcessor<String> processor = new UpdateViewEntryProcessor<>(
                current -> createTestRecord("key1", "Updated", current.getString("email"))
        );
        GenericRecord result = viewStore.executeOnKey("key1", processor);

        // Assert
        assertNotNull(result);
        assertEquals("Updated", result.getString("name"));

        // Verify persistence
        Optional<GenericRecord> stored = viewStore.get("key1");
        assertTrue(stored.isPresent());
        assertEquals("Updated", stored.get().getString("name"));
    }

    @Test
    @DisplayName("should execute entry processor on new key")
    void shouldExecuteEntryProcessorOnNewKey() {
        // Act
        UpdateViewEntryProcessor<String> processor = new UpdateViewEntryProcessor<>(
                current -> {
                    assertNull(current); // Should be null for new entry
                    return createTestRecord("newKey", "Created", "new@test.com");
                }
        );
        GenericRecord result = viewStore.executeOnKey("newKey", processor);

        // Assert
        assertNotNull(result);
        assertEquals("Created", result.getString("name"));
        assertTrue(viewStore.containsKey("newKey"));
    }

    @Test
    @DisplayName("should rebuild view from event store")
    void shouldRebuildViewFromEventStore() {
        // Arrange
        HazelcastEventStore<TestDomainObject, String, TestEvent> eventStore =
                new HazelcastEventStore<>(hazelcast, "RebuildTest");

        // Add some events
        eventStore.append(new PartitionedSequenceKey<>(1L, "entity1"),
                createTestEventRecord("EntityCreated", "entity1", "Name1"));
        eventStore.append(new PartitionedSequenceKey<>(2L, "entity2"),
                createTestEventRecord("EntityCreated", "entity2", "Name2"));
        eventStore.append(new PartitionedSequenceKey<>(3L, "entity1"),
                createTestEventRecord("EntityUpdated", "entity1", "Name1-Updated"));

        // Pre-populate view with stale data
        viewStore.put("staleKey", createTestRecord("staleKey", "Stale", "stale@test.com"));

        // Act
        long count = viewStore.rebuild(eventStore, eventRecord -> {
            String key = eventRecord.getString("key");
            String name = eventRecord.getString("name");
            viewStore.put(key, createTestRecord(key, name, key + "@test.com"));
        });

        // Assert
        assertEquals(3, count);
        assertFalse(viewStore.containsKey("staleKey")); // Stale data should be cleared
        assertTrue(viewStore.containsKey("entity1"));
        assertTrue(viewStore.containsKey("entity2"));
        assertEquals("Name1-Updated", viewStore.get("entity1").get().getString("name"));

        // Cleanup
        eventStore.getUnderlyingMap().clear();
    }

    @Test
    @DisplayName("should throw exception when hazelcast is null")
    void shouldThrowExceptionWhenHazelcastIsNull() {
        assertThrows(NullPointerException.class, () ->
                new HazelcastViewStore<>(null, "Test")
        );
    }

    @Test
    @DisplayName("should throw exception when domain name is null")
    void shouldThrowExceptionWhenDomainNameIsNull() {
        assertThrows(NullPointerException.class, () ->
                new HazelcastViewStore<>(hazelcast, null)
        );
    }

    @Test
    @DisplayName("should throw exception when key is null")
    void shouldThrowExceptionWhenKeyIsNull() {
        assertThrows(NullPointerException.class, () -> viewStore.get(null));
        assertThrows(NullPointerException.class, () ->
                viewStore.put(null, createTestRecord("k", "n", "e"))
        );
        assertThrows(NullPointerException.class, () -> viewStore.remove(null));
        assertThrows(NullPointerException.class, () -> viewStore.containsKey(null));
    }

    @Test
    @DisplayName("should throw exception when value is null for put")
    void shouldThrowExceptionWhenValueIsNull() {
        assertThrows(NullPointerException.class, () ->
                viewStore.put("key1", null)
        );
    }

    @Test
    @DisplayName("should throw exception when predicate is null for query")
    void shouldThrowExceptionWhenPredicateIsNull() {
        assertThrows(NullPointerException.class, () ->
                viewStore.query(null)
        );
    }

    @Test
    @DisplayName("should return underlying map")
    void shouldReturnUnderlyingMap() {
        assertNotNull(viewStore.getUnderlyingMap());
        assertEquals("TestDomain_VIEW", viewStore.getUnderlyingMap().getName());
    }

    @Test
    @DisplayName("should return hazelcast instance")
    void shouldReturnHazelcastInstance() {
        assertNotNull(viewStore.getHazelcast());
        assertSame(hazelcast, viewStore.getHazelcast());
    }

    // Helper methods

    private GenericRecord createTestRecord(String key, String name, String email) {
        return GenericRecordBuilder.compact("TestEntity")
                .setString("key", key)
                .setString("name", name)
                .setString("email", email)
                .build();
    }

    private GenericRecord createTestEventRecord(String eventType, String key, String name) {
        return GenericRecordBuilder.compact("TestEvent")
                .setString("eventType", eventType)
                .setString("key", key)
                .setString("name", name)
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
