# Phase 1: Event Sourcing Architecture
## Detailed Design Based on Your Existing Implementation

---

## Core Event Processing Pipeline

### Overview
Your Hazelcast Jet pipeline is the heart of the system. Every service uses the same pattern:

```
User Action → handleEvent() → PendingEvents IMap → Jet Pipeline → Multiple Outputs
```

### Jet Pipeline Steps (Detailed)

```java
Pipeline pipeline = Pipeline.create();

pipeline.readFrom(Sources.mapJournal(
    "pending-events",
    JournalInitialPosition.START_FROM_CURRENT
))
// Step 1: Timestamp event arrival (for metrics)
.map(entry -> {
    EventWithMetrics event = new EventWithMetrics(entry.getValue());
    event.setReceivedTimestamp(Instant.now());
    return event;
})
// Step 2: Persist to EventStore (immutable event log)
.peek(event -> {
    eventStoreMap.put(event.getEventId(), event);
    // Alternative for Postgres: appendToEventStore(event)
})
// Step 3: Update materialized view
.peek(event -> {
    MaterializedViewUpdater.update(event);
    // Updates appropriate Hazelcast IMap based on event type
})
// Step 4: Publish to subscribers
.peek(event -> {
    String topicName = "events." + event.getEventType();
    HazelcastTopic topic = hz.getTopic(topicName);
    topic.publish(event);
})
// Step 5: Record completion
.peek(event -> {
    CompletedEvent completion = new CompletedEvent(
        event.getEventId(),
        event.getCorrelationId(),
        Instant.now(),
        "SUCCESS"
    );
    completedEventsMap.put(event.getEventId(), completion);
})
// Step 6: Remove from pending (cleanup)
.writeTo(Sinks.map("pending-events-to-remove"));
```

### Key Components

#### 1. PendingEvents IMap
- **Purpose**: Transient queue for events awaiting processing
- **Key**: eventId (UUID)
- **Value**: DomainEvent
- **Lifecycle**: Short-lived (seconds)
- **Event Journal**: Enabled to feed Jet pipeline

#### 2. EventStore IMap (Phase 1) / PostgreSQL (Phase 2+)
- **Purpose**: Immutable event log (source of truth)
- **Key**: eventId (UUID)
- **Value**: DomainEvent with full metadata
- **Retention**: Forever (or very long TTL)
- **Phase 1**: Hazelcast IMap (fast, in-memory)
- **Phase 2**: Migrate to PostgreSQL for durability
- **Hybrid**: Keep recent events in Hazelcast, archive old to Postgres

#### 3. MaterializedView IMaps
- **Purpose**: Current state projections from event stream
- **Examples**:
  - `customer-view` (customerId → Customer snapshot)
  - `product-availability-view` (productId → Product snapshot)
  - `enriched-order-view` (orderId → Order with denormalized data)
- **Update**: Event-driven via Jet pipeline

#### 4. CompletedEvents IMap
- **Purpose**: Event completion acknowledgment
- **Key**: eventId (UUID)
- **Value**: CompletedEvent (status, timestamp, error if any)
- **Use Cases**:
  - Synchronous-style API responses (wait for completion)
  - Retry logic (detect failures)
  - Observability (track event processing time)

---

## handleEvent() Method (Standard Pattern)

Every service implements this method:

```java
public class OrderService {
    private IMap<String, DomainEvent> pendingEvents;
    private IMap<String, CompletedEvent> completedEvents;
    
    /**
     * Central event submission method.
     * All domain events flow through this method.
     * 
     * @param event The domain event to process
     * @return CompletableFuture that completes when event is processed
     */
    public CompletableFuture<EventResult> handleEvent(DomainEvent event) {
        // Generate event ID if not present
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID().toString());
        }
        
        // Set metadata
        event.setSource(this.serviceName);
        event.setTimestamp(Instant.now());
        
        // Write to PendingEvents (triggers Jet pipeline via Event Journal)
        pendingEvents.set(event.getEventId(), event);
        
        // Return future that completes when event processing finishes
        return CompletableFuture.supplyAsync(() -> {
            return waitForCompletion(event.getEventId());
        });
    }
    
    private EventResult waitForCompletion(String eventId) {
        // Poll CompletedEvents map for completion
        int maxWaitMs = 5000;
        int pollIntervalMs = 10;
        int elapsed = 0;
        
        while (elapsed < maxWaitMs) {
            CompletedEvent completion = completedEvents.get(eventId);
            if (completion != null) {
                return new EventResult(completion.getStatus(), completion.getError());
            }
            Thread.sleep(pollIntervalMs);
            elapsed += pollIntervalMs;
        }
        
        throw new TimeoutException("Event processing timeout: " + eventId);
    }
}
```

