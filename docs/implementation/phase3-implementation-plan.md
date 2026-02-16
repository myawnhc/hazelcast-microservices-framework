# Phase 3 Implementation Plan: Production Hardening

**Status**: Planning
**Estimated Duration**: 26 days (6 work areas + review)
**Prerequisites**: Phase 2 complete (1,311 tests passing, 7 modules)

---

## Overview

Phase 3 hardens the framework for production use. Each work area builds on the previous — resilience makes sagas more robust, the orchestrator offers an alternative saga pattern, the gateway provides a single entry point, Kubernetes packages everything for deployment, security locks it down, and PostgreSQL adds durability.

### Work Areas (Agreed Ordering)

| # | Area | Days | New Module(s) | Key Dependencies |
|---|------|------|---------------|-----------------|
| 1 | Resilience Patterns | 1-5 | — | Resilience4j |
| 2 | Orchestrated Sagas | 6-10 | — | Builds on SagaStateStore |
| 3 | API Gateway | 11-14 | `api-gateway` | Spring Cloud Gateway |
| 4 | Kubernetes Deployment | 15-18 | — | Helm 3 |
| 5 | Security | 19-22 | — | Spring Security, OAuth2 |
| 6 | PostgreSQL Event Store | 23-25 | — | Spring Data JPA, PostgreSQL |
| — | Phase 3 Review | 26 | — | — |

---

## Area 1: Resilience Patterns (Days 1-5)

**Goal**: Make service-to-service communication resilient to transient failures.

**Context**: Saga listeners currently make direct REST calls and ITopic publishes with no retry or fallback. A network blip or brief service restart causes the saga to fail permanently.

### Day 1: Resilience4j Foundation

**Tasks**:
1. Add Resilience4j BOM and dependencies to root POM
2. Create `com.theyawns.framework.resilience` package in framework-core
3. Implement `ResilientServiceInvoker` — wraps calls with circuit breaker + retry
4. Create `ResilienceProperties` for Spring Boot configuration
5. Create `ResilienceAutoConfiguration` to register beans
6. Unit tests for configuration binding

**Dependencies to add**:
```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-retry</artifactId>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-micrometer</artifactId>
</dependency>
```

**Configuration pattern**:
```yaml
framework:
  resilience:
    circuit-breaker:
      failure-rate-threshold: 50
      wait-duration-in-open-state: 10s
      sliding-window-size: 10
    retry:
      max-attempts: 3
      wait-duration: 500ms
      exponential-backoff-multiplier: 2.0
```

**Deliverables**:
- [ ] Resilience4j in root POM
- [ ] `ResilientServiceInvoker` abstraction
- [ ] Configuration properties and auto-config
- [ ] Unit tests

---

### Day 2: Circuit Breaker for Saga Listeners

**Tasks**:
1. Wrap saga listener REST calls with circuit breaker
2. Add circuit breaker to `InventorySagaListener` (stock reserve/release)
3. Add circuit breaker to `PaymentSagaListener` (payment processing)
4. Add circuit breaker to `OrderSagaListener` (order confirmation/cancellation)
5. Add fallback behavior — when circuit is open, record saga step as PENDING_RETRY
6. Unit tests for circuit breaker behavior

**Pattern**:
```java
@CircuitBreaker(name = "inventory-service", fallbackMethod = "reserveStockFallback")
public void reserveStock(GenericRecord event) {
    // existing stock reservation logic
}

private void reserveStockFallback(GenericRecord event, Throwable t) {
    logger.warn("Circuit open for inventory-service, queuing retry: {}", t.getMessage());
    sagaStateStore.recordStepPendingRetry(sagaId, "StockReservation");
}
```

**Deliverables**:
- [ ] Circuit breakers on all 3 saga listeners
- [ ] Fallback methods with pending retry recording
- [ ] Unit tests for open/closed/half-open states

---

### Day 3: Retry with Exponential Backoff

**Tasks**:
1. Add `@Retry` to saga listener methods alongside circuit breaker
2. Configure exponential backoff (500ms → 1s → 2s, max 3 attempts)
3. Implement retry event logging (track retry count in saga state)
4. Add `RetryEventListener` to publish metrics on retry attempts
5. Ensure retry does not trigger for non-retryable failures (e.g., validation errors)
6. Unit tests for retry behavior and backoff timing

