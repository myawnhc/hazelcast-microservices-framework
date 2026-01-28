# Phase 2 Implementation Plan
## Sagas, Vector Store, and Observability Dashboards

---

## Overview

**Phase 2 Focus**: Distributed transaction patterns, AI-powered features, visual observability

**Prerequisites**: Phase 1 complete (verified 2026-01-27)

**Key Deliverables**:
1. Payment Service (4th microservice)
2. Choreographed Order Fulfillment Saga
3. Saga State Tracking Infrastructure (foundation for orchestration)
4. Product Recommendations via Vector Store (optional, Enterprise)
5. Grafana Dashboards for observability
6. Distributed Tracing with Jaeger

---

## Implementation Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Saga Style | Choreographed (Phase 2) | Simpler for 4-service scenario |
| Orchestration | Foundation only | Enable future orchestration |
| Dashboard | Grafana | Faster than custom UI |
| Vector Store | Optional | Requires Enterprise license |
| Tracing | OpenTelemetry + Jaeger | Industry standard |
| PostgreSQL | Optional persistence | Keep in-memory as default |

---

## Tech Stack Additions

### New Dependencies

```xml
<!-- Distributed Tracing -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>1.34.1</version>
</dependency>
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-spring-boot-starter</artifactId>
    <version>2.1.0</version>
</dependency>

<!-- Vector Store (Optional - Enterprise) -->
<!-- Hazelcast Enterprise dependency if enabled -->

<!-- PostgreSQL (Optional) -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
```

### Infrastructure Additions

```yaml
# docker-compose additions
services:
  jaeger:
    image: jaegertracing/all-in-one:1.54
    ports:
      - "16686:16686"  # UI
      - "4317:4317"    # OTLP gRPC

  grafana:
    image: grafana/grafana:10.3.1
    ports:
      - "3000:3000"
    volumes:
      - ./grafana/dashboards:/var/lib/grafana/dashboards
      - ./grafana/provisioning:/etc/grafana/provisioning
```

---

## Project Structure Additions

```
hazelcast-microservices-framework/
├── payment-service/                      # NEW - Phase 2
│   ├── src/main/java/com/theyawns/ecommerce/payment/
│   │   ├── PaymentServiceApplication.java
│   │   ├── domain/
│   │   │   └── Payment.java
│   │   ├── events/
│   │   │   ├── PaymentProcessedEvent.java
│   │   │   ├── PaymentFailedEvent.java
│   │   │   └── PaymentRefundedEvent.java
│   │   ├── service/
│   │   │   └── PaymentService.java
│   │   ├── controller/
│   │   │   └── PaymentController.java
│   │   └── config/
│   │       └── PaymentServiceConfig.java
│   └── pom.xml
│
├── framework-core/
│   └── src/main/java/com/theyawns/framework/
│       ├── saga/                         # NEW - Saga infrastructure
│       │   ├── SagaState.java
│       │   ├── SagaStateStore.java       # Interface
│       │   ├── HazelcastSagaStateStore.java
│       │   ├── SagaEvent.java            # Interface
│       │   ├── SagaTimeoutDetector.java
│       │   └── CompensationRegistry.java
│       │
│       ├── vectorstore/                  # NEW - Optional Vector Store
│       │   ├── VectorStoreService.java   # Interface
│       │   ├── HazelcastVectorStore.java # Enterprise implementation
│       │   ├── NoOpVectorStore.java      # Fallback
│       │   └── ProductEmbedding.java
│       │
│       └── tracing/                      # NEW - Distributed tracing
│           ├── TracingConfig.java
│           └── EventSpanDecorator.java
│
├── docker/
│   ├── grafana/                          # NEW
│   │   ├── dashboards/
│   │   │   ├── system-overview.json
│   │   │   ├── transaction-monitoring.json
│   │   │   ├── event-flow.json
│   │   │   ├── materialized-views.json
│   │   │   └── saga-dashboard.json
│   │   └── provisioning/
│   │       ├── dashboards.yml
│   │       └── datasources.yml
│   │
│   └── jaeger/                           # NEW
│       └── jaeger-config.yml
│
└── docs/
    └── blog/
        ├── 04-observability-in-event-sourced-systems.md  # NEW
        └── 05-saga-pattern-for-distributed-transactions.md # NEW
```

