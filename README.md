# Hazelcast Microservices Framework

A production-ready event sourcing framework built on Hazelcast, with a complete eCommerce demo showcasing the distributed systems patterns that make microservices actually work.

## Overview

Splitting a monolith into separate deployables doesn't make a microservices architecture. The hard problems — keeping services consistent without shared transactions, staying up when dependencies go down, understanding behavior across service boundaries — require specific patterns and infrastructure. This framework implements those patterns on Hazelcast and Spring Boot, with a four-service eCommerce demo (Account, Inventory, Order, Payment) that exercises every one of them end-to-end.

### Event Sourcing & Stream Processing

Events are the source of truth, not database rows. Every state change is captured as an immutable event, enabling full audit trails, temporal queries, and replay.

- **Event Store**: Append-only event log backed by Hazelcast IMap with event journal
- **Materialized Views**: Query-optimized projections rebuilt from events, delivering 100,000+ ops/sec at sub-millisecond latency
- **Jet Pipelines**: Hazelcast Jet processes events in real time — persisting to the event store, updating materialized views, and publishing to the event bus in a single pipeline
- **Event-Driven Communication**: Cross-service events flow through Hazelcast ITopic, decoupling services without message broker infrastructure

### Distributed Transactions

Microservices can't share a database transaction. Sagas coordinate multi-service operations with automatic compensation when something fails.

- **Choreographed Sagas**: Event-driven coordination across all four services with automatic timeout detection and compensation — no central coordinator, services react to events
- **Orchestrated Sagas**: Central `SagaOrchestrator` drives steps sequentially via HTTP with per-step timeout, retry, and automatic reverse-order compensation — explicit control flow for complex workflows
- Choose the right pattern for each use case, or run both side-by-side ([Saga Pattern Guide](docs/guides/saga-pattern-guide.md))

### Resilience & Reliability

In a distributed system, partial failure is normal. The framework ensures no event is lost and no failure goes unhandled.

- **Circuit Breakers**: Resilience4j circuit breakers with retry and exponential backoff on all saga listeners — fail fast when a downstream service is unhealthy
- **Transactional Outbox**: Guaranteed at-least-once event delivery — events are written atomically with state changes, then published asynchronously
- **Dead Letter Queue**: Failed events land in a DLQ with admin REST endpoints for inspection, replay, and discard
- **Idempotency Guards**: Exactly-once processing semantics prevent duplicate side effects when events are retried

### Security

Three independent layers, all opt-in and backward compatible — security is never in the way during development but ready for production.

- **JWT Authentication**: OAuth2 Resource Server on the API Gateway and all services, with configurable public paths and custom `SecurityFilterChain` override support
- **Service-to-Service Authentication**: HMAC-SHA256 signing of ITopic saga events via `EventAuthenticator` — consuming services verify message integrity and origin
- **MCP API Key RBAC**: API key authentication with role-based tool access control (VIEWER / OPERATOR / ADMIN) for the MCP server, supporting both stdio and HTTP/SSE modes
- [Security Guide](docs/guides/security-guide.md)

### Observability

You can't fix what you can't see. Every layer of the framework is instrumented and pre-wired to dashboards.

- **Metrics**: Micrometer + Prometheus scraping across all services and Hazelcast nodes, with per-step saga duration metrics (`saga.step.duration`) and Grafana panels comparing choreography vs orchestration side-by-side
- **Dashboards**: Pre-provisioned Grafana dashboards for System Overview, Event Flow, Materialized Views, and Sagas — ready out of the box
- **Distributed Tracing**: Jaeger via OTLP for end-to-end request tracing across service boundaries
- [Dashboard Setup Guide](docs/guides/dashboard-setup-guide.md)

### API Gateway

A single entry point that handles the cross-cutting concerns services shouldn't implement individually.