**Non-retryable exception handling**:
```java
// Payment declined is NOT retryable
if (e instanceof PaymentDeclinedException) {
    throw e;  // skip retry, let circuit breaker handle
}
// Connection refused IS retryable
if (e instanceof RestClientException) {
    throw e;  // retry with backoff
}
```

**Deliverables**:
- [ ] Retry annotations on saga listeners
- [ ] Exponential backoff configuration
- [ ] Non-retryable exception classification
- [ ] Retry metrics integration
- [ ] Unit tests

---

### Day 4: Outbox Pattern

**Tasks**:
1. Create `OutboxEntry` domain object (eventId, destination, payload, status, createdAt)
2. Create `OutboxStore` interface backed by Hazelcast IMap
3. Create `OutboxPublisher` scheduled task — polls outbox, publishes to ITopic, marks delivered
4. Modify `EventSourcingController` to write to outbox instead of direct ITopic publish
5. Add idempotency check on consumer side (deduplicate by eventId)
6. Unit tests for outbox store and publisher

**Why outbox with Hazelcast (not PostgreSQL)?**: The outbox pattern normally uses a DB transaction to atomically write the event + outbox entry. Since we're in-memory with Hazelcast, we use IMap transactions (`TransactionContext`) to atomically write to both the event store IMap and the outbox IMap. This gives us the outbox guarantee without requiring a relational DB.

**Deliverables**:
- [ ] `OutboxStore` with Hazelcast IMap backing
- [ ] `OutboxPublisher` scheduled task
- [ ] Idempotency filter on consumers
- [ ] `EventSourcingController` modified for outbox writes
- [ ] Unit tests

---

### Day 5: Resilience Testing & Documentation

**Tasks**:
1. Integration tests simulating transient failures (WireMock for service unavailability)
2. Test circuit breaker state transitions (closed → open → half-open → closed)
3. Test retry exhaustion → saga failure recording
4. Test outbox delivery guarantee (publish, crash, restart, redeliver)
5. Add resilience metrics to Grafana dashboard (circuit breaker state, retry count)
6. Update Prometheus alerting rules for circuit breaker open alerts
7. Draft blog post section on resilience patterns
8. Update saga pattern guide with resilience information

**Deliverables**:
- [ ] Integration tests for resilience scenarios
- [ ] Grafana dashboard updates
- [ ] Prometheus alerts for circuit breaker
- [ ] Documentation updates
- [ ] Area 1 complete

---

## Area 2: Orchestrated Sagas (Days 6-10)

**Goal**: Add a saga orchestrator as an alternative to choreography, with a side-by-side comparison demo.

**Context**: Phase 2 implemented choreographed sagas where services react to events independently. An orchestrator provides a centralized coordinator that explicitly directs each step. Both patterns have trade-offs; showing both is educational.

### Day 6: Saga Orchestrator Interfaces

**Tasks**:
1. Create `com.theyawns.framework.saga.orchestrator` package
2. Define `SagaDefinition` — ordered list of steps with compensation actions
3. Define `SagaStep` — action + compensation + timeout + retry policy
4. Define `SagaOrchestrator` interface — `start(sagaId, definition, context)`, `handleStepResult(sagaId, stepName, result)`
5. Define `SagaStepResult` — SUCCESS, FAILURE, TIMEOUT
6. Define `SagaOrchestratorListener` — receives step completion events
7. Unit tests for definition building

**Step definition DSL**:
```java
SagaDefinition orderFulfillment = SagaDefinition.builder()
    .name("OrderFulfillment")
    .step("ReserveStock")
        .action(ctx -> inventoryClient.reserveStock(ctx.get("productId"), ctx.get("quantity")))
        .compensation(ctx -> inventoryClient.releaseStock(ctx.get("productId"), ctx.get("quantity")))
        .timeout(Duration.ofSeconds(10))
        .build()
    .step("ProcessPayment")
        .action(ctx -> paymentClient.processPayment(ctx.get("orderId"), ctx.get("amount")))
        .compensation(ctx -> paymentClient.refundPayment(ctx.get("paymentId")))
        .timeout(Duration.ofSeconds(15))
        .build()
    .step("ConfirmOrder")
        .action(ctx -> orderClient.confirmOrder(ctx.get("orderId")))
        .noCompensation()  // terminal step
        .timeout(Duration.ofSeconds(5))
        .build()
    .build();
```

