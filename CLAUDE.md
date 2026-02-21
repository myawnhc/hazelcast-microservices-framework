# Claude Code Rules for Hazelcast Microservices Framework

## Project Context

This is a **Hazelcast-based event sourcing microservices framework** with a clean, educational implementation.

### Core Principles
1. **Event Sourcing First**: Events are the source of truth, not database state
2. **Domain-Agnostic Framework**: Core framework should work for any domain
3. **Educational Quality**: Code should be exemplary for blog posts and demos
4. **Production-Ready Patterns**: Follow enterprise best practices
5. **Test-Driven**: >80% test coverage required

---

## Technology Stack

### Required Versions
- **Java**: 17 or higher
- **Spring Boot**: 3.2.x (latest stable)
- **Hazelcast**: 5.6.0 (Community Edition default, Enterprise Edition optional)
- **Maven**: 3.8+

### Hazelcast Edition Strategy

**Core Principle**: Community Edition is the default. Enterprise features are optional enhancements.

#### Default Configuration (Community Edition)
The framework MUST work fully with Community Edition only. This is:
- The default configuration
- Required for demos and tutorials
- The expected setup for most users

**Community Edition Features** (always available):
- ✅ FlakeIdGenerator (for sequence generation)
- ✅ IMap, ITopic, Jet Pipeline
- ✅ Event Journal
- ✅ Entry Processors
- ✅ Near Cache

#### Optional Enhancements (Enterprise Edition)
Enterprise features MAY be used for optional capabilities that enhance:
- **High Availability**: CP Subsystem for stronger consistency guarantees
- **Resilience**: Hot Restart, Rolling Upgrades
- **Security**: TLS, Authentication, Authorization
- **Performance**: HD Memory
- **AI/ML**: Vector Store (similarity search, recommendations)

**Implementation Rules for Enterprise Features**:
1. **Always Optional**: Code must detect and gracefully handle absence of Enterprise
2. **Feature Flags**: Use configuration to enable/disable Enterprise features
3. **Fallback Required**: Every Enterprise feature must have a Community fallback
4. **Clear Documentation**: Mark Enterprise-only features in JavaDoc

See `com.theyawns.framework.edition.EditionDetector` and `ConditionalOnEnterpriseFeature` / `ConditionalOnCommunityFallback` annotations for the actual implementation pattern.

---

## Critical Architecture Decisions

> **READ THESE BEFORE MAKING CHANGES TO HAZELCAST CONFIGURATION OR CLUSTERING**

### Dual-Instance Hazelcast Architecture (ADR 008)

**THIS IS A SETTLED DECISION. Do not attempt to change to a single-cluster architecture.**

Each microservice runs **TWO** Hazelcast instances:

1. **`hazelcastInstance`** (embedded, standalone, `@Primary`)
   - No cluster join - runs completely isolated
   - Runs Jet pipeline for event sourcing
   - Stores event store, view maps, pending events locally
   - Lambdas never serialize across nodes

2. **`hazelcastClient`** (client to external cluster)
   - Connects to the shared 3-node Hazelcast cluster
   - Used for cross-service ITopic pub/sub (saga events)
   - Used for shared saga state IMap
   - No Jet jobs submitted through this instance

**Why this architecture exists:**

Jet pipeline lambdas reference service-specific classes (e.g., `OrderViewUpdater`). When services join a shared cluster, Jet distributes jobs to ALL members. Members from other services don't have these classes → `ClassCastException: cannot assign instance of java.lang.invoke.SerializedLambda`.

**Alternatives that DO NOT WORK:**

| Approach | Why It Fails |
|----------|--------------|
| Single shared cluster (all services as members) | Jet lambda serialization fails across services |
| Separate cluster per service | Cross-service ITopic/IMap impossible - breaks sagas |
| User Code Deployment | Deprecated, removed in next major version |
| User Code Namespaces | Enterprise Edition only - violates ADR 005 |
| `JobConfig.addClass()` | Spring Boot uber-JARs incompatible, doesn't work with proxies |

**Required configuration pattern:**

```java
@Configuration
public class ServiceConfig {

    @Primary  // REQUIRED - hazelcast-spring needs this
    @Bean
    public HazelcastInstance hazelcastInstance() {
        // Standalone - NO cluster join
        Config config = new Config();
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getAutoDetectionConfig().setEnabled(false);
        config.getJetConfig().setEnabled(true);
        return Hazelcast.newHazelcastInstance(config);
    }

    @Bean(name = "hazelcastClient")
    public HazelcastInstance hazelcastClient() {
        // Client to shared cluster for cross-service communication
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setClusterName("ecommerce-cluster");
        clientConfig.getNetworkConfig().addAddress("hazelcast-1:5701", ...);
        return HazelcastClient.newHazelcastClient(clientConfig);
    }

    @Bean
    public EventSourcingController<...> controller(
            HazelcastInstance hazelcastInstance,  // Embedded for Jet
            @Qualifier("hazelcastClient") HazelcastInstance hazelcastClient,  // For ITopic
            ...) {
        return EventSourcingController.builder()
                .hazelcast(hazelcastInstance)
                .sharedHazelcast(hazelcastClient)  // Republishes events to shared cluster
                .build();
    }
}
```

