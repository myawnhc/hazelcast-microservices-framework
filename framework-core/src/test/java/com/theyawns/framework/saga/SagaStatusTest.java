package com.theyawns.framework.saga;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SagaStatus enum.
 */
@DisplayName("SagaStatus - Saga lifecycle states")
class SagaStatusTest {

    @Nested
    @DisplayName("isTerminal")
    class IsTerminal {

        @Test
        @DisplayName("STARTED should not be terminal")
        void startedShouldNotBeTerminal() {
            assertFalse(SagaStatus.STARTED.isTerminal());
        }

        @Test
        @DisplayName("IN_PROGRESS should not be terminal")
        void inProgressShouldNotBeTerminal() {
            assertFalse(SagaStatus.IN_PROGRESS.isTerminal());
        }

        @Test
        @DisplayName("COMPENSATING should not be terminal")
        void compensatingShouldNotBeTerminal() {
            assertFalse(SagaStatus.COMPENSATING.isTerminal());
        }

        @Test
        @DisplayName("COMPLETED should be terminal")
        void completedShouldBeTerminal() {
            assertTrue(SagaStatus.COMPLETED.isTerminal());
        }

        @Test
        @DisplayName("COMPENSATED should be terminal")
        void compensatedShouldBeTerminal() {
            assertTrue(SagaStatus.COMPENSATED.isTerminal());
        }

        @Test
        @DisplayName("FAILED should be terminal")
        void failedShouldBeTerminal() {
            assertTrue(SagaStatus.FAILED.isTerminal());
        }

        @Test
        @DisplayName("TIMED_OUT should be terminal")
        void timedOutShouldBeTerminal() {
            assertTrue(SagaStatus.TIMED_OUT.isTerminal());
        }
    }

    @Nested
    @DisplayName("isActive")
    class IsActive {

        @Test
        @DisplayName("STARTED should be active")
        void startedShouldBeActive() {
            assertTrue(SagaStatus.STARTED.isActive());
        }

        @Test
        @DisplayName("IN_PROGRESS should be active")
        void inProgressShouldBeActive() {
            assertTrue(SagaStatus.IN_PROGRESS.isActive());
        }

        @Test
        @DisplayName("COMPENSATING should be active")
        void compensatingShouldBeActive() {
            assertTrue(SagaStatus.COMPENSATING.isActive());
        }

        @Test
        @DisplayName("COMPLETED should not be active")
        void completedShouldNotBeActive() {
            assertFalse(SagaStatus.COMPLETED.isActive());
        }

        @Test
        @DisplayName("COMPENSATED should not be active")
        void compensatedShouldNotBeActive() {
            assertFalse(SagaStatus.COMPENSATED.isActive());
        }

        @Test
        @DisplayName("FAILED should not be active")
        void failedShouldNotBeActive() {
            assertFalse(SagaStatus.FAILED.isActive());
        }

        @Test
        @DisplayName("TIMED_OUT should not be active")
        void timedOutShouldNotBeActive() {
            assertFalse(SagaStatus.TIMED_OUT.isActive());
        }
    }

    @Nested
    @DisplayName("isSuccessful")
    class IsSuccessful {

        @Test
        @DisplayName("only COMPLETED should be successful")
        void onlyCompletedShouldBeSuccessful() {
            assertTrue(SagaStatus.COMPLETED.isSuccessful());

            assertFalse(SagaStatus.STARTED.isSuccessful());
            assertFalse(SagaStatus.IN_PROGRESS.isSuccessful());
            assertFalse(SagaStatus.COMPENSATING.isSuccessful());
            assertFalse(SagaStatus.COMPENSATED.isSuccessful());
            assertFalse(SagaStatus.FAILED.isSuccessful());
            assertFalse(SagaStatus.TIMED_OUT.isSuccessful());
        }
    }

    @Nested
    @DisplayName("isFailure")
    class IsFailure {

        @Test
        @DisplayName("COMPENSATING should be failure")
        void compensatingShouldBeFailure() {
            assertTrue(SagaStatus.COMPENSATING.isFailure());
        }

        @Test
        @DisplayName("COMPENSATED should be failure")
        void compensatedShouldBeFailure() {
            assertTrue(SagaStatus.COMPENSATED.isFailure());
        }

        @Test
        @DisplayName("FAILED should be failure")
        void failedShouldBeFailure() {
            assertTrue(SagaStatus.FAILED.isFailure());
        }

        @Test
        @DisplayName("TIMED_OUT should be failure")
        void timedOutShouldBeFailure() {
            assertTrue(SagaStatus.TIMED_OUT.isFailure());
        }

        @Test
        @DisplayName("STARTED should not be failure")
        void startedShouldNotBeFailure() {
            assertFalse(SagaStatus.STARTED.isFailure());
        }

        @Test
        @DisplayName("IN_PROGRESS should not be failure")
        void inProgressShouldNotBeFailure() {
            assertFalse(SagaStatus.IN_PROGRESS.isFailure());
        }

        @Test
        @DisplayName("COMPLETED should not be failure")
        void completedShouldNotBeFailure() {
            assertFalse(SagaStatus.COMPLETED.isFailure());
        }
    }
}
