# Framework Core

Domain-agnostic event sourcing framework built on Hazelcast.

## Overview

The framework-core module provides the foundational components for building event-sourced microservices. It handles:

- **Event Storage**: Immutable append-only event log using Hazelcast IMap
- **Materialized Views**: Fast query-optimized projections of domain state
- **Event Pipeline**: Hazelcast Jet streaming pipeline for event processing
- **Event Bus**: Publish/subscribe for cross-service event propagation

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                    EventSourcingController                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │ handleEvent()│→ │ Pending Map  │→ │ Jet Pipeline │              │
│  └──────────────┘  └──────────────┘  └──────────────┘              │
│                                            │                        │
│         ┌──────────────────────────────────┼───────────────────┐   │
│         │                                  │                   │   │
│         ▼                                  ▼                   ▼   │
│  ┌─────────────┐                   ┌─────────────┐    ┌───────────┐│
│  │ Event Store │                   │ View Store  │    │ Event Bus ││
│  │  (append)   │                   │  (update)   │    │ (publish) ││
│  └─────────────┘                   └─────────────┘    └───────────┘│
└─────────────────────────────────────────────────────────────────────┘
```

## Core Components

### DomainEvent

Base class for all domain events. Events are immutable and represent state changes.

```java
public class CustomerCreatedEvent extends DomainEvent<Customer, String> {
    private String email;
    private String name;

    public CustomerCreatedEvent(String customerId, String email, String name) {
        super(customerId);  // Sets the key
        this.eventType = "CustomerCreated";
        this.email = email;
        this.name = name;
    }

    @Override
    public GenericRecord apply(GenericRecord existing) {
        return GenericRecordBuilder.compact("Customer")
            .setString("customerId", key)
            .setString("email", email)
            .setString("name", name)
            .setString("status", "ACTIVE")
            .build();
    }

    @Override
    public GenericRecord toGenericRecord() {
        return GenericRecordBuilder.compact(getSchemaName())
            .setString("eventId", eventId)
            .setString("eventType", eventType)
            .setString("key", key)
            .setString("email", email)
            .setString("name", name)
            .setLong("timestamp", timestamp.toEpochMilli())
            .build();
    }
}
```

### EventSourcingController

The main entry point for event processing. Uses a builder pattern for configuration.

```java
EventSourcingController<Customer, String, CustomerEvent> controller =
    EventSourcingController.<Customer, String, CustomerEvent>builder()
        .hazelcast(hazelcastInstance)
        .domainName("Customer")
        .eventStore(customerEventStore)
        .viewUpdater(customerViewUpdater)
        .viewUpdaterClass(CustomerViewUpdater.class)
        .meterRegistry(meterRegistry)
        .build();

// Start the pipeline
controller.start();

// Handle events
CompletableFuture<CompletionInfo<String>> future =
    controller.handleEvent(new CustomerCreatedEvent("cust-123", "john@example.com", "John"));

// Query materialized view
Optional<GenericRecord> customer = controller.getViewState("cust-123");
```

### EventStore

Append-only storage for domain events.

```java
public interface EventStore<D extends DomainObject<K>, K, E extends DomainEvent<D, K>> {
    void append(PartitionedSequenceKey<K> key, GenericRecord eventRecord);
    List<GenericRecord> getEventsForKey(K key);
    List<GenericRecord> getEventsByType(String eventType);
    List<GenericRecord> getEventsInTimeRange(Instant from, Instant to);
}
```

### ViewUpdater

Transforms events into materialized view state.

```java
public abstract class ViewUpdater<K> implements Serializable {
    public abstract void update(GenericRecord eventRecord);
    public abstract long rebuild(EventStore<?, K, ?> eventStore);
    public abstract Optional<GenericRecord> rebuildForKey(K key, EventStore<?, K, ?> eventStore);
}
```

### HazelcastEventBus

Pub/sub for cross-service event propagation.

```java
HazelcastEventBus<Customer, String> eventBus = new HazelcastEventBus<>(hazelcast, "customer-events");

// Subscribe to events
eventBus.subscribe(event -> {
    logger.info("Received event: {}", event.getEventType());
});

