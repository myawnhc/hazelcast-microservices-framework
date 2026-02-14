package com.theyawns.framework.saga.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SagaDefinition.
 */
@DisplayName("SagaDefinition - Ordered step collection with fluent DSL")
class SagaDefinitionTest {

    private static final SagaAction DUMMY_ACTION = ctx -> CompletableFuture.completedFuture(SagaStepResult.success());
    private static final SagaCompensation DUMMY_COMPENSATION =
            ctx -> CompletableFuture.completedFuture(SagaStepResult.success());

    @Nested
    @DisplayName("Fluent DSL builder")
    class FluentDsl {

        @Test
        @DisplayName("should build saga with single step via DSL")
        void shouldBuildWithSingleStep() {
            final SagaDefinition saga = SagaDefinition.builder()
                    .name("SimpleSaga")
                    .step("OnlyStep")
                        .action(DUMMY_ACTION)
                        .build()
                    .build();

            assertEquals("SimpleSaga", saga.getName());
            assertEquals(1, saga.getStepCount());
            assertEquals("OnlyStep", saga.getStep(0).getName());
        }

        @Test
        @DisplayName("should build saga with multiple steps via DSL")
        void shouldBuildWithMultipleSteps() {
            final SagaDefinition saga = SagaDefinition.builder()
                    .name("OrderFulfillment")
                    .step("ReserveStock")
                        .action(DUMMY_ACTION)
                        .compensation(DUMMY_COMPENSATION)
                        .timeout(Duration.ofSeconds(10))
                        .build()
                    .step("ProcessPayment")
                        .action(DUMMY_ACTION)
                        .compensation(DUMMY_COMPENSATION)
                        .timeout(Duration.ofSeconds(30))
                        .build()
                    .step("ConfirmOrder")
                        .action(DUMMY_ACTION)
                        .noCompensation()
                        .build()
                    .build();

            assertEquals("OrderFulfillment", saga.getName());
            assertEquals(3, saga.getStepCount());
            assertEquals("ReserveStock", saga.getStep(0).getName());
            assertEquals("ProcessPayment", saga.getStep(1).getName());
            assertEquals("ConfirmOrder", saga.getStep(2).getName());
        }

        @Test
        @DisplayName("step DSL should support all step builder options")
        void stepDslShouldSupportAllOptions() {
            final SagaDefinition saga = SagaDefinition.builder()
                    .name("FullOptions")
                    .step("Step1")
                        .action(DUMMY_ACTION)
                        .compensation(DUMMY_COMPENSATION)
                        .timeout(Duration.ofSeconds(15))
                        .maxRetries(3)
                        .retryDelay(Duration.ofSeconds(2))
                        .build()
                    .build();

            final SagaStep step = saga.getStep(0);
            assertEquals(Duration.ofSeconds(15), step.getTimeout());
            assertEquals(3, step.getMaxRetries());
            assertEquals(Duration.ofSeconds(2), step.getRetryDelay());
            assertTrue(step.hasCompensation());
        }
    }

    @Nested
    @DisplayName("addStep")
    class AddStep {

        @Test
        @DisplayName("should add pre-built steps")
        void shouldAddPreBuiltSteps() {
            final SagaStep preBuilt = SagaStep.builder("PreBuiltStep")
                    .action(DUMMY_ACTION)
                    .build();

            final SagaDefinition saga = SagaDefinition.builder()
                    .name("PreBuiltSaga")
                    .addStep(preBuilt)
                    .build();

            assertEquals(1, saga.getStepCount());
            assertEquals("PreBuiltStep", saga.getStep(0).getName());
        }

        @Test
        @DisplayName("should mix pre-built and DSL steps")
        void shouldMixPreBuiltAndDsl() {
            final SagaStep preBuilt = SagaStep.builder("PreBuilt")
                    .action(DUMMY_ACTION)
                    .build();

            final SagaDefinition saga = SagaDefinition.builder()
                    .name("Mixed")
                    .addStep(preBuilt)
                    .step("DslStep")
                        .action(DUMMY_ACTION)
                        .build()
                    .build();

            assertEquals(2, saga.getStepCount());
            assertEquals("PreBuilt", saga.getStep(0).getName());
            assertEquals("DslStep", saga.getStep(1).getName());
        }

        @Test
        @DisplayName("should reject null step")
        void shouldRejectNullStep() {
            assertThrows(NullPointerException.class,
                    () -> SagaDefinition.builder().addStep(null));
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("should fail without name")
        void shouldFailWithoutName() {
            final SagaDefinition.Builder builder = SagaDefinition.builder()
                    .step("Step1").action(DUMMY_ACTION).build();

            assertThrows(IllegalStateException.class, builder::build);
        }

        @Test
        @DisplayName("should reject null name")
        void shouldRejectNullName() {
            assertThrows(NullPointerException.class,
                    () -> SagaDefinition.builder().name(null));
        }

        @Test
        @DisplayName("should reject blank name")
        void shouldRejectBlankName() {
            assertThrows(IllegalArgumentException.class,
                    () -> SagaDefinition.builder().name("  "));
        }

        @Test
        @DisplayName("should fail without steps")
        void shouldFailWithoutSteps() {
            final SagaDefinition.Builder builder = SagaDefinition.builder()
                    .name("EmptySaga");

            assertThrows(IllegalStateException.class, builder::build);
        }

        @Test
        @DisplayName("should fail with duplicate step names")
        void shouldFailWithDuplicateStepNames() {
            final SagaDefinition.Builder builder = SagaDefinition.builder()
                    .name("DupSaga")
                    .step("SameName").action(DUMMY_ACTION).build()
                    .step("SameName").action(DUMMY_ACTION).build();

            assertThrows(IllegalStateException.class, builder::build);
        }

        @Test
        @DisplayName("should reject null step name in DSL")
        void shouldRejectNullStepName() {
            assertThrows(NullPointerException.class,
                    () -> SagaDefinition.builder().step(null));
        }

        @Test
        @DisplayName("should reject blank step name in DSL")
        void shouldRejectBlankStepName() {
            assertThrows(IllegalArgumentException.class,
                    () -> SagaDefinition.builder().step("  "));
        }
    }

