# Hazelcast Microservices Framework
## Multi-Phase Requirements Document

---

## Overview

This document captures the complete vision for the Hazelcast microservices demonstration framework, organized into phases for incremental development. Phase 1 focuses on core functionality, while subsequent phases add observability, tooling, and advanced features.

---

## Phase 1: Core Framework & Event Sourcing Foundation
**Goal**: Event-sourced microservices with Hazelcast Jet pipeline and materialized views

### Core Architecture Philosophy
**Event Sourcing as Foundation** - Events are the source of truth, not database state. All state changes captured as immutable event log. Materialized views are projections of event stream.

### Functional Requirements
- [x] 3-service eCommerce implementation (Account, Inventory, Order)
- [x] **Event Sourcing Architecture** (PRIMARY FOCUS)
  - Central `handleEvent()` method in all services
  - PendingEvents IMap (transient event queue)
  - EventStore IMap (event log - may migrate to Postgres later)
  - Hazelcast Jet pipeline for event processing
  - CompletedEvents map for event acknowledgment
- [x] Event-driven communication via Hazelcast Topics
- [x] Materialized view management in Hazelcast
- [x] Basic REST APIs for each service
- [x] Docker Compose setup for local development (laptop-runnable)
- [x] Optional: PostgreSQL for persistent event store (Phase 2 migration)

### Domain-Agnostic Framework
- [ ] Event bus abstractions (publish/subscribe)
- [ ] Materialized view management base classes
- [ ] Service base classes and configuration
- [ ] Common event schemas and metadata

### Pluggable Architecture (Interface/Implementation Separation)
- [ ] **Messaging Abstraction Layer**
  - Interface: `EventPublisher`, `EventSubscriber`
  - Implementations: 
    - `HazelcastEventBus` (default for Phase 1)
    - `KafkaEventBus` (Phase 3/4)
    - `RabbitMQEventBus` (Phase 3/4)
  - Configuration-driven selection (no code changes)
  
- [ ] **Storage Abstraction Layer**
  - Interface: `EventStore`, `ViewStore`, `EntityRepository`
  - Implementations:
    - `PostgresEntityRepository` (default for Phase 1)
    - `MongoEntityRepository` (Phase 4)
    - `CassandraEntityRepository` (Phase 4)
  - Per-service configuration flexibility
  
- [ ] **View Storage Abstraction**
  - Interface: `MaterializedViewStore`
  - Implementations:
    - `HazelcastViewStore` (default)
    - `RedisViewStore` (Phase 4)
    - `In-memory fallback for testing`
  
- [ ] **Configuration Examples**:
  ```yaml
  # Switch to Kafka messaging
  framework:
    event-bus:
      provider: kafka
      kafka:
        bootstrap-servers: localhost:9092
  
  # Or stick with Hazelcast
  framework:
    event-bus:
      provider: hazelcast
      hazelcast:
        cluster-name: demo
  ```

### Demo Scenarios
- [ ] Create customer, product, order (happy path)
- [ ] Cancel order (compensating transaction)
- [ ] Query customer order history (aggregated view)

### Technical Requirements
- [ ] Java 17+ with Spring Boot 3.x
- [ ] Hazelcast 5.x cluster (3 nodes)
- [ ] PostgreSQL databases (one per service)
- [ ] Basic structured logging (JSON format)

### Documentation
- [ ] Architecture design document ✓
- [ ] README with setup instructions
- [ ] API documentation (Swagger/OpenAPI)
- [ ] Demo walkthrough guide

### Out of Scope for Phase 1
- Real-time visual dashboards
- Advanced monitoring/metrics
- Performance testing tools
- Alternative domain implementations
- Kubernetes deployment

---

## Phase 2: Observability & Monitoring
**Goal**: Comprehensive visibility into system behavior and health

### 2.1 Real-Time Visual Dashboard

#### System Overview Panel
- **Current System Status**
  - Service health indicators (green/yellow/red)
  - Hazelcast cluster health (nodes, partitions)
  - Database connection status
  - Event bus status

#### Transaction Monitoring
- **Completed Transactions**
  - Total transactions completed (lifetime counter)
  - Transactions per second (TPS) - rolling average
  - Success vs. failure rate
  - Transaction type breakdown (orders, cancellations, etc.)
  - Recent transaction history (last 10-20 transactions)

- **In-Flight Transactions**
  - Currently processing transactions count
  - Transaction details:
    - Transaction ID
    - Type (OrderCreation, OrderCancellation, etc.)
    - Started timestamp
    - Current status/step
    - Services involved
    - Age (time since started)
  - Visual flow showing transaction path through services

- **Stalled Transactions**
  - Transactions exceeding expected completion time
  - Threshold-based alerts (e.g., >5 seconds for order)
  - Potential causes:
    - Service non-responsive
    - Event processing backlog
    - Database connection issues
    - Deadlocks or timeouts
  - Auto-detection with configurable thresholds

#### Event Flow Visualization
- **Live Event Stream**
  - Real-time events as they flow through the system
  - Visual connections between publishers and consumers
  - Event payload preview
  - Event processing latency
  - Color-coded by event type

- **Event Metrics**
  - Events published per service (rate)
  - Events consumed per service (rate)
  - Event processing lag per topic
  - Dead letter queue depth

#### Materialized View Health
- **View Statistics**
  - Current size per view (entry count)
  - Memory usage per view
  - View staleness (max age of entries)
  - Update rate per view
  - Last update timestamp

- **View Freshness Indicators**
  - Real-time staleness gauge (green: <1s, yellow: 1-5s, red: >5s)
  - Alerts when views fall behind
  - Histogram of update latencies

#### Service-Level Metrics
- **Per-Service Dashboard**
  - Request rate (incoming API calls)
  - Response time distribution (p50, p95, p99)
  - Error rate
  - Database query count and latency
  - Event publish rate
  - Event consume rate
  - Active threads / connection pools

### 2.2 Technical Implementation

#### Metrics Collection
- **Technology**: Micrometer + Prometheus
- **Custom Metrics**:
  - `transactions.completed.total` (counter)
  - `transactions.in_flight` (gauge)
  - `transactions.duration.seconds` (histogram)
  - `transactions.stalled.total` (gauge with threshold)
  - `events.published.total` (counter per topic)
  - `events.consumed.total` (counter per topic)
  - `events.processing.lag.seconds` (gauge)
  - `view.size.entries` (gauge per view)
  - `view.staleness.seconds` (gauge per view)
  - `view.update.duration.seconds` (histogram per view)

#### Dashboard Technology
- **Option A**: Grafana dashboards
  - Prometheus data source
  - Pre-built dashboard JSON
  - Annotations for deployments/events
  
- **Option B**: Custom Web UI (React/Vue)
  - WebSocket for real-time updates
  - D3.js or similar for visualizations
  - More interactive, demo-friendly

- **Recommendation**: Start with Grafana (faster), add custom UI in Phase 3 if needed

