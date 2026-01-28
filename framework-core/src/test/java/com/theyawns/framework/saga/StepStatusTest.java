package com.theyawns.framework.saga;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StepStatus enum.
 */
@DisplayName("StepStatus - Saga step lifecycle states")
class StepStatusTest {

    @Nested
    @DisplayName("needsCompensation")
    class NeedsCompensation {

        @Test
        @DisplayName("COMPLETED should need compensation")
        void completedShouldNeedCompensation() {
            assertTrue(StepStatus.COMPLETED.needsCompensation());
        }

        @Test
        @DisplayName("PENDING should not need compensation")
        void pendingShouldNotNeedCompensation() {
            assertFalse(StepStatus.PENDING.needsCompensation());
        }

        @Test
        @DisplayName("FAILED should not need compensation")
        void failedShouldNotNeedCompensation() {
            assertFalse(StepStatus.FAILED.needsCompensation());
        }

        @Test
        @DisplayName("SKIPPED should not need compensation")
        void skippedShouldNotNeedCompensation() {
            assertFalse(StepStatus.SKIPPED.needsCompensation());
        }

        @Test
        @DisplayName("COMPENSATED should not need compensation")
        void compensatedShouldNotNeedCompensation() {
            assertFalse(StepStatus.COMPENSATED.needsCompensation());
        }
    }

    @Nested
    @DisplayName("isTerminal")
    class IsTerminal {

        @Test
        @DisplayName("PENDING should not be terminal")
        void pendingShouldNotBeTerminal() {
            assertFalse(StepStatus.PENDING.isTerminal());
        }

        @Test
        @DisplayName("COMPLETED should be terminal")
        void completedShouldBeTerminal() {
            assertTrue(StepStatus.COMPLETED.isTerminal());
        }

        @Test
        @DisplayName("FAILED should be terminal")
        void failedShouldBeTerminal() {
            assertTrue(StepStatus.FAILED.isTerminal());
        }

        @Test
        @DisplayName("SKIPPED should be terminal")
        void skippedShouldBeTerminal() {
            assertTrue(StepStatus.SKIPPED.isTerminal());
        }

        @Test
        @DisplayName("COMPENSATED should be terminal")
        void compensatedShouldBeTerminal() {
            assertTrue(StepStatus.COMPENSATED.isTerminal());
        }
    }
}
