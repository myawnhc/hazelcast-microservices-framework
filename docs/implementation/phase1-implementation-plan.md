# Phase 1 Implementation Plan
## Clean Reimplementation - Event Sourcing Microservices Framework

---

## Implementation Decisions ✅

1. **EventStore**: Hazelcast IMap only (Phase 1), Postgres migration in Phase 2
2. **Tests**: Start from scratch, target >80% coverage
3. **Tech Stack**: Latest stable Spring Boot + Hazelcast Community Edition
4. **Services**: Fresh implementation with REST APIs (no gRPC baggage)
5. **Package**: `com.theyawns.framework.*`
6. **Approach**: Clean reimplementation (Option B)
7. **Sequence Generator**: Use Hazelcast Community Edition features only (NOT FlakeIdGenerator)

---

## Tech Stack Verification

### Spring Boot 3.2.x (Latest Stable)
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.1</version>
</parent>
```

### Hazelcast 5.4.x Community Edition (Latest Stable)
```xml
<dependency>
    <groupId>com.hazelcast</groupId>
    <artifactId>hazelcast</artifactId>
    <version>5.4.0</version>
</dependency>
<dependency>
    <groupId>com.hazelcast</groupId>
    <artifactId>hazelcast-spring</artifactId>
    <version>5.4.0</version>
</dependency>
```

### IMPORTANT: Sequence Generation
**FlakeIdGenerator is Hazelcast Community Edition** - Good news! It's available.
- ✅ Use FlakeIdGenerator (distributed, no coordination needed)
- ❌ Don't use IAtomicLong (requires CP Subsystem - Enterprise only)

Verification: https://docs.hazelcast.com/hazelcast/5.4/data-structures/flake-id-generator

### Other Dependencies
```xml
<!-- Testing -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>hazelcast</artifactId>
    <version>1.19.3</version>
    <scope>test</scope>
</dependency>

<!-- Observability -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

<!-- JSON Logging -->
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

---

## Project Structure

```
hazelcast-microservices-framework/
├── docs/                                    # Already created
│   ├── design/
│   ├── architecture/
│   └── requirements/
│
├── framework-core/                          # NEW - Domain-agnostic framework
│   ├── src/main/java/com/theyawns/framework/
│   │   ├── event/
│   │   │   ├── DomainEvent.java            # Base event class
│   │   │   ├── EventMetadata.java          # Correlation, saga metadata
│   │   │   ├── EventPublisher.java         # Interface
│   │   │   ├── EventSubscriber.java        # Interface
│   │   │   └── HazelcastEventBus.java      # Implementation
│   │   │
│   │   ├── domain/
│   │   │   └── DomainObject.java           # Interface for domain objects
│   │   │
│   │   ├── store/
│   │   │   ├── EventStore.java             # Event persistence
│   │   │   ├── PartitionedSequenceKey.java # Composite key
│   │   │   └── HydrationFactory.java       # Deserialize events/objects
│   │   │
│   │   ├── view/
│   │   │   ├── MaterializedViewStore.java  # Interface
│   │   │   ├── ViewUpdater.java            # Interface
│   │   │   ├── HazelcastViewStore.java     # Implementation
│   │   │   └── UpdateViewEntryProcessor.java # Hazelcast EntryProcessor
│   │   │
│   │   ├── pipeline/
│   │   │   ├── EventSourcingPipeline.java  # Jet pipeline
│   │   │   └── PipelineMetrics.java        # Metrics collection
│   │   │
│   │   ├── controller/
│   │   │   ├── EventSourcingController.java # Main controller
│   │   │   └── CompletionInfo.java          # Event completion tracking
│   │   │
│   │   ├── config/
│   │   │   ├── FrameworkConfig.java        # Auto-configuration
│   │   │   ├── HazelcastConfig.java        # Hazelcast setup
│   │   │   └── MetricsConfig.java          # Micrometer setup
│   │   │
│   │   └── exception/
│   │       ├── EventProcessingException.java
│   │       └── ViewUpdateException.java
│   │
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── logback-spring.xml
│   │
│   └── src/test/java/com/theyawns/framework/
│       ├── event/
│       ├── store/
│       ├── view/
│       └── pipeline/
│
├── ecommerce-common/                        # NEW - Shared eCommerce domain
│   ├── src/main/java/com/theyawns/ecommerce/common/
│   │   ├── events/                         # Event schemas
│   │   │   ├── CustomerCreatedEvent.java
│   │   │   ├── ProductCreatedEvent.java
│   │   │   ├── OrderCreatedEvent.java
│   │   │   └── ...
│   │   └── dto/                            # Shared DTOs
│   └── pom.xml
│
├── account-service/                         # NEW - Account microservice
│   ├── src/main/java/com/theyawns/ecommerce/account/
│   │   ├── AccountServiceApplication.java
│   │   ├── domain/
│   │   │   └── Customer.java               # DomainObject implementation
│   │   ├── events/
│   │   │   └── (event implementations)
│   │   ├── service/
│   │   │   └── AccountService.java
│   │   ├── controller/
│   │   │   └── AccountController.java      # REST endpoints
│   │   └── config/
│   │       └── AccountServiceConfig.java
│   ├── src/main/resources/
│   │   └── application.yml
│   └── src/test/java/
│
├── inventory-service/                       # NEW - Inventory microservice
│   ├── (similar structure to account-service)
│
├── order-service/                           # NEW - Order microservice
│   ├── (similar structure to account-service)
│
├── docker/                                  # NEW - Docker setup
│   ├── docker-compose.yml                  # All services + Hazelcast
│   ├── hazelcast/
│   │   └── hazelcast.yaml                  # Cluster config
│   └── prometheus/
│       └── prometheus.yml
│
├── scripts/                                 # NEW - Utility scripts
│   ├── load-sample-data.sh
│   ├── run-tests.sh
│   └── benchmark.sh
│
├── pom.xml                                  # Root POM
└── README.md
```

