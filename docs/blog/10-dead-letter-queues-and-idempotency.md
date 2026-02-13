# Dead Letter Queues and Idempotency Guards

*Part 10 in the "Building Event-Driven Microservices with Hazelcast" series*

---

## Introduction

Over the past two articles, we built resilience into both sides of our saga communication:

- [Part 8](08-circuit-breakers-and-retry-for-saga-resilience.md): Circuit breakers and retry protect saga listeners against transient failures during event *consumption*.
- [Part 9](09-transactional-outbox-pattern-with-hazelcast.md): The transactional outbox pattern guarantees event *delivery* from producer to shared cluster.

But two gaps remain:

1. **What happens when an event fails processing permanently?** The circuit breaker exhausts retries. The `NonRetryableException` is thrown. The event is gone — only a log message survives. There's no way to inspect what failed, understand why, or retry it later when the issue is resolved.

2. **What happens when the outbox delivers an event twice?** At-least-once delivery means duplicates are possible. Without protection, the Inventory Service might reserve stock twice for the same order. The Payment Service might charge the customer twice.

This article covers two complementary patterns that close these gaps:

- **Dead Letter Queue (DLQ)**: A durable store for events that failed consumer-side processing, with admin endpoints for inspection, replay, and discard.
- **Idempotency Guard**: An atomic check-and-claim mechanism that ensures each event is processed exactly once, even if delivered multiple times.

Together, the outbox (at-least-once delivery) and idempotency guard (exactly-once processing) provide **effectively-once semantics** — the gold standard for event-driven systems.

---

## Part 1: Dead Letter Queue

### The Problem

Consider this failure sequence in the Inventory Service's saga listener:

```
1. OrderCreated event arrives on shared cluster ITopic
2. InventorySagaListener picks it up
3. executeWithResilience("inventory-stock-reservation", ...) is called
4. Retry attempt 1: InsufficientStockException (non-retryable)
5. Circuit breaker records failure
6. ResilienceException thrown
7. logger.error("Failed to process event: {}", eventId, error)
8. ← Event is gone
```

The log message is the only trace of what happened. In production, you'd need to:

1. Search logs for the event ID
2. Reconstruct the event payload from other sources
3. Manually fix the underlying issue
4. Figure out how to re-trigger the saga step

A dead letter queue captures the failed event — complete with its payload, failure reason, saga context, and source service — in a durable store that an administrator can inspect and act on.

### The DeadLetterEntry

Each DLQ entry preserves the full failure context:

```java
public class DeadLetterEntry {

    private String dlqEntryId;       // UUID — unique DLQ identifier
    private String originalEventId;  // The event that failed
    private String eventType;        // "OrderCreated", "StockReserved", etc.
    private String topicName;        // The ITopic where the event was published
    private GenericRecord eventRecord; // The complete event payload for replay
    private String failureReason;    // Why processing failed
    private Instant failureTimestamp; // When the failure occurred
    private String sourceService;    // Which service failed ("inventory-service")
    private String sagaId;           // Saga context for tracing
    private String correlationId;    // Correlation context for tracing
    private int replayCount;         // How many times this entry has been replayed
    private Status status;           // PENDING, REPLAYED, or DISCARDED

    public enum Status {
        PENDING,    // Awaiting review or replay
        REPLAYED,   // Re-published to original topic
        DISCARDED   // Manually discarded by administrator
    }
}
```

The builder pattern provides clean construction at the failure site:

```java
DeadLetterEntry.builder()
    .originalEventId(eventId)
    .eventType(record.getString("eventType"))
    .topicName("OrderCreated")
    .eventRecord(record)
    .failureReason(error.getMessage())
    .sourceService("inventory-service")
    .sagaId(record.getString("sagaId"))
    .correlationId(record.getString("correlationId"))
    .build();
```

The `eventRecord` field is critical — it holds the complete `GenericRecord` that was published to the ITopic. When an administrator replays the entry, this exact record is re-published to the original topic, triggering the saga step again.

### The DeadLetterQueueOperations Interface

Following the same interface-extraction pattern used for `ResilientOperations` and `ServiceClientOperations` (for Java 25 Mockito compatibility), the DLQ exposes its capabilities through an interface:

```java
public interface DeadLetterQueueOperations {

    void add(DeadLetterEntry entry);

    List<DeadLetterEntry> list(int limit);

    DeadLetterEntry getEntry(String dlqEntryId);

    void replay(String dlqEntryId);

    void discard(String dlqEntryId);

    long count();
}
```

