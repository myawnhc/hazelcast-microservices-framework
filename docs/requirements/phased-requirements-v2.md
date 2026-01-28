# Hazelcast Microservices Framework
## Phased Requirements Document v2.0

**Last Updated**: 2026-01-28
**Status**: Phase 1 Complete, Phase 2 Planning

---

## Document Overview

This document defines the phased development plan for the Hazelcast Microservices Framework. It replaces the previous multi-iteration requirements document with a clean, consistent structure.

**Document History**:
- v1.0: Initial requirements (superseded)
- v2.0: Streamlined after Phase 1 completion

---

## Project Vision

Build an **educational, production-quality microservices framework** demonstrating event sourcing, materialized views, and distributed patterns using Hazelcast.

### Core Principles

1. **Event Sourcing First**: Events are the source of truth
2. **Domain-Agnostic**: Framework works for any domain
3. **Educational Quality**: Code suitable for blog posts and demos
4. **Open Source Default**: Hazelcast Community Edition; Enterprise features optional
5. **Laptop-Runnable**: Full demo runs via `docker-compose up`

---

## Phase Summary

| Phase | Focus | Status | Duration |
|-------|-------|--------|----------|
| Phase 1 | Core Framework & Event Sourcing | **COMPLETE** | Days 1-15 |
| Phase 2 | Sagas, Vector Store, Dashboards | **PLANNED** | TBD |
| Phase 3 | Production Hardening | FUTURE | TBD |

---

# Phase 1: Core Framework & Event Sourcing (COMPLETE)

**Status**: Complete as of 2026-01-27
**Duration**: 15 days

## What Was Built

### Architecture

- **6-Stage Hazelcast Jet Pipeline**: Source → Enrich → Persist → Update View → Publish → Complete
- **Event Sourcing**: All state changes captured as immutable events
- **Materialized Views**: Pre-computed, denormalized views for fast queries
- **Cross-Service Data**: Local views instead of service-to-service calls

### Modules

| Module | Purpose |
|--------|---------|
| `framework-core` | Domain-agnostic event sourcing infrastructure |
| `ecommerce-common` | Shared domain objects and events |
| `account-service` | Customer management (port 8081) |
| `inventory-service` | Product and stock management (port 8082) |
| `order-service` | Order processing (port 8083) |

### Key Components

- `DomainEvent<D, K>` - Base class with saga metadata support
- `HazelcastEventStore` - Append-only event persistence
- `HazelcastViewStore` - Materialized view management
- `ViewUpdater<K>` - Abstract view update logic
- `EventSourcingPipeline` - Jet pipeline for event processing
- `EventSourcingController` - Main entry point for event handling
- `HazelcastEventBus` - Pub/sub for cross-service events

### Materialized Views

| View | Purpose |
|------|---------|
| `customer-view` | Customer profile data |
| `product-view` | Product catalog data |
| `order-view` | Basic order data |
| `enriched-order-view` | Order + customer name + product names (denormalized) |
| `product-availability-view` | Real-time stock levels |
| `customer-order-summary-view` | Aggregated stats per customer |

### Performance Achieved

| Metric | Target | Achieved |
|--------|--------|----------|
| Throughput | 10,000+ TPS | 100,000+ TPS |
| P50 Latency | < 10ms | < 0.3ms |
| P99 Latency | < 50ms | < 1ms |

### Test Results

- **Total Tests**: 1,354
- **Passed**: 1,354
- **Coverage**: >80%

### Documentation Produced

- Architecture Decision Records (ADRs 001-006)
- OpenAPI specifications for all services
- Setup guide and demo walkthrough
- 3 blog posts with code examples

## Phase 1 Limitations (Addressed in Phase 2+)

1. **In-memory only**: Events not persisted to external database
2. **No saga orchestration**: Metadata captured but sagas not automated
3. **No dashboards**: Metrics exposed but no visual UI
4. **No distributed tracing**: Correlation IDs present but no Jaeger
5. **No authentication**: APIs open

