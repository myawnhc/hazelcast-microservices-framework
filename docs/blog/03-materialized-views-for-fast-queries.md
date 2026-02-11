# Materialized Views for Fast Queries

*Part 3 of 7 in the "Building Event-Driven Microservices with Hazelcast" series*

---

## Introduction

In [Part 1](01-event-sourcing-with-hazelcast-introduction.md), we introduced event sourcing where events are the source of truth. In [Part 2](02-building-event-pipeline-with-hazelcast-jet.md), we built the Jet pipeline that processes events through 6 stages.

Now we address a critical question: **How do we query data efficiently if everything is stored as events?**

The answer is **materialized views** - pre-computed projections of current state that are updated incrementally as events flow through the system.

---

## The Problem with Raw Events

Imagine querying a customer's current profile by replaying all their events:

```java
// Naive approach: replay events to get current state
public Customer getCustomer(String customerId) {
    List<Event> events = eventStore.getEventsForKey(customerId);

    Customer customer = null;
    for (Event event : events) {
        customer = event.apply(customer);
    }
    return customer;
}
```

This works, but has problems:

| Issue | Impact |
|-------|--------|
| **Latency** | Must replay ALL events for every query |
| **Cost** | CPU time grows with event count |
| **Scalability** | A customer with 1000 events takes 1000x longer than one with 1 event |

For a high-traffic API, this is unacceptable. We need O(1) lookups, not O(n).

---

## What is a Materialized View?

A materialized view is a **pre-computed projection** of data derived from events. Instead of computing state on every query, we:

1. Compute state once when the event is processed
2. Store the result in a fast lookup structure (Hazelcast IMap)
3. Return the stored result on queries

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Without Materialized Views                      │
│                                                                     │
│  Query → Replay 1000 events → Compute state → Return result        │
│          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~                            │
│                      Slow (O(n))                                    │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                     With Materialized Views                         │
│                                                                     │
│  Event → Update view (once)                                         │
│                                                                     │
│  Query → Lookup view → Return result                                │
│          ~~~~~~~~~                                                  │
│          Fast (O(1))                                                │
└─────────────────────────────────────────────────────────────────────┘
```

---

## The ViewStore

Our framework's `HazelcastViewStore` wraps an IMap to store materialized views:

```java
public class HazelcastViewStore<K> {

    private final IMap<K, GenericRecord> viewMap;
    private final String viewName;

    public HazelcastViewStore(HazelcastInstance hazelcast, String viewName) {
        this.viewName = viewName + "_VIEW";
        this.viewMap = hazelcast.getMap(this.viewName);
    }

    public Optional<GenericRecord> get(K key) {
        return Optional.ofNullable(viewMap.get(key));
    }

    public void put(K key, GenericRecord value) {
        viewMap.set(key, value);
    }

    public void remove(K key) {
        viewMap.delete(key);
    }

    // Atomic update using EntryProcessor
    public GenericRecord executeOnKey(K key, EntryProcessor<K, GenericRecord, GenericRecord> processor) {
        return viewMap.executeOnKey(key, processor);
    }
}
```

**Key Design Decisions:**

- **GenericRecord**: Flexible schema without Java classes on cluster
- **IMap**: Distributed, partitioned, in-memory for speed
- **Entry Processor**: Atomic updates without race conditions

---

## The ViewUpdater

The `ViewUpdater` abstract class defines how events transform into view state:

```java
public abstract class ViewUpdater<K> implements Serializable {

    protected final transient HazelcastViewStore<K> viewStore;

    // Subclasses implement: how to extract the key from an event
    protected abstract K extractKey(GenericRecord eventRecord);

    // Subclasses implement: how to apply an event to current state
    protected abstract GenericRecord applyEvent(
        GenericRecord eventRecord,
        GenericRecord currentState);

    // Called by the pipeline
    public GenericRecord updateDirect(GenericRecord eventRecord) {
        K key = extractKey(eventRecord);
        if (key == null) {
            logger.warn("Could not extract key from event");
            return null;
        }

        GenericRecord currentState = viewStore.get(key).orElse(null);
        GenericRecord updatedState = applyEvent(eventRecord, currentState);

        if (updatedState != null) {
            viewStore.put(key, updatedState);
        } else if (currentState != null) {
            viewStore.remove(key);  // Event caused deletion
        }

        return updatedState;
    }
}
```

---

## Example: Customer View

Let's see a complete `CustomerViewUpdater` implementation:

```java
public class CustomerViewUpdater extends ViewUpdater<String> {

    public static final String VIEW_NAME = "Customer";

    public CustomerViewUpdater(HazelcastViewStore<String> viewStore) {
        super(viewStore);
    }