### HazelcastDeadLetterQueue: IMap-Backed Storage

The implementation stores DLQ entries as Compact-serialized GenericRecords in a Hazelcast IMap — the same pattern used by the `HazelcastOutboxStore`:

```java
public class HazelcastDeadLetterQueue implements DeadLetterQueueOperations {

    private static final String SCHEMA_NAME = "DeadLetterEntry";
    private final HazelcastInstance hazelcast;
    private final IMap<String, GenericRecord> dlqMap;
    private final DeadLetterQueueProperties properties;
    private final MeterRegistry meterRegistry;

    public HazelcastDeadLetterQueue(HazelcastInstance hazelcast,
                                     DeadLetterQueueProperties properties,
                                     MeterRegistry meterRegistry) {
        this.hazelcast = hazelcast;
        this.dlqMap = hazelcast.getMap(properties.getMapName());
        // ...
    }
}
```

The DLQ map lives on the **shared cluster** (or falls back to the embedded instance), making it accessible from any service in the cluster. This means an administrator can query the DLQ from any service's admin endpoint.

### POJO-to-GenericRecord Conversion

Like the outbox store, the DLQ converts between POJOs and GenericRecords at the boundary:

```java
static GenericRecord toRecord(final DeadLetterEntry entry) {
    return GenericRecordBuilder.compact(SCHEMA_NAME)
            .setString("dlqEntryId", entry.getDlqEntryId())
            .setString("originalEventId", entry.getOriginalEventId())
            .setString("eventType", entry.getEventType())
            .setString("topicName", entry.getTopicName())
            .setGenericRecord("eventRecord", entry.getEventRecord())
            .setString("failureReason", entry.getFailureReason())
            .setInt64("failureTimestamp", entry.getFailureTimestamp().toEpochMilli())
            .setString("sourceService", entry.getSourceService())
            .setString("sagaId", entry.getSagaId())
            .setString("correlationId", entry.getCorrelationId())
            .setInt32("replayCount", entry.getReplayCount())
            .setString("status", entry.getStatus().name())
            .build();
}
```

The `eventRecord` field uses `setGenericRecord()` — Compact serialization nests GenericRecords naturally, so the complete event payload is preserved without any special handling.

### Replay: Re-Publishing to the Original Topic

The most powerful DLQ operation is replay. When an administrator determines that the underlying issue has been fixed (the service is back, stock has been restocked, the payment provider is responsive), they can replay the entry:

```java
@Override
public void replay(final String dlqEntryId) {
    final GenericRecord record = dlqMap.get(dlqEntryId);
    if (record == null) {
        throw new IllegalArgumentException("DLQ entry not found: " + dlqEntryId);
    }

    final DeadLetterEntry entry = fromRecord(record);

    if (entry.getStatus() != DeadLetterEntry.Status.PENDING) {
        throw new IllegalStateException(
                "Cannot replay entry in status " + entry.getStatus());
    }
    if (entry.getReplayCount() >= properties.getMaxReplayAttempts()) {
        throw new IllegalStateException(
                "Max replay attempts (" + properties.getMaxReplayAttempts() + ") exceeded");
    }

    // Re-publish to the original topic
    final GenericRecord eventRecord = entry.getEventRecord();
    if (eventRecord != null && entry.getTopicName() != null) {
        final ITopic<GenericRecord> topic = hazelcast.getTopic(entry.getTopicName());
        topic.publish(eventRecord);
    }

    // Update entry status
    entry.setReplayCount(entry.getReplayCount() + 1);
    entry.setStatus(DeadLetterEntry.Status.REPLAYED);
    dlqMap.set(dlqEntryId, toRecord(entry));

    meterRegistry.counter("dlq.entries.replayed").increment();
}
```

Safety guards:

- **Status check**: Only PENDING entries can be replayed. This prevents accidentally replaying an entry that was already replayed or discarded.
- **Replay limit**: A maximum replay count (configurable, default 3) prevents infinite replay loops. If the underlying issue isn't actually fixed, replaying will just fail again — the limit prevents runaway cycles.
- **Null safety**: If the `eventRecord` is null (which shouldn't happen in normal operation but could in edge cases), the status is still updated without publishing.

### Count: Monitoring Queue Depth

The `count()` method uses a Hazelcast predicate to count only PENDING entries:

```java
@Override
public long count() {
    final Collection<GenericRecord> pending = dlqMap.values(
            Predicates.equal("status", DeadLetterEntry.Status.PENDING.name()));
    return pending.size();
}
```

This powers monitoring alerts: a DLQ count above zero for more than a few minutes typically indicates an issue that needs investigation.

### Admin REST Endpoints

The `DeadLetterQueueController` exposes the DLQ through REST endpoints:

```java
@RestController
@RequestMapping("/api/admin/dlq")
@Tag(name = "Dead Letter Queue")
public class DeadLetterQueueController {

    @GetMapping
    public ResponseEntity<List<DeadLetterEntry>> list(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(deadLetterQueue.list(limit));
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Long>> count() {
        return ResponseEntity.ok(Map.of("count", deadLetterQueue.count()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeadLetterEntry> getEntry(@PathVariable String id) { ... }

    @PostMapping("/{id}/replay")
    public ResponseEntity<Map<String, String>> replay(@PathVariable String id) { ... }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> discard(@PathVariable String id) { ... }
}
```

A typical investigation flow:

```bash
# 1. Check if there are any pending DLQ entries
curl http://localhost:8082/api/admin/dlq/count
# {"count": 2}

# 2. List the entries
curl http://localhost:8082/api/admin/dlq
# [{"dlqEntryId":"abc-123", "originalEventId":"evt-456",
#   "eventType":"OrderCreated", "failureReason":"Insufficient stock for product PROD-789",
#   "sourceService":"inventory-service", "status":"PENDING", ...}]

# 3. Inspect a specific entry
curl http://localhost:8082/api/admin/dlq/abc-123

# 4. After restocking inventory, replay the event
curl -X POST http://localhost:8082/api/admin/dlq/abc-123/replay
# {"status":"replayed", "dlqEntryId":"abc-123"}

# 5. Or discard if the saga has already timed out
curl -X DELETE http://localhost:8082/api/admin/dlq/abc-123
# {"status":"discarded", "dlqEntryId":"abc-123"}
```

### Integration with Saga Listeners

Each saga listener injects the DLQ as an optional dependency:

```java
@Autowired(required = false)
public void setDeadLetterQueue(DeadLetterQueueOperations deadLetterQueue) {
    this.deadLetterQueue = deadLetterQueue;
}
```

And sends failed events to the DLQ in the error handler:

```java
private void sendToDeadLetterQueue(GenericRecord record, String topicName, Throwable error) {
    String eventId = record.getString("eventId");
    if (deadLetterQueue != null) {
        try {
            deadLetterQueue.add(DeadLetterEntry.builder()
                    .originalEventId(eventId)
                    .eventType(record.getString("eventType"))
                    .topicName(topicName)
                    .eventRecord(record)
                    .failureReason(error.getMessage())
                    .sourceService("inventory-service")
                    .sagaId(record.getString("sagaId"))
                    .correlationId(record.getString("correlationId"))
                    .build());
            logger.warn("Event {} sent to DLQ after failure: {}", eventId, error.getMessage());
        } catch (Exception dlqError) {
            logger.error("Failed to send event {} to DLQ: {}", eventId, dlqError.getMessage());
        }
    } else {
        // Fallback: existing behavior (log only)
        if (error instanceof ResilienceException) {
            logger.warn("Circuit breaker open, saga step deferred: eventId={}", eventId);
        } else {
            logger.error("Failed to process event: {}", eventId, error);
        }
    }
}
```

The try/catch around the DLQ add is defensive — if the DLQ itself fails (shared cluster unreachable), we fall back to logging. The DLQ is a best-effort capture mechanism, not a hard requirement.

---

## Part 2: Idempotency Guard

### The Problem

The transactional outbox provides at-least-once delivery. Combined with ITopic's own delivery semantics (which can deliver messages to listeners that reconnect after a brief disconnection), the same event may arrive at a consumer multiple times:

```
OutboxPublisher                    Shared Cluster                 Inventory Listener
      |                                  |                               |
      |── publish(OrderCreated) ────────►|───────────────────────────►  |
      |   (success, but markDelivered    |                               |
      |    call fails due to timeout)    |                               |
      |                                  |                               |
      |   ... next poll cycle ...        |                               |
      |── publish(OrderCreated) ────────►|───────────────────────────►  |
      |   (duplicate!)                   |                            ⚠️ Double
      |                                  |                               reservation!
```

Without protection, the Inventory Service reserves stock twice. The customer is charged twice. The order is confirmed twice.

### The Solution: Atomic Check-and-Claim

The idempotency guard uses Hazelcast's `putIfAbsent` operation — an atomic, cluster-wide check-and-set — to ensure each event ID is processed exactly once:

```java
public class HazelcastIdempotencyGuard implements IdempotencyGuard {

    private final IMap<String, Long> processedEventsMap;
    private final long ttlMillis;
    private final MeterRegistry meterRegistry;

    public HazelcastIdempotencyGuard(HazelcastInstance hazelcast,
                                      IdempotencyProperties properties,
                                      MeterRegistry meterRegistry) {
        this.processedEventsMap = hazelcast.getMap(properties.getMapName());
        this.ttlMillis = properties.getTtl().toMillis();
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean tryProcess(final String eventId) {
        Long previous = processedEventsMap.putIfAbsent(
                eventId, System.currentTimeMillis(), ttlMillis, TimeUnit.MILLISECONDS);

        boolean firstTime = (previous == null);
        meterRegistry.counter("idempotency.checks",
                "result", firstTime ? "miss" : "hit").increment();

        if (!firstTime) {
            logger.debug("Duplicate event detected: eventId={}", eventId);
        }

        return firstTime;
    }
}
```

The interface is deliberately minimal:

```java
public interface IdempotencyGuard {
    boolean tryProcess(String eventId);
}
```

One method. Returns `true` if this is the first time the event ID has been seen (the caller should process it). Returns `false` if a previous call already claimed it (the caller should skip it).

### How putIfAbsent Works

Hazelcast's `IMap.putIfAbsent(key, value, ttl, timeUnit)` is an atomic operation:

1. If the key does not exist: inserts the key-value pair with the specified TTL, returns `null`
2. If the key already exists: does nothing, returns the existing value

This atomicity is guaranteed even across multiple Hazelcast cluster members. Two listeners on different nodes processing the same event simultaneously will never both see `null` — exactly one will claim the event, and the other will see the first caller's value.

### TTL: Forgetting Old Events

The `putIfAbsent` call includes a TTL (default: 1 hour). After the TTL expires, the event ID is removed from the map, allowing the same event to be reprocessed if it arrives much later.

Why would the same event arrive an hour later? Typically it wouldn't — the TTL is a safety measure against unbounded memory growth. Without TTL, the processed events map would grow forever. With a 1-hour TTL, the map holds at most 1 hour's worth of event IDs, which is bounded.

The TTL should be longer than the maximum expected duplicate delivery window (how long after the first delivery a duplicate might arrive). For our outbox publisher with a 1-second poll interval and 5 retries, duplicates arrive within seconds — a 1-hour TTL provides ample margin.

### Integration with Saga Listeners

Each saga listener checks the idempotency guard at the top of every message handler:

```java
class OrderCreatedListener implements MessageListener<GenericRecord> {

    @Override
    public void onMessage(Message<GenericRecord> message) {
        GenericRecord record = message.getMessageObject();

        String eventId = record.getString("eventId");
        if (idempotencyGuard != null && eventId != null
                && !idempotencyGuard.tryProcess(eventId)) {
            logger.debug("Duplicate event {} already processed, skipping", eventId);
            return;
        }

        // ... proceed with normal processing
    }
}
```

Three null checks ensure graceful degradation:

1. **`idempotencyGuard != null`**: If idempotency is disabled or not configured, all events are processed (no deduplication).
2. **`eventId != null`**: If the event doesn't have an event ID (shouldn't happen, but defensive), skip the check.
3. **`tryProcess()` returns false**: Duplicate detected, skip processing.