---

## Implementation Sequence

### Week 1: Framework Core (Days 1-5)

#### Day 1: Project Setup + Core Abstractions
**Tasks**:
1. Create Maven multi-module project structure
2. Setup parent POM with Spring Boot + Hazelcast dependencies
3. Create framework-core module
4. Implement core interfaces:
   - `DomainEvent.java` (with all metadata fields)
   - `DomainObject.java`
   - `EventPublisher.java` / `EventSubscriber.java`
   - `MaterializedViewStore.java`

**Deliverables**:
- [ ] Maven project builds
- [ ] Core interfaces defined
- [ ] Basic unit tests for interfaces

#### Day 2: Event Store Implementation
**Tasks**:
1. Implement `EventStore.java` using Hazelcast IMap
2. Implement `PartitionedSequenceKey.java`
3. Implement `HydrationFactory.java` for serialization
4. Add event query methods (by key, by type, by time range)
5. Write tests for EventStore

**Deliverables**:
- [ ] EventStore saves/retrieves events
- [ ] Query methods work
- [ ] Tests pass (>80% coverage)

#### Day 3: Materialized View Components
**Tasks**:
1. Implement `HazelcastViewStore.java`
2. Implement `UpdateViewEntryProcessor.java`
3. Implement `ViewUpdater.java` base class
4. Add view rebuild capability
5. Write tests

**Deliverables**:
- [ ] Views can be updated from events
- [ ] Views can be rebuilt from EventStore
- [ ] Tests pass

#### Day 4: Event Sourcing Pipeline
**Tasks**:
1. Implement `EventSourcingPipeline.java` with 6 stages
2. Add `PipelineMetrics.java` for instrumentation
3. Implement `HazelcastEventBus.java` for pub/sub
4. Write pipeline tests

**Deliverables**:
- [ ] Pipeline processes events end-to-end
- [ ] Metrics collected at each stage
- [ ] Tests pass

#### Day 5: Controller & Configuration
**Tasks**:
1. Implement `EventSourcingController.java` with Builder
2. Implement `CompletionInfo.java` tracking
3. Add Spring Boot auto-configuration
4. Add HazelcastConfig with Community Edition settings
5. Write integration tests