---

## Event Store Design Considerations

### Phase 1: Hazelcast IMap
**Pros**:
- Fast writes (in-memory)
- Fast event replay (rebuild views)
- Simple setup (no external database)
- Good for demo/development

**Cons**:
- Not durable (lose events on cluster restart)
- Memory constraints (can't store unlimited history)
- No SQL queries on event history

**Configuration**:
```yaml
hazelcast:
  map:
    event-store:
      in-memory-format: BINARY  # More space-efficient
      backup-count: 2            # Durability within cluster
      eviction:
        eviction-policy: NONE    # Keep all events
      # Alternative: Use MapStore to write-through to Postgres
```

### Phase 2: PostgreSQL Event Store
**Migration Strategy**:
```sql
CREATE TABLE event_store (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    event_data JSONB NOT NULL,
    metadata JSONB,
    timestamp TIMESTAMPTZ NOT NULL,
    sequence_number BIGSERIAL,
    correlation_id UUID,
    saga_id UUID,
    INDEX idx_aggregate (aggregate_type, aggregate_id, sequence_number),
    INDEX idx_event_type (event_type),
    INDEX idx_timestamp (timestamp)
);
```

**Hybrid Approach** (Best of both worlds):
- Write events to PostgreSQL (durability)
- Keep recent events in Hazelcast IMap (fast replay)
- Hazelcast MapLoader reads from Postgres on startup
- Jet pipeline writes to both

---

## Event Replay & View Rebuilding

### Cold Start (Rebuild All Views)
```java
public void rebuildMaterializedViews() {
    // Clear existing views
    customerView.clear();
    productAvailabilityView.clear();
    enrichedOrderView.clear();
    
    // Replay all events from EventStore
    eventStore.values().stream()
        .sorted(Comparator.comparing(DomainEvent::getTimestamp))
        .forEach(event -> {
            MaterializedViewUpdater.update(event);
        });
}
```

### Incremental Rebuild (From Checkpoint)
```java
public void rebuildViewsFromCheckpoint(Instant checkpoint) {
    eventStore.values().stream()
        .filter(event -> event.getTimestamp().isAfter(checkpoint))
        .sorted(Comparator.comparing(DomainEvent::getTimestamp))
        .forEach(event -> {
            MaterializedViewUpdater.update(event);
        });
}
```

### Snapshot Pattern (Optimization)
- Periodically save view snapshots
- Replay only events after snapshot
- Much faster rebuilds

```java
// Snapshot metadata
class ViewSnapshot {
    String viewName;
    Instant snapshotTimestamp;
    String snapshotLocation;  // S3, filesystem, etc.
}

// Rebuild from snapshot
public void rebuildFromSnapshot(ViewSnapshot snapshot) {
    // Load snapshot
    loadViewSnapshot(snapshot);
    
    // Replay events after snapshot
    rebuildViewsFromCheckpoint(snapshot.snapshotTimestamp);
}
```

---

## Materialized View Updater

Centralized logic for updating views based on events:

```java
public class MaterializedViewUpdater {
    
    public static void update(DomainEvent event) {
        switch (event.getEventType()) {
            case "CustomerCreated":
                handleCustomerCreated((CustomerCreatedEvent) event);
                break;
            case "CustomerUpdated":
                handleCustomerUpdated((CustomerUpdatedEvent) event);
                break;
            case "ProductCreated":
                handleProductCreated((ProductCreatedEvent) event);
                break;
            case "StockReserved":
                handleStockReserved((StockReservedEvent) event);
                break;
            case "OrderCreated":
                handleOrderCreated((OrderCreatedEvent) event);
                break;
            // ... other event types
        }
    }
    
    private static void handleOrderCreated(OrderCreatedEvent event) {
        // Update enriched-order-view
        String orderId = event.getOrderId();
        
        // Get customer data from customer-view
        CustomerView customer = customerView.get(event.getCustomerId());
        
        // Get product data from product-availability-view
        List<OrderItemView> items = event.getItems().stream()
            .map(item -> {
                ProductView product = productAvailabilityView.get(item.getProductId());
                return new OrderItemView(
                    product.getProductId(),
                    product.getName(),
                    product.getSku(),
                    item.getQuantity(),
                    item.getPriceAtOrder()
                );
            })
            .collect(Collectors.toList());
        
        // Create enriched order view
        EnrichedOrderView orderView = new EnrichedOrderView(
            orderId,
            new CustomerSummary(customer.getCustomerId(), customer.getName(), customer.getEmail()),
            items,
            event.getTotalAmount(),
            "PENDING",
            event.getTimestamp()
        );
        
        enrichedOrderView.put(orderId, orderView);
        
        // Update customer-order-summary-view
        updateCustomerOrderSummary(event.getCustomerId(), orderId, event.getTotalAmount());
    }
}
```

---

## Performance Metrics (Built-in)

The timestamp in Step 1 enables metrics:

```java
// Metrics to track
class EventMetrics {
    String eventId;
    String eventType;
    
    Instant submittedAt;     // When handleEvent() called
    Instant receivedAt;      // Step 1: Jet pipeline receives
    Instant persistedAt;     // Step 2: Written to EventStore
    Instant viewUpdatedAt;   // Step 3: View updated
    Instant publishedAt;     // Step 4: Published to subscribers
    Instant completedAt;     // Step 5: Completion recorded
    
    // Derived metrics
    long totalDurationMs;    // completedAt - submittedAt
    long jetLatencyMs;       // receivedAt - submittedAt
    long persistLatencyMs;   // persistedAt - receivedAt
    long viewUpdateLatencyMs;// viewUpdatedAt - persistedAt
    long publishLatencyMs;   // publishedAt - viewUpdatedAt
}
```

These metrics feed directly into Phase 2 observability!

---

## Advantages of This Architecture

### Event Sourcing Benefits
1. **Complete Audit Trail**: Every state change recorded
2. **Time Travel**: Replay events to see state at any point in time
3. **Debugging**: Reproduce bugs by replaying event sequence
4. **Event Replay**: Rebuild views from scratch
5. **Multiple Views**: Create new projections without changing event log

### Hazelcast Jet Benefits
1. **Stream Processing**: Natural fit for event-driven architecture
2. **Backpressure Handling**: Jet handles load spikes gracefully
3. **Fault Tolerance**: Jet restarts pipeline on failure
4. **Low Latency**: In-memory processing, <10ms typical
5. **Scalability**: Jet distributes work across cluster

### Materialized View Benefits
1. **Fast Queries**: Read from in-memory projections
2. **Denormalized Data**: No joins needed
3. **Service Independence**: Views don't require service calls
4. **Scalability**: Views scale independently

---

## Open Questions for Your Existing Design

1. **Event Ordering**: 
   - How do you handle event ordering per aggregate?
   - Use sequence numbers per aggregate?

2. **Concurrency**:
   - What if two events for same aggregate arrive simultaneously?
   - Optimistic locking in view updates?

3. **Failure Handling**:
   - What if Step 3 (view update) fails?
   - Retry? Dead letter queue?
   - How does CompletedEvents reflect failure?

4. **Event Versioning**:
   - How do you handle schema evolution?
   - Version field in events?

5. **Jet Pipeline Deployment**:
   - One pipeline per service? Or shared pipeline?
   - How do services register their event handlers?

---

## Recommendations for Phase 1

### Keep
✅ Central `handleEvent()` method - excellent pattern  
✅ Hazelcast Jet pipeline - perfect for event processing  
✅ PendingEvents → EventStore → Views → Publish flow  
✅ CompletedEvents for acknowledgment  

### Enhance
⚠️ Add event versioning (eventVersion field)  
⚠️ Add sequence numbers for event ordering per aggregate  
⚠️ Add retry/DLQ for failed view updates  
⚠️ Consider snapshot pattern for faster view rebuilds  

### Document
📝 Event catalog (all event types, schemas, handlers)  
📝 View update logic (which events update which views)  
📝 Failure scenarios (what happens when X fails)  
📝 Jet pipeline deployment model  

---

## Integration with Saga Pattern

Your event sourcing architecture actually makes sagas easier!

```java
// Saga events are just regular events with saga metadata
OrderCreatedEvent event = new OrderCreatedEvent(...);
event.setSagaId(UUID.randomUUID().toString());
event.setSagaType("OrderFulfillment");
event.setStepNumber(1);

handleEvent(event);  // Uses same pipeline!
```

The EventStore becomes your saga history log automatically.

---

## Next Steps

1. **Document your current implementation**:
   - Event schemas
   - View update logic
   - Jet pipeline configuration
   - Current IMaps and their purposes

2. **Identify gaps**:
   - What's working well?
   - What needs improvement?
   - What's missing from this design?

3. **Decide on EventStore strategy**:
   - Phase 1: Pure Hazelcast IMap
   - Phase 1: Hybrid (Hazelcast + Postgres MapStore)
   - Phase 2: Migrate to Postgres

4. **Plan for durability**:
   - How critical is event history?
   - What happens if cluster restarts?
   - Backup strategy?

This architecture is actually quite sophisticated! The event sourcing foundation is solid. We should build Phase 1 around what you already have, then enhance in later phases.
