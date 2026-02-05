package com.theyawns.framework.pipeline;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PipelineMetrics.
 */
@DisplayName("PipelineMetrics")
class PipelineMetricsTest {

    private MeterRegistry meterRegistry;
    private PipelineMetrics metrics;
    private static final String DOMAIN_NAME = "TestDomain";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new PipelineMetrics(meterRegistry, DOMAIN_NAME);
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should create metrics with valid parameters")
        void shouldCreateMetricsWithValidParameters() {
            assertNotNull(metrics);
            assertEquals(DOMAIN_NAME, metrics.getDomainName());
            assertEquals("eventsourcing.pipeline", metrics.getMetricsPrefix());
        }

        @Test
        @DisplayName("should throw on null meter registry")
        void shouldThrowOnNullMeterRegistry() {
            assertThrows(NullPointerException.class,
                    () -> new PipelineMetrics(null, DOMAIN_NAME));
        }

        @Test
        @DisplayName("should throw on null domain name")
        void shouldThrowOnNullDomainName() {
            assertThrows(NullPointerException.class,
                    () -> new PipelineMetrics(meterRegistry, null));
        }
    }

    @Nested
    @DisplayName("Event Counters")
    class EventCounterTests {

        @Test
        @DisplayName("should record event processed")
        void shouldRecordEventProcessed() {
            metrics.recordEventProcessed("CustomerCreated");

            Counter counter = meterRegistry.find("eventsourcing.pipeline.events.processed")
                    .tag("domain", DOMAIN_NAME)
                    .tag("eventType", "CustomerCreated")
                    .counter();

            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("should increment counter on multiple calls")
        void shouldIncrementCounterOnMultipleCalls() {
            metrics.recordEventProcessed("CustomerCreated");
            metrics.recordEventProcessed("CustomerCreated");
            metrics.recordEventProcessed("CustomerCreated");

            Counter counter = meterRegistry.find("eventsourcing.pipeline.events.processed")
                    .tag("domain", DOMAIN_NAME)
                    .tag("eventType", "CustomerCreated")
                    .counter();

            assertNotNull(counter);
            assertEquals(3.0, counter.count());
        }

        @Test
        @DisplayName("should record event failed with stage")
        void shouldRecordEventFailed() {
            metrics.recordEventFailed("CustomerCreated", PipelineMetrics.PipelineStage.PERSIST);

            Counter counter = meterRegistry.find("eventsourcing.pipeline.events.failed")
                    .tag("domain", DOMAIN_NAME)
                    .tag("eventType", "CustomerCreated")
                    .tag("stage", "persist")
                    .counter();

            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("should record event received")
        void shouldRecordEventReceived() {
            metrics.recordEventReceived("OrderCreated");

            Counter counter = meterRegistry.find("eventsourcing.pipeline.events.received")
                    .tag("domain", DOMAIN_NAME)
                    .tag("eventType", "OrderCreated")
                    .counter();

            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("should record view updated")
        void shouldRecordViewUpdated() {
            metrics.recordViewUpdated("ProductUpdated");

            Counter counter = meterRegistry.find("eventsourcing.pipeline.views.updated")
                    .tag("domain", DOMAIN_NAME)
                    .tag("eventType", "ProductUpdated")
                    .counter();

            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("should record event published")
        void shouldRecordEventPublished() {
            metrics.recordEventPublished("InventoryReserved");

            Counter counter = meterRegistry.find("eventsourcing.pipeline.events.published")
                    .tag("domain", DOMAIN_NAME)
                    .tag("eventType", "InventoryReserved")
                    .counter();

            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }
    }

    @Nested
    @DisplayName("Timing Metrics")
    class TimingTests {

        @Test
        @DisplayName("should record stage timing")
        void shouldRecordStageTiming() {
            Instant start = Instant.now().minusMillis(100);
            metrics.recordStageTiming(PipelineMetrics.PipelineStage.PERSIST, start);

            Timer timer = meterRegistry.find("eventsourcing.pipeline.stage.duration")
                    .tag("domain", DOMAIN_NAME)
                    .tag("stage", "persist")
                    .timer();

            assertNotNull(timer);
            assertEquals(1, timer.count());
            assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 100);
        }

        @Test
        @DisplayName("should record stage timing with event type")
        void shouldRecordStageTimingWithEventType() {
            Instant start = Instant.now().minusMillis(50);
            metrics.recordStageTiming(PipelineMetrics.PipelineStage.UPDATE_VIEW,
                    "CustomerCreated", start);

            Timer timer = meterRegistry.find("eventsourcing.pipeline.stage.duration")
                    .tag("domain", DOMAIN_NAME)
                    .tag("stage", "update_view")
                    .tag("eventType", "CustomerCreated")
                    .timer();

            assertNotNull(timer);
            assertEquals(1, timer.count());
        }

        @Test
        @DisplayName("should record end-to-end latency")
        void shouldRecordEndToEndLatency() {
            Instant submittedAt = Instant.now().minusMillis(200);
            metrics.recordEndToEndLatency("CustomerCreated", submittedAt);

            Timer timer = meterRegistry.find("eventsourcing.pipeline.latency.end_to_end")
                    .tag("domain", DOMAIN_NAME)
                    .tag("eventType", "CustomerCreated")
                    .timer();

            assertNotNull(timer);
            assertEquals(1, timer.count());
            assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 200);
        }

        @Test
        @DisplayName("should handle null submittedAt gracefully")
        void shouldHandleNullSubmittedAtGracefully() {
            // Should not throw
            metrics.recordEndToEndLatency("CustomerCreated", (Instant) null);

            Timer timer = meterRegistry.find("eventsourcing.pipeline.latency.end_to_end")
                    .tag("eventType", "CustomerCreated")
                    .timer();

            // Timer should not be created
            assertNull(timer);
        }

        @Test
        @DisplayName("should record queue wait time")
        void shouldRecordQueueWaitTime() {
            Instant submittedAt = Instant.now().minusMillis(150);
            Instant pipelineEntry = Instant.now().minusMillis(50);
            metrics.recordQueueWaitTime("CustomerCreated", submittedAt, pipelineEntry);

            Timer timer = meterRegistry.find("eventsourcing.pipeline.latency.queue_wait")
                    .tag("domain", DOMAIN_NAME)
                    .tag("eventType", "CustomerCreated")
                    .timer();

            assertNotNull(timer);
            assertEquals(1, timer.count());
        }

        @Test
        @DisplayName("should handle null times in queue wait gracefully")
        void shouldHandleNullTimesInQueueWait() {
            // Should not throw
            metrics.recordQueueWaitTime("CustomerCreated", null, Instant.now());
            metrics.recordQueueWaitTime("CustomerCreated", Instant.now(), null);

            Timer timer = meterRegistry.find("eventsourcing.pipeline.latency.queue_wait")
                    .timer();

            assertNull(timer);
        }
    }

    @Nested
    @DisplayName("Pipeline Stages")
    class PipelineStageTests {

        @Test
        @DisplayName("should have correct stage names")
        void shouldHaveCorrectStageNames() {
            assertEquals("source", PipelineMetrics.PipelineStage.SOURCE.getStageName());
            assertEquals("enrich", PipelineMetrics.PipelineStage.ENRICH.getStageName());
            assertEquals("persist", PipelineMetrics.PipelineStage.PERSIST.getStageName());
            assertEquals("update_view", PipelineMetrics.PipelineStage.UPDATE_VIEW.getStageName());
            assertEquals("publish", PipelineMetrics.PipelineStage.PUBLISH.getStageName());
            assertEquals("complete", PipelineMetrics.PipelineStage.COMPLETE.getStageName());
        }

        @Test
        @DisplayName("should have all six stages")
        void shouldHaveAllSixStages() {
            assertEquals(6, PipelineMetrics.PipelineStage.values().length);
        }
    }

    @Nested
    @DisplayName("Counter Caching")
    class CachingTests {

        @Test
        @DisplayName("should reuse cached counters")
        void shouldReuseCachedCounters() {
            // Record same event type multiple times
            metrics.recordEventProcessed("CustomerCreated");
            metrics.recordEventProcessed("CustomerCreated");

            // Should only have one counter registered
            long counterCount = meterRegistry.find("eventsourcing.pipeline.events.processed")
                    .counters()
                    .stream()
                    .filter(c -> "CustomerCreated".equals(c.getId().getTag("eventType")))
                    .count();

            assertEquals(1, counterCount);
        }

        @Test
        @DisplayName("should create separate counters for different event types")
        void shouldCreateSeparateCountersForDifferentEventTypes() {
            metrics.recordEventProcessed("CustomerCreated");
            metrics.recordEventProcessed("CustomerUpdated");
            metrics.recordEventProcessed("CustomerDeleted");

            long counterCount = meterRegistry.find("eventsourcing.pipeline.events.processed")
                    .counters()
                    .size();

            assertEquals(3, counterCount);
        }
    }
}
