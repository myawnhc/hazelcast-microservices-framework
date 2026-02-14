package com.theyawns.framework.saga.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SagaStep.
 */
@DisplayName("SagaStep - Immutable step definition with builder")
class SagaStepTest {

    private static final SagaAction DUMMY_ACTION = ctx -> CompletableFuture.completedFuture(SagaStepResult.success());
    private static final SagaCompensation DUMMY_COMPENSATION =
            ctx -> CompletableFuture.completedFuture(SagaStepResult.success());

    @Nested
    @DisplayName("Builder basics")
    class BuilderBasics {

        @Test
        @DisplayName("should build step with name and action")
        void shouldBuildWithNameAndAction() {
            final SagaStep step = SagaStep.builder("ReserveStock")
                    .action(DUMMY_ACTION)
                    .build();

            assertEquals("ReserveStock", step.getName());
            assertNotNull(step.getAction());
        }

        @Test
        @DisplayName("should build step with all fields")
        void shouldBuildWithAllFields() {
            final SagaStep step = SagaStep.builder("ProcessPayment")
                    .action(DUMMY_ACTION)
                    .compensation(DUMMY_COMPENSATION)
                    .timeout(Duration.ofSeconds(15))
                    .maxRetries(3)
                    .retryDelay(Duration.ofSeconds(2))
                    .build();

            assertEquals("ProcessPayment", step.getName());
            assertTrue(step.hasCompensation());
            assertEquals(Duration.ofSeconds(15), step.getTimeout());
            assertEquals(3, step.getMaxRetries());
            assertEquals(Duration.ofSeconds(2), step.getRetryDelay());
        }

        @Test
        @DisplayName("should fail if action is not set")
        void shouldFailWithoutAction() {
            final SagaStep.Builder builder = SagaStep.builder("NoAction");

            assertThrows(IllegalStateException.class, builder::build);
        }

        @Test
        @DisplayName("should reject null name")
        void shouldRejectNullName() {
            assertThrows(NullPointerException.class, () -> SagaStep.builder(null));
        }

        @Test
        @DisplayName("should reject blank name")
        void shouldRejectBlankName() {
            assertThrows(IllegalArgumentException.class, () -> SagaStep.builder("  "));
        }

        @Test
        @DisplayName("should reject null action")
        void shouldRejectNullAction() {
            assertThrows(NullPointerException.class,
                    () -> SagaStep.builder("Step").action(null));
        }
    }

    @Nested
    @DisplayName("Defaults")
    class Defaults {

        @Test
        @DisplayName("should default timeout to 30 seconds")
        void shouldDefaultTimeout() {
            final SagaStep step = SagaStep.builder("Step")
                    .action(DUMMY_ACTION)
                    .build();

            assertEquals(Duration.ofSeconds(30), step.getTimeout());
        }

        @Test
        @DisplayName("should default maxRetries to 0")
        void shouldDefaultMaxRetries() {
            final SagaStep step = SagaStep.builder("Step")
                    .action(DUMMY_ACTION)
                    .build();

            assertEquals(0, step.getMaxRetries());
        }

        @Test
        @DisplayName("should default retryDelay to 500ms")
        void shouldDefaultRetryDelay() {
            final SagaStep step = SagaStep.builder("Step")
                    .action(DUMMY_ACTION)
                    .build();

            assertEquals(Duration.ofMillis(500), step.getRetryDelay());
        }

        @Test
        @DisplayName("should default to no compensation")
        void shouldDefaultToNoCompensation() {
            final SagaStep step = SagaStep.builder("Step")
                    .action(DUMMY_ACTION)
                    .build();

            assertFalse(step.hasCompensation());
            assertTrue(step.getCompensation().isEmpty());
        }
    }

    @Nested
    @DisplayName("Compensation")
    class Compensation {

        @Test
        @DisplayName("should have compensation when set")
        void shouldHaveCompensation() {
            final SagaStep step = SagaStep.builder("Step")
                    .action(DUMMY_ACTION)
                    .compensation(DUMMY_COMPENSATION)
                    .build();

            assertTrue(step.hasCompensation());
            assertTrue(step.getCompensation().isPresent());
        }

        @Test
        @DisplayName("noCompensation should explicitly clear compensation")
        void noCompensationShouldClear() {
            final SagaStep step = SagaStep.builder("Step")
                    .action(DUMMY_ACTION)
                    .compensation(DUMMY_COMPENSATION)
                    .noCompensation()
                    .build();

            assertFalse(step.hasCompensation());
        }

        @Test
        @DisplayName("should reject null compensation")
        void shouldRejectNullCompensation() {
            assertThrows(NullPointerException.class,
                    () -> SagaStep.builder("Step").compensation(null));
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("should reject zero timeout")
        void shouldRejectZeroTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> SagaStep.builder("Step").timeout(Duration.ZERO));
        }

        @Test
        @DisplayName("should reject negative timeout")
        void shouldRejectNegativeTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> SagaStep.builder("Step").timeout(Duration.ofSeconds(-1)));
        }

        @Test
        @DisplayName("should reject negative maxRetries")
        void shouldRejectNegativeMaxRetries() {
            assertThrows(IllegalArgumentException.class,
                    () -> SagaStep.builder("Step").maxRetries(-1));
        }

        @Test
        @DisplayName("should reject negative retryDelay")
        void shouldRejectNegativeRetryDelay() {
            assertThrows(IllegalArgumentException.class,
                    () -> SagaStep.builder("Step").retryDelay(Duration.ofMillis(-1)));
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("should equal by name")
        void shouldEqualByName() {
            final SagaStep step1 = SagaStep.builder("ReserveStock").action(DUMMY_ACTION).build();
            final SagaStep step2 = SagaStep.builder("ReserveStock").action(DUMMY_ACTION)
                    .timeout(Duration.ofSeconds(99)).build();

            assertEquals(step1, step2);
            assertEquals(step1.hashCode(), step2.hashCode());
        }

        @Test
        @DisplayName("should not equal different names")
        void shouldNotEqualDifferentNames() {
            final SagaStep step1 = SagaStep.builder("ReserveStock").action(DUMMY_ACTION).build();
            final SagaStep step2 = SagaStep.builder("ProcessPayment").action(DUMMY_ACTION).build();

            assertNotEquals(step1, step2);
        }
    }

    @Test
    @DisplayName("toString should include name and compensation flag")
    void toStringShouldIncludeNameAndCompensation() {
        final SagaStep step = SagaStep.builder("ReserveStock")
                .action(DUMMY_ACTION)
                .compensation(DUMMY_COMPENSATION)
                .build();

        final String str = step.toString();

        assertTrue(str.contains("ReserveStock"));
        assertTrue(str.contains("hasCompensation=true"));
    }
}
