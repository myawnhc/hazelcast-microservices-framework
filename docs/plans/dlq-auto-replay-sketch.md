# Automated DLQ Replay — Feature Sketch

**Status**: Sketch (low priority — "we've thought about it" artifact)
**Date**: 2026-03-17
**Context**: The framework currently supports manual DLQ replay via REST endpoints and
MCP tools. This sketch explores automated replay triggered by circuit breaker recovery.

---

## The Problem

When a downstream service goes unhealthy (network blip, pod restart, resource pressure),
the saga listeners catch failures and route them to the DLQ. The circuit breaker opens,
preventing further damage. Eventually the service recovers, the circuit breaker closes —
but the DLQ entries just sit there. Someone has to manually replay them via curl or the
MCP server.

In a real production system, that manual step is the weak link. You've built all this
resilience machinery — circuit breakers, DLQ, retry logic — but the last mile (getting
failed transactions back into the flow after recovery) is a human process.

## What We Have Today

| Component | State |
|-----------|-------|
| `DeadLetterQueueOperations` | Interface with `replay(id)`, `list()`, `count()` |
| `HazelcastDeadLetterQueue` | Stores entries as GenericRecord, tracks `replayCount` and `status` |
| `DeadLetterQueueController` | REST: `POST /api/admin/dlq/{id}/replay` |
| `ResilientServiceInvoker` | Wraps calls with circuit breaker + retry |
| `RetryEventListener` | Logs retry events, publishes metrics |
| Circuit breaker event listener | **Does not exist yet** |
| Automated replay | **Does not exist** |

The gap is small: we need to observe circuit breaker state transitions and connect
them to the existing replay mechanism.

## Proposed Design

### Trigger: Circuit Breaker State Transitions

The smart trigger is the circuit breaker closing (or transitioning to half-open).
This means the downstream service is accepting calls again — exactly when you want
to retry failed work.

```
CB OPEN → HALF_OPEN:  Replay a small batch (probe — are we really healthy?)
CB HALF_OPEN → CLOSED: Replay remaining entries (confirmed healthy)
CB CLOSED → OPEN:      Stop replaying immediately
```

This is event-driven, not polling. Resilience4j's `CircuitBreakerRegistry` exposes
an event publisher that fires on state transitions. We already use this pattern in
`RetryEventListener` for retry events.

### New Component: `CircuitBreakerEventListener`

Analogous to the existing `RetryEventListener`:

- Subscribe to `CircuitBreakerRegistry.getEventPublisher()`
- Log all state transitions (WARN for OPEN, INFO for CLOSED)
- Publish Micrometer metrics: `framework.resilience.circuitbreaker.transitions`
- On HALF_OPEN or CLOSED transition: notify the replay coordinator

This component has standalone value even without auto-replay — we're missing CB
state transition logging and metrics today.

### New Component: `DlqReplayCoordinator`

Orchestrates replay decisions:

```java
public class DlqReplayCoordinator {

    void onCircuitBreakerRecovery(String circuitBreakerName) {
        // 1. Map CB name to DLQ topic filter
        //    e.g., "inventory-stock-reservation" → topicName "OrderCreated"
        // 2. Query DLQ for PENDING entries matching that topic
        // 3. Filter by minimum age (don't replay entries from the current incident)
        // 4. Replay in small batches with backoff
        // 5. Stop if any replay fails (CB may re-open)
    }
}
```

**Key behaviors:**

- **Selective replay**: Only replay entries related to the recovered circuit breaker,
  not the entire DLQ. The mapping from CB name to DLQ topic is the main piece of
  configuration needed.
- **Minimum age**: Don't replay entries younger than N seconds. During a failure burst,
  entries are still arriving while the CB is opening. Wait for things to settle.
- **Batch + backoff**: Replay a few entries, pause, replay more. Don't slam a
  just-recovered service with the full backlog at once.
- **Replay cap**: Respect `DeadLetterEntry.replayCount`. After N failed replays,
  an entry should be escalated (alert, manual review), not retried forever.
- **Idempotency**: The saga listeners already have idempotency guards — replayed
  events that were partially processed won't cause duplicates.

### Configuration

```yaml
framework:
  dlq:
    auto-replay:
      enabled: false          # opt-in, not on by default
      min-age-seconds: 60     # entries must be at least this old
      batch-size: 5           # entries per replay batch
      batch-delay-ms: 2000    # pause between batches
      max-replay-count: 3     # give up after this many replays
```

Default `enabled: false` is important — this is an enhancement, not a requirement.
Manual replay via REST and MCP remains the primary interface.

### Fallback: Scheduled Sweep

As a simpler alternative (or complement) to event-driven replay:

```java
@Scheduled(fixedDelay = 30000)  // every 30 seconds
void sweepDeadLetterQueue() {
    // For each PENDING entry older than min-age:
    //   Check if the relevant circuit breaker is CLOSED
    //   If yes, attempt replay
}
```

Less elegant but more robust — doesn't depend on catching every state transition
event. In practice, you'd probably want both: event-driven for fast recovery,
scheduled sweep as a safety net.

## Complexity Assessment

**Low-medium complexity.** The building blocks exist:

- DLQ has `replay()`, `list()`, status tracking, replay counting
- Resilience4j provides circuit breaker event publishers
- `RetryEventListener` is a template for the CB event listener
- Saga listeners already handle idempotency

**New code needed:**
- `CircuitBreakerEventListener` (~80 lines, modeled on `RetryEventListener`)
- `DlqReplayCoordinator` (~150 lines)
- `DlqAutoReplayAutoConfiguration` (~40 lines)
- Configuration properties class (~30 lines)
- Tests (~200 lines)
- CB-name-to-topic mapping (configuration, not code)

Roughly 500 lines of new code, plus configuration. A day's work, maybe two with
thorough testing.

## Demo Value Assessment

**Honest take: moderate.** It's satisfying to show ("watch — I kill the payment
service, orders fail to DLQ, payment restarts, circuit breaker closes, and the
orders automatically replay"). But:

- The demo already shows resilience via circuit breakers and DLQ
- Manual replay via MCP is arguably *more* impressive in a demo ("ask the AI to
  fix it") than a background process doing it silently
- The feature is best appreciated by ops-minded audiences, not typical conference
  attendees

**Best as**: A talking point with a design document behind it, or a brief addition
to the resilience blog post. Implementation is warranted if we find ourselves
demoing the failure/recovery cycle frequently and the manual replay step is
slowing things down.

## Open Questions

1. **CB-name-to-topic mapping**: The circuit breaker names (`inventory-stock-reservation`,
   `payment-processing`, etc.) don't directly correspond to DLQ topic names
   (`OrderCreated`, `StockReserved`, etc.). We need a mapping. Convention-based?
   Configuration? Annotation on the saga listener?

2. **Cross-service coordination**: Each service has its own DLQ and circuit breakers.
   Auto-replay runs per-service. But a saga failure might deposit entries in multiple
   services' DLQs. Is coordinated cross-service replay needed, or is per-service
   sufficient? (Probably sufficient — each service replays its own failures
   independently, and the saga orchestration handles the rest.)

3. **Replay ordering**: Should DLQ entries be replayed in FIFO order (oldest first)?
   For saga correctness, order probably matters within a single saga instance but not
   across instances. The current `replay()` method doesn't enforce ordering.

4. **Metrics overlap with manual replay**: The existing `dlq.entries.replayed` metric
   doesn't distinguish manual from auto replay. Should it? A tag would be simple:
   `replay.trigger=manual|auto|sweep`.