#!/bin/bash
# Start script for Docker deployment
# Starts the Hazelcast cluster and all microservices
#
# Usage: ./scripts/docker/start.sh [--mode demo|production|perf-test]
#
# Modes:
#   (none)      Development — current behavior, all services, full logging
#   demo        Trade show — no PostgreSQL/Jaeger, aggressive eviction, low memory
#   production  Zero data loss — PostgreSQL persistence, archival, full monitoring
#   perf-test   Stress testing — persistence ON, rate limiting OFF, GC logging

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/../.." && pwd )"

# Parse arguments
MODE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --mode)
            shift
            MODE="$1"
            ;;
        --help)
            echo "Usage: $0 [--mode demo|production|perf-test]"
            exit 0
            ;;
    esac
    shift
done

# Validate mode
if [ -n "$MODE" ]; then
    case "$MODE" in
        demo|production|perf-test) ;;
        *) echo "ERROR: Invalid mode '${MODE}'. Must be demo, production, or perf-test."; exit 1 ;;
    esac
fi

echo "============================================"
echo "Hazelcast Microservices Framework - Starting"
if [ -n "$MODE" ]; then
    echo "Mode: ${MODE}"
fi
echo "============================================"
echo ""

cd "$PROJECT_ROOT/docker"

# Check if images exist
if ! docker images | grep -q "docker-account-service\|docker_account-service"; then
    echo "Docker images not found. Building first..."
    "$SCRIPT_DIR/build.sh"
fi

# Build compose file list based on mode
COMPOSE_FILES="-f docker-compose.yml"
if [ -n "$MODE" ]; then
    COMPOSE_FILES="$COMPOSE_FILES -f docker-compose-${MODE}.yml"
fi

echo "Starting services..."
docker compose $COMPOSE_FILES up -d

echo ""
echo "Waiting for services to be healthy..."
echo ""

# Determine which services to skip health checks for based on mode
SKIP_POSTGRES=false
SKIP_JAEGER=false
if [ "$MODE" = "demo" ]; then
    SKIP_POSTGRES=true
    SKIP_JAEGER=true
fi

# Wait for Hazelcast cluster
echo "Waiting for Hazelcast cluster..."
timeout=120
counter=0
until docker compose $COMPOSE_FILES exec -T hazelcast-1 curl -sf http://localhost:5701/hazelcast/health/ready > /dev/null 2>&1; do
    sleep 2
    counter=$((counter + 2))
    if [ $counter -ge $timeout ]; then
        echo "ERROR: Hazelcast cluster failed to start within ${timeout}s"
        docker compose $COMPOSE_FILES logs hazelcast-1
        exit 1
    fi
    echo "  Waiting... (${counter}s)"
done
echo "  Hazelcast cluster is ready!"

# Wait for services
for service in account-service inventory-service order-service payment-service mcp-server; do
    port=""
    case $service in
        account-service) port=8081 ;;
        inventory-service) port=8082 ;;
        order-service) port=8083 ;;
        payment-service) port=8084 ;;
        mcp-server) port=8085 ;;
    esac

    echo "Waiting for ${service}..."
    counter=0
    until curl -sf http://localhost:${port}/actuator/health > /dev/null 2>&1; do
        sleep 2
        counter=$((counter + 2))
        if [ $counter -ge $timeout ]; then
            echo "ERROR: ${service} failed to start within ${timeout}s"
            docker compose $COMPOSE_FILES logs ${service}
            exit 1
        fi
        echo "  Waiting... (${counter}s)"
    done
    echo "  ${service} is ready!"
done

echo ""
echo "============================================"
echo "All services are running!"
if [ -n "$MODE" ]; then
    echo "Mode: ${MODE}"
fi
echo ""
echo "Service Endpoints:"
echo "  Account Service:   http://localhost:8081/api/customers"
echo "  Inventory Service: http://localhost:8082/api/products"
echo "  Order Service:     http://localhost:8083/api/orders"
echo "  Payment Service:   http://localhost:8084/api/payments"
echo ""
echo "MCP Server (AI Assistant):"
echo "  SSE Endpoint:      http://localhost:8085/sse"
echo ""
echo "Monitoring:"
echo "  Management Center: http://localhost:8888"
echo "  Prometheus:        http://localhost:9090"
echo "  Health Checks:     http://localhost:{8081..8085}/actuator/health"
if [ "$SKIP_POSTGRES" = "true" ]; then
    echo ""
    echo "Note: PostgreSQL is disabled in demo mode (no persistence)"
fi
if [ "$SKIP_JAEGER" = "true" ]; then
    echo "Note: Jaeger is disabled in demo mode (no tracing)"
fi
echo ""
echo "Hazelcast Cluster:"
echo "  Node 1:            http://localhost:5701/hazelcast/health"
echo "  Node 2:            http://localhost:5702/hazelcast/health"
echo "  Node 3:            http://localhost:5703/hazelcast/health"
echo ""
echo "To view logs:  docker compose $COMPOSE_FILES logs -f"
echo "To stop:       docker compose $COMPOSE_FILES down"
echo "============================================"
