# Resilience Guide

This guide explains the framework's resilience architecture: which execution paths have resilience protection, why they need different approaches, and how to decide what a new component needs.

## The Core Insight

Not every part of a distributed system needs the same resilience treatment. The cost of failure varies dramatically across execution paths, and the right resilience model depends on that cost — not on a blanket policy of wrapping everything in circuit breakers.

## Execution Paths and Their Resilience Models

The framework has four distinct execution paths, each with different failure characteristics and resilience needs.

### REST Controllers

**Failure cost**: Client gets an HTTP error.

REST controllers (`OrderController`, `PaymentController`, etc.) are synchronous request-response. When something goes wrong, the caller sees a 4xx or 5xx status code and can decide what to do — retry, show an error to the user, or try a different approach.

In this architecture, controllers write to the local embedded Hazelcast instance. They don't make cross-service calls. The resilience need is low because the failure blast radius is a single request from a single client.

**Resilience model**: Spring Boot error handling (`@ControllerAdvice`), input validation. No circuit breakers, DLQ, or idempotency needed. If a controller ever adds an outbound call to an external dependency, that specific call would be a candidate for a circuit breaker — but the controller itself doesn't need one.

### Simple Event Listeners (Cache Listeners)

**Failure cost**: Temporarily stale local cache.

`CustomerCacheListener` and `ProductCacheListener` subscribe to cross-service ITopic events and update local materialized view caches. These are idempotent by nature — applying the same `CustomerUpdated` event twice produces the same result. Missing an event means a stale cache entry until the next event for that entity arrives.

**Resilience model**: Log-and-continue. A `try/catch` with error logging is the appropriate response. No circuit breaker (the operation is local), no DLQ (the next event self-heals), no idempotency guard (updates are naturally idempotent).

```java
// This is sufficient for a cache listener
try {
    viewUpdater.updateDirect(record);
} catch (Exception e) {
    logger.error("Failed to update cache for event: {}", eventType, e);
}
```

### Jet Event Sourcing Pipeline

**Failure cost**: Missed event processing (temporarily).

The Jet pipeline that drives event sourcing — persisting to the event store, updating materialized views, and publishing to the event bus — has its own resilience model built into the Hazelcast Jet runtime.

**Resilience model**: Jet's `AT_LEAST_ONCE` processing guarantee with event journal replay. If a pipeline stage fails, Jet automatically retries it. If the pipeline job restarts (e.g., after a node failure), it replays from the event journal. This is infrastructure-level resilience — the application code doesn't need to add retry logic or error handling beyond what the pipeline framework provides.

### Saga Listeners

**Failure cost**: Stuck distributed transaction — multiple services left in inconsistent state.

This is the critical path. When `PaymentSagaListener` receives a `StockReserved` event and fails to process the payment, the saga is stuck: inventory has reserved stock that nobody will pay for and nobody will release. Without intervention, the stock remains locked indefinitely, the order stays in a pending state, and the saga eventually times out.

**Resilience model**: Full resilience stack.

| Mechanism | Purpose | Why saga listeners need it |
|-----------|---------|---------------------------|
| Circuit breaker | Fail fast when a service is down | Prevents queueing up doomed requests that would all fail and all land in the DLQ |
| Retry with backoff | Handle transient failures | Network blips and brief service restarts shouldn't require manual intervention |
| Idempotency guard | Prevent duplicate processing | The outbox guarantees at-least-once delivery, so duplicates are expected; double-charging a payment is not acceptable |
| Dead letter queue | Capture permanently failed events | After retries and circuit breaker exhaustion, the event must not be lost — it represents an incomplete distributed transaction |
| Timeout detection | Catch sagas that get stuck | Safety net for cases where an event is lost entirely or a listener crashes before reaching the DLQ |
| Event authentication | Verify event origin and integrity | A forged saga event could trigger real payments or stock releases |
| Distributed tracing | End-to-end visibility across services | Debugging a failed saga step requires tracing the event through multiple services |

## Why Saga Listeners Carry the Resilience Burden

The resilience stack is concentrated in saga listeners because they are the one place where a single failure affects multiple services simultaneously. Every other path either fails locally (REST controller), self-heals (cache listener), or has infrastructure-level protection (Jet pipeline).

This is not a design limitation — it reflects the actual risk profile. Adding circuit breakers to `CustomerCacheListener` would add complexity without reducing risk. Adding a DLQ to a Jet pipeline stage would fight against Jet's built-in replay mechanism.

