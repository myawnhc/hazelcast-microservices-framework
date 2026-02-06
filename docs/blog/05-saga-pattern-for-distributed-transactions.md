# The Saga Pattern for Distributed Transactions

*Part 5 of 6 in the "Building Event-Driven Microservices with Hazelcast" series*

---

## Introduction

In the first three articles, we built an event sourcing framework with a Jet pipeline and materialized views. Each service is self-contained — it owns its events, its views, and its data.

But what happens when a business operation spans multiple services?

Placing an order in our eCommerce system requires coordination across four services:

1. **Order Service** creates the order
2. **Inventory Service** reserves stock
3. **Payment Service** charges the customer
4. **Order Service** confirms the order

If payment fails after stock is reserved, we need to release the stock. If the inventory service is down, we need to cancel the order. This is the distributed transaction problem, and **sagas** are the solution.

---

## Why Not a Distributed Transaction?

Traditional databases solve multi-table consistency with ACID transactions. In a microservices architecture, each service has its own data store. A single database transaction can't span them.

Two-phase commit (2PC) can coordinate distributed transactions, but it has serious drawbacks:

| Problem | Impact |
|---------|--------|
| **Coordinator is a single point of failure** | If the coordinator crashes mid-commit, participants are blocked |
| **Locks held across network** | Participants lock resources while waiting for the commit decision |
| **Tight coupling** | All participants must support the 2PC protocol |
| **Poor availability** | Any participant failure blocks the entire transaction |

The saga pattern provides an alternative: instead of one atomic transaction, execute a sequence of local transactions with **compensating actions** to undo work when things go wrong.

---

## Choreography vs. Orchestration

There are two approaches to implementing sagas:

### Orchestration

A central **saga orchestrator** directs each service step-by-step:

```
Orchestrator → "Order Service, create order"
Orchestrator → "Inventory Service, reserve stock"
Orchestrator → "Payment Service, charge customer"
Orchestrator → "Order Service, confirm order"
```

### Choreography

Each service **reacts to events** independently. No central coordinator:

```
Order Service publishes OrderCreated →
  Inventory Service hears it, reserves stock, publishes StockReserved →
    Payment Service hears it, charges customer, publishes PaymentProcessed →
      Order Service hears it, confirms order
```

### Why We Chose Choreography

| Factor | Orchestration | Choreography |
|--------|--------------|-------------|
| Coupling | Services depend on orchestrator | Services only know about events |
| Single point of failure | Orchestrator | None |
| Fit with event sourcing | Requires separate coordinator | Events drive the flow naturally |
| Scalability | Orchestrator can bottleneck | Each service scales independently |
| Complexity | Flow visible in one place | Flow distributed across listeners |

Choreography aligns naturally with our event sourcing architecture. Services already publish events — saga coordination is just another consumer of those events.

The trade-off is that the flow is implicit. You can't read a single file to see the entire saga. We mitigate this with a `SagaCompensationConfig` that documents all steps and their compensations in one place.

---

## The Order Fulfillment Saga

Here's the complete flow for our primary saga:

```
Step 0: Order Service
  ├── Creates order (status: PENDING)
  ├── Publishes OrderCreated event (with sagaId)
  └── Starts saga state tracking

         │
         ▼ OrderCreated event (via ITopic)

Step 1: Inventory Service
  ├── Reserves stock for each line item
  ├── Publishes StockReserved event
  │   (includes payment context: amount, currency, method)
  └── Records step 1 completed in saga state

         │
         ▼ StockReserved event (via ITopic)

Step 2: Payment Service
  ├── Processes payment using context from StockReserved
  ├── On success: publishes PaymentProcessed event
  └── On failure: publishes PaymentFailed event
                   → triggers compensation

         │
         ▼ PaymentProcessed event (via ITopic)

Step 3: Order Service
  ├── Confirms order (status: CONFIRMED)
  ├── Publishes OrderConfirmed event
  └── Marks saga as COMPLETED
```

### Event Context Propagation

Every saga event carries metadata that links the chain together:

