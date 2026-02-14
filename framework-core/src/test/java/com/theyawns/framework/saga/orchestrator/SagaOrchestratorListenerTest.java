package com.theyawns.framework.saga.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Unit tests for SagaOrchestratorListener.
 */
@DisplayName("SagaOrchestratorListener - Default no-op callback methods")
class SagaOrchestratorListenerTest {

    private final SagaOrchestratorListener listener = new SagaOrchestratorListener() {
        // Use all defaults
    };

    private final SagaDefinition definition = SagaDefinition.builder()
            .name("TestSaga")
            .step("Step1")
                .action(ctx -> CompletableFuture.completedFuture(SagaStepResult.success()))
                .build()
            .build();

    @Test
    @DisplayName("onSagaStarted should be a no-op by default")
    void onSagaStartedShouldBeNoOp() {
        assertDoesNotThrow(() -> listener.onSagaStarted("saga-1", definition, SagaContext.create()));
    }

    @Test
    @DisplayName("onStepStarted should be a no-op by default")
    void onStepStartedShouldBeNoOp() {
        assertDoesNotThrow(() -> listener.onStepStarted("saga-1", "Step1", 0));
    }

    @Test
    @DisplayName("onStepCompleted should be a no-op by default")
    void onStepCompletedShouldBeNoOp() {
        assertDoesNotThrow(() -> listener.onStepCompleted("saga-1", "Step1", SagaStepResult.success()));
    }

    @Test
    @DisplayName("onCompensationStarted should be a no-op by default")
    void onCompensationStartedShouldBeNoOp() {
        assertDoesNotThrow(() -> listener.onCompensationStarted("saga-1", "Step2", 1));
    }

    @Test
    @DisplayName("onCompensationStepCompleted should be a no-op by default")
    void onCompensationStepCompletedShouldBeNoOp() {
        assertDoesNotThrow(() -> listener.onCompensationStepCompleted("saga-1", "Step1", SagaStepResult.success()));
    }

    @Test
    @DisplayName("onSagaCompleted should be a no-op by default")
    void onSagaCompletedShouldBeNoOp() {
        final SagaOrchestratorResult result = SagaOrchestratorResult.success(
                "saga-1", "TestSaga", Instant.now(), 1);

        assertDoesNotThrow(() -> listener.onSagaCompleted("saga-1", result));
    }
}
