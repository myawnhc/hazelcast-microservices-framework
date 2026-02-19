# Docker Deployment

This directory contains Docker configuration for running the complete Hazelcast Microservices Framework stack.

## Architecture

```
┌───────────────────────────────────────────────────────────────────────┐
│                     Docker Network (ecommerce-network)                 │
├───────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                │
│  │ hazelcast-1  │  │ hazelcast-2  │  │ hazelcast-3  │                │
│  │   :5701      │  │   :5702      │  │   :5703      │                │
│  └──────────────┘  └──────────────┘  └──────────────┘                │
│         │                 │                 │                          │
│         └─────────────────┴─────────────────┘                          │
│                           │                                            │
│      ┌────────────────────┼────────────────────┐                      │
│      │          ┌─────────┴─────────┐          │                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐                │
│  │ account  │ │inventory │ │  order   │ │ payment  │                │
│  │ service  │ │ service  │ │ service  │ │ service  │                │
│  │  :8081   │ │  :8082   │ │  :8083   │ │  :8084   │                │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘                │
│                                                                        │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐                │
│  │prometheus│ │  grafana │ │  jaeger  │ │mgmt-centr│                │
│  │  :9090   │ │  :3000   │ │ :16686   │ │  :8080   │                │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘                │
│                                                                        │
└───────────────────────────────────────────────────────────────────────┘
```

## Prerequisites

- Docker Desktop 4.x+
- Docker Compose v2+
- ~4GB available RAM (total stack usage)
- Maven 3.8+ (for building)
- Java 17+ (for building)

## Quick Start

### Option 1: Using Scripts

```bash
# From project root
./scripts/docker/build.sh   # Build Maven + Docker images
./scripts/docker/start.sh   # Start all services
./scripts/docker/stop.sh    # Stop services
./scripts/docker/stop.sh --clean  # Stop and remove volumes
```

### Option 2: Manual Commands

```bash
# 1. Build the Maven project
mvn clean package -DskipTests

# 2. Build Docker images
cd docker
docker-compose build

# 3. Start all services
docker-compose up -d

# 4. View logs
docker-compose logs -f

# 5. Stop services
docker-compose down
```

## Demo & Sample Data

After starting the services, you can load sample data and run interactive demo scenarios:

```bash
# Load sample customers and products
./scripts/load-sample-data.sh

# Run interactive demo scenarios
./scripts/demo-scenarios.sh

# Run a specific scenario (1, 2, 3, 4, or all)
./scripts/demo-scenarios.sh 1      # Happy path order flow
./scripts/demo-scenarios.sh 2      # Order cancellation
./scripts/demo-scenarios.sh 3      # View rebuilding concepts
./scripts/demo-scenarios.sh 4      # Similar products (vector store)
./scripts/demo-scenarios.sh all    # All scenarios
```

### Demo Scenarios

1. **Happy Path** - Complete order lifecycle: create customer, create products, place order, reserve stock, confirm order, query enriched views
2. **Order Cancellation** - Create order, reserve stock, cancel order, release stock, verify inventory restored
3. **View Rebuilding** - Conceptual demonstration of event sourcing and view reconstruction
4. **Similar Products** - Vector store similarity search; shows Enterprise results or Community Edition graceful fallback

For detailed walkthrough instructions, see [docs/demo/demo-walkthrough.md](../docs/demo/demo-walkthrough.md).

## Services

| Service | Port | Description | Health Check |
|---------|------|-------------|--------------|
| account-service | 8081 | Customer management | http://localhost:8081/actuator/health |
| inventory-service | 8082 | Product/stock management | http://localhost:8082/actuator/health |
| order-service | 8083 | Order management | http://localhost:8083/actuator/health |
| payment-service | 8084 | Payment processing | http://localhost:8084/actuator/health |
| hazelcast-1 | 5701 | Hazelcast cluster node 1 | http://localhost:5701/hazelcast/health |
| hazelcast-2 | 5702 | Hazelcast cluster node 2 | http://localhost:5702/hazelcast/health |
| hazelcast-3 | 5703 | Hazelcast cluster node 3 | http://localhost:5703/hazelcast/health |
| management-center | 8080 | Hazelcast cluster monitoring UI | http://localhost:8080 |
| grafana | 3000 | Metrics dashboards | http://localhost:3000 |
| prometheus | 9090 | Metrics collection | http://localhost:9090 |
| jaeger | 16686 | Distributed tracing UI | http://localhost:16686 |

## API Examples

### Create a Customer
```bash
curl -X POST http://localhost:8081/api/customers \
  -H "Content-Type: application/json" \
  -d '{
    "email": "john@example.com",
    "name": "John Doe",
    "address": "123 Main St",
    "phone": "555-1234"
  }'
```

