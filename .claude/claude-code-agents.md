# Custom Agents for Hazelcast Microservices Framework

This file defines specialized agent configurations for different types of tasks.

---

## Agent: Framework Developer

**Purpose**: Building the core framework components

**Rules**:
- Focus on domain-agnostic, reusable code
- Every public API must have comprehensive JavaDoc
- Interfaces before implementations
- Test coverage >80% required
- No service-specific logic in framework

**Checklist for every component**:
1. [ ] Interface defined with clear contracts
2. [ ] JavaDoc complete with usage examples
3. [ ] Implementation follows interface
4. [ ] Unit tests cover happy path + edge cases
5. [ ] No hard-coded service-specific values

---

## Agent: Service Developer

**Purpose**: Building microservices (Account, Inventory, Order)

**Rules**:
- Use framework abstractions, don't reinvent
- REST controllers follow standard pattern
- All endpoints have OpenAPI documentation
- Service layer handles business logic only
- Controllers handle HTTP concerns only

**Checklist for every endpoint**:
1. [ ] Service method implemented and tested
2. [ ] Controller method delegates to service
3. [ ] DTO classes for request/response
4. [ ] Validation annotations on DTOs
5. [ ] OpenAPI annotations on controller
6. [ ] Integration test covers full flow

---

## Agent: Test Writer

**Purpose**: Writing comprehensive tests

**Rules**:
- Unit tests: No external dependencies (use mocks)
- Integration tests: Use Testcontainers
- Test names describe behavior being tested
- Arrange-Act-Assert structure
- One assertion per test when possible

**Test patterns**:

```java
// Unit test pattern
@DisplayName("EventSourcingController")
class EventSourcingControllerTest {
    
    private EventSourcingController controller;
    private EventStore mockEventStore;
    private IMap mockViewMap;
    
    @BeforeEach
    void setUp() {
        mockEventStore = mock(EventStore.class);
        mockViewMap = mock(IMap.class);
        controller = new EventSourcingController(
            mockHazelcast,
            "test-domain",
            mockEventStore,
            mockViewMap,
            // ... other mocks
        );
    }
    
    @Test
    @DisplayName("should set correlation ID when handling event")
    void shouldSetCorrelationIdWhenHandlingEvent() {
        // Arrange
        TestEvent event = new TestEvent("key-1", "data");
        UUID correlationId = UUID.randomUUID();
        
        // Act
        controller.handleEvent(event, correlationId);
        
        // Assert
        assertEquals(correlationId.toString(), event.getCorrelationId());
    }
}

// Integration test pattern
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class AccountServiceIntegrationTest {
    
    @Container
    static GenericContainer<?> hazelcast = 
        new GenericContainer<>("hazelcast/hazelcast:5.6.0")
            .withExposedPorts(5701);
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Test
    @DisplayName("should create customer and retrieve from view")
    void shouldCreateCustomerAndRetrieveFromView() {
        // Arrange
        CustomerDTO request = new CustomerDTO(
            "john@example.com",
            "John Doe",
            "123 Main St"
        );
        
        // Act - Create
        ResponseEntity<CustomerDTO> createResponse = 
            restTemplate.postForEntity("/api/customers", request, CustomerDTO.class);
        
        // Assert - Create
        assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());
        String customerId = createResponse.getBody().getCustomerId();
        assertNotNull(customerId);
        
        // Act - Retrieve
        ResponseEntity<CustomerDTO> getResponse = 
            restTemplate.getForEntity("/api/customers/" + customerId, CustomerDTO.class);
        
        // Assert - Retrieve
        assertEquals(HttpStatus.OK, getResponse.getStatusCode());
        assertEquals("john@example.com", getResponse.getBody().getEmail());
    }
}
```

---

## Agent: Documentation Writer

**Purpose**: Writing clear, helpful documentation

**Rules**:
- Write for someone new to the project
- Include code examples for complex concepts
- Explain WHY, not just WHAT
- Use diagrams for architecture
- Keep docs up-to-date with code

**Documentation checklist**:
1. [ ] README has clear setup instructions
2. [ ] Architecture docs explain design decisions
3. [ ] API docs show request/response examples
4. [ ] Troubleshooting section exists
5. [ ] Code examples actually work

---

## Agent: Pipeline Specialist

**Purpose**: Working with Hazelcast Jet pipelines

**Special knowledge**:
- Jet pipelines are distributed, stateless by default
- Use ServiceFactory for shared resources
- Avoid blocking operations
- EntryProcessor for atomic view updates
- Event journal enabled on source maps

**Pipeline pattern**:
```java
Pipeline p = Pipeline.create();

p.readFrom(Sources.mapJournal(
    "pending-events",
    JournalInitialPosition.START_FROM_CURRENT
))
.withIngestionTimestamps()
// Stage 1: Add timestamp
.map(entry -> {
    // Non-blocking transformation
    event.setPipelineEntryTime(Instant.now());
    return tuple2(psk, event);
})
// Stage 2: Persist (uses service)
.mapUsingService(eventStoreService, (store, tuple) -> {
    store.append(tuple.f0(), tuple.f1());
    return tuple;
})
// Stage 3: Update view (uses EntryProcessor for atomicity)
.mapUsingService(viewMapService, (viewMap, tuple) -> {
    viewMap.executeOnKey(
        tuple.f1().getKey(),
        new UpdateViewEntryProcessor(tuple.f1())
    );
    return tuple;
})
.writeTo(Sinks.map(completionsMap, /* ... */));
```

**Common pitfalls**:
- ❌ Blocking in map stage
- ❌ Mutable shared state
- ❌ Synchronous external calls
- ❌ Large object serialization

---

## Agent: Config Manager

**Purpose**: Managing application configuration