---

## Implementation Sequence

### Week 1: Saga Infrastructure (Days 1-5)

#### Day 1: Saga State Store Interface & Implementation

**Goal**: Create infrastructure to track saga instances across services

**Tasks**:
1. Define `SagaState` domain object
2. Define `SagaStateStore` interface
3. Implement `HazelcastSagaStateStore`
4. Create saga-specific IMap configuration
5. Write unit tests

**SagaState.java**:
```java
package com.theyawns.framework.saga;

import java.time.Instant;
import java.util.List;

/**
 * Represents the current state of a saga instance.
 * Tracks which steps have completed and whether compensation is needed.
 */
public class SagaState {

    private String sagaId;
    private String sagaType;          // "OrderFulfillment", etc.
    private SagaStatus status;        // STARTED, IN_PROGRESS, COMPLETED, COMPENSATING, FAILED
    private Instant startedAt;
    private Instant completedAt;
    private String correlationId;

    // Step tracking
    private int currentStep;
    private int totalSteps;
    private List<SagaStepRecord> steps;

    // Timeout configuration
    private Instant deadline;         // When saga should timeout

    // Originating context
    private String initiatingService;
    private String initiatingEventId;

    public enum SagaStatus {
        STARTED,
        IN_PROGRESS,
        COMPLETED,
        COMPENSATING,
        COMPENSATED,
        FAILED,
        TIMED_OUT
    }
}

/**
 * Record of a single step in the saga.
 */
public class SagaStepRecord {
    private int stepNumber;
    private String stepName;
    private String service;
    private String eventType;
    private StepStatus status;        // PENDING, COMPLETED, FAILED, COMPENSATED
    private Instant timestamp;
    private String failureReason;     // If failed
}
```

**SagaStateStore.java Interface**:
```java
package com.theyawns.framework.saga;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Interface for tracking saga state across distributed services.
 *
 * <p>This infrastructure supports choreographed sagas now and
 * enables future orchestrated saga implementation.
 */
public interface SagaStateStore {

    /**
     * Start a new saga instance.
     */
    void startSaga(String sagaId, String sagaType, String correlationId,
                   int totalSteps, Duration timeout);

    /**
     * Record that a saga step has completed.
     */
    void recordStepCompleted(String sagaId, int stepNumber, String eventType,
                             String service);

    /**
     * Record that a saga step has failed.
     */
    void recordStepFailed(String sagaId, int stepNumber, String eventType,
                          String service, String reason);

    /**
     * Record that compensation has been triggered.
     */
    void recordCompensationStarted(String sagaId);

    /**
     * Record that a compensation step completed.
     */
    void recordCompensationStep(String sagaId, int stepNumber, String eventType);

    /**
     * Mark saga as completed successfully.
     */
    void completeSaga(String sagaId, SagaState.SagaStatus outcome);

    /**
     * Get current state of a saga.
     */
    Optional<SagaState> getSagaState(String sagaId);

    /**
     * Find sagas that have exceeded their timeout.
     */
    List<SagaState> findTimedOutSagas();

    /**
     * Find sagas by status.
     */
    List<SagaState> findSagasByStatus(SagaState.SagaStatus status);

    /**
     * Find sagas by correlation ID.
     */
    List<SagaState> findSagasByCorrelationId(String correlationId);
}
```

**Deliverables**:
- [ ] `SagaState` domain object
- [ ] `SagaStateStore` interface
- [ ] `HazelcastSagaStateStore` implementation
- [ ] Unit tests (>80% coverage)

---

#### Day 2: Saga Event Contracts & Compensation Registry

**Goal**: Define standard saga event interface and compensation mapping

**Tasks**:
1. Define `SagaEvent` interface extending `DomainEvent`
2. Create compensation event pairs for existing events
3. Implement `CompensationRegistry`
4. Write tests

