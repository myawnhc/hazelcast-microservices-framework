# Saga Pattern Guide

This guide explains how the Hazelcast Microservices Framework implements the saga pattern for distributed transaction coordination across services.

## Overview

A **saga** is a sequence of local transactions where each service performs its own work and publishes an event to trigger the next step. If any step fails, compensation events undo the work completed so far.

This framework supports **both choreographed and orchestrated sagas**:

- **Choreography**: Each service reacts independently to events — no central coordinator. Best for loosely coupled flows where services already publish events.
- **Orchestration**: A central `SagaOrchestrator` drives steps sequentially via HTTP calls. Best for complex flows where you need the entire saga visible in one place.

For the original choreography decision rationale, see [ADR 007: Choreographed Sagas](../architecture/adr/007-choreographed-sagas.md).

## Architecture

### Choreography vs Orchestration

Both patterns are fully supported. Choose based on your requirements:

| Aspect | Choreography | Orchestration |
|--------|-------------|--------------|
| **Coupling** | Services only know about events | Steps defined in one central definition |
| **Visibility** | Flow distributed across listeners | Entire saga readable in one `SagaDefinition` |
| **Communication** | Hazelcast ITopic (events) | HTTP calls (synchronous per step) |
| **Failure handling** | `CompensationRegistry` + event publishing | Orchestrator runs compensation lambdas in reverse |
| **Single point of failure** | None | Orchestrator (mitigated by Hazelcast state persistence) |
| **Scalability** | Each service scales independently | Orchestrator coordinates sequentially |
| **Fit with event sourcing** | Events drive the flow naturally | Requires separate coordinator |
| **Best for** | Loosely coupled, independently evolving services | Complex flows needing centralized control and per-step timeout/retry |

### Saga Components

#### Choreography Components

The framework provides 16 classes in `com.theyawns.framework.saga` for choreographed sagas:

| Component | Description |
|-----------|-------------|
| `SagaState` | Immutable state machine tracking a saga instance's progress, steps, and status |
| `SagaStepRecord` | Record of a single step with timing, status, and failure information |
| `SagaStatus` | Lifecycle states: STARTED, IN_PROGRESS, COMPLETED, COMPENSATING, COMPENSATED, FAILED, TIMED_OUT |
| `StepStatus` | Per-step states: PENDING, COMPLETED, FAILED, SKIPPED, COMPENSATED |
| `SagaStateStore` | Interface for distributed saga state storage and querying |
| `HazelcastSagaStateStore` | IMap-backed implementation with distributed queries |
| `SagaEvent` | Marker interface for events that participate in sagas |
| `CompensationRegistry` | Maps forward events to their compensating counterparts |
| `SagaCompensationConfig` | Spring configuration defining saga type constants and step mappings |
| `SagaCompensator` | Interface for triggering compensation operations |
| `DefaultSagaCompensator` | Publishes compensation events via Hazelcast ITopic with metrics |
| `SagaTimeoutDetector` | Scheduled service that finds timed-out sagas and triggers compensation |
| `SagaTimeoutConfig` | Configuration properties for timeout detection |
| `SagaTimeoutAutoConfiguration` | Auto-configuration for timeout detection beans |
| `SagaTimedOutEvent` | Spring ApplicationEvent published when a saga times out |
| `SagaMetrics` | Micrometer-based metrics for saga operations (shared by both patterns) |

#### Orchestration Components

12 classes in `com.theyawns.framework.saga.orchestrator`:

| Component | Description |
|-----------|-------------|
| `SagaOrchestrator` | Core interface: `start()`, `handleStepResult()`, `getStatus()`, `cancel()` |
| `HazelcastSagaOrchestrator` | State machine implementation handling sequential step execution, retry, compensation, and timeouts |
| `SagaDefinition` | Immutable fluent builder for defining sagas as ordered step sequences |
| `SagaStep` | Immutable step definition with action, optional compensation, timeout, max retries, and retry delay |
| `SagaContext` | Thread-safe mutable key-value store for passing data between saga steps |
| `SagaAction` | Functional interface: `execute(SagaContext) → CompletableFuture<SagaStepResult>` |
| `SagaCompensation` | Functional interface for rollback actions |
| `SagaStepResult` | Immutable result: SUCCESS, FAILURE, or TIMEOUT with optional data payload |
| `SagaOrchestratorResult` | Saga completion result with status, timing, step counts, and failure details |
| `SagaOrchestratorListener` | Callback interface for lifecycle events (9 default no-op methods) |
| `LoggingSagaOrchestratorListener` | Implementation that logs all lifecycle events at INFO level |
| `SagaOrchestratorAutoConfiguration` | Auto-configuration: creates listener, executor, and orchestrator beans |

