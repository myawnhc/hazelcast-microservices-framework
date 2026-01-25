package com.theyawns.framework.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EventSourcingPipeline.
 *
 * Note: Full pipeline testing requires a running Hazelcast instance and is
 * covered by integration tests. This class tests the builder validation logic.
 */
@DisplayName("EventSourcingPipeline")
class EventSourcingPipelineTest {

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidationTests {

        @Test
        @DisplayName("should throw on missing hazelcast")
        void shouldThrowOnMissingHazelcast() {
            assertThrows(NullPointerException.class, () ->
                    EventSourcingPipeline.builder()
                            .domainName("TestDomain")
                            .build());
        }

        @Test
        @DisplayName("should throw on missing domain name")
        void shouldThrowOnMissingDomainName() {
            assertThrows(NullPointerException.class, () ->
                    EventSourcingPipeline.builder()
                            .build());
        }

        @Test
        @DisplayName("builder should be fluent")
        void builderShouldBeFluent() {
            // Just verify the builder returns itself for chaining
            EventSourcingPipeline.Builder<?, ?, ?> builder = EventSourcingPipeline.builder();
            assertSame(builder, builder.domainName("Test"));
        }
    }
}