### The Processed Events Map

The map is configured on the **shared cluster**, so deduplication works across all service instances:

| Key | Value | TTL |
|-----|-------|-----|
| `evt-abc-123` | `1738000000000` (timestamp) | 1 hour |
| `evt-def-456` | `1738000001000` | 1 hour |
| `evt-ghi-789` | `1738000002000` | 1 hour |

The value (processing timestamp) is informational — only the key's presence or absence matters for deduplication. The timestamp is useful for debugging: it tells you when the event was first processed.

---

## How the Three Patterns Work Together

The outbox, DLQ, and idempotency guard form a complete reliability pipeline:

```
PRODUCER SIDE                          CONSUMER SIDE

EventSourcingController                Saga Listener onMessage()
        |                                     |
  Pipeline completes                   IdempotencyGuard.tryProcess()?
        |                                  |         |
  outboxStore.write(entry)              claimed    duplicate → skip
        |                                  |
  {domain}_OUTBOX IMap                 executeWithResilience(...)
        |                                  |
  OutboxPublisher polls                .whenComplete()
        |                               |          |
  Publish to shared ITopic           success      failure
        |                               |          |
  Mark DELIVERED                     (done)     sendToDeadLetterQueue()
                                                    |
                                              framework_DLQ IMap
                                                    |
                                              Admin: replay / discard
```