**SagaEvent.java**:
```java
package com.theyawns.framework.saga;

import com.theyawns.framework.event.DomainEvent;

/**
 * Marker interface for events that participate in sagas.
 * Extends DomainEvent with saga-specific contracts.
 */
public interface SagaEvent<D, K> {

    /**
     * Returns the saga step number for this event.
     */
    int getSagaStepNumber();

    /**
     * Returns the event type that compensates this event.
     * Returns null if no compensation is needed.
     */
    String getCompensatingEventType();

    /**
     * Returns true if this is a compensating (rollback) event.
     */
    boolean isCompensating();
}
```

**CompensationRegistry.java**:
```java
package com.theyawns.framework.saga;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry mapping events to their compensating counterparts.
 * Used by saga infrastructure to know how to roll back.
 */
public class CompensationRegistry {

    private final Map<String, CompensationMapping> mappings = new ConcurrentHashMap<>();

    /**
     * Register a compensation mapping.
     *
     * @param eventType The forward event type
     * @param compensatingEventType The event that undoes it
     * @param service The service responsible for compensation
     */
    public void register(String eventType, String compensatingEventType, String service) {
        mappings.put(eventType, new CompensationMapping(compensatingEventType, service));
    }

    /**
     * Get compensation for an event type.
     */
    public Optional<CompensationMapping> getCompensation(String eventType) {
        return Optional.ofNullable(mappings.get(eventType));
    }

    public record CompensationMapping(String compensatingEventType, String service) {}
}
```

**Compensation Mappings for eCommerce**:
```java
// Order Fulfillment Saga compensations
registry.register("OrderCreated", "OrderCancelled", "order-service");
registry.register("StockReserved", "StockReleased", "inventory-service");
registry.register("PaymentProcessed", "PaymentRefunded", "payment-service");
```

**Deliverables**:
- [ ] `SagaEvent` interface
- [ ] `CompensationRegistry` implementation
- [ ] Compensation mappings for eCommerce events
- [ ] Unit tests

---

#### Day 3: Saga Timeout Detection

**Goal**: Detect and handle sagas that exceed their deadline

**Tasks**:
1. Implement `SagaTimeoutDetector` scheduled service
2. Add timeout event publishing
3. Configure timeout thresholds
4. Write tests

**SagaTimeoutDetector.java**:
```java
package com.theyawns.framework.saga;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Scheduled service that detects sagas that have exceeded their timeout.
 * Triggers compensation for timed-out sagas.
 */
@Component
public class SagaTimeoutDetector {

    private static final Logger logger = LoggerFactory.getLogger(SagaTimeoutDetector.class);

    private final SagaStateStore sagaStateStore;
    private final SagaCompensator compensator;

    @Scheduled(fixedDelayString = "${saga.timeout.check-interval:5000}")
    public void checkForTimedOutSagas() {
        List<SagaState> timedOut = sagaStateStore.findTimedOutSagas();

        for (SagaState saga : timedOut) {
            logger.warn("Saga {} timed out after deadline. Triggering compensation.",
                saga.getSagaId());

            // Mark as timed out
            sagaStateStore.completeSaga(saga.getSagaId(), SagaState.SagaStatus.TIMED_OUT);

            // Trigger compensation
            compensator.compensate(saga);
        }
    }
}
```

**Deliverables**:
- [ ] `SagaTimeoutDetector` scheduled service
- [ ] Configurable timeout thresholds
- [ ] `SagaTimedOutEvent` for notification
- [ ] Unit and integration tests

---

#### Day 4: Payment Service - Domain & Events

**Goal**: Create Payment Service domain model and events

**Tasks**:
1. Create payment-service module structure
2. Implement `Payment` domain object
3. Implement payment events:
   - `PaymentProcessedEvent`
   - `PaymentFailedEvent`
   - `PaymentRefundedEvent`
4. Implement `PaymentViewUpdater`
5. Write tests

