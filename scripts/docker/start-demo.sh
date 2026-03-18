#!/bin/bash
# One-command demo setup:
#   1. Reset PostgreSQL data (clean slate each demo)
#   2. Start services in demo mode (no Jaeger)
#   3. Load sample data (customers, products)
#   4. Run demo load generator
#
# Default: 30 TPS for 10 minutes — impressive for a quick customer demo.
# For trade shows / all-day booths: ./scripts/docker/start-demo.sh --tps 3 --duration 8h
#
# Usage: ./scripts/docker/start-demo.sh [--tps 30] [--duration 10m]
#
# Prerequisites:
#   - Docker images built (run scripts/docker/build.sh first)
#   - k6 installed (brew install k6)

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/../.." && pwd )"

TPS="${TPS:-30}"
DURATION="${DURATION:-10m}"

# Parse optional overrides
while [ $# -gt 0 ]; do
    case "$1" in
        --tps)      shift; TPS="$1" ;;
        --duration) shift; DURATION="$1" ;;
        --help)
            echo "Usage: $0 [--tps N] [--duration Nh]"
            echo ""
            echo "  --tps N        Target requests per second (default: 30)"
            echo "  --duration Nh  How long to run (default: 10m)"
            echo ""
            echo "Examples:"
            echo "  Quick customer demo:   $0"
            echo "  Trade show (all day):  $0 --tps 3 --duration 8h"
            exit 0
            ;;
    esac
    shift
done

echo "============================================"
echo "Demo Setup"
echo "  TPS:      ${TPS}"
echo "  Duration: ${DURATION}"
echo "============================================"
echo ""

# Step 1: Stop any running services and reset PostgreSQL data
echo "[1/4] Cleaning up previous demo data..."
cd "$PROJECT_ROOT/docker"
COMPOSE_FILES="-f docker-compose.yml -f docker-compose-demo.yml"
docker compose $COMPOSE_FILES down -v 2>/dev/null || true
echo "  Done — containers stopped, PostgreSQL volume removed."
echo ""

# Step 2: Start services in demo mode
echo "[2/4] Starting services in demo mode..."
"$SCRIPT_DIR/start.sh" --mode demo

echo ""

# Step 3: Load sample data
echo "[3/4] Loading sample data..."
if [ -f "$PROJECT_ROOT/scripts/load-sample-data.sh" ]; then
    "$PROJECT_ROOT/scripts/load-sample-data.sh"
else
    echo "  WARNING: scripts/load-sample-data.sh not found. Skipping data load."
    echo "  Create customers and products manually before the load generator starts."
fi

echo ""

# Step 4: Check for k6
if ! command -v k6 > /dev/null 2>&1; then
    echo "[4/4] k6 not installed. Install with: brew install k6"
    echo "  Then run manually:"
    echo "  k6 run -e TPS=${TPS} -e DURATION=${DURATION} scripts/perf/k6-scenarios/demo-ambient.js"
    exit 0
fi

# Step 4: Run ambient load generator
echo "[4/4] Starting ambient load generator..."
echo "  Press Ctrl+C to stop the load generator (services keep running)"
echo ""
k6 run \
    -e TPS="${TPS}" \
    -e DURATION="${DURATION}" \
    "$PROJECT_ROOT/scripts/perf/k6-scenarios/demo-ambient.js"
