package com.theyawns.framework.controller;

import com.theyawns.framework.store.PartitionedSequenceKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CompletionInfo.
 */
@DisplayName("CompletionInfo - Event completion tracking")
class CompletionInfoTest {

    private PartitionedSequenceKey<String> eventKey;
    private UUID correlationId;
    private CompletionInfo<String> completionInfo;

    @BeforeEach
    void setUp() {
        eventKey = new PartitionedSequenceKey<>(12345L, "customer-123");
        correlationId = UUID.randomUUID();
        completionInfo = new CompletionInfo<>(eventKey, correlationId, "CustomerCreated", "evt-001");
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("should create with initial PENDING status")
        void shouldCreateWithPendingStatus() {
            assertEquals(CompletionInfo.Status.PENDING, completionInfo.getStatus());
        }

        @Test
        @DisplayName("should store event key")
        void shouldStoreEventKey() {
            assertEquals(eventKey, completionInfo.getEventKey());
        }

        @Test
        @DisplayName("should store correlation ID")
        void shouldStoreCorrelationId() {
            assertEquals(correlationId, completionInfo.getCorrelationId());
        }

        @Test
        @DisplayName("should store event type")
        void shouldStoreEventType() {
            assertEquals("CustomerCreated", completionInfo.getEventType());
        }

        @Test
        @DisplayName("should store event ID")
        void shouldStoreEventId() {
            assertEquals("evt-001", completionInfo.getEventId());
        }

        @Test
        @DisplayName("should set submitted time")
        void shouldSetSubmittedTime() {
            assertNotNull(completionInfo.getSubmittedAt());
        }

        @Test
        @DisplayName("should have null completed time initially")
        void shouldHaveNullCompletedTimeInitially() {
            assertNull(completionInfo.getCompletedAt());
        }

        @Test
        @DisplayName("should have null error message initially")
        void shouldHaveNullErrorMessageInitially() {
            assertNull(completionInfo.getErrorMessage());
        }
    }

    @Nested
    @DisplayName("markProcessing")
    class MarkProcessing {

        @Test
        @DisplayName("should transition status to PROCESSING")
        void shouldTransitionToProcessing() {
            completionInfo.markProcessing();
            assertEquals(CompletionInfo.Status.PROCESSING, completionInfo.getStatus());
        }

        @Test
        @DisplayName("should not be done when processing")
        void shouldNotBeDoneWhenProcessing() {
            completionInfo.markProcessing();
            assertFalse(completionInfo.isDone());
        }
    }

    @Nested
    @DisplayName("markCompleted")
    class MarkCompleted {

        @Test
        @DisplayName("should transition status to COMPLETED")
        void shouldTransitionToCompleted() {
            completionInfo.markCompleted();
            assertEquals(CompletionInfo.Status.COMPLETED, completionInfo.getStatus());
        }

        @Test
        @DisplayName("should set completed time")
        void shouldSetCompletedTime() {
            completionInfo.markCompleted();
            assertNotNull(completionInfo.getCompletedAt());
        }

        @Test
        @DisplayName("should be done when completed")
        void shouldBeDoneWhenCompleted() {
            completionInfo.markCompleted();
            assertTrue(completionInfo.isDone());
        }

        @Test
        @DisplayName("should be success when completed")
        void shouldBeSuccessWhenCompleted() {
            completionInfo.markCompleted();
            assertTrue(completionInfo.isSuccess());
        }

        @Test
        @DisplayName("should calculate processing time")
        void shouldCalculateProcessingTime() throws InterruptedException {
            Thread.sleep(10); // Small delay to ensure measurable time
            completionInfo.markCompleted();
            long processingTimeMs = completionInfo.getProcessingTimeMs();
            assertTrue(processingTimeMs >= 10);
        }
    }

    @Nested
    @DisplayName("markFailed")
    class MarkFailed {

        @Test
        @DisplayName("should transition status to FAILED")
        void shouldTransitionToFailed() {
            completionInfo.markFailed("Test error");
            assertEquals(CompletionInfo.Status.FAILED, completionInfo.getStatus());
        }

