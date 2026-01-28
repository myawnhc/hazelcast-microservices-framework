# Setup & Installation Guide

Complete guide for setting up and running the Hazelcast Microservices Framework.

## Prerequisites

### Required Software

| Software | Version | Purpose |
|----------|---------|---------|
| Java | 17+ | Runtime and compilation |
| Maven | 3.8+ | Build tool |
| Docker | 20.10+ | Container runtime |
| Docker Compose | 2.0+ | Multi-container orchestration |

### Verify Installation

```bash
# Check Java version
java -version
# Expected: openjdk version "17.x.x" or higher

# Check Maven version
mvn -version
# Expected: Apache Maven 3.8.x or higher

# Check Docker version
docker --version
# Expected: Docker version 20.10.x or higher

# Check Docker Compose version
docker compose version
# Expected: Docker Compose version v2.x.x
```

### System Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| RAM | 4 GB | 8 GB |
| CPU | 2 cores | 4 cores |
| Disk | 5 GB | 10 GB |

## Quick Start

### 1. Clone the Repository

```bash
git clone https://github.com/theyawns/hazelcast-microservices-framework.git
cd hazelcast-microservices-framework
```

### 2. Build the Project

```bash
mvn clean package -DskipTests
```

### 3. Start with Docker Compose

```bash
# Using the convenience script
./scripts/start-docker.sh

# Or directly with docker-compose
cd docker && docker-compose up -d
```

### 4. Verify Services

```bash
# Check all services are running
docker-compose ps

# Check health endpoints
curl http://localhost:8081/actuator/health  # Account Service
curl http://localhost:8082/actuator/health  # Inventory Service
curl http://localhost:8083/actuator/health  # Order Service
```

### 5. Load Sample Data

```bash
./scripts/load-sample-data.sh
```

### 6. Run Demo Scenarios

```bash
./scripts/demo-scenarios.sh
```

## Detailed Installation

### Building from Source

```bash
# Full build with tests
mvn clean install

# Build specific modules
mvn clean package -pl framework-core
mvn clean package -pl ecommerce-common
mvn clean package -pl account-service,inventory-service,order-service -am

# Skip tests for faster builds
mvn clean package -DskipTests
```

### Running Tests

```bash
# All tests
mvn test

# Specific module
mvn test -pl account-service

# With coverage report
mvn test jacoco:report

# Load tests only
mvn test -Dtest=LoadTest -pl order-service
```

## Running Options

### Option 1: Docker Compose (Recommended)

Best for demos and complete environment testing.

```bash
# Start all services
./scripts/start-docker.sh

# View logs
docker-compose logs -f

# Stop services
./scripts/stop-docker.sh

# Stop and clean volumes
./scripts/stop-docker.sh --clean
```

**Services started:**
- 3-node Hazelcast cluster (ports 5701-5703)
- Account Service (port 8081)
- Inventory Service (port 8082)
- Order Service (port 8083)
- Prometheus (port 9090)

### Option 2: Local Development

Best for development and debugging.

#### Start Hazelcast Cluster

```bash
# Option A: Use Docker for Hazelcast only
docker run -d --name hazelcast \
  -p 5701:5701 \
  -e HZ_CLUSTERNAME=ecommerce-demo \
  hazelcast/hazelcast:5.6.0

# Option B: Start embedded Hazelcast (included in services)
# Just start the services - they'll form a cluster automatically
```

#### Start Services

Each service can be started independently:

```bash
# Terminal 1: Account Service
cd account-service
mvn spring-boot:run

# Terminal 2: Inventory Service
cd inventory-service
mvn spring-boot:run

# Terminal 3: Order Service
cd order-service
mvn spring-boot:run
```

Or with Java directly:

```bash
java -jar account-service/target/account-service-1.0.0-SNAPSHOT.jar
java -jar inventory-service/target/inventory-service-1.0.0-SNAPSHOT.jar
java -jar order-service/target/order-service-1.0.0-SNAPSHOT.jar
```

### Option 3: IDE Integration

#### IntelliJ IDEA

