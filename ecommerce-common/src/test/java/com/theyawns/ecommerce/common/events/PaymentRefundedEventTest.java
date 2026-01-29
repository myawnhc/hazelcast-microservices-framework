package com.theyawns.ecommerce.common.events;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.hazelcast.nio.serialization.genericrecord.GenericRecordBuilder;
import com.theyawns.ecommerce.common.domain.Payment;
import com.theyawns.framework.saga.SagaCompensationConfig;
import com.theyawns.framework.saga.SagaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PaymentRefundedEvent.
 */
@DisplayName("PaymentRefundedEvent - Payment refund compensation event")
class PaymentRefundedEventTest {

    private PaymentRefundedEvent event;

    @BeforeEach
    void setUp() {
        event = new PaymentRefundedEvent(
                "pay-123", "order-456",
                "99.99", "SAGA_COMPENSATION",
                "refund-txn-001");
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should set event type")
        void shouldSetEventType() {
            assertEquals(PaymentRefundedEvent.EVENT_TYPE, event.getEventType());
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
        @DisplayName("should store amount")
        void shouldStoreAmount() {
            assertEquals("99.99", event.getAmount());
        }

        @Test
        @DisplayName("should store reason")
        void shouldStoreReason() {
            assertEquals("SAGA_COMPENSATION", event.getReason());
        }

        @Test
        @DisplayName("should store refund transaction ID")
        void shouldStoreRefundTransactionId() {
            assertEquals("refund-txn-001", event.getRefundTransactionId());
        }

        @Test
        @DisplayName("should set timestamp")
        void shouldSetTimestamp() {
            assertNotNull(event.getTimestamp());
        }

        @Test
        @DisplayName("default constructor should set event type")
        void defaultConstructorShouldSetEventType() {
            PaymentRefundedEvent defaultEvent = new PaymentRefundedEvent();
            assertEquals(PaymentRefundedEvent.EVENT_TYPE, defaultEvent.getEventType());
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
            assertEquals("99.99", record.getString("amount"));
            assertEquals("SAGA_COMPENSATION", record.getString("reason"));
            assertEquals("refund-txn-001", record.getString("refundTransactionId"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            GenericRecord record = event.toGenericRecord();
            PaymentRefundedEvent deserialized = PaymentRefundedEvent.fromGenericRecord(record);

            assertNotNull(deserialized);
            assertEquals(event.getEventId(), deserialized.getEventId());
            assertEquals(event.getEventType(), deserialized.getEventType());
            assertEquals(event.getKey(), deserialized.getKey());
            assertEquals(event.getOrderId(), deserialized.getOrderId());
            assertEquals(event.getAmount(), deserialized.getAmount());
            assertEquals(event.getReason(), deserialized.getReason());
            assertEquals(event.getRefundTransactionId(), deserialized.getRefundTransactionId());
        }

        @Test
        @DisplayName("should return null when deserializing null record")
        void shouldReturnNullWhenDeserializingNullRecord() {
            assertNull(PaymentRefundedEvent.fromGenericRecord(null));
        }

        @Test
        @DisplayName("should preserve saga fields through serialization")
        void shouldPreserveSagaFields() {
            event.setSagaId("saga-789");
            event.setSagaType("OrderFulfillment");
            event.setStepNumber(2);
            event.setIsCompensating(true);

            GenericRecord record = event.toGenericRecord();
            PaymentRefundedEvent deserialized = PaymentRefundedEvent.fromGenericRecord(record);

            assertEquals("saga-789", deserialized.getSagaId());
            assertEquals("OrderFulfillment", deserialized.getSagaType());
            assertEquals(2, deserialized.getStepNumber());
            assertTrue(deserialized.getIsCompensating());
        }
    }

    @Nested
    @DisplayName("Apply")
    class Apply {

        private GenericRecord existingPaymentState;

        @BeforeEach
        void setUpExistingState() {
            Instant now = Instant.now();
            existingPaymentState = GenericRecordBuilder.compact(Payment.SCHEMA_NAME)
                    .setString("paymentId", "pay-123")
                    .setString("orderId", "order-456")
                    .setString("customerId", "cust-789")
                    .setString("amount", "99.99")
                    .setString("currency", "USD")
                    .setString("method", "CREDIT_CARD")
                    .setString("status", Payment.PaymentStatus.CAPTURED.name())
                    .setString("transactionId", "txn-ext-001")
                    .setInt64("processedAt", now.toEpochMilli())
                    .setString("failureReason", null)
                    .setInt64("createdAt", now.toEpochMilli())
                    .setInt64("updatedAt", now.toEpochMilli())
                    .build();
        }

        @Test
        @DisplayName("should update status to REFUNDED")
        void shouldUpdateStatusToRefunded() {
            GenericRecord refunded = event.apply(existingPaymentState);

            assertEquals(Payment.PaymentStatus.REFUNDED.name(), refunded.getString("status"));
        }

        @Test
        @DisplayName("should set failure reason to refund reason")
        void shouldSetFailureReasonToRefundReason() {
            GenericRecord refunded = event.apply(existingPaymentState);

            assertEquals("SAGA_COMPENSATION", refunded.getString("failureReason"));
        }

        @Test
        @DisplayName("should preserve payment details")
        void shouldPreservePaymentDetails() {
            GenericRecord refunded = event.apply(existingPaymentState);

            assertEquals("pay-123", refunded.getString("paymentId"));
            assertEquals("order-456", refunded.getString("orderId"));
            assertEquals("cust-789", refunded.getString("customerId"));
            assertEquals("99.99", refunded.getString("amount"));
            assertEquals("USD", refunded.getString("currency"));
            assertEquals("CREDIT_CARD", refunded.getString("method"));
            assertEquals("txn-ext-001", refunded.getString("transactionId"));
        }

        @Test
        @DisplayName("should preserve createdAt and processedAt")
        void shouldPreserveOriginalTimestamps() {
            long originalCreatedAt = existingPaymentState.getInt64("createdAt");
            long originalProcessedAt = existingPaymentState.getInt64("processedAt");

            GenericRecord refunded = event.apply(existingPaymentState);

            assertEquals(originalCreatedAt, refunded.getInt64("createdAt"));
            assertEquals(originalProcessedAt, refunded.getInt64("processedAt"));
        }

        @Test
        @DisplayName("should update updatedAt timestamp")
        void shouldUpdateTimestamp() {
            long beforeApply = Instant.now().toEpochMilli();
            GenericRecord refunded = event.apply(existingPaymentState);
            long afterApply = Instant.now().toEpochMilli();

            long updatedAt = refunded.getInt64("updatedAt");
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
        @DisplayName("should update order ID via setter")
        void shouldUpdateOrderId() {
            event.setOrderId("new-order");
            assertEquals("new-order", event.getOrderId());
        }

        @Test
        @DisplayName("should update amount via setter")
        void shouldUpdateAmount() {
            event.setAmount("50.00");
            assertEquals("50.00", event.getAmount());
        }

        @Test
        @DisplayName("should update reason via setter")
        void shouldUpdateReason() {
            event.setReason("CUSTOMER_REQUEST");
            assertEquals("CUSTOMER_REQUEST", event.getReason());
        }

        @Test
        @DisplayName("should update refund transaction ID via setter")
        void shouldUpdateRefundTransactionId() {
            event.setRefundTransactionId("refund-new");
            assertEquals("refund-new", event.getRefundTransactionId());
        }
    }

    @Nested
    @DisplayName("Schema")
    class Schema {

        @Test
        @DisplayName("should return correct schema name")
        void shouldReturnCorrectSchemaName() {
            assertEquals(PaymentRefundedEvent.SCHEMA_NAME, event.getSchemaName());
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
            assertTrue(str.contains("SAGA_COMPENSATION"));
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
        @DisplayName("should return step 2 for saga step number (compensates PaymentProcessed)")
        void shouldReturnStep2ForSagaStepNumber() {
            assertEquals(SagaCompensationConfig.STEP_PAYMENT_PROCESSED, event.getSagaStepNumber());
            assertEquals(2, event.getSagaStepNumber());
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
