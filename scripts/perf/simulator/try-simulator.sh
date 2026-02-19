#!/bin/bash
#
# try-simulator.sh - Hazelcast Simulator Quick Evaluation
#
# Pulls the Hazelcast Simulator Docker image and attempts to run the built-in
# IntByteMapTest against the Docker Compose cluster. This is an evaluation of
# Simulator as a tool, not a primary benchmark method.
#
# Compatible with macOS bash 3.2 (no bash 4+ features).
#
# Usage:
#   ./scripts/perf/simulator/try-simulator.sh
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"
LOG_FILE="$RESULTS_DIR/simulator-evaluation.log"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

die() {
    echo -e "${RED}Error: $1${NC}" >&2
    exit 1
}

log() {
    local msg="[$(date +%H:%M:%S)] $1"
    echo -e "${CYAN}${msg}${NC}"
    echo "$msg" >> "$LOG_FILE"
}

# ---------------------------------------------------------------------------
# Prerequisites
# ---------------------------------------------------------------------------
echo ""
echo -e "${BOLD}${BLUE}================================================${NC}"
echo -e "${BOLD}${BLUE}   Hazelcast Simulator Evaluation${NC}"
echo -e "${BOLD}${BLUE}================================================${NC}"
echo ""

if ! command -v docker > /dev/null 2>&1; then
    die "Docker is required but not installed."
fi

if ! docker info > /dev/null 2>&1; then
    die "Docker is not running. Please start Docker Desktop."
fi

mkdir -p "$RESULTS_DIR"
echo "=== Simulator Evaluation Log ===" > "$LOG_FILE"
echo "Date: $(date)" >> "$LOG_FILE"
echo "" >> "$LOG_FILE"

# ---------------------------------------------------------------------------
# Step 1: Check Docker Compose cluster
# ---------------------------------------------------------------------------
log "Step 1: Checking if Docker Compose cluster is running..."

CLUSTER_RUNNING=true
for port in 5701 5702 5703; do
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:${port}/hazelcast/health/ready" 2>/dev/null || echo "000")
    if [ "$STATUS" != "200" ]; then
        CLUSTER_RUNNING=false
        log "  Node on port $port: NOT READY (HTTP $STATUS)"
    else
        log "  Node on port $port: READY"
    fi
done

if [ "$CLUSTER_RUNNING" = false ]; then
    log "WARNING: Cluster not fully healthy. Simulator may fail to connect."
    echo -e "${YELLOW}Cluster not fully healthy. Proceeding anyway for evaluation.${NC}"
fi

# ---------------------------------------------------------------------------
# Step 2: Pull Simulator image
# ---------------------------------------------------------------------------
log "Step 2: Pulling Hazelcast Simulator Docker image..."
echo "" >> "$LOG_FILE"

PULL_START=$(date +%s)
docker pull hazelcast/hazelcast-simulator:latest >> "$LOG_FILE" 2>&1 || {
    log "WARNING: Failed to pull simulator image. It may not exist or Docker Hub may be down."
    echo "" >> "$LOG_FILE"
    echo "=== PULL FAILED ===" >> "$LOG_FILE"

    # Try alternate image names
    log "Trying alternate image: hazelcast/simulator..."
    docker pull hazelcast/simulator:latest >> "$LOG_FILE" 2>&1 || {
        log "Alternate image also failed."
        echo "=== ALTERNATE PULL ALSO FAILED ===" >> "$LOG_FILE"
    }
}
PULL_END=$(date +%s)
PULL_TIME=$((PULL_END - PULL_START))
log "Image pull took ${PULL_TIME}s"

# ---------------------------------------------------------------------------
# Step 3: List available Simulator images
# ---------------------------------------------------------------------------
log "Step 3: Checking available Simulator images..."
echo "" >> "$LOG_FILE"

SIM_IMAGES=$(docker images --format "{{.Repository}}:{{.Tag}} ({{.Size}})" 2>/dev/null | grep -i "simulator" || echo "none found")
log "Simulator images: $SIM_IMAGES"
echo "Available images:" >> "$LOG_FILE"
echo "$SIM_IMAGES" >> "$LOG_FILE"

# ---------------------------------------------------------------------------
# Step 4: Attempt to run built-in test
# ---------------------------------------------------------------------------
log "Step 4: Attempting to run Simulator built-in IntByteMapTest..."
echo "" >> "$LOG_FILE"
echo "=== RUN ATTEMPT ===" >> "$LOG_FILE"

# Try to find the Docker network name
NETWORK_NAME=""
NETWORK_NAME=$(docker network ls --format "{{.Name}}" 2>/dev/null | grep "ecommerce" | head -1 || echo "")

