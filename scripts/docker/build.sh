#!/bin/bash
# Build script for Docker deployment
# Builds all Maven modules and creates Docker images

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/../.." && pwd )"

# Parse flags
EDITION=""
for arg in "$@"; do
    case "$arg" in
        --enterprise) EDITION="enterprise" ;;
        --community)  EDITION="community" ;;
        --help)
            echo "Usage: $0 [--enterprise | --community]"
            echo ""
            echo "  --enterprise  Force enterprise build (requires HZ_LICENSEKEY)"
            echo "  --community   Force community build (ignore license key)"
            echo "  (default)     Auto-detect from HZ_LICENSEKEY environment variable"
            exit 0
            ;;
    esac
done

echo "============================================"
echo "Hazelcast Microservices Framework - Docker Build"
echo "============================================"
echo ""

# Step 1: Build Maven project
echo "Step 1: Building Maven project..."
cd "$PROJECT_ROOT"

MAVEN_PROFILES=""
if [ "$EDITION" = "enterprise" ]; then
    MAVEN_PROFILES="-Penterprise"
    echo "  Enterprise build requested via --enterprise flag"
elif [ "$EDITION" = "community" ]; then
    echo "  Community build requested via --community flag"
elif [ -n "$HZ_LICENSEKEY" ]; then
    MAVEN_PROFILES="-Penterprise"
    echo "  Enterprise license detected — building with enterprise profile"
fi

./mvnw clean package -DskipTests -q $MAVEN_PROFILES

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
