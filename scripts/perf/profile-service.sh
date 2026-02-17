#!/bin/bash
#
# profile-service.sh - Capture async-profiler flame graphs from Dockerized services
#
# Orchestrates: optional k6 warmup -> k6 load (background) -> async-profiler capture -> extract HTML
#
# Usage:
#   ./scripts/perf/profile-service.sh --service order-service --event cpu --duration 60 --tps 25
#
# Options:
#   --service SERVICE     Container name to profile (required)
#                         Values: order-service, account-service, inventory-service, payment-service
#   --duration SECONDS    Profiling duration in seconds (default: 60)
#   --event EVENT         Profiling event type (default: cpu)
#                         Values: cpu, alloc, wall, lock
#   --tps TPS             Target transactions per second for k6 load (default: 25)
#   --warmup SECONDS      Warmup duration in seconds before profiling (default: 30)
#   --no-load             Skip k6 load generation (profile idle/external traffic only)
#   --output DIR          Output directory for flame graphs (default: docs/perf/flamegraphs)
#   --help                Show this help message
#
# Prerequisites:
#   - async-profiler downloaded: ./docker/profiling/download-async-profiler.sh
#   - Services running with profiling override:
#     cd docker && docker compose -f docker-compose.yml -f docker-compose-profiling.yml up -d
#   - Sample data loaded: ./scripts/perf/generate-sample-data.sh
#   - k6 installed (unless --no-load)
#
# Compatible with macOS bash 3.2.
#

set -e

# ---------------------------------------------------------------------------
# Configuration defaults
# ---------------------------------------------------------------------------
SERVICE=""
DURATION=60
EVENT="cpu"
TPS=25
WARMUP=30
NO_LOAD=false
OUTPUT_DIR=""

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PROFILER_DIR="$PROJECT_ROOT/docker/profiling/async-profiler"
PROFILER_OUTPUT_DIR="$PROJECT_ROOT/docker/profiling/output"
DEFAULT_OUTPUT_DIR="$PROJECT_ROOT/docs/perf/flamegraphs"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# ---------------------------------------------------------------------------
# Functions
# ---------------------------------------------------------------------------
print_usage() {
    echo "Usage: $0 --service SERVICE [options]"
    echo ""
    echo "Options:"
    echo "  --service SERVICE     Container name to profile (required)"
    echo "  --duration SECONDS    Profiling duration in seconds (default: 60)"
    echo "  --event EVENT         Profiling event: cpu, alloc, wall, lock (default: cpu)"
    echo "  --tps TPS             Target TPS for k6 load (default: 25)"
    echo "  --warmup SECONDS      Warmup before profiling (default: 30)"
    echo "  --no-load             Skip k6 load generation"
    echo "  --output DIR          Output directory (default: docs/perf/flamegraphs)"
    echo "  --help                Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 --service order-service --event cpu --duration 60 --tps 25"
    echo "  $0 --service order-service --event alloc --duration 60 --tps 25"
    echo "  $0 --service account-service --event wall --duration 30 --no-load"
}

cleanup() {
    # Kill background k6 process if running
    if [ -n "$K6_PID" ] && kill -0 "$K6_PID" 2>/dev/null; then
        echo -e "${YELLOW}Stopping k6 load generator (PID $K6_PID)...${NC}"
        kill "$K6_PID" 2>/dev/null || true
        wait "$K6_PID" 2>/dev/null || true
    fi
}

trap cleanup EXIT

# ---------------------------------------------------------------------------
# Parse arguments (bash 3.2 compatible)
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --service)
            SERVICE="$2"
            shift 2
            ;;
        --duration)
            DURATION="$2"
            shift 2
            ;;
        --event)
            EVENT="$2"
            shift 2
            ;;
        --tps)
            TPS="$2"
            shift 2
            ;;
        --warmup)
            WARMUP="$2"
            shift 2
            ;;
        --no-load)
            NO_LOAD=true
            shift
            ;;
        --output)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --help|-h)
            print_usage
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            print_usage
            exit 1
            ;;
    esac
done

if [ -z "$OUTPUT_DIR" ]; then
    OUTPUT_DIR="$DEFAULT_OUTPUT_DIR"
fi

# ---------------------------------------------------------------------------
# Validate inputs
# ---------------------------------------------------------------------------
if [ -z "$SERVICE" ]; then
    echo -e "${RED}Error: --service is required${NC}"
    echo ""
    print_usage
    exit 1
fi

# Validate event type
case "$EVENT" in
    cpu|alloc|wall|lock) ;;
    *)
        echo -e "${RED}Error: invalid event type '$EVENT'${NC}"
        echo "Valid events: cpu, alloc, wall, lock"
        exit 1
        ;;
esac

# Validate duration is a number
case "$DURATION" in
    ''|*[!0-9]*)
        echo -e "${RED}Error: duration must be a positive integer (got '$DURATION')${NC}"
        exit 1
        ;;
esac

# ---------------------------------------------------------------------------
# Check prerequisites
# ---------------------------------------------------------------------------
echo -e "${CYAN}Checking prerequisites...${NC}"

