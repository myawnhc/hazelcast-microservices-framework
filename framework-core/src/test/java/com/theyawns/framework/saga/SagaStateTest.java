package com.theyawns.framework.saga;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SagaState.
 */
@DisplayName("SagaState - Saga instance tracking")
class SagaStateTest {

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("start should create saga in STARTED status")
        void startShouldCreateSagaInStartedStatus() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            assertEquals("saga-123", saga.getSagaId());
            assertEquals("OrderFulfillment", saga.getSagaType());
            assertEquals(SagaStatus.STARTED, saga.getStatus());
            assertEquals(4, saga.getTotalSteps());
            assertEquals(0, saga.getCurrentStep());
        }

        @Test
        @DisplayName("start should set deadline")
        void startShouldSetDeadline() {
            Instant before = Instant.now();
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));
            Instant after = Instant.now();

            assertNotNull(saga.getDeadline());
            assertTrue(saga.getDeadline().isAfter(before.plusSeconds(29)));
            assertTrue(saga.getDeadline().isBefore(after.plusSeconds(31)));
        }

        @Test
        @DisplayName("start with correlationId should set it")
        void startWithCorrelationIdShouldSetIt() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", "corr-456",
                    4, Duration.ofSeconds(30));

            assertEquals("corr-456", saga.getCorrelationId());
        }
    }

    @Nested
    @DisplayName("Step completion")
    class StepCompletion {

        @Test
        @DisplayName("recordStepCompleted should update currentStep")
        void recordStepCompletedShouldUpdateCurrentStep() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            SagaState updated = saga.recordStepCompleted(0, "OrderCreated", "order-service", "evt-1");

            assertEquals(1, updated.getCurrentStep());
        }

        @Test
        @DisplayName("recordStepCompleted should change status to IN_PROGRESS")
        void recordStepCompletedShouldChangeStatusToInProgress() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            SagaState updated = saga.recordStepCompleted(0, "OrderCreated", "order-service", "evt-1");

            assertEquals(SagaStatus.IN_PROGRESS, updated.getStatus());
        }

        @Test
        @DisplayName("recordStepCompleted should add step record")
        void recordStepCompletedShouldAddStepRecord() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            SagaState updated = saga.recordStepCompleted(0, "OrderCreated", "order-service", "evt-1");

            assertEquals(1, updated.getSteps().size());
            assertEquals("OrderCreated", updated.getSteps().get(0).getEventType());
            assertEquals(StepStatus.COMPLETED, updated.getSteps().get(0).getStatus());
        }

        @Test
        @DisplayName("final step should complete saga")
        void finalStepShouldCompleteSaga() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 2, Duration.ofSeconds(30));
            saga = saga.recordStepCompleted(0, "OrderCreated", "order-service", "evt-1");

            SagaState completed = saga.recordStepCompleted(1, "OrderConfirmed", "order-service", "evt-2");

            assertEquals(SagaStatus.COMPLETED, completed.getStatus());
            assertNotNull(completed.getCompletedAt());
        }
    }

    @Nested
    @DisplayName("Step failure")
    class StepFailure {

        @Test
        @DisplayName("recordStepFailed should change status to COMPENSATING")
        void recordStepFailedShouldChangeStatusToCompensating() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));
            saga = saga.recordStepCompleted(0, "OrderCreated", "order-service", "evt-1");

            SagaState failed = saga.recordStepFailed(1, "PaymentFailed", "payment-service", "Insufficient funds");

            assertEquals(SagaStatus.COMPENSATING, failed.getStatus());
        }

        @Test
        @DisplayName("recordStepFailed should record failure reason")
        void recordStepFailedShouldRecordFailureReason() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            SagaState failed = saga.recordStepFailed(1, "PaymentFailed", "payment-service", "Insufficient funds");

            assertEquals("Insufficient funds", failed.getFailureReason());
            assertEquals(1, failed.getFailedAtStep());
        }

        @Test
        @DisplayName("recordStepFailed should add failed step record")
        void recordStepFailedShouldAddFailedStepRecord() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            SagaState failed = saga.recordStepFailed(1, "PaymentFailed", "payment-service", "Card declined");

            List<SagaStepRecord> steps = failed.getSteps();
            assertEquals(1, steps.size());
            assertEquals(StepStatus.FAILED, steps.get(0).getStatus());
            assertEquals("Card declined", steps.get(0).getFailureReason());
        }
    }

    @Nested
    @DisplayName("Compensation")
    class Compensation {

        @Test
        @DisplayName("startCompensation should set status to COMPENSATING")
        void startCompensationShouldSetStatusToCompensating() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            SagaState compensating = saga.startCompensation();

            assertEquals(SagaStatus.COMPENSATING, compensating.getStatus());
        }

        @Test
        @DisplayName("recordCompensationStep should mark step as COMPENSATED")
        void recordCompensationStepShouldMarkStepAsCompensated() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));
            saga = saga.recordStepCompleted(0, "OrderCreated", "order-service", "evt-1");
            saga = saga.recordStepCompleted(1, "StockReserved", "inventory-service", "evt-2");
            saga = saga.startCompensation();

            SagaState compensated = saga.recordCompensationStep(1, "StockReleased", "inventory-service");

            List<SagaStepRecord> steps = compensated.getSteps();
            SagaStepRecord step1 = steps.stream()
                    .filter(s -> s.getStepNumber() == 1)
                    .findFirst()
                    .orElseThrow();
            assertEquals(StepStatus.COMPENSATED, step1.getStatus());
        }

        @Test
        @DisplayName("getStepsNeedingCompensation should return completed steps in reverse order")
        void getStepsNeedingCompensationShouldReturnCompletedStepsInReverseOrder() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));
            saga = saga.recordStepCompleted(0, "OrderCreated", "order-service", "evt-1");
            saga = saga.recordStepCompleted(1, "StockReserved", "inventory-service", "evt-2");

            List<SagaStepRecord> needsCompensation = saga.getStepsNeedingCompensation();

            assertEquals(2, needsCompensation.size());
            assertEquals(1, needsCompensation.get(0).getStepNumber()); // Reverse order
            assertEquals(0, needsCompensation.get(1).getStepNumber());
        }
    }

    @Nested
    @DisplayName("Complete saga")
    class CompleteSaga {

        @Test
        @DisplayName("complete with COMPLETED status should set completedAt")
        void completeWithCompletedStatusShouldSetCompletedAt() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            SagaState completed = saga.complete(SagaStatus.COMPLETED);

            assertEquals(SagaStatus.COMPLETED, completed.getStatus());
            assertNotNull(completed.getCompletedAt());
        }

        @Test
        @DisplayName("complete with COMPENSATED status should work")
        void completeWithCompensatedStatusShouldWork() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            SagaState compensated = saga.complete(SagaStatus.COMPENSATED);

            assertEquals(SagaStatus.COMPENSATED, compensated.getStatus());
        }

        @Test
        @DisplayName("complete with non-terminal status should throw")
        void completeWithNonTerminalStatusShouldThrow() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            assertThrows(IllegalArgumentException.class, () ->
                    saga.complete(SagaStatus.IN_PROGRESS)
            );
        }

        @Test
        @DisplayName("timedOut should set status and reason")
        void timedOutShouldSetStatusAndReason() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            SagaState timedOut = saga.timedOut();

            assertEquals(SagaStatus.TIMED_OUT, timedOut.getStatus());
            assertNotNull(timedOut.getCompletedAt());
            assertTrue(timedOut.getFailureReason().contains("deadline"));
        }
    }

    @Nested
    @DisplayName("Timeout detection")
    class TimeoutDetection {

        @Test
        @DisplayName("isTimedOut should return false before deadline")
        void isTimedOutShouldReturnFalseBeforeDeadline() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofMinutes(5));

            assertFalse(saga.isTimedOut());
        }

        @Test
        @DisplayName("isTimedOut should return true after deadline for active saga")
        void isTimedOutShouldReturnTrueAfterDeadlineForActiveSaga() {
            // Create saga with deadline in the past
            SagaState saga = SagaState.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .status(SagaStatus.IN_PROGRESS)
                    .totalSteps(4)
                    .deadline(Instant.now().minusSeconds(10))
                    .build();

            assertTrue(saga.isTimedOut());
        }

        @Test
        @DisplayName("isTimedOut should return false for completed saga")
        void isTimedOutShouldReturnFalseForCompletedSaga() {
            SagaState saga = SagaState.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .status(SagaStatus.COMPLETED)
                    .totalSteps(4)
                    .deadline(Instant.now().minusSeconds(10))
                    .build();

            assertFalse(saga.isTimedOut());
        }
    }

    @Nested
    @DisplayName("Progress tracking")
    class ProgressTracking {

        @Test
        @DisplayName("getProgressPercentage should return 0 for new saga")
        void getProgressPercentageShouldReturnZeroForNewSaga() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            assertEquals(0, saga.getProgressPercentage());
        }

        @Test
        @DisplayName("getProgressPercentage should return correct percentage")
        void getProgressPercentageShouldReturnCorrectPercentage() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));
            saga = saga.recordStepCompleted(0, "Step1", "service", "evt-1");
            saga = saga.recordStepCompleted(1, "Step2", "service", "evt-2");

            assertEquals(50, saga.getProgressPercentage());
        }

        @Test
        @DisplayName("getDuration should return time since start")
        void getDurationShouldReturnTimeSinceStart() throws InterruptedException {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));
            Thread.sleep(50);

            Duration duration = saga.getDuration();

            assertTrue(duration.toMillis() >= 50);
        }
    }

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("should serialize to GenericRecord")
        void shouldSerializeToGenericRecord() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", "corr-456",
                    4, Duration.ofSeconds(30));

            GenericRecord record = saga.toGenericRecord();

            assertEquals("saga-123", record.getString("sagaId"));
            assertEquals("OrderFulfillment", record.getString("sagaType"));
            assertEquals("corr-456", record.getString("correlationId"));
            assertEquals("STARTED", record.getString("status"));
            assertEquals(4, record.getInt32("totalSteps"));
        }

        @Test
        @DisplayName("should deserialize from GenericRecord")
        void shouldDeserializeFromGenericRecord() {
            SagaState original = SagaState.start("saga-123", "OrderFulfillment", "corr-456",
                    4, Duration.ofSeconds(30));
            original = original.recordStepCompleted(0, "OrderCreated", "order-service", "evt-1");

            GenericRecord record = original.toGenericRecord();
            SagaState deserialized = SagaState.fromGenericRecord(record);

            assertEquals(original.getSagaId(), deserialized.getSagaId());
            assertEquals(original.getSagaType(), deserialized.getSagaType());
            assertEquals(original.getCorrelationId(), deserialized.getCorrelationId());
            assertEquals(original.getStatus(), deserialized.getStatus());
            assertEquals(original.getTotalSteps(), deserialized.getTotalSteps());
            assertEquals(original.getCurrentStep(), deserialized.getCurrentStep());
            assertEquals(original.getSteps().size(), deserialized.getSteps().size());
        }

        @Test
        @DisplayName("should round-trip serialize saga with steps")
        void shouldRoundTripSerializeSagaWithSteps() {
            SagaState original = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));
            original = original.recordStepCompleted(0, "OrderCreated", "order-service", "evt-1");
            original = original.recordStepCompleted(1, "StockReserved", "inventory-service", "evt-2");

            SagaState roundTrip = SagaState.fromGenericRecord(original.toGenericRecord());

            assertEquals(2, roundTrip.getSteps().size());
            assertEquals("OrderCreated", roundTrip.getSteps().get(0).getEventType());
            assertEquals("StockReserved", roundTrip.getSteps().get(1).getEventType());
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("should be equal when sagaId matches")
        void shouldBeEqualWhenSagaIdMatches() {
            SagaState saga1 = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));
            SagaState saga2 = SagaState.start("saga-123", "DifferentType", 2, Duration.ofSeconds(60));

            assertEquals(saga1, saga2);
            assertEquals(saga1.hashCode(), saga2.hashCode());
        }

        @Test
        @DisplayName("should not be equal when sagaId differs")
        void shouldNotBeEqualWhenSagaIdDiffers() {
            SagaState saga1 = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));
            SagaState saga2 = SagaState.start("saga-456", "OrderFulfillment", 4, Duration.ofSeconds(30));

            assertNotEquals(saga1, saga2);
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("getSteps should return unmodifiable list")
        void getStepsShouldReturnUnmodifiableList() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));
            saga = saga.recordStepCompleted(0, "OrderCreated", "order-service", "evt-1");

            List<SagaStepRecord> steps = saga.getSteps();

            assertThrows(UnsupportedOperationException.class, () ->
                    steps.add(SagaStepRecord.builder().stepNumber(99).build())
            );
        }

        @Test
        @DisplayName("state transitions should return new instances")
        void stateTransitionsShouldReturnNewInstances() {
            SagaState saga = SagaState.start("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));
            SagaState updated = saga.recordStepCompleted(0, "OrderCreated", "order-service", "evt-1");

            assertNotSame(saga, updated);
            assertEquals(SagaStatus.STARTED, saga.getStatus());
            assertEquals(SagaStatus.IN_PROGRESS, updated.getStatus());
        }
    }
}
