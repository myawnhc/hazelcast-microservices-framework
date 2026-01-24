package com.theyawns.framework.domain;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DomainObject - Interface for event-sourced domain entities")
class DomainObjectTest {

    @Test
    @DisplayName("should return unique key identifier")
    void shouldReturnKey() {
        // Arrange
        TestDomainObject obj = new TestDomainObject("key-123", "Test Name");

        // Act & Assert
        assertEquals("key-123", obj.getKey());
    }

    @Test
    @DisplayName("should serialize to GenericRecord for Hazelcast storage")
    void shouldSerializeToGenericRecord() {
        // Arrange
        TestDomainObject obj = new TestDomainObject("key-456", "Another Name");

        // Act
        GenericRecord record = obj.toGenericRecord();

        // Assert
        assertNotNull(record);
        assertEquals("key-456", record.getString("id"));
        assertEquals("Another Name", record.getString("name"));
    }

    @Test
    @DisplayName("should return schema name for GenericRecord serialization")
    void shouldReturnSchemaName() {
        // Arrange
        TestDomainObject obj = new TestDomainObject("key-789", "Name");

        // Act & Assert
        assertEquals("TestDomainObject", obj.getSchemaName());
    }

    @Test
    @DisplayName("should implement Serializable interface")
    void shouldBeSerializable() {
        // Arrange
        TestDomainObject obj = new TestDomainObject("key-abc", "Serializable Test");

        // Assert
        assertTrue(obj instanceof java.io.Serializable);
    }

    /**
     * Test implementation of DomainObject.
     */
    static class TestDomainObject implements DomainObject<String> {
        private final String id;
        private final String name;

        TestDomainObject(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String getKey() {
            return id;
        }

        @Override
        public GenericRecord toGenericRecord() {
            return GenericRecordBuilder.compact(getSchemaName())
                    .setString("id", id)
                    .setString("name", name)
                    .build();
        }

        @Override
        public String getSchemaName() {
            return "TestDomainObject";
        }

        public String getName() {
            return name;
        }
    }
}