# 1. async-profiler downloaded
if [ ! -f "$PROFILER_DIR/bin/asprof" ]; then
    echo -e "${RED}Error: async-profiler not found at $PROFILER_DIR/bin/asprof${NC}"
    echo "  Run: ./docker/profiling/download-async-profiler.sh"
    exit 1
fi
echo -e "  async-profiler: ${GREEN}OK${NC}"

# 2. Container running
if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${SERVICE}$"; then
    echo -e "${RED}Error: container '$SERVICE' is not running${NC}"
    echo "  Start with profiling:"
    echo "  cd docker && docker compose -f docker-compose.yml -f docker-compose-profiling.yml up -d"
    exit 1
fi
echo -e "  Container $SERVICE: ${GREEN}running${NC}"

# 3. Profiler mounted in container
if ! docker exec "$SERVICE" test -f /opt/async-profiler/bin/asprof 2>/dev/null; then
    echo -e "${RED}Error: async-profiler not mounted in container${NC}"
    echo "  Make sure you started with the profiling override:"
    echo "  docker compose -f docker-compose.yml -f docker-compose-profiling.yml up -d"
    exit 1
fi
echo -e "  Profiler mount: ${GREEN}OK${NC}"

# 4. k6 installed (unless --no-load)
K6_PID=""
if [ "$NO_LOAD" = false ]; then
    if ! command -v k6 > /dev/null 2>&1; then
        echo -e "${RED}Error: k6 not installed (needed for load generation)${NC}"
        echo "  Install: brew install k6"
        echo "  Or use --no-load to skip load generation"
        exit 1
    fi
    echo -e "  k6: ${GREEN}OK${NC}"
fi

# 5. Create output directories
mkdir -p "$OUTPUT_DIR"
mkdir -p "$PROFILER_OUTPUT_DIR"

echo ""

# ---------------------------------------------------------------------------
# Find Java PID inside the container
# ---------------------------------------------------------------------------
echo -e "${YELLOW}Finding Java PID in $SERVICE...${NC}"

# The Dockerfile uses: ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
# So java is NOT PID 1 — we need pgrep
JAVA_PID=$(docker exec "$SERVICE" pgrep -f 'java.*app.jar' 2>/dev/null | head -1)

if [ -z "$JAVA_PID" ]; then
    # Fallback: try to find any java process
    JAVA_PID=$(docker exec "$SERVICE" pgrep java 2>/dev/null | head -1)
fi

if [ -z "$JAVA_PID" ]; then
    echo -e "${RED}Error: cannot find Java process in $SERVICE${NC}"
    echo "  Container processes:"
    docker exec "$SERVICE" ps aux 2>/dev/null || docker top "$SERVICE" 2>/dev/null || true
    exit 1
fi

echo -e "  Java PID: ${GREEN}$JAVA_PID${NC}"
echo ""

# ---------------------------------------------------------------------------
# Generate output filename
# ---------------------------------------------------------------------------
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
FILENAME="${SERVICE}-${EVENT}-${TIMESTAMP}"