**Payment.java**:
```java
package com.theyawns.ecommerce.payment.domain;

import com.theyawns.framework.domain.DomainObject;
import java.math.BigDecimal;
import java.time.Instant;

public class Payment implements DomainObject<String> {

    private String paymentId;
    private String orderId;
    private String customerId;
    private BigDecimal amount;
    private String currency;
    private PaymentMethod method;
    private PaymentStatus status;
    private String transactionId;    // External payment processor ID
    private Instant processedAt;
    private String failureReason;

    public enum PaymentMethod {
        CREDIT_CARD, DEBIT_CARD, BANK_TRANSFER, DIGITAL_WALLET
    }

    public enum PaymentStatus {
        PENDING, AUTHORIZED, CAPTURED, FAILED, REFUNDED
    }

    @Override
    public String getKey() {
        return paymentId;
    }
}
```

**PaymentProcessedEvent.java**:
```java
package com.theyawns.ecommerce.payment.events;

import com.theyawns.framework.saga.SagaEvent;

public class PaymentProcessedEvent extends DomainEvent<Payment, String>
    implements SagaEvent<Payment, String> {

    private String orderId;
    private String customerId;
    private BigDecimal amount;
    private String currency;
    private String transactionId;

    public PaymentProcessedEvent(String paymentId, String orderId,
                                  String customerId, BigDecimal amount) {
        super();
        this.key = paymentId;
        this.eventType = "PaymentProcessed";
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
    }

    @Override
    public int getSagaStepNumber() {
        return 3; // Third step in OrderFulfillment saga
    }

    @Override
    public String getCompensatingEventType() {
        return "PaymentRefunded";
    }

    @Override
    public boolean isCompensating() {
        return false;
    }

    @Override
    public GenericRecord apply(GenericRecord current) {
        return GenericRecordBuilder.compact("Payment")
            .setString("paymentId", key)
            .setString("orderId", orderId)
            .setString("customerId", customerId)
            .setString("amount", amount.toString())
            .setString("status", "CAPTURED")
            .setString("transactionId", transactionId)
            .build();
    }
}
```

**Deliverables**:
- [ ] payment-service module created
- [ ] `Payment` domain object
- [ ] `PaymentProcessedEvent`, `PaymentFailedEvent`, `PaymentRefundedEvent`
- [ ] `PaymentViewUpdater`
- [ ] Unit tests

---

#### Day 5: Payment Service - REST & Integration

**Goal**: Complete Payment Service with REST endpoints and saga integration

**Tasks**:
1. Implement `PaymentService` business logic
2. Implement `PaymentController` REST endpoints
3. Listen to `StockReserved` events (saga trigger)
4. Publish `PaymentProcessed` or `PaymentFailed`
5. Handle `PaymentRefundRequested` for compensation
6. Write integration tests

**PaymentController.java**:
```java
@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payment Management")
public class PaymentController {

    @PostMapping
    @Operation(summary = "Process payment")
    public CompletableFuture<ResponseEntity<PaymentDTO>> processPayment(
        @Valid @RequestBody PaymentRequest request
    ) { ... }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get payment by ID")
    public ResponseEntity<PaymentDTO> getPayment(@PathVariable String paymentId) { ... }

    @GetMapping("/order/{orderId}")
    @Operation(summary = "Get payment for order")
    public ResponseEntity<PaymentDTO> getPaymentByOrder(@PathVariable String orderId) { ... }

    @PostMapping("/{paymentId}/refund")
    @Operation(summary = "Refund payment")
    public CompletableFuture<ResponseEntity<PaymentDTO>> refundPayment(
        @PathVariable String paymentId
    ) { ... }
}
```

**Saga Listener**:
```java
@Component
public class PaymentSagaListener {

    @EventListener
    public void onStockReserved(StockReservedEvent event) {
        if (event.getSagaId() != null) {
            // This is part of a saga - process payment
            paymentService.processPaymentForOrder(
                event.getOrderId(),
                event.getSagaId(),
                event.getCorrelationId()
            );
        }
    }

    @EventListener
    public void onPaymentRefundRequested(PaymentRefundRequestedEvent event) {
        // Compensation - refund the payment
        paymentService.refundPayment(event.getPaymentId(), event.getSagaId());
    }
}
```

**Deliverables**:
- [ ] `PaymentService` implementation
- [ ] `PaymentController` REST endpoints
- [ ] Saga event listeners
- [ ] Integration tests
- [ ] Week 1 DONE: Saga infrastructure + Payment Service