Service-level components:

| Component | Service | Description |
|-----------|---------|-------------|
| `ECommerceCompensationConfig` | ecommerce-common | Registers compensation mappings for the Order Fulfillment saga |
| `OrderSagaListener` | order-service | Handles PaymentProcessed/PaymentFailed for order confirmation/cancellation |
| `InventorySagaListener` | inventory-service | Handles OrderCreated/OrderCancelled for stock reservation/release |
| `PaymentSagaListener` | payment-service | Handles StockReserved/PaymentRefundRequested for payment processing/refunds |

## Choreographed Order Fulfillment Saga

The choreographed saga coordinates order placement across four services via Hazelcast ITopic events:

```
                    ┌──────────────┐
                    │ Order Service│
                    │  Step 0      │
                    └──────┬───────┘
                           │
                    OrderCreated event
                           │
                           ▼
                   ┌───────────────┐
                   │Inventory Svc  │
                   │  Step 1       │
                   └──────┬────────┘
                          │
                   StockReserved event
                   (includes payment context: amount, currency, method)
                          │
                          ▼
                   ┌───────────────┐
                   │ Payment Svc   │
                   │  Step 2       │
                   └──────┬────────┘
                          │
                  PaymentProcessed event
                          │
                          ▼
                   ┌───────────────┐
                   │ Order Service │
                   │  Step 3       │
                   └───────────────┘
                          │
                  OrderConfirmed event
                          │
                    Saga COMPLETED
```

### Step Details

| Step | Service | Action | Publishes | Compensating Event |
|------|---------|--------|-----------|-------------------|
| 0 | Order Service | Creates order, starts saga state tracking | `OrderCreated` | `OrderCancelled` |
| 1 | Inventory Service | Reserves stock for each line item | `StockReserved` | `StockReleased` |
| 2 | Payment Service | Processes payment | `PaymentProcessed` | `PaymentRefunded` |
| 3 | Order Service | Confirms order, marks saga COMPLETED | `OrderConfirmed` | — (terminal) |

### Event Context Propagation

All saga events carry metadata for correlation and tracking:

- **`sagaId`** — Unique identifier linking all events in one saga instance
- **`correlationId`** — Links events to the original business request
- **`sagaType`** — Identifies the saga definition (e.g., `"OrderFulfillment"`)
- **`stepNumber`** — Which step in the saga this event represents
- **`isCompensating`** — Flags events that are part of the compensation flow

## Compensation

When a step fails, compensation runs **in reverse order** for completed steps only.

### Payment Failure (Step 2 fails)

```
PaymentFailed event
       │
       ├──► Inventory Service: releases reserved stock (StockReleased)
       │
       └──► Order Service: cancels order (OrderCancelled)

       Saga finalized as COMPENSATED
```

### Stock Unavailable (Step 1 fails)

```
StockReservationFailed
       │
       └──► Order Service: cancels order (OrderCancelled)

       No stock release needed (never reserved)
       Saga finalized as COMPENSATED
```

### Key Principle

Each service owns its own compensation logic. The `CompensationRegistry` maps forward events to their compensating counterparts so the `SagaCompensator` knows which events to publish:

```java
// From ECommerceCompensationConfig
registry.register("OrderCreated", "OrderCancelled", "order-service");
registry.register("StockReserved", "StockReleased", "inventory-service");
registry.register("PaymentProcessed", "PaymentRefunded", "payment-service");
```

## Orchestrated Order Fulfillment Saga

The orchestrated saga uses `HazelcastSagaOrchestrator` to drive the same four-step flow via synchronous HTTP calls:

```
                    ┌──────────────────────┐
                    │   SagaOrchestrator   │
                    │  (Order Service)     │
                    └──────────┬───────────┘
                               │
              ┌────────────────┼────────────────┐
              │                │                │
    Step 0: CreateOrder   Step 1: HTTP      Step 2: HTTP
    (local)               POST /api/saga/   POST /api/saga/
              │           inventory/         payment/
              │           reserve-stock      process
              │                │                │
              ▼                ▼                ▼
       ┌──────────┐    ┌──────────┐    ┌──────────┐
       │  Order   │    │Inventory │    │ Payment  │
       │  Service │    │  Service │    │  Service │
       └──────────┘    └──────────┘    └──────────┘
              │                                │
              └────────────────────────────────┘
                               │
                    Step 3: ConfirmOrder (local)
                               │
                         Saga COMPLETED
```

