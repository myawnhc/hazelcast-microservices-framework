package com.theyawns.framework.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EventMetadata - Tracing and saga metadata for events")
class EventMetadataTest {

    @Test
    @DisplayName("should require correlation ID")
    void shouldRequireCorrelationId() {
        assertThrows(NullPointerException.class, () ->
                EventMetadata.builder(null).build()
        );
    }

    @Test
    @DisplayName("should create simple metadata with only correlation ID")
    void shouldCreateSimpleMetadata() {
        // Act
        EventMetadata metadata = EventMetadata.simple("corr-123");

        // Assert
        assertEquals("corr-123", metadata.getCorrelationId());
        assertNull(metadata.getSagaId());
        assertNull(metadata.getSagaType());
        assertNull(metadata.getStepNumber());
        assertFalse(metadata.isCompensating());
        assertFalse(metadata.isSagaEvent());
    }

    @Test
    @DisplayName("should build full saga metadata with all fields")
    void shouldBuildFullSagaMetadata() {
        // Act
        EventMetadata metadata = EventMetadata.builder("corr-456")
                .sagaId("saga-789")
                .sagaType("OrderFulfillment")
                .stepNumber(3)
                .compensating(true)
                .build();

        // Assert
        assertEquals("corr-456", metadata.getCorrelationId());
        assertEquals("saga-789", metadata.getSagaId());
        assertEquals("OrderFulfillment", metadata.getSagaType());
        assertEquals(3, metadata.getStepNumber());
        assertTrue(metadata.isCompensating());
        assertTrue(metadata.isSagaEvent());
    }

    @Test
    @DisplayName("should set createdAt automatically when not specified")
    void shouldSetCreatedAtAutomatically() {
        // Arrange
        Instant before = Instant.now();

        // Act
        EventMetadata metadata = EventMetadata.simple("corr-123");
        Instant after = Instant.now();

        // Assert
        assertNotNull(metadata.getCreatedAt());
        assertTrue(metadata.getCreatedAt().compareTo(before) >= 0);
        assertTrue(metadata.getCreatedAt().compareTo(after) <= 0);
    }

    @Test
    @DisplayName("should allow explicit createdAt timestamp")
    void shouldAllowExplicitCreatedAt() {
        // Arrange
        Instant explicit = Instant.parse("2024-01-15T10:30:00Z");

        // Act
        EventMetadata metadata = EventMetadata.builder("corr-123")
                .createdAt(explicit)
                .build();

        // Assert
        assertEquals(explicit, metadata.getCreatedAt());
    }

    @Test
    @DisplayName("should implement equals correctly")
    void shouldImplementEquals() {
        // Arrange
        EventMetadata m1 = EventMetadata.builder("corr-123")
                .sagaId("saga-1")
                .build();
        EventMetadata m2 = EventMetadata.builder("corr-123")
                .sagaId("saga-1")
                .build();
        EventMetadata m3 = EventMetadata.builder("corr-456")
                .sagaId("saga-1")
                .build();

        // Assert
        assertEquals(m1, m2);
        assertNotEquals(m1, m3);
    }

    @Test
    @DisplayName("should implement hashCode consistently with equals")
    void shouldImplementHashCode() {
        // Arrange
        EventMetadata m1 = EventMetadata.builder("corr-123")
                .sagaId("saga-1")
                .build();
        EventMetadata m2 = EventMetadata.builder("corr-123")
                .sagaId("saga-1")
                .build();

        // Assert
        assertEquals(m1.hashCode(), m2.hashCode());
    }

    @Test
    @DisplayName("should have descriptive toString representation")
    void shouldHaveToString() {
        // Arrange
        EventMetadata metadata = EventMetadata.builder("corr-123")
                .sagaId("saga-456")
                .sagaType("OrderFulfillment")
                .build();

        // Act
        String toString = metadata.toString();

        // Assert
        assertTrue(toString.contains("corr-123"));
        assertTrue(toString.contains("saga-456"));
        assertTrue(toString.contains("OrderFulfillment"));
    }
}