    @Nested
    @DisplayName("Step access")
    class StepAccess {

        private final SagaDefinition saga = SagaDefinition.builder()
                .name("TestSaga")
                .step("Step1").action(DUMMY_ACTION).build()
                .step("Step2").action(DUMMY_ACTION).build()
                .step("Step3").action(DUMMY_ACTION).build()
                .build();

        @Test
        @DisplayName("getStep(int) should return step by index")
        void getStepByIndex() {
            assertEquals("Step2", saga.getStep(1).getName());
        }

        @Test
        @DisplayName("getStep(int) should throw for out of bounds")
        void getStepByIndexOutOfBounds() {
            assertThrows(IndexOutOfBoundsException.class, () -> saga.getStep(5));
        }

        @Test
        @DisplayName("getStep(String) should return step by name")
        void getStepByName() {
            assertTrue(saga.getStep("Step2").isPresent());
            assertEquals("Step2", saga.getStep("Step2").get().getName());
        }

        @Test
        @DisplayName("getStep(String) should return empty for unknown name")
        void getStepByNameNotFound() {
            assertTrue(saga.getStep("NonExistent").isEmpty());
        }

        @Test
        @DisplayName("getStep(String) should reject null")
        void getStepByNameRejectNull() {
            assertThrows(NullPointerException.class, () -> saga.getStep((String) null));
        }

        @Test
        @DisplayName("getSteps should return unmodifiable list")
        void getStepsShouldBeUnmodifiable() {
            final List<SagaStep> steps = saga.getSteps();

            assertThrows(UnsupportedOperationException.class,
                    () -> steps.add(SagaStep.builder("New").action(DUMMY_ACTION).build()));
        }
    }

    @Nested
    @DisplayName("Saga timeout")
    class SagaTimeout {

        @Test
        @DisplayName("should have no saga timeout by default")
        void shouldHaveNoDefaultTimeout() {
            final SagaDefinition saga = SagaDefinition.builder()
                    .name("NoTimeout")
                    .step("Step1").action(DUMMY_ACTION).build()
                    .build();

            assertTrue(saga.getSagaTimeout().isEmpty());
        }

        @Test
        @DisplayName("should set saga timeout")
        void shouldSetSagaTimeout() {
            final SagaDefinition saga = SagaDefinition.builder()
                    .name("WithTimeout")
                    .step("Step1").action(DUMMY_ACTION).build()
                    .sagaTimeout(Duration.ofMinutes(5))
                    .build();

            assertTrue(saga.getSagaTimeout().isPresent());
            assertEquals(Duration.ofMinutes(5), saga.getSagaTimeout().get());
        }

        @Test
        @DisplayName("should reject zero saga timeout")
        void shouldRejectZeroTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> SagaDefinition.builder().sagaTimeout(Duration.ZERO));
        }

        @Test
        @DisplayName("should reject negative saga timeout")
        void shouldRejectNegativeTimeout() {
            assertThrows(IllegalArgumentException.class,
                    () -> SagaDefinition.builder().sagaTimeout(Duration.ofSeconds(-1)));
        }
    }

    @Nested
    @DisplayName("computeTotalStepTimeout")
    class ComputeTotalStepTimeout {

        @Test
        @DisplayName("should sum all step timeouts")
        void shouldSumAllStepTimeouts() {
            final SagaDefinition saga = SagaDefinition.builder()
                    .name("TimedSaga")
                    .step("Step1").action(DUMMY_ACTION).timeout(Duration.ofSeconds(10)).build()
                    .step("Step2").action(DUMMY_ACTION).timeout(Duration.ofSeconds(20)).build()
                    .step("Step3").action(DUMMY_ACTION).timeout(Duration.ofSeconds(30)).build()
                    .build();

            assertEquals(Duration.ofSeconds(60), saga.computeTotalStepTimeout());
        }

        @Test
        @DisplayName("should use default step timeout when not specified")
        void shouldUseDefaultStepTimeout() {
            final SagaDefinition saga = SagaDefinition.builder()
                    .name("DefaultTimeouts")
                    .step("Step1").action(DUMMY_ACTION).build()
                    .step("Step2").action(DUMMY_ACTION).build()
                    .build();

            // Default is 30s per step
            assertEquals(Duration.ofSeconds(60), saga.computeTotalStepTimeout());
        }
    }

    @Test
    @DisplayName("toString should include name and step count")
    void toStringShouldIncludeNameAndStepCount() {
        final SagaDefinition saga = SagaDefinition.builder()
                .name("TestSaga")
                .step("Step1").action(DUMMY_ACTION).build()
                .build();

        final String str = saga.toString();

        assertTrue(str.contains("TestSaga"));
        assertTrue(str.contains("steps=1"));
    }
}