- Path-based routing to all four microservices with per-route circuit breakers
- Hazelcast-backed per-IP rate limiting (shared state across gateway instances)
- Correlation ID propagation, CORS, aggregated health checks, and consistent JSON error responses
- [API Gateway Guide](api-gateway/README.md)

### AI Integration

An AI assistant can query, inspect, and operate the entire system through the Model Context Protocol.

- **MCP Server**: 7 tools for querying materialized views, submitting events, inspecting saga state, checking metrics, and running demo scenarios
- **Zero-config with Claude Code**: `.mcp.json` in the repo root auto-registers the MCP server — just `docker compose up` and Claude Code discovers it on first launch
- Supports stdio transport (local AI assistants) and HTTP/SSE (networked deployments)
- [MCP Server Guide](mcp-server/README.md) | [Example Conversations](docs/guides/mcp-examples.md)

### Durable Persistence

In-memory data survives restarts. Write-behind MapStores asynchronously persist events and views to PostgreSQL without slowing the hot path.

- **Write-Behind MapStore**: Events and views are batched and flushed to PostgreSQL asynchronously — zero-latency impact on the event pipeline
- **Automatic Cold Start**: Materialized views reload from the database on service restart via MapLoader (EAGER mode)
- **Bounded Memory**: IMap eviction keeps entries within configurable limits; evicted entries are reloaded on demand from the database
- **Provider-Agnostic**: `EventStorePersistence` and `ViewStorePersistence` interfaces — swap PostgreSQL for MySQL, CockroachDB, or any JDBC-compatible database
- [Persistence Guide](docs/guides/persistence-guide.md)

### Enterprise Extensions

The framework runs fully on Hazelcast Community Edition. Enterprise features are optional enhancements with automatic detection and graceful fallback.

- **Vector Store**: Product similarity search using Hazelcast VectorCollection with HNSW indexing (Enterprise) or IMap-based cosine similarity (Community fallback)
- **Edition Detection**: `@ConditionalOnEnterpriseFeature` and `@ConditionalOnCommunityFallback` annotations for conditional bean wiring — write once, run on either edition

## Architecture

```
┌──────────────────────┐  ┌─────────────────────────────────────────────────┐
│  AI Assistant        │  │              Client Applications                │
│  (Claude, etc.)      │  └─────────────────────────────────────────────────┘
└──────────┬───────────┘                       │
           │ MCP                               ▼
           ▼                        ┌─────────────────────┐
   ┌──────────────┐                 │    API Gateway      │
   │  MCP Server  │────────────────▶│      :8080          │
   │   :8085      │                 │  Rate Limit ● CORS  │
   └──────────────┘                 │  Circuit Breaker    │
                                    └─────────┬───────────┘
                       ┌────────┬─────────────┼──────────┬────────┐
                       ▼        ▼             ▼          ▼        ▼
                 ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
                 │ Account  │ │Inventory │ │  Order   │ │ Payment  │
                 │  :8081   │ │  :8082   │ │  :8083   │ │  :8084   │
                 └──────────┘ └──────────┘ └──────────┘ └──────────┘
                      │            │             │            │
                      └────────────┴──────┬──────┴────────────┘
                                          │
                      ┌───────────────────┴───────────────────┐
                      │          Hazelcast Cluster            │
                      │  ┌─────────┐ ┌─────────┐ ┌────────┐  │
                      │  │  Node 1 │ │  Node 2 │ │ Node 3 │  │
                      │  │  :5701  │ │  :5702  │ │  :5703 │  │
                      │  └─────────┘ └─────────┘ └────────┘  │
                      │                                      │
                      │  ┌──────────────────────────────┐    │
                      │  │ Event Stores (IMap)          │    │
                      │  │ Materialized Views (IMap)    │    │
                      │  │ Event Bus (ITopic)           │    │
                      │  │ Jet Pipelines                │    │
                      │  └──────────────────────────────┘    │
                      └──────────────────────────────────────┘
          ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
          │  Prometheus  │  │   Grafana    │  │    Jaeger    │
          │   :9090      │  │   :3000      │  │   :16686     │
          └──────────────┘  └──────────────┘  └──────────────┘
```