**Deliverables**:
- [ ] Saga orchestrator interfaces
- [ ] `SagaDefinition` and `SagaStep` model
- [ ] Step definition builder DSL
- [ ] Unit tests

---

### Day 7: Saga Orchestrator Implementation

**Tasks**:
1. Implement `HazelcastSagaOrchestrator` — state machine driven by step results
2. State transitions: PENDING → EXECUTING → step SUCCESS → next step → COMPLETED
3. Failure path: step FAILURE → run compensations in reverse → COMPENSATED
4. Timeout path: step TIMEOUT → run compensations → TIMED_OUT
5. Store orchestrator state in `SagaStateStore` (reuse Phase 2 infrastructure)
6. Publish step commands via ITopic on shared Hazelcast client
7. Listen for step results via ITopic
8. Unit tests for state machine transitions

**State machine**:
```
STARTED → EXECUTING_STEP_1 → STEP_1_COMPLETE → EXECUTING_STEP_2 → ... → COMPLETED
                  │                                    │
                  ▼                                    ▼
          COMPENSATING_STEP_1 ← COMPENSATING_STEP_2 ← STEP_2_FAILED
                  │
                  ▼
             COMPENSATED
```

**Deliverables**:
- [ ] `HazelcastSagaOrchestrator` implementation
- [ ] State machine with forward + compensation paths
- [ ] ITopic command/result messaging
- [ ] Reuse of `SagaStateStore` for state tracking
- [ ] Unit tests for all state transitions

---

### Day 8: Orchestrated Order Fulfillment

**Tasks**:
1. Create `OrderFulfillmentOrchestratedSaga` — defines the 3-step saga
2. Create `OrchestratedSagaController` REST endpoint to trigger orchestrated orders
3. Create step executors in each service that listen for orchestrator commands
4. Wire orchestrator to existing service methods (reuse `handleEvent`)
5. Ensure both orchestrated and choreographed paths can run simultaneously
6. Integration tests for orchestrated happy path

**Endpoint**: `POST /api/orders?mode=orchestrated` (vs default `choreographed`)

**Deliverables**:
- [ ] Orchestrated order fulfillment saga definition
- [ ] REST endpoint with `mode` parameter
- [ ] Step executors in inventory, payment, order services
- [ ] Integration tests

---

### Day 9: Comparison Demo & Metrics

**Tasks**:
1. Add `run_demo` scenario `orchestrated_happy_path` to MCP server
2. Add `run_demo` scenario `orchestrated_payment_failure` to MCP server
3. Add orchestrator metrics (step duration, compensation count, orchestrator throughput)
4. Create Grafana dashboard panel comparing choreography vs orchestration latency
5. Add saga type filter to `list_sagas` MCP tool (choreographed vs orchestrated)
6. Unit tests for new demo scenarios

**Deliverables**:
- [ ] New MCP demo scenarios
- [ ] Orchestrator metrics
- [ ] Grafana comparison panel
- [ ] Updated MCP tools

---

### Day 10: Orchestration Documentation & Blog Post

**Tasks**:
1. Draft blog post 08: "Choreography vs Orchestration: Two Saga Patterns Compared"
2. Update saga pattern guide with orchestration section
3. Update MCP examples with orchestrated saga conversations
4. Update main README
5. Full test suite run

**Blog post structure**:
- When to use choreography vs orchestration
- Side-by-side implementation comparison
- Performance and complexity trade-offs
- Running both simultaneously

**Deliverables**:
- [ ] Blog post 08 draft
- [ ] Updated saga pattern guide
- [ ] Updated MCP examples
- [ ] Area 2 complete

---

## Area 3: API Gateway (Days 11-14)

**Goal**: Add a Spring Cloud Gateway as the single entry point for all services.

### Day 11: Gateway Module Setup

**Tasks**:
1. Create `api-gateway` Maven module with Spring Cloud Gateway dependency
2. Add Spring Cloud BOM to root POM dependency management
3. Configure routes for all services (account, inventory, order, payment, MCP)
4. Add path-prefix rewriting (e.g., `/api/customers` → `account-service:8081/api/customers`)
5. Create `GatewayApplication` Spring Boot main class
6. Add `application.yml` with route definitions
7. Basic smoke test

