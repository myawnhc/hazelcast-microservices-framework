# Transactional Outbox Pattern with Hazelcast

*Part 9 in the "Building Event-Driven Microservices with Hazelcast" series*

---

## Introduction

In [Part 8](08-circuit-breakers-and-retry-for-saga-resilience.md), we added circuit breakers and retry to protect saga listeners against transient failures on the *consumer* side. But the producer side — the moment an event is created and needs to reach the shared cluster — has its own vulnerability.

Recall our [dual-instance architecture](05-saga-pattern-for-distributed-transactions.md): each service runs an **embedded** Hazelcast instance for Jet pipeline processing and a **client** connected to the shared cluster for cross-service ITopic communication. After the Jet pipeline processes an event, the `EventSourcingController` republishes it to the shared cluster so that saga listeners in other services can pick it up.

Here's the problem: that republish step was a fire-and-forget call wrapped in a try/catch that silently swallowed failures:

```java
// The old approach — fragile
try {
    ITopic<GenericRecord> topic = sharedHazelcast.getTopic(pending.eventType);
    topic.publish(pending.eventRecord);
} catch (Exception e) {
    logger.warn("Failed to republish event {}: {}", pending.eventType, e.getMessage());
    // Event is permanently lost!
}
```

If the shared cluster is temporarily unreachable — network partition, cluster restart, maintenance window — the event disappears. The saga never progresses. The only safety net is the saga timeout detector, which eventually marks the saga as failed, but by then the original event data is gone.

The **Transactional Outbox Pattern** solves this by decoupling event *production* from event *delivery*. Instead of publishing directly to the shared cluster, the controller writes the event to a local outbox (an IMap on the embedded Hazelcast instance). A separate publisher component polls the outbox and delivers events to the shared cluster, retrying until delivery succeeds or a maximum retry count is exceeded.

In this article, we'll cover:

- Why direct cross-cluster publishing is fragile
- How the outbox pattern provides guaranteed delivery
- The `OutboxStore` interface and its Hazelcast IMap implementation
- The `OutboxPublisher` polling loop
- Integration with `EventSourcingController`
- Compact serialization for outbox entries
- Configuration and metrics

---

## Why Direct Publishing Fails

The fundamental issue is that publishing to an external system (the shared cluster) and completing a local operation (the Jet pipeline) are two separate operations that cannot be done atomically.

Consider the timeline:

```
Time    Event
─────   ──────────────────────────────────
t=0     Jet pipeline processes OrderCreated event
t=1     Event store updated (local IMap) ✓
t=2     Materialized view updated (local IMap) ✓
t=3     Publish to shared cluster ITopic...
        ╳ Network partition! Connection refused.
t=4     logger.warn("Failed to republish")
        Event is gone. Inventory Service never sees it.
```

The event is safely stored in the local event store, but the cross-service notification is lost. Three options:

1. **Retry in place**: Block the Jet pipeline completion listener and retry. This backs up the pipeline for all events, not just the failed one.
2. **Async retry**: Schedule a retry, but how do you persist the retry state? If the process restarts, the retry is lost too.
3. **Outbox pattern**: Write to a durable local store, then deliver asynchronously. The outbox survives process restarts because it's backed by the same embedded Hazelcast instance that hosts the event store.

The outbox pattern is the standard solution in event-driven architectures. It trades immediate delivery for *guaranteed* delivery.

---

## Architecture

```
EventSourcingController                  OutboxPublisher
         |                                      |
   handleEvent()                          @Scheduled poll
         |                                      |
   Pipeline completes                    outboxStore.pollPending()
         |                                      |
   republishToSharedCluster()            For each PENDING entry:
         |                                      |
   outboxStore.write(entry)              sharedHazelcast.getTopic()
         |                                      .publish(record)
   {domain}_OUTBOX IMap                         |
   (embedded Hazelcast)                  On success:
                                           markDelivered()
                                         On failure:
                                           incrementRetryCount()
                                           (or markFailed if max exceeded)
```

The key insight: the outbox IMap lives on the **embedded** Hazelcast instance — the same instance that hosts the event store and materialized views. Writing to it is a local operation that cannot fail independently of the pipeline.

---

## The OutboxEntry