```java
// These fields are present on every saga event
String sagaId;         // Links all events in one saga instance
String correlationId;  // Links to the original API request
String sagaType;       // "OrderFulfillment"
int stepNumber;        // Which step (0, 1, 2, 3)
boolean isCompensating; // True for compensation events
```

The `sagaId` is generated when the order is created and propagated through every subsequent event. This is how the Inventory Service knows which saga a `StockReserved` event belongs to, and how the Payment Service gets the payment details (amount, currency, method) from the `StockReserved` event context.

---

## Implementing Saga Listeners

Each service has a saga listener that subscribes to relevant events via Hazelcast ITopic. Here's the Payment Service's listener:

```java
@Component
public class PaymentSagaListener {

    public PaymentSagaListener(
            @Qualifier("hazelcastClient") HazelcastInstance hazelcast,
            PaymentService paymentService,
            SagaStateStore sagaStateStore) {

        // Listen for StockReserved → process payment
        ITopic<GenericRecord> stockReservedTopic = hazelcast.getTopic("StockReserved");
        stockReservedTopic.addMessageListener(message -> {
            GenericRecord event = message.getMessageObject();
            String sagaId = event.getString("sagaId");
            String orderId = event.getString("orderId");
            String amount = event.getString("paymentAmount");
            String currency = event.getString("paymentCurrency");
            String method = event.getString("paymentMethod");

            paymentService.processPaymentForOrder(
                orderId, customerId, amount, currency, method,
                sagaId, event.getString("correlationId")
            );
        });

        // Listen for PaymentRefundRequested → process refund
        ITopic<GenericRecord> refundTopic = hazelcast.getTopic("PaymentRefundRequested");
        refundTopic.addMessageListener(message -> {
            GenericRecord event = message.getMessageObject();
            String paymentId = event.getString("paymentId");
            String sagaId = event.getString("sagaId");

            paymentService.refundPaymentForSaga(
                paymentId, "Saga compensation", sagaId,
                event.getString("correlationId")
            );
        });
    }
}
```

Key points:

- The listener uses `@Qualifier("hazelcastClient")` — it connects to the **shared cluster**, not the embedded instance. This is the dual-instance architecture from [ADR 008](../../architecture/adr/008-dual-instance-hazelcast-architecture.md).
- Each listener is a simple `@Component` — Spring creates it on startup and the ITopic subscription stays active.
- The listener delegates to the service layer, which handles the actual business logic and event publication.

---

## Compensation: Undoing Work

When a step fails, we need to undo the work completed by previous steps. This is compensation — the saga equivalent of a rollback.

### Compensation Flow: Payment Fails

```
PaymentFailed event published by Payment Service
         │
         ├──► Inventory Service (listens to PaymentFailed)
         │    └── Releases reserved stock
         │    └── Publishes StockReleased event
         │
         └──► Order Service (listens to PaymentFailed)
              └── Cancels order (status: CANCELLED)
              └── Publishes OrderCancelled event

         Saga finalized as COMPENSATED
```

### Compensation Flow: Stock Unavailable

```
StockReservationFailed event
         │
         └──► Order Service
              └── Cancels order (status: CANCELLED)
              └── No stock release needed (nothing was reserved)

         Saga finalized as COMPENSATED
```

### The Compensation Registry

A `CompensationRegistry` maps each forward event to its compensating event and responsible service:

```java
@Configuration
public class ECommerceCompensationConfig {

    @Bean
    public CompensationRegistrar ecommerceCompensations(CompensationRegistry registry) {
        // Step 0: OrderCreated → compensate with OrderCancelled
        registry.register("OrderCreated", "OrderCancelled", "order-service");

        // Step 1: StockReserved → compensate with StockReleased
        registry.register("StockReserved", "StockReleased", "inventory-service");

        // Step 2: PaymentProcessed → compensate with PaymentRefunded
        registry.register("PaymentProcessed", "PaymentRefunded", "payment-service");

        // Step 3: OrderConfirmed → no compensation (terminal success)
        return () -> {};
    }
}
```