### Data Flow & Representation

Data changes format as it moves through the architecture. DTOs live at the REST boundary, domain POJOs in the service layer, and GenericRecord everywhere inside the framework — the pipeline, event store, view store, ITopic, and outbox never touch Java domain objects.

```
Write path:  JSON → DTO → DomainEvent POJO ──[.toGenericRecord()]──→ GenericRecord
                                                                         │
                    ┌────────────────────────────────────────────────────┘
                    ▼
             Pending Map → Jet Pipeline → Event Store (event schema)
                                        → View Store  (domain schema)
                                        → ITopic / Outbox (event schema)

Read path:   View Store (GenericRecord) ──[.fromGenericRecord()]──→ Domain POJO → DTO → JSON
```

| Layer | Format | Example | Notes |
|-------|--------|---------|-------|
| HTTP request/response | JSON | `{"email":"...","name":"..."}` | Jackson serialization |
| REST controller | DTO | `CustomerDTO` | Jakarta Validation, mutable POJO |
| Service layer | DomainEvent | `CustomerCreatedEvent` | Extends `DomainEvent<D,K>`, Java POJO |
| *Serialization boundary* | `.toGenericRecord()` | | Event POJO → GenericRecord |
| Pending map, Event store | GenericRecord | Schema: `"CustomerCreatedEvent"` | Event schema with all metadata fields |
| Jet pipeline (6 stages) | GenericRecord | Wrapped in `EventContext<K>` | Never deserialized back to Java |
| Cross-service ITopic | GenericRecord | Schema: `"OrderCreatedEvent"` | Saga listeners read fields directly |
| Outbox | GenericRecord | Schema: `"OutboxEntry"` | Nested event GenericRecord inside |
| Materialized view store | GenericRecord | Schema: `"Customer"` | Domain schema, not event schema |
| *Deserialization boundary* | `.fromGenericRecord()` | | GenericRecord → Domain POJO |
| Service read path | DomainObject | `Customer` | Implements `DomainObject<K>` |
| REST response | DTO | `CustomerDTO` | Via `.toDTO()` on domain object |
| Event history endpoint | `Map<String,Object>` | Via `GenericRecordConverter.toMap()` | No Java event class needed |

**Key points:**

- **GenericRecord dominates the interior.** Everything between the two serialization boundaries is GenericRecord. The pipeline, stores, ITopic, and outbox never instantiate Java domain objects.
- **Two schema families.** Event schemas (`"CustomerCreatedEvent"`, `"OrderCreatedEvent"`) carry the full event payload including metadata. Domain schemas (`"Customer"`, `"Order"`) represent current materialized state.
- **ViewUpdater bridges schemas.** `applyEvent(eventRecord, currentState)` reads from an event-schema GenericRecord and produces a domain-schema GenericRecord.
- **Type mappings.** `Instant` → `int64` epoch millis, `BigDecimal` → `String`, nested objects → `GenericRecord[]` (e.g., `OrderLineItem` array in `OrderCreatedEvent`).
- **Saga listeners work purely with GenericRecord** — they call `record.getString("key")` and `record.getArrayOfGenericRecord("lineItems")` directly, never reconstituting Java event objects.

## Modules

| Module | Description |
|--------|-------------|
| [framework-core](framework-core/README.md) | Domain-agnostic event sourcing framework |
| [framework-postgres](framework-postgres/) | PostgreSQL persistence provider (write-behind MapStore) |
| [ecommerce-common](ecommerce-common/README.md) | Shared domain objects, events, and DTOs |
| [account-service](account-service/README.md) | Customer account management |
| [inventory-service](inventory-service/README.md) | Product catalog and stock management |
| [order-service](order-service/README.md) | Order lifecycle management |
| [payment-service](payment-service/README.md) | Payment processing and refunds |
| [api-gateway](api-gateway/README.md) | API Gateway (routing, rate limiting, circuit breakers) |
| [mcp-server](mcp-server/README.md) | AI assistant integration (MCP) |

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.8+
- Docker & Docker Compose