#### Distributed Tracing
- **Technology**: OpenTelemetry + Jaeger/Zipkin
- **Capabilities**:
  - Trace requests across service boundaries
  - Visualize event propagation
  - Identify bottlenecks
  - Detect stalled transactions via trace duration
  - Correlation IDs linking API calls → events → view updates

### 2.3 Alerting & Notifications

#### Alert Definitions
- **Critical**:
  - Service down
  - Hazelcast cluster unhealthy
  - Stalled transactions exceed threshold (e.g., >10)
  - Event processing lag >10 seconds
  
- **Warning**:
  - Stalled transactions detected (>0)
  - View staleness >5 seconds
  - Error rate >1%
  - TPS drops >50% from baseline

#### Alert Channels
- Console/logs (Phase 2)
- Email (Phase 2)
- Slack/webhook (Phase 3)

### 2.4 Stalled Transaction Detection

#### Detection Mechanism
```java
// Pseudo-code for stalled transaction detection
class TransactionTracker {
    Map<String, TransactionInFlight> inFlightTransactions;
    
    void startTransaction(String txId, String type) {
        inFlightTransactions.put(txId, new TransactionInFlight(
            txId, type, Instant.now(), expectedDurationMillis(type)
        ));
    }
    
    void completeTransaction(String txId) {
        inFlightTransactions.remove(txId);
    }
    
    List<Transaction> detectStalledTransactions() {
        return inFlightTransactions.values().stream()
            .filter(tx -> tx.age() > tx.expectedDuration() * 2)
            .collect(toList());
    }
}
```

#### Root Cause Indicators
- **Service Non-Responsive**:
  - Health check failures
  - No event consumption
  - API timeout errors
  
- **Event Processing Backlog**:
  - Topic lag increasing
  - Consumer thread pool saturated
  
- **Database Issues**:
  - Connection pool exhausted
  - Slow query log entries
  - Transaction timeout errors

#### Recovery Actions
- Manual intervention (Phase 2)
- Auto-retry with backoff (Phase 3)
- Circuit breaker pattern (Phase 3)
- Dead letter queue processing (Phase 3)

### 2.5 Performance Baselines

#### Expected Performance (for demo)
- **Order Creation (end-to-end)**: 
  - p50: <100ms
  - p95: <200ms
  - p99: <500ms
  
- **Event Processing Latency**: 
  - p50: <50ms
  - p95: <100ms
  
- **View Read Latency**: 
  - p50: <5ms
  - p99: <10ms
  
- **Transactions Per Second**: 
  - Target: 100+ TPS (single instance)
  - Stretch: 1000+ TPS (scaled)

#### Benchmarking Tools
- JMeter or Gatling for load generation
- Built-in performance test scenarios
- Automated regression testing

#### Microbenchmarking Capability
- **Built-in Microbenchmark Suite**
  - Leverages existing TPS metrics collection
  - Command-line tool for running benchmarks
  - Measure specific operations in isolation:
    - Event publish latency
    - View read latency
    - View update latency
    - Database query performance
    - End-to-end transaction time
  - JSON output for tracking over time
  - Comparison mode (baseline vs. current)
  
- **Integration with CI/CD** (Phase 5)
  - Detect performance regressions
  - Fail builds on threshold violations
  
- **Example Usage**:
  ```bash
  ./benchmark.sh --operation view_read --iterations 10000
  ./benchmark.sh --operation order_creation --tps 100 --duration 60s
  ./benchmark.sh --compare baseline.json
  ```

---

## Phase 3: Advanced Features & Tooling
**Goal**: Production-grade capabilities and developer experience

### 3.1 Transaction Simulation & Testing

#### Load Generator
- **Web UI for Test Configuration**:
  - Configure transaction types (order creation, cancellation, etc.)
  - Set transaction rate (TPS target)
  - Define duration (time-based or count-based)
  - Randomize customer/product selection
  - Simulate error scenarios (invalid data, service failures)

- **Automated Test Scenarios**:
  - Steady load (constant TPS)
  - Ramp-up load (gradual increase)
  - Spike load (sudden burst)
  - Chaos scenarios (random service failures)

#### Transaction Replay
- Record production-like transaction sequences
- Replay for debugging or testing
- Compare performance across versions

### 3.2 Chaos Engineering

#### Failure Injection
- **Service Failures**:
  - Kill random service instance
  - Introduce latency (slow responses)
  - Return error responses
  
- **Network Failures**:
  - Partition services (simulate network split)
  - Introduce packet loss
  
- **Resource Constraints**:
  - CPU throttling
  - Memory pressure
  - Disk I/O saturation

#### Resilience Testing
- Demonstrate system behavior under failure
- Validate recovery mechanisms
- Test transaction rollback/compensation

### 3.3 Advanced Observability

#### Custom Real-Time Dashboard
- **Interactive Transaction Flow**:
  - Animated visualization of transactions flowing through system
  - Click transaction to see details and trace
  - Filter by status, service, time range
  
- **System Topology Map**:
  - Visual representation of services and dependencies
  - Real-time health indicators on each node
  - Event flow animations between services
  
- **Historical Analysis**:
  - Time-series charts for all metrics
  - Compare time periods
  - Anomaly detection

#### Log Aggregation
- Centralized logging (ELK stack or similar)
- Correlation ID search
- Log analysis and patterns

### 3.4 Developer Experience

#### CLI Tools
- Transaction submission tool
- View inspection tool (query Hazelcast views from CLI)
- Event publisher/consumer tools
- Health check utilities

#### Hot Reload / Dev Mode
- Auto-restart services on code change
- Fast feedback loop for development

#### Testing Framework
- Integration test framework with embedded Hazelcast
- Test data generators
- Mock event publishers

---

## Phase 4: Multi-Domain & Extensions
**Goal**: Framework reusability and alternative demonstrations

### 4.1 Alternative Domain Implementations

#### Financial Services / Payment Processing
- **Services**: Account, Transaction, Fraud Detection
- **Materialized Views**: 
  - Account balance view
  - Transaction history view
  - Fraud score view
- **Demo Focus**: Real-time fraud detection

#### IoT Sensor Data
- **Services**: Device Registry, Data Ingestion, Analytics
- **Materialized Views**:
  - Device status view
  - Aggregated sensor readings
  - Alert view
- **Demo Focus**: High-throughput data processing

#### Content Management
- **Services**: Content, Workflow, Publishing
- **Materialized Views**:
  - Content index
  - Workflow status
  - Published content cache
- **Demo Focus**: CQRS pattern

### 4.2 Framework Enhancements

#### Domain Model Generator
- CLI tool to scaffold new domain implementation
- Template-based code generation
- Prompts for entities, events, views

#### Pattern Library
- Saga orchestration pattern
- Outbox pattern for reliable event publishing
- Event sourcing with Hazelcast event journal
- CQRS command/query separation

### 4.3 Integration Extensions

#### Message Bus Options
- Kafka integration (alternative to Hazelcast Topics)
- RabbitMQ integration
- Pulsar integration

#### Database Options
- MongoDB support (document store per service)
- Cassandra support (wide-column store)
- Mix-and-match databases per service

---