Each outbox entry captures everything needed to deliver the event:

```java
public class OutboxEntry {

    private String eventId;          // Matches the domain event's eventId
    private String eventType;        // ITopic name (e.g., "OrderCreated")
    private GenericRecord eventRecord; // The serialized event to publish
    private int retryCount;          // Delivery attempts so far
    private Status status;           // PENDING, DELIVERED, or FAILED
    private Instant createdAt;       // When the entry was created
    private Instant lastAttemptAt;   // When the last delivery attempt occurred
    private String failureReason;    // Most recent failure message

    public OutboxEntry(String eventId, String eventType, GenericRecord eventRecord) {
        this.eventId = Objects.requireNonNull(eventId);
        this.eventType = Objects.requireNonNull(eventType);
        this.eventRecord = Objects.requireNonNull(eventRecord);
        this.retryCount = 0;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
    }

    public enum Status {
        PENDING,    // Awaiting delivery
        DELIVERED,  // Successfully published to shared cluster
        FAILED      // Permanently failed after max retries
    }
}
```

The `eventRecord` field holds the full `GenericRecord` that needs to be published to the shared cluster's ITopic. This is the same record that the Jet pipeline produces — it contains all event fields including saga metadata like `sagaId` and `correlationId`.

---

## The OutboxStore Interface

The store interface is deliberately simple — six methods that cover the full lifecycle:

```java
public interface OutboxStore {

    void write(OutboxEntry entry);

    List<OutboxEntry> pollPending(int maxBatchSize);

    void markDelivered(String eventId);

    void markFailed(String eventId, String reason);

    void incrementRetryCount(String eventId, String failureReason);

    long pendingCount();
}
```

This interface is provider-agnostic. The Hazelcast implementation uses an IMap, but the same interface could be backed by a relational database or any other durable store.

---

## HazelcastOutboxStore: IMap with Compact Serialization

The Hazelcast implementation stores outbox entries as Compact-serialized `GenericRecord` values in an IMap:

```java
public class HazelcastOutboxStore implements OutboxStore {

    private static final String SCHEMA_NAME = "OutboxEntry";
    private final IMap<String, GenericRecord> outboxMap;
    private final MeterRegistry meterRegistry;

    public HazelcastOutboxStore(HazelcastInstance hazelcast, MeterRegistry meterRegistry) {
        this.outboxMap = hazelcast.getMap(DEFAULT_MAP_NAME);
        this.meterRegistry = meterRegistry;
    }
}
```

### Why GenericRecord Instead of Plain Java Objects?

You might expect the IMap to hold `OutboxEntry` Java objects directly. However, `OutboxEntry` contains an `Instant` field and a nested `GenericRecord` field — neither of which Hazelcast's zero-config Compact serialization handles automatically. Rather than writing a custom `CompactSerializer` (which would need to be registered on every Hazelcast instance configuration), we convert at the boundary:

```java
static GenericRecord toRecord(final OutboxEntry entry) {
    return GenericRecordBuilder.compact(SCHEMA_NAME)
            .setString("eventId", entry.getEventId())
            .setString("eventType", entry.getEventType())
            .setGenericRecord("eventRecord", entry.getEventRecord())
            .setInt32("retryCount", entry.getRetryCount())
            .setString("status", entry.getStatus().name())
            .setInt64("createdAt", entry.getCreatedAt().toEpochMilli())
            .setNullableInt64("lastAttemptAt",
                    entry.getLastAttemptAt() != null
                            ? entry.getLastAttemptAt().toEpochMilli() : null)
            .setString("failureReason", entry.getFailureReason())
            .build();
}
```

Key design choices:

- **`Instant` as `int64` epoch millis**: Hazelcast Compact doesn't have an `Instant` type. Epoch millis is compact, sortable, and unambiguous.
- **`setNullableInt64` for `lastAttemptAt`**: This field is `null` when no delivery attempt has been made yet. Hazelcast Compact has nullable variants for numeric types.
- **`setGenericRecord` for nested event**: Compact serialization handles nested `GenericRecord` natively — no special handling needed.
- **`status` as `String`**: Stored as the enum name for readability in Hazelcast Management Center and for `Predicates.equal()` queries.

### Polling Pending Entries

