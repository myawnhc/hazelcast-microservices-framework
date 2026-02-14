# Phase 3 Day 4: Outbox Pattern + Dead Letter Queue

## Context

After completing resilience foundations (Days 1-3: circuit breaker, retry, exponential backoff), the framework still has two reliability gaps:

1. **Producer side**: `EventSourcingController.republishToSharedCluster()` publishes directly to the shared cluster's ITopic with a try/catch that silently swallows failures. If the publish fails, the cross-service saga event is permanently lost.

2. **Consumer side**: Saga listeners log errors when resilience is exhausted but have no recovery mechanism. Failed events vanish — only the saga timeout detector eventually catches the stalled saga.

This plan adds three complementary components: an **Outbox** (durable producer-side delivery), a **Dead Letter Queue** (consumer-side failure capture), and an **Idempotency Guard** (deduplication for at-least-once delivery).

---

## Architecture Overview

```
PRODUCER SIDE (embedded Hazelcast)          CONSUMER SIDE (shared cluster)

handleEvent()                               Saga Listener onMessage()
    |                                           |
Pipeline completes                          IdempotencyGuard.tryProcess(eventId)?
    |                                           |         |
Completion listener                          claimed    duplicate
    |                                           |       (skip)
outboxStore.write(entry)                    executeWithResilience(...)
    |                                           |
{domain}_OUTBOX IMap                        .whenComplete()
    |                                         |          |
OutboxPublisher (@Scheduled)              success      failure
    |                                         |          |
Publishes to shared ITopic              markProcessed  DLQ.add(entry)
    |                                                    |
Marks DELIVERED                                   framework_DLQ IMap
    |                                                    |
(if max retries exceeded → discard)       Admin: GET/replay/discard
```

---

## Step 1: Value Objects + Interfaces

### 1a. `OutboxEntry.java`
**Package**: `com.theyawns.framework.outbox`

Fields: `eventId` (String), `eventType` (String), `eventRecord` (GenericRecord), `retryCount` (int), `status` (enum PENDING/DELIVERED/FAILED), `createdAt` (Instant), `lastAttemptAt` (Instant), `failureReason` (String)

### 1b. `OutboxStore.java` (interface)
**Package**: `com.theyawns.framework.outbox`

```java
void write(OutboxEntry entry);
List<OutboxEntry> pollPending(int maxBatchSize);
void markDelivered(String eventId);
void markFailed(String eventId, String reason);
void incrementRetryCount(String eventId, String failureReason);
long pendingCount();
```

### 1c. `DeadLetterEntry.java`
**Package**: `com.theyawns.framework.dlq`

Fields: `dlqEntryId` (UUID), `originalEventId`, `eventType`, `topicName`, `eventRecord` (GenericRecord), `failureReason`, `failureTimestamp` (Instant), `sourceService`, `sagaId`, `correlationId`, `replayCount` (int), `status` (enum PENDING/REPLAYED/DISCARDED)

Builder pattern for clean construction in saga listeners.

### 1d. `DeadLetterQueueOperations.java` (interface)
**Package**: `com.theyawns.framework.dlq`

Interface extracted for Java 25 Mockito compatibility (same pattern as `ResilientOperations`).

```java
void add(DeadLetterEntry entry);
List<DeadLetterEntry> list(int limit);
DeadLetterEntry getEntry(String dlqEntryId);
void replay(String dlqEntryId);   // Re-publishes to original topic
void discard(String dlqEntryId);
long count();
```

### 1e. `IdempotencyGuard.java` (interface)
**Package**: `com.theyawns.framework.idempotency`

```java
boolean tryProcess(String eventId);  // Atomic check-and-claim (putIfAbsent)
```

Single method using `putIfAbsent` semantics — returns `true` if this caller claimed the event (first time), `false` if already processed (duplicate).

---

## Step 2: Properties Classes

### 2a. `OutboxProperties.java`
**Prefix**: `framework.outbox`

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Master toggle |
| `poll-interval` | `1s` | How often OutboxPublisher polls |
| `max-batch-size` | `50` | Events per poll cycle |
| `max-retries` | `5` | Before marking entry as FAILED |
| `entry-ttl` | `24h` | How long DELIVERED entries survive |

