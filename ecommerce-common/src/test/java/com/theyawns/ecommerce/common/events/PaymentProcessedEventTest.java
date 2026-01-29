package com.theyawns.ecommerce.common.events;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.theyawns.ecommerce.common.domain.Payment;
import com.theyawns.framework.saga.SagaCompensationConfig;
import com.theyawns.framework.saga.SagaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PaymentProcessedEvent.
 */
@DisplayName("PaymentProcessedEvent - Payment capture event")
class PaymentProcessedEventTest {

    private PaymentProcessedEvent event;

    @BeforeEach
    void setUp() {
        event = new PaymentProcessedEvent(
                "pay-123", "order-456", "cust-789",
                new BigDecimal("99.99"), "USD",
                "txn-ext-001", "CREDIT_CARD");
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should set event type")
        void shouldSetEventType() {
            assertEquals(PaymentProcessedEvent.EVENT_TYPE, event.getEventType());
        }

        @Test
        @DisplayName("should generate event ID")
        void shouldGenerateEventId() {
            assertNotNull(event.getEventId());
            assertFalse(event.getEventId().isEmpty());
        }

        @Test
        @DisplayName("should set payment ID as key")
        void shouldSetPaymentIdAsKey() {
            assertEquals("pay-123", event.getKey());
        }

        @Test
        @DisplayName("should store order ID")
        void shouldStoreOrderId() {
            assertEquals("order-456", event.getOrderId());
        }

        @Test
        @DisplayName("should store customer ID")
        void shouldStoreCustomerId() {
            assertEquals("cust-789", event.getCustomerId());
        }

        @Test
        @DisplayName("should store amount as string")
        void shouldStoreAmount() {
            assertEquals("99.99", event.getAmount());
        }

        @Test
        @DisplayName("should store currency")
        void shouldStoreCurrency() {
            assertEquals("USD", event.getCurrency());
        }

        @Test
        @DisplayName("should store transaction ID")
        void shouldStoreTransactionId() {
            assertEquals("txn-ext-001", event.getTransactionId());
        }

        @Test
        @DisplayName("should store method")
        void shouldStoreMethod() {
            assertEquals("CREDIT_CARD", event.getMethod());
        }

        @Test
        @DisplayName("should set timestamp")
        void shouldSetTimestamp() {
            assertNotNull(event.getTimestamp());
        }

        @Test
        @DisplayName("default constructor should set event type")
        void defaultConstructorShouldSetEventType() {
            PaymentProcessedEvent defaultEvent = new PaymentProcessedEvent();
            assertEquals(PaymentProcessedEvent.EVENT_TYPE, defaultEvent.getEventType());
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
            assertEquals("order-456", record.getString("orderId"));
            assertEquals("cust-789", record.getString("customerId"));
            assertEquals("99.99", record.getString("amount"));
            assertEquals("USD", record.getString("currency"));
            assertEquals("txn-ext-001", record.getString("transactionId"));
            assertEquals("CREDIT_CARD", record.getString("method"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            GenericRecord record = event.toGenericRecord();
            PaymentProcessedEvent deserialized = PaymentProcessedEvent.fromGenericRecord(record);

            assertNotNull(deserialized);
            assertEquals(event.getEventId(), deserialized.getEventId());
            assertEquals(event.getEventType(), deserialized.getEventType());
            assertEquals(event.getKey(), deserialized.getKey());
            assertEquals(event.getOrderId(), deserialized.getOrderId());
            assertEquals(event.getCustomerId(), deserialized.getCustomerId());
            assertEquals(event.getAmount(), deserialized.getAmount());
            assertEquals(event.getCurrency(), deserialized.getCurrency());
            assertEquals(event.getTransactionId(), deserialized.getTransactionId());
            assertEquals(event.getMethod(), deserialized.getMethod());
        }

        @Test
        @DisplayName("should return null when deserializing null record")
        void shouldReturnNullWhenDeserializingNullRecord() {
            assertNull(PaymentProcessedEvent.fromGenericRecord(null));
        }

        @Test
        @DisplayName("should preserve saga fields through serialization")
        void shouldPreserveSagaFields() {
            event.setSagaId("saga-123");
            event.setSagaType("OrderSaga");
            event.setStepNumber(2);
            event.setIsCompensating(false);

            GenericRecord record = event.toGenericRecord();
            PaymentProcessedEvent deserialized = PaymentProcessedEvent.fromGenericRecord(record);

            assertEquals("saga-123", deserialized.getSagaId());
            assertEquals("OrderSaga", deserialized.getSagaType());
            assertEquals(2, deserialized.getStepNumber());
            assertFalse(deserialized.getIsCompensating());
        }
    }

    @Nested
    @DisplayName("Apply")
    class Apply {

        @Test
        @DisplayName("should create payment with CAPTURED status")
        void shouldCreatePaymentWithCapturedStatus() {
            GenericRecord newState = event.apply(null);

            assertNotNull(newState);
            assertEquals("pay-123", newState.getString("paymentId"));
            assertEquals("order-456", newState.getString("orderId"));
            assertEquals("cust-789", newState.getString("customerId"));
            assertEquals("99.99", newState.getString("amount"));
            assertEquals("USD", newState.getString("currency"));
            assertEquals("CREDIT_CARD", newState.getString("method"));
            assertEquals(Payment.PaymentStatus.CAPTURED.name(), newState.getString("status"));
            assertEquals("txn-ext-001", newState.getString("transactionId"));
        }

        @Test
        @DisplayName("should set processedAt timestamp")
        void shouldSetProcessedAtTimestamp() {
            GenericRecord newState = event.apply(null);
            assertTrue(newState.getInt64("processedAt") > 0);
        }

        @Test
        @DisplayName("should set createdAt and updatedAt timestamps")
        void shouldSetTimestamps() {
            GenericRecord newState = event.apply(null);
            assertTrue(newState.getInt64("createdAt") > 0);
            assertTrue(newState.getInt64("updatedAt") > 0);
        }

        @Test
        @DisplayName("should have null failure reason")
        void shouldHaveNullFailureReason() {
            GenericRecord newState = event.apply(null);
            assertNull(newState.getString("failureReason"));
        }
    }

    @Nested
    @DisplayName("Schema")
    class Schema {

        @Test
        @DisplayName("should return correct schema name")
        void shouldReturnCorrectSchemaName() {
            assertEquals(PaymentProcessedEvent.SCHEMA_NAME, event.getSchemaName());
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToString {

        @Test
        @DisplayName("should include relevant fields")
        void shouldIncludeRelevantFields() {
            String str = event.toString();
            assertTrue(str.contains("pay-123"));
            assertTrue(str.contains("order-456"));
            assertTrue(str.contains("99.99"));
            assertTrue(str.contains("txn-ext-001"));
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
        @DisplayName("should return step 2 for saga step number")
        void shouldReturnStep2ForSagaStepNumber() {
            assertEquals(SagaCompensationConfig.STEP_PAYMENT_PROCESSED, event.getSagaStepNumber());
            assertEquals(2, event.getSagaStepNumber());
        }

        @Test
        @DisplayName("should return PaymentRefunded as compensating event type")
        void shouldReturnPaymentRefundedAsCompensatingEventType() {
            assertEquals(SagaCompensationConfig.PAYMENT_REFUNDED, event.getCompensatingEventType());
            assertEquals("PaymentRefunded", event.getCompensatingEventType());
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