**Deliverables**:
- [ ] handleEvent() works end-to-end
- [ ] Controller auto-configures with Spring Boot
- [ ] Integration tests pass
- [ ] Week 1 DONE: Framework core complete

---

### Week 2: eCommerce Services (Days 6-10)

#### Day 6: Common Domain Model
**Tasks**:
1. Create ecommerce-common module
2. Define event schemas:
   - `CustomerCreatedEvent`, `CustomerUpdatedEvent`
   - `ProductCreatedEvent`, `StockReservedEvent`, etc.
   - `OrderCreatedEvent`, `OrderConfirmedEvent`, etc.
3. Define shared DTOs
4. Write tests for event serialization

**Deliverables**:
- [ ] All event classes implemented
- [ ] Events serialize to/from GenericRecord
- [ ] Tests pass

#### Day 7: Account Service
**Tasks**:
1. Create account-service module
2. Implement `Customer` domain object
3. Implement event handlers
4. Create `AccountService` business logic
5. Create `AccountController` REST endpoints:
   - POST /api/customers
   - GET /api/customers/{id}
   - PUT /api/customers/{id}
   - PATCH /api/customers/{id}/status
6. Write unit + integration tests

**Deliverables**:
- [ ] Account service runs standalone
- [ ] REST endpoints work
- [ ] Events published on customer changes
- [ ] Tests pass

#### Day 8: Inventory Service
**Tasks**:
1. Create inventory-service module
2. Implement `Product` domain object
3. Implement event handlers (including stock reservation logic)
4. Create `InventoryService` business logic
5. Create `InventoryController` REST endpoints:
   - POST /api/products
   - GET /api/products/{id}
   - POST /api/products/{id}/stock/reserve
   - POST /api/products/{id}/stock/release
6. Listen to OrderCreated events
7. Write tests

**Deliverables**:
- [ ] Inventory service runs standalone
- [ ] REST endpoints work
- [ ] Listens to orders, reserves stock
- [ ] Tests pass

#### Day 9: Order Service (Part 1)
**Tasks**:
1. Create order-service module
2. Implement `Order` domain object
3. Implement event handlers
4. Create `OrderService` business logic
5. Create `OrderController` REST endpoints:
   - POST /api/orders
   - GET /api/orders/{id}
   - GET /api/orders/customer/{customerId}
   - PATCH /api/orders/{id}/cancel

**Deliverables**:
- [ ] Order service runs standalone
- [ ] REST endpoints work
- [ ] Tests pass

#### Day 10: Order Service (Part 2) - Materialized Views
**Tasks**:
1. Implement customer-view updater (listens to Customer events)
2. Implement product-availability-view updater (listens to Product events)
3. Implement enriched-order-view updater
4. Implement customer-order-summary-view updater
5. Test view updates from events
6. Test end-to-end order flow

**Deliverables**:
- [ ] All views update from events
- [ ] Order queries use views (no service calls!)
- [ ] End-to-end tests pass
- [ ] Week 2 DONE: Services complete

---

### Week 3: Integration & Docker (Days 11-15)

#### Day 11: Docker Compose Setup
**Tasks**:
1. Create Dockerfile for each service
2. Create docker-compose.yml
3. Configure Hazelcast cluster (3 nodes)
4. Add Prometheus for metrics
5. Test full stack startup

**Deliverables**:
- [ ] `docker-compose up` starts all services
- [ ] Services connect to Hazelcast cluster
- [ ] Metrics accessible via Prometheus
- [ ] Laptop can run full demo (<8GB RAM)

#### Day 12: Demo Scenarios & Sample Data
**Tasks**:
1. Create sample data scripts
2. Implement demo scenario 1: Happy path
   - Create customer
   - Create product
   - Place order
   - Query enriched order (shows denormalized data)
3. Implement demo scenario 2: Order cancellation
4. Implement demo scenario 3: View rebuilding
5. Create demo walkthrough guide

**Deliverables**:
- [ ] Sample data loads successfully
- [ ] All demo scenarios work
- [ ] Demo walkthrough documented