### Create a Product
```bash
curl -X POST http://localhost:8082/api/products \
  -H "Content-Type: application/json" \
  -d '{
    "sku": "LAPTOP-001",
    "name": "Gaming Laptop",
    "description": "High-performance gaming laptop",
    "price": 999.99,
    "quantityOnHand": 100,
    "category": "Electronics"
  }'
```

### Create an Order
```bash
curl -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "<customer-id>",
    "lineItems": [
      {
        "productId": "<product-id>",
        "quantity": 1,
        "unitPrice": 999.99
      }
    ],
    "shippingAddress": "123 Main St"
  }'
```

### Reserve Stock
```bash
curl -X POST http://localhost:8082/api/products/<product-id>/stock/reserve \
  -H "Content-Type: application/json" \
  -d '{"quantity": 1, "orderId": "<order-id>"}'
```

### Confirm Order
```bash
curl -X PATCH http://localhost:8083/api/orders/<order-id>/confirm
```

### Query Enriched Order
```bash
curl http://localhost:8083/api/orders/<order-id>
```

## Memory Usage

The stack is optimized to run on a laptop with 8GB RAM:

| Component | Memory Limit |
|-----------|-------------|
| hazelcast-1 | 512 MB |
| hazelcast-2 | 512 MB |
| hazelcast-3 | 512 MB |
| account-service | 512 MB |
| inventory-service | 512 MB |
| order-service | 512 MB |
| payment-service | 512 MB |
| management-center | 256 MB |
| grafana | 256 MB |
| prometheus | 256 MB |
| jaeger | 256 MB |
| **Total** | **~4.75 GB** |

## Monitoring

### Hazelcast Management Center

Access at http://localhost:8080

Management Center auto-connects to the `ecommerce-cluster` on startup. It provides:
- Cluster member status and health
- Map browser (view PENDING, ES, VIEW, and COMPLETIONS maps)
- Jet job monitoring (event sourcing pipelines)
- Memory and partition distribution
- Real-time cluster metrics

### Prometheus Metrics

Access Prometheus at http://localhost:9090

Useful queries:
- `jvm_memory_used_bytes` - JVM memory usage
- `http_server_requests_seconds_count` - HTTP request counts
- `events_submitted_total` - Event sourcing metrics

### Jaeger Tracing

Access Jaeger UI at http://localhost:16686

View distributed traces for event processing and saga flows across services.

### Service Health

Check all services:
```bash
for port in 8081 8082 8083 8084; do
  echo "Port $port: $(curl -s http://localhost:$port/actuator/health | jq -r .status)"
done
```

### Hazelcast Cluster Status

```bash
curl -s http://localhost:5701/hazelcast/rest/cluster | jq
```

## Troubleshooting

### Services won't start
1. Ensure Docker has enough memory allocated (4GB minimum)
2. Check if ports are already in use
3. View logs: `docker-compose logs <service-name>`

### Hazelcast cluster not forming
1. Verify all 3 nodes are running: `docker-compose ps`
2. Check Hazelcast logs: `docker-compose logs hazelcast-1`
3. Verify network connectivity between containers

### Out of memory errors
1. Increase Docker Desktop memory allocation
2. Reduce memory limits in docker-compose.yml
3. Run fewer Hazelcast nodes (minimum 1)

## Files

```
docker/
├── docker-compose.yml           # Main orchestration file
├── hazelcast/
│   └── hazelcast-docker.yaml    # Hazelcast cluster configuration
├── prometheus/
│   └── prometheus.yml           # Prometheus scrape configuration (4 services + 3 HZ nodes)
├── grafana/
│   ├── dashboards/
│   │   ├── system-overview.json     # Home dashboard
│   │   ├── event-flow.json          # Event sourcing metrics
│   │   ├── materialized-views.json  # View update metrics
│   │   └── saga-dashboard.json      # Saga lifecycle metrics
│   └── provisioning/
│       ├── datasources/
│       │   └── datasources.yml      # Prometheus datasource
│       ├── dashboards/
│       │   └── dashboards.yml       # Dashboard auto-loading config
│       └── alerting/
│           ├── alerts.yml           # Alert rule definitions
│           ├── contactpoints.yml    # Notification channels
│           └── policies.yml         # Alert routing policies
└── README.md                    # This file
```

Management Center and Jaeger are configured via environment variables in docker-compose.yml (no separate config files needed).

## Customization

### Running with fewer Hazelcast nodes

Edit `docker-compose.yml` and comment out `hazelcast-2` and `hazelcast-3`. Update the TCP/IP member list accordingly.

### Grafana Dashboards

Grafana is included in the stack at http://localhost:3000 (login: `admin`/`admin`). Four dashboards are pre-provisioned and load automatically. See the [Dashboard Setup Guide](../docs/guides/dashboard-setup-guide.md) for details on dashboards, alerts, and custom configuration.

### Enabling TLS (requires Hazelcast Enterprise)

See Hazelcast documentation for TLS configuration.
