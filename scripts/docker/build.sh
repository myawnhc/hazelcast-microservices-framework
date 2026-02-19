#!/bin/bash
# Build script for Docker deployment
# Builds all Maven modules and creates Docker images

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/../.." && pwd )"

echo "============================================"
echo "Hazelcast Microservices Framework - Docker Build"
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

# Step 2: Build Docker images
echo "Step 2: Building Docker images..."
cd "$PROJECT_ROOT/docker"

docker-compose build

if [ $? -ne 0 ]; then
    echo "ERROR: Docker build failed!"
    exit 1
fi

echo ""
echo "============================================"
echo "Build complete!"
echo ""
echo "To start the stack, run:"
echo "  cd docker && docker-compose up -d"
echo ""
echo "Or use: ./scripts/docker/start.sh"
echo "============================================"