---

### Week 2: Choreographed Saga Implementation (Days 6-10)

#### Day 6: Order Fulfillment Saga - Happy Path

**Goal**: Implement end-to-end saga for successful order

**Tasks**:
1. Update `OrderCreatedEvent` with saga initiation
2. Update `InventoryService` to listen and reserve stock
3. Update `PaymentService` to listen and process payment
4. Update `OrderService` to confirm on payment success
5. Track saga state at each step
6. Write end-to-end test

**Saga Flow**:
```
1. POST /api/orders → OrderService.createOrder()
   - Generate sagaId
   - Publish OrderCreated (sagaId, step=1)
   - SagaStateStore.startSaga()

2. InventoryService receives OrderCreated
   - Reserve stock
   - Publish StockReserved (sagaId, step=2)
   - SagaStateStore.recordStepCompleted()

3. PaymentService receives StockReserved
   - Process payment
   - Publish PaymentProcessed (sagaId, step=3)
   - SagaStateStore.recordStepCompleted()

4. OrderService receives PaymentProcessed
   - Confirm order
   - Publish OrderConfirmed (sagaId, step=4)
   - SagaStateStore.completeSaga(COMPLETED)
```

**Deliverables**:
- [ ] Saga initiation in OrderService
- [ ] Saga listeners in InventoryService
- [ ] Saga listeners in PaymentService
- [ ] Saga completion handling
- [ ] End-to-end happy path test

---

#### Day 7: Order Fulfillment Saga - Compensation

**Goal**: Implement compensation when payment fails

**Tasks**:
1. Implement `PaymentFailedEvent` publishing
2. Implement stock release compensation
3. Implement order cancellation compensation
4. Track compensation steps in SagaStateStore
5. Write compensation tests

**Compensation Flow**:
```
1. PaymentService fails to process payment
   - Publish PaymentFailed (sagaId, isCompensating=false)
   - SagaStateStore.recordStepFailed()

2. InventoryService receives PaymentFailed
   - Release reserved stock
   - Publish StockReleased (sagaId, isCompensating=true)
   - SagaStateStore.recordCompensationStep()

3. OrderService receives PaymentFailed
   - Cancel order
   - Publish OrderCancelled (sagaId, isCompensating=true)
   - SagaStateStore.completeSaga(COMPENSATED)
```

**Deliverables**:
- [ ] Payment failure handling
- [ ] Stock release compensation
- [ ] Order cancellation compensation
- [ ] Compensation tracking in SagaStateStore
- [ ] Compensation scenario tests

---

#### Day 8: Saga Timeout Handling

**Goal**: Handle sagas that get stuck

**Tasks**:
1. Configure saga timeout thresholds
2. Implement timeout detection triggering compensation
3. Add timeout metrics
4. Write timeout scenario tests

**Configuration**:
```yaml
saga:
  timeout:
    check-interval: 5000          # Check every 5 seconds
    default-deadline: 30000       # 30 second default timeout
    order-fulfillment: 60000      # 60 seconds for order saga
```

**Deliverables**:
- [ ] Configurable saga timeouts
- [ ] Timeout triggers compensation
- [ ] Timeout metrics exposed
- [ ] Timeout scenario tests

---

#### Day 9: Saga Dashboard Metrics

**Goal**: Expose saga metrics for Grafana dashboard

**Tasks**:
1. Add Micrometer metrics for saga operations
2. Create saga-specific metric endpoints
3. Define Grafana queries for saga dashboard
4. Test metrics collection

**Saga Metrics**:
```java
// Counters
sagasStarted.total          // Total sagas started
sagasCompleted.total        // Completed successfully
sagasCompensated.total      // Completed via compensation
sagasFailed.total           // Failed without recovery
sagasTimedOut.total         // Timed out

// Gauges
sagasInProgress.count       // Currently active sagas
sagasCompensating.count     // Currently compensating

// Histograms
sagaDuration.seconds        // Time to complete saga
sagaCompensationDuration.seconds // Time to compensate

// Tags
saga.type                   // "OrderFulfillment", etc.
saga.outcome                // "completed", "compensated", "failed"
```

