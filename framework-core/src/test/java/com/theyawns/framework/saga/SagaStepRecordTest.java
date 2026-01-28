package com.theyawns.framework.saga;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SagaStepRecord.
 */
@DisplayName("SagaStepRecord - Individual saga step tracking")
class SagaStepRecordTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build with all fields")
        void shouldBuildWithAllFields() {
            SagaStepRecord step = SagaStepRecord.builder()
                    .stepNumber(1)
                    .stepName("ReserveStock")
                    .service("inventory-service")
                    .eventType("StockReserved")
                    .status(StepStatus.COMPLETED)
                    .eventId("evt-123")
                    .build();

            assertEquals(1, step.getStepNumber());
            assertEquals("ReserveStock", step.getStepName());
            assertEquals("inventory-service", step.getService());
            assertEquals("StockReserved", step.getEventType());
            assertEquals(StepStatus.COMPLETED, step.getStatus());
            assertEquals("evt-123", step.getEventId());
        }

        @Test
        @DisplayName("should default status to PENDING")
        void shouldDefaultStatusToPending() {
            SagaStepRecord step = SagaStepRecord.builder()
                    .stepNumber(0)
                    .stepName("CreateOrder")
                    .service("order-service")
                    .eventType("OrderCreated")
                    .build();

            assertEquals(StepStatus.PENDING, step.getStatus());
        }

        @Test
        @DisplayName("should set timestamp automatically")
        void shouldSetTimestampAutomatically() {
            Instant before = Instant.now();
            SagaStepRecord step = SagaStepRecord.builder()
                    .stepNumber(0)
                    .stepName("CreateOrder")
                    .service("order-service")
                    .eventType("OrderCreated")
                    .build();
            Instant after = Instant.now();

            assertNotNull(step.getTimestamp());
            assertTrue(step.getTimestamp().compareTo(before) >= 0);
            assertTrue(step.getTimestamp().compareTo(after) <= 0);
        }

        @Test
        @DisplayName("should allow custom timestamp")
        void shouldAllowCustomTimestamp() {
            Instant customTime = Instant.parse("2026-01-01T00:00:00Z");
            SagaStepRecord step = SagaStepRecord.builder()
                    .stepNumber(0)
                    .stepName("CreateOrder")
                    .service("order-service")
                    .eventType("OrderCreated")
                    .timestamp(customTime)
                    .build();

            assertEquals(customTime, step.getTimestamp());
        }

        @Test
        @DisplayName("should include failure reason")
        void shouldIncludeFailureReason() {
            SagaStepRecord step = SagaStepRecord.builder()
                    .stepNumber(2)
                    .stepName("ProcessPayment")
                    .service("payment-service")
                    .eventType("PaymentFailed")
                    .status(StepStatus.FAILED)
                    .failureReason("Insufficient funds")
                    .build();

            assertEquals("Insufficient funds", step.getFailureReason());
        }
    }

    @Nested
    @DisplayName("State transitions")
    class StateTransitions {

        @Test
        @DisplayName("withStatus should create new instance")
        void withStatusShouldCreateNewInstance() {
            SagaStepRecord original = SagaStepRecord.builder()
                    .stepNumber(1)
                    .stepName("ReserveStock")
                    .service("inventory-service")
                    .eventType("StockReserved")
                    .status(StepStatus.PENDING)
                    .build();

            SagaStepRecord updated = original.withStatus(StepStatus.COMPLETED);

            assertEquals(StepStatus.PENDING, original.getStatus());
            assertEquals(StepStatus.COMPLETED, updated.getStatus());
        }

        @Test
        @DisplayName("completed should set status and eventId")
        void completedShouldSetStatusAndEventId() {
            SagaStepRecord original = SagaStepRecord.builder()
                    .stepNumber(1)
                    .stepName("ReserveStock")
                    .service("inventory-service")
                    .eventType("StockReserved")
                    .status(StepStatus.PENDING)
                    .build();

            SagaStepRecord completed = original.completed("evt-456");

            assertEquals(StepStatus.COMPLETED, completed.getStatus());
            assertEquals("evt-456", completed.getEventId());
        }

        @Test
        @DisplayName("failed should set status and reason")
        void failedShouldSetStatusAndReason() {
            SagaStepRecord original = SagaStepRecord.builder()
                    .stepNumber(2)
                    .stepName("ProcessPayment")
                    .service("payment-service")
                    .eventType("PaymentProcessed")
                    .status(StepStatus.PENDING)
                    .build();

            SagaStepRecord failed = original.failed("Card declined");

            assertEquals(StepStatus.FAILED, failed.getStatus());
            assertEquals("Card declined", failed.getFailureReason());
        }
    }

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("should serialize to GenericRecord")
        void shouldSerializeToGenericRecord() {
            SagaStepRecord step = SagaStepRecord.builder()
                    .stepNumber(1)
                    .stepName("ReserveStock")
                    .service("inventory-service")
                    .eventType("StockReserved")
                    .status(StepStatus.COMPLETED)
                    .eventId("evt-123")
                    .build();

            GenericRecord record = step.toGenericRecord();

            assertEquals(1, record.getInt32("stepNumber"));
            assertEquals("ReserveStock", record.getString("stepName"));
            assertEquals("inventory-service", record.getString("service"));
            assertEquals("StockReserved", record.getString("eventType"));
            assertEquals("COMPLETED", record.getString("status"));
            assertEquals("evt-123", record.getString("eventId"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            SagaStepRecord original = SagaStepRecord.builder()
                    .stepNumber(2)
                    .stepName("ProcessPayment")
                    .service("payment-service")
                    .eventType("PaymentProcessed")
                    .status(StepStatus.FAILED)
                    .failureReason("Insufficient funds")
                    .build();

            GenericRecord record = original.toGenericRecord();
            SagaStepRecord deserialized = SagaStepRecord.fromGenericRecord(record);

            assertEquals(original.getStepNumber(), deserialized.getStepNumber());
            assertEquals(original.getStepName(), deserialized.getStepName());
            assertEquals(original.getService(), deserialized.getService());
            assertEquals(original.getEventType(), deserialized.getEventType());
            assertEquals(original.getStatus(), deserialized.getStatus());
            assertEquals(original.getFailureReason(), deserialized.getFailureReason());
        }

        @Test
        @DisplayName("should round-trip serialize")
        void shouldRoundTripSerialize() {
            SagaStepRecord original = SagaStepRecord.builder()
                    .stepNumber(1)
                    .stepName("ReserveStock")
                    .service("inventory-service")
                    .eventType("StockReserved")
                    .status(StepStatus.COMPLETED)
                    .eventId("evt-123")
                    .build();

            SagaStepRecord roundTrip = SagaStepRecord.fromGenericRecord(original.toGenericRecord());

            assertEquals(original, roundTrip);
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("should be equal when key fields match")
        void shouldBeEqualWhenKeyFieldsMatch() {
            SagaStepRecord step1 = SagaStepRecord.builder()
                    .stepNumber(1)
                    .stepName("ReserveStock")
                    .service("inventory-service")
                    .eventType("StockReserved")
                    .status(StepStatus.COMPLETED)
                    .build();

            SagaStepRecord step2 = SagaStepRecord.builder()
                    .stepNumber(1)
                    .stepName("ReserveStock")
                    .service("inventory-service")
                    .eventType("StockReserved")
                    .status(StepStatus.COMPLETED)
                    .build();

            assertEquals(step1, step2);
            assertEquals(step1.hashCode(), step2.hashCode());
        }

        @Test
        @DisplayName("should not be equal when stepNumber differs")
        void shouldNotBeEqualWhenStepNumberDiffers() {
            SagaStepRecord step1 = SagaStepRecord.builder()
                    .stepNumber(1)
                    .stepName("ReserveStock")
                    .service("inventory-service")
                    .eventType("StockReserved")
                    .status(StepStatus.COMPLETED)
                    .build();

            SagaStepRecord step2 = SagaStepRecord.builder()
                    .stepNumber(2)
                    .stepName("ReserveStock")
                    .service("inventory-service")
                    .eventType("StockReserved")
                    .status(StepStatus.COMPLETED)
                    .build();

            assertNotEquals(step1, step2);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("should include key information")
        void shouldIncludeKeyInformation() {
            SagaStepRecord step = SagaStepRecord.builder()
                    .stepNumber(1)
                    .stepName("ReserveStock")
                    .service("inventory-service")
                    .eventType("StockReserved")
                    .status(StepStatus.COMPLETED)
                    .build();

            String str = step.toString();

            assertTrue(str.contains("1"));
            assertTrue(str.contains("ReserveStock"));
            assertTrue(str.contains("inventory-service"));
            assertTrue(str.contains("COMPLETED"));
        }
    }
}