**Saga listeners must use the client:**

```java
public SagaListener(@Qualifier("hazelcastClient") HazelcastInstance hazelcast) {
    ITopic<GenericRecord> topic = hazelcast.getTopic("EventName");
    topic.addMessageListener(...);
}
```

**Full details:** See `docs/architecture/adr/008-dual-instance-hazelcast-architecture.md`

### Per-Service Embedded Clustering (ADR 013)

**Extension to ADR 008 for Kubernetes multi-replica deployments.**

When `hazelcast.embedded.clustering.enabled=true`, replicas of the **same** service form their own per-service embedded Hazelcast cluster using Kubernetes DNS discovery. Different service types remain isolated. This enables distributed Jet pipeline processing across replicas.

- **Default**: `false` (standalone mode — Docker Compose and local dev unchanged)
- **Enabled in K8s**: via Helm `embeddedClustering.enabled: true` (medium/large tier values)
- **Discovery**: Headless Service per microservice, port 5801
- **Outbox**: Atomic claiming (`CLAIMED` status + `ClaimEntryProcessor`) prevents duplicate delivery
- **Pipeline**: `AT_LEAST_ONCE` guarantee with auto-scaling enabled for Jet rebalancing

**Full details:** See `docs/architecture/adr/013-per-service-embedded-clustering.md`

---

## Code Standards

### Package Structure
**Base package**: `com.theyawns.framework`

**Modules**:
- `com.theyawns.framework.event` - Event abstractions
- `com.theyawns.framework.domain` - Domain object interfaces
- `com.theyawns.framework.store` - Event store
- `com.theyawns.framework.view` - Materialized views
- `com.theyawns.framework.pipeline` - Jet pipeline
- `com.theyawns.framework.controller` - Main controller
- `com.theyawns.framework.config` - Spring configuration

**eCommerce services**:
- `com.theyawns.ecommerce.account.*`
- `com.theyawns.ecommerce.inventory.*`
- `com.theyawns.ecommerce.order.*`
- `com.theyawns.ecommerce.common.*` - Shared

### Naming Conventions
- **Events**: `{Action}{Entity}Event` (e.g., `CustomerCreatedEvent`, `StockReservedEvent`)
- **Domain Objects**: Simple nouns (e.g., `Customer`, `Product`, `Order`)
- **Services**: `{Entity}Service` (e.g., `AccountService`, `InventoryService`)
- **Controllers**: `{Entity}Controller` (e.g., `AccountController`)
- **Tests**: `{ClassName}Test` for unit, `{ClassName}IntegrationTest` for integration

### Code Style
- **Line length**: 120 characters max
- **Indentation**: 4 spaces (no tabs)
- **Braces**: Always use, even for single-line blocks
- **Imports**: No wildcards, organize by: java, javax, org, com
- **Final**: Use `final` for parameters and local variables when possible

---

## Testing Requirements

### Unit Tests (>80% Coverage Required)
- **Location**: `src/test/java` mirroring `src/main/java` structure
- **Framework**: JUnit 5, Mockito
- **Naming**: `{ClassName}Test.java`
- **Structure**:
  ```java
  @DisplayName("ClassName - Description")
  class ClassNameTest {
      
      @BeforeEach
      void setUp() {
          // Test setup
      }
      
      @Test
      @DisplayName("should do something when condition")
      void shouldDoSomethingWhenCondition() {
          // Arrange
          
          // Act
          
          // Assert
      }
  }
  ```

### Integration Tests
- **Location**: `src/test/java` with `IntegrationTest` suffix
- **Framework**: Spring Boot Test, Testcontainers
- **Annotations**:
  ```java
  @SpringBootTest
  @Testcontainers
  class ServiceIntegrationTest {
      @Container
      static GenericContainer<?> hazelcast = 
          new GenericContainer<>("hazelcast/hazelcast:5.6.0")
              .withExposedPorts(5701);
  }
  ```

### Test Principles
- **Arrange-Act-Assert**: Clear three-part structure
- **One assertion per test**: Focus on single behavior
- **Descriptive names**: Test name explains what's being tested
- **No production dependencies**: Use mocks/stubs for external dependencies
- **Fast**: Unit tests should run in milliseconds

---

## Performance Guidelines