---

# Phase 2: Sagas, Vector Store, Dashboards (PLANNED)

**Status**: Planning
**Focus**: Distributed transaction patterns, AI-powered features, observability

## 2.1 Saga Pattern Implementation

### Goal

Implement choreographed sagas for distributed transactions with automatic compensation on failure.

### Approach: Choreography with Orchestration Foundation

**Phase 2**: Implement choreographed sagas (services react to events independently)
**Future**: Foundation in place for orchestrated sagas when complexity grows

**Why Choreography First**:
- Simpler for 3-4 service scenarios
- Services maintain autonomy over their compensation logic
- Less infrastructure (no central coordinator)
- Easier to understand and demonstrate

**Foundation for Future Orchestration**:
- Saga state tracking infrastructure (tracks saga progress centrally)
- Clear event contracts (success/failure pairs)
- Saga metadata in events (`sagaId`, `stepNumber`, `isCompensating`)
- Idempotent event handlers
- Correlation ID propagation

This foundation allows adding an orchestrator service in Phase 3+ when:
- Flows become more complex (conditional branching)
- Need centralized saga visibility
- Cross-team coordination challenges emerge

### Scope

#### Payment Service (New, 4th Service)

Add a Payment Service to enable realistic saga scenarios:

```
Payment Service responsibilities:
- Process payments (authorize, capture, refund)
- Validate payment methods
- Track payment state
- Publish: PaymentProcessed, PaymentFailed, PaymentRefunded
```

**Port**: 8084

#### Order Fulfillment Saga (Choreographed)

```
Happy Path:
1. Order Service → Create order (PENDING)
2. Publish: OrderCreated
3. Inventory Service → Reserve stock
4. Publish: StockReserved
5. Payment Service → Process payment
6. Publish: PaymentProcessed
7. Order Service → Confirm order
8. Publish: OrderConfirmed

Compensation Path (Payment Fails):
1. Payment Service → Payment fails
2. Publish: PaymentFailed
3. Inventory Service → Release reserved stock
4. Publish: StockReleased (compensating)
5. Order Service → Cancel order
6. Publish: OrderCancelled (compensating)
```

#### Saga Features

- **Saga State Tracking**: Track saga instances and their progress
- **Timeout Handling**: Detect and handle stuck sagas
- **Idempotent Handlers**: Handle duplicate events gracefully
- **Compensating Events**: Rollback on failure

#### Phase 1 Foundation Already Present

- Correlation IDs for saga tracking
- Saga metadata fields in `DomainEvent` (`sagaId`, `sagaType`, `stepNumber`, `isCompensating`)
- Event-driven communication via Topics
- Success/failure event patterns

#### Orchestration Foundation (Phase 2)

Build infrastructure that enables future orchestrated sagas:

| Component | Purpose | Enables Orchestration |
|-----------|---------|----------------------|
| `SagaStateStore` | Track saga instances and their current state | Orchestrator can query saga state |
| `SagaEvent` interface | Standardized saga event contract | Orchestrator can send/receive |
| Step tracking | Record which steps completed/failed | Orchestrator can resume |
| Timeout detection | Detect stuck sagas | Orchestrator can intervene |
| Compensation registry | Map of step → compensation action | Orchestrator can trigger rollback |

**Implementation**:
```java
// SagaStateStore - tracks all saga instances
public interface SagaStateStore {
    void startSaga(String sagaId, String sagaType);
    void recordStep(String sagaId, int step, String eventType, StepStatus status);
    void completeSaga(String sagaId, SagaOutcome outcome);
    SagaState getSagaState(String sagaId);
    List<SagaState> findStalledSagas(Duration threshold);
}

// In Phase 2: Services update state via events
// In Phase 3+: Orchestrator can use this to drive sagas
```

This infrastructure supports choreography now while enabling orchestration later.

### Deliverables

