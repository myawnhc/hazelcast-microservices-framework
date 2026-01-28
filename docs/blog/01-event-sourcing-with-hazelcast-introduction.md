# Event Sourcing with Hazelcast: A Practical Introduction

*Part 1 of 3 in the "Building Event-Driven Microservices with Hazelcast" series*

---

## Introduction

Traditional database applications store the *current state* of entities. When you update a customer's address, the old address is gone forever. This approach works for many applications, but it comes with significant limitations:

- **No audit trail**: You can't see what changed and when
- **No time travel**: You can't reconstruct the system state at a previous point in time
- **Debugging is hard**: When something goes wrong, you can't replay the sequence of events
- **Coupling**: Services that need data must call each other synchronously

**Event sourcing** flips this model on its head. Instead of storing current state, you store the *sequence of events* that led to that state. The current state becomes a derived view that can be rebuilt from events at any time.

In this article, we'll explore how to implement event sourcing using Hazelcast, an in-memory computing platform that provides the perfect foundation for high-performance event-driven systems.

---

## What is Event Sourcing?

Event sourcing is an architectural pattern where:

1. **Events are the source of truth** - Every state change is captured as an immutable event
2. **State is derived** - Current state is computed by replaying events
3. **History is preserved** - The complete sequence of changes is retained forever

### A Simple Example

Consider a customer in an e-commerce system. In a traditional CRUD approach:

```
UPDATE customers SET address = '456 Oak Ave' WHERE id = 'cust-123';
```

After this update, the old address is lost. In event sourcing:

```
Event 1: CustomerCreatedEvent { customerId: 'cust-123', address: '123 Main St' }
Event 2: CustomerAddressChangedEvent { customerId: 'cust-123', address: '456 Oak Ave' }
```

Now you have a complete history. The current address is still '456 Oak Ave', but you can see it was originally '123 Main St'.

---

## Why Hazelcast for Event Sourcing?

Hazelcast provides several capabilities that make it ideal for event sourcing:

| Capability | Event Sourcing Use |
|------------|-------------------|
| **IMap** | Store events and materialized views |
| **Event Journal** | Stream events to Jet pipelines in real-time |
| **Jet Pipeline** | Process events through multiple stages |
| **ITopic** | Publish events to subscribers |
| **Distributed** | Scale horizontally across nodes |
| **Low Latency** | Sub-millisecond reads from in-memory storage |

The combination of fast writes (events to IMap), real-time streaming (Event Journal + Jet), and fast reads (materialized views) creates a complete event sourcing infrastructure.

---

## Core Concepts

### 1. Domain Events

A domain event represents something meaningful that happened in your business domain. In our framework, events extend `DomainEvent`:

```java
public abstract class DomainEvent<D extends DomainObject<K>, K>
        implements UnaryOperator<GenericRecord>, Serializable {

    // Event identification
    protected String eventId;        // Unique ID (UUID)
    protected String eventType;      // e.g., "CustomerCreated"
    protected String eventVersion;   // For schema evolution

    // Event metadata
    protected String source;         // Service that created it
    protected Instant timestamp;     // When it happened

    // Domain object reference
    protected K key;                 // Key of affected entity

    // Traceability
    protected String correlationId;  // Links related events

    // The key method: how does this event change state?
    public abstract GenericRecord apply(GenericRecord currentState);
}
```

The `apply()` method is crucial - it defines how this event transforms the current state into new state.

### 2. Concrete Event Example

Here's a `CustomerCreatedEvent` from our eCommerce implementation:

```java
public class CustomerCreatedEvent extends DomainEvent<Customer, String> {

    public static final String EVENT_TYPE = "CustomerCreated";

    private String email;
    private String name;
    private String address;
    private String phone;

    public CustomerCreatedEvent(String customerId, String email,
                                 String name, String address) {
        super(customerId);  // Sets the key
        this.eventType = EVENT_TYPE;
        this.email = email;
        this.name = name;
        this.address = address;
    }

    @Override
    public GenericRecord apply(GenericRecord currentState) {
        // For creation events, ignore current state and create new
        return GenericRecordBuilder.compact("Customer")
            .setString("customerId", key)
            .setString("email", email)
            .setString("name", name)
            .setString("address", address)
            .setString("status", "ACTIVE")
            .setInt64("createdAt", Instant.now().toEpochMilli())
            .build();
    }
}
```

