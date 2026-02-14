package com.theyawns.framework.saga.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SagaStepResult.
 */
@DisplayName("SagaStepResult - Step execution outcome")
class SagaStepResultTest {

    @Nested
    @DisplayName("success()")
    class SuccessNoData {

        @Test
        @DisplayName("should have SUCCESS status")
        void shouldHaveSuccessStatus() {
            final SagaStepResult result = SagaStepResult.success();

            assertEquals(SagaStepResult.Status.SUCCESS, result.getStatus());
        }

        @Test
        @DisplayName("should return true for isSuccess()")
        void shouldBeSuccess() {
            assertTrue(SagaStepResult.success().isSuccess());
        }

        @Test
        @DisplayName("should return false for isFailure() and isTimeout()")
        void shouldNotBeFailureOrTimeout() {
            final SagaStepResult result = SagaStepResult.success();

            assertFalse(result.isFailure());
            assertFalse(result.isTimeout());
        }

        @Test
        @DisplayName("should have empty data")
        void shouldHaveEmptyData() {
            assertTrue(SagaStepResult.success().getData().isEmpty());
        }

        @Test
        @DisplayName("should have empty error message")
        void shouldHaveNoErrorMessage() {
            assertTrue(SagaStepResult.success().getErrorMessage().isEmpty());
        }
    }

    @Nested
    @DisplayName("success(Map)")
    class SuccessWithData {

        @Test
        @DisplayName("should carry data")
        void shouldCarryData() {
            final Map<String, Object> data = Map.of("paymentId", "pay-123", "amount", 99.99);
            final SagaStepResult result = SagaStepResult.success(data);

            assertEquals("pay-123", result.getData().get("paymentId"));
            assertEquals(99.99, result.getData().get("amount"));
        }

        @Test
        @DisplayName("data should be unmodifiable")
        void dataShouldBeUnmodifiable() {
            final SagaStepResult result = SagaStepResult.success(Map.of("key", "value"));

            assertThrows(UnsupportedOperationException.class, () -> result.getData().put("new", "val"));
        }

        @Test
        @DisplayName("should reject null data")
        void shouldRejectNullData() {
            assertThrows(NullPointerException.class, () -> SagaStepResult.success(null));
        }
    }

    @Nested
    @DisplayName("failure()")
    class Failure {

        @Test
        @DisplayName("should have FAILURE status")
        void shouldHaveFailureStatus() {
            final SagaStepResult result = SagaStepResult.failure("Insufficient funds");

            assertEquals(SagaStepResult.Status.FAILURE, result.getStatus());
        }

        @Test
        @DisplayName("should return true for isFailure()")
        void shouldBeFailure() {
            assertTrue(SagaStepResult.failure("error").isFailure());
        }

        @Test
        @DisplayName("should return false for isSuccess() and isTimeout()")
        void shouldNotBeSuccessOrTimeout() {
            final SagaStepResult result = SagaStepResult.failure("error");

            assertFalse(result.isSuccess());
            assertFalse(result.isTimeout());
        }

        @Test
        @DisplayName("should carry error message")
        void shouldCarryErrorMessage() {
            final SagaStepResult result = SagaStepResult.failure("Insufficient funds");

            assertTrue(result.getErrorMessage().isPresent());
            assertEquals("Insufficient funds", result.getErrorMessage().get());
        }

        @Test
        @DisplayName("should reject null error message")
        void shouldRejectNullErrorMessage() {
            assertThrows(NullPointerException.class, () -> SagaStepResult.failure(null));
        }
    }

    @Nested
    @DisplayName("timeout()")
    class Timeout {

        @Test
        @DisplayName("should have TIMEOUT status")
        void shouldHaveTimeoutStatus() {
            final SagaStepResult result = SagaStepResult.timeout("Gateway timeout");

            assertEquals(SagaStepResult.Status.TIMEOUT, result.getStatus());
        }

        @Test
        @DisplayName("should return true for isTimeout()")
        void shouldBeTimeout() {
            assertTrue(SagaStepResult.timeout("timeout").isTimeout());
        }

        @Test
        @DisplayName("should return false for isSuccess() and isFailure()")
        void shouldNotBeSuccessOrFailure() {
            final SagaStepResult result = SagaStepResult.timeout("timeout");

            assertFalse(result.isSuccess());
            assertFalse(result.isFailure());
        }

        @Test
        @DisplayName("should carry error message")
        void shouldCarryErrorMessage() {
            final SagaStepResult result = SagaStepResult.timeout("Gateway timeout");

            assertEquals("Gateway timeout", result.getErrorMessage().get());
        }

        @Test
        @DisplayName("should reject null error message")
        void shouldRejectNullErrorMessage() {
            assertThrows(NullPointerException.class, () -> SagaStepResult.timeout(null));
        }
    }

    @Test
    @DisplayName("toString should include status")
    void toStringShouldIncludeStatus() {
        final String str = SagaStepResult.failure("err").toString();

        assertTrue(str.contains("FAILURE"));
        assertTrue(str.contains("err"));
    }
}