## Phase 5: Production Readiness (Optional)
**Goal**: Enterprise-grade deployment capabilities

### 5.1 Kubernetes Deployment

#### Helm Charts
- Service deployments
- Hazelcast cluster (StatefulSet)
- PostgreSQL (StatefulSet or external)
- Ingress configuration
- ConfigMaps and Secrets

#### Auto-Scaling
- Horizontal Pod Autoscaler (HPA) for services
- Hazelcast cluster auto-scaling
- Load-based scaling policies

### 5.2 Security

#### Authentication & Authorization
- OAuth2/OIDC integration
- Service-to-service authentication (mTLS)
- API key management

#### Secrets Management
- Vault integration
- Encrypted configuration
- Credential rotation

### 5.3 CI/CD Pipeline

#### Automated Build
- Multi-stage Docker builds
- Dependency caching
- Version tagging

#### Testing Pipeline
- Unit tests
- Integration tests
- Performance regression tests
- Security scanning

#### Deployment Automation
- Blue-green deployment
- Canary releases
- Rollback capabilities

---

## Phasing Strategy

### Recommended Sequence

**Phase 1** (Weeks 1-3): Core Framework
- Focus: Get basic system working end-to-end
- Milestone: Demo the happy path with 3 services
- Deliverable: Working code + design docs

**Phase 2** (Weeks 4-6): Observability
- Focus: Add monitoring, metrics, and visual dashboard
- Milestone: See transactions flowing, detect stalled transactions
- Deliverable: Grafana dashboards + distributed tracing

**Phase 3** (Weeks 7-10): Advanced Features
- Focus: Load generation, chaos testing, custom UI
- Milestone: Robust demo with failure scenarios
- Deliverable: Load testing tools + resilience demos

**Phase 4** (Weeks 11-14): Multi-Domain
- Focus: Alternative domain implementations
- Milestone: Framework validated with 2+ domains
- Deliverable: Payment processing demo + framework docs

**Phase 5** (Optional): Production readiness
- Focus: Kubernetes, security, CI/CD
- Milestone: Production-ready reference architecture
- Deliverable: Helm charts + deployment guides

### Phase 1 Focus Areas

For your initial implementation, I recommend:

**Must Have**:
- ✅ 3 services with materialized views
- ✅ Event-driven communication
- ✅ Basic REST APIs
- ✅ Docker Compose setup
- ✅ Happy path demo scenario
- ⚠️ Basic logging (structured JSON logs with correlation IDs)
- ⚠️ Basic metrics exposed (Micrometer actuator endpoints)

**Nice to Have** (if time permits):
- Simple health check endpoints
- Swagger/OpenAPI documentation
- Sample Grafana dashboard (can import later)

**Defer to Phase 2**:
- Real-time visual dashboard
- Stalled transaction detection UI
- Advanced alerting
- Distributed tracing
- Performance testing

---

## Open Questions for Phase 1

### Observability Foundations
1. **Metrics Exposure**: Should we add Micrometer + Prometheus endpoints in Phase 1, even without Grafana?
   - **Recommendation**: Yes - minimal overhead, enables Phase 2
   
2. **Correlation IDs**: Implement in Phase 1?
   - **Recommendation**: Yes - essential for troubleshooting, easy to add now

3. **Health Checks**: Beyond simple "UP", include dependency checks (DB, Hazelcast)?
   - **Recommendation**: Yes - helps debug integration issues

### Dashboard Approach
4. **Phase 2 Dashboard**: Grafana or custom UI?
   - **Recommendation**: Start with Grafana (faster), iterate to custom UI if needed

5. **Stalled Transaction Detection**: Server-side logic or dashboard-based?
   - **Recommendation**: Server-side logic (metrics), dashboard for visualization

### Technical Decisions
6. **Distributed Tracing**: OpenTelemetry in Phase 1 or Phase 2?
   - **Recommendation**: Phase 2 - adds complexity, not critical for basic demo

7. **Event Schema Evolution**: Version events from the start?
   - **Recommendation**: Yes - add `eventVersion` field, easier than retrofitting

8. **View Rebuild**: On startup, rebuild from database or wait for events?
   - **Recommendation**: Phase 1: wait for events (simpler), Phase 2: add rebuild capability

---

## Success Criteria by Phase

### Phase 1: Core Framework
- ✅ End-to-end transaction completes successfully
- ✅ Materialized views updated correctly
- ✅ Services can be started via `docker-compose up`
- ✅ Demo scenario runnable with curl/Postman
- ✅ Code is modular and domain-agnostic framework extracted

### Phase 2: Observability
- ✅ Dashboard shows real-time TPS
- ✅ In-flight transactions visible
- ✅ Stalled transactions detected and highlighted
- ✅ Service health indicators accurate
- ✅ View staleness monitored
- ✅ Alerts trigger on threshold violations

### Phase 3: Advanced Features
- ✅ Load generator produces 100+ TPS
- ✅ Chaos scenarios demonstrate resilience
- ✅ Custom UI provides interactive experience
- ✅ Performance baselines documented

### Phase 4: Multi-Domain
- ✅ Payment processing demo working
- ✅ Framework successfully adapted to 2nd domain
- ✅ Framework documentation complete
- ✅ Developer can scaffold new domain in <1 hour

### Phase 5: Production Readiness
- ✅ Deploys to Kubernetes cluster
- ✅ Auto-scales under load
- ✅ Secure by default
- ✅ CI/CD pipeline operational

---

## Appendix: Phase 2 Dashboard Mockup

### Main Dashboard Layout