### Step Details

| Step | Name | Action | HTTP Endpoint | Compensation |
|------|------|--------|---------------|-------------|
| 0 | CreateOrder | Local order creation | — (local) | Cancel order |
| 1 | ReserveStock | Reserve stock via HTTP | `POST /api/saga/inventory/reserve-stock` | `POST /api/saga/inventory/release-stock` |
| 2 | ProcessPayment | Charge customer via HTTP | `POST /api/saga/payment/process` | `POST /api/saga/payment/refund` |
| 3 | ConfirmOrder | Confirm order locally | — (local) | None (terminal step) |

Each step has a 15-second timeout and the overall saga has a 60-second timeout.

### SagaDefinition Builder

The `OrderFulfillmentSagaFactory` defines the orchestrated saga:

```java
public SagaDefinition create() {
    return SagaDefinition.builder()
            .name("OrderFulfillmentOrchestrated")

            .step("CreateOrder")
                .action(this::createOrderAction)
                .compensation(this::createOrderCompensation)
                .timeout(Duration.ofSeconds(15))
                .build()

            .step("ReserveStock")
                .action(this::reserveStockAction)
                .compensation(this::reserveStockCompensation)
                .timeout(Duration.ofSeconds(15))
                .build()

            .step("ProcessPayment")
                .action(this::processPaymentAction)
                .compensation(this::processPaymentCompensation)
                .timeout(Duration.ofSeconds(15))
                .build()

            .step("ConfirmOrder")
                .action(this::confirmOrderAction)
                .noCompensation()
                .timeout(Duration.ofSeconds(10))
                .build()

            .sagaTimeout(Duration.ofSeconds(60))
            .build();
}
```

### Orchestrator State Machine

The `HazelcastSagaOrchestrator` executes the definition as a state machine:

1. **Start** — Creates a `SagaExecution`, records saga start in `SagaStateStore`, schedules a saga-level timeout
2. **Execute Step** — Calls `step.getAction().execute(context)` with per-step timeout; retries on failure up to `maxRetries` with configurable delay
3. **Step Success** — Merges result data into `SagaContext`, records step completion, advances to next step
4. **Step Failure** — Records failure, triggers compensation
5. **Compensate** — Iterates completed steps in reverse order, executing each step's compensation lambda; skips steps with no compensation defined
6. **Complete** — Reports final status: `COMPLETED`, `COMPENSATED`, `FAILED`, or `TIMED_OUT`

### Compensation Flow

Unlike choreography (which publishes compensation events via ITopic), orchestration runs compensation lambdas directly. When step 2 (ProcessPayment) fails:

```
Step 2 FAILED → Orchestrator triggers compensation
  ├── Compensation for Step 1 (ReserveStock): HTTP POST /api/saga/inventory/release-stock
  ├── Compensation for Step 0 (CreateOrder): Cancel order locally
  └── Saga finalized as COMPENSATED
```

### Why HTTP Instead of ITopic for Orchestration

The orchestrator uses HTTP calls instead of Hazelcast ITopic because of the [dual-instance architecture](../architecture/adr/008-dual-instance-hazelcast-architecture.md). The orchestrator runs in the Order Service and needs synchronous request-response semantics — it must know whether each step succeeded before proceeding. ITopic is fire-and-forget, making it unsuitable for the orchestrator's sequential flow.

### Saga Type Names

Choreographed and orchestrated sagas coexist with distinct type names:

| Pattern | Saga Type | Trigger |
|---------|-----------|---------|
| Choreography | `OrderFulfillment` | `POST /api/orders` |
| Orchestration | `OrderFulfillmentOrchestrated` | `POST /api/orders/orchestrated` |

Both types are stored in the same `SagaStateStore` and visible in Grafana dashboards.

## Timeout Handling

Sagas can get stuck if a service becomes unavailable or an event is lost. The `SagaTimeoutDetector` handles this automatically for choreographed sagas. Orchestrated sagas use per-step timeouts built into `HazelcastSagaOrchestrator`.

### How It Works

1. Each saga has a **deadline** (absolute time) set when the saga starts
2. `SagaTimeoutDetector` runs at a configurable interval (default: 5 seconds)
3. It queries the `SagaStateStore` for active sagas past their deadline
4. Timed-out sagas are marked as `TIMED_OUT`
5. If `auto-compensate` is enabled, the `SagaCompensator` runs compensation for completed steps
6. A `SagaTimedOutEvent` (Spring ApplicationEvent) is published for monitoring/alerting

