#!/bin/bash
# Build script for local Kubernetes deployment (Docker Desktop)
# Builds Maven modules and creates Docker images available to the local K8s cluster

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/../.." && pwd )"

SERVICES=(account-service inventory-service order-service payment-service api-gateway mcp-server)

echo "============================================"
echo "Hazelcast Microservices - K8s Local Build"
echo "============================================"
echo ""

# Step 1: Build Maven project
echo "Step 1: Building Maven project..."
cd "$PROJECT_ROOT"
./mvnw clean package -DskipTests -q

if [ $? -ne 0 ]; then
    echo "ERROR: Maven build failed!"
    exit 1
fi

echo "Maven build completed successfully."
echo ""

# Step 2: Build Docker images (shared with Docker Desktop K8s daemon)
echo "Step 2: Building Docker images..."
echo ""

for svc in "${SERVICES[@]}"; do
    echo "  Building hazelcast-microservices/${svc}:latest ..."
    docker build -t "hazelcast-microservices/${svc}:latest" "$PROJECT_ROOT/${svc}/" -q
done

echo ""

# Summary
echo "============================================"
echo "Build complete! Images built:"
echo ""
for svc in "${SERVICES[@]}"; do
    size=$(docker images "hazelcast-microservices/${svc}:latest" --format '{{.Size}}' 2>/dev/null || echo "unknown")
    echo "  hazelcast-microservices/${svc}:latest  (${size})"
done
echo ""
echo "These images are available to Docker Desktop K8s"
echo "(no registry push needed)."
echo ""
echo "Next: ./scripts/k8s-local/start.sh"
echo "============================================"