The `pollPending` method uses a Hazelcast predicate to filter by status:

```java
@Override
public List<OutboxEntry> pollPending(final int maxBatchSize) {
    final Collection<GenericRecord> pending = outboxMap.values(
            Predicates.equal("status", OutboxEntry.Status.PENDING.name()));

    return pending.stream()
            .map(HazelcastOutboxStore::fromRecord)
            .sorted(Comparator.comparing(OutboxEntry::getCreatedAt))
            .limit(maxBatchSize)
            .collect(Collectors.toList());
}
```

Entries are sorted by `createdAt` (oldest first) to ensure FIFO ordering. The `maxBatchSize` limits how many entries are processed per polling cycle, preventing the publisher from being overwhelmed after a long outage when many entries have accumulated.

---

## The OutboxPublisher

The publisher is the workhorse that bridges the outbox and the shared cluster:

```java
public class OutboxPublisher {

    private final OutboxStore outboxStore;
    private final HazelcastInstance sharedHazelcast;
    private final OutboxProperties properties;
    private final MeterRegistry meterRegistry;
    private volatile boolean noSharedClusterWarningLogged = false;

    public void publishPendingEntries() {
        if (sharedHazelcast == null) {
            if (!noSharedClusterWarningLogged) {
                logger.warn("No shared Hazelcast instance configured — outbox delivery skipped");
                noSharedClusterWarningLogged = true;
            }
            return;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        List<OutboxEntry> pending = outboxStore.pollPending(properties.getMaxBatchSize());

        if (pending.isEmpty()) {
            return;
        }

        int delivered = 0;
        int failed = 0;

        for (OutboxEntry entry : pending) {
            try {
                ITopic<GenericRecord> topic = sharedHazelcast.getTopic(entry.getEventType());
                topic.publish(entry.getEventRecord());
                outboxStore.markDelivered(entry.getEventId());
                delivered++;
            } catch (Exception e) {
                failed++;
                if (entry.getRetryCount() + 1 >= properties.getMaxRetries()) {
                    outboxStore.markFailed(entry.getEventId(),
                            "Max retries exceeded: " + e.getMessage());
                    meterRegistry.counter("outbox.entries.failed").increment();
                } else {
                    outboxStore.incrementRetryCount(entry.getEventId(), e.getMessage());
                }
            }
        }

        if (delivered > 0) {
            meterRegistry.counter("outbox.entries.delivered").increment(delivered);
        }
        sample.stop(meterRegistry.timer("outbox.publish.duration"));
    }
}
```

### Graceful Degradation

When no shared Hazelcast instance is configured (e.g., in single-node development mode), the publisher logs a warning once and exits. Events remain in PENDING status in the outbox — they'll be delivered when the shared cluster becomes available.

### Retry Escalation

Each entry tracks its own retry count. The progression:

```
Attempt 1: Publish fails → incrementRetryCount (retryCount=1)
Attempt 2: Publish fails → incrementRetryCount (retryCount=2)
...
Attempt 5: Publish fails → markFailed (retryCount=5 >= maxRetries=5)
```

Once an entry is marked FAILED, it stops appearing in `pollPending()` results. The failure reason is preserved for debugging.

### Scheduling

The `OutboxAutoConfiguration` sets up the polling schedule:

```java
@EnableScheduling
public class OutboxAutoConfiguration {

    @Bean
    public OutboxPublisher outboxPublisher(OutboxStore outboxStore, ...) {
        return new OutboxPublisher(outboxStore, sharedHazelcast, properties, meterRegistry);
    }

    @Scheduled(fixedDelayString = "${framework.outbox.poll-interval:1000}")
    public void publishOutbox() {
        outboxPublisher.publishPendingEntries();
    }
}
```

The default poll interval is 1 second, configurable via `framework.outbox.poll-interval`. This means events reach the shared cluster within 1 second of being written to the outbox under normal conditions — a small latency trade-off for guaranteed delivery.

---

## Integration with EventSourcingController

The controller's `republishToSharedCluster` method now checks for an outbox store before falling back to direct publishing:

```java
private void republishToSharedCluster(PendingCompletion<K> pending) {
    if (sharedHazelcast == null || pending.eventRecord == null || pending.eventType == null) {
        return;
    }
    if (outboxStore != null) {
        // Durable outbox path — OutboxPublisher handles actual delivery
        OutboxEntry entry = new OutboxEntry(
                pending.completionInfo.getEventId(),
                pending.eventType,
                pending.eventRecord
        );
        outboxStore.write(entry);
        logger.debug("Wrote event {} to outbox for deferred shared cluster delivery",
                pending.eventType);
    } else {
        // Legacy direct publish (when outbox is disabled)
        try {
            ITopic<GenericRecord> topic = sharedHazelcast.getTopic(pending.eventType);
            topic.publish(pending.eventRecord);
        } catch (Exception e) {
            logger.warn("Failed to republish event {}: {}", pending.eventType, e.getMessage());
        }
    }
}
```

This is fully backward compatible:

- When `outboxStore` is injected (outbox enabled): Events go through the durable outbox path.
- When `outboxStore` is null (outbox disabled or not configured): The legacy direct-publish behavior is preserved.

The `OutboxStore` is wired into the controller through each service's configuration:

```java
@Bean
public EventSourcingController<Order, String, DomainEvent<Order, String>> orderController(
        HazelcastInstance hazelcastInstance,
        @Qualifier("hazelcastClient") HazelcastInstance hazelcastClient,
        @Autowired(required = false) OutboxStore outboxStore,
        ...) {
    return EventSourcingController.builder()
            .hazelcast(hazelcastInstance)
            .sharedHazelcast(hazelcastClient)
            .outboxStore(outboxStore)  // nullable — graceful degradation
            .build();
}
```

---

## Delivery Guarantees

The outbox pattern provides **at-least-once delivery**:

- An event may be delivered multiple times if the publisher crashes after publishing to the ITopic but before calling `markDelivered()`. The next poll cycle will pick up the same entry and redeliver it.
- Events are never lost (as long as the embedded Hazelcast instance's IMap data is intact).

At-least-once delivery means consumers must handle duplicates. This is where the [Idempotency Guard](10-dead-letter-queues-and-idempotency.md) (covered in Part 10) comes in — it deduplicates events at the consumer side, complementing the outbox's guaranteed delivery.

### Ordering

Events for the same domain aggregate are written to the outbox in sequence number order (since the Jet pipeline processes them sequentially). The `pollPending` method sorts by `createdAt`, preserving this order.

However, if two events are in the outbox simultaneously and the first one fails while the second succeeds, they'll be delivered out of order. For our saga use case this is acceptable — each saga step is identified by `sagaId` and `eventType`, and duplicate/out-of-order events are handled by the saga state machine.

---

## Configuration Reference

### `framework.outbox.*`

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Master toggle for the outbox pattern |
| `poll-interval` | `1000` (ms) | How often the publisher polls for pending entries |
| `max-batch-size` | `50` | Maximum entries per poll cycle |
| `max-retries` | `5` | Delivery attempts before marking as FAILED |
| `entry-ttl` | `24h` | How long DELIVERED entries survive in the map |

### Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `outbox.entries.written` | Counter | Events written to the outbox |
| `outbox.entries.delivered` | Counter | Events successfully delivered to shared cluster |
| `outbox.entries.failed` | Counter | Events permanently failed after max retries |
| `outbox.publish.duration` | Timer | Time per publish cycle |

---

## Outbox vs. Direct Publishing: When to Use Each

| Scenario | Approach |
|----------|----------|
| Production with shared cluster | Outbox (default) |
| Single-node development | Either — outbox queues until cluster appears |
| Performance testing | Direct publish (lower latency, no durability) |
| No cross-service communication | Neither — don't configure `sharedHazelcast` |

To disable the outbox and use direct publishing:

```yaml
framework:
  outbox:
    enabled: false
```

---

## What's Next

The outbox guarantees that events reach the shared cluster. But once they arrive, what happens if a consumer fails to process them? The consumer might crash, the business logic might throw an exception, or the circuit breaker might be open.

In [Part 10](10-dead-letter-queues-and-idempotency.md), we add two complementary patterns: a **Dead Letter Queue** that captures events that fail consumer-side processing, and an **Idempotency Guard** that prevents duplicate processing — the natural consequence of the outbox's at-least-once delivery guarantee.
