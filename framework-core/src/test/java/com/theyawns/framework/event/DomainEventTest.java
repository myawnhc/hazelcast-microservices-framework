package com.theyawns.framework.event;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.framework.domain.DomainObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DomainEvent - Base event class for event sourcing")
class DomainEventTest {

    private TestEvent event;

    @BeforeEach
    void setUp() {
        event = new TestEvent("test-key", "test-data");
    }

    @Test
    @DisplayName("should generate unique event ID on creation")
    void shouldGenerateEventId() {
        assertNotNull(event.getEventId());
        assertFalse(event.getEventId().isEmpty());
        // Verify it's a valid UUID format
        assertDoesNotThrow(() -> UUID.fromString(event.getEventId()));
    }

    @Test
    @DisplayName("should set default version to 1.0")
    void shouldSetDefaultVersion() {
        assertEquals("1.0", event.getEventVersion());
    }

    @Test
    @DisplayName("should set timestamp on creation")
    void shouldSetTimestampOnCreation() {
        assertNotNull(event.getTimestamp());
        // Should be close to now
        assertTrue(event.getTimestamp().isBefore(Instant.now().plusSeconds(1)));
        assertTrue(event.getTimestamp().isAfter(Instant.now().minusSeconds(1)));
    }

    @Test
    @DisplayName("should set key from constructor")
    void shouldSetKey() {
        assertEquals("test-key", event.getKey());
    }

    @Test
    @DisplayName("should set correlation ID for tracing")
    void shouldSetCorrelationId() {
        String correlationId = UUID.randomUUID().toString();
        event.setCorrelationId(correlationId);
        assertEquals(correlationId, event.getCorrelationId());
    }

    @Test
    @DisplayName("should set saga metadata for distributed transactions")
    void shouldSetSagaMetadata() {
        event.setSagaId("saga-123");
        event.setSagaType("OrderFulfillment");
        event.setStepNumber(2);
        event.setIsCompensating(true);

        assertEquals("saga-123", event.getSagaId());
        assertEquals("OrderFulfillment", event.getSagaType());
        assertEquals(2, event.getStepNumber());
        assertTrue(event.getIsCompensating());
    }

    @Test
    @DisplayName("should default to non-compensating event")
    void shouldDefaultToNonCompensating() {
        assertFalse(event.getIsCompensating());
    }

    @Test
    @DisplayName("should set performance tracking timestamps")
    void shouldSetPerformanceTracking() {
        Instant submittedAt = Instant.now();
        Instant pipelineEntry = Instant.now().plusMillis(10);

        event.setSubmittedAt(submittedAt);
        event.setPipelineEntryTime(pipelineEntry);

        assertEquals(submittedAt, event.getSubmittedAt());
        assertEquals(pipelineEntry, event.getPipelineEntryTime());
    }

    @Test
    @DisplayName("should serialize to GenericRecord for Hazelcast storage")
    void shouldSerializeToGenericRecord() {
        GenericRecord gr = event.toGenericRecord();

        assertNotNull(gr);
        assertEquals(event.getEventId(), gr.getString("eventId"));
        assertEquals("TestEvent", gr.getString("eventType"));
        assertEquals(event.getKey(), gr.getString("key"));
        assertEquals("test-data", gr.getString("data"));
    }

    @Test
    @DisplayName("should apply event to domain object GenericRecord")
    void shouldApplyToGenericRecord() {
        // Arrange
        GenericRecord domainObject = GenericRecordBuilder.compact("TestDomainObject")
                .setString("key", "test-key")
                .setString("value", "initial-value")
                .build();

        // Act
        GenericRecord result = event.apply(domainObject);

        // Assert
        assertNotNull(result);
        assertEquals("test-key", result.getString("key"));
        assertEquals("test-data", result.getString("value"));
    }

    @Test
    @DisplayName("should have descriptive toString representation")
    void shouldHaveToString() {
        String toString = event.toString();
        assertNotNull(toString);
        assertTrue(toString.contains("TestEvent"));
        assertTrue(toString.contains("test-key"));
    }

    /**
     * Concrete test implementation of DomainEvent for testing.
     */
    static class TestEvent extends DomainEvent<TestDomainObject, String> {
        private final String data;

        TestEvent(String key, String data) {
            super(key);
            this.data = data;
            this.eventType = "TestEvent";
        }

        public String getData() {
            return data;
        }

        @Override
        public GenericRecord toGenericRecord() {
            return GenericRecordBuilder.compact(getSchemaName())
                    .setString("eventId", eventId)
                    .setString("eventType", eventType)
                    .setString("eventVersion", eventVersion)
                    .setString("key", key)
                    .setString("data", data)
                    .build();
        }

        @Override
        public GenericRecord apply(GenericRecord domainObjectRecord) {
            return GenericRecordBuilder.compact("TestDomainObject")
                    .setString("key", key)
                    .setString("value", data)
                    .build();
        }

        @Override
        public String getSchemaName() {
            return "TestEvent";
        }
    }

    /**
     * Simple test domain object implementation.
     */
    static class TestDomainObject implements DomainObject<String> {
        private final String key;
        private final String value;

        TestDomainObject(String key, String value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public GenericRecord toGenericRecord() {
            return GenericRecordBuilder.compact(getSchemaName())
                    .setString("key", key)
                    .setString("value", value)
                    .build();
        }

        @Override
        public String getSchemaName() {
            return "TestDomainObject";
        }
    }
}