### Run with Docker (Recommended)

```bash
# Clone the repository
git clone https://github.com/myawnhc/hazelcast-microservices-framework.git
cd hazelcast-microservices-framework

# Build the project
mvn clean package -DskipTests

# Start all services
./scripts/docker/start.sh

# Load sample data
./scripts/load-sample-data.sh

# Run demo scenarios
./scripts/demo-scenarios.sh
```

### Verify Services

```bash
# Check gateway health (aggregates all downstream services)
curl http://localhost:8080/actuator/health  # API Gateway

# Or check individual services directly
curl http://localhost:8081/actuator/health  # Account Service
curl http://localhost:8082/actuator/health  # Inventory Service
curl http://localhost:8083/actuator/health  # Order Service
curl http://localhost:8084/actuator/health  # Payment Service
```

### API Documentation

Once running, access Swagger UI:
- Account Service: http://localhost:8081/swagger-ui.html
- Inventory Service: http://localhost:8082/swagger-ui.html
- Order Service: http://localhost:8083/swagger-ui.html
- Payment Service: http://localhost:8084/swagger-ui.html

## API Examples

### Create a Customer

```bash
curl -X POST http://localhost:8081/api/customers \
  -H "Content-Type: application/json" \
  -d '{
    "email": "john@example.com",
    "name": "John Doe",
    "address": "123 Main St"
  }'
```

### Create a Product

```bash
curl -X POST http://localhost:8082/api/products \
  -H "Content-Type: application/json" \
  -d '{
    "sku": "LAPTOP-001",
    "name": "Gaming Laptop",
    "price": 999.99,
    "quantityOnHand": 100
  }'
```

### Place an Order

```bash
curl -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "<customer-id>",
    "lineItems": [
      {"productId": "<product-id>", "quantity": 1, "unitPrice": 999.99}
    ],
    "shippingAddress": "123 Main St"
  }'
```

### Process a Payment

```bash
curl -X POST http://localhost:8084/api/payments \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "<order-id>",
    "customerId": "<customer-id>",
    "amount": 999.99,
    "currency": "USD",
    "method": "CREDIT_CARD"
  }'
```

### Refund a Payment

```bash
curl -X POST http://localhost:8084/api/payments/<payment-id>/refund \
  -H "Content-Type: application/json" \
  -d '{"reason": "Customer requested cancellation"}'
```

### Find Similar Products (Enterprise)

```bash
curl http://localhost:8082/api/products/<product-id>/similar?limit=5
```

## Documentation

- [Setup Guide](docs/SETUP.md) - Complete installation instructions
- [Docker Deployment](docker/README.md) - Docker Compose configuration
- [Architecture](docs/architecture/) - System design and patterns
- [Demo Walkthrough](docs/demo/demo-walkthrough.md) - Step-by-step demo guide
- [Saga Pattern Guide](docs/guides/saga-pattern-guide.md) - Choreographed and orchestrated saga patterns
- [API Gateway](api-gateway/README.md) - Routing, rate limiting, circuit breakers, and CORS
- [Dashboard Setup Guide](docs/guides/dashboard-setup-guide.md) - Grafana, Prometheus, and Jaeger setup
- [MCP Server Guide](mcp-server/README.md) - AI assistant integration via Model Context Protocol
- [MCP Examples](docs/guides/mcp-examples.md) - Example AI assistant conversations
- [Persistence Guide](docs/guides/persistence-guide.md) - Write-behind MapStore, PostgreSQL, eviction, and custom providers
- [Security Guide](docs/guides/security-guide.md) - JWT, service-to-service, and MCP API key authentication

## Event Sourcing Flow