1. Open the project root folder
2. Import as Maven project
3. Create run configurations for each service:
   - Main class: `com.theyawns.ecommerce.account.AccountServiceApplication`
   - Main class: `com.theyawns.ecommerce.inventory.InventoryServiceApplication`
   - Main class: `com.theyawns.ecommerce.order.OrderServiceApplication`

#### VS Code

1. Open the project folder
2. Install Java Extension Pack
3. Use the Run/Debug panel to start services

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `HAZELCAST_CLUSTER_NAME` | ecommerce-demo | Cluster name |
| `HAZELCAST_MEMBERS` | localhost:5701 | Cluster member addresses |
| `ACCOUNT_SERVICE_PORT` | 8081 | Account Service port |
| `INVENTORY_SERVICE_PORT` | 8082 | Inventory Service port |
| `ORDER_SERVICE_PORT` | 8083 | Order Service port |

### application.yml Override

Create `application-local.yml` in each service's resources:

```yaml
spring:
  profiles:
    active: local

server:
  port: 8081

hazelcast:
  cluster-name: my-cluster
  network:
    join:
      tcp-ip:
        enabled: true
        members:
          - 192.168.1.100:5701
          - 192.168.1.101:5701
```

Run with profile:
```bash
java -jar account-service.jar --spring.profiles.active=local
```

## Troubleshooting

### Common Issues

#### 1. Port Already in Use

```bash
# Find process using port
lsof -i :8081

# Kill process
kill -9 <PID>

# Or use different port
java -jar account-service.jar --server.port=8091
```

#### 2. Docker Memory Issues

```bash
# Check Docker memory allocation
docker system info | grep Memory

# Increase Docker Desktop memory:
# Docker Desktop → Settings → Resources → Memory → 4GB+
```

#### 3. Hazelcast Cluster Not Forming

```bash
# Check Hazelcast logs
docker-compose logs hazelcast-1

# Verify network connectivity
docker exec hazelcast-1 ping hazelcast-2

# Check cluster members
curl http://localhost:5701/hazelcast/rest/cluster
```

#### 4. Services Can't Connect to Hazelcast

```bash
# Verify Hazelcast is running
curl http://localhost:5701/hazelcast/health

# Check service logs
docker-compose logs account-service

# Verify network
docker network inspect docker_ecommerce-network
```

#### 5. Build Failures

```bash
# Clear Maven cache
rm -rf ~/.m2/repository/com/theyawns

# Clean and rebuild
mvn clean install -U

# Check for dependency issues
mvn dependency:tree
```

### Health Checks

```bash
# All-in-one health check
./scripts/health-check.sh

# Or manually:
for port in 8081 8082 8083; do
  echo "Port $port: $(curl -s http://localhost:$port/actuator/health | jq -r .status)"
done
```

### Logs

```bash
# All Docker logs
docker-compose logs

# Specific service
docker-compose logs account-service

# Follow logs
docker-compose logs -f order-service

# Last 100 lines
docker-compose logs --tail=100 inventory-service
```

## Monitoring

### Prometheus Metrics

Access at: http://localhost:9090

Useful queries:
```promql
# JVM memory usage
jvm_memory_used_bytes{area="heap"}

# HTTP request count
http_server_requests_seconds_count

# Event sourcing metrics
eventsourcing_events_submitted_total
```

### Hazelcast Management Center (Optional)

```bash
# Add to docker-compose.yml
management-center:
  image: hazelcast/management-center:5.6.0
  ports:
    - "8080:8080"
  environment:
    - MC_DEFAULT_CLUSTER=ecommerce-demo
    - MC_DEFAULT_CLUSTER_MEMBERS=hazelcast-1:5701
```

Access at: http://localhost:8080

## Next Steps

1. **Explore the API**: See [docker/README.md](../docker/README.md) for API examples
2. **Run Demo Scenarios**: Execute `./scripts/demo-scenarios.sh`
3. **Read Architecture**: See [docs/architecture/](architecture/) for design details
4. **Customize**: Modify services for your use case

## Getting Help

- **Issues**: https://github.com/theyawns/hazelcast-microservices-framework/issues
- **Documentation**: See `docs/` directory
- **Hazelcast Docs**: https://docs.hazelcast.com/hazelcast/5.6/
