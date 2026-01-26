#!/bin/bash
# Stop script for Docker deployment
# Stops all services and optionally removes volumes

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/.." && pwd )"

echo "============================================"
echo "Hazelcast Microservices Framework - Stopping"
echo "============================================"
echo ""

cd "$PROJECT_ROOT/docker"

if [ "$1" = "--clean" ] || [ "$1" = "-c" ]; then
    echo "Stopping services and removing volumes..."
    docker-compose down -v
else
    echo "Stopping services..."
    docker-compose down
    echo ""
    echo "Note: Use --clean or -c to also remove volumes"
fi

echo ""
echo "Services stopped."
echo "============================================"