### 2b. `DeadLetterQueueProperties.java`
**Prefix**: `framework.dlq`

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Master toggle |
| `map-name` | `framework_DLQ` | IMap name on shared cluster |
| `max-replay-attempts` | `3` | Max replays before permanent discard |
| `entry-ttl` | `168h` | 7-day retention |

### 2c. `IdempotencyProperties.java`
**Prefix**: `framework.idempotency`

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Master toggle |
| `map-name` | `framework_PROCESSED_EVENTS` | IMap name on shared cluster |
| `ttl` | `1h` | How long to remember processed eventIds |

---

## Step 3: Hazelcast Implementations

### 3a. `HazelcastOutboxStore.java`
**Package**: `com.theyawns.framework.outbox`

- Uses `IMap<String, OutboxEntry>` on the **embedded** instance (same as event store)
- Map name: configurable, default `framework_OUTBOX`
- `pollPending()`: queries with `Predicates.equal("status", "PENDING")`, sorts by `createdAt`, limits to batch size
- `markDelivered()`: updates status, sets `lastAttemptAt`
- Micrometer counter: `outbox.entries.written`

**Design note**: `OutboxEntry` stored as a Java `Serializable` object in IMap (not as GenericRecord). This is simpler than Compact serialization for internal framework data. The `eventRecord` field (GenericRecord) serializes natively within Hazelcast.

### 3b. `HazelcastDeadLetterQueue.java`
**Package**: `com.theyawns.framework.dlq`

- Uses `IMap<String, DeadLetterEntry>` on the **shared** cluster (or embedded as fallback)
- `replay()`: reads entry, publishes `eventRecord` to ITopic named `topicName`, marks REPLAYED, increments `replayCount`
- `discard()`: marks DISCARDED
- Uses `IMap.replace(key, oldValue, newValue)` for CAS-safe status transitions
- Micrometer counters: `dlq.entries.added`, `dlq.entries.replayed`, `dlq.entries.discarded`

### 3c. `HazelcastIdempotencyGuard.java`
**Package**: `com.theyawns.framework.idempotency`

- Uses `IMap<String, Long>` on the **shared** cluster (eventId → processing timestamp)
- `tryProcess()`: `putIfAbsent(eventId, timestamp, ttl, MILLISECONDS)` — returns `true` if `null` was returned (first inserter), `false` otherwise
- Micrometer counter: `idempotency.checks` with tag `result=hit|miss`

---

## Step 4: OutboxPublisher

**File**: `com.theyawns.framework.outbox.OutboxPublisher`

- Injected with: `OutboxStore`, `HazelcastInstance` (shared cluster, nullable), `OutboxProperties`, `MeterRegistry`
- `@Scheduled(fixedDelayString = "${framework.outbox.poll-interval:1000}")` method
- Each cycle:
  1. `outboxStore.pollPending(maxBatchSize)`
  2. For each entry: publish to `sharedHazelcast.getTopic(entry.eventType)`
  3. On success: `outboxStore.markDelivered(eventId)`
  4. On failure: `outboxStore.incrementRetryCount(eventId, reason)`
  5. If `retryCount >= maxRetries`: `outboxStore.markFailed(eventId, reason)`
- If `sharedHazelcast == null`: logs warning once, skips publishing (graceful degradation)
- Micrometer: `outbox.entries.delivered` counter, `outbox.entries.failed` counter, `outbox.publish.duration` timer

---

## Step 5: DLQ Admin Controller

**File**: `com.theyawns.framework.dlq.DeadLetterQueueController`

| Endpoint | Method | Description |
|----------|--------|-------------|
| `GET /api/admin/dlq` | `list(@RequestParam(defaultValue = "20") int limit)` | List DLQ entries |
| `GET /api/admin/dlq/count` | `count()` | Current PENDING count |
| `GET /api/admin/dlq/{id}` | `getEntry(@PathVariable String id)` | Single entry details |
| `POST /api/admin/dlq/{id}/replay` | `replay(@PathVariable String id)` | Re-publish to original topic |
| `DELETE /api/admin/dlq/{id}` | `discard(@PathVariable String id)` | Mark as discarded |

Uses `@Tag(name = "Dead Letter Queue")` for OpenAPI grouping. Returns 404 for unknown entries.

