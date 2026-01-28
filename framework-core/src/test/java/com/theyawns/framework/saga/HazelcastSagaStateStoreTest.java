package com.theyawns.framework.saga;

import com.hazelcast.config.Config;
import com.hazelcast.config.IndexConfig;
import com.hazelcast.config.IndexType;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for HazelcastSagaStateStore.
 */
@DisplayName("HazelcastSagaStateStore - Distributed saga state tracking")
class HazelcastSagaStateStoreTest {

    private static HazelcastInstance hazelcast;
    private HazelcastSagaStateStore sagaStateStore;

    @BeforeAll
    static void setUpClass() {
        Config config = new Config();
        config.setClusterName("saga-test-cluster-" + System.currentTimeMillis());
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(true).addMember("127.0.0.1");

        // Configure indexes for saga-state map
        config.getMapConfig("saga-state")
                .addIndexConfig(new IndexConfig(IndexType.HASH, "status"))
                .addIndexConfig(new IndexConfig(IndexType.HASH, "correlationId"))
                .addIndexConfig(new IndexConfig(IndexType.HASH, "sagaType"))
                .addIndexConfig(new IndexConfig(IndexType.SORTED, "deadline"));

        hazelcast = Hazelcast.newHazelcastInstance(config);
    }

    @AfterAll
    static void tearDownClass() {
        if (hazelcast != null) {
            hazelcast.shutdown();
        }
    }

    @BeforeEach
    void setUp() {
        sagaStateStore = new HazelcastSagaStateStore(hazelcast, null);
    }

    @AfterEach
    void tearDown() {
        sagaStateStore.getSagaMap().clear();
    }

    @Nested
    @DisplayName("Start saga")
    class StartSaga {

        @Test
        @DisplayName("should create saga with correct initial state")
        void shouldCreateSagaWithCorrectInitialState() {
            SagaState saga = sagaStateStore.startSaga("saga-123", "OrderFulfillment",
                    "corr-456", 4, Duration.ofSeconds(30));

            assertEquals("saga-123", saga.getSagaId());
            assertEquals("OrderFulfillment", saga.getSagaType());
            assertEquals("corr-456", saga.getCorrelationId());
            assertEquals(SagaStatus.STARTED, saga.getStatus());
            assertEquals(4, saga.getTotalSteps());
            assertEquals(0, saga.getCurrentStep());
        }

        @Test
        @DisplayName("should persist saga to map")
        void shouldPersistSagaToMap() {
            sagaStateStore.startSaga("saga-123", "OrderFulfillment",
                    "corr-456", 4, Duration.ofSeconds(30));

            Optional<SagaState> retrieved = sagaStateStore.getSagaState("saga-123");

            assertTrue(retrieved.isPresent());
            assertEquals("saga-123", retrieved.get().getSagaId());
        }

        @Test
        @DisplayName("should work without correlation ID")
        void shouldWorkWithoutCorrelationId() {
            SagaState saga = sagaStateStore.startSaga("saga-123", "OrderFulfillment",
                    4, Duration.ofSeconds(30));

            assertNotNull(saga);
            assertNull(saga.getCorrelationId());
        }
    }

    @Nested
    @DisplayName("Record step completed")
    class RecordStepCompleted {

        @Test
        @DisplayName("should update saga state")
        void shouldUpdateSagaState() {
            sagaStateStore.startSaga("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            SagaState updated = sagaStateStore.recordStepCompleted("saga-123", 0,
                    "OrderCreated", "order-service", "evt-1");

            assertEquals(1, updated.getCurrentStep());
            assertEquals(SagaStatus.IN_PROGRESS, updated.getStatus());
        }

        @Test
        @DisplayName("should persist updated state")
        void shouldPersistUpdatedState() {
            sagaStateStore.startSaga("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));
            sagaStateStore.recordStepCompleted("saga-123", 0, "OrderCreated", "order-service", "evt-1");

            Optional<SagaState> retrieved = sagaStateStore.getSagaState("saga-123");

            assertTrue(retrieved.isPresent());
            assertEquals(1, retrieved.get().getCurrentStep());
            assertEquals(1, retrieved.get().getSteps().size());
        }

        @Test
        @DisplayName("should complete saga on final step")
        void shouldCompleteSagaOnFinalStep() {
            sagaStateStore.startSaga("saga-123", "OrderFulfillment", 2, Duration.ofSeconds(30));
            sagaStateStore.recordStepCompleted("saga-123", 0, "Step1", "service", "evt-1");

            SagaState completed = sagaStateStore.recordStepCompleted("saga-123", 1,
                    "Step2", "service", "evt-2");

            assertEquals(SagaStatus.COMPLETED, completed.getStatus());
        }

        @Test
        @DisplayName("should throw when saga not found")
        void shouldThrowWhenSagaNotFound() {
            assertThrows(IllegalArgumentException.class, () ->
                    sagaStateStore.recordStepCompleted("nonexistent", 0, "Event", "service", "evt-1")
            );
        }
    }

