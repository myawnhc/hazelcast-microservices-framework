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
 * Unit tests for OrderCancelledEvent.
 */
@DisplayName("OrderCancelledEvent - Order cancellation event")
class OrderCancelledEventTest {

    private OrderCancelledEvent event;

    @BeforeEach
    void setUp() {
        event = new OrderCancelledEvent("order-123", "Customer requested", "customer");
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should set event type")
        void shouldSetEventType() {
            assertEquals(OrderCancelledEvent.EVENT_TYPE, event.getEventType());
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
        @DisplayName("should store reason")
        void shouldStoreReason() {
            assertEquals("Customer requested", event.getReason());
        }

        @Test
        @DisplayName("should store cancelledBy")
        void shouldStoreCancelledBy() {
            assertEquals("customer", event.getCancelledBy());
        }

        @Test
        @DisplayName("should set timestamp")
        void shouldSetTimestamp() {
            assertNotNull(event.getTimestamp());
        }

        @Test
        @DisplayName("default constructor should set event type")
        void defaultConstructorShouldSetEventType() {
            OrderCancelledEvent defaultEvent = new OrderCancelledEvent();
            assertEquals(OrderCancelledEvent.EVENT_TYPE, defaultEvent.getEventType());
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
            assertEquals(event.getReason(), record.getString("reason"));
            assertEquals(event.getCancelledBy(), record.getString("cancelledBy"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            GenericRecord record = event.toGenericRecord();
            OrderCancelledEvent deserialized = OrderCancelledEvent.fromGenericRecord(record);

            assertNotNull(deserialized);
            assertEquals(event.getEventId(), deserialized.getEventId());
            assertEquals(event.getEventType(), deserialized.getEventType());
            assertEquals(event.getKey(), deserialized.getKey());
            assertEquals(event.getReason(), deserialized.getReason());
            assertEquals(event.getCancelledBy(), deserialized.getCancelledBy());
        }

        @Test
        @DisplayName("should return null when deserializing null record")
        void shouldReturnNullWhenDeserializingNullRecord() {
            assertNull(OrderCancelledEvent.fromGenericRecord(null));
        }

        @Test
        @DisplayName("should preserve saga fields through serialization")
        void shouldPreserveSagaFields() {
            event.setSagaId("saga-123");
            event.setSagaType("OrderSaga");
            event.setStepNumber(3);
            event.setIsCompensating(true);

            GenericRecord record = event.toGenericRecord();
            OrderCancelledEvent deserialized = OrderCancelledEvent.fromGenericRecord(record);

            assertEquals("saga-123", deserialized.getSagaId());
            assertEquals("OrderSaga", deserialized.getSagaType());
            assertEquals(3, deserialized.getStepNumber());
            assertTrue(deserialized.getIsCompensating());
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
        @DisplayName("should change status to CANCELLED")
        void shouldChangeStatusToCancelled() {
            GenericRecord cancelled = event.apply(existingOrderState);

            assertEquals(Order.Status.CANCELLED.name(), cancelled.getString("status"));
        }

        @Test
        @DisplayName("should preserve order details")
        void shouldPreserveOrderDetails() {
            GenericRecord cancelled = event.apply(existingOrderState);

            assertEquals("order-123", cancelled.getString("orderId"));
            assertEquals("cust-456", cancelled.getString("customerId"));
            assertEquals("John Doe", cancelled.getString("customerName"));
            assertEquals("100.00", cancelled.getString("subtotal"));
        }

        @Test
        @DisplayName("should update timestamp")
        void shouldUpdateTimestamp() {
            long beforeApply = Instant.now().toEpochMilli();
            GenericRecord cancelled = event.apply(existingOrderState);
            long afterApply = Instant.now().toEpochMilli();

            long updatedAt = cancelled.getInt64("updatedAt");
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
        @DisplayName("should update reason via setter")
        void shouldUpdateReason() {
            event.setReason("Payment failed");
            assertEquals("Payment failed", event.getReason());
        }

        @Test
        @DisplayName("should update cancelledBy via setter")
        void shouldUpdateCancelledBy() {
            event.setCancelledBy("system");
            assertEquals("system", event.getCancelledBy());
        }
    }

    @Nested
    @DisplayName("Schema")
    class Schema {

        @Test
        @DisplayName("should return correct schema name")
        void shouldReturnCorrectSchemaName() {
            assertEquals(OrderCancelledEvent.SCHEMA_NAME, event.getSchemaName());
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
            assertTrue(str.contains("Customer requested"));
            assertTrue(str.contains("customer"));
        }
    }
}