### Hazelcast Best Practices
1. **Partition Key**: Always use partition key for IMap operations
   ```java
   // Good: Partitioned by customer ID
   viewMap.executeOnKey(customerId, new UpdateViewEntryProcessor(event));
   
   // Bad: No partition key (broadcasts to all nodes)
   viewMap.executeOnEntries(new UpdateAllProcessor());
   ```

2. **Batch Operations**: Use batch operations when possible
   ```java
   // Good: Batch read
   Map<String, Customer> customers = viewMap.getAll(customerIds);
   
   // Bad: Multiple individual reads
   for (String id : customerIds) {
       Customer c = viewMap.get(id);
   }
   ```

3. **Entry Processor**: Use for atomic updates
   ```java
   viewMap.executeOnKey(key, new UpdateViewEntryProcessor(event));
   ```

### Jet Pipeline Performance
- Keep pipeline stages stateless when possible
- Use `ServiceFactory.sharedService()` for expensive resources
- Avoid blocking operations in pipeline stages
- Use appropriate parallelism settings

---

## Common Pitfalls to Avoid

### ❌ DON'T
1. **Use blocking operations in Jet pipeline**
   ```java
   // BAD
   .map(event -> {
       Thread.sleep(1000); // NEVER block pipeline!
       return event;
   })
   ```

2. **Mutate events after creation**
   ```java
   // BAD
   event.setTimestamp(newTime); // Events should be immutable!
   ```

3. **Catch and swallow exceptions**
   ```java
   // BAD
   try {
       process(event);
   } catch (Exception e) {
       // Silent failure - bad!
   }
   ```

4. **Use synchronous service calls in event handlers**
   ```java
   // BAD - defeats purpose of materialized views!
   Customer customer = restTemplate.getForObject(accountServiceUrl + customerId, Customer.class);
   ```

5. **Forget to set event metadata**
   ```java
   // BAD
   DomainEvent event = new OrderCreatedEvent(...);
   controller.handleEvent(event); // Missing correlation ID!
   ```

### ✅ DO
1. **Use async operations**
2. **Make events immutable**
3. **Log errors and propagate exceptions**
4. **Use materialized views, not service calls**
5. **Always set correlation IDs**

---

## Code Review Checklist

Before committing, verify:

### Functionality
- [ ] Code compiles without warnings
- [ ] All tests pass
- [ ] New tests written (>80% coverage)
- [ ] Edge cases handled
- [ ] Error handling implemented

### Documentation
- [ ] JavaDoc on all public classes/methods
- [ ] Inline comments for complex logic
- [ ] README updated if needed
- [ ] API documentation updated

### Code Quality
- [ ] No code duplication
- [ ] Methods are focused (single responsibility)
- [ ] No magic numbers or strings (use constants)
- [ ] Consistent naming conventions
- [ ] Proper exception handling

### Hazelcast Specific
- [ ] Works with Community Edition (default config)
- [ ] Enterprise features are optional with graceful fallback
- [ ] Partition keys used correctly
- [ ] Event journal enabled for pending maps
- [ ] No blocking in pipeline stages

### Testing
- [ ] Unit tests cover happy path
- [ ] Unit tests cover error cases
- [ ] Integration tests for key scenarios
- [ ] Tests use descriptive names
- [ ] No test dependencies on other tests

---

## Git Commit Messages

### Format
```
<type>(<scope>): <subject>

<body>

<footer>
```

### Types
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `test`: Test additions/changes
- `refactor`: Code refactoring
- `style`: Code style changes (formatting)
- `chore`: Build/dependency updates

### Examples
```
feat(framework): implement EventSourcingController with handleEvent method

- Add correlation ID support
- Add saga metadata fields
- Add metrics collection
- Implement CompletableFuture for async responses

Closes #15
```

```
test(account): add integration tests for customer creation flow

- Test happy path
- Test validation errors
- Test event publication
- Test view updates

Coverage now at 85%
```

---

## Project-Specific Agents

Custom agents defined in `.claude/agents/` for this project's domain-specific tasks.

| Agent | File | Use When |
|-------|------|----------|
| **Framework Developer** | `framework-developer.md` | Building core framework components (EventStore, ViewUpdater, Pipeline) |
| **Service Developer** | `service-developer.md` | Building Account, Inventory, Order microservices |
| **Test Writer** | `test-writer.md` | Writing unit and integration tests |
| **Documentation Writer** | `documentation-writer.md` | Writing JavaDoc, README, API guides |
| **Pipeline Specialist** | `pipeline-specialist.md` | Working with Hazelcast Jet pipelines |
| **Config Manager** | `config-manager.md` | Managing Spring/Hazelcast configuration |
| **Performance Optimizer** | `performance-optimizer.md` | Optimizing for performance targets |
| **Debugging Helper** | `debugging-helper.md` | Troubleshooting issues and flaky tests |

### Task Routing