---

## Step 6: Auto-Configuration

### 6a. `OutboxAutoConfiguration.java`
```java
@Configuration
@ConditionalOnProperty(name = "framework.outbox.enabled", matchIfMissing = true)
@ConditionalOnBean(HazelcastInstance.class)
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
```
Beans: `OutboxStore` (uses @Primary embedded instance), `OutboxPublisher` (needs @Qualifier("hazelcastClient") shared instance, nullable)

### 6b. `DeadLetterQueueAutoConfiguration.java`
```java
@Configuration
@ConditionalOnProperty(name = "framework.dlq.enabled", matchIfMissing = true)
@EnableConfigurationProperties(DeadLetterQueueProperties.class)
```
Beans: `DeadLetterQueueOperations` (prefers shared cluster, falls back to embedded), `DeadLetterQueueController`

### 6c. `IdempotencyAutoConfiguration.java`
```java
@Configuration
@ConditionalOnProperty(name = "framework.idempotency.enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
```
Beans: `IdempotencyGuard` (prefers shared cluster, falls back to embedded)

### 6d. Register in `AutoConfiguration.imports`
Add three new lines:
```
com.theyawns.framework.outbox.OutboxAutoConfiguration
com.theyawns.framework.dlq.DeadLetterQueueAutoConfiguration
com.theyawns.framework.idempotency.IdempotencyAutoConfiguration
```

---

## Step 7: EventSourcingController Integration

**File**: `framework-core/src/main/java/com/theyawns/framework/controller/EventSourcingController.java`

### Changes:
1. Add field: `private final OutboxStore outboxStore;` (nullable)
2. In constructor: `this.outboxStore = builder.outboxStore;`
3. In Builder: add `private OutboxStore outboxStore;` + `outboxStore(OutboxStore)` method
4. Modify `republishToSharedCluster()`:

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
            logger.debug("Republished event {} to shared cluster topic", pending.eventType);
        } catch (Exception e) {
            logger.warn("Failed to republish event {}: {}", pending.eventType, e.getMessage());
        }
    }
}
```

Backward compatible: when `outboxStore == null`, existing direct-publish behavior is preserved.

---

## Step 8: Saga Listener Integration

**Files** (same pattern applied to all three):
- `inventory-service/.../saga/InventorySagaListener.java`
- `payment-service/.../saga/PaymentSagaListener.java`
- `order-service/.../saga/OrderSagaListener.java`

### Changes per listener:

1. Add optional fields + setter injection:
```java
@Autowired(required = false)
private IdempotencyGuard idempotencyGuard;

@Autowired(required = false)
private DeadLetterQueueOperations deadLetterQueue;
```

2. Add idempotency check at top of each inner listener's `onMessage()`:
```java
String eventId = record.getString("eventId");
if (idempotencyGuard != null && !idempotencyGuard.tryProcess(eventId)) {
    logger.debug("Duplicate event {} already processed, skipping", eventId);
    return;
}
```

3. Modify `.whenComplete()` error handler to send to DLQ:
```java
.whenComplete((result, error) -> {
    if (error != null) {
        sendToDeadLetterQueue(record, "TopicName", error);
    }
});
```

4. Add shared helper method in outer class:
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
                .sourceService("inventory-service")  // varies per listener
                .sagaId(record.getString("sagaId"))
                .correlationId(record.getString("correlationId"))
                .build());
            logger.warn("Event {} sent to DLQ after failure: {}", eventId, error.getMessage());
        } catch (Exception dlqError) {
            logger.error("Failed to send event {} to DLQ: {}", eventId, dlqError.getMessage());
        }
    } else {
        // Existing behavior — log only
        if (error instanceof ResilienceException) {
            logger.warn("Circuit breaker open, saga step deferred: eventId={}", eventId);
        } else {
            logger.error("Failed to process event: {}", eventId, error);
        }
    }
}
```

---

## Step 9: Wire OutboxStore into Service Configs

**Files**:
- `account-service/.../config/AccountServiceConfig.java`
- `inventory-service/.../config/InventoryServiceConfig.java`
- `order-service/.../config/OrderServiceConfig.java`
- `payment-service/.../config/PaymentServiceConfig.java`