    @Override
    protected String extractKey(GenericRecord eventRecord) {
        // All customer events have a "key" field with customerId
        return eventRecord.getString("key");
    }

    @Override
    protected GenericRecord applyEvent(GenericRecord event, GenericRecord current) {
        String eventType = getEventType(event);

        return switch (eventType) {
            case "CustomerCreated" -> createCustomer(event);
            case "CustomerUpdated" -> updateCustomer(event, current);
            case "CustomerStatusChanged" -> changeStatus(event, current);
            case "CustomerDeleted" -> null;  // Return null to delete
            default -> {
                logger.debug("Unknown event type: {}", eventType);
                yield current;  // Keep current state unchanged
            }
        };
    }

    private GenericRecord createCustomer(GenericRecord event) {
        Instant now = Instant.now();
        return GenericRecordBuilder.compact(VIEW_NAME)
            .setString("customerId", event.getString("key"))
            .setString("email", event.getString("email"))
            .setString("name", event.getString("name"))
            .setString("address", event.getString("address"))
            .setString("phone", getStringField(event, "phone"))
            .setString("status", "ACTIVE")
            .setInt64("createdAt", now.toEpochMilli())
            .setInt64("updatedAt", now.toEpochMilli())
            .build();
    }

    private GenericRecord updateCustomer(GenericRecord event, GenericRecord current) {
        if (current == null) {
            logger.warn("Update event for non-existent customer: {}",
                event.getString("key"));
            return null;
        }

        return GenericRecordBuilder.compact(VIEW_NAME)
            .setString("customerId", current.getString("customerId"))
            .setString("email", coalesce(event.getString("email"),
                                         current.getString("email")))
            .setString("name", coalesce(event.getString("name"),
                                        current.getString("name")))
            .setString("address", coalesce(event.getString("address"),
                                           current.getString("address")))
            .setString("phone", coalesce(getStringField(event, "phone"),
                                         current.getString("phone")))
            .setString("status", current.getString("status"))
            .setInt64("createdAt", current.getInt64("createdAt"))
            .setInt64("updatedAt", Instant.now().toEpochMilli())
            .build();
    }

    private GenericRecord changeStatus(GenericRecord event, GenericRecord current) {
        if (current == null) return null;

        return GenericRecordBuilder.compact(VIEW_NAME)
            .setString("customerId", current.getString("customerId"))
            .setString("email", current.getString("email"))
            .setString("name", current.getString("name"))
            .setString("address", current.getString("address"))
            .setString("phone", current.getString("phone"))
            .setString("status", event.getString("newStatus"))
            .setInt64("createdAt", current.getInt64("createdAt"))
            .setInt64("updatedAt", Instant.now().toEpochMilli())
            .build();
    }

    private String coalesce(String newValue, String currentValue) {
        return newValue != null ? newValue : currentValue;
    }
}
```

---

## Cross-Service Materialized Views

One of the most powerful patterns is **denormalized views** that combine data from multiple services without making service calls.

### The Problem

Consider displaying an order:

```json
{
  "orderId": "order-123",
  "customerId": "cust-456",
  "items": [
    { "productId": "prod-789", "quantity": 2, "unitPrice": 49.99 }
  ]
}
```

To display a complete order, we need:
- Customer name and email (from Account Service)
- Product names and SKUs (from Inventory Service)

**The traditional approach** makes service calls:

```java
// SLOW - synchronous service calls
Order order = orderRepository.findById(orderId);
Customer customer = accountService.getCustomer(order.getCustomerId());  // HTTP call!
for (LineItem item : order.getItems()) {
    Product product = inventoryService.getProduct(item.getProductId());  // HTTP call!
    item.setProductName(product.getName());
}
```

This is:
- **Slow**: Multiple network round trips
- **Fragile**: Fails if any service is down
- **Coupled**: Order service depends on Account and Inventory

### The Solution: Enriched Order View

Instead, we create a **denormalized view** that contains all the data:

```java
public class EnrichedOrderViewUpdater extends ViewUpdater<String> {

    private final IMap<String, GenericRecord> customerView;
    private final IMap<String, GenericRecord> productView;

    @Override
    protected GenericRecord applyEvent(GenericRecord event, GenericRecord current) {
        String eventType = getEventType(event);

        if ("OrderCreated".equals(eventType)) {
            return createEnrichedOrder(event);
        }
        // ... other event types
        return current;
    }