- [ ] Payment Service implementation
- [ ] Choreographed saga for order fulfillment
- [ ] `SagaStateStore` interface and implementation
- [ ] Saga timeout detection
- [ ] Demo scenarios (happy path, payment failure, timeout)
- [ ] Dashboard showing saga progress

---

## 2.2 Hazelcast Vector Store Integration

### Goal

Demonstrate Hazelcast Vector Store for AI-powered features (optional, Enterprise feature).

### Note on Enterprise Features

Vector Store requires Hazelcast Enterprise. Implementation must:
- Be **optional** (framework works without it)
- Gracefully degrade when unavailable
- Be clearly documented as Enterprise-only

### Use Case: Product Recommendations

```
Concept:
- Generate product embeddings from descriptions
- Store embeddings in Vector Store
- Query for similar products (k-nearest neighbors)
- Show "Similar Products" on product page
```

#### Implementation Approach

1. **Embedding Generation**
   - Use open-source embedding model (e.g., sentence-transformers)
   - Generate embeddings for product descriptions
   - Store embeddings in Hazelcast Vector Store

2. **Similarity Search**
   - Query Vector Store for k-nearest neighbors
   - Return similar products based on vector distance

3. **API Endpoints**
   ```
   GET /api/products/{id}/similar?limit=5
   Response: [list of similar products]
   ```

#### Alternative Use Cases (If Product Recommendations Not Suitable)

- **Fraud Detection**: Embed order event sequences, detect anomalous patterns
- **Semantic Search**: Natural language search over order history
- **Customer Segmentation**: Cluster customers by behavior vectors

### Deliverables

- [ ] Vector Store integration (conditional on Enterprise)
- [ ] Product embedding generation
- [ ] Similarity search API
- [ ] Feature flag for enable/disable
- [ ] Documentation noting Enterprise requirement

---

## 2.3 Visual Dashboards

### Goal

Provide real-time visibility into system behavior for demos and troubleshooting.

### Scope Options

#### Option A: Grafana Dashboards (Recommended Start)

Leverage existing Prometheus metrics with pre-built Grafana dashboards.

**Dashboards**:
1. **System Overview**
   - Service health indicators
   - Hazelcast cluster status
   - Overall TPS and latency

2. **Transaction Monitoring**
   - Transactions per second (TPS)
   - In-flight transaction count
   - Transaction latency distribution

3. **Event Flow**
   - Events published per service
   - Event processing lag
   - Pipeline stage latencies

4. **Materialized View Health**
   - View entry counts
   - View update rates
   - View staleness

5. **Saga Dashboard** (New for Phase 2)
   - Active saga count
   - Saga completion rate
   - Failed/compensating sagas
   - Saga duration histogram

**Deliverables**:
- [ ] Grafana dashboard JSON files
- [ ] Docker Compose with Grafana
- [ ] Documentation for dashboard setup

#### Option B: Custom React Dashboard (Higher Investment)

Build a custom interactive UI with real-time updates.

**Features**:
- WebSocket for live updates
- Interactive transaction flow visualization
- Click-through to transaction details
- Saga state machine visualization

**Effort**: Significantly higher than Option A

**Recommendation**: Start with Option A (Grafana). Consider Option B for Phase 3 if Grafana proves insufficient for demos.

---

## 2.4 Additional Phase 2 Features

### Distributed Tracing

- OpenTelemetry integration
- Jaeger for trace visualization
- Trace events across services

### Enhanced Metrics

- Transaction success/failure rates
- Saga-specific metrics
- Vector search latency (if enabled)

### PostgreSQL Event Store (Optional)

- Persist events to PostgreSQL via MapStore
- Event replay from database
- Hybrid: Hazelcast for speed, PostgreSQL for durability

---

## Phase 2 Success Criteria