    @Nested
    @DisplayName("Record step failed")
    class RecordStepFailed {

        @Test
        @DisplayName("should change status to COMPENSATING")
        void shouldChangeStatusToCompensating() {
            sagaStateStore.startSaga("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            SagaState failed = sagaStateStore.recordStepFailed("saga-123", 1,
                    "PaymentFailed", "payment-service", "Insufficient funds");

            assertEquals(SagaStatus.COMPENSATING, failed.getStatus());
            assertEquals("Insufficient funds", failed.getFailureReason());
        }

        @Test
        @DisplayName("should persist failure information")
        void shouldPersistFailureInformation() {
            sagaStateStore.startSaga("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));
            sagaStateStore.recordStepFailed("saga-123", 1, "PaymentFailed",
                    "payment-service", "Card declined");

            Optional<SagaState> retrieved = sagaStateStore.getSagaState("saga-123");

            assertTrue(retrieved.isPresent());
            assertEquals("Card declined", retrieved.get().getFailureReason());
            assertEquals(1, retrieved.get().getFailedAtStep());
        }
    }

    @Nested
    @DisplayName("Compensation")
    class Compensation {

        @Test
        @DisplayName("recordCompensationStarted should update status")
        void recordCompensationStartedShouldUpdateStatus() {
            sagaStateStore.startSaga("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            SagaState compensating = sagaStateStore.recordCompensationStarted("saga-123");

            assertEquals(SagaStatus.COMPENSATING, compensating.getStatus());
        }

        @Test
        @DisplayName("recordCompensationStep should mark step as compensated")
        void recordCompensationStepShouldMarkStepAsCompensated() {
            sagaStateStore.startSaga("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));
            sagaStateStore.recordStepCompleted("saga-123", 0, "OrderCreated", "order-service", "evt-1");
            sagaStateStore.recordStepCompleted("saga-123", 1, "StockReserved", "inventory-service", "evt-2");
            sagaStateStore.recordCompensationStarted("saga-123");

            SagaState compensated = sagaStateStore.recordCompensationStep("saga-123", 1,
                    "StockReleased", "inventory-service");

            Optional<SagaStepRecord> step = compensated.getSteps().stream()
                    .filter(s -> s.getStepNumber() == 1)
                    .findFirst();
            assertTrue(step.isPresent());
            assertEquals(StepStatus.COMPENSATED, step.get().getStatus());
        }
    }

    @Nested
    @DisplayName("Complete saga")
    class CompleteSaga {

        @Test
        @DisplayName("should update status and set completedAt")
        void shouldUpdateStatusAndSetCompletedAt() {
            sagaStateStore.startSaga("saga-123", "OrderFulfillment", 4, Duration.ofSeconds(30));

            SagaState completed = sagaStateStore.completeSaga("saga-123", SagaStatus.COMPENSATED);

            assertEquals(SagaStatus.COMPENSATED, completed.getStatus());
            assertNotNull(completed.getCompletedAt());
        }

        @Test
        @DisplayName("should throw when saga not found")
        void shouldThrowWhenSagaNotFound() {
            assertThrows(IllegalArgumentException.class, () ->
                    sagaStateStore.completeSaga("nonexistent", SagaStatus.COMPLETED)
            );
        }
    }

    @Nested
    @DisplayName("Query by status")
    class QueryByStatus {

        @Test
        @DisplayName("should find sagas by status")
        void shouldFindSagasByStatus() {
            sagaStateStore.startSaga("saga-1", "OrderFulfillment", 4, Duration.ofSeconds(30));
            sagaStateStore.startSaga("saga-2", "OrderFulfillment", 4, Duration.ofSeconds(30));
            sagaStateStore.recordStepCompleted("saga-1", 0, "Event", "service", "evt-1");

            List<SagaState> started = sagaStateStore.findSagasByStatus(SagaStatus.STARTED);
            List<SagaState> inProgress = sagaStateStore.findSagasByStatus(SagaStatus.IN_PROGRESS);

            assertEquals(1, started.size());
            assertEquals("saga-2", started.get(0).getSagaId());
            assertEquals(1, inProgress.size());
            assertEquals("saga-1", inProgress.get(0).getSagaId());
        }

        @Test
        @DisplayName("should return empty list when no matches")
        void shouldReturnEmptyListWhenNoMatches() {
            sagaStateStore.startSaga("saga-1", "OrderFulfillment", 4, Duration.ofSeconds(30));

            List<SagaState> completed = sagaStateStore.findSagasByStatus(SagaStatus.COMPLETED);

            assertTrue(completed.isEmpty());
        }
    }

    @Nested
    @DisplayName("Query by correlation ID")
    class QueryByCorrelationId {

        @Test
        @DisplayName("should find sagas by correlation ID")
        void shouldFindSagasByCorrelationId() {
            sagaStateStore.startSaga("saga-1", "OrderFulfillment", "corr-123", 4, Duration.ofSeconds(30));
            sagaStateStore.startSaga("saga-2", "OrderFulfillment", "corr-123", 4, Duration.ofSeconds(30));
            sagaStateStore.startSaga("saga-3", "OrderFulfillment", "corr-456", 4, Duration.ofSeconds(30));

            List<SagaState> sagas = sagaStateStore.findSagasByCorrelationId("corr-123");

            assertEquals(2, sagas.size());
            assertTrue(sagas.stream().allMatch(s -> "corr-123".equals(s.getCorrelationId())));
        }
    }

    @Nested
    @DisplayName("Query by type")
    class QueryByType {

        @Test
        @DisplayName("should find sagas by type")
        void shouldFindSagasByType() {
            sagaStateStore.startSaga("saga-1", "OrderFulfillment", 4, Duration.ofSeconds(30));
            sagaStateStore.startSaga("saga-2", "OrderFulfillment", 4, Duration.ofSeconds(30));
            sagaStateStore.startSaga("saga-3", "PaymentProcessing", 3, Duration.ofSeconds(30));

            List<SagaState> orderSagas = sagaStateStore.findSagasByType("OrderFulfillment");

            assertEquals(2, orderSagas.size());
            assertTrue(orderSagas.stream().allMatch(s -> "OrderFulfillment".equals(s.getSagaType())));
        }
    }

    @Nested
    @DisplayName("Find timed out sagas")
    class FindTimedOutSagas {

        @Test
        @DisplayName("should find sagas past deadline")
        void shouldFindSagasPastDeadline() {
            // Create saga with very short timeout
            sagaStateStore.startSaga("saga-1", "OrderFulfillment", 4, Duration.ofMillis(1));

            // Wait for timeout
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            List<SagaState> timedOut = sagaStateStore.findTimedOutSagas();

            assertEquals(1, timedOut.size());
            assertEquals("saga-1", timedOut.get(0).getSagaId());
        }

        @Test
        @DisplayName("should not return completed sagas")
        void shouldNotReturnCompletedSagas() {
            sagaStateStore.startSaga("saga-1", "OrderFulfillment", 4, Duration.ofMillis(1));
            sagaStateStore.completeSaga("saga-1", SagaStatus.COMPLETED);

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            List<SagaState> timedOut = sagaStateStore.findTimedOutSagas();

            assertTrue(timedOut.isEmpty());
        }
    }

    @Nested
    @DisplayName("Counts")
    class Counts {

        @Test
        @DisplayName("count should return total sagas")
        void countShouldReturnTotalSagas() {
            sagaStateStore.startSaga("saga-1", "OrderFulfillment", 4, Duration.ofSeconds(30));
            sagaStateStore.startSaga("saga-2", "OrderFulfillment", 4, Duration.ofSeconds(30));
            sagaStateStore.startSaga("saga-3", "OrderFulfillment", 4, Duration.ofSeconds(30));

            assertEquals(3, sagaStateStore.count());
        }

        @Test
        @DisplayName("countByStatus should return correct count")
        void countByStatusShouldReturnCorrectCount() {
            sagaStateStore.startSaga("saga-1", "OrderFulfillment", 4, Duration.ofSeconds(30));
            sagaStateStore.startSaga("saga-2", "OrderFulfillment", 4, Duration.ofSeconds(30));
            sagaStateStore.recordStepCompleted("saga-1", 0, "Event", "service", "evt-1");

            assertEquals(1, sagaStateStore.countByStatus(SagaStatus.STARTED));
            assertEquals(1, sagaStateStore.countByStatus(SagaStatus.IN_PROGRESS));
            assertEquals(0, sagaStateStore.countByStatus(SagaStatus.COMPLETED));
        }
    }

    @Nested
    @DisplayName("Purge completed sagas")
    class PurgeCompletedSagas {

        @Test
        @DisplayName("should remove old completed sagas")
        void shouldRemoveOldCompletedSagas() {
            sagaStateStore.startSaga("saga-1", "OrderFulfillment", 1, Duration.ofSeconds(30));
            sagaStateStore.recordStepCompleted("saga-1", 0, "Event", "service", "evt-1");

            // Saga is now completed, purge with 0 duration should remove it
            int removed = sagaStateStore.purgeCompletedSagas(Duration.ZERO);

            assertEquals(1, removed);
            assertTrue(sagaStateStore.getSagaState("saga-1").isEmpty());
        }

        @Test
        @DisplayName("should not remove active sagas")
        void shouldNotRemoveActiveSagas() {
            sagaStateStore.startSaga("saga-1", "OrderFulfillment", 4, Duration.ofSeconds(30));

            int removed = sagaStateStore.purgeCompletedSagas(Duration.ZERO);

            assertEquals(0, removed);
            assertTrue(sagaStateStore.getSagaState("saga-1").isPresent());
        }
    }

    @Nested
    @DisplayName("Full saga lifecycle")
    class FullSagaLifecycle {

        @Test
        @DisplayName("should track happy path saga")
        void shouldTrackHappyPathSaga() {
            // Start saga
            SagaState saga = sagaStateStore.startSaga("saga-123", "OrderFulfillment",
                    "corr-456", 4, Duration.ofSeconds(30));
            assertEquals(SagaStatus.STARTED, saga.getStatus());

            // Step 1: Order created
            saga = sagaStateStore.recordStepCompleted("saga-123", 0,
                    "OrderCreated", "order-service", "evt-1");
            assertEquals(SagaStatus.IN_PROGRESS, saga.getStatus());
            assertEquals(1, saga.getCurrentStep());

            // Step 2: Stock reserved
            saga = sagaStateStore.recordStepCompleted("saga-123", 1,
                    "StockReserved", "inventory-service", "evt-2");
            assertEquals(2, saga.getCurrentStep());

            // Step 3: Payment processed
            saga = sagaStateStore.recordStepCompleted("saga-123", 2,
                    "PaymentProcessed", "payment-service", "evt-3");
            assertEquals(3, saga.getCurrentStep());

            // Step 4: Order confirmed (final step)
            saga = sagaStateStore.recordStepCompleted("saga-123", 3,
                    "OrderConfirmed", "order-service", "evt-4");
            assertEquals(SagaStatus.COMPLETED, saga.getStatus());
            assertEquals(4, saga.getCurrentStep());
            assertNotNull(saga.getCompletedAt());
        }

        @Test
        @DisplayName("should track compensation saga")
        void shouldTrackCompensationSaga() {
            // Start saga
            sagaStateStore.startSaga("saga-123", "OrderFulfillment",
                    "corr-456", 4, Duration.ofSeconds(30));

            // Step 1: Order created
            sagaStateStore.recordStepCompleted("saga-123", 0,
                    "OrderCreated", "order-service", "evt-1");

            // Step 2: Stock reserved
            sagaStateStore.recordStepCompleted("saga-123", 1,
                    "StockReserved", "inventory-service", "evt-2");

            // Step 3: Payment failed!
            SagaState failed = sagaStateStore.recordStepFailed("saga-123", 2,
                    "PaymentFailed", "payment-service", "Insufficient funds");
            assertEquals(SagaStatus.COMPENSATING, failed.getStatus());

            // Compensate step 2: Release stock
            sagaStateStore.recordCompensationStep("saga-123", 1,
                    "StockReleased", "inventory-service");

            // Compensate step 1: Cancel order
            sagaStateStore.recordCompensationStep("saga-123", 0,
                    "OrderCancelled", "order-service");

            // Complete as compensated
            SagaState compensated = sagaStateStore.completeSaga("saga-123", SagaStatus.COMPENSATED);

            assertEquals(SagaStatus.COMPENSATED, compensated.getStatus());
            assertNotNull(compensated.getCompletedAt());
            assertEquals("Insufficient funds", compensated.getFailureReason());
        }
    }
}