### 3. Event Store

The event store is an append-only log of all events. Events are never modified or deleted - they're the permanent record of what happened.

```java
public interface EventStore<D extends DomainObject<K>, K,
                            E extends DomainEvent<D, K>> {

    // Append an event to the store
    void append(K key, GenericRecord eventRecord);

    // Get all events for a specific entity
    List<GenericRecord> getEventsForKey(K key);

    // Replay events (for rebuilding views)
    void replayByKey(K key, Consumer<GenericRecord> eventConsumer);

    // Get total event count
    long getEventCount();
}
```

### 4. Materialized Views

Since events are the source of truth, how do we query current state efficiently? Materialized views!

A materialized view is a pre-computed projection of state derived from events. Instead of replaying all events on every query, the view is updated incrementally as each event is processed.

```
Events:                          Materialized View:
┌──────────────────────────┐     ┌─────────────────────────┐
│ CustomerCreatedEvent     │     │ Customer                │
│   name: "Alice"          │────►│   customerId: "123"     │
│   email: "a@example.com" │     │   name: "Alice"         │
└──────────────────────────┘     │   email: "a@example.com"│
┌──────────────────────────┐     │   status: "ACTIVE"      │
│ CustomerUpdatedEvent     │────►│                         │
│   name: "Alice Smith"    │     │   name: "Alice Smith"   │
└──────────────────────────┘     └─────────────────────────┘
```

---

## The Event Flow

Here's how events flow through our framework:

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Event Flow                                   │
│                                                                      │
│  1. API Request         2. Create Event       3. Submit to Pipeline │
│  ┌─────────────┐       ┌─────────────────┐   ┌───────────────────┐  │
│  │ POST        │       │ CustomerCreated │   │ pendingEvents     │  │
│  │ /customers  │ ───►  │ Event           │ ──► IMap              │  │
│  └─────────────┘       └─────────────────┘   └─────────┬─────────┘  │
│                                                         │            │
│  ┌──────────────────────────────────────────────────────┼──────────┐ │
│  │                   Jet Pipeline (via Event Journal)   │          │ │
│  │                                                      ▼          │ │
│  │  4. Persist    5. Update View    6. Publish    7. Complete     │ │
│  │  ┌─────────┐   ┌─────────────┐   ┌─────────┐   ┌───────────┐   │ │
│  │  │ Event   │   │ Customer    │   │ Event   │   │ Completion│   │ │
│  │  │ Store   │   │ View        │   │ Bus     │   │ Map       │   │ │
│  │  └─────────┘   └─────────────┘   └─────────┘   └───────────┘   │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  8. Return Response                                                  │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ { "customerId": "123", "name": "Alice", "status": "ACTIVE" }│    │
│  └─────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────┘
```

### Step by Step

1. **API Request**: A REST endpoint receives a request to create a customer
2. **Create Event**: The service creates a `CustomerCreatedEvent` with the data
3. **Submit to Pipeline**: The event is written to the pending events IMap
4. **Persist**: The Jet pipeline reads the event and stores it in the Event Store
5. **Update View**: The event is applied to the Customer materialized view
6. **Publish**: The event is published to subscribers via ITopic
7. **Complete**: A completion record is written to signal the event is processed
8. **Return Response**: The API returns the customer data from the view

---

## Implementing the Service Layer

Here's how a service submits events using our framework:

```java
@Service
public class AccountService {

    private final EventSourcingController<Customer, String,
                    DomainEvent<Customer, String>> controller;

    public CompletableFuture<Customer> createCustomer(CustomerDTO dto) {
        // 1. Create the event
        CustomerCreatedEvent event = new CustomerCreatedEvent(
            UUID.randomUUID().toString(),
            dto.getEmail(),
            dto.getName(),
            dto.getAddress()
        );

        // 2. Handle the event (triggers the entire pipeline)
        UUID correlationId = UUID.randomUUID();
        return controller.handleEvent(event, correlationId)
            .thenApply(completion -> {
                // 3. Return the current state from the view
                return getCustomer(event.getKey()).orElseThrow();
            });
    }