| Criterion | Verification |
|-----------|--------------|
| Order fulfillment saga works end-to-end | Demo scenario passes |
| Saga compensates on payment failure | Demo scenario passes |
| Saga timeout detection works | Stuck saga triggers alert |
| Grafana dashboards show real-time data | Visual verification |
| Saga progress visible in dashboard | Visual verification |
| Vector Store recommendations work (if enabled) | Similar products returned |
| All existing tests pass | CI/CD verification |
| New tests for Phase 2 features | >80% coverage |

---

# Phase 3: Production Hardening (FUTURE)

**Status**: Future Planning
**Focus**: Enterprise readiness, resilience patterns, Kubernetes

## Planned Features

### Resilience Patterns

- **Circuit Breaker**: Resilience4j integration for graceful degradation
- **Retry Pattern**: Exponential backoff for transient failures
- **Outbox Pattern**: Transactional event publishing

### API Gateway

- Spring Cloud Gateway for request aggregation
- Rate limiting
- Authentication/authorization

### Orchestrated Sagas (Builds on Phase 2 Foundation)

With Phase 2's `SagaStateStore` and infrastructure in place, add:

- **Saga Orchestrator Service**: Centralized coordinator that drives saga steps
- **Saga Definition DSL**: Define saga flows declaratively
- **Comparison Demo**: Same order fulfillment, orchestrated vs choreographed
- **Visual Saga Flow Editor**: Define sagas via UI (stretch goal)

### MCP Server Integration

- Expose framework capabilities via MCP
- Allow AI assistants to interact with demo
- Tools: query views, submit events, inspect sagas

### Kubernetes Deployment

- Helm charts for all services
- Horizontal pod autoscaling
- Hazelcast cluster on Kubernetes

### Security

- OAuth2/OIDC integration
- Service-to-service mTLS
- Secrets management (Vault)

---

# Technical Standards

## Open Source Compliance

All core features must work with open source software:

| Component | Open Source Option | Enterprise Option |
|-----------|-------------------|-------------------|
| In-Memory Grid | Hazelcast Community | Hazelcast Enterprise |
| Stream Processing | Hazelcast Jet | - |
| Vector Store | N/A (skip feature) | Hazelcast Enterprise |
| Database | PostgreSQL | - |
| Metrics | Prometheus | - |
| Dashboards | Grafana | - |
| Tracing | Jaeger | - |
| Container | Docker | - |
| Orchestration | Kubernetes | - |

## Testing Requirements

- **Unit Tests**: JUnit 5, Mockito, >80% coverage
- **Integration Tests**: Testcontainers with real Hazelcast
- **End-to-End Tests**: Full scenario tests
- **Load Tests**: Performance validation

## Documentation Requirements

- JavaDoc for all public APIs
- README per module
- Architecture Decision Records (ADRs)
- Demo walkthrough guides
- API documentation (OpenAPI)

---

# Appendix: Removed/Deferred Items

The following items from the previous requirements document have been removed or deferred indefinitely:

| Item | Reason |
|------|--------|
| Pluggable messaging (Kafka, RabbitMQ) | Complexity not justified for educational demo |
| Alternative databases (MongoDB, Cassandra) | Complexity not justified |
| Alternative domains (IoT, Financial) | Focus on eCommerce for now |
| Strangler Fig pattern | Not relevant to greenfield demo |
| Service Mesh (Istio) | Kubernetes-specific, defer to Phase 3+ |
| CDC (Debezium) | Alternative to outbox, not needed |
| Custom React dashboard | Start with Grafana instead |
| Microbenchmarking CLI | Existing load tests sufficient |

---

# Decision Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-01-27 | Phase 1 complete | All deliverables verified |
| 2026-01-28 | Phase 2 scope: Sagas + Vector Store + Dashboards | User priorities |
| 2026-01-28 | Grafana over custom UI for Phase 2 | Faster to implement |
| 2026-01-28 | Add Payment Service for saga demo | Makes saga realistic |
| 2026-01-28 | Vector Store optional (Enterprise) | Maintain open source default |

---

*This document supersedes all previous versions of the phased requirements.*