# ---------------------------------------------------------------------------
# Print configuration
# ---------------------------------------------------------------------------
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}   async-profiler Flame Graph Capture${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "  Service:     $SERVICE"
echo "  Event:       $EVENT"
echo "  Duration:    ${DURATION}s"
echo "  Java PID:    $JAVA_PID"
echo "  Output:      $OUTPUT_DIR/$FILENAME.html"
if [ "$NO_LOAD" = false ]; then
    echo "  k6 TPS:      $TPS"
    echo "  Warmup:      ${WARMUP}s"
fi
echo ""

# ---------------------------------------------------------------------------
# Load sample data IDs (for k6)
# ---------------------------------------------------------------------------
CUSTOMER_ID="00000000-0000-4000-8000-000000000000"
PRODUCT_ID="00000000-0000-4000-8000-000000000000"

PERF_IDS_FILE="$SCRIPT_DIR/data/perf-data-ids.sh"
DEMO_IDS_FILE="$PROJECT_ROOT/demo-data/sample-data-ids.sh"

if [ -f "$PERF_IDS_FILE" ]; then
    . "$PERF_IDS_FILE"
    if [ -n "${PERF_CUSTOMER_ID:-}" ]; then
        CUSTOMER_ID="$PERF_CUSTOMER_ID"
    fi
    if [ -n "${PERF_PRODUCT_ID:-}" ]; then
        PRODUCT_ID="$PERF_PRODUCT_ID"
    fi
elif [ -f "$DEMO_IDS_FILE" ]; then
    . "$DEMO_IDS_FILE"
    if [ -n "${ALICE_ID:-}" ]; then
        CUSTOMER_ID="$ALICE_ID"
    fi
    if [ -n "${LAPTOP_ID:-}" ]; then
        PRODUCT_ID="$LAPTOP_ID"
    fi
fi

# ---------------------------------------------------------------------------
# Phase 1: Warmup (optional)
# ---------------------------------------------------------------------------
if [ "$NO_LOAD" = false ] && [ "$WARMUP" -gt 0 ]; then
    echo -e "${YELLOW}Phase 1: Warming up (${WARMUP}s at ${TPS} TPS)...${NC}"

    cd "$PROJECT_ROOT"
    k6 run \
        -e CUSTOMER_ID="$CUSTOMER_ID" \
        -e PRODUCT_ID="$PRODUCT_ID" \
        -e TPS="$TPS" \
        -e K6_SCENARIO=constant_rate \
        -e DURATION="${WARMUP}s" \
        -e USE_GATEWAY=false \
        --quiet \
        "$SCRIPT_DIR/k6-load-test.js" > /dev/null 2>&1 || true

    echo -e "  ${GREEN}Warmup complete${NC}"
    echo ""
fi

# ---------------------------------------------------------------------------
# Phase 2: Start k6 load in background (optional)
# ---------------------------------------------------------------------------
if [ "$NO_LOAD" = false ]; then
    echo -e "${YELLOW}Phase 2: Starting k6 load (${TPS} TPS for $((DURATION + 10))s)...${NC}"

    # Run k6 for slightly longer than profiling duration to ensure overlap
    LOAD_DURATION=$((DURATION + 10))

    cd "$PROJECT_ROOT"
    k6 run \
        -e CUSTOMER_ID="$CUSTOMER_ID" \
        -e PRODUCT_ID="$PRODUCT_ID" \
        -e TPS="$TPS" \
        -e K6_SCENARIO=constant_rate \
        -e DURATION="${LOAD_DURATION}s" \
        -e USE_GATEWAY=false \
        --quiet \
        "$SCRIPT_DIR/k6-load-test.js" > /dev/null 2>&1 &
    K6_PID=$!

    echo -e "  k6 running in background (PID $K6_PID)"
    echo ""

    # Brief pause to let load stabilize
    sleep 3
fi

# ---------------------------------------------------------------------------
# Phase 3: Capture flame graph with async-profiler
# ---------------------------------------------------------------------------
echo -e "${YELLOW}Phase 3: Profiling $SERVICE ($EVENT for ${DURATION}s)...${NC}"
echo "  This will take ${DURATION} seconds. Please wait."
echo ""

# Build asprof command arguments
ASPROF_ARGS="-d $DURATION -e $EVENT"

# For alloc event, add a reasonable threshold to reduce noise
if [ "$EVENT" = "alloc" ]; then
    ASPROF_ARGS="$ASPROF_ARGS --alloc 512k"
fi

# Output as HTML flame graph
ASPROF_ARGS="$ASPROF_ARGS -f /profiles/${FILENAME}.html"

# Run async-profiler inside the container
docker exec "$SERVICE" /opt/async-profiler/bin/asprof $ASPROF_ARGS "$JAVA_PID"
ASPROF_EXIT=$?

echo ""

if [ $ASPROF_EXIT -ne 0 ]; then
    echo -e "${RED}async-profiler exited with code $ASPROF_EXIT${NC}"
    echo "  Check container logs: docker logs $SERVICE"
    exit 1
fi

# ---------------------------------------------------------------------------
# Phase 4: Extract result
# ---------------------------------------------------------------------------
echo -e "${YELLOW}Extracting flame graph...${NC}"

# The result is in the bind-mounted output directory
SOURCE_FILE="$PROFILER_OUTPUT_DIR/${FILENAME}.html"

if [ ! -f "$SOURCE_FILE" ]; then
    echo -e "${RED}Error: flame graph not found at $SOURCE_FILE${NC}"
    echo "  Trying docker cp as fallback..."
    docker cp "$SERVICE:/profiles/${FILENAME}.html" "$OUTPUT_DIR/${FILENAME}.html" 2>/dev/null
    if [ ! -f "$OUTPUT_DIR/${FILENAME}.html" ]; then
        echo -e "${RED}Error: could not extract flame graph${NC}"
        exit 1
    fi
else
    cp "$SOURCE_FILE" "$OUTPUT_DIR/${FILENAME}.html"
fi

FILE_SIZE=$(ls -lh "$OUTPUT_DIR/${FILENAME}.html" | awk '{print $5}')

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}   Flame Graph Captured Successfully${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "  Service:   $SERVICE"
echo "  Event:     $EVENT"
echo "  Duration:  ${DURATION}s"
echo "  File:      $OUTPUT_DIR/${FILENAME}.html"
echo "  Size:      $FILE_SIZE"
echo ""
echo "  View in browser:"
echo "    open $OUTPUT_DIR/${FILENAME}.html"
echo ""
echo "  Tips:"
echo "    - Click frames to zoom in, click background to zoom out"
echo "    - Use search (Ctrl+F) to find specific methods"
echo "    - Wide frames = more time/allocations spent"
if [ "$EVENT" = "cpu" ]; then
    echo "    - On macOS Docker, this uses itimer (wall-clock) instead of perf_events"
    echo "    - Results include I/O wait time; use --event alloc for pure CPU work"
fi
if [ "$EVENT" = "alloc" ]; then
    echo "    - Each frame width = bytes allocated (not retained)"
    echo "    - Focus on frames that allocate frequently, not one-time setup"
fi