    public Optional<Customer> getCustomer(String customerId) {
        // Read from materialized view - fast!
        GenericRecord gr = controller.getViewMap().get(customerId);
        if (gr == null) return Optional.empty();

        return Optional.of(Customer.fromGenericRecord(gr));
    }
}
```

Key points:
- The service creates an event, not a database record
- `handleEvent()` returns a `CompletableFuture` that completes when the event is fully processed
- Reads come from the materialized view, not by replaying events

---

## Benefits in Practice

### 1. Complete Audit Trail

Every change is recorded. For compliance or debugging, you can see exactly what happened and when:

```java
// Get the complete history for a customer
List<GenericRecord> history = eventStore.getEventsForKey("cust-123");
for (GenericRecord event : history) {
    System.out.println(event.getString("eventType") + " at " +
                       event.getInt64("timestamp"));
}
```

Output:
```
CustomerCreated at 1706374800000
CustomerUpdated at 1706375100000
CustomerAddressChanged at 1706461200000
```

### 2. Time Travel

Need to see what the data looked like yesterday? Replay events up to that point:

```java
// Rebuild state as of a specific time
GenericRecord pastState = null;
for (GenericRecord event : eventStore.getEventsForKey("cust-123")) {
    if (event.getInt64("timestamp") > targetTime) break;
    pastState = applyEvent(event, pastState);
}
```

### 3. View Rebuilding

Found a bug in your view update logic? Fix it and rebuild:

```java
// Clear the view and replay all events
viewUpdater.rebuild(eventStore);
```

This is impossible with CRUD - corrupted data is permanently corrupted.

### 4. Service Independence

Services don't need to call each other for data. The Order service doesn't call the Account service to get customer names - it maintains its own materialized view:

```java
// Order service creates an enriched order view
EnrichedOrderView {
    orderId: "order-456",
    customerId: "cust-123",
    customerName: "Alice Smith",  // Denormalized from Customer events
    customerEmail: "alice@example.com",
    items: [...],
    total: 299.99
}
```

---

## Performance Characteristics

Our framework achieves impressive performance with Hazelcast:

| Metric | Value |
|--------|-------|
| Event throughput | 100,000+ events/second |
| P99 latency | < 1ms |
| View read latency | < 0.5ms |
| Event store capacity | Millions of events |

This is possible because:
- Events are written to in-memory IMaps
- Jet pipelines process events in parallel
- Views are pre-computed for instant queries
- Hazelcast distributes load across the cluster

---

## What's Next?

This article introduced the fundamentals of event sourcing with Hazelcast. In the next two articles, we'll dive deeper:

- **Part 2**: Building the Event Pipeline with Hazelcast Jet - How the 6-stage pipeline processes events in real-time
- **Part 3**: Materialized Views for Fast Queries - How to design denormalized views for different query patterns

---

## Getting Started

The complete framework is available on GitHub with:
- Full source code
- Docker Compose setup
- Sample eCommerce application
- Load testing tools
- 677 tests with >80% coverage

```bash
# Clone and run
git clone https://github.com/yourusername/hazelcast-microservices-framework
cd hazelcast-microservices-framework
./scripts/start-docker.sh
./scripts/load-sample-data.sh
./scripts/demo-scenarios.sh
```

---

## Summary

Event sourcing with Hazelcast provides:

- **Immutable event log** as the source of truth
- **Materialized views** for fast queries
- **Complete audit trail** for compliance and debugging
- **Time travel** capability to reconstruct past state
- **High performance** with in-memory processing

The pattern requires a shift in thinking from "store current state" to "record what happened," but the benefits in auditability, debugging, and scalability make it worthwhile for many applications.

---

*Next: [Part 2 - Building the Event Pipeline with Hazelcast Jet](02-building-event-pipeline-with-hazelcast-jet.md)*
