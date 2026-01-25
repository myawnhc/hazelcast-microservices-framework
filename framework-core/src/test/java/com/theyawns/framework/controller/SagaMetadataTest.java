package com.theyawns.framework.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SagaMetadata.
 */
@DisplayName("SagaMetadata - Saga pattern support")
class SagaMetadataTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build with all required fields")
        void shouldBuildWithAllRequiredFields() {
            SagaMetadata saga = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .build();

            assertEquals("saga-123", saga.getSagaId());
            assertEquals("OrderFulfillment", saga.getSagaType());
        }

        @Test
        @DisplayName("should default stepNumber to 0")
        void shouldDefaultStepNumberToZero() {
            SagaMetadata saga = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .build();

            assertEquals(0, saga.getStepNumber());
        }

        @Test
        @DisplayName("should default isCompensating to false")
        void shouldDefaultIsCompensatingToFalse() {
            SagaMetadata saga = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .build();

            assertFalse(saga.isCompensating());
            assertFalse(saga.getIsCompensating());
        }

        @Test
        @DisplayName("should set custom stepNumber")
        void shouldSetCustomStepNumber() {
            SagaMetadata saga = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .stepNumber(5)
                    .build();

            assertEquals(5, saga.getStepNumber());
        }

        @Test
        @DisplayName("should set compensating flag")
        void shouldSetCompensatingFlag() {
            SagaMetadata saga = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .compensating(true)
                    .build();

            assertTrue(saga.isCompensating());
        }

        @Test
        @DisplayName("should throw when sagaId is null")
        void shouldThrowWhenSagaIdIsNull() {
            assertThrows(NullPointerException.class, () ->
                    SagaMetadata.builder()
                            .sagaType("OrderFulfillment")
                            .build()
            );
        }

        @Test
        @DisplayName("should throw when sagaType is null")
        void shouldThrowWhenSagaTypeIsNull() {
            assertThrows(NullPointerException.class, () ->
                    SagaMetadata.builder()
                            .sagaId("saga-123")
                            .build()
            );
        }
    }

    @Nested
    @DisplayName("toCompensating")
    class ToCompensating {

        @Test
        @DisplayName("should create new instance with isCompensating true")
        void shouldCreateWithIsCompensatingTrue() {
            SagaMetadata original = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .stepNumber(2)
                    .build();

            SagaMetadata compensating = original.toCompensating();

            assertTrue(compensating.isCompensating());
        }

        @Test
        @DisplayName("should preserve sagaId")
        void shouldPreserveSagaId() {
            SagaMetadata original = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .build();

            SagaMetadata compensating = original.toCompensating();

            assertEquals("saga-123", compensating.getSagaId());
        }

        @Test
        @DisplayName("should preserve sagaType")
        void shouldPreserveSagaType() {
            SagaMetadata original = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .build();

            SagaMetadata compensating = original.toCompensating();

            assertEquals("OrderFulfillment", compensating.getSagaType());
        }

        @Test
        @DisplayName("should preserve stepNumber")
        void shouldPreserveStepNumber() {
            SagaMetadata original = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .stepNumber(3)
                    .build();

            SagaMetadata compensating = original.toCompensating();

            assertEquals(3, compensating.getStepNumber());
        }

        @Test
        @DisplayName("should not modify original")
        void shouldNotModifyOriginal() {
            SagaMetadata original = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .build();

            original.toCompensating();

            assertFalse(original.isCompensating());
        }
    }

    @Nested
    @DisplayName("nextStep")
    class NextStep {

        @Test
        @DisplayName("should increment stepNumber")
        void shouldIncrementStepNumber() {
            SagaMetadata original = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .stepNumber(2)
                    .build();

            SagaMetadata next = original.nextStep();

            assertEquals(3, next.getStepNumber());
        }

        @Test
        @DisplayName("should preserve sagaId")
        void shouldPreserveSagaId() {
            SagaMetadata original = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .build();

            SagaMetadata next = original.nextStep();

            assertEquals("saga-123", next.getSagaId());
        }

        @Test
        @DisplayName("should preserve sagaType")
        void shouldPreserveSagaType() {
            SagaMetadata original = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .build();

            SagaMetadata next = original.nextStep();

            assertEquals("OrderFulfillment", next.getSagaType());
        }

        @Test
        @DisplayName("should reset isCompensating to false")
        void shouldResetIsCompensatingToFalse() {
            SagaMetadata original = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .compensating(true)
                    .build();

            SagaMetadata next = original.nextStep();

            assertFalse(next.isCompensating());
        }

        @Test
        @DisplayName("should not modify original")
        void shouldNotModifyOriginal() {
            SagaMetadata original = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .stepNumber(1)
                    .build();

            original.nextStep();

            assertEquals(1, original.getStepNumber());
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsAndHashCode {

        @Test
        @DisplayName("should be equal when all fields match")
        void shouldBeEqualWhenAllFieldsMatch() {
            SagaMetadata saga1 = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .stepNumber(2)
                    .compensating(true)
                    .build();

            SagaMetadata saga2 = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .stepNumber(2)
                    .compensating(true)
                    .build();

            assertEquals(saga1, saga2);
            assertEquals(saga1.hashCode(), saga2.hashCode());
        }

        @Test
        @DisplayName("should not be equal when sagaId differs")
        void shouldNotBeEqualWhenSagaIdDiffers() {
            SagaMetadata saga1 = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .build();

            SagaMetadata saga2 = SagaMetadata.builder()
                    .sagaId("saga-456")
                    .sagaType("OrderFulfillment")
                    .build();

            assertNotEquals(saga1, saga2);
        }

        @Test
        @DisplayName("should not be equal when sagaType differs")
        void shouldNotBeEqualWhenSagaTypeDiffers() {
            SagaMetadata saga1 = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .build();

            SagaMetadata saga2 = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("PaymentProcessing")
                    .build();

            assertNotEquals(saga1, saga2);
        }

        @Test
        @DisplayName("should not be equal when stepNumber differs")
        void shouldNotBeEqualWhenStepNumberDiffers() {
            SagaMetadata saga1 = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .stepNumber(1)
                    .build();

            SagaMetadata saga2 = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .stepNumber(2)
                    .build();

            assertNotEquals(saga1, saga2);
        }

        @Test
        @DisplayName("should not be equal when isCompensating differs")
        void shouldNotBeEqualWhenIsCompensatingDiffers() {
            SagaMetadata saga1 = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .compensating(false)
                    .build();

            SagaMetadata saga2 = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .compensating(true)
                    .build();

            assertNotEquals(saga1, saga2);
        }

        @Test
        @DisplayName("should be equal to itself")
        void shouldBeEqualToItself() {
            SagaMetadata saga = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .build();

            assertEquals(saga, saga);
        }

        @Test
        @DisplayName("should not be equal to null")
        void shouldNotBeEqualToNull() {
            SagaMetadata saga = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .build();

            assertNotEquals(null, saga);
        }

        @Test
        @DisplayName("should not be equal to different type")
        void shouldNotBeEqualToDifferentType() {
            SagaMetadata saga = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .build();

            assertNotEquals("not a saga", saga);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("should include all fields")
        void shouldIncludeAllFields() {
            SagaMetadata saga = SagaMetadata.builder()
                    .sagaId("saga-123")
                    .sagaType("OrderFulfillment")
                    .stepNumber(2)
                    .compensating(true)
                    .build();

            String str = saga.toString();

            assertTrue(str.contains("saga-123"));
            assertTrue(str.contains("OrderFulfillment"));
            assertTrue(str.contains("2"));
            assertTrue(str.contains("true"));
        }
    }
}
