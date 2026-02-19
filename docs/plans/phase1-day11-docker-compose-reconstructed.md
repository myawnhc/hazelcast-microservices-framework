# Phase 1 Day 11: Docker Compose Setup (Reconstructed)

> Reconstructed from git history and implementation plan. May not capture all details from the original session.

## Context
Days 1-10 completed the framework core (Week 1) and all three eCommerce microservices (Week 2). Day 11 began Week 3 (Integration & Docker), focused on containerizing the entire stack so that `docker-compose up` starts all services with a 3-node Hazelcast cluster and Prometheus metrics.

## What Was Built

### Dockerfiles
- Multi-stage Dockerfiles for account-service, inventory-service, and order-service using Maven build + JRE runtime stages.

### Docker Compose
- Full `docker-compose.yml` with:
  - 3-node Hazelcast cluster (hazelcast-1, hazelcast-2, hazelcast-3)
  - Account service (port 8081)
  - Inventory service (port 8082)
  - Order service (port 8083)
  - Prometheus for metrics scraping

### Hazelcast Cluster Configuration
- `hazelcast-docker.yaml` — TCP/IP-based cluster discovery for Docker networking (multicast does not work inside Docker).
- `hazelcast.yaml` — standalone cluster config reference.

### Prometheus
- `prometheus.yml` configured to scrape metrics from all three services and the Hazelcast cluster.

### Framework Config Updates
- `HazelcastConfig.java` updated to support TCP/IP discovery via environment variables, enabling the same code to run both locally (multicast) and in Docker (TCP/IP).
- Defensive null handling added for config values in Docker environment.

### Service Config Fixes
- Fixed bean naming conflict in `OrderServiceConfig`.
- Updated `AccountServiceConfig` and `InventoryServiceConfig` with minor adjustments.
- Updated `application.yml` files for all three services with environment variable support.

### Helper Scripts
- `docker/build.sh` — builds Maven artifacts and Docker images.
- `docker/start.sh` — starts the full stack with health checks.
- `docker/stop.sh` — tears down the Docker environment.

### Build Optimization
- `.dockerignore` added to reduce build context and speed up Docker builds.

## Key Decisions
- **TCP/IP discovery in Docker**: Multicast does not work in Docker networks, so TCP/IP join with explicit member addresses was configured for the Hazelcast cluster.
- **Environment variable driven configuration**: Services detect their environment (local vs Docker) via environment variables, allowing a single codebase for both modes.
- **Memory target**: Total stack footprint designed to stay under ~3.5GB, runnable on an 8GB laptop.
- **Prometheus from Day 1**: Observability was included as part of the initial Docker setup rather than deferred.

## Test Results
No new tests were added in this commit. The focus was on infrastructure and containerization.

## Files Changed
| File | Change |
|------|--------|
| `.dockerignore` | Created -- ignore patterns to optimize Docker build context |
| `account-service/Dockerfile` | Created -- multi-stage build for account service |
| `account-service/.../AccountServiceConfig.java` | Modified -- minor config adjustments |
| `account-service/src/main/resources/application.yml` | Modified -- add environment variable support |
| `docker/README.md` | Created -- Docker setup documentation (200 lines) |
| `docker/docker-compose.yml` | Created -- full stack compose file (246 lines) |
| `docker/hazelcast/hazelcast-docker.yaml` | Created -- TCP/IP cluster discovery config |
| `docker/hazelcast/hazelcast.yaml` | Created -- standalone Hazelcast config |
| `docker/prometheus/prometheus.yml` | Created -- Prometheus scrape targets |
| `framework-core/.../HazelcastConfig.java` | Modified -- add TCP/IP discovery support via env vars |
| `inventory-service/Dockerfile` | Created -- multi-stage build for inventory service |
| `inventory-service/.../InventoryServiceConfig.java` | Modified -- minor config adjustments |
| `inventory-service/src/main/resources/application.yml` | Modified -- add environment variable support |
| `order-service/Dockerfile` | Created -- multi-stage build for order service |
| `order-service/.../OrderServiceConfig.java` | Modified -- fix bean naming conflict |
| `order-service/src/main/resources/application.yml` | Modified -- add environment variable support |
| `scripts/docker/build.sh` | Created -- build script for Maven + Docker |
| `scripts/docker/start.sh` | Created -- startup script with health checks |
| `scripts/docker/stop.sh` | Created -- teardown script |

**Totals**: 19 files changed, 1,019 insertions, 13 deletions.