This registry is the single place that documents the entire saga structure. Even though the flow is distributed across listeners, the registry makes the step-to-compensation mapping explicit.

---

## Saga State Tracking

The `SagaState` class is an immutable state machine that tracks each saga instance:

```java
public class SagaState implements Serializable {

    private final String sagaId;
    private final String sagaType;         // "OrderFulfillment"
    private final String correlationId;
    private final SagaStatus status;       // STARTED → IN_PROGRESS → COMPLETED
    private final List<SagaStepRecord> steps;
    private final Instant startedAt;
    private final Instant deadline;        // Absolute timeout
}
```

Status transitions:

```
STARTED → IN_PROGRESS → COMPLETED     (happy path)
STARTED → IN_PROGRESS → COMPENSATING → COMPENSATED  (failure + recovery)
STARTED → IN_PROGRESS → TIMED_OUT → COMPENSATING → COMPENSATED  (timeout)
STARTED → IN_PROGRESS → FAILED        (unrecoverable)
```

The state is stored in a `HazelcastSagaStateStore` backed by an IMap on the shared cluster. Every service can read and update the saga state because they all connect to the same shared cluster via the client instance.

Each step is recorded as a `SagaStepRecord`:

```java
public class SagaStepRecord implements Serializable {

    private final int stepNumber;
    private final String eventType;
    private final StepStatus status;    // PENDING, COMPLETED, FAILED, COMPENSATED
    private final Instant completedAt;
    private final String failureReason;
}
```

---

## Timeout Handling

Sagas can get stuck. A service might be down, a message might be lost, or a listener might throw an unhandled exception. Without timeout detection, a stuck saga would hang forever — stock reserved but never charged, or charged but never confirmed.

### The SagaTimeoutDetector

The `SagaTimeoutDetector` is a scheduled service that runs in each service instance:

```java
@Component
public class SagaTimeoutDetector {

    @Scheduled(fixedDelayString = "${saga.timeout.check-interval:5000}")
    public void detectTimeouts() {
        List<SagaState> timedOut = sagaStateStore.findTimedOutSagas(
            maxBatchSize
        );

        for (SagaState saga : timedOut) {
            sagaStateStore.markTimedOut(saga.getSagaId());

            if (autoCompensate) {
                compensator.compensate(saga);
            }

            applicationEventPublisher.publishEvent(
                new SagaTimedOutEvent(saga)
            );

            sagaMetrics.recordSagaTimedOut(saga.getSagaType());
        }
    }
}
```

When a saga exceeds its deadline:

1. The state is marked as `TIMED_OUT`
2. If `auto-compensate` is enabled, the `SagaCompensator` publishes compensation events for completed steps
3. A Spring `SagaTimedOutEvent` is published for any local listeners (logging, alerting)
4. Metrics are recorded for monitoring

### Configuration

Timeout behavior is configurable per service:

```yaml
saga:
  timeout:
    enabled: true
    check-interval: 5000          # Check every 5 seconds
    default-deadline: 30000       # 30-second default timeout
    auto-compensate: true         # Auto-trigger compensation
    max-batch-size: 100           # Process up to 100 timeouts per check
    saga-types:
      OrderFulfillment: 60000     # 60 seconds for order fulfillment
```

The Order Fulfillment saga gets a 60-second timeout (longer than the 30-second default) because it spans four services and includes payment processing, which can be slow.

### Auto-Configuration

The timeout system is auto-configured via `SagaTimeoutAutoConfiguration`:

```java
@Configuration
@ConditionalOnProperty(name = "saga.timeout.enabled", havingValue = "true")
@ConditionalOnBean({HazelcastInstance.class, SagaStateStore.class})
public class SagaTimeoutAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SagaTimeoutDetector sagaTimeoutDetector(...) {
        return new SagaTimeoutDetector(sagaStateStore, compensator,
            metrics, publisher, config);
    }
}
```

Set `saga.timeout.enabled=true` and the detector starts automatically. No manual wiring needed.