```
1. Client Request
       │
       ▼
2. REST Controller
       │
       ▼
3. Service Layer
       │
       ▼
4. EventSourcingController.handleEvent()
       │
       ├──► 5a. Write to Pending Events Map
       │           │
       │           ▼
       │    5b. Jet Pipeline Triggered
       │           │
       │           ├──► 6a. Persist to Event Store
       │           │
       │           ├──► 6b. Update Materialized View
       │           │
       │           └──► 6c. Publish to Event Bus
       │
       ▼
7. Return Response to Client
```

## Performance

### Materialized View Layer (in-memory)

The materialized view layer — where Hazelcast IMap operations power query-optimized projections — achieves the highest throughput since it bypasses HTTP and pipeline overhead:

| Metric | Result |
|--------|--------|
| View update throughput | 100,000+ ops/sec |
| P50 Latency | < 0.1 ms |
| P99 Latency | < 1 ms |
| Test Duration | 10 seconds |
| Total Operations | 967,939 |

This benchmark (`LoadTest.java`) measures `OrderViewUpdater.update()` directly within the JVM — the same code path that Jet pipelines invoke to maintain materialized views.

```bash
mvn test -Dtest=LoadTest -pl order-service
```

### End-to-End (HTTP through full pipeline)

The end-to-end path — HTTP request through REST controller, event store write, Jet pipeline, materialized view update, and ITopic publish — is naturally slower:

| Metric | Result |
|--------|--------|
| End-to-end throughput | ~300 TPS (curl/bash) |
| Workload mix | 60% orders, 25% stock reserves, 15% customer creates |
| Concurrency | 10 workers |

The bundled `load-test.sh` script uses `curl` in bash subshells, which adds significant overhead from process forking and TCP connection setup per request. A purpose-built load testing tool (e.g., `wrk`, `k6`, or `Gatling`) with connection pooling and efficient I/O would produce substantially higher numbers for the same end-to-end path.

```bash
./scripts/load-test.sh --duration 30 --concurrency 10
```

## Technology Stack

| Component | Technology | Version |
|-----------|------------|---------|
| Runtime | Java | 17+ |
| Framework | Spring Boot | 3.2.x |
| Data Grid | Hazelcast | 5.6.0 |
| Build | Maven | 3.8+ |
| Database | PostgreSQL | 16+ |
| Containers | Docker | 20.10+ |
| Metrics | Micrometer/Prometheus | - |
| Dashboards | Grafana | 10.3.x |
| Tracing | Jaeger (OTLP) | - |
| API Gateway | Spring Cloud Gateway | 2023.0.x |
| AI Integration | Spring AI MCP Server | 1.0.0 |
| API Docs | OpenAPI/Swagger | 3.0 |

## Project Structure

```
hazelcast-microservices-framework/
├── framework-core/          # Core framework (event sourcing, sagas, edition detection, vector store)
├── framework-postgres/      # PostgreSQL persistence provider
├── ecommerce-common/        # Shared domain objects, events, DTOs
├── account-service/         # Customer service (:8081)
├── inventory-service/       # Product service (:8082)
├── order-service/           # Order service (:8083)
├── payment-service/         # Payment service (:8084)
├── api-gateway/             # Spring Cloud Gateway (:8080)
├── mcp-server/              # AI assistant MCP server (:8085)
├── docker/                  # Docker Compose, Grafana dashboards, Prometheus config
├── scripts/                 # Build, start, stop, demo scripts
├── docs/                    # Documentation
│   ├── architecture/        # ADRs and design documents
│   ├── guides/              # Saga pattern guide, dashboard setup guide
│   ├── demo/                # Demo walkthrough
│   └── SETUP.md
└── pom.xml                  # Parent POM
```

## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [Hazelcast](https://hazelcast.com/) - In-memory data grid and stream processing
- [Spring Boot](https://spring.io/projects/spring-boot) - Application framework
- [Claude Code](https://claude.ai/) - AI-assisted development