Walk through a failure scenario:

1. **Event production**: `OrderCreated` event is processed by the Jet pipeline. The controller writes it to the outbox (`{domain}_OUTBOX` IMap).

2. **Outbox delivery**: The `OutboxPublisher` picks up the entry, publishes to the shared cluster's `OrderCreated` ITopic, and marks it DELIVERED.

3. **Duplicate delivery**: Due to a timeout, `markDelivered` fails. The next poll cycle re-publishes the same event. Now the event has been delivered twice.

4. **Idempotency guard**: The Inventory Service's `OrderCreatedListener` receives both copies. The first call to `idempotencyGuard.tryProcess("evt-123")` returns `true` (process it). The second call returns `false` (skip it). Only one reservation is made.

5. **Processing failure**: The first delivery attempts to reserve stock, but the product is out of stock. `InsufficientStockException` (a `NonRetryableException`) is thrown. The circuit breaker records the failure. The retry mechanism skips it. `ResilienceException` propagates to `whenComplete()`.

6. **DLQ capture**: `sendToDeadLetterQueue()` creates a `DeadLetterEntry` with the full event payload, failure reason, saga ID, and source service. The entry is stored in the `framework_DLQ` IMap on the shared cluster.

7. **Admin intervention**: An operator checks `GET /api/admin/dlq/count`, sees 1 pending entry. They restock the product and call `POST /api/admin/dlq/{id}/replay`. The event is re-published to the `OrderCreated` ITopic.

8. **Idempotency on replay**: The replayed event arrives at the listener. But this time, the idempotency guard has a problem — `evt-123` is already in the processed events map! The fix: the DLQ replay publishes the *original* GenericRecord, which the listener receives as a new ITopic message. Since the idempotency guard uses the eventId from the record, and the record is the same, the guard will block it. For replay to work, the DLQ entry's TTL on the processed events map should have expired (default 1 hour), or the operator should wait for expiration before replaying.

This is a deliberate design trade-off: the 1-hour idempotency TTL means replays of very recent events may be blocked. In practice, DLQ entries are reviewed after the underlying issue is investigated, which typically takes longer than an hour.

---

## Configuration Reference

### Dead Letter Queue: `framework.dlq.*`

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Master toggle |
| `map-name` | `framework_DLQ` | IMap name on shared cluster |
| `max-replay-attempts` | `3` | Maximum replays before permanent block |
| `entry-ttl` | `168h` | 7-day retention for DLQ entries |

### Idempotency Guard: `framework.idempotency.*`

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Master toggle |
| `map-name` | `framework_PROCESSED_EVENTS` | IMap name on shared cluster |
| `ttl` | `1h` | How long to remember processed event IDs |

### Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `dlq.entries.added` | Counter | Events added to the DLQ |
| `dlq.entries.replayed` | Counter | Events replayed from the DLQ |
| `dlq.entries.discarded` | Counter | Events discarded from the DLQ |
| `idempotency.checks` | Counter (tagged: result=hit\|miss) | Deduplication checks |

---

## The Complete Resilience Stack

Across Parts 8, 9, and 10, we've built four interlocking patterns:

| Pattern | Layer | Purpose | Protects Against |
|---------|-------|---------|-----------------|
| **Circuit Breaker** | Consumer | Automatic service isolation | Cascade failures |
| **Retry + Backoff** | Consumer | Transient failure recovery | Network blips, brief outages |
| **Transactional Outbox** | Producer | Guaranteed delivery | Shared cluster unavailability |
| **Dead Letter Queue** | Consumer | Failure capture and replay | Permanent processing failures |
| **Idempotency Guard** | Consumer | Exactly-once processing | Duplicate delivery |

Each pattern is:
- **Optional**: Enabled by default, disabled via `framework.{pattern}.enabled=false`
- **Auto-configured**: Spring Boot auto-configuration wires everything when dependencies are present
- **Observable**: Micrometer metrics for Prometheus/Grafana dashboards
- **Backward compatible**: When disabled, the framework falls back to its previous behavior

Together, they transform a fragile fire-and-forget event pipeline into a robust, observable, self-healing system.
