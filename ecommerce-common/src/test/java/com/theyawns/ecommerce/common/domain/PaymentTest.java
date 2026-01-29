package com.theyawns.ecommerce.common.domain;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.theyawns.ecommerce.common.dto.PaymentDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Payment domain object.
 */
@DisplayName("Payment - Payment domain object")
class PaymentTest {

    private Payment payment;

    @BeforeEach
    void setUp() {
        payment = new Payment(
                "pay-123", "order-456", "cust-789",
                new BigDecimal("99.99"), "USD",
                Payment.PaymentMethod.CREDIT_CARD);
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should set payment ID as key")
        void shouldSetPaymentIdAsKey() {
            assertEquals("pay-123", payment.getKey());
            assertEquals("pay-123", payment.getPaymentId());
        }

        @Test
        @DisplayName("should set order ID")
        void shouldSetOrderId() {
            assertEquals("order-456", payment.getOrderId());
        }

        @Test
        @DisplayName("should set customer ID")
        void shouldSetCustomerId() {
            assertEquals("cust-789", payment.getCustomerId());
        }

        @Test
        @DisplayName("should set amount")
        void shouldSetAmount() {
            assertEquals(new BigDecimal("99.99"), payment.getAmount());
        }

        @Test
        @DisplayName("should set currency")
        void shouldSetCurrency() {
            assertEquals("USD", payment.getCurrency());
        }

        @Test
        @DisplayName("should set method")
        void shouldSetMethod() {
            assertEquals(Payment.PaymentMethod.CREDIT_CARD, payment.getMethod());
        }

        @Test
        @DisplayName("should default status to PENDING")
        void shouldDefaultStatusToPending() {
            assertEquals(Payment.PaymentStatus.PENDING, payment.getStatus());
        }

        @Test
        @DisplayName("should set createdAt")
        void shouldSetCreatedAt() {
            assertNotNull(payment.getCreatedAt());
        }

        @Test
        @DisplayName("default constructor should create empty payment")
        void defaultConstructorShouldCreateEmptyPayment() {
            Payment empty = new Payment();
            assertNull(empty.getPaymentId());
            assertNull(empty.getStatus());
        }
    }

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("should serialize to GenericRecord")
        void shouldSerializeToGenericRecord() {
            GenericRecord record = payment.toGenericRecord();

            assertNotNull(record);
            assertEquals("pay-123", record.getString("paymentId"));
            assertEquals("order-456", record.getString("orderId"));
            assertEquals("cust-789", record.getString("customerId"));
            assertEquals("99.99", record.getString("amount"));
            assertEquals("USD", record.getString("currency"));
            assertEquals("CREDIT_CARD", record.getString("method"));
            assertEquals("PENDING", record.getString("status"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            GenericRecord record = payment.toGenericRecord();
            Payment deserialized = Payment.fromGenericRecord(record);

            assertNotNull(deserialized);
            assertEquals(payment.getPaymentId(), deserialized.getPaymentId());
            assertEquals(payment.getOrderId(), deserialized.getOrderId());
            assertEquals(payment.getCustomerId(), deserialized.getCustomerId());
            assertEquals(payment.getAmount(), deserialized.getAmount());
            assertEquals(payment.getCurrency(), deserialized.getCurrency());
            assertEquals(payment.getMethod(), deserialized.getMethod());
            assertEquals(payment.getStatus(), deserialized.getStatus());
        }

        @Test
        @DisplayName("should return null when deserializing null record")
        void shouldReturnNullWhenDeserializingNullRecord() {
            assertNull(Payment.fromGenericRecord(null));
        }
    }

    @Nested
    @DisplayName("Business Logic")
    class BusinessLogic {

        @Test
        @DisplayName("should allow refund when status is CAPTURED")
        void shouldAllowRefundWhenCaptured() {
            payment.setStatus(Payment.PaymentStatus.CAPTURED);
            assertTrue(payment.canRefund());
        }

        @Test
        @DisplayName("should not allow refund when status is PENDING")
        void shouldNotAllowRefundWhenPending() {
            assertFalse(payment.canRefund());
        }

        @Test
        @DisplayName("should not allow refund when status is FAILED")
        void shouldNotAllowRefundWhenFailed() {
            payment.setStatus(Payment.PaymentStatus.FAILED);
            assertFalse(payment.canRefund());
        }

        @Test
        @DisplayName("should not allow refund when already REFUNDED")
        void shouldNotAllowRefundWhenRefunded() {
            payment.setStatus(Payment.PaymentStatus.REFUNDED);
            assertFalse(payment.canRefund());
        }
    }

    @Nested
    @DisplayName("DTO Conversion")
    class DTOConversion {

        @Test
        @DisplayName("should convert to DTO")
        void shouldConvertToDTO() {
            payment.setTransactionId("txn-001");
            PaymentDTO dto = payment.toDTO();

            assertNotNull(dto);
            assertEquals("pay-123", dto.getPaymentId());
            assertEquals("order-456", dto.getOrderId());
            assertEquals("cust-789", dto.getCustomerId());
            assertEquals(new BigDecimal("99.99"), dto.getAmount());
            assertEquals("USD", dto.getCurrency());
            assertEquals("CREDIT_CARD", dto.getMethod());
            assertEquals("PENDING", dto.getStatus());
            assertEquals("txn-001", dto.getTransactionId());
        }
    }

    @Nested
    @DisplayName("Schema")
    class Schema {

        @Test
        @DisplayName("should return correct schema name")
        void shouldReturnCorrectSchemaName() {
            assertEquals(Payment.SCHEMA_NAME, payment.getSchemaName());
            assertEquals("Payment", payment.getSchemaName());
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("should be equal when payment IDs match")
        void shouldBeEqualWhenIdsMatch() {
            Payment other = new Payment("pay-123", "other-order", "other-cust",
                    new BigDecimal("1.00"), "EUR", Payment.PaymentMethod.DEBIT_CARD);
            assertEquals(payment, other);
        }

        @Test
        @DisplayName("should not be equal when payment IDs differ")
        void shouldNotBeEqualWhenIdsDiffer() {
            Payment other = new Payment("pay-999", "order-456", "cust-789",
                    new BigDecimal("99.99"), "USD", Payment.PaymentMethod.CREDIT_CARD);
            assertNotEquals(payment, other);
        }

        @Test
        @DisplayName("should have consistent hashCode")
        void shouldHaveConsistentHashCode() {
            Payment other = new Payment("pay-123", "other-order", "other-cust",
                    new BigDecimal("1.00"), "EUR", Payment.PaymentMethod.DEBIT_CARD);
            assertEquals(payment.hashCode(), other.hashCode());
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToString {

        @Test
        @DisplayName("should include relevant fields")
        void shouldIncludeRelevantFields() {
            String str = payment.toString();
            assertTrue(str.contains("pay-123"));
            assertTrue(str.contains("order-456"));
            assertTrue(str.contains("99.99"));
            assertTrue(str.contains("USD"));
            assertTrue(str.contains("PENDING"));
        }
    }

    @Nested
    @DisplayName("Enums")
    class Enums {

        @Test
        @DisplayName("PaymentMethod should have all values")
        void paymentMethodShouldHaveAllValues() {
            Payment.PaymentMethod[] methods = Payment.PaymentMethod.values();
            assertEquals(4, methods.length);
            assertNotNull(Payment.PaymentMethod.valueOf("CREDIT_CARD"));
            assertNotNull(Payment.PaymentMethod.valueOf("DEBIT_CARD"));
            assertNotNull(Payment.PaymentMethod.valueOf("BANK_TRANSFER"));
            assertNotNull(Payment.PaymentMethod.valueOf("DIGITAL_WALLET"));
        }

        @Test
        @DisplayName("PaymentStatus should have all values")
        void paymentStatusShouldHaveAllValues() {
            Payment.PaymentStatus[] statuses = Payment.PaymentStatus.values();
            assertEquals(5, statuses.length);
            assertNotNull(Payment.PaymentStatus.valueOf("PENDING"));
            assertNotNull(Payment.PaymentStatus.valueOf("AUTHORIZED"));
            assertNotNull(Payment.PaymentStatus.valueOf("CAPTURED"));
            assertNotNull(Payment.PaymentStatus.valueOf("FAILED"));
            assertNotNull(Payment.PaymentStatus.valueOf("REFUNDED"));
        }
    }
}