// Events are automatically published by the pipeline
```

## Package Structure

```
com.theyawns.framework
├── controller/
│   ├── EventSourcingController.java  # Main controller
│   ├── CompletionInfo.java           # Event completion tracking
│   └── SagaMetadata.java             # Saga transaction support
│
├── domain/
│   └── DomainObject.java             # Interface for domain entities
│
├── event/
│   ├── DomainEvent.java              # Base event class
│   ├── EventMetadata.java            # Event metadata holder
│   ├── EventPublisher.java           # Publisher interface
│   └── EventSubscriber.java          # Subscriber interface
│
├── store/
│   ├── EventStore.java               # Storage interface
│   ├── HazelcastEventStore.java      # Hazelcast implementation
│   ├── PartitionedSequenceKey.java   # Composite key for partitioning
│   └── HydrationFactory.java         # Event deserialization
│
├── view/
│   ├── MaterializedViewStore.java    # View storage interface
│   ├── HazelcastViewStore.java       # Hazelcast implementation
│   ├── ViewUpdater.java              # Event-to-view transformer
│   └── UpdateViewEntryProcessor.java # Atomic view updates
│
├── pipeline/
│   ├── EventSourcingPipeline.java    # Jet streaming pipeline
│   ├── HazelcastEventBus.java        # Pub/sub implementation
│   └── PipelineMetrics.java          # Pipeline instrumentation
│
└── config/
    ├── FrameworkAutoConfiguration.java # Spring Boot auto-config
    ├── HazelcastConfig.java            # Hazelcast setup
    └── MetricsConfig.java              # Micrometer setup
```

## Configuration

### application.yml

```yaml
framework:
  hazelcast:
    cluster-name: my-cluster
    event-journal:
      capacity: 10000
```

### Hazelcast Configuration

The framework requires event journaling for the pending events map:

```yaml
hazelcast:
  cluster-name: my-cluster
  map:
    "*_PENDING":
      event-journal:
        enabled: true
        capacity: 10000
    "*_ES":
      backup-count: 1
    "*_VIEW":
      backup-count: 1
      read-backup-data: true
```

## Saga Support

The framework includes built-in saga transaction support:

```java
SagaMetadata saga = new SagaMetadata("saga-123", "OrderFulfillment", 1, false);

controller.handleEvent(
    new ReserveStockEvent(productId, quantity),
    correlationId,
    saga
);

// For compensation (rollback)
SagaMetadata compensatingSaga = new SagaMetadata("saga-123", "OrderFulfillment", 1, true);

controller.handleEvent(
    new ReleaseStockEvent(productId, quantity),
    correlationId,
    compensatingSaga
);
```

## Metrics

The framework exposes Micrometer metrics:

| Metric | Type | Description |
|--------|------|-------------|
| `eventsourcing.events.submitted` | Counter | Events submitted to the controller |
| `eventsourcing.events.failed` | Counter | Failed event submissions |
| `eventsourcing.pipeline.events.processed` | Counter | Events processed by pipeline |
| `eventsourcing.pipeline.stage.latency` | Timer | Per-stage processing time |
| `eventsourcing.view.updates` | Counter | View update operations |

## Testing

### Unit Testing

```java
@DisplayName("EventSourcingController")
class EventSourcingControllerTest {

    private HazelcastInstance hazelcast;
    private EventSourcingController<Customer, String, CustomerEvent> controller;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.setClusterName("test-" + System.currentTimeMillis());
        config.getJetConfig().setEnabled(true);
        hazelcast = Hazelcast.newHazelcastInstance(config);

        // Build controller with test components
        controller = EventSourcingController.<Customer, String, CustomerEvent>builder()
            .hazelcast(hazelcast)
            .domainName("TestCustomer")
            // ... other configuration
            .build();
    }

    @Test
    void shouldHandleEvent() {
        CustomerCreatedEvent event = new CustomerCreatedEvent("cust-1", "test@example.com", "Test");

        CompletableFuture<CompletionInfo<String>> future = controller.handleEvent(event);

        assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS));
    }
}
```

### Integration Testing

```java
@SpringBootTest
@Testcontainers
class EventSourcingIntegrationTest {

    @Container
    static GenericContainer<?> hazelcast =
        new GenericContainer<>("hazelcast/hazelcast:5.6.0")
            .withExposedPorts(5701);

    @Autowired
    private EventSourcingController<Customer, String, CustomerEvent> controller;

    @Test
    void shouldProcessEventEndToEnd() {
        // Submit event
        CustomerCreatedEvent event = new CustomerCreatedEvent(...);
        controller.handleEvent(event).get();

        // Verify view updated
        Optional<GenericRecord> view = controller.getViewState("cust-1");
        assertTrue(view.isPresent());
        assertEquals("test@example.com", view.get().getString("email"));
    }
}
```

## Requirements

- Java 17+
- Spring Boot 3.2+
- Hazelcast 5.6+ (Community Edition)
- Micrometer (for metrics)

## Dependencies

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>com.theyawns</groupId>
    <artifactId>framework-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## License

Apache License 2.0