### Configuration

Timeout properties are configured per-service in `application.yml`:

```yaml
saga:
  timeout:
    enabled: true                    # Enable/disable timeout detection
    check-interval: 5000            # How often to check (ms), default 5000
    default-deadline: 30000         # Default saga deadline (ms), default 30000
    auto-compensate: true           # Auto-trigger compensation on timeout
    max-batch-size: 100             # Max sagas to process per check cycle
    saga-types:
      OrderFulfillment: 60000       # Per-type override: 60s for order fulfillment
```

The Order Service uses a 60-second timeout for `OrderFulfillment` sagas (longer than the 30-second default) because the flow spans multiple services.

### Auto-Configuration

`SagaTimeoutAutoConfiguration` creates the timeout detection beans automatically when:
- `saga.timeout.enabled=true` is set in configuration
- A `HazelcastInstance` bean is available
- A `SagaStateStore` bean is available

## How to Add a New Choreographed Saga

### 1. Define the Saga Steps

Identify the services involved and the order of steps:

```
Step 0: Service A performs action → publishes EventA
Step 1: Service B reacts to EventA → publishes EventB
Step 2: Service C reacts to EventB → publishes EventC (saga complete)
```

### 2. Create Domain Events

Each step needs a forward event and a compensating event. Events should implement `SagaEvent`:

```java
public class MyForwardEvent extends DomainEvent<MyEntity, String> implements SagaEvent {
    // Include saga metadata: sagaId, correlationId, sagaType, stepNumber
}

public class MyCompensatingEvent extends DomainEvent<MyEntity, String> implements SagaEvent {
    // Same metadata, with isCompensating = true
}
```

### 3. Register Compensation Mappings

Add entries to your compensation configuration:

```java
@Configuration
public class MyCompensationConfig {

    @Bean
    public CompensationRegistrar myCompensationRegistrar(CompensationRegistry registry) {
        registry.register("MyForwardEvent", "MyCompensatingEvent", "my-service");
        return () -> {}; // Marker bean
    }
}
```

### 4. Create Saga Listeners

Each participating service needs a listener that subscribes to relevant events via Hazelcast ITopic:

```java
@Component
public class MyServiceSagaListener {

    public MyServiceSagaListener(
            @Qualifier("hazelcastClient") HazelcastInstance hazelcast,
            MyService myService,
            SagaStateStore sagaStateStore) {

        ITopic<GenericRecord> topic = hazelcast.getTopic("TriggeringEvent");
        topic.addMessageListener(message -> {
            GenericRecord event = message.getMessageObject();
            String sagaId = event.getString("sagaId");

            // Perform local action
            myService.doWork(event);

            // Update saga state
            sagaStateStore.updateOrAddStep(sagaId, stepNumber, StepStatus.COMPLETED);

            // Publish next event
            ITopic<GenericRecord> nextTopic = hazelcast.getTopic("NextEvent");
            nextTopic.publish(nextEvent);
        });
    }
}
```

### 5. Configure Timeout

Add saga timeout configuration to the participating services' `application.yml`:

```yaml
saga:
  timeout:
    enabled: true
    check-interval: 5000
    default-deadline: 30000
    auto-compensate: true
    saga-types:
      MySagaType: 45000  # Custom timeout for your saga
```

### 6. Test

- **Unit test** each saga listener independently by mocking services and the state store
- **Integration test** the full flow with embedded Hazelcast
- Verify compensation triggers correctly when a step fails
- Verify timeout detection works with a short deadline in tests

## How to Add a New Orchestrated Saga

### 1. Create a SagaDefinition Factory

Define the saga as an ordered sequence of steps using the fluent builder:

```java
@Component
public class MySagaFactory {

    private final SagaServiceClient sagaServiceClient;

    public SagaDefinition create() {
        return SagaDefinition.builder()
                .name("MySagaType")

                .step("StepOne")
                    .action(this::stepOneAction)
                    .compensation(this::stepOneCompensation)
                    .timeout(Duration.ofSeconds(15))
                    .build()

                .step("StepTwo")
                    .action(this::stepTwoAction)
                    .noCompensation()  // Terminal step
                    .timeout(Duration.ofSeconds(10))
                    .build()

                .sagaTimeout(Duration.ofSeconds(60))
                .build();
    }

    private CompletableFuture<SagaStepResult> stepOneAction(SagaContext context) {
        // Perform local work or HTTP call
        // Store results in context for subsequent steps
        context.put("stepOneResult", result);
        return CompletableFuture.completedFuture(SagaStepResult.success());
    }

    private CompletableFuture<SagaStepResult> stepOneCompensation(SagaContext context) {
        // Undo step one's work
        return CompletableFuture.completedFuture(SagaStepResult.success());
    }
}
```