```
┌─────────────────────────────────────────────────────────────────────┐
│  Hazelcast Microservices Demo - System Overview                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  System Health                                                        │
│  ┌──────────────┬──────────────┬──────────────┬──────────────┐      │
│  │  Account     │  Inventory   │  Order       │  Hazelcast   │      │
│  │  Service     │  Service     │  Service     │  Cluster     │      │
│  │     🟢       │     🟢       │     🟢       │     🟢       │      │
│  │   Healthy    │   Healthy    │   Healthy    │   3 Nodes    │      │
│  └──────────────┴──────────────┴──────────────┴──────────────┘      │
│                                                                       │
│  Transaction Metrics                                                  │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  Completed: 1,247    TPS: 42.3    In-Flight: 3    Stalled: 0│    │
│  │                                                               │    │
│  │  [TPS Graph - last 5 minutes]                                │    │
│  │   60 ┤                                    ╭─╮                │    │
│  │   40 ┤              ╭───╮      ╭─────────╯ ╰─╮              │    │
│  │   20 ┤    ╭─────────╯   ╰──────╯            ╰──            │    │
│  │    0 ┼────┴────────────────────────────────────────────     │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                       │
│  In-Flight Transactions                                               │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ TxID: a7f3...  │ Type: OrderCreation  │ Age: 0.3s  │ 🟢     │    │
│  │ TxID: b2e9...  │ Type: StockReserve   │ Age: 0.8s  │ 🟢     │    │
│  │ TxID: c4d1...  │ Type: OrderCreation  │ Age: 5.2s  │ 🟡     │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                       │
│  Materialized View Health                                             │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ customer-view              │ 142 entries  │ Staleness: 0.2s │    │
│  │ product-availability-view  │ 89 entries   │ Staleness: 0.1s │    │
│  │ enriched-order-view        │ 1,247 entries│ Staleness: 0.5s │    │
│  │ customer-order-summary     │ 142 entries  │ Staleness: 0.3s │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                       │
│  Recent Events (last 10)                                              │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ 11:15:42.123 → OrderCreated      (Order → Inventory)         │    │
│  │ 11:15:42.089 → StockReserved     (Inventory → Order)         │    │
│  │ 11:15:41.950 → CustomerUpdated   (Account → Order)           │    │
│  │ ...                                                           │    │
│  └─────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

### Stalled Transaction Detail View

```
┌─────────────────────────────────────────────────────────────────────┐
│  Stalled Transaction Details - TxID: c4d1e7a3                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  Status: ⚠️  STALLED (exceeds 5s threshold)                          │
│  Type: OrderCreation                                                  │
│  Started: 2024-01-15 11:15:37.123                                    │
│  Age: 5.2 seconds                                                     │
│  Expected Duration: <200ms                                            │
│                                                                       │
│  Transaction Flow:                                                    │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                                                               │    │
│  │  Order Service  ──✓─→  OrderCreated Event  ──✓─→  Inventory │    │
│  │                                                     Service   │    │
│  │                                                        │      │    │
│  │                                                        ✗      │    │
│  │                                              StockReserved    │    │
│  │                                              (Not Received)   │    │
│  │                                                               │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                       │
│  Root Cause Analysis:                                                 │
│  • Inventory Service: Last heartbeat 6s ago (may be down)            │
│  • Event Topic: events.order.order-created has 1 pending message     │
│  • Recommendation: Check Inventory Service health                    │
│                                                                       │
│  Actions:                                                             │
│  [ Retry Transaction ]  [ Cancel Transaction ]  [ View Logs ]        │
│                                                                       │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Document Control

- **Version**: 1.0
- **Last Updated**: 2024-01-23
- **Author**: [Your Name]
- **Status**: Requirements Definition
- **Related Documents**: 
  - hazelcast-ecommerce-design.md (Phase 1 detailed design)


---

## Microservices Patterns to Demonstrate

### Overview
Beyond basic materialized views, this framework can demonstrate multiple proven microservices patterns. Each pattern addresses specific distributed system challenges and provides educational value.

### Pattern Implementation Strategy
- **Phase 1**: Foundation patterns (event-driven, materialized views)
- **Phase 2**: Observability patterns (distributed tracing, health checks)
- **Phase 3**: Resilience patterns (sagas, circuit breakers, retries)
- **Phase 4**: Advanced patterns (event sourcing, CQRS, outbox)

---

### P1: Materialized View Pattern (IMPLEMENTED - Phase 1)
**Problem**: Cross-service queries require expensive joins or service-to-service calls  
**Solution**: Maintain denormalized, pre-computed views in fast storage (Hazelcast)

**Demonstrated by**:
- `enriched-order-view` - denormalized order with customer/product data
- `customer-order-summary-view` - aggregated statistics

**Educational Value**: Shows read/write separation, eventual consistency

---

### P2: Saga Pattern (RECOMMENDED - Phase 3)
**Problem**: Distributed transactions across services need coordination and compensation  
**Solution**: Choreographed or orchestrated sequence of local transactions with compensating actions

**Implementation Approach for This Framework**:

#### Choreographed Saga (Simpler, recommend starting here)
- Each service listens to events and performs its part
- Publishes success/failure events
- Other services react with compensation if needed

**Example: Order Fulfillment Saga**
```
1. Order Service → Creates order (PENDING)
2. Publishes: OrderCreated
3. Inventory Service → Reserves stock
4. Publishes: StockReserved (success) OR StockReservationFailed (failure)
5. If StockReserved:
   - Order Service → Confirms order
   - Payment Service → Processes payment (new service for saga demo)
   - Publishes: PaymentProcessed OR PaymentFailed
6. If PaymentFailed:
   - Inventory Service → Releases stock (compensation)
   - Order Service → Cancels order (compensation)
```

**Phase 1 Foundation (already supports this!)**:
- ✅ Correlation IDs (track saga instance)
- ✅ Event-driven communication
- ✅ Compensating events (OrderCancelled → StockReleased)

**Phase 3 Additions**:
- [ ] Saga orchestrator service (optional, for orchestrated sagas)
- [ ] Saga state machine visualization
- [ ] Timeout handling (saga deadline)
- [ ] Saga state persistence (track progress)

**Educational Value**: 
- Distributed transaction coordination
- Compensation logic
- Eventual consistency with guarantees
- Failure handling in distributed systems

---

### P3: Circuit Breaker Pattern (Phase 3)
**Problem**: Cascading failures when dependent services are slow/down  
**Solution**: Automatically detect failures and "open circuit" to prevent cascading issues

**Implementation**:
- Use Resilience4j library
- Wrap service-to-service calls (if we add any synchronous calls)
- Wrap database calls
- Wrap Hazelcast operations

**States**: Closed → Open (on failures) → Half-Open (test recovery) → Closed

**Demo Scenario**: 
- Database becomes slow
- Circuit opens after N failures
- Service continues with degraded functionality (e.g., return cached data)
- Circuit tests recovery and closes when DB recovers

**Educational Value**: Fault isolation, graceful degradation

---

### P4: Outbox Pattern (Phase 3)
**Problem**: Database update + event publish must be atomic (avoid dual-write problem)  
**Solution**: Write events to outbox table in same transaction, separate process publishes

**Implementation**:
```
1. Service updates entity in database
2. In SAME transaction, writes event to `outbox` table
3. Background process polls outbox table
4. Publishes events to event bus
5. Marks as published in outbox
```

**Educational Value**: 
- Transactional integrity across database and messaging
- At-least-once delivery guarantees
- Solving dual-write problem

**Phase 1 Foundation**:
- Each service already has database per microservice principle
- Easy to add outbox table

**Phase 3 Implementation**:
- [ ] Outbox table schema
- [ ] Outbox publisher component
- [ ] Idempotent event handlers (handle duplicates)

---

### P5: Event Sourcing (Phase 4 - Advanced)
**Problem**: Need complete audit trail, time-travel queries, or event replay  
**Solution**: Store all state changes as immutable event log, rebuild state from events

**Implementation**:
- Hazelcast Event Journal as event store
- Events are source of truth (not database tables)
- Rebuild entity state by replaying events
- Materialized views are projections of event stream

**Example - Order Aggregate**:
```
Events:
- OrderCreatedEvent
- PaymentReceivedEvent  
- OrderShippedEvent
- OrderDeliveredEvent

Current State = replay all events for order ID
```

**Educational Value**:
- Immutable event log
- Temporal queries ("what was order status yesterday?")
- Event replay for testing/debugging
- CQRS natural fit

**Complexity**: HIGH - recommend Phase 4 only if strong interest

---

