#!/bin/bash
# Start script for Docker deployment
# Starts the Hazelcast cluster and all microservices

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"

echo "============================================"
echo "Hazelcast Microservices Framework - Starting"
echo "============================================"
echo ""

cd "$PROJECT_ROOT/docker"

# Check if images exist
if ! docker images | grep -q "docker-account-service\|docker_account-service"; then
    echo "Docker images not found. Building first..."
    "$SCRIPT_DIR/build-docker.sh"
fi

echo "Starting services..."
docker-compose up -d

echo ""
echo "Waiting for services to be healthy..."
echo ""

# Wait for Hazelcast cluster
echo "Waiting for Hazelcast cluster..."
timeout=120
counter=0
until docker-compose exec -T hazelcast-1 curl -sf http://localhost:5701/hazelcast/health/ready > /dev/null 2>&1; do
    sleep 2
    counter=$((counter + 2))
    if [ $counter -ge $timeout ]; then
        echo "ERROR: Hazelcast cluster failed to start within ${timeout}s"
        docker-compose logs hazelcast-1
        exit 1
    fi
    echo "  Waiting... (${counter}s)"
done
echo "  Hazelcast cluster is ready!"

# Wait for services
for service in account-service inventory-service order-service; do
    port=""
    case $service in
        account-service) port=8081 ;;
        inventory-service) port=8082 ;;
        order-service) port=8083 ;;
    esac

    echo "Waiting for ${service}..."
    counter=0
    until curl -sf http://localhost:${port}/actuator/health > /dev/null 2>&1; do
        sleep 2
        counter=$((counter + 2))
        if [ $counter -ge $timeout ]; then
            echo "ERROR: ${service} failed to start within ${timeout}s"
            docker-compose logs ${service}
            exit 1
        fi
        echo "  Waiting... (${counter}s)"
    done
    echo "  ${service} is ready!"
done

echo ""
echo "============================================"
echo "All services are running!"
echo ""
echo "Service Endpoints:"
echo "  Account Service:   http://localhost:8081/api/customers"
echo "  Inventory Service: http://localhost:8082/api/products"
echo "  Order Service:     http://localhost:8083/api/orders"
echo ""
echo "Monitoring:"
echo "  Prometheus:        http://localhost:9090"
echo "  Health Checks:     http://localhost:808X/actuator/health"
echo ""
echo "Hazelcast Cluster:"
echo "  Node 1:            http://localhost:5701/hazelcast/health"
echo "  Node 2:            http://localhost:5702/hazelcast/health"
echo "  Node 3:            http://localhost:5703/hazelcast/health"
echo ""
echo "To view logs:  docker-compose logs -f"
echo "To stop:       docker-compose down"
echo "============================================"