**Rules**:
- Use application.yml for configuration
- Environment-specific profiles (dev, test, prod)
- Sensitive values from environment variables
- Hazelcast config separate from Spring config
- Metrics and health checks always enabled

**Configuration structure**:

```yaml
# application.yml (common)
spring:
  application:
    name: ${SERVICE_NAME:account-service}

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics

---
# application-dev.yml
logging:
  level:
    com.theyawns: DEBUG
    com.hazelcast: INFO

hazelcast:
  network:
    join:
      multicast:
        enabled: true

---
# application-prod.yml
logging:
  level:
    com.theyawns: INFO
    com.hazelcast: WARN

hazelcast:
  network:
    join:
      kubernetes:
        enabled: true
        namespace: ecommerce
```

---

## Agent: Performance Optimizer

**Purpose**: Ensuring performance targets met

**Targets** (from Phase 1 plan):
- Event processing: <100ms end-to-end
- View read: <5ms (p99)
- TPS: 100+ on laptop, 1000+ scaled

**Optimization techniques**:
1. **Partition Keys**: Always use for IMap operations
2. **Batch Operations**: `getAll()` instead of multiple `get()`
3. **Entry Processor**: Atomic updates without network overhead
4. **GenericRecord**: Efficient serialization
5. **Pipeline Parallelism**: Let Jet distribute work

**Performance testing**:
```java
@Test
void shouldMeetPerformanceTargets() {
    // Warm up
    for (int i = 0; i < 100; i++) {
        createCustomer();
    }
    
    // Measure
    List<Long> latencies = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
        long start = System.nanoTime();
        createCustomer();
        long end = System.nanoTime();
        latencies.add(TimeUnit.NANOSECONDS.toMillis(end - start));
    }
    
    // Assert
    double p99 = calculatePercentile(latencies, 0.99);
    assertTrue(p99 < 100, "p99 latency should be < 100ms, was: " + p99);
}
```

---

## Agent: Debugging Helper

**Purpose**: Troubleshooting issues

**Common issues and solutions**:

### Issue: Events not processing
**Check**:
1. Event journal enabled on pending map?
2. Pipeline job running? (`Job.getStatus()`)
3. Events actually in pending map?
4. Check pipeline logs for exceptions

### Issue: View not updating
**Check**:
1. EntryProcessor executing? (add logging)
2. Event apply() method correct?
3. View map key matches event key?
4. GenericRecord schema correct?

### Issue: Tests failing intermittently
**Check**:
1. Async operations - use `CompletableFuture.get()` with timeout
2. Hazelcast cluster initialization - wait for cluster to form
3. Test isolation - clean up maps between tests
4. Race conditions - use CountDownLatch or similar

### Issue: Hazelcast exceptions
**Check**:
1. Using Community Edition features only?
2. Cluster members can discover each other?
3. Serialization configured for custom types?
4. Map configurations applied?

**Debugging tools**:
```java
// Log Hazelcast internals
logger.info("Cluster members: {}", hazelcast.getCluster().getMembers());
logger.info("Pipeline job status: {}", pipelineJob.getStatus());
logger.info("Pending events count: {}", pendingEventsMap.size());
logger.info("View map count: {}", viewMap.size());

// Enable verbose Hazelcast logging
<logger name="com.hazelcast" level="DEBUG"/>
```

---

## Task Routing Guide

### When to use which agent:

| Task Type | Agent | Example |
|-----------|-------|---------|
| Implement EventStore | Framework Developer | "Create EventStore class" |
| Implement AccountService | Service Developer | "Create customer management APIs" |
| Write tests for pipeline | Test Writer | "Add tests for pipeline stages" |
| Update README | Documentation Writer | "Document setup process" |
| Create Jet pipeline | Pipeline Specialist | "Implement event processing pipeline" |
| Configure Hazelcast | Config Manager | "Setup cluster configuration" |
| Optimize queries | Performance Optimizer | "Improve view read performance" |
| Fix failing test | Debugging Helper | "Why is integration test flaky?" |

---

## Multi-Agent Workflows

### Workflow: New Framework Component
1. **Framework Developer**: Define interface + base implementation
2. **Test Writer**: Write comprehensive tests
3. **Documentation Writer**: JavaDoc + usage guide
4. **Performance Optimizer**: Verify performance characteristics

### Workflow: New Microservice
1. **Service Developer**: Implement service + controllers
2. **Test Writer**: Unit + integration tests
3. **Config Manager**: Service configuration
4. **Documentation Writer**: API documentation + README

### Workflow: New Feature
1. **Service Developer**: Implement feature
2. **Pipeline Specialist**: Update pipeline if needed
3. **Test Writer**: Add tests
4. **Performance Optimizer**: Verify performance impact
5. **Documentation Writer**: Update docs

---

## Quality Gates

### Before Committing:
1. All tests pass (`mvn clean test`)
2. Code coverage >80% (`mvn jacoco:report`)
3. No compile warnings
4. JavaDoc complete
5. Code follows style guide

### Before Merging:
1. Integration tests pass
2. Performance tests meet targets
3. Documentation updated
4. Code reviewed
5. No TODOs or FIXMEs in committed code

---

## Agent Communication Protocol

When agents need to coordinate:

### Framework Developer → Service Developer
"Interface X is ready with these methods: ..."

### Service Developer → Test Writer  
"Service Y implemented with endpoints: ... Please test"

### Test Writer → Debugging Helper
"Test Z failing intermittently, see stack trace: ..."

### Any Agent → Documentation Writer
"Feature ABC complete, needs documentation"

### Any Agent → Performance Optimizer
"Concerned about performance of XYZ"

---

Last updated: 2026-01-24
Version: 1.0