---

## Monitoring Sagas

### Prometheus Metrics

The `SagaMetrics` class (covered in [Part 4](04-observability-in-event-sourced-systems.md)) exposes:

| Metric | What It Tells You |
|--------|------------------|
| `saga.started` | How many sagas are being created |
| `saga.completed` | How many succeed end-to-end |
| `saga.compensated` | How many required rollback |
| `saga.timedout` | How many exceeded their deadline |
| `saga.duration` (p95) | How long sagas are taking |
| `saga.compensation.duration` (p95) | How long compensation takes |

### Grafana Dashboard

The pre-provisioned Saga Dashboard visualizes these metrics with:

- Active saga count and compensating count (real-time)
- Saga throughput rates filterable by saga type
- Duration percentiles (P50, P95, P99)
- Success rate as a percentage
- Timeout detection rate
- Compensation breakdown with failure tracking

### Alerts

Pre-configured alerts fire when sagas behave unexpectedly:

- **High Saga Failure Rate** (Critical): Any failures in a 5-minute window
- **Saga Timeouts Detected** (Warning): Any timeouts in a 5-minute window
- **Saga Compensation Failures** (Critical): Compensation events fail to publish
- **Low Saga Success Rate** (Warning): Success rate drops below 90%

---

## How to Add a New Saga

Adding a new saga to the framework requires four steps:

### 1. Define Events

Create forward and compensating events for each step. Events should implement `SagaEvent`:

```java
public class MyForwardEvent extends DomainEvent<MyEntity, String>
        implements SagaEvent {
    // Include sagaId, correlationId, sagaType, stepNumber
}

public class MyCompensatingEvent extends DomainEvent<MyEntity, String>
        implements SagaEvent {
    // Same metadata, isCompensating = true
}
```

### 2. Register Compensations

Add entries to a compensation configuration:

```java
registry.register("MyForwardEvent", "MyCompensatingEvent", "my-service");
```

### 3. Create Saga Listeners

Each participating service needs a listener subscribed to relevant events via the shared Hazelcast client instance.

### 4. Configure Timeouts

Add a per-saga-type timeout in `application.yml`:

```yaml
saga:
  timeout:
    saga-types:
      MySagaType: 45000   # 45-second timeout
```

---

## Design Decisions and Trade-offs

### Eventual Consistency

Sagas provide **eventual consistency**, not immediate consistency. Between the time stock is reserved and payment is processed, the system is in a "pending" state. This is intentional — the trade-off buys us service independence and availability.

### Idempotency

Saga listeners must be idempotent. ITopic delivery is at-most-once in Hazelcast, but the timeout detector might trigger compensation for a saga that was actually processing (just slowly). If the original flow completes after compensation starts, the system must handle duplicate events gracefully.

The `SagaState` class handles this by using `updateOrAddStep` which replaces by step number, ensuring a step can't be recorded twice.

### Compensation is Not Rollback

Compensation undoes the *business effect*, not the *technical state*. When we "refund a payment," we don't delete the payment record — we create a new `PaymentRefundedEvent` that changes the payment status to REFUNDED. The event history preserves the full story: payment was processed, then refunded.

This is a natural fit for event sourcing. The compensation itself is just another event.

---

## Summary

The saga pattern solves distributed transaction coordination without the drawbacks of two-phase commit:

- **Choreographed sagas** align naturally with event sourcing — events drive the flow
- **Compensation** provides the equivalent of rollback through new events, preserving the full history
- **Timeout detection** catches stuck sagas and triggers automatic compensation
- **Saga state tracking** provides a complete audit trail of every step
- **Metrics and dashboards** give real-time visibility into saga health

The result is a system where four independent services coordinate a complex business transaction without any service knowing about the others — they only know about events.

---

*Next: [Part 6 - Vector Similarity Search with Hazelcast](06-vector-similarity-search-with-hazelcast.md)*

*Previous: [Part 4 - Observability in Event-Sourced Systems](04-observability-in-event-sourced-systems.md)*