#### Day 13: Testing & Quality
**Tasks**:
1. Review test coverage (aim for >80%)
2. Add missing tests
3. Run load tests (verify 100+ TPS)
4. Fix any bugs found
5. Performance tuning if needed

**Deliverables**:
- [ ] Test coverage >80%
- [ ] All tests passing
- [ ] Performance meets targets (100+ TPS)

#### Day 14: Documentation
**Tasks**:
1. Write JavaDoc for all public APIs
2. Write README for framework-core
3. Write README for each service
4. Write setup/installation guide
5. Write API documentation (OpenAPI/Swagger)
6. Update architecture docs based on implementation

**Deliverables**:
- [ ] All code documented
- [ ] READMEs complete
- [ ] Setup guide works (tested by someone else if possible)
- [ ] API docs available at /swagger-ui

#### Day 15: Blog Post Prep & Review
**Tasks**:
1. Draft blog post 1: "Event Sourcing with Hazelcast - Introduction"
2. Draft blog post 2: "Building the Event Pipeline with Hazelcast Jet"
3. Draft blog post 3: "Materialized Views for Fast Queries"
4. Create code examples for blog posts
5. Review Week 3 deliverables

**Deliverables**:
- [ ] 3 blog post drafts
- [ ] Code examples ready
- [ ] Phase 1 COMPLETE ✅

---

## Detailed Code Templates

### 1. DomainEvent.java (framework-core)

```java
package com.theyawns.framework.event;

import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Base class for all domain events in the event sourcing framework.
 * Events represent state changes to domain objects and are the source of truth.
 * 
 * <p>All events are:
 * <ul>
 *   <li>Immutable after creation</li>
 *   <li>Serialized as GenericRecord for flexibility</li>
 *   <li>Applied to domain objects via the apply() method</li>
 *   <li>Published to subscribers automatically</li>
 * </ul>
 * 
 * @param <D> The domain object type this event affects
 * @param <K> The key type of the domain object
 */
public abstract class DomainEvent<D extends DomainObject<K>, K> 
    implements UnaryOperator<GenericRecord>, Serializable {
    
    // Event identification
    protected String eventId;
    protected String eventType;
    protected String eventVersion;
    
    // Event source
    protected String source;        // Service that created the event
    protected Instant timestamp;    // When event was created
    
    // Domain object reference
    protected K key;               // Key of affected domain object
    
    // Traceability
    protected String correlationId; // Links related events across services
    
    // Saga support (optional, null if not part of saga)
    protected String sagaId;        // Saga instance identifier
    protected String sagaType;      // Type of saga (e.g., "OrderFulfillment")
    protected Integer stepNumber;   // Position in saga sequence
    protected Boolean isCompensating; // Is this a rollback event?
    
    // Performance tracking
    protected Instant submittedAt;       // When handleEvent() was called
    protected Instant pipelineEntryTime; // When entered Jet pipeline
    
    /**
     * Default constructor initializes base metadata.
     */
    protected DomainEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.eventVersion = getDefaultVersion();
        this.isCompensating = false;
    }
    
    /**
     * Returns the default version for this event type.
     * Subclasses can override to change versioning.
     */
    protected String getDefaultVersion() {
        return "1.0";
    }
    
    /**
     * Serialize this event to a GenericRecord for storage.
     * Subclasses must implement to include their specific fields.
     * 
     * @return GenericRecord representation of this event
     */
    public abstract GenericRecord toGenericRecord();
    
    /**
     * Apply this event to a domain object's GenericRecord representation.
     * This is how events modify domain object state.
     * 
     * @param domainObjectRecord The current state of the domain object
     * @return The updated state after applying this event
     */
    @Override
    public abstract GenericRecord apply(GenericRecord domainObjectRecord);
    
    // Getters and setters
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    
    public String getEventVersion() { return eventVersion; }
    public void setEventVersion(String eventVersion) { this.eventVersion = eventVersion; }
    
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    
    public K getKey() { return key; }
    public void setKey(K key) { this.key = key; }
    
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    
    // Saga support
    public String getSagaId() { return sagaId; }
    public void setSagaId(String sagaId) { this.sagaId = sagaId; }
    
    public String getSagaType() { return sagaType; }
    public void setSagaType(String sagaType) { this.sagaType = sagaType; }
    
    public Integer getStepNumber() { return stepNumber; }
    public void setStepNumber(Integer stepNumber) { this.stepNumber = stepNumber; }
    
    public Boolean getIsCompensating() { return isCompensating; }
    public void setIsCompensating(Boolean compensating) { isCompensating = compensating; }
    
    // Performance tracking
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    
    public Instant getPipelineEntryTime() { return pipelineEntryTime; }
    public void setPipelineEntryTime(Instant pipelineEntryTime) { 
        this.pipelineEntryTime = pipelineEntryTime; 
    }
}
```

