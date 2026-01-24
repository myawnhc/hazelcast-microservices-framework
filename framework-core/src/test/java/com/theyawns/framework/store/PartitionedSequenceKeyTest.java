package com.theyawns.framework.store;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PartitionedSequenceKey - Composite key for partitioned event storage")
class PartitionedSequenceKeyTest {

    @Test
    @DisplayName("should create key with sequence and partition key")
    void shouldCreateKeyWithSequenceAndPartitionKey() {
        // Act
        PartitionedSequenceKey<String> key = new PartitionedSequenceKey<>(1000L, "customer-123");

        // Assert
        assertEquals(1000L, key.getSequence());
        assertEquals("customer-123", key.getKey());
        assertEquals("customer-123", key.getPartitionKey());
    }

    @Test
    @DisplayName("should throw exception when partition key is null")
    void shouldThrowExceptionWhenPartitionKeyIsNull() {
        // Act & Assert
        assertThrows(IllegalArgumentException.class, () ->
                new PartitionedSequenceKey<>(1000L, null)
        );
    }

    @Test
    @DisplayName("should serialize to GenericRecord")
    void shouldSerializeToGenericRecord() {
        // Arrange
        PartitionedSequenceKey<String> key = new PartitionedSequenceKey<>(2000L, "order-456");

        // Act
        GenericRecord record = key.toGenericRecord();

        // Assert
        assertNotNull(record);
        assertEquals(2000L, record.getInt64("sequence"));
        assertEquals("order-456", record.getString("partitionKey"));
    }

    @Test
    @DisplayName("should deserialize from GenericRecord")
    void shouldDeserializeFromGenericRecord() {
        // Arrange
        PartitionedSequenceKey<String> original = new PartitionedSequenceKey<>(3000L, "product-789");
        GenericRecord record = original.toGenericRecord();

        // Act
        PartitionedSequenceKey<String> restored = PartitionedSequenceKey.fromGenericRecord(record);

        // Assert
        assertEquals(original.getSequence(), restored.getSequence());
        assertEquals(original.getKey(), restored.getKey());
    }

    @Test
    @DisplayName("should compare keys by sequence number")
    void shouldCompareKeysBySequenceNumber() {
        // Arrange
        PartitionedSequenceKey<String> key1 = new PartitionedSequenceKey<>(100L, "a");
        PartitionedSequenceKey<String> key2 = new PartitionedSequenceKey<>(200L, "b");
        PartitionedSequenceKey<String> key3 = new PartitionedSequenceKey<>(100L, "c");

        // Assert
        assertTrue(key1.compareTo(key2) < 0, "key1 should be less than key2");
        assertTrue(key2.compareTo(key1) > 0, "key2 should be greater than key1");
        assertEquals(0, key1.compareTo(key3), "keys with same sequence should be equal in comparison");
    }

    @Test
    @DisplayName("should sort keys by sequence number")
    void shouldSortKeysBySequenceNumber() {
        // Arrange
        List<PartitionedSequenceKey<String>> keys = new ArrayList<>();
        keys.add(new PartitionedSequenceKey<>(300L, "c"));
        keys.add(new PartitionedSequenceKey<>(100L, "a"));
        keys.add(new PartitionedSequenceKey<>(200L, "b"));

        // Act
        Collections.sort(keys);

        // Assert
        assertEquals(100L, keys.get(0).getSequence());
        assertEquals(200L, keys.get(1).getSequence());
        assertEquals(300L, keys.get(2).getSequence());
    }

    @Test
    @DisplayName("should implement equals correctly")
    void shouldImplementEqualsCorrectly() {
        // Arrange
        PartitionedSequenceKey<String> key1 = new PartitionedSequenceKey<>(100L, "customer-1");
        PartitionedSequenceKey<String> key2 = new PartitionedSequenceKey<>(100L, "customer-1");
        PartitionedSequenceKey<String> key3 = new PartitionedSequenceKey<>(100L, "customer-2");
        PartitionedSequenceKey<String> key4 = new PartitionedSequenceKey<>(200L, "customer-1");

        // Assert
        assertEquals(key1, key2, "Same sequence and partition key should be equal");
        assertNotEquals(key1, key3, "Different partition key should not be equal");
        assertNotEquals(key1, key4, "Different sequence should not be equal");
    }

    @Test
    @DisplayName("should implement hashCode consistently with equals")
    void shouldImplementHashCodeConsistently() {
        // Arrange
        PartitionedSequenceKey<String> key1 = new PartitionedSequenceKey<>(100L, "customer-1");
        PartitionedSequenceKey<String> key2 = new PartitionedSequenceKey<>(100L, "customer-1");

        // Assert
        assertEquals(key1.hashCode(), key2.hashCode(), "Equal keys should have same hashCode");
    }

    @Test
    @DisplayName("should have descriptive toString")
    void shouldHaveDescriptiveToString() {
        // Arrange
        PartitionedSequenceKey<String> key = new PartitionedSequenceKey<>(12345L, "test-key");

        // Act
        String toString = key.toString();

        // Assert
        assertTrue(toString.contains("12345"), "toString should contain sequence");
        assertTrue(toString.contains("test-key"), "toString should contain partition key");
    }

    @Test
    @DisplayName("should work with different partition key types")
    void shouldWorkWithDifferentPartitionKeyTypes() {
        // Arrange & Act
        PartitionedSequenceKey<Long> longKey = new PartitionedSequenceKey<>(100L, 999L);
        PartitionedSequenceKey<Integer> intKey = new PartitionedSequenceKey<>(100L, 42);

        // Assert
        assertEquals(999L, longKey.getPartitionKey());
        assertEquals(42, intKey.getPartitionKey());
    }

    @Test
    @DisplayName("should handle negative sequence numbers")
    void shouldHandleNegativeSequenceNumbers() {
        // Act
        PartitionedSequenceKey<String> key = new PartitionedSequenceKey<>(-100L, "test");

        // Assert
        assertEquals(-100L, key.getSequence());
    }

    @Test
    @DisplayName("should handle zero sequence number")
    void shouldHandleZeroSequenceNumber() {
        // Act
        PartitionedSequenceKey<String> key = new PartitionedSequenceKey<>(0L, "test");

        // Assert
        assertEquals(0L, key.getSequence());
    }

    @Test
    @DisplayName("should handle max long sequence number")
    void shouldHandleMaxLongSequenceNumber() {
        // Act
        PartitionedSequenceKey<String> key = new PartitionedSequenceKey<>(Long.MAX_VALUE, "test");

        // Assert
        assertEquals(Long.MAX_VALUE, key.getSequence());
    }
}