**Deliverables**:
- [ ] Saga Micrometer metrics
- [ ] Prometheus endpoint includes saga metrics
- [ ] Metric documentation

---

#### Day 10: Saga Integration Tests

**Goal**: Comprehensive saga testing

**Tasks**:
1. Happy path end-to-end test
2. Payment failure compensation test
3. Stock unavailable compensation test
4. Timeout compensation test
5. Idempotency test (duplicate events)
6. Documentation

**Deliverables**:
- [ ] All saga scenarios tested
- [ ] Integration tests with Testcontainers
- [ ] Saga documentation (ADR)
- [ ] Week 2 DONE: Choreographed saga complete

---

### Week 3: Observability & Dashboards (Days 11-15)

#### Day 11: Distributed Tracing Setup

**Goal**: OpenTelemetry + Jaeger integration

**Tasks**:
1. Add OpenTelemetry dependencies
2. Configure auto-instrumentation
3. Add custom spans for event processing
4. Configure Jaeger in Docker Compose
5. Test trace propagation across services

**TracingConfig.java**:
```java
@Configuration
public class TracingConfig {

    @Bean
    public Tracer tracer() {
        return GlobalOpenTelemetry.getTracer("hazelcast-microservices");
    }

    @Bean
    public EventSpanDecorator eventSpanDecorator(Tracer tracer) {
        return new EventSpanDecorator(tracer);
    }
}

public class EventSpanDecorator {

    public Span startEventSpan(DomainEvent event) {
        return tracer.spanBuilder("process-" + event.getEventType())
            .setAttribute("event.id", event.getEventId())
            .setAttribute("event.type", event.getEventType())
            .setAttribute("correlation.id", event.getCorrelationId())
            .setAttribute("saga.id", event.getSagaId())
            .startSpan();
    }
}
```

**Deliverables**:
- [ ] OpenTelemetry configured
- [ ] Jaeger in Docker Compose
- [ ] Traces visible in Jaeger UI
- [ ] Correlation IDs in traces

---

#### Day 12: Grafana Dashboard - System Overview

**Goal**: Create main system overview dashboard

**Tasks**:
1. Configure Grafana in Docker Compose
2. Create Prometheus datasource
3. Build System Overview dashboard:
   - Service health indicators
   - Hazelcast cluster status
   - Overall TPS
   - Error rates
4. Export dashboard JSON

**Dashboard Panels**:
```
┌─────────────────────────────────────────────────────────────────┐
│  System Health                                                   │
│  ┌──────────┬──────────┬──────────┬──────────┬──────────┐      │
│  │ Account  │ Inventory│ Order    │ Payment  │ Hazelcast│      │
│  │   🟢     │   🟢     │   🟢     │   🟢     │  3 nodes │      │
│  └──────────┴──────────┴──────────┴──────────┴──────────┘      │
│                                                                  │
│  Throughput (Events/sec)                    Error Rate           │
│  ┌────────────────────────────┐  ┌────────────────────────────┐│
│  │  [TPS Graph]               │  │  [Error % Graph]           ││
│  └────────────────────────────┘  └────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

**Deliverables**:
- [ ] Grafana Docker configuration
- [ ] Prometheus datasource configured
- [ ] System Overview dashboard JSON
- [ ] Documentation

---

#### Day 13: Grafana Dashboards - Events & Views

**Goal**: Create event flow and materialized view dashboards

**Tasks**:
1. Event Flow dashboard:
   - Events per service
   - Event processing latency
   - Pipeline stage timing
2. Materialized Views dashboard:
   - View entry counts
   - Update rates
   - Staleness metrics

**Event Flow Dashboard**:
```
┌─────────────────────────────────────────────────────────────────┐
│  Event Flow                                                      │
│                                                                  │
│  Events Published (by service)         Processing Latency        │
│  ┌────────────────────────────┐  ┌────────────────────────────┐│
│  │  Account: ████ 42/s        │  │  P50: 0.3ms               ││
│  │  Inventory: ██████ 67/s    │  │  P95: 1.2ms               ││
│  │  Order: █████ 55/s         │  │  P99: 5.1ms               ││
│  │  Payment: ███ 30/s         │  │                            ││
│  └────────────────────────────┘  └────────────────────────────┘│
│                                                                  │
│  Pipeline Stage Timing (last hour)                               │
│  ┌────────────────────────────────────────────────────────────┐│
│  │  [Stacked area chart: Source, Enrich, Persist, View, Pub] ││
│  └────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