### P6: CQRS (Command Query Responsibility Segregation) (Phase 2/3)
**Problem**: Read and write models have different requirements  
**Solution**: Separate models for commands (writes) and queries (reads)

**Implementation in This Framework**:
**Already partially implemented!**
- Write Model: PostgreSQL entities (Account, Inventory, Order services)
- Read Model: Hazelcast materialized views
- Commands go to services, queries go to views

**Phase 2/3 Enhancement**:
- [ ] Explicit command objects (CreateOrderCommand)
- [ ] Command handlers (validate, execute, publish events)
- [ ] Query objects (GetCustomerOrdersQuery)
- [ ] Query handlers (read from materialized views)
- [ ] Demonstrate different read models for different use cases

**Educational Value**: 
- Separation of concerns
- Optimized read/write paths
- Scalability (scale reads independently)

---

### P7: Strangler Fig Pattern (Phase 4 - Alternative Demo)
**Problem**: Migrating monolith to microservices incrementally  
**Solution**: Gradually replace monolith functionality with microservices

**Demo Approach**:
- Start with "legacy monolith" (simulated)
- Incrementally extract services
- Route traffic through facade
- Eventually remove monolith

**Educational Value**: Migration strategy, not just greenfield architecture

---

### P8: API Gateway / BFF (Backend for Frontend) (Phase 3)
**Problem**: Clients need different views, many service calls, authentication  
**Solution**: Gateway aggregates calls, provides client-specific APIs

**Implementation**:
- Spring Cloud Gateway or similar
- Aggregate multiple service calls for UI
- Handle authentication/authorization
- Rate limiting
- Request/response transformation

**Educational Value**: Client-service decoupling, API composition

---

### P9: Service Mesh / Sidecar Pattern (Phase 5)
**Problem**: Cross-cutting concerns (retry, timeout, tracing) in every service  
**Solution**: Sidecar proxy handles concerns, services stay simple

**Implementation**: 
- Istio or Linkerd on Kubernetes
- Traffic management, observability, security

**Educational Value**: Infrastructure-level patterns, cloud-native architecture

---

### P10: Change Data Capture (CDC) Pattern (Phase 4)
**Problem**: Need to publish events based on database changes  
**Solution**: Capture database transaction log, convert to events

**Implementation**:
- Debezium watches PostgreSQL WAL (write-ahead log)
- Publishes events automatically on database changes
- Alternative to outbox pattern

**Educational Value**: Database as event source, operational simplicity

---

## Recommended Pattern Roadmap

### Phase 1 (Weeks 1-3)
✅ **Materialized View Pattern**
✅ **Event-Driven Architecture** (foundation)
✅ **Database per Service**
⚠️ **Saga Foundation** - Add saga metadata to events

**Saga Foundation for Phase 1** (minimal effort, huge payoff):
```java
// Enhanced event metadata
public class DomainEvent {
    private String eventId;
    private String eventType;
    private String correlationId;  // Already in design
    
    // NEW for saga support:
    private String sagaId;         // Optional: groups events in a saga
    private String sagaType;       // Optional: "OrderFulfillment", "PaymentProcessing"
    private Integer stepNumber;    // Optional: position in saga sequence
    
    private Instant timestamp;
    private String source;
    private Object payload;
}
```

**Why add now**:
- Minimal code change (~1 day)
- Enables Phase 3 saga implementation
- Useful for Phase 2 observability (trace saga flow)
- No complexity if not used

### Phase 2 (Weeks 4-6)
🔄 **CQRS** (explicit commands/queries)
🔄 **Health Check Pattern**
🔄 **Distributed Tracing Pattern**
🔄 **Saga Visibility** (dashboard shows saga progress)

### Phase 3 (Weeks 7-10) - RESILIENCE FOCUS
🎯 **Saga Pattern** (choreographed) ⭐ HIGH PRIORITY
  - Add Payment Service (4th service)
  - Implement OrderFulfillment saga with compensation
  - Saga timeout/deadline handling
  
🎯 **Outbox Pattern** (transactional messaging)
  - Solve dual-write problem
  - Guaranteed event delivery
  
🎯 **Circuit Breaker Pattern**
  - Resilience4j integration
  - Graceful degradation demos
  
🎯 **Retry Pattern** (with exponential backoff)
  - Handle transient failures
  
🎯 **API Gateway / BFF**
  - Request aggregation
  - Client-specific APIs

### Phase 4 (Weeks 11-14) - ADVANCED PATTERNS
🔮 **Event Sourcing** (optional, if interested)
🔮 **Change Data Capture** (Debezium)
🔮 **Strangler Fig** (migration demo)
🔮 **Saga Orchestrator** (compare with choreography)

### Phase 5 (Optional) - PRODUCTION PATTERNS
🚀 **Service Mesh** (Istio/Linkerd)

---

## Saga Pattern: Detailed Implementation Plan

Since you're particularly interested in sagas, here's the incremental approach:

### Phase 1: Lay Foundation (DO NOW) ✅
**Changes to existing Phase 1 design**:

1. **Enhanced Event Metadata**
   ```java
   @Data
   public class EventMetadata {
       private String eventId = UUID.randomUUID().toString();
       private String eventType;
       private String correlationId;  // Existing
       
       // NEW - Saga support (all optional, null if not in saga)
       private String sagaId;        // Groups events in a saga instance
       private String sagaType;      // "OrderFulfillment", "ReturnProcessing"
       private Integer stepNumber;   // Position in saga (optional)
       private Boolean isCompensating = false;  // Is this a rollback event?
       
       private Instant timestamp = Instant.now();
       private String source;
   }
   ```

2. **Explicit Success/Failure Events**
   - Already have: `OrderCreated`, `StockReserved`, `OrderCancelled`
   - Add: `StockReservationFailed`, with failure reason
   ```java
   public class StockReservationFailed {
       private String orderId;
       private String productId;
       private Integer requestedQuantity;
       private Integer availableQuantity;
       private String reason;  // "INSUFFICIENT_STOCK", "PRODUCT_DISCONTINUED"
   }
   ```

3. **Idempotent Event Handlers**
   - Track processed event IDs
   - Ignore duplicate events (at-least-once delivery)

**Effort**: ~1-2 days additional in Phase 1  
**Payoff**: Enables all of Phase 3 saga work

### Phase 2: Observe Sagas 🔍
- Dashboard shows saga instances
- Trace saga flow across services
- Detect stuck/failed sagas
- Timeline view of saga execution

### Phase 3: Full Saga Implementation 🎯

**Add Payment Service** (4th microservice):
```
Payment Service responsibilities:
- Process payments (authorize, capture, refund)
- Validate payment methods
- Track payment state
- Publish: PaymentProcessed, PaymentFailed, PaymentRefunded
```

**Implement Choreographed OrderFulfillment Saga**:

```
Happy Path:
1. User → Order Service: Create order
2. Order Service: 
   - Create order (PENDING)
   - Publish: OrderCreated (sagaId=X, stepNumber=1)
3. Inventory Service:
   - Receive: OrderCreated
   - Reserve stock
   - Publish: StockReserved (sagaId=X, stepNumber=2)
4. Payment Service:
   - Receive: StockReserved
   - Process payment
   - Publish: PaymentProcessed (sagaId=X, stepNumber=3)
5. Order Service:
   - Receive: PaymentProcessed
   - Update order (CONFIRMED)
   - Publish: OrderConfirmed (sagaId=X, stepNumber=4)

Compensation Path (Payment Fails):
1. Payment Service:
   - Payment authorization fails
   - Publish: PaymentFailed (sagaId=X, isCompensating=false)
2. Inventory Service:
   - Receive: PaymentFailed
   - Release reserved stock
   - Publish: StockReleased (sagaId=X, isCompensating=true)
3. Order Service:
   - Receive: PaymentFailed + StockReleased
   - Update order (CANCELLED)
   - Publish: OrderCancelled (sagaId=X, isCompensating=true)
```

**Saga Timeout Handling**:
```java
// Saga timeout monitor
class SagaTimeoutMonitor {
    void checkTimeouts() {
        // Find sagas in-flight > timeout threshold
        // Publish SagaTimedOut event
        // Trigger compensation
    }
}
```

**Demo Scenarios**:
- Happy path (all steps succeed)
- Payment failure (triggers compensation)
- Inventory failure (triggers compensation before payment)
- Timeout (saga exceeds deadline, auto-compensate)

### Phase 4: Orchestrated Saga (Optional Comparison) 🔮

Add Saga Orchestrator Service:
```
SagaOrchestrator responsibilities:
- Maintains saga state machine
- Coordinates saga steps
- Triggers compensation on failure
- Persists saga state (for recovery)
```

**Comparison Demo**:
- Same OrderFulfillment saga
- Choreographed vs Orchestrated
- Trade-offs discussion

---

## Additional Patterns - Priority Ranking

Based on educational value and implementation feasibility:

| Priority | Pattern | Phase | Complexity | Educational Value | Effort |
|----------|---------|-------|------------|-------------------|--------|
| 🥇 #1 | **Saga (Choreographed)** | 3 | Medium | Very High | Medium |
| 🥈 #2 | **Outbox Pattern** | 3 | Low | High | Low |
| 🥉 #3 | **Circuit Breaker** | 3 | Low | High | Low |
| #4 | **CQRS (explicit)** | 2 | Low | Medium | Low |
| #5 | **API Gateway** | 3 | Medium | Medium | Medium |
| #6 | **Retry Pattern** | 3 | Low | Medium | Low |
| #7 | **Event Sourcing** | 4 | High | Very High | High |
| #8 | **CDC (Debezium)** | 4 | Medium | Medium | Medium |
| #9 | **Saga (Orchestrated)** | 4 | High | High | High |
| #10 | **Service Mesh** | 5 | High | Medium | High |

### Recommended Phase 3 Patterns (Pick 3-5):
1. ✅ Saga (choreographed) - **Must have**
2. ✅ Outbox - **Should have** (solves dual-write)
3. ✅ Circuit Breaker - **Should have** (resilience)
4. ⚠️ API Gateway - **Nice to have** (if time)
5. ⚠️ Explicit CQRS - **Nice to have** (already implicit)

---

## Summary of New Requirements

### 1. Microbenchmarking ✅
- **Phase 2**: Built-in benchmark tool
- Leverages existing TPS metrics
- CLI for running benchmarks
- JSON output for tracking
- **Effort**: Low (2-3 days)

### 2. Interface/Implementation Separation ✅
- **Phase 1**: Define abstractions
  - `EventPublisher`/`EventSubscriber` interfaces
  - `MaterializedViewStore` interface
  - `EntityRepository` interface
- **Phase 1**: Default implementations
  - `HazelcastEventBus`
  - `HazelcastViewStore`
  - `PostgresEntityRepository`
- **Phase 3/4**: Alternative implementations
  - `KafkaEventBus`
  - `RedisViewStore`
  - `MongoEntityRepository`
- **Effort**: Medium (5-7 days for abstractions, 3-5 days per alternative)

### 3. Saga Pattern ✅
- **Phase 1**: Foundation (saga metadata in events) - **1-2 days**
- **Phase 2**: Visibility (dashboard, tracing)
- **Phase 3**: Full implementation (choreographed) - **1-2 weeks**
  - Add Payment Service
  - Implement OrderFulfillment saga
  - Timeout handling
  - Demo scenarios
- **Phase 4**: Orchestrated alternative (optional) - **1 week**
- **Complexity**: Medium-High
- **Recommendation**: ✅ Do it! High educational value

### 4. Additional Patterns ✅
**Recommended for Phase 3**:
- Outbox Pattern (3-5 days)
- Circuit Breaker (2-3 days)
- Retry Pattern (2-3 days)

**Consider for Phase 4**:
- Event Sourcing (if interested in advanced patterns)
- API Gateway (if need aggregation demo)

---

## Updated Phase 1 Scope

### Original Phase 1 Scope:
- 3 services (Account, Inventory, Order)
- Event-driven communication
- Materialized views
- Basic REST APIs
- Docker Compose

### Updated Phase 1 Scope (with new requirements):

**Add (Low effort, high value)**:
- ⚠️ **Pluggable architecture abstractions** (+3-5 days)
  - Define interfaces for messaging, storage, views
  - Implement Hazelcast/Postgres defaults
  - Configuration-based selection
  
- ⚠️ **Saga foundation** (+1-2 days)
  - Add saga metadata to events
  - Success/failure event variants
  - Idempotent event handlers

**Defer to Phase 2**:
- Microbenchmarking tool (needs TPS metrics first)

**Defer to Phase 3**:
- Alternative implementations (Kafka, Mongo, etc.)
- Full saga implementation
- Additional patterns

### Estimated Phase 1 Duration:
- Original: 2-3 weeks
- With additions: 3-4 weeks (still manageable!)

---

## Questions for You

1. **Saga Foundation in Phase 1**: Add saga metadata to events now?
   - **Recommendation**: ✅ YES - minimal effort, enables Phase 3
   
2. **Pluggable Architecture in Phase 1**: Define abstractions now?
   - **Recommendation**: ✅ YES - good design practice, low effort
   
3. **Payment Service**: Add in Phase 3 for saga demo?
   - **Recommendation**: ✅ YES - makes saga more realistic

4. **Pattern Priority**: Which patterns most interesting to you?
   - Saga (choreographed) - assumed YES
   - Outbox - recommended
   - Circuit Breaker - recommended
   - Event Sourcing - your interest level?
   - API Gateway - your interest level?

5. **Orchestrated Saga**: Interested in Phase 4 comparison with choreographed?
   - Could be valuable educational content

6. **Event Sourcing**: High, medium, or low interest?
   - Determines if we plan for it in Phase 4

Ready to finalize Phase 1 design with these additions? Or more to discuss?

---

## MAJOR UPDATE: Revised Architecture & Requirements

### Key Changes Based on User Feedback