### The Layered Defense

Saga listeners use a specific ordering of resilience checks, and the order matters:

```
Event received
    │
    ▼
1. Unwrap and verify signature (EventAuthenticator)
    │
    ▼
2. Check sagaId presence (ignore non-saga events)
    │
    ▼
3. Check fault injection (before idempotency — see note below)
    │
    ▼
4. Check idempotency (reject duplicates)
    │
    ▼
5. Execute with circuit breaker + retry (ResilientOperations)
    │
    ├── Success: log and continue
    │
    └── Failure: send to DLQ
```

**Why fault injection runs before idempotency**: If the idempotency guard ran first and marked the event as "processed," then fault injection sent it to the DLQ, a later replay of that DLQ entry would be silently rejected as a duplicate. By checking fault injection first, the event is never marked as processed, so replay works correctly.

## Decision Guide: What Does My New Component Need?

When adding a new component, ask these questions:

**1. Does failure affect only the caller?**

If yes (REST controller, query endpoint), standard error handling is sufficient. Let the caller retry.

**2. Is the operation naturally idempotent and self-healing?**

If yes (cache update, view projection), log-and-continue is appropriate. The next event fixes any inconsistency.

**3. Does the component participate in a multi-service workflow?**

If yes, it needs the saga resilience stack: idempotency guard, circuit breaker, DLQ routing, and distributed tracing. The question is not "should I use a saga?" but rather "can a failure here leave other services in an inconsistent state?"

**4. Does the infrastructure provide its own resilience?**

Jet pipelines, Hazelcast replication, and Spring Cloud Gateway all have built-in resilience mechanisms. Adding application-level retry on top of infrastructure retry creates confusion (and potentially exponential retry amplification). Use the infrastructure's mechanisms.

### Do I Need a Saga to Get Resilience?

No. `ResilientOperations`, `IdempotencyGuard`, and `DeadLetterQueueOperations` are all injectable Spring beans available to any component. The saga listeners happen to be the only current consumers because they're the only components where the failure cost justifies the complexity.

If you have a non-saga listener that makes a cross-service call where failure has real consequences (not just a stale cache), inject `ResilientOperations` directly. You don't need to wrap the flow in a saga just to get circuit breaker protection.

```java
// Resilience without a saga — inject the components you need directly
@Component
public class MyImportantListener {

    private final ResilientOperations resilience;    // optional
    private final DeadLetterQueueOperations dlq;     // optional

    @Autowired
    public MyImportantListener(
            @Autowired(required = false) ResilientOperations resilience,
            @Autowired(required = false) DeadLetterQueueOperations dlq) {
        this.resilience = resilience;
        this.dlq = dlq;
    }

    public void handleEvent(GenericRecord record) {
        try {
            if (resilience != null) {
                resilience.executeAsync("my-operation",
                        () -> doWork(record));
            } else {
                doWork(record);
            }
        } catch (Exception e) {
            if (dlq != null) {
                dlq.add(DeadLetterEntry.builder()...build());
            }
        }
    }
}
```

## Resilience by Path: Summary

| Execution Path | Failure Cost | Resilience Model | Components Used |
|----------------|-------------|------------------|-----------------|
| REST controller | Single request fails | HTTP error response, caller retries | `@ControllerAdvice`, validation |
| Cache listener | Stale local view | Log-and-continue, self-healing on next event | Try/catch with logging |
| Jet pipeline | Missed event (temporarily) | Jet AT_LEAST_ONCE, event journal replay | Hazelcast Jet runtime |
| Saga listener | Stuck distributed transaction | Full resilience stack | Circuit breaker, retry, idempotency, DLQ, tracing, timeout detection |
| API Gateway | Routing failure | Per-route circuit breakers, rate limiting | Spring Cloud Gateway built-in |

## Configuration Reference

All resilience components are optional and configured per-service in `application.yml`. See the [Saga Pattern Guide](saga-pattern-guide.md#resilience-configuration) for the full configuration reference including circuit breaker, retry, outbox, DLQ, and idempotency settings.

## Related Documentation

- [Saga Pattern Guide](saga-pattern-guide.md) — Deep dive on saga mechanics, compensation, and timeout handling
- [Performance Tuning Guide](performance-tuning-guide.md) — Tuning resilience parameters for throughput vs safety trade-offs
- [Security Guide](security-guide.md) — Event authentication and signature verification
- [Dashboard Setup Guide](dashboard-setup-guide.md) — Monitoring resilience metrics in Grafana