Add `@Autowired(required = false) OutboxStore outboxStore` parameter to the controller builder method, then call `.outboxStore(outboxStore)` on the builder. Account service doesn't use shared cluster for sagas, so outbox may be null there — that's fine.

---

## Testing Strategy

### Unit Tests (framework-core) — 13 new test files

| Test | Pattern | Key Assertions |
|------|---------|----------------|
| `OutboxEntryTest` | Direct construction | Status transitions, field access |
| `OutboxPropertiesTest` | Direct construction | Defaults, validation |
| `HazelcastOutboxStoreTest` | Real embedded Hazelcast, `@TestInstance(PER_CLASS)` | write/poll/markDelivered/markFailed cycle |
| `OutboxPublisherTest` | Mock `OutboxStore` + mock `HazelcastInstance` | Poll-publish-mark cycle, retry increment, max retry handling |
| `OutboxAutoConfigurationTest` | `ApplicationContextRunner` | Beans created by default, disabled via property, ConditionalOnMissingBean |
| `DeadLetterEntryTest` | Builder pattern | Construction, field access |
| `DeadLetterQueuePropertiesTest` | Direct construction | Defaults |
| `HazelcastDeadLetterQueueTest` | Real embedded Hazelcast, `@TestInstance(PER_CLASS)` | add/list/replay/discard cycle |
| `DeadLetterQueueControllerTest` | Mock `DeadLetterQueueOperations` | REST endpoints return correct status codes |
| `DeadLetterQueueAutoConfigurationTest` | `ApplicationContextRunner` | Bean creation, property toggle |
| `IdempotencyPropertiesTest` | Direct construction | Defaults |
| `HazelcastIdempotencyGuardTest` | Real embedded Hazelcast, `@TestInstance(PER_CLASS)` | tryProcess returns true first, false second; TTL behavior |
| `IdempotencyAutoConfigurationTest` | `ApplicationContextRunner` | Bean creation |

### Modified Tests (3 files)

- `InventorySagaListenerTest.java` — Add: idempotency skips duplicate, DLQ receives failed event, both null-safe
- `PaymentSagaListenerTest.java` — Same additions
- `OrderSagaListenerTest.java` — Same additions

### Testing Notes
- Use `@TestInstance(PER_CLASS)` + `@BeforeAll`/`@AfterAll` for embedded Hazelcast lifecycle (Java 25 compatibility)
- Use `framework.edition.license.env-var=NONEXISTENT_TEST_LICENSE_VAR_12345` in tests to avoid picking up real HZ_LICENSEKEY
- `HazelcastInstance` interface can still be mocked with Mockito (for OutboxPublisher tests)
- `ApplicationContextRunner` for all auto-configuration tests

---

## Verification

1. **Build**: `mvn clean install -pl framework-core` — all new tests pass
2. **Service build**: `mvn clean install` — all services compile with new optional dependencies
3. **Docker smoke test**: `docker compose up` — verify outbox publisher logs "Delivered N events" periodically
4. **DLQ admin**: `curl http://localhost:8083/api/admin/dlq/count` — returns `{"count": 0}`
5. **Disable test**: Set `framework.outbox.enabled=false` — verify direct publish still works (backward compatible)

---

## File Summary

**New files**: 29 (16 main + 13 test)
**Modified files**: 12 (4 service configs, 3 saga listeners, 3 saga listener tests, EventSourcingController, AutoConfiguration.imports)
**Config updates**: 4 service `application.yml` files

## Implementation Order

1. Value objects + interfaces (OutboxEntry, OutboxStore, DeadLetterEntry, DeadLetterQueueOperations, IdempotencyGuard)
2. Properties classes (OutboxProperties, DeadLetterQueueProperties, IdempotencyProperties)
3. Hazelcast implementations + their tests (HazelcastOutboxStore, HazelcastDeadLetterQueue, HazelcastIdempotencyGuard)
4. OutboxPublisher + test
5. DeadLetterQueueController + test
6. Auto-configurations + tests + imports registration
7. EventSourcingController modification (outboxStore field + builder + republishToSharedCluster)
8. Service config modifications (wire OutboxStore into controller builders)
9. Saga listener modifications (idempotency + DLQ) + test updates
10. Service application.yml updates
