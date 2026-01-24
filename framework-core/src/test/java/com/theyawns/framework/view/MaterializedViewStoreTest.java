package com.theyawns.framework.view;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MaterializedViewStore - Fast read access to denormalized data")
class MaterializedViewStoreTest {

    private InMemoryViewStore viewStore;

    @BeforeEach
    void setUp() {
        viewStore = new InMemoryViewStore("test-view");
    }

    @Test
    @DisplayName("should put and get entries by key")
    void shouldPutAndGet() {
        // Arrange
        GenericRecord record = createRecord("key1", "value1");

        // Act
        viewStore.put("key1", record);
        Optional<GenericRecord> retrieved = viewStore.get("key1");

        // Assert
        assertTrue(retrieved.isPresent());
        assertEquals("value1", retrieved.get().getString("value"));
    }

    @Test
    @DisplayName("should return empty Optional for missing key")
    void shouldReturnEmptyForMissingKey() {
        // Act
        Optional<GenericRecord> result = viewStore.get("nonexistent");

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("should remove entry and return removed value")
    void shouldRemove() {
        // Arrange
        GenericRecord record = createRecord("key1", "value1");
        viewStore.put("key1", record);

        // Act
        GenericRecord removed = viewStore.remove("key1");

        // Assert
        assertNotNull(removed);
        assertEquals("value1", removed.getString("value"));
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
    void shouldContainsKey() {
        // Arrange
        viewStore.put("key1", createRecord("key1", "value1"));

        // Assert
        assertTrue(viewStore.containsKey("key1"));
        assertFalse(viewStore.containsKey("key2"));
    }

    @Test
    @DisplayName("should return all keys")
    void shouldReturnAllKeys() {
        // Arrange
        viewStore.put("key1", createRecord("key1", "value1"));
        viewStore.put("key2", createRecord("key2", "value2"));

        // Act
        Collection<String> keys = viewStore.keys();

        // Assert
        assertEquals(2, keys.size());
        assertTrue(keys.contains("key1"));
        assertTrue(keys.contains("key2"));
    }

    @Test
    @DisplayName("should return all values")
    void shouldReturnAllValues() {
        // Arrange
        viewStore.put("key1", createRecord("key1", "value1"));
        viewStore.put("key2", createRecord("key2", "value2"));

        // Act
        Collection<GenericRecord> values = viewStore.values();

        // Assert
        assertEquals(2, values.size());
    }

    @Test
    @DisplayName("should query with predicate filter")
    void shouldQueryWithPredicate() {
        // Arrange
        viewStore.put("key1", createRecord("key1", "apple"));
        viewStore.put("key2", createRecord("key2", "banana"));
        viewStore.put("key3", createRecord("key3", "apricot"));

        // Act
        Collection<GenericRecord> results = viewStore.query(
                record -> record.getString("value").startsWith("a")
        );

        // Assert
        assertEquals(2, results.size());
    }

    @Test
    @DisplayName("should return correct size")
    void shouldReturnSize() {
        // Assert initial state
        assertEquals(0, viewStore.size());

        // Act & Assert after adding entries
        viewStore.put("key1", createRecord("key1", "value1"));
        assertEquals(1, viewStore.size());

        viewStore.put("key2", createRecord("key2", "value2"));
        assertEquals(2, viewStore.size());
    }

    @Test
    @DisplayName("should clear all entries")
    void shouldClear() {
        // Arrange
        viewStore.put("key1", createRecord("key1", "value1"));
        viewStore.put("key2", createRecord("key2", "value2"));
        assertEquals(2, viewStore.size());

        // Act
        viewStore.clear();

        // Assert
        assertEquals(0, viewStore.size());
        assertTrue(viewStore.get("key1").isEmpty());
    }

    @Test
    @DisplayName("should return view name")
    void shouldReturnViewName() {
        assertEquals("test-view", viewStore.getViewName());
    }

    private GenericRecord createRecord(String key, String value) {
        return GenericRecordBuilder.compact("TestRecord")
                .setString("key", key)
                .setString("value", value)
                .build();
    }

    /**
     * In-memory implementation of MaterializedViewStore for testing.
     */
    static class InMemoryViewStore implements MaterializedViewStore<String> {
        private final String viewName;
        private final Map<String, GenericRecord> store = new HashMap<>();

        InMemoryViewStore(String viewName) {
            this.viewName = viewName;
        }

        @Override
        public Optional<GenericRecord> get(String key) {
            return Optional.ofNullable(store.get(key));
        }

        @Override
        public void put(String key, GenericRecord value) {
            store.put(key, value);
        }

        @Override
        public GenericRecord remove(String key) {
            return store.remove(key);
        }

        @Override
        public boolean containsKey(String key) {
            return store.containsKey(key);
        }

        @Override
        public Collection<String> keys() {
            return new ArrayList<>(store.keySet());
        }

        @Override
        public Collection<GenericRecord> values() {
            return new ArrayList<>(store.values());
        }

        @Override
        public Collection<GenericRecord> query(Predicate<GenericRecord> predicate) {
            List<GenericRecord> results = new ArrayList<>();
            for (GenericRecord record : store.values()) {
                if (predicate.test(record)) {
                    results.add(record);
                }
            }
            return results;
        }

        @Override
        public int size() {
            return store.size();
        }

        @Override
        public void clear() {
            store.clear();
        }

        @Override
        public String getViewName() {
            return viewName;
        }
    }
}