#### 1. Event Sourcing is Phase 1 Core (Not Phase 4!)
**Your existing Hazelcast Jet pipeline architecture becomes the foundation:**
- `handleEvent()` method standard across all services
- PendingEvents IMap → Jet Pipeline → EventStore + Views + Publish
- CompletedEvents IMap for event acknowledgment
- Event Store as source of truth (initially Hazelcast IMap, optionally Postgres)

See: `phase1-event-sourcing-architecture.md` for detailed design

#### 2. Hazelcast AI Features & Vector Store
**NEW REQUIREMENT - Phase 2/3**:
- Demonstrate Hazelcast Vector Store capabilities
- Must be OPTIONAL (open-source tech stack requirement)
- Potential use cases to explore:
  - Product recommendation engine (vector similarity search)
  - Customer behavior patterns
  - Fraud detection (anomaly detection via vectors)
  - Semantic search over order history
  - Event pattern recognition

**Ideas for Vector Store Integration**:
```
Option A: Product Recommendations
- Store product embeddings in Vector Store
- Query for similar products
- Update recommendations based on order history

Option B: Customer Segmentation
- Generate customer behavior vectors
- Cluster customers by purchasing patterns
- Personalized experiences

Option C: Event Pattern Detection
- Embed event sequences as vectors
- Detect similar patterns (e.g., fraud signatures)
- Alert on anomalous event patterns
```

#### 3. MCP Server Integration
**NEW REQUIREMENT - Phase 3/4**:
- Expose microservices framework capabilities via MCP
- Allow AI assistants to interact with the demo
- Possible MCP tools:
  - `query_materialized_view` - Query any view
  - `submit_event` - Submit events to services
  - `replay_events` - Trigger event replay
  - `get_event_history` - Query EventStore
  - `get_metrics` - Retrieve performance metrics
  - `inspect_saga` - View saga state

#### 4. Open Source Only Tech Stack
**CRITICAL REQUIREMENT**:
- All core features must run on 100% open source software
- Enterprise/paid features must be OPTIONAL add-ons
- Specifically:
  - Hazelcast Community Edition (not Enterprise)
  - Hazelcast AI features must be optional
  - PostgreSQL (not Oracle, not SQL Server)
  - No required cloud services (AWS, GCP, Azure optional)

**Compatibility Matrix**:
```
Core Demo (Phase 1-3):
✅ Hazelcast Community Edition
✅ PostgreSQL
✅ Docker / Docker Compose
✅ Spring Boot
✅ Prometheus + Grafana (observability)
✅ OpenTelemetry (tracing)

Optional Enhancements (Phase 3-5):
⚠️ Hazelcast Enterprise (optional features)
⚠️ Hazelcast Vector Store (AI features)
⚠️ Cloud deployment (Kubernetes, AWS, GCP)
⚠️ Managed Postgres (RDS, Cloud SQL)
```

#### 5. Laptop-Runnable + Cloud-Scalable
**Deployment Flexibility**:
- **Laptop Mode** (Phase 1-2):
  - Docker Compose
  - Single Hazelcast cluster (3 nodes)
  - Single Postgres instance
  - Low resource requirements (<8GB RAM)
  - All-in-one demo
  
- **Cloud Mode** (Phase 4-5):
  - Kubernetes deployment
  - Hazelcast cluster scales horizontally
  - Managed databases
  - Load balancers, ingress
  - Production-ready

#### 6. Testing & Documentation Requirements
**MANDATORY for Every Feature**:
- **Unit Tests**: JUnit 5, Mockito
  - Test coverage >80%
  - Test event handlers in isolation
  - Test view updaters
  - Test Jet pipeline logic
  
- **Integration Tests**: Testcontainers
  - Test with real Hazelcast cluster
  - Test with real Postgres
  - End-to-end scenarios
  
- **JavaDoc**: For all public APIs
  - Class-level documentation
  - Method-level documentation
  - Parameter descriptions
  - Usage examples
  
- **Markdown Documentation**:
  - README per module
  - Architecture Decision Records (ADRs)
  - Runbooks (how to run, deploy, troubleshoot)
  - Demo scripts

#### 7. Blog Post Series Structure
**Educational Content Strategy**:

Phase 1 deliverables should support a blog post series:

**Post 1: "Event Sourcing with Hazelcast - Introduction"**
- Why event sourcing?
- Basic architecture
- handleEvent() pattern
- Simple demo (create customer, create order)

**Post 2: "Building the Event Pipeline with Hazelcast Jet"**
- Jet pipeline deep dive
- PendingEvents → EventStore → Views
- Performance characteristics
- Metrics collection

**Post 3: "Materialized Views for Fast Queries"**
- View patterns (denormalized, aggregated)
- Update strategies
- View rebuilding
- Query patterns

**Post 4: "Observability in Event-Sourced Systems"** (Phase 2)
- Transaction monitoring
- Event flow visualization
- Detecting stalled transactions
- Dashboards

**Post 5: "Saga Pattern for Distributed Transactions"** (Phase 3)
- Choreographed sagas
- Compensation logic
- Timeout handling
- Demo scenarios

**Post 6: "AI-Powered Features with Hazelcast Vector Store"** (Phase 3)
- Vector embeddings
- Similarity search
- Product recommendations
- [Specific use case we choose]

**Post 7: "Production Deployment Strategies"** (Phase 5)
- Kubernetes deployment
- Scaling strategies
- Monitoring in production
- Disaster recovery

**Implication for Development**:
- Features should be developed in blog-post-sized chunks
- Each chunk should be independently demonstrable
- Code should be organized to match blog post structure
- Include runnable examples for each post

---

## Updated Phase 1 Scope

### Must Have (Revised)
✅ **Event Sourcing Architecture**
- handleEvent() method pattern
- Hazelcast Jet pipeline
- PendingEvents IMap
- EventStore IMap (with optional Postgres backup)
- MaterializedView IMaps
- CompletedEvents IMap

✅ **3 Microservices**
- Account Service
- Inventory Service
- Order Service

✅ **Framework Abstractions**
- EventPublisher/EventSubscriber interfaces
- MaterializedViewStore interface
- ViewUpdater pattern
- Domain-agnostic core

✅ **Basic Observability**
- Event metrics (timestamps at each pipeline stage)
- Correlation IDs
- Structured logging (JSON)
- Health checks

✅ **Developer Experience**
- Docker Compose setup (laptop-runnable)
- Unit tests (>80% coverage)
- Integration tests (Testcontainers)
- JavaDoc for all public APIs
- README files

✅ **Demo Scenarios**
- Create customer → Create product → Place order
- View order with enriched data (no service calls!)
- Rebuild views from EventStore

### Nice to Have (Phase 1)
⚠️ Event replay CLI tool
⚠️ Swagger/OpenAPI documentation
⚠️ Sample Grafana dashboard (import-ready)

### Defer to Phase 2
- Real-time visual dashboard
- Distributed tracing (OpenTelemetry)
- PostgreSQL event store (optional in Phase 1)
- Microbenchmarking tool

### Defer to Phase 3
- Saga pattern (choreographed)
- Hazelcast Vector Store demo
- MCP server integration
- Additional microservices patterns