### 2. EventSourcingController.java (framework-core)

```java
package com.theyawns.framework.controller;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.flakeidgen.FlakeIdGenerator;
import com.hazelcast.jet.Job;
import com.hazelcast.map.IMap;
import com.hazelcast.nio.serialization.genericrecord.GenericRecord;
import com.theyawns.framework.domain.DomainObject;
import com.theyawns.framework.event.DomainEvent;
import com.theyawns.framework.pipeline.EventSourcingPipeline;
import com.theyawns.framework.store.EventStore;
import com.theyawns.framework.store.PartitionedSequenceKey;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Main controller for the event sourcing framework.
 * Provides the central handleEvent() method that all services use to process events.
 * 
 * <p>Responsibilities:
 * <ul>
 *   <li>Accept events via handleEvent()</li>
 *   <li>Assign sequence numbers</li>
 *   <li>Write to PendingEvents (triggers pipeline)</li>
 *   <li>Track completion via CompletionInfo</li>
 *   <li>Return CompletableFuture for async responses</li>
 * </ul>
 * 
 * @param <D> Domain object type
 * @param <K> Domain object key type
 * @param <E> Base event type for this domain
 */
public class EventSourcingController<D extends DomainObject<K>, 
                                      K extends Comparable<K>, 
                                      E extends DomainEvent<D,K>> {
    
    private static final Logger logger = LoggerFactory.getLogger(EventSourcingController.class);
    
    private final HazelcastInstance hazelcast;
    private final String domainObjectName;
    private final FlakeIdGenerator sequenceGenerator;
    private final EventStore<D,K,E> eventStore;
    private final IMap<K, GenericRecord> viewMap;
    private final IMap<PartitionedSequenceKey<K>, GenericRecord> pendingEventsMap;
    private final IMap<PartitionedSequenceKey<K>, GenericRecord> completionsMap;
    private final Job pipelineJob;
    private final MeterRegistry meterRegistry;
    
    /**
     * Central event handling method.
     * 
     * <p>Process:
     * <ol>
     *   <li>Set event metadata (correlation ID, timestamp, source)</li>
     *   <li>Assign sequence number</li>
     *   <li>Write to PendingEvents (triggers pipeline)</li>
     *   <li>Return future that completes when processing finishes</li>
     * </ol>
     * 
     * @param event The domain event to process
     * @param correlationId UUID linking related events
     * @param sagaMetadata Optional saga information (null if not part of saga)
     * @return CompletableFuture that completes when event is processed
     */
    public CompletableFuture<CompletionInfo> handleEvent(
        E event,
        UUID correlationId,
        SagaMetadata sagaMetadata
    ) {
        try {
            // Set metadata
            event.setCorrelationId(correlationId.toString());
            event.setSource(domainObjectName);
            event.setSubmittedAt(Instant.now());
            
            // Saga support
            if (sagaMetadata != null) {
                event.setSagaId(sagaMetadata.getSagaId());
                event.setSagaType(sagaMetadata.getSagaType());
                event.setStepNumber(sagaMetadata.getStepNumber());
                event.setIsCompensating(sagaMetadata.getIsCompensating());
            }
            
            // Assign sequence number
            long sequence = sequenceGenerator.newId();
            PartitionedSequenceKey<K> psk = new PartitionedSequenceKey<>(sequence, event.getKey());
            
            // Create completion tracker
            CompletableFuture<CompletionInfo> future = new CompletableFuture<>();
            CompletionInfo info = new CompletionInfo(psk, event.toGenericRecord(), correlationId);
            
            // Write to completions map
            completionsMap.set(psk, info.toGenericRecord());
            
            // Write to pending events (TRIGGERS PIPELINE)
            GenericRecord eventGR = event.toGenericRecord();
            pendingEventsMap.set(psk, eventGR);
            
            // Metrics
            meterRegistry.counter("events.submitted", 
                "eventType", event.getEventType(),
                "domain", domainObjectName
            ).increment();
            
            logger.debug("Event submitted: {} (correlation: {})", 
                event.getEventType(), correlationId);
            
            return future;
            
        } catch (Exception e) {
            logger.error("Failed to handle event: " + event.getEventType(), e);
            meterRegistry.counter("events.failed",
                "eventType", event.getEventType(),
                "domain", domainObjectName
            ).increment();
            
            CompletableFuture<CompletionInfo> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(e);
            return failedFuture;
        }
    }
    
    /**
     * Convenience method for events not part of a saga.
     */
    public CompletableFuture<CompletionInfo> handleEvent(E event, UUID correlationId) {
        return handleEvent(event, correlationId, null);
    }
    
    /**
     * Convenience method that generates correlation ID.
     */
    public CompletableFuture<CompletionInfo> handleEvent(E event) {
        return handleEvent(event, UUID.randomUUID(), null);
    }
    
    // Getters
    public String getDomainObjectName() { return domainObjectName; }
    public EventStore<D,K,E> getEventStore() { return eventStore; }
    public IMap<K, GenericRecord> getViewMap() { return viewMap; }
    public HazelcastInstance getHazelcast() { return hazelcast; }
    
    // Constructor and Builder would go here...
}
```