| Task Type | Recommended Agent |
|-----------|-------------------|
| Implement EventStore, ViewStore | Framework Developer |
| Create AccountService, InventoryService | Service Developer |
| Write pipeline tests | Test Writer |
| Update README, add JavaDoc | Documentation Writer |
| Build/modify Jet pipeline | Pipeline Specialist |
| Setup Hazelcast config | Config Manager |
| Improve query performance | Performance Optimizer |
| Fix flaky test, debug issue | Debugging Helper |

### Multi-Agent Workflows

**New Framework Component:**
1. Framework Developer → Define interface + implementation
2. Test Writer → Write comprehensive tests
3. Documentation Writer → JavaDoc + usage guide
4. Performance Optimizer → Verify performance

**New Microservice:**
1. Service Developer → Implement service + controllers
2. Test Writer → Unit + integration tests
3. Config Manager → Service configuration
4. Documentation Writer → API docs + README

**New Feature:**
1. Service Developer → Implement feature
2. Pipeline Specialist → Update pipeline if needed
3. Test Writer → Add tests
4. Performance Optimizer → Verify performance
5. Documentation Writer → Update docs

### Invoking Project Agents

Reference the agent context file when starting a task:

```
Using the service-developer agent (.claude/agents/service-developer.md),
implement the InventoryService with:
- Product domain object
- StockReservedEvent, StockReleasedEvent
- REST endpoints for stock management
```

---

## Environment Constraints

### Java / Build Environment
- **Development runs on Java 25.** Mockito's inline mock maker **cannot mock concrete classes** on Java 25. Always mock interfaces; if a concrete class needs mocking, extract an interface first (e.g., `ServiceClient` → `ServiceClientOperations`).
- All framework modules must be installed to the local Maven repo (`mvn install -pl framework-core`) before running dependent module tests, otherwise BOM imports and transitive dependencies won't resolve.
- When running the full test suite, use `mvn clean test` from the project root. For individual module tests after changes, install the changed module first: `mvn install -pl <module> -DskipTests && mvn test -pl <dependent-module>`.

### Shell Scripts
- **Target macOS bash 3.2** (`/bin/bash`). Avoid bash 4+ features:
  - No associative arrays (`declare -A`)
  - No `${!var}` indirect expansion
  - No `mapfile` / `readarray`
  - No `&>>` (use `>> file 2>&1` instead)
- Use POSIX-compatible alternatives: `key:value` string loops with `${var%%:*}` / `${var##*:}` parameter expansion.

### Spring Boot Conventions
- When creating Spring beans with multiple constructors, always annotate the intended constructor with `@Autowired` to avoid ambiguous constructor errors.
- `@Scheduled` annotations must use numeric milliseconds (e.g., `fixedDelay = 10000`), **not** duration strings like `"10s"`.
- `@ConditionalOnBean` works with `ApplicationContextRunner` + user config providing the bean.

### Kubernetes / Helm
- When embedding JSON containing `{{ }}` syntax (e.g., Grafana dashboards, Prometheus queries) inside Helm templates, **never inline them directly**. Use `.Files.Get` to load from external files to avoid template rendering conflicts.
- Service memory limits must be at least 1Gi for microservices running embedded Hazelcast + Jet (512Mi causes OOMKill under load).

---

## Architecture & Design

- Architectural decisions that have been debated or revisited are recorded as ADRs under `docs/architecture/adr/`. **Once an ADR is accepted, do not revisit the decision without explicit user direction.**
- Key settled ADRs:
  - **ADR 008**: Dual-instance Hazelcast architecture (embedded standalone + shared cluster client)
  - **ADR 010**: Single-replica scaling strategy (superseded by ADR 013 for Kubernetes)
  - **ADR 013**: Per-service embedded clustering (same-service replicas form per-service Hazelcast cluster)
  - **ADR 005**: Community Edition default with Enterprise opt-in
  - **ADR 009**: Flexible edition configuration via `EditionDetector`

---

## Testing & Build

- After implementation, always run the full test suite (`mvn clean test`) from the project root before committing.
- Before committing, verify that API request payloads in demo/test scripts match the actual controller contract (e.g., include all required fields like `unitPrice` in order line items). Check that metric names used in dashboards actually match the registered Micrometer metric names.
- When writing tests that assume Community Edition, set `framework.edition.license.env-var=NONEXISTENT_TEST_LICENSE_VAR_12345` to avoid picking up the real `HZ_LICENSEKEY` set in the dev environment.

---

## Getting Help

If uncertain about:
- **Architecture decisions**: Refer to `docs/architecture/adr/`
- **Requirements**: Refer to `docs/requirements/`
- **Design patterns**: Refer to `docs/design/`
- **Hazelcast APIs**: Check Hazelcast 5.6 documentation
- **Spring Boot**: Check Spring Boot 3.2 documentation

---

Last updated: 2026-02-15
Version: 1.4
