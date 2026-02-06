# Hazelcast Microservices Framework

An event sourcing microservices framework built on Hazelcast, demonstrating modern distributed systems patterns.

## Overview

This project provides a production-ready event sourcing framework using Hazelcast for distributed data storage and stream processing. It includes a complete eCommerce demo with four microservices showcasing the framework's capabilities.

### Key Features

- **Event Sourcing**: Immutable event log as the source of truth
- **Materialized Views**: Fast query-optimized projections using Hazelcast IMap
- **Event-Driven Architecture**: Cross-service communication via Hazelcast topics
- **Stream Processing**: Hazelcast Jet pipelines for real-time event processing
- **High Performance**: 100,000+ operations per second
- **Saga Support**: Built-in distributed transaction patterns
- **Observability**: Prometheus metrics and structured logging

### Phase 2 Features

- **Choreographed Sagas**: Order fulfillment saga coordinating across all four services — Order, Inventory, Payment, and Account — with automatic timeout detection and compensation ([Saga Pattern Guide](docs/guides/saga-pattern-guide.md))
- **Payment Service**: Fourth microservice handling payment processing and refunds, integrated into the saga flow
- **Vector Store** (Enterprise): Product similarity search using Hazelcast IMap-based cosine similarity; Community Edition falls back gracefully with empty results
- **Observability Stack**: Pre-provisioned Grafana dashboards (System Overview, Event Flow, Materialized Views, Sagas), Prometheus metrics scraping across all services and Hazelcast nodes, Jaeger distributed tracing via OTLP ([Dashboard Setup Guide](docs/guides/dashboard-setup-guide.md))
- **Edition Detection**: Automatic Community/Enterprise edition detection with `@ConditionalOnEnterpriseFeature` and `@ConditionalOnCommunityFallback` annotations for conditional bean wiring

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Client Applications                             │
└─────────────────────────────────────────────────────────────────────────────┘
                                       │
              ┌────────────────────────┼────────────────────────┐
              │              ┌─────────┴─────────┐              │
              ▼              ▼                   ▼              ▼
      ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
      │   Account    │ │  Inventory   │ │    Order     │ │   Payment    │
      │   Service    │ │   Service    │ │   Service    │ │   Service    │
      │   :8081      │ │   :8082      │ │   :8083      │ │   :8084      │
      └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘
              │              │                   │              │
              └──────────────┴─────────┬─────────┴──────────────┘
                                       │
                    ┌──────────────────┴──────────────────┐
                    │         Hazelcast Cluster           │
                    │  ┌─────────┐ ┌─────────┐ ┌────────┐ │
                    │  │  Node 1 │ │  Node 2 │ │ Node 3 │ │
                    │  │  :5701  │ │  :5702  │ │  :5703 │ │
                    │  └─────────┘ └─────────┘ └────────┘ │
                    │                                     │
                    │  ┌─────────────────────────────┐   │
                    │  │ Event Stores (IMap)         │   │
                    │  │ Materialized Views (IMap)   │   │
                    │  │ Event Bus (ITopic)          │   │
                    │  │ Jet Pipelines               │   │
                    │  └─────────────────────────────┘   │
                    └─────────────────────────────────────┘
              ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
              │  Prometheus  │  │   Grafana    │  │    Jaeger    │
              │   :9090      │  │   :3000      │  │   :16686     │
              └──────────────┘  └──────────────┘  └──────────────┘
```

## Modules

| Module | Description |
|--------|-------------|
| [framework-core](framework-core/README.md) | Domain-agnostic event sourcing framework |
| [ecommerce-common](ecommerce-common/README.md) | Shared domain objects, events, and DTOs |
| [account-service](account-service/README.md) | Customer account management |
| [inventory-service](inventory-service/README.md) | Product catalog and stock management |
| [order-service](order-service/README.md) | Order lifecycle management |
| [payment-service](payment-service/README.md) | Payment processing and refunds |

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.8+
- Docker & Docker Compose

### Run with Docker (Recommended)

```bash
# Clone the repository
git clone https://github.com/theyawns/hazelcast-microservices-framework.git
cd hazelcast-microservices-framework

# Build the project
mvn clean package -DskipTests

# Start all services
./scripts/start-docker.sh

# Load sample data
./scripts/load-sample-data.sh

# Run demo scenarios
./scripts/demo-scenarios.sh
```

### Verify Services

```bash
# Check health
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
- [Saga Pattern Guide](docs/guides/saga-pattern-guide.md) - Choreographed saga implementation
- [Dashboard Setup Guide](docs/guides/dashboard-setup-guide.md) - Grafana, Prometheus, and Jaeger setup

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

Load testing results demonstrate the framework's capabilities:

| Metric | Result |
|--------|--------|
| Throughput | 100,000+ TPS |
| P50 Latency | < 0.1 ms |
| P99 Latency | < 1 ms |
| Test Duration | 10 seconds |
| Total Operations | 967,939 |

Run load tests:
```bash
mvn test -Dtest=LoadTest -pl order-service
```

## Technology Stack

| Component | Technology | Version |
|-----------|------------|---------|
| Runtime | Java | 17+ |
| Framework | Spring Boot | 3.2.x |
| Data Grid | Hazelcast | 5.6.0 |
| Build | Maven | 3.8+ |
| Containers | Docker | 20.10+ |
| Metrics | Micrometer/Prometheus | - |
| Dashboards | Grafana | 10.3.x |
| Tracing | Jaeger (OTLP) | - |
| API Docs | OpenAPI/Swagger | 3.0 |

## Project Structure

```
hazelcast-microservices-framework/
├── framework-core/          # Core framework (event sourcing, sagas, edition detection, vector store)
├── ecommerce-common/        # Shared domain objects, events, DTOs
├── account-service/         # Customer service (:8081)
├── inventory-service/       # Product service (:8082)
├── order-service/           # Order service (:8083)
├── payment-service/         # Payment service (:8084)
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
