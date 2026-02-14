package com.theyawns.framework.saga.orchestrator;

import com.theyawns.framework.saga.SagaStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SagaOrchestratorResult.
 */
@DisplayName("SagaOrchestratorResult - Final saga outcome")
class SagaOrchestratorResultTest {

    private static final String SAGA_ID = "saga-123";
    private static final String SAGA_NAME = "OrderFulfillment";
    private static final Instant STARTED_AT = Instant.now().minusSeconds(5);

    @Nested
    @DisplayName("success()")
    class Success {

        @Test
        @DisplayName("should have COMPLETED status")
        void shouldHaveCompletedStatus() {
            final SagaOrchestratorResult result = SagaOrchestratorResult.success(
                    SAGA_ID, SAGA_NAME, STARTED_AT, 3);

            assertEquals(SagaStatus.COMPLETED, result.getStatus());
        }

        @Test
        @DisplayName("should be successful")
        void shouldBeSuccessful() {
            final SagaOrchestratorResult result = SagaOrchestratorResult.success(
                    SAGA_ID, SAGA_NAME, STARTED_AT, 3);

            assertTrue(result.isSuccessful());
            assertFalse(result.isCompensated());
            assertFalse(result.isFailed());
            assertFalse(result.isTimedOut());
        }

        @Test
        @DisplayName("should carry step count")
        void shouldCarryStepCount() {
            final SagaOrchestratorResult result = SagaOrchestratorResult.success(
                    SAGA_ID, SAGA_NAME, STARTED_AT, 3);

            assertEquals(3, result.getStepsCompleted());
            assertEquals(0, result.getStepsCompensated());
        }

        @Test
        @DisplayName("should have no failure reason")
        void shouldHaveNoFailureReason() {
            final SagaOrchestratorResult result = SagaOrchestratorResult.success(
                    SAGA_ID, SAGA_NAME, STARTED_AT, 3);

            assertTrue(result.getFailureReason().isEmpty());
            assertTrue(result.getFailedAtStep().isEmpty());
        }
    }

    @Nested
    @DisplayName("compensated()")
    class Compensated {

        @Test
        @DisplayName("should have COMPENSATED status")
        void shouldHaveCompensatedStatus() {
            final SagaOrchestratorResult result = SagaOrchestratorResult.compensated(
                    SAGA_ID, SAGA_NAME, STARTED_AT, 2, 2,
                    "ProcessPayment", "Insufficient funds");

            assertEquals(SagaStatus.COMPENSATED, result.getStatus());
        }

        @Test
        @DisplayName("should be compensated")
        void shouldBeCompensated() {
            final SagaOrchestratorResult result = SagaOrchestratorResult.compensated(
                    SAGA_ID, SAGA_NAME, STARTED_AT, 2, 2,
                    "ProcessPayment", "Insufficient funds");

            assertTrue(result.isCompensated());
            assertFalse(result.isSuccessful());
        }

        @Test
        @DisplayName("should carry failure details")
        void shouldCarryFailureDetails() {
            final SagaOrchestratorResult result = SagaOrchestratorResult.compensated(
                    SAGA_ID, SAGA_NAME, STARTED_AT, 2, 2,
                    "ProcessPayment", "Insufficient funds");

            assertEquals("ProcessPayment", result.getFailedAtStep().get());
            assertEquals("Insufficient funds", result.getFailureReason().get());
            assertEquals(2, result.getStepsCompleted());
            assertEquals(2, result.getStepsCompensated());
        }
    }

    @Nested
    @DisplayName("failed()")
    class Failed {

        @Test
        @DisplayName("should have FAILED status")
        void shouldHaveFailedStatus() {
            final SagaOrchestratorResult result = SagaOrchestratorResult.failed(
                    SAGA_ID, SAGA_NAME, STARTED_AT, 1, 0,
                    "ReserveStock", "Service unavailable");

            assertEquals(SagaStatus.FAILED, result.getStatus());
            assertTrue(result.isFailed());
        }
    }

    @Nested
    @DisplayName("timedOut()")
    class TimedOut {

        @Test
        @DisplayName("should have TIMED_OUT status")
        void shouldHaveTimedOutStatus() {
            final SagaOrchestratorResult result = SagaOrchestratorResult.timedOut(
                    SAGA_ID, SAGA_NAME, STARTED_AT, 1, "ProcessPayment");

            assertEquals(SagaStatus.TIMED_OUT, result.getStatus());
            assertTrue(result.isTimedOut());
        }

        @Test
        @DisplayName("should carry timeout message")
        void shouldCarryTimeoutMessage() {
            final SagaOrchestratorResult result = SagaOrchestratorResult.timedOut(
                    SAGA_ID, SAGA_NAME, STARTED_AT, 1, "ProcessPayment");

            assertEquals("Saga timed out", result.getFailureReason().get());
            assertEquals("ProcessPayment", result.getFailedAtStep().get());
        }
    }

    @Nested
    @DisplayName("Duration and identity")
    class DurationAndIdentity {

        @Test
        @DisplayName("should compute positive duration")
        void shouldComputePositiveDuration() {
            final SagaOrchestratorResult result = SagaOrchestratorResult.success(
                    SAGA_ID, SAGA_NAME, STARTED_AT, 3);

            final Duration duration = result.getDuration();

            assertFalse(duration.isNegative());
            assertFalse(duration.isZero());
        }

        @Test
        @DisplayName("should return saga identity fields")
        void shouldReturnIdentityFields() {
            final SagaOrchestratorResult result = SagaOrchestratorResult.success(
                    SAGA_ID, SAGA_NAME, STARTED_AT, 3);

            assertEquals(SAGA_ID, result.getSagaId());
            assertEquals(SAGA_NAME, result.getSagaName());
            assertEquals(STARTED_AT, result.getStartedAt());
            assertNotNull(result.getCompletedAt());
        }
    }

    @Test
    @DisplayName("toString should include key fields")
    void toStringShouldIncludeKeyFields() {
        final SagaOrchestratorResult result = SagaOrchestratorResult.failed(
                SAGA_ID, SAGA_NAME, STARTED_AT, 1, 0,
                "ReserveStock", "Service down");

        final String str = result.toString();

        assertTrue(str.contains(SAGA_ID));
        assertTrue(str.contains(SAGA_NAME));
        assertTrue(str.contains("FAILED"));
        assertTrue(str.contains("ReserveStock"));
    }
}