**Route configuration**:
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: account-service
          uri: http://localhost:8081
          predicates:
            - Path=/api/customers/**
        - id: inventory-service
          uri: http://localhost:8082
          predicates:
            - Path=/api/products/**
        - id: order-service
          uri: http://localhost:8083
          predicates:
            - Path=/api/orders/**,/api/sagas/**,/api/metrics/**
        - id: payment-service
          uri: http://localhost:8084
          predicates:
            - Path=/api/payments/**
```

**Deliverables**:
- [ ] `api-gateway` module with Spring Cloud Gateway
- [ ] Route configuration for all services
- [ ] Basic smoke test

---

### Day 12: Rate Limiting & Request Logging

**Tasks**:
1. Add Hazelcast-backed rate limiting using IMap with sliding-window token bucket
2. Configure rate limits per route (e.g., 100 req/s for reads, 20 req/s for writes)
3. Add request/response logging filter
4. Add correlation ID propagation filter (inject if missing, forward if present)
5. Add request timing filter (log duration, expose as metric)
6. Unit tests for filters

**Deliverables**:
- [ ] Rate limiting filter
- [ ] Logging filter
- [ ] Correlation ID filter
- [ ] Timing filter
- [ ] Unit tests

---

### Day 13: Gateway Error Handling & CORS

**Tasks**:
1. Global error handler for gateway (502, 503, 504 responses)
2. Circuit breaker per route (Resilience4j integration with gateway)
3. CORS configuration for browser-based clients
4. Custom error response format (consistent JSON error body)
5. Health endpoint aggregating downstream service health
6. Unit tests

**Deliverables**:
- [ ] Global error handler
- [ ] Per-route circuit breakers
- [ ] CORS configuration
- [ ] Aggregated health endpoint
- [ ] Unit tests

---

### Day 14: Gateway Docker, Testing & Documentation

**Tasks**:
1. Create Dockerfile for api-gateway
2. Add api-gateway service to Docker Compose (port 8080 — replaces direct management center port)
3. Integration tests (route forwarding, rate limiting, error handling)
4. Create api-gateway/README.md
5. Update main README with gateway architecture diagram
6. Update MCP server to optionally route through gateway

**Deliverables**:
- [ ] Dockerfile
- [ ] Docker Compose updated
- [ ] Integration tests
- [ ] Documentation
- [ ] Area 3 complete

---

## Area 4: Kubernetes Deployment (Days 15-18)

**Goal**: Helm charts for deploying the full stack to Kubernetes, with cloud provider cost estimates for real-world deployment.

### Day 15: Helm Chart Structure & Hazelcast StatefulSet

**Tasks**:
1. Create `k8s/` directory with Helm chart structure
2. Create umbrella chart `hazelcast-microservices` with subcharts
3. Create `hazelcast-cluster` subchart — 3-node StatefulSet with discovery
4. Configure Hazelcast Kubernetes discovery plugin (`hazelcast-kubernetes`)
5. Add PersistentVolumeClaim for Hazelcast data (optional hot restart)
6. Add ConfigMap for hazelcast.yaml
7. Test locally with minikube/kind

**Chart structure**:
```
k8s/
├── hazelcast-microservices/        # Umbrella chart
│   ├── Chart.yaml
│   ├── values.yaml
│   └── charts/
│       ├── hazelcast-cluster/      # Hazelcast StatefulSet
│       ├── account-service/
│       ├── inventory-service/
│       ├── order-service/
│       ├── payment-service/
│       ├── api-gateway/
│       ├── mcp-server/
│       └── monitoring/             # Prometheus + Grafana
```

**Deliverables**:
- [ ] Helm chart structure
- [ ] Hazelcast StatefulSet with K8s discovery
- [ ] ConfigMap and PVC
- [ ] Local K8s validation

---

### Day 16: Microservice Helm Charts

**Tasks**:
1. Create Helm subchart for each microservice (account, inventory, order, payment)
2. Deployment, Service, ConfigMap templates
3. Environment variable injection from values.yaml
4. Liveness and readiness probes (actuator health endpoints)
5. Resource requests/limits
6. Service accounts

**Deliverables**:
- [ ] 4 microservice subcharts
- [ ] Probes, resources, service accounts
- [ ] Parameterized values.yaml

---

### Day 17: Gateway, MCP & Monitoring Charts

**Tasks**:
1. Create api-gateway subchart with Ingress resource
2. Create mcp-server subchart
3. Create monitoring subchart (Prometheus Operator CRDs or standalone)
4. Add Grafana dashboards as ConfigMaps
5. Add ServiceMonitor CRDs for Prometheus scraping
6. Configure Ingress for external access

**Deliverables**:
- [ ] Gateway subchart with Ingress
- [ ] MCP server subchart
- [ ] Monitoring subchart
- [ ] Grafana dashboard ConfigMaps

---

### Day 18: HPA, Cloud Cost Estimates, Testing & Documentation

**Tasks**:
1. Add HorizontalPodAutoscaler for microservices (CPU-based scaling)
2. Add PodDisruptionBudget for Hazelcast (min 2 available)
3. End-to-end test on local K8s cluster (minikube/kind)
4. Create cloud deployment cost estimates (see below)
5. Create k8s/README.md with deployment instructions and cost guide
6. Document helm install/upgrade/rollback commands
7. Update main README

**Cloud Provider Cost Estimates**:

Produce a `docs/guides/cloud-deployment-guide.md` with cost estimates across three tiers for AWS EKS, GCP GKE, and Azure AKS. Each tier defines node sizes, replica counts, and estimated monthly cost:

| Tier | Hazelcast | Services | Gateway | Monitoring | Use Case |
|------|-----------|----------|---------|------------|----------|
| **Demo** | 3x 2 vCPU / 4 GB | 1 replica each | 1 replica | Prometheus + Grafana | Conference demos, blog walkthroughs |
| **Staging** | 3x 4 vCPU / 8 GB | 2 replicas each | 2 replicas | Full stack + alerting | Pre-production testing |
| **Production** | 5x 8 vCPU / 16 GB | 3 replicas + HPA | 3 replicas + HPA | Full stack + PagerDuty | Real workloads |

Estimates should cover: compute (node pools), load balancer, persistent storage, data transfer, and managed K8s control plane fees. Include spot/preemptible instance pricing as a cost reduction option.

**Deliverables**:
- [ ] HPA manifests
- [ ] PDB for Hazelcast
- [ ] Local K8s validation
- [ ] Cloud deployment cost guide (3 providers x 3 tiers)
- [ ] Documentation
- [ ] Area 4 complete

---

## Area 5: Security (Days 19-22)

**Goal**: Add authentication and authorization across all services.

### Day 19: Spring Security & OAuth2 Resource Server

**Tasks**:
1. Add Spring Security and OAuth2 Resource Server dependencies
2. Create `com.theyawns.framework.security` package in framework-core
3. Implement `SecurityAutoConfiguration` for consistent security setup
4. Configure API Gateway as OAuth2 resource server (validate JWT tokens)
5. Add security filter chain — public endpoints (health, info) vs protected (API)
6. Create `SecurityProperties` for configurable issuer URI, audiences
7. Unit tests with mock JWT tokens

**Configuration pattern**:
```yaml
framework:
  security:
    enabled: true
    issuer-uri: https://auth.example.com/realms/ecommerce
    public-paths:
      - /actuator/health
      - /actuator/info
      - /swagger-ui/**
      - /v3/api-docs/**
```

**Deliverables**:
- [ ] Spring Security dependencies
- [ ] Security auto-configuration
- [ ] JWT validation on API Gateway
- [ ] Public vs protected endpoint configuration
- [ ] Unit tests with mock JWT

---

### Day 20: Service-to-Service Authentication

**Tasks**:
1. Implement service-to-service JWT propagation (forward caller's token)
2. Add `ServiceTokenRelay` gateway filter — passes JWT to downstream services
3. For saga ITopic events: embed service identity in event metadata
4. Create `ServiceIdentity` — each service has a name and signing key for internal events
5. Validate service identity on ITopic message receipt
6. Unit tests for token relay and service identity

**Deliverables**:
- [ ] JWT token relay in gateway
- [ ] Service identity for internal events
- [ ] ITopic message authentication
- [ ] Unit tests

---

### Day 21: MCP Server Security & Role-Based Access

**Tasks**:
1. Add API key authentication for MCP server connections
2. Define roles: VIEWER (query tools only), OPERATOR (query + demo), ADMIN (all tools)
3. Add `@PreAuthorize` or tool-level access checks based on role
4. Configure MCP server API key via environment variable
5. Restrict `run_demo` and `submit_event` tools to OPERATOR/ADMIN roles
6. Unit tests for role-based access

**Deliverables**:
- [ ] MCP API key authentication
- [ ] Role-based tool access
- [ ] Tool-level authorization
- [ ] Unit tests

---

### Day 22: Security Testing & Documentation

**Tasks**:
1. Integration tests for authenticated requests (valid/invalid/expired tokens)
2. Test service-to-service auth in saga flow
3. Test MCP server API key validation
4. Create security guide (`docs/guides/security-guide.md`)
5. Update Docker Compose with security environment variables
6. Update main README with security section

**Deliverables**:
- [ ] Integration tests
- [ ] Security guide
- [ ] Docker Compose updates
- [ ] Documentation
- [ ] Area 5 complete

---

## Area 6: Persistent Event Store (Days 23-25)

**Goal**: Add durable event persistence behind a provider-agnostic interface, with PostgreSQL as the first implementation.

**Design principle**: The persistence interface lives in `framework-core` with no database-specific dependencies. The PostgreSQL implementation is the default, but the interface is clean enough that someone could fork the repo and implement MySQL, CockroachDB, or — if Hazelcast resurrects tiered storage — a Hazelcast-native persistence layer.

### Day 23: Persistence Interface & PostgreSQL Implementation

**Architecture decision**: Write-behind MapStore (see ADR 012). Hazelcast's native MapStore/MapLoader handles async batching, retry, and cold-start loading. No custom dual-write or replay code needed.

**Tasks**:
1. Create `com.theyawns.framework.store.persistence` package in framework-core
2. Define `EventStorePersistence` interface:
   - `persist(PersistableEvent event)` — store a single event
   - `persistBatch(List<PersistableEvent> events)` — store a batch (used by MapStore.storeAll)
   - `loadEvent(String eventId)` — load a single event by key (used by MapLoader.load)
   - `loadEvents(String aggregateId)` — load events for an aggregate (ordered by sequence)
   - `loadEvents(String aggregateId, long afterSequence)` — load events after a sequence number
   - `loadAllKeys()` — return all event keys (used by MapLoader.loadAllKeys)
   - `isAvailable()` — health check
   - `getProviderName()` — "PostgreSQL", "MySQL", etc.
3. Define `PersistableEvent` record (eventId, aggregateId, aggregateType, eventType, payload as byte[], correlationId, sagaId, sequence, timestamp)
4. Define `PersistenceProperties` — provider-agnostic config (enabled, write-delay, batch-size, coalescing, initial-load-mode)
5. Add Spring Data JPA and PostgreSQL driver dependencies
6. Create `PostgresEventStorePersistence` implementing the interface
7. Create `EventEntity` JPA entity + `EventRepository`
8. Create Flyway migration for event store schema
9. Create `PersistenceAutoConfiguration` with `@ConditionalOnProperty("framework.persistence.enabled")`
10. Add PostgreSQL container to Docker Compose
11. Unit tests for interface contract and JPA mapping

**Interface**:
```java
public interface EventStorePersistence {

    void persist(PersistableEvent event);

    void persistBatch(List<PersistableEvent> events);

    Optional<PersistableEvent> loadEvent(String eventId);

    List<PersistableEvent> loadEvents(String aggregateId);

    List<PersistableEvent> loadEvents(String aggregateId, long afterSequence);

    Iterable<String> loadAllKeys();

    boolean isAvailable();

    String getProviderName();  // "PostgreSQL", "MySQL", "Hazelcast Tiered Storage", etc.
}
```

**Record**:
```java
public record PersistableEvent(
    String eventId,
    String aggregateId,
    String aggregateType,
    String eventType,
    byte[] payload,       // serialized GenericRecord or JSON
    String correlationId,
    String sagaId,
    long sequence,
    Instant timestamp
) {}
```

**Schema** (PostgreSQL-specific, in Flyway migration):
```sql
CREATE TABLE domain_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id VARCHAR(255) NOT NULL UNIQUE,
    aggregate_id VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    correlation_id VARCHAR(255),
    saga_id VARCHAR(255),
    sequence BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE (aggregate_id, sequence)
);

CREATE INDEX idx_events_aggregate ON domain_events(aggregate_id, sequence);
CREATE INDEX idx_events_event_id ON domain_events(event_id);
CREATE INDEX idx_events_type ON domain_events(event_type);
CREATE INDEX idx_events_saga ON domain_events(saga_id);
CREATE INDEX idx_events_created ON domain_events(created_at);
```

**Deliverables**:
- [ ] `EventStorePersistence` interface in framework-core (no DB dependencies)
- [ ] `PersistableEvent` record in framework-core
- [ ] `PostgresEventStorePersistence` implementation
- [ ] JPA entities, repository, Flyway migration
- [ ] Auto-configuration with `@ConditionalOnProperty`
- [ ] PostgreSQL in Docker Compose
- [ ] Unit tests

---

### Day 24: Write-Behind MapStore Integration

**Architecture**: See ADR 012. MapStore replaces the previously planned `DurableEventStore` wrapper and `EventReplayService`. Hazelcast handles async write batching, retry, and cold-start cache loading natively.

**Tasks**:
1. Create `EventStoreMapStore implements MapStore<String, GenericRecord>, MapLoader<String, GenericRecord>` — delegates to `EventStorePersistence`, converts between `GenericRecord` and `PersistableEvent`
2. Create `ViewStoreMapStore implements MapStore<String, GenericRecord>, MapLoader<String, GenericRecord>` — similar pattern for materialized view maps
3. Create `MapStoreAutoConfiguration` — programmatically configures `MapStoreConfig` on `_ES` and `_VIEW` IMaps when persistence is enabled
4. Configure write-behind settings: `writeDelaySeconds=5`, `writeBatchSize=100`, `writeCoalescing=false` for event stores, `writeCoalescing=true` for views
5. Configure MapLoader: lazy loading for event stores (events loaded on-demand), eager loading for view maps (views loaded at startup for REST API readiness)
6. Add IMap eviction policies: `max-size` or `max-idle-seconds` on `_ES` maps (evicted entries reloadable from PostgreSQL via MapLoader)
7. When persistence is disabled (`framework.persistence.enabled=false`), no MapStore is configured — IMaps behave exactly as today
8. Unit tests for MapStore conversion logic (mock the `EventStorePersistence` interface)

**Configuration**:
```yaml
framework:
  persistence:
    enabled: true                    # false = Hazelcast-only (Phase 1/2 behavior)
    provider: postgresql             # informational; auto-detected from classpath
    write-delay-seconds: 5           # write-behind flush interval
    write-batch-size: 100            # max entries per batch flush
    write-coalescing: false          # false for event stores (append-only, unique keys)
    view-write-coalescing: true      # true for views (only latest state persisted)
    initial-load-mode: LAZY          # LAZY = load on demand; EAGER = load all at startup
    view-initial-load-mode: EAGER    # views loaded at startup for REST API readiness
```

**MapStore architecture**:
```
EventStorePersistence (interface)     ← provider-agnostic
    │
    ▼
PostgresEventStorePersistence         ← PostgreSQL implementation
    │
    ▼
EventStoreMapStore                    ← thin Hazelcast adapter
    │                                    (GenericRecord ↔ PersistableEvent conversion)
    ▼
IMap "{Domain}_ES"                    ← write-behind, coalescing=false
    MapStoreConfig:
      writeDelaySeconds: 5
      writeBatchSize: 100
      writeCoalescing: false
      initialLoadMode: LAZY
```

**Extensibility note**: Because `EventStoreMapStore` depends on the `EventStorePersistence` interface (not `PostgresEventStorePersistence`), swapping PostgreSQL for another provider requires only:
1. Implement `EventStorePersistence`
2. Register the bean (e.g., via auto-configuration)
3. No changes to MapStore, pipeline, or service code

**Deliverables**:
- [ ] `EventStoreMapStore` (write-behind for event store IMaps)
- [ ] `ViewStoreMapStore` (write-behind for view IMaps)
- [ ] `MapStoreAutoConfiguration` (programmatic MapStoreConfig)
- [ ] IMap eviction configuration
- [ ] Configuration properties
- [ ] Unit tests (persistence interface mocked)

---

### Day 25: Persistence Testing & Documentation

**Tasks**:
1. Integration tests with Testcontainers PostgreSQL
2. Test crash recovery: write events → kill Hazelcast → restart → verify MapLoader rebuilds state
3. Test write-behind batching: verify `storeAll()` receives batched entries
4. Test write-behind failure handling: simulate PostgreSQL outage → verify retry
5. Test `EventStorePersistence` contract with a simple in-memory implementation (proves the interface works independently of PostgreSQL)
6. Test MapLoader cold-start: eager load for views, lazy load for events
7. Add persistence metrics (write-behind queue depth, batch flush latency, MapLoader load count)
8. Update docker-compose with PostgreSQL service
9. Create persistence guide (`docs/guides/persistence-guide.md`) including sections on:
   - Write-behind configuration tuning
   - Custom provider implementation
   - Cold-start behavior (eager vs lazy loading)
   - Eviction and memory management
10. Update main README
11. Draft blog post section on durable event sourcing with write-behind MapStore

**Deliverables**:
- [ ] Testcontainers integration tests
- [ ] Crash recovery test (MapLoader rebuild)
- [ ] In-memory `EventStorePersistence` for contract testing
- [ ] Persistence metrics
- [ ] Documentation (including custom provider guide)
- [ ] Area 6 complete

---

## Day 26: Phase 3 Review & Handoff

**Tasks**:
1. Run full test suite across all modules
2. Verify all deliverables against checklists
3. Create PHASE3-COMPLETE.md
4. Update blog series numbering if new posts added
5. Final README review

**Deliverables**:
- [ ] All tests pass
- [ ] Documentation complete
- [ ] PHASE3-COMPLETE.md created
- [ ] Phase 3 COMPLETE

---

## Success Criteria

### Area 1 Complete When:
- [ ] Circuit breakers on all saga listeners
- [ ] Retry with exponential backoff working
- [ ] Outbox pattern ensuring delivery
- [ ] Resilience metrics in Grafana

### Area 2 Complete When:
- [ ] Orchestrated saga runs end-to-end
- [ ] Comparison demo works (choreography vs orchestration)
- [ ] Blog post 08 drafted
- [ ] MCP demo scenarios updated

### Area 3 Complete When:
- [ ] All API requests routable through gateway
- [ ] Rate limiting functional
- [ ] Gateway in Docker Compose
- [ ] Correlation ID propagation working

### Area 4 Complete When:
- [ ] Full stack deploys to local K8s cluster
- [ ] Hazelcast discovers members via K8s API
- [ ] HPA scales microservices
- [ ] Cloud cost estimates for 3 providers x 3 tiers
- [ ] Helm install/upgrade documented

### Area 5 Complete When:
- [ ] JWT validation on all protected endpoints
- [ ] Service-to-service auth in saga flow
- [ ] MCP server API key authentication
- [ ] Security guide published

### Area 6 Complete When:
- [ ] `EventStorePersistence` interface defined (provider-agnostic)
- [ ] PostgreSQL implementation working (persist + load)
- [ ] In-memory implementation for contract testing
- [ ] Write-behind MapStore configured on `_ES` and `_VIEW` IMaps (ADR 012)
- [ ] MapLoader rebuilds Hazelcast state on startup (eager for views, lazy for events)
- [ ] IMap eviction policies configured (bounded hot cache)
- [ ] Crash recovery verified (MapLoader rebuild from PostgreSQL)
- [ ] Persistence guide published (including custom provider instructions)

---

## Phase 3 Preview → Phase 4

With Phase 3 complete, potential Phase 4 topics:
- Event versioning and schema evolution (Avro/Protobuf)
- CQRS read database (Elasticsearch for full-text search)
- Hazelcast tiered storage `EventStorePersistence` provider (if/when available)
- Multi-region deployment
- Canary deployments and feature flags
- GraphQL API
- Admin UI (React dashboard for saga monitoring)

---

*Created: 2026-02-11*
*Framework Version: 3.0.0 (target)*
