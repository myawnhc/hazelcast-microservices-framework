# ADR 007: Choreographed Sagas for Cross-Service Coordination

## Status

**Accepted** - January 2026

## Context

The eCommerce platform requires cross-service coordination for multi-step business processes. The primary use case is **order fulfillment**, which spans three services:

1. **Order Service** - Creates and manages orders
2. **Inventory Service** - Reserves and releases stock
3. **Payment Service** - Processes payments and refunds

When a customer places an order, these services must coordinate:
- Reserve stock before processing payment
- Process payment only after stock is confirmed
- Cancel order and release stock if payment fails
- Handle stuck/unresponsive services via timeouts

Two primary patterns exist for saga coordination:

### Option 1: Orchestration
A central **saga orchestrator** directs each service step-by-step:
- Pros: Centralized flow control, easier to reason about, explicit workflow
- Cons: Single point of failure, tight coupling to orchestrator, more infrastructure

### Option 2: Choreography
Each service **reacts to events** independently, publishing its own events to trigger the next step:
- Pros: Fully decoupled, no central coordinator, aligns with event sourcing
- Cons: Flow is implicit across listeners, harder to trace, requires timeout detection

## Decision

We adopt **choreographed sagas** using Hazelcast ITopic for event-driven coordination between services.

### Saga Flow: Order Fulfillment

```
Step 0: Order Service
  ├── Creates order
  ├── Publishes OrderCreated event (with sagaId)
  └── Starts saga state tracking

Step 1: Inventory Service (listens to OrderCreated)
  ├── Reserves stock for each line item
  ├── Publishes StockReserved event (with sagaId, payment context)
  └── Records step 1 completed

Step 2: Payment Service (listens to StockReserved)
  ├── Processes payment
  ├── Publishes PaymentProcessed event (with sagaId)
  └── Records step 2 completed
  │
  └── On failure: Publishes PaymentFailed event
      └── Triggers compensation flow

Step 3: Order Service (listens to PaymentProcessed)
  ├── Confirms order
  ├── Publishes OrderConfirmed event
  └── Records step 3 completed → Saga COMPLETED
```

### Compensation Strategy

When a step fails, compensation runs in reverse order for completed steps only:

```
Payment fails at Step 2:
  ├── Step 1 compensation: Inventory releases reserved stock (StockReleased)
  └── Step 0 compensation: Order cancelled (OrderCancelled)
  └── Saga finalized as COMPENSATED

Stock unavailable at Step 1:
  └── Step 0 compensation: Order cancelled (OrderCancelled)
  └── No stock release needed (never reserved)
  └── Saga finalized as COMPENSATED
```

Each service owns its own saga listener (`OrderSagaListener`, `InventorySagaListener`, `PaymentSagaListener`) that subscribes to relevant events and triggers local actions.

### Saga State Tracking

A `SagaStateStore` (backed by Hazelcast IMap) tracks each saga instance:
- **sagaId**: Unique identifier propagated through all events
- **correlationId**: Links all events in the business flow
- **status**: STARTED → IN_PROGRESS → COMPLETED / COMPENSATING → COMPENSATED / TIMED_OUT
- **steps**: Record of each step's completion or failure
- **deadline**: Absolute time after which the saga is considered stuck

### Timeout Handling

A `SagaTimeoutDetector` runs periodic checks (configurable interval) to find sagas past their deadline:

1. Queries `SagaStateStore` for active sagas past deadline
2. Marks saga as TIMED_OUT
3. Triggers `SagaCompensator` to run compensation for completed steps
4. Records metrics for monitoring

A `CompensationRegistry` maps forward events to their compensating events and responsible services, enabling the compensator to publish the correct compensation events.

### Event Context Propagation

All saga events carry metadata for correlation and tracking:
- `sagaId` - Links events to the saga instance
- `correlationId` - Links events to the original business request
- `sagaType` - Identifies the saga definition (e.g., "OrderFulfillment")
- `stepNumber` - Identifies which saga step this event represents
- `isCompensating` - Flags compensation events

## Consequences

### Positive

- **Decoupled services**: Each service only knows about events, not other services
- **Natural fit**: Aligns with the event sourcing architecture (ADR 001)
- **Resilient**: No single point of failure; services recover independently
- **Observable**: Saga state store provides complete audit trail of every step
- **Testable**: Each listener is independently unit-testable with mocked services

### Negative

- **Implicit flow**: The saga flow is distributed across service listeners, not visible in one place
- **Debugging complexity**: Tracing a saga requires correlating events across services
- **Timeout dependency**: Stuck sagas rely on periodic timeout detection rather than immediate failure
- **Ordering sensitivity**: Events must carry sufficient context for downstream services

### Mitigations

| Risk | Mitigation |
|------|------------|
| Implicit flow | `SagaCompensationConfig` documents all steps, events, and services as constants |
| Debugging | Correlation IDs and structured logging with MDC enable distributed tracing |
| Stuck sagas | `SagaTimeoutDetector` with configurable check interval and auto-compensation |
| Missing context | Events include payment context (amount, currency, method) for downstream use |
| Duplicate events | `updateOrAddStep` in `SagaState` replaces by step number, ensuring idempotency |

## References

- Chris Richardson: [Saga Pattern](https://microservices.io/patterns/data/saga.html)
- ADR 001: Event Sourcing Pattern (foundation for event-driven coordination)
- `SagaCompensationConfig` - Step and event type constants
- `SagaState` / `HazelcastSagaStateStore` - State tracking implementation
- `SagaTimeoutDetector` / `DefaultSagaCompensator` - Timeout and compensation handling