        @Test
        @DisplayName("should store error message")
        void shouldStoreErrorMessage() {
            completionInfo.markFailed("Test error");
            assertEquals("Test error", completionInfo.getErrorMessage());
        }

        @Test
        @DisplayName("should set completed time on failure")
        void shouldSetCompletedTimeOnFailure() {
            completionInfo.markFailed("Test error");
            assertNotNull(completionInfo.getCompletedAt());
        }

        @Test
        @DisplayName("should be done when failed")
        void shouldBeDoneWhenFailed() {
            completionInfo.markFailed("Test error");
            assertTrue(completionInfo.isDone());
        }

        @Test
        @DisplayName("should not be success when failed")
        void shouldNotBeSuccessWhenFailed() {
            completionInfo.markFailed("Test error");
            assertFalse(completionInfo.isSuccess());
        }

        @Test
        @DisplayName("should handle null error message")
        void shouldHandleNullErrorMessage() {
            completionInfo.markFailed(null);
            assertEquals(CompletionInfo.Status.FAILED, completionInfo.getStatus());
            assertNull(completionInfo.getErrorMessage());
        }
    }

    @Nested
    @DisplayName("isDone")
    class IsDone {

        @Test
        @DisplayName("should return false for PENDING")
        void shouldReturnFalseForPending() {
            assertFalse(completionInfo.isDone());
        }

        @Test
        @DisplayName("should return false for PROCESSING")
        void shouldReturnFalseForProcessing() {
            completionInfo.markProcessing();
            assertFalse(completionInfo.isDone());
        }

        @Test
        @DisplayName("should return true for COMPLETED")
        void shouldReturnTrueForCompleted() {
            completionInfo.markCompleted();
            assertTrue(completionInfo.isDone());
        }

        @Test
        @DisplayName("should return true for FAILED")
        void shouldReturnTrueForFailed() {
            completionInfo.markFailed("error");
            assertTrue(completionInfo.isDone());
        }
    }

    @Nested
    @DisplayName("isSuccess")
    class IsSuccess {

        @Test
        @DisplayName("should return false for PENDING")
        void shouldReturnFalseForPending() {
            assertFalse(completionInfo.isSuccess());
        }

        @Test
        @DisplayName("should return false for PROCESSING")
        void shouldReturnFalseForProcessing() {
            completionInfo.markProcessing();
            assertFalse(completionInfo.isSuccess());
        }

        @Test
        @DisplayName("should return true for COMPLETED")
        void shouldReturnTrueForCompleted() {
            completionInfo.markCompleted();
            assertTrue(completionInfo.isSuccess());
        }

        @Test
        @DisplayName("should return false for FAILED")
        void shouldReturnFalseForFailed() {
            completionInfo.markFailed("error");
            assertFalse(completionInfo.isSuccess());
        }
    }

    @Nested
    @DisplayName("getProcessingTimeMs")
    class GetProcessingTimeMs {

        @Test
        @DisplayName("should return -1 before completion")
        void shouldReturnNegativeOneBeforeCompletion() {
            assertEquals(-1, completionInfo.getProcessingTimeMs());
        }

        @Test
        @DisplayName("should return positive value after completion")
        void shouldReturnPositiveAfterCompletion() {
            completionInfo.markCompleted();
            assertTrue(completionInfo.getProcessingTimeMs() >= 0);
        }

        @Test
        @DisplayName("should return positive value after failure")
        void shouldReturnPositiveAfterFailure() {
            completionInfo.markFailed("error");
            assertTrue(completionInfo.getProcessingTimeMs() >= 0);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("should include key information")
        void shouldIncludeKeyInformation() {
            String str = completionInfo.toString();
            assertTrue(str.contains("customer-123"));
            assertTrue(str.contains("CustomerCreated"));
            assertTrue(str.contains("PENDING"));
        }

        @Test
        @DisplayName("should include error message when failed")
        void shouldIncludeErrorMessageWhenFailed() {
            completionInfo.markFailed("Something went wrong");
            String str = completionInfo.toString();
            assertTrue(str.contains("Something went wrong"));
        }
    }
}
