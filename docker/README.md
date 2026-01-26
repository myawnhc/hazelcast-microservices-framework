# Docker Deployment

This directory contains Docker configuration for running the complete Hazelcast Microservices Framework stack.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Docker Network (ecommerce-network)           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │ hazelcast-1  │  │ hazelcast-2  │  │ hazelcast-3  │          │
│  │   :5701      │  │   :5702      │  │   :5703      │          │
│  └──────────────┘  └──────────────┘  └──────────────┘          │
│         │                 │                 │                   │
│         └─────────────────┴─────────────────┘                   │
│                           │                                      │
│         ┌─────────────────┼─────────────────┐                   │
│         │                 │                 │                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │account-service│ │inventory-svc │  │ order-service│          │
│  │   :8081      │  │   :8082      │  │   :8083      │          │
│  └──────────────┘  └──────────────┘  └──────────────┘          │
│                                                                  │
│  ┌──────────────┐                                               │
│  │  prometheus  │                                               │
│  │   :9090      │                                               │
│  └──────────────┘                                               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
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
./scripts/build-docker.sh   # Build Maven + Docker images
./scripts/start-docker.sh   # Start all services
./scripts/stop-docker.sh    # Stop services
./scripts/stop-docker.sh --clean  # Stop and remove volumes
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

## Services

| Service | Port | Description | Health Check |
|---------|------|-------------|--------------|
| account-service | 8081 | Customer management | http://localhost:8081/actuator/health |
| inventory-service | 8082 | Product/stock management | http://localhost:8082/actuator/health |
| order-service | 8083 | Order management | http://localhost:8083/actuator/health |
| hazelcast-1 | 5701 | Hazelcast cluster node 1 | http://localhost:5701/hazelcast/health |
| hazelcast-2 | 5702 | Hazelcast cluster node 2 | http://localhost:5702/hazelcast/health |
| hazelcast-3 | 5703 | Hazelcast cluster node 3 | http://localhost:5703/hazelcast/health |
| prometheus | 9090 | Metrics collection | http://localhost:9090 |

## API Examples

### Create a Customer
```bash
curl -X POST http://localhost:8081/api/customers \
  -H "Content-Type: application/json" \
  -d '{"email":"john@example.com","name":"John Doe","address":"123 Main St"}'
```

### Create a Product
```bash
curl -X POST http://localhost:8082/api/products \
  -H "Content-Type: application/json" \
  -d '{"sku":"LAPTOP-001","name":"Gaming Laptop","price":999.99,"stockQuantity":100}'
```

### Create an Order
```bash
curl -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"<customer-id>","items":[{"productId":"<product-id>","quantity":1}]}'
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
| prometheus | 256 MB |
| **Total** | **~3.5 GB** |

## Monitoring

### Prometheus Metrics

Access Prometheus at http://localhost:9090

Useful queries:
- `jvm_memory_used_bytes` - JVM memory usage
- `http_server_requests_seconds_count` - HTTP request counts
- `events_submitted_total` - Event sourcing metrics

### Service Health

Check all services:
```bash
for port in 8081 8082 8083; do
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
├── docker-compose.yml      # Main orchestration file
├── hazelcast/
│   └── hazelcast.yaml      # Hazelcast cluster configuration
├── prometheus/
│   └── prometheus.yml      # Prometheus scrape configuration
└── README.md               # This file
```

## Customization

### Running with fewer Hazelcast nodes

Edit `docker-compose.yml` and comment out `hazelcast-2` and `hazelcast-3`. Update the TCP/IP member list accordingly.

### Adding Grafana

```yaml
grafana:
  image: grafana/grafana:10.2.0
  ports:
    - "3000:3000"
  volumes:
    - grafana-data:/var/lib/grafana
  depends_on:
    - prometheus
```

### Enabling TLS (requires Hazelcast Enterprise)

See Hazelcast documentation for TLS configuration.