**Deliverables**:
- [ ] Event Flow dashboard JSON
- [ ] Materialized Views dashboard JSON
- [ ] Pipeline metrics dashboard

---

#### Day 14: Grafana Dashboard - Sagas

**Goal**: Create saga-specific monitoring dashboard

**Tasks**:
1. Active sagas count
2. Saga completion rate
3. Compensation rate
4. Saga duration histogram
5. Failed/timed-out saga alerts

**Saga Dashboard**:
```
┌─────────────────────────────────────────────────────────────────┐
│  Saga Monitoring                                                 │
│                                                                  │
│  Active Sagas    Completed     Compensated    Failed             │
│  ┌────────┐     ┌────────┐    ┌────────┐     ┌────────┐        │
│  │   3    │     │  1,247 │    │   23   │     │   2    │        │
│  └────────┘     └────────┘    └────────┘     └────────┘        │
│                                                                  │
│  Saga Duration (p50/p95/p99)     Completion Rate                 │
│  ┌────────────────────────────┐  ┌────────────────────────────┐│
│  │  [Histogram]               │  │  [Pie: Success/Comp/Fail] ││
│  └────────────────────────────┘  └────────────────────────────┘│
│                                                                  │
│  Recent Sagas                                                    │
│  ┌────────────────────────────────────────────────────────────┐│
│  │ SagaID   │ Type            │ Status    │ Duration │ Steps ││
│  │ a7f3...  │ OrderFulfillment│ COMPLETED │ 234ms   │ 4/4   ││
│  │ b2e9...  │ OrderFulfillment│ COMPENSATING│ 1.2s  │ 2/4   ││
│  └────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

**Deliverables**:
- [ ] Saga Dashboard JSON
- [ ] Alert rules for failed sagas
- [ ] Documentation

---

#### Day 15: Vector Store Integration (Optional)

**Goal**: Product recommendations via Hazelcast Vector Store

**Note**: This feature requires Hazelcast Enterprise. Implementation includes graceful fallback.

**Tasks**:
1. Define `VectorStoreService` interface
2. Implement `HazelcastVectorStore` (Enterprise)
3. Implement `NoOpVectorStore` (fallback)
4. Add product embedding generation
5. Add similarity search endpoint
6. Feature flag configuration

**VectorStoreService.java**:
```java
public interface VectorStoreService {

    /**
     * Store a product embedding.
     */
    void storeProductEmbedding(String productId, float[] embedding);

    /**
     * Find similar products.
     */
    List<String> findSimilarProducts(String productId, int limit);

    /**
     * Check if vector store is available.
     */
    boolean isAvailable();
}

@Service
@ConditionalOnProperty(name = "hazelcast.vector-store.enabled", havingValue = "true")
public class HazelcastVectorStore implements VectorStoreService {
    // Enterprise implementation
}

@Service
@ConditionalOnProperty(name = "hazelcast.vector-store.enabled",
                        havingValue = "false", matchIfMissing = true)
public class NoOpVectorStore implements VectorStoreService {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public List<String> findSimilarProducts(String productId, int limit) {
        return Collections.emptyList(); // Graceful degradation
    }
}
```

**Configuration**:
```yaml
hazelcast:
  vector-store:
    enabled: false           # Default: disabled (requires Enterprise)
    dimension: 384           # Embedding dimension
    index-type: HNSW         # Approximate nearest neighbor