### 3. REST Controller Example (account-service)

```java
package com.theyawns.ecommerce.account.controller;

import com.theyawns.ecommerce.account.domain.Customer;
import com.theyawns.ecommerce.account.service.AccountService;
import com.theyawns.ecommerce.common.dto.CustomerDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/customers")
@Tag(name = "Customer Management", description = "APIs for managing customer accounts")
public class AccountController {
    
    private final AccountService accountService;
    
    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }
    
    @PostMapping
    @Operation(summary = "Create new customer", 
               description = "Registers a new customer account")
    public CompletableFuture<ResponseEntity<CustomerDTO>> createCustomer(
        @Valid @RequestBody CustomerDTO customerDTO
    ) {
        return accountService.createCustomer(customerDTO)
            .thenApply(customer -> ResponseEntity
                .status(HttpStatus.CREATED)
                .body(customer.toDTO())
            );
    }
    
    @GetMapping("/{customerId}")
    @Operation(summary = "Get customer by ID",
               description = "Retrieves customer details from materialized view")
    public ResponseEntity<CustomerDTO> getCustomer(
        @PathVariable String customerId
    ) {
        return accountService.getCustomer(customerId)
            .map(customer -> ResponseEntity.ok(customer.toDTO()))
            .orElse(ResponseEntity.notFound().build());
    }
    
    @PutMapping("/{customerId}")
    @Operation(summary = "Update customer",
               description = "Updates customer profile information")
    public CompletableFuture<ResponseEntity<CustomerDTO>> updateCustomer(
        @PathVariable String customerId,
        @Valid @RequestBody CustomerDTO customerDTO
    ) {
        return accountService.updateCustomer(customerId, customerDTO)
            .thenApply(customer -> ResponseEntity.ok(customer.toDTO()));
    }
    
    @PatchMapping("/{customerId}/status")
    @Operation(summary = "Change customer status",
               description = "Activates, suspends, or closes customer account")
    public CompletableFuture<ResponseEntity<Void>> changeStatus(
        @PathVariable String customerId,
        @RequestParam String status
    ) {
        return accountService.changeStatus(customerId, status)
            .thenApply(v -> ResponseEntity.ok().<Void>build());
    }
}
```