---

## Vector Store Use Case Ideas

Need to choose ONE compelling use case for Phase 3. Options ranked by value:

### Option 1: Product Recommendation Engine 🥇
**Concept**: Recommend similar products based on embeddings
**Implementation**:
- Generate product embeddings (description + category + attributes)
- Store in Hazelcast Vector Store
- Query for k-nearest neighbors
- Show "customers who bought X also liked Y"

**Demo Value**: 
- Clear business value
- Easy to visualize
- Shows vector similarity search
- Integrates naturally with eCommerce domain

### Option 2: Customer Behavior Clustering 🥈
**Concept**: Segment customers by purchasing patterns
**Implementation**:
- Generate customer vectors (order history, preferences, behavior)
- Cluster customers using vector similarity
- Personalized product recommendations per cluster
- Anomaly detection (unusual purchase patterns)

**Demo Value**:
- Shows segmentation
- Demonstrates clustering
- Business intelligence angle

### Option 3: Fraud Detection via Event Patterns 🥉
**Concept**: Detect fraudulent order patterns
**Implementation**:
- Embed order event sequences as vectors
- Known fraud patterns stored in Vector Store
- Query for similar patterns on new orders
- Alert on suspicious similarity

**Demo Value**:
- High business value (fraud prevention)
- Shows event pattern recognition
- Security angle

### Option 4: Semantic Search over Orders
**Concept**: Natural language search over order history
**Implementation**:
- Embed order descriptions as vectors
- User searches with natural language ("my blue widget order")
- Vector similarity search returns matching orders
- Better than keyword search

**Demo Value**:
- Shows semantic understanding
- User-friendly feature
- Search UX improvement

**Recommendation**: Start with **Option 1** (Product Recommendations) - clearest value, easiest to demonstrate.

---

## MCP Server Integration Design

### MCP Tools to Expose (Phase 3)

```python
# MCP Server for Hazelcast Microservices Demo

tools = [
    {
        "name": "query_materialized_view",
        "description": "Query any materialized view (customer-view, product-availability-view, enriched-order-view)",
        "parameters": {
            "view_name": "string",
            "key": "string (optional)",
            "filter": "object (optional)"
        }
    },
    {
        "name": "submit_event",
        "description": "Submit a domain event to the system",
        "parameters": {
            "event_type": "string (CustomerCreated, OrderCreated, etc.)",
            "payload": "object"
        }
    },
    {
        "name": "get_event_history",
        "description": "Query EventStore for event history",
        "parameters": {
            "aggregate_id": "string (optional)",
            "aggregate_type": "string (optional)",
            "event_type": "string (optional)",
            "from_timestamp": "ISO-8601 (optional)",
            "to_timestamp": "ISO-8601 (optional)"
        }
    },
    {
        "name": "replay_events",
        "description": "Trigger event replay to rebuild materialized views",
        "parameters": {
            "view_name": "string (optional - all if not specified)",
            "from_checkpoint": "ISO-8601 (optional)"
        }
    },
    {
        "name": "get_system_metrics",
        "description": "Retrieve current system metrics (TPS, event latency, view staleness)",
        "parameters": {
            "metric_type": "string (optional)"
        }
    },
    {
        "name": "inspect_saga",
        "description": "View saga state and progress",
        "parameters": {
            "saga_id": "string"
        }
    }
]
```

**Demo Scenario with MCP**:
```
User: "Show me all pending orders"
Claude: [calls query_materialized_view with view_name="enriched-order-view", filter={status: "PENDING"}]
Claude: "You have 3 pending orders: ..."

User: "Create a test order for customer John Doe"
Claude: [calls query_materialized_view to find John's customer ID]
Claude: [calls submit_event with OrderCreated event]
Claude: "Order created successfully: order-12345"

User: "What events happened for that order?"
Claude: [calls get_event_history with aggregate_id="order-12345"]
Claude: "Here's the event history: OrderCreated, StockReserved, OrderConfirmed"
```

---

## Open Source Compliance Checklist

### Phase 1 (Must be 100% Open Source)
- [ ] Hazelcast Community Edition 5.x
- [ ] PostgreSQL (optional for event store)
- [ ] Spring Boot
- [ ] Docker / Docker Compose
- [ ] JUnit 5
- [ ] Mockito
- [ ] Testcontainers

### Phase 2 (Must be 100% Open Source)
- [ ] Prometheus
- [ ] Grafana
- [ ] OpenTelemetry
- [ ] Jaeger or Zipkin

### Phase 3 (AI Features - OPTIONAL)
- [ ] Hazelcast Vector Store (check license)
- [ ] Embedding model (open source options: sentence-transformers, etc.)
- [ ] Mark AI features as OPTIONAL in docs

### Phase 4-5 (Cloud - OPTIONAL)
- [ ] Kubernetes
- [ ] Helm
- [ ] Cloud deployment is optional, not required

---

## Updated Timeline Estimate

### Phase 1: Event Sourcing Foundation (4-5 weeks)
- Week 1-2: Core framework (handleEvent, Jet pipeline, abstractions)
- Week 3: Three services with event sourcing
- Week 4: Tests, documentation, demo scenarios
- Week 5: Blog post 1-3 drafts

### Phase 2: Observability (3-4 weeks)
- Week 6-7: Metrics, tracing, dashboards
- Week 8: Microbenchmarking, alerts
- Week 9: Blog post 4 draft

### Phase 3: Resilience & AI (4-5 weeks)
- Week 10-11: Saga pattern, outbox, circuit breaker
- Week 12: Vector Store integration (product recommendations)
- Week 13: MCP server integration
- Week 14: Blog posts 5-6 drafts

### Phase 4: Multi-Domain (3-4 weeks)
- Week 15-17: Alternative domain implementation
- Week 18: Blog post series finalization

### Phase 5: Production Readiness (Optional, 3-4 weeks)
- Kubernetes deployment
- Security hardening
- CI/CD pipeline
- Blog post 7 draft

**Total**: 17-22 weeks for Phases 1-4

---

## Critical Questions for Next Steps

1. **Your Existing Code**:
   - Can you share your current implementation?
   - Which parts work well? Which need refactoring?
   - Do you have tests already?

2. **EventStore Strategy**:
   - Phase 1: Pure Hazelcast IMap (simple, not durable)
   - Phase 1: Hybrid Hazelcast + Postgres (more complex, durable)
   - Which do you prefer?

3. **Vector Store Use Case**:
   - Product recommendations (my top choice)?
   - Customer segmentation?
   - Fraud detection?
   - Semantic search?

4. **Blog Post Timing**:
   - Write as you develop (concurrent)?
   - Write after features complete (sequential)?
   - Target publication venue?

5. **MCP Priority**:
   - High priority (Phase 3)?
   - Lower priority (Phase 4)?
   - Skip entirely?

6. **Starting Point**:
   - Refactor your existing code as Phase 1 base?
   - Start fresh with your architecture as blueprint?

Ready to dive into Phase 1 detailed design incorporating your event sourcing architecture?