```

**Deliverables**:
- [ ] `VectorStoreService` interface
- [ ] `HazelcastVectorStore` (Enterprise)
- [ ] `NoOpVectorStore` (fallback)
- [ ] `/api/products/{id}/similar` endpoint
- [ ] Feature flag and graceful degradation
- [ ] Documentation noting Enterprise requirement
- [ ] Week 3 DONE: Observability complete

---

### Week 4: Testing, Documentation & Polish (Days 16-20)

#### Day 16: Integration Testing

**Tasks**:
1. Full saga end-to-end tests
2. Dashboard verification
3. Tracing verification
4. Vector store tests (if enabled)
5. Performance benchmarks

**Deliverables**:
- [ ] All integration tests pass
- [ ] Dashboards verified with sample data
- [ ] Traces visible in Jaeger

---

#### Day 17: Documentation

**Tasks**:
1. Update main README with Phase 2 features
2. Write Saga pattern documentation
3. Write Dashboard setup guide
4. Update OpenAPI specs for Payment Service
5. Create ADR for saga decisions

**Deliverables**:
- [ ] README updated
- [ ] Saga documentation
- [ ] Dashboard setup guide
- [ ] Payment Service OpenAPI spec
- [ ] ADR-007: Choreographed Sagas

---

#### Day 18: Blog Posts

**Tasks**:
1. Draft "Observability in Event-Sourced Systems"
2. Draft "Saga Pattern for Distributed Transactions"
3. Create code examples for blog posts

**Deliverables**:
- [ ] Blog post 4 draft
- [ ] Blog post 5 draft
- [ ] Code examples

---

#### Day 19: Demo Scenarios

**Tasks**:
1. Create saga demo script (happy path)
2. Create saga failure demo (payment decline)
3. Create saga timeout demo
4. Update demo walkthrough guide
5. Create video walkthrough outline

**Demo Script**:
```bash
# Saga Demo - Happy Path
./scripts/demo-saga-happy-path.sh
# Creates order, shows saga flow, final confirmation

# Saga Demo - Payment Failure
./scripts/demo-saga-payment-failure.sh
# Creates order, payment fails, shows compensation

# Saga Demo - Timeout
./scripts/demo-saga-timeout.sh
# Creates order, simulates stuck service, shows timeout compensation
```

**Deliverables**:
- [ ] Demo scripts
- [ ] Updated walkthrough guide
- [ ] Video outline

---

#### Day 20: Phase 2 Review & Handoff

**Tasks**:
1. Run full test suite
2. Verify all deliverables
3. Update PHASE2-COMPLETE.md
4. Plan Phase 3 scope
5. Celebrate! 🎉

**Deliverables**:
- [ ] All tests pass
- [ ] Documentation complete
- [ ] PHASE2-COMPLETE.md created
- [ ] Phase 2 COMPLETE ✅

---

## Success Criteria

### Week 1 Complete When:
- [ ] SagaStateStore tracks saga instances
- [ ] Payment Service runs standalone
- [ ] Payment events flow correctly
- [ ] Saga metadata propagates through events

### Week 2 Complete When:
- [ ] Order fulfillment saga works end-to-end
- [ ] Compensation triggers on payment failure
- [ ] Timeout detection works
- [ ] Saga metrics exposed

### Week 3 Complete When:
- [ ] Traces visible in Jaeger
- [ ] Grafana dashboards functional
- [ ] Vector Store available (if Enterprise enabled)
- [ ] All Phase 2 features integrated

### Week 4 Complete When:
- [ ] All tests pass
- [ ] Documentation complete
- [ ] Demo scenarios work
- [ ] Blog posts drafted
- [ ] Phase 2 COMPLETE

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Saga complexity | Start with choreographed, defer orchestration |
| Vector Store requires Enterprise | Implement graceful fallback |
| Dashboard learning curve | Use Grafana (familiar to most) |
| Tracing overhead | Make tracing configurable |
| Test flakiness | Use Testcontainers, avoid timing dependencies |

---

## Phase 3 Preview

With Phase 2 complete, Phase 3 could focus on:
- Orchestrated sagas (using SagaStateStore foundation)
- Resilience patterns (circuit breaker, retry, outbox)
- API Gateway
- MCP Server integration
- Kubernetes deployment

---

*Document Version: 1.0*
*Created: 2026-01-28*