---

## Test Templates

### Unit Test Example

```java
package com.theyawns.framework.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

class DomainEventTest {
    
    private TestEvent event;
    
    @BeforeEach
    void setUp() {
        event = new TestEvent("test-key", "test-data");
    }
    
    @Test
    void shouldGenerateEventId() {
        assertNotNull(event.getEventId());
        assertTrue(event.getEventId().length() > 0);
    }
    
    @Test
    void shouldSetDefaultVersion() {
        assertEquals("1.0", event.getEventVersion());
    }
    
    @Test
    void shouldSetCorrelationId() {
        String correlationId = UUID.randomUUID().toString();
        event.setCorrelationId(correlationId);
        assertEquals(correlationId, event.getCorrelationId());
    }
    
    @Test
    void shouldSerializeToGenericRecord() {
        GenericRecord gr = event.toGenericRecord();
        assertNotNull(gr);
        assertEquals(event.getEventType(), gr.getString("eventType"));
        assertEquals(event.getKey(), gr.getString("key"));
    }
}
```

### Integration Test Example

```java
package com.theyawns.ecommerce.account;

import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AccountServiceIntegrationTest {
    
    @Container
    static GenericContainer<?> hazelcast = new GenericContainer<>("hazelcast/hazelcast:5.4.0")
        .withExposedPorts(5701);
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Autowired
    private HazelcastInstance hazelcastInstance;
    
    @Test
    void shouldCreateCustomerEndToEnd() {
        // Create customer via REST
        CustomerDTO request = new CustomerDTO("john@example.com", "John Doe", "123 Main St");
        ResponseEntity<CustomerDTO> response = restTemplate.postForEntity(
            "/api/customers",
            request,
            CustomerDTO.class
        );
        
        // Verify response
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody().getCustomerId());
        
        // Verify event in event store
        // Verify materialized view updated
        // etc...
    }
}
```

---

## Key Implementation Notes

### 1. Sequence Generation (Community Edition)
```java
// USE FlakeIdGenerator (Community Edition compatible)
FlakeIdGenerator sequenceGenerator = hazelcast.getFlakeIdGenerator("sequence-gen");
long sequence = sequenceGenerator.newId();

// DON'T USE IAtomicLong (requires Enterprise CP Subsystem)
// IAtomicLong atomicLong = hazelcast.getCPSubsystem().getAtomicLong("seq"); // ENTERPRISE ONLY
```

### 2. Hazelcast Configuration (Community Edition)
```yaml
hazelcast:
  cluster-name: ecommerce-demo
  network:
    port:
      auto-increment: true
      port: 5701
    join:
      multicast:
        enabled: true
  map:
    "*_PENDING":
      event-journal:
        enabled: true
        capacity: 10000
    "*_ES":
      backup-count: 1
      in-memory-format: BINARY
      eviction:
        eviction-policy: NONE
    "*_VIEW":
      backup-count: 1
      read-backup-data: true
```

### 3. Spring Boot Properties
```yaml
spring:
  application:
    name: account-service
  hazelcast:
    config: classpath:hazelcast.yaml

server:
  port: 8081

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics
  metrics:
    export:
      prometheus:
        enabled: true
```

---

## Success Criteria

### Week 1 Complete When:
- [ ] Framework core compiles
- [ ] All unit tests pass (>80% coverage)
- [ ] Sample event can flow through pipeline
- [ ] Materialized view updates from event

### Week 2 Complete When:
- [ ] All 3 services run independently
- [ ] REST endpoints work
- [ ] Events flow between services
- [ ] Materialized views contain denormalized data
- [ ] Integration tests pass

### Week 3 Complete When:
- [ ] `docker-compose up` starts full system
- [ ] Demo scenarios work end-to-end
- [ ] Documentation complete
- [ ] Blog posts drafted
- [ ] Ready for Phase 2!

---

## Ready to Start?

Next actions:
1. **I'll create the initial Maven project structure**
2. **I'll generate the framework-core skeleton**
3. **We move to Claude Code for implementation**

Sound good?