    private GenericRecord createEnrichedOrder(GenericRecord event) {
        String customerId = event.getString("customerId");

        // Get customer data from local view (NO service call!)
        GenericRecord customer = customerView.get(customerId);
        String customerName = customer != null ?
            customer.getString("name") : "Unknown";
        String customerEmail = customer != null ?
            customer.getString("email") : "";

        // Enrich each line item
        List<GenericRecord> enrichedItems = new ArrayList<>();
        // ... (iterate through items, lookup products)

        return GenericRecordBuilder.compact("EnrichedOrder")
            .setString("orderId", event.getString("key"))
            .setString("customerId", customerId)
            .setString("customerName", customerName)    // Denormalized!
            .setString("customerEmail", customerEmail)  // Denormalized!
            .setArrayOfGenericRecord("lineItems",
                enrichedItems.toArray(new GenericRecord[0]))
            .setString("status", "PENDING")
            .build();
    }
}
```

**The Result:**

```json
{
  "orderId": "order-123",
  "customerId": "cust-456",
  "customerName": "Alice Smith",
  "customerEmail": "alice@example.com",
  "lineItems": [
    {
      "productId": "prod-789",
      "productName": "Gaming Laptop",
      "sku": "LAPTOP-001",
      "quantity": 2,
      "unitPrice": 49.99
    }
  ],
  "status": "PENDING"
}
```

**Benefits:**
- **Fast**: Single IMap lookup, no service calls
- **Resilient**: Works even if other services are down
- **Decoupled**: Order service doesn't call Account or Inventory

---

## View Rebuilding

A key advantage of event sourcing is that views can be **rebuilt from scratch**. This is essential for:

1. **Bug fixes**: Fix the view logic and replay
2. **New views**: Add a new projection from existing events
3. **Disaster recovery**: Restore corrupted views
4. **Schema changes**: Migrate to new view structure

### The Rebuild Process

```java
public class ViewUpdater<K> {

    /**
     * Rebuilds the entire view from the event store.
     * Clears existing view and replays all events.
     */
    public <D extends DomainObject<K>, E extends DomainEvent<D, K>> long rebuild(
            EventStore<D, K, E> eventStore) {

        logger.info("Starting view rebuild for {} from {}",
            viewStore.getViewName(), eventStore.getStoreName());

        // Step 1: Clear the view
        viewStore.clear();

        // Step 2: Replay all events in order
        AtomicLong count = new AtomicLong(0);
        eventStore.replayAll(eventRecord -> {
            updateDirect(eventRecord);
            count.incrementAndGet();
        });

        logger.info("Rebuild complete. Processed {} events", count.get());
        return count.get();
    }
}
```

### When to Rebuild

| Scenario | Action |
|----------|--------|
| Bug in view update logic | Fix code, rebuild |
| New view type added | Create new view, rebuild from events |
| View schema changed | Update code, rebuild |
| View data corrupted | Rebuild from events |
| Adding historical data | Not possible in CRUD; trivial with events |

---

## View Patterns

### 1. Entity View (1:1 with aggregate)

The simplest view - one entry per domain entity:

```
Customer Events → Customer View
  CustomerCreatedEvent     customerId → { name, email, status }
  CustomerUpdatedEvent
  CustomerStatusChanged
```

### 2. Lookup View (Alternate keys)

Index by different keys for different query patterns:

```
Customer Events → Customer-by-Email View
  CustomerCreatedEvent     email → customerId
  CustomerEmailChanged     old email → null, new email → customerId
```

### 3. Summary View (Aggregation)

Aggregate data across multiple entities:

```
Order Events → Customer Order Summary View
  OrderCreatedEvent        customerId → { totalOrders++, totalSpent += amount }
  OrderCancelledEvent      customerId → { totalOrders--, totalSpent -= amount }
```

### 4. Enriched View (Denormalization)

Combine data from multiple sources:

```
Order Events + Customer View + Product View → Enriched Order View
  OrderCreatedEvent        orderId → { order + customerName + productNames }
```

### 5. Time-Series View

Track changes over time:

```
Stock Events → Daily Inventory Snapshot View
  StockReserved           date + productId → { reserved, available }
  StockReleased
  StockAdjusted
```

---

## Handling View Staleness

Since views are updated asynchronously, there's a brief window where the view might not reflect the latest event. How do we handle this?

### Option 1: Accept Eventual Consistency

For most reads, a few milliseconds of staleness is acceptable:

```java
// Read from view (might be slightly stale)
Customer customer = customerView.get(customerId);
```

### Option 2: Wait for Completion

For critical reads after a write, wait for the event to process:

```java
// Write and wait
CompletableFuture<EventCompletion> future = controller.handleEvent(event, correlationId);
future.join();  // Wait for pipeline completion

// Now the view is guaranteed to be updated
Customer customer = customerView.get(customerId);
```

### Option 3: Read-Your-Writes

Include a version/timestamp and check:

```java
// After updating
long expectedVersion = event.getTimestamp().toEpochMilli();