### 2. Create a Controller to Trigger the Saga

```java
@RestController
@RequestMapping("/api/my-saga")
public class MySagaController {

    private final SagaOrchestrator orchestrator;
    private final MySagaFactory sagaFactory;

    @PostMapping
    public CompletableFuture<ResponseEntity<?>> start(@RequestBody MyDTO dto) {
        String sagaId = UUID.randomUUID().toString();
        SagaContext context = new SagaContext();
        context.put("inputData", dto.getValue());

        return orchestrator.start(sagaId, sagaFactory.create(), context)
                .thenApply(result -> switch (result.getStatus()) {
                    case COMPLETED -> ResponseEntity.status(HttpStatus.CREATED).body(result);
                    case COMPENSATED, FAILED -> ResponseEntity.status(HttpStatus.CONFLICT).body(result);
                    default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
                });
    }
}
```

### 3. Add HTTP Endpoints for Remote Steps

Each remote service needs saga-specific endpoints that return success/failure responses:

```java
@RestController
@RequestMapping("/api/saga/my-service")
public class MySagaEndpoint {

    @PostMapping("/do-work")
    public ResponseEntity<?> doWork(@RequestBody Map<String, Object> payload) {
        try {
            // Perform the work
            return ResponseEntity.ok(Map.of("success", true, "resultId", id));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
```

### 4. Test

- **Unit test** the factory to verify the definition has the correct steps and timeouts
- **Unit test** each action/compensation lambda by mocking HTTP calls
- **Integration test** the full orchestrated flow with a running `HazelcastSagaOrchestrator`
- Verify compensation runs in reverse order when a step fails
- Verify per-step timeouts trigger correctly

## Resilience Patterns

Saga listeners are protected by multiple resilience patterns that prevent cascade failures, guarantee delivery, and handle permanent processing failures.

### Circuit Breaker + Retry

Each saga step is wrapped with a circuit breaker and retry decorator via `ResilientServiceInvoker`:

```java
executeWithResilience("inventory-stock-reservation",
        () -> inventoryService.reserveStockForSaga(productId, quantity, ...))
.whenComplete((result, error) -> {
    if (error != null) {
        sendToDeadLetterQueue(record, "OrderCreated", error);
    }
});
```

When a service is failing:
1. **Retry** attempts the operation up to `max-attempts` times with exponential backoff
2. **Circuit breaker** trips OPEN when the failure rate exceeds the threshold, rejecting further calls
3. After `wait-duration-in-open-state`, the breaker moves to HALF_OPEN and allows test calls
4. If test calls succeed, the breaker returns to CLOSED

Named circuit breakers isolate failures per saga step:

| Circuit Breaker Name | Saga Step | Service |
|---------------------|-----------|---------|
| `inventory-stock-reservation` | Reserve stock | Inventory |
| `inventory-stock-release` | Release stock (compensation) | Inventory |
| `payment-processing` | Process payment | Payment |
| `payment-refund` | Refund payment (compensation) | Payment |
| `order-confirmation` | Confirm order | Order |
| `order-cancellation` | Cancel order (compensation) | Order |

Business exceptions (e.g., `InsufficientStockException`, `PaymentDeclinedException`) implement `NonRetryableException` and skip retry — there's no point retrying a declined credit card.

### Transactional Outbox

Events published from `EventSourcingController` to the shared cluster go through a durable outbox:

```
Pipeline completes → outboxStore.write(entry) → OutboxPublisher polls → publish to ITopic → mark DELIVERED
```

This guarantees at-least-once delivery even if the shared cluster is temporarily unreachable. The `OutboxPublisher` runs on a configurable schedule (default: every 1 second).

### Idempotency Guard

Since the outbox provides at-least-once delivery, consumers may receive duplicate events. Each saga listener checks an `IdempotencyGuard` at the top of every message handler:

```java
String eventId = record.getString("eventId");
if (idempotencyGuard != null && eventId != null && !idempotencyGuard.tryProcess(eventId)) {
    logger.debug("Duplicate event {} already processed, skipping", eventId);
    return;
}
```

