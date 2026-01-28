package com.theyawns.ecommerce.common.events;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.theyawns.ecommerce.common.domain.Order;
import com.theyawns.ecommerce.common.domain.OrderLineItem;
import com.theyawns.framework.saga.SagaCompensationConfig;
import com.theyawns.framework.saga.SagaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OrderCreatedEvent.
 */
@DisplayName("OrderCreatedEvent - Order creation event")
class OrderCreatedEventTest {

    private OrderCreatedEvent event;
    private List<OrderLineItem> lineItems;

    @BeforeEach
    void setUp() {
        lineItems = Arrays.asList(
                new OrderLineItem("prod-1", "Widget", "SKU-001", 2, new BigDecimal("10.00")),
                new OrderLineItem("prod-2", "Gadget", "SKU-002", 1, new BigDecimal("25.00"))
        );
        event = new OrderCreatedEvent(
                "order-123",
                "cust-456",
                lineItems,
                "123 Main St"
        );
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should set event type")
        void shouldSetEventType() {
            assertEquals(OrderCreatedEvent.EVENT_TYPE, event.getEventType());
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
        @DisplayName("should store customer ID")
        void shouldStoreCustomerId() {
            assertEquals("cust-456", event.getCustomerId());
        }

        @Test
        @DisplayName("should store line items")
        void shouldStoreLineItems() {
            assertEquals(2, event.getLineItems().size());
        }

        @Test
        @DisplayName("should store shipping address")
        void shouldStoreShippingAddress() {
            assertEquals("123 Main St", event.getShippingAddress());
        }

        @Test
        @DisplayName("should calculate subtotal")
        void shouldCalculateSubtotal() {
            // 2 * 10.00 + 1 * 25.00 = 45.00
            assertEquals(new BigDecimal("45.00"), event.getSubtotal());
        }

        @Test
        @DisplayName("should calculate tax at 10%")
        void shouldCalculateTax() {
            // 45.00 * 0.10 = 4.50
            assertEquals(0, new BigDecimal("4.50").compareTo(event.getTax()));
        }

        @Test
        @DisplayName("should calculate total")
        void shouldCalculateTotal() {
            // 45.00 + 4.50 = 49.50
            assertEquals(0, new BigDecimal("49.50").compareTo(event.getTotal()));
        }

        @Test
        @DisplayName("should handle null line items")
        void shouldHandleNullLineItems() {
            OrderCreatedEvent emptyEvent = new OrderCreatedEvent("order-1", "cust-1", null, "addr");
            assertNotNull(emptyEvent.getLineItems());
            assertTrue(emptyEvent.getLineItems().isEmpty());
        }

        @Test
        @DisplayName("should handle empty line items")
        void shouldHandleEmptyLineItems() {
            OrderCreatedEvent emptyEvent = new OrderCreatedEvent("order-1", "cust-1", Collections.emptyList(), "addr");
            assertEquals(BigDecimal.ZERO, emptyEvent.getSubtotal());
        }

        @Test
        @DisplayName("should set timestamp")
        void shouldSetTimestamp() {
            assertNotNull(event.getTimestamp());
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
            assertEquals(event.getCustomerId(), record.getString("customerId"));
            assertEquals(event.getShippingAddress(), record.getString("shippingAddress"));
            assertNotNull(record.getArrayOfGenericRecord("lineItems"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            GenericRecord record = event.toGenericRecord();
            OrderCreatedEvent deserialized = OrderCreatedEvent.fromGenericRecord(record);

            assertNotNull(deserialized);
            assertEquals(event.getEventId(), deserialized.getEventId());
            assertEquals(event.getEventType(), deserialized.getEventType());
            assertEquals(event.getKey(), deserialized.getKey());
            assertEquals(event.getCustomerId(), deserialized.getCustomerId());
            assertEquals(event.getShippingAddress(), deserialized.getShippingAddress());
            assertEquals(event.getLineItems().size(), deserialized.getLineItems().size());
        }

        @Test
        @DisplayName("should return null when deserializing null record")
        void shouldReturnNullWhenDeserializingNullRecord() {
            assertNull(OrderCreatedEvent.fromGenericRecord(null));
        }

        @Test
        @DisplayName("should preserve monetary values through serialization")
        void shouldPreserveMonetaryValues() {
            GenericRecord record = event.toGenericRecord();
            OrderCreatedEvent deserialized = OrderCreatedEvent.fromGenericRecord(record);

            assertEquals(event.getSubtotal(), deserialized.getSubtotal());
            assertEquals(event.getTax(), deserialized.getTax());
            assertEquals(event.getTotal(), deserialized.getTotal());
        }
    }

    @Nested
    @DisplayName("Apply")
    class Apply {

        @Test
        @DisplayName("should create new order record")
        void shouldCreateNewOrderRecord() {
            GenericRecord orderRecord = event.apply(null);

            assertNotNull(orderRecord);
            assertEquals("order-123", orderRecord.getString("orderId"));
            assertEquals("cust-456", orderRecord.getString("customerId"));
            assertEquals(Order.Status.PENDING.name(), orderRecord.getString("status"));
        }

        @Test
        @DisplayName("should set shipping address")
        void shouldSetShippingAddress() {
            GenericRecord orderRecord = event.apply(null);
            assertEquals("123 Main St", orderRecord.getString("shippingAddress"));
        }

        @Test
        @DisplayName("should include line items")
        void shouldIncludeLineItems() {
            GenericRecord orderRecord = event.apply(null);
            GenericRecord[] items = orderRecord.getArrayOfGenericRecord("lineItems");
            assertNotNull(items);
            assertEquals(2, items.length);
        }

        @Test
        @DisplayName("should set timestamps")
        void shouldSetTimestamps() {
            GenericRecord orderRecord = event.apply(null);
            assertTrue(orderRecord.getInt64("createdAt") > 0);
            assertTrue(orderRecord.getInt64("updatedAt") > 0);
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("should update customer info via setters")
        void shouldUpdateCustomerInfo() {
            event.setCustomerName("John Doe");
            event.setCustomerEmail("john@example.com");

            assertEquals("John Doe", event.getCustomerName());
            assertEquals("john@example.com", event.getCustomerEmail());
        }

        @Test
        @DisplayName("should recalculate totals when line items change")
        void shouldRecalculateTotals() {
            List<OrderLineItem> newItems = Collections.singletonList(
                    new OrderLineItem("prod-3", "Item", "SKU-003", 5, new BigDecimal("20.00"))
            );
            event.setLineItems(newItems);

            assertEquals(new BigDecimal("100.00"), event.getSubtotal());
        }

        @Test
        @DisplayName("should handle null when setting line items")
        void shouldHandleNullWhenSettingLineItems() {
            event.setLineItems(null);
            assertNotNull(event.getLineItems());
            assertTrue(event.getLineItems().isEmpty());
        }
    }

    @Nested
    @DisplayName("Schema")
    class Schema {

        @Test
        @DisplayName("should return correct schema name")
        void shouldReturnCorrectSchemaName() {
            assertEquals(OrderCreatedEvent.SCHEMA_NAME, event.getSchemaName());
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
            assertTrue(str.contains("cust-456"));
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
        @DisplayName("should return step 0 for saga step number")
        void shouldReturnStep0ForSagaStepNumber() {
            assertEquals(SagaCompensationConfig.STEP_ORDER_CREATED, event.getSagaStepNumber());
            assertEquals(0, event.getSagaStepNumber());
        }

        @Test
        @DisplayName("should return OrderCancelled as compensating event type")
        void shouldReturnOrderCancelledAsCompensatingEventType() {
            assertEquals(SagaCompensationConfig.ORDER_CANCELLED, event.getCompensatingEventType());
            assertEquals("OrderCancelled", event.getCompensatingEventType());
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