if [ -z "$NETWORK_NAME" ]; then
    log "Could not find ecommerce Docker network. Trying with host networking."
    NETWORK_FLAG="--network host"
else
    log "Found Docker network: $NETWORK_NAME"
    NETWORK_FLAG="--network $NETWORK_NAME"
fi

# Attempt to run simulator (this may fail — that's the point of evaluation)
RUN_SUCCESS=false
RUN_OUTPUT="$RESULTS_DIR/simulator-run-output.txt"

# Try running with the most common Simulator image
for IMAGE in "hazelcast/hazelcast-simulator:latest" "hazelcast/simulator:latest"; do
    if docker image inspect "$IMAGE" > /dev/null 2>&1; then
        log "Attempting run with image: $IMAGE"
        echo "Running: docker run --rm $NETWORK_FLAG $IMAGE" >> "$LOG_FILE"

        # Run with timeout — simulator may hang or require interactive setup
        # Note: using perl-based timeout for macOS compatibility (no GNU timeout)
        perl -e 'alarm 60; exec @ARGV' docker run --rm $NETWORK_FLAG \
            -e "HAZELCAST_CLUSTER_MEMBERS=hazelcast-1:5701,hazelcast-2:5701,hazelcast-3:5701" \
            "$IMAGE" > "$RUN_OUTPUT" 2>&1 || {
            EXIT_CODE=$?
            log "Simulator exited with code: $EXIT_CODE"
            echo "Exit code: $EXIT_CODE" >> "$LOG_FILE"
        }

        if [ -f "$RUN_OUTPUT" ]; then
            RUN_SIZE=$(wc -c < "$RUN_OUTPUT" | tr -d ' ')
            if [ "$RUN_SIZE" -gt 0 ]; then
                log "Simulator produced ${RUN_SIZE} bytes of output"
                echo "" >> "$LOG_FILE"
                echo "=== SIMULATOR OUTPUT ===" >> "$LOG_FILE"
                cat "$RUN_OUTPUT" >> "$LOG_FILE"
                RUN_SUCCESS=true
            fi
        fi
        break
    fi
done

if [ "$RUN_SUCCESS" = false ]; then
    log "No Simulator image available or run produced no output."
    echo "No successful run." >> "$LOG_FILE"
fi

# ---------------------------------------------------------------------------
# Step 5: Evaluation Summary
# ---------------------------------------------------------------------------
echo ""
echo -e "${BOLD}${BLUE}================================================${NC}"
echo -e "${BOLD}${BLUE}   Simulator Evaluation Summary${NC}"
echo -e "${BOLD}${BLUE}================================================${NC}"
echo ""

echo "=== EVALUATION SUMMARY ===" >> "$LOG_FILE"

echo "  Image pull:     ${PULL_TIME}s"
echo "  Docker network: ${NETWORK_NAME:-host}"
echo "  Run succeeded:  ${RUN_SUCCESS}"
echo "  Log file:       ${LOG_FILE}"

if [ -f "$RUN_OUTPUT" ]; then
    echo "  Run output:     ${RUN_OUTPUT}"
fi

echo ""
echo -e "${YELLOW}Evaluation Notes:${NC}"
echo "  - Hazelcast Simulator is designed for large-scale cluster testing"
echo "  - It uses a coordinator/agent/worker architecture"
echo "  - Running against an existing Docker Compose cluster requires"
echo "    custom networking and test class JAR compilation"
echo "  - For this project's scale (3-node local cluster), JMH benchmarks"
echo "    connecting via HazelcastClient provide equivalent measurements"
echo "    with much less setup overhead"
echo ""
echo -e "${GREEN}Recommendation:${NC} Continue using JMH for cluster benchmarks."
echo "  Simulator is better suited for cloud-scale testing (50+ nodes)."
echo ""

# Write summary to log
cat >> "$LOG_FILE" << 'SUMMARY'

RECOMMENDATION:
Hazelcast Simulator is designed for large-scale distributed testing with
coordinator/agent/worker architecture. For this project's 3-node Docker
Compose cluster, JMH benchmarks via HazelcastClient provide equivalent
measurements with significantly less setup overhead.

Simulator would be valuable for:
- Cloud deployment testing (50+ nodes)
- Network partition testing
- Long-duration stability tests
- Comparing Hazelcast versions at scale

Not recommended for:
- Local Docker Compose development
- Quick A-B comparisons
- CI pipeline integration (too heavy)
SUMMARY

echo "Full log: $LOG_FILE"
echo ""