// When reading
Customer customer = customerView.get(customerId);
if (customer.getUpdatedAt() < expectedVersion) {
    // View not yet updated, either wait or return event data directly
}
```

---

## Performance Characteristics

Materialized views with Hazelcast IMap achieve excellent read performance:

| Operation | Latency | Notes |
|-----------|---------|-------|
| View read (local) | < 0.1ms | Data on same node |
| View read (remote) | < 0.5ms | One network hop |
| View read (near cache) | < 0.01ms | Cached locally |
| View update | < 1ms | Part of pipeline |

### Enabling Near Cache

For frequently-read views, enable near cache:

```yaml
hazelcast:
  map:
    Customer_VIEW:
      near-cache:
        name: customer-near-cache
        time-to-live-seconds: 30
        max-idle-seconds: 10
        eviction:
          eviction-policy: LRU
          max-size-policy: ENTRY_COUNT
          size: 10000
```

This caches view entries on the client, reducing reads to microseconds.

---

## Best Practices

### 1. Keep Views Focused

Each view should serve specific query patterns:

```java
// GOOD: Focused views
CustomerView          // For customer profile queries
CustomerByEmailView   // For login/authentication
CustomerOrderSummary  // For order history aggregation

// BAD: Kitchen sink view
CustomerEverythingView  // All data, all keys, all aggregations
```

### 2. Document View Contracts

Make it clear what events update each view:

```java
/**
 * Customer materialized view.
 *
 * <p>Updated by events:
 * <ul>
 *   <li>CustomerCreatedEvent - creates new entry</li>
 *   <li>CustomerUpdatedEvent - updates fields</li>
 *   <li>CustomerStatusChangedEvent - updates status</li>
 *   <li>CustomerDeletedEvent - removes entry</li>
 * </ul>
 *
 * <p>Query patterns supported:
 * <ul>
 *   <li>Get customer by ID (primary)</li>
 * </ul>
 */
public class CustomerViewUpdater extends ViewUpdater<String> { }
```

### 3. Handle Missing Data Gracefully

Views may have stale references to deleted entities:

```java
private GenericRecord createEnrichedOrder(GenericRecord event) {
    String customerId = event.getString("customerId");

    // Customer might not exist (deleted, not yet created, etc.)
    GenericRecord customer = customerView.get(customerId);
    String customerName = customer != null ?
        customer.getString("name") : "Customer " + customerId;
    // ...
}
```

### 4. Consider View Rebuilding Cost

Large event histories take time to rebuild. Strategies:

- **Snapshots**: Periodically save view state to reduce replay time
- **Incremental**: Track last processed event, rebuild from checkpoint
- **Parallel**: Rebuild views in parallel if they're independent

---

## Summary

Materialized views solve the query problem in event sourcing:

| Aspect | Without Views | With Views |
|--------|--------------|------------|
| Read latency | O(n) events | O(1) lookup |
| Query patterns | Limited | Flexible per view |
| Cross-service data | Service calls | Denormalized |
| Data freshness | Always current | Eventually consistent |
| Rebuild capability | N/A | Replay from events |

**Key Takeaways:**

1. **Views are derived** - Events remain the source of truth
2. **Views are disposable** - They can be rebuilt anytime
3. **Denormalization is good** - Avoid service calls with enriched views
4. **Multiple views per entity** - Different views for different query patterns
5. **Eventually consistent** - Accept brief staleness for read performance

---

## Series Conclusion

Over these three articles, we've built a complete event sourcing system with Hazelcast:

1. **Part 1**: Event sourcing fundamentals - events as source of truth
2. **Part 2**: Jet pipeline - 6-stage event processing
3. **Part 3**: Materialized views - fast queries from denormalized projections

The result is a framework that achieves:
- 100,000+ events per second throughput
- Sub-millisecond query latency
- Complete audit trail
- View rebuilding capability
- Service independence

The code is available on GitHub with 677 tests, Docker Compose setup, and demo scenarios ready to run.

---

## Further Reading

- [Hazelcast Jet Documentation](https://docs.hazelcast.com/hazelcast/latest/pipelines/overview)
- [Event Sourcing Pattern](https://martinfowler.com/eaaDev/EventSourcing.html)
- [CQRS and Event Sourcing](https://docs.microsoft.com/en-us/azure/architecture/patterns/cqrs)

---

*Next: [Part 4 - Observability in Event-Sourced Systems](04-observability-in-event-sourced-systems.md)*

*Previous: [Part 2 - Building the Event Pipeline with Hazelcast Jet](02-building-event-pipeline-with-hazelcast-jet.md)*

*Start of series: [Part 1 - Event Sourcing with Hazelcast Introduction](01-event-sourcing-with-hazelcast-introduction.md)*