The guard uses `IMap.putIfAbsent()` with a configurable TTL (default: 1 hour) for atomic, cluster-wide deduplication.

### Dead Letter Queue

Events that fail processing after resilience mechanisms are exhausted are captured in a Dead Letter Queue (DLQ):

```java
private void sendToDeadLetterQueue(GenericRecord record, String topicName, Throwable error) {
    deadLetterQueue.add(DeadLetterEntry.builder()
            .originalEventId(eventId)
            .eventType(record.getString("eventType"))
            .topicName(topicName)
            .eventRecord(record)
            .failureReason(error.getMessage())
            .sourceService("inventory-service")
            .sagaId(record.getString("sagaId"))
            .build());
}
```

DLQ entries can be inspected, replayed, or discarded via REST admin endpoints:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `GET /api/admin/dlq` | List | View pending DLQ entries |
| `GET /api/admin/dlq/count` | Count | Number of pending entries |
| `GET /api/admin/dlq/{id}` | Get | Single entry details |
| `POST /api/admin/dlq/{id}/replay` | Replay | Re-publish to original topic |
| `DELETE /api/admin/dlq/{id}` | Discard | Mark as discarded |

### Resilience Configuration

All resilience features are optional and configured in each service's `application.yml`:

```yaml
framework:
  resilience:
    enabled: true
    circuit-breaker:
      failure-rate-threshold: 50
      wait-duration-in-open-state: 10s
      sliding-window-size: 10
    retry:
      max-attempts: 3
      wait-duration: 500ms
      enable-exponential-backoff: true
      exponential-backoff-multiplier: 2.0
    instances:
      payment-processing:
        circuit-breaker:
          failure-rate-threshold: 60
        retry:
          max-attempts: 5
  outbox:
    enabled: true
    poll-interval: 1000
    max-batch-size: 50
    max-retries: 5
  dlq:
    enabled: true
    max-replay-attempts: 3
  idempotency:
    enabled: true
    ttl: 1h
```

All components use `@Autowired(required = false)` injection, so they degrade gracefully when disabled.

---

## Monitoring

### Prometheus Metrics

The `SagaMetrics` class tracks both saga-level and step-level metrics:

**Saga-level metrics** (both patterns):

| Metric | Type | Description |
|--------|------|-------------|
| `saga_started_total` | Counter | Total sagas started |
| `saga_completed_total` | Counter | Successfully completed sagas |
| `saga_failed_total` | Counter | Failed sagas |
| `saga_compensations_total` | Counter | Compensations triggered |
| `saga_compensations_failed_total` | Counter | Failed compensations |
| `saga_timeouts_detected_total` | Counter | Timed-out sagas detected |
| `saga_duration_seconds` | Timer | End-to-end saga duration |

**Step-level metrics** (orchestrated sagas):

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `saga.step.duration` | Timer | `sagaType`, `stepName` | Per-step execution duration with p50/p95/p99 percentiles |

The `HazelcastSagaOrchestrator` records step duration on every step completion, failure, or timeout. This enables fine-grained performance analysis — identifying which step in the saga is the bottleneck.

### Grafana Dashboard

The pre-provisioned **Saga Dashboard** (`docker/grafana/dashboards/saga-dashboard.json`) includes:

- Saga lifecycle panels (start, complete, fail, compensate rates) for both patterns
- **Choreography vs Orchestration** comparison row with side-by-side duration (p50/p95) and success rate panels
- **Orchestrated step timing breakdown** showing per-step duration for each step in the `SagaDefinition`

See the [Dashboard Setup Guide](dashboard-setup-guide.md) for access instructions.

### Alerting

Pre-configured alerts fire on:
- **High Saga Failure Rate** (Critical) — Any saga failures in a 5-minute window
- **Saga Timeouts Detected** (Warning) — Any timeouts in a 5-minute window
- **Saga Compensation Failures** (Critical) — Compensation events fail to publish
- **Low Saga Success Rate** (Warning) — Success rate drops below 90% over 10 minutes

## References

- [ADR 007: Choreographed Sagas](../architecture/adr/007-choreographed-sagas.md) — Original choreography decision rationale
- [ADR 008: Dual-Instance Architecture](../architecture/adr/008-dual-instance-hazelcast-architecture.md) — Why orchestration uses HTTP instead of ITopic
- [Chris Richardson: Saga Pattern](https://microservices.io/patterns/data/saga.html) — Pattern overview
- [Dashboard Setup Guide](dashboard-setup-guide.md) — Monitoring and observability setup
