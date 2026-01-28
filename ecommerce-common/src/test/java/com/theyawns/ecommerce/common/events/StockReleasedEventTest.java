package com.theyawns.ecommerce.common.events;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.ecommerce.common.domain.Product;
import com.theyawns.framework.saga.SagaCompensationConfig;
import com.theyawns.framework.saga.SagaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StockReleasedEvent.
 */
@DisplayName("StockReleasedEvent - Stock release event")
class StockReleasedEventTest {

    private StockReleasedEvent event;

    @BeforeEach
    void setUp() {
        event = new StockReleasedEvent("prod-123", 5, "order-456", "ORDER_CANCELLED");
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should set event type")
        void shouldSetEventType() {
            assertEquals(StockReleasedEvent.EVENT_TYPE, event.getEventType());
        }

        @Test
        @DisplayName("should generate event ID")
        void shouldGenerateEventId() {
            assertNotNull(event.getEventId());
            assertFalse(event.getEventId().isEmpty());
        }

        @Test
        @DisplayName("should set product ID as key")
        void shouldSetProductIdAsKey() {
            assertEquals("prod-123", event.getKey());
        }

        @Test
        @DisplayName("should store quantity")
        void shouldStoreQuantity() {
            assertEquals(5, event.getQuantity());
        }

        @Test
        @DisplayName("should store order ID")
        void shouldStoreOrderId() {
            assertEquals("order-456", event.getOrderId());
        }

        @Test
        @DisplayName("should store reason")
        void shouldStoreReason() {
            assertEquals("ORDER_CANCELLED", event.getReason());
        }

        @Test
        @DisplayName("should set timestamp")
        void shouldSetTimestamp() {
            assertNotNull(event.getTimestamp());
        }

        @Test
        @DisplayName("default constructor should set event type")
        void defaultConstructorShouldSetEventType() {
            StockReleasedEvent defaultEvent = new StockReleasedEvent();
            assertEquals(StockReleasedEvent.EVENT_TYPE, defaultEvent.getEventType());
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
            assertEquals(5, record.getInt32("quantity"));
            assertEquals("order-456", record.getString("orderId"));
            assertEquals("ORDER_CANCELLED", record.getString("reason"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            GenericRecord record = event.toGenericRecord();
            StockReleasedEvent deserialized = StockReleasedEvent.fromGenericRecord(record);

            assertNotNull(deserialized);
            assertEquals(event.getEventId(), deserialized.getEventId());
            assertEquals(event.getEventType(), deserialized.getEventType());
            assertEquals(event.getKey(), deserialized.getKey());
            assertEquals(event.getQuantity(), deserialized.getQuantity());
            assertEquals(event.getOrderId(), deserialized.getOrderId());
            assertEquals(event.getReason(), deserialized.getReason());
        }

        @Test
        @DisplayName("should return null when deserializing null record")
        void shouldReturnNullWhenDeserializingNullRecord() {
            assertNull(StockReleasedEvent.fromGenericRecord(null));
        }

        @Test
        @DisplayName("should preserve saga fields through serialization")
        void shouldPreserveSagaFields() {
            event.setSagaId("saga-123");
            event.setSagaType("OrderSaga");
            event.setStepNumber(2);
            event.setIsCompensating(true);

            GenericRecord record = event.toGenericRecord();
            StockReleasedEvent deserialized = StockReleasedEvent.fromGenericRecord(record);

            assertEquals("saga-123", deserialized.getSagaId());
            assertEquals("OrderSaga", deserialized.getSagaType());
            assertEquals(2, deserialized.getStepNumber());
            assertTrue(deserialized.getIsCompensating());
        }
    }

    @Nested
    @DisplayName("Apply")
    class Apply {

        private GenericRecord existingProductState;

        @BeforeEach
        void setUpExistingState() {
            existingProductState = GenericRecordBuilder.compact(Product.SCHEMA_NAME)
                    .setString("productId", "prod-123")
                    .setString("sku", "SKU-123")
                    .setString("name", "Widget")
                    .setString("description", "A widget")
                    .setString("price", "25.00")
                    .setInt32("quantityOnHand", 100)
                    .setInt32("quantityReserved", 10)
                    .setString("category", "Electronics")
                    .setString("status", "ACTIVE")
                    .setInt64("createdAt", Instant.now().toEpochMilli())
                    .setInt64("updatedAt", Instant.now().toEpochMilli())
                    .build();
        }

        @Test
        @DisplayName("should decrease quantity reserved")
        void shouldDecreaseQuantityReserved() {
            GenericRecord released = event.apply(existingProductState);

            // 10 reserved - 5 released = 5 remaining reserved
            assertEquals(5, released.getInt32("quantityReserved"));
        }

        @Test
        @DisplayName("should not allow negative reserved quantity")
        void shouldNotAllowNegativeReservedQuantity() {
            // Try to release more than reserved
            StockReleasedEvent largeRelease = new StockReleasedEvent("prod-123", 20, "order-1", "TEST");
            GenericRecord released = largeRelease.apply(existingProductState);

            // Should be capped at 0
            assertEquals(0, released.getInt32("quantityReserved"));
        }

        @Test
        @DisplayName("should preserve product details")
        void shouldPreserveProductDetails() {
            GenericRecord released = event.apply(existingProductState);

            assertEquals("prod-123", released.getString("productId"));
            assertEquals("SKU-123", released.getString("sku"));
            assertEquals("Widget", released.getString("name"));
            assertEquals(100, released.getInt32("quantityOnHand"));
        }

        @Test
        @DisplayName("should update timestamp")
        void shouldUpdateTimestamp() {
            long beforeApply = Instant.now().toEpochMilli();
            GenericRecord released = event.apply(existingProductState);
            long afterApply = Instant.now().toEpochMilli();

            long updatedAt = released.getInt64("updatedAt");
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
        @DisplayName("should update quantity via setter")
        void shouldUpdateQuantity() {
            event.setQuantity(10);
            assertEquals(10, event.getQuantity());
        }

        @Test
        @DisplayName("should update order ID via setter")
        void shouldUpdateOrderId() {
            event.setOrderId("new-order");
            assertEquals("new-order", event.getOrderId());
        }

        @Test
        @DisplayName("should update reason via setter")
        void shouldUpdateReason() {
            event.setReason("TIMEOUT");
            assertEquals("TIMEOUT", event.getReason());
        }
    }

    @Nested
    @DisplayName("Schema")
    class Schema {

        @Test
        @DisplayName("should return correct schema name")
        void shouldReturnCorrectSchemaName() {
            assertEquals(StockReleasedEvent.SCHEMA_NAME, event.getSchemaName());
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToString {

        @Test
        @DisplayName("should include relevant fields")
        void shouldIncludeRelevantFields() {
            String str = event.toString();
            assertTrue(str.contains("prod-123"));
            assertTrue(str.contains("5"));
            assertTrue(str.contains("order-456"));
            assertTrue(str.contains("ORDER_CANCELLED"));
        }
    }

    @Nested
    @DisplayName("SagaEvent interface")
    class SagaEventInterface {

        @Test
        @DisplayName("should implement SagaEvent")
        void shouldImplementSagaEvent() {
            assertTrue(event instanceof SagaEvent);
        }

        @Test
        @DisplayName("should return step 1 for saga step number (compensates StockReserved)")
        void shouldReturnStep1ForSagaStepNumber() {
            assertEquals(SagaCompensationConfig.STEP_STOCK_RESERVED, event.getSagaStepNumber());
            assertEquals(1, event.getSagaStepNumber());
        }

        @Test
        @DisplayName("should return null as compensating event type (compensation events have no compensation)")
        void shouldReturnNullAsCompensatingEventType() {
            assertNull(event.getCompensatingEventType());
        }

        @Test
        @DisplayName("should be a compensating event")
        void shouldBeACompensatingEvent() {
            assertTrue(event.isCompensatingEvent());
        }

        @Test
        @DisplayName("should return OrderFulfillment saga type")
        void shouldReturnOrderFulfillmentSagaType() {
            assertEquals(SagaCompensationConfig.ORDER_FULFILLMENT_SAGA, event.getSagaTypeName());
            assertEquals("OrderFulfillment", event.getSagaTypeName());
        }
    }
}
