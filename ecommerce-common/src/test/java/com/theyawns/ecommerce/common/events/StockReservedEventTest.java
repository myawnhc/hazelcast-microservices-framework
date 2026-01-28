package com.theyawns.ecommerce.common.events;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.theyawns.ecommerce.common.domain.Product;
import com.theyawns.framework.saga.SagaCompensationConfig;
import com.theyawns.framework.saga.SagaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StockReservedEvent.
 */
@DisplayName("StockReservedEvent - Stock reservation event")
class StockReservedEventTest {

    private StockReservedEvent event;
    private GenericRecord productState;

    @BeforeEach
    void setUp() {
        event = new StockReservedEvent("prod-123", 5, "order-456");

        // Create initial product state
        ProductCreatedEvent createEvent = new ProductCreatedEvent(
                "prod-123", "SKU-001", "Test Product",
                new BigDecimal("29.99"), 100);
        productState = createEvent.apply(null);
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should set event type")
        void shouldSetEventType() {
            assertEquals(StockReservedEvent.EVENT_TYPE, event.getEventType());
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
    }

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("should serialize to GenericRecord")
        void shouldSerializeToGenericRecord() {
            GenericRecord record = event.toGenericRecord();

            assertNotNull(record);
            assertEquals(event.getKey(), record.getString("key"));
            assertEquals(event.getQuantity(), record.getInt32("quantity"));
            assertEquals(event.getOrderId(), record.getString("orderId"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            GenericRecord record = event.toGenericRecord();
            StockReservedEvent deserialized = StockReservedEvent.fromGenericRecord(record);

            assertNotNull(deserialized);
            assertEquals(event.getKey(), deserialized.getKey());
            assertEquals(event.getQuantity(), deserialized.getQuantity());
            assertEquals(event.getOrderId(), deserialized.getOrderId());
        }
    }

    @Nested
    @DisplayName("Apply")
    class Apply {

        @Test
        @DisplayName("should increase reserved quantity")
        void shouldIncreaseReservedQuantity() {
            GenericRecord newState = event.apply(productState);

            assertEquals(5, newState.getInt32("quantityReserved"));
            assertEquals(100, newState.getInt32("quantityOnHand")); // unchanged
        }

        @Test
        @DisplayName("should accumulate multiple reservations")
        void shouldAccumulateMultipleReservations() {
            GenericRecord state1 = event.apply(productState);
            StockReservedEvent event2 = new StockReservedEvent("prod-123", 3, "order-789");
            GenericRecord state2 = event2.apply(state1);

            assertEquals(8, state2.getInt32("quantityReserved"));
        }

        @Test
        @DisplayName("should throw when product does not exist")
        void shouldThrowWhenProductDoesNotExist() {
            assertThrows(IllegalStateException.class, () -> event.apply(null));
        }

        @Test
        @DisplayName("should preserve other product fields")
        void shouldPreserveOtherProductFields() {
            GenericRecord newState = event.apply(productState);

            assertEquals("SKU-001", newState.getString("sku"));
            assertEquals("Test Product", newState.getString("name"));
            assertEquals(Product.Status.ACTIVE.name(), newState.getString("status"));
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
        @DisplayName("should return step 1 for saga step number")
        void shouldReturnStep1ForSagaStepNumber() {
            assertEquals(SagaCompensationConfig.STEP_STOCK_RESERVED, event.getSagaStepNumber());
            assertEquals(1, event.getSagaStepNumber());
        }

        @Test
        @DisplayName("should return StockReleased as compensating event type")
        void shouldReturnStockReleasedAsCompensatingEventType() {
            assertEquals(SagaCompensationConfig.STOCK_RELEASED, event.getCompensatingEventType());
            assertEquals("StockReleased", event.getCompensatingEventType());
        }

        @Test
        @DisplayName("should not be a compensating event")
        void shouldNotBeACompensatingEvent() {
            assertFalse(event.isCompensatingEvent());
        }

        @Test
        @DisplayName("should return OrderFulfillment saga type")
        void shouldReturnOrderFulfillmentSagaType() {
            assertEquals(SagaCompensationConfig.ORDER_FULFILLMENT_SAGA, event.getSagaTypeName());
            assertEquals("OrderFulfillment", event.getSagaTypeName());
        }
    }
}
