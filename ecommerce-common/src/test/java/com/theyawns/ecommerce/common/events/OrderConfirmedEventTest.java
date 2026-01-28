package com.theyawns.ecommerce.common.events;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.ecommerce.common.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OrderConfirmedEvent.
 */
@DisplayName("OrderConfirmedEvent - Order confirmation event")
class OrderConfirmedEventTest {

    private OrderConfirmedEvent event;

    @BeforeEach
    void setUp() {
        event = new OrderConfirmedEvent("order-123", "CONF-456");
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should set event type")
        void shouldSetEventType() {
            assertEquals(OrderConfirmedEvent.EVENT_TYPE, event.getEventType());
        }

        @Test
        @DisplayName("should generate event ID")
        void shouldGenerateEventId() {
            assertNotNull(event.getEventId());
            assertFalse(event.getEventId().isEmpty());
        }

        @Test
        @DisplayName("should set order ID as key")
        void shouldSetOrderIdAsKey() {
            assertEquals("order-123", event.getKey());
        }

        @Test
        @DisplayName("should store confirmation number")
        void shouldStoreConfirmationNumber() {
            assertEquals("CONF-456", event.getConfirmationNumber());
        }

        @Test
        @DisplayName("should set timestamp")
        void shouldSetTimestamp() {
            assertNotNull(event.getTimestamp());
        }

        @Test
        @DisplayName("default constructor should set event type")
        void defaultConstructorShouldSetEventType() {
            OrderConfirmedEvent defaultEvent = new OrderConfirmedEvent();
            assertEquals(OrderConfirmedEvent.EVENT_TYPE, defaultEvent.getEventType());
        }
    }

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("should serialize to GenericRecord")
        void shouldSerializeToGenericRecord() {
            GenericRecord record = event.toGenericRecord();

            assertNotNull(record);
            assertEquals(event.getEventId(), record.getString("eventId"));
            assertEquals(event.getEventType(), record.getString("eventType"));
            assertEquals(event.getKey(), record.getString("key"));
            assertEquals(event.getConfirmationNumber(), record.getString("confirmationNumber"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            GenericRecord record = event.toGenericRecord();
            OrderConfirmedEvent deserialized = OrderConfirmedEvent.fromGenericRecord(record);

            assertNotNull(deserialized);
            assertEquals(event.getEventId(), deserialized.getEventId());
            assertEquals(event.getEventType(), deserialized.getEventType());
            assertEquals(event.getKey(), deserialized.getKey());
            assertEquals(event.getConfirmationNumber(), deserialized.getConfirmationNumber());
        }

        @Test
        @DisplayName("should return null when deserializing null record")
        void shouldReturnNullWhenDeserializingNullRecord() {
            assertNull(OrderConfirmedEvent.fromGenericRecord(null));
        }

        @Test
        @DisplayName("should handle zero timestamp in deserialization")
        void shouldHandleZeroTimestamp() {
            GenericRecord record = GenericRecordBuilder.compact(OrderConfirmedEvent.SCHEMA_NAME)
                    .setString("eventId", "evt-1")
                    .setString("eventType", "OrderConfirmed")
                    .setString("eventVersion", "1.0")
                    .setString("source", null)
                    .setInt64("timestamp", 0L)
                    .setString("key", "order-1")
                    .setString("correlationId", null)
                    .setString("sagaId", null)
                    .setString("sagaType", null)
                    .setInt32("stepNumber", 0)
                    .setBoolean("isCompensating", false)
                    .setString("confirmationNumber", "CONF-1")
                    .build();

            OrderConfirmedEvent deserialized = OrderConfirmedEvent.fromGenericRecord(record);
            assertNull(deserialized.getTimestamp());
        }
    }

    @Nested
    @DisplayName("Apply")
    class Apply {

        private GenericRecord existingOrderState;

        @BeforeEach
        void setUpExistingState() {
            existingOrderState = GenericRecordBuilder.compact(Order.SCHEMA_NAME)
                    .setString("orderId", "order-123")
                    .setString("customerId", "cust-456")
                    .setString("customerName", "John Doe")
                    .setString("customerEmail", "john@example.com")
                    .setArrayOfGenericRecord("lineItems", new GenericRecord[0])
                    .setString("subtotal", "100.00")
                    .setString("tax", "10.00")
                    .setString("total", "110.00")
                    .setString("shippingAddress", "123 Main St")
                    .setString("status", Order.Status.PENDING.name())
                    .setInt64("createdAt", Instant.now().toEpochMilli())
                    .setInt64("updatedAt", Instant.now().toEpochMilli())
                    .build();
        }

        @Test
        @DisplayName("should change status to CONFIRMED")
        void shouldChangeStatusToConfirmed() {
            GenericRecord confirmed = event.apply(existingOrderState);

            assertEquals(Order.Status.CONFIRMED.name(), confirmed.getString("status"));
        }

        @Test
        @DisplayName("should preserve order details")
        void shouldPreserveOrderDetails() {
            GenericRecord confirmed = event.apply(existingOrderState);

            assertEquals("order-123", confirmed.getString("orderId"));
            assertEquals("cust-456", confirmed.getString("customerId"));
            assertEquals("John Doe", confirmed.getString("customerName"));
            assertEquals("100.00", confirmed.getString("subtotal"));
        }

        @Test
        @DisplayName("should update timestamp")
        void shouldUpdateTimestamp() {
            long beforeApply = Instant.now().toEpochMilli();
            GenericRecord confirmed = event.apply(existingOrderState);
            long afterApply = Instant.now().toEpochMilli();

            long updatedAt = confirmed.getInt64("updatedAt");
            assertTrue(updatedAt >= beforeApply && updatedAt <= afterApply);
        }

        @Test
        @DisplayName("should throw when applied to null state")
        void shouldThrowWhenAppliedToNullState() {
            assertThrows(IllegalStateException.class, () -> event.apply(null));
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("should update confirmation number via setter")
        void shouldUpdateConfirmationNumber() {
            event.setConfirmationNumber("NEW-CONF-789");
            assertEquals("NEW-CONF-789", event.getConfirmationNumber());
        }
    }

    @Nested
    @DisplayName("Schema")
    class Schema {

        @Test
        @DisplayName("should return correct schema name")
        void shouldReturnCorrectSchemaName() {
            assertEquals(OrderConfirmedEvent.SCHEMA_NAME, event.getSchemaName());
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToString {

        @Test
        @DisplayName("should include relevant fields")
        void shouldIncludeRelevantFields() {
            String str = event.toString();
            assertTrue(str.contains("order-123"));
            assertTrue(str.contains("CONF-456"));
        }
    }
}
