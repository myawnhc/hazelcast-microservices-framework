#!/bin/bash
#
# run-sustained-test.sh - Sustained Load Test Orchestrator
#
# Runs a long-duration load test (default 30 minutes) with periodic Grafana
# dashboard screenshot capture. Designed to find memory leaks, GC accumulation,
# and unbounded map growth.
#
# Compatible with macOS bash 3.2 (no bash 4+ features).
#
# Usage:
#   ./scripts/perf/run-sustained-test.sh [options]
#
# Options:
#   --duration DURATION    k6 duration (default: 30m)
#   --tps TPS              Target TPS (default: 50)
#   --capture-interval SEC Screenshot interval in seconds (default: 600)
#   --skip-build           Skip Maven build and Docker image rebuild
#   --skip-data            Skip sample data generation
#   --help                 Show this help message
#

set -e

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DOCKER_DIR="$PROJECT_ROOT/docker"
RESULTS_BASE="$SCRIPT_DIR/sustained-results"

DURATION="30m"
TPS="50"
CAPTURE_INTERVAL=600    # 10 minutes
SKIP_BUILD=false
SKIP_DATA=false

# Health check settings
HEALTH_TIMEOUT=180
HEALTH_INTERVAL=5

# Service health endpoints (bash 3.2 compatible: key:value pairs)
SERVICE_PORTS="account-service:8081 inventory-service:8082 order-service:8083 payment-service:8084"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# Background process PIDs for cleanup
K6_PID=""
STATS_PID=""
CAPTURE_PID=""

# ---------------------------------------------------------------------------
# Functions
# ---------------------------------------------------------------------------

print_usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "Runs a sustained load test with periodic Grafana dashboard capture."
    echo ""
    echo "Options:"
    echo "  --duration DURATION    k6 duration (default: 30m)"
    echo "  --tps TPS              Target TPS (default: 50)"
    echo "  --capture-interval SEC Screenshot interval in seconds (default: 600)"
    echo "  --skip-build           Skip Maven build and Docker image rebuild"
    echo "  --skip-data            Skip sample data generation"
    echo "  --help                 Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0                                  # 30m at 50 TPS (default)"
    echo "  $0 --duration 1h --tps 30           # 1 hour at 30 TPS"
    echo "  $0 --duration 5m --tps 10           # 5 min smoke test"
    echo "  $0 --skip-build --skip-data         # Re-run without setup"
}

die() {
    echo -e "${RED}Error: $1${NC}" >&2
    cleanup_background
    exit 1
}

log() {
    echo -e "${CYAN}[$(date +%H:%M:%S)]${NC} $1"
}

log_step() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

# Get current time in epoch milliseconds (macOS compatible)
epoch_ms() {
    python3 -c "import time; print(int(time.time() * 1000))"
}

# Parse k6 duration string to seconds (e.g., "30m" -> 1800, "1h" -> 3600)
duration_to_seconds() {
    local dur="$1"
    local num="${dur%[smh]*}"
    local unit="${dur##*[0-9]}"

    case "$unit" in
        s) echo "$num" ;;
        m) echo "$((num * 60))" ;;
        h) echo "$((num * 3600))" ;;
        *) echo "$((num * 60))" ;;  # default to minutes
    esac
}

# Cleanup background processes
cleanup_background() {
    log "Cleaning up background processes..."

    if [ -n "$K6_PID" ] && kill -0 "$K6_PID" 2>/dev/null; then
        log "  Stopping k6 (PID $K6_PID)..."
        kill "$K6_PID" 2>/dev/null || true
        wait "$K6_PID" 2>/dev/null || true
    fi

    if [ -n "$STATS_PID" ] && kill -0 "$STATS_PID" 2>/dev/null; then
        log "  Stopping stats monitor (PID $STATS_PID)..."
        kill "$STATS_PID" 2>/dev/null || true
        wait "$STATS_PID" 2>/dev/null || true
    fi

    if [ -n "$CAPTURE_PID" ] && kill -0 "$CAPTURE_PID" 2>/dev/null; then
        log "  Stopping capture loop (PID $CAPTURE_PID)..."
        kill "$CAPTURE_PID" 2>/dev/null || true
        wait "$CAPTURE_PID" 2>/dev/null || true
    fi
}

# Wait for all services to be healthy
wait_for_services() {
    log "Waiting for services to become healthy (timeout: ${HEALTH_TIMEOUT}s)..."

    local elapsed=0
    local all_healthy=false

    while [ $elapsed -lt $HEALTH_TIMEOUT ]; do
        all_healthy=true

        for svc_info in $SERVICE_PORTS; do
            local svc_name="${svc_info%%:*}"
            local svc_port="${svc_info#*:}"
            local url="http://localhost:${svc_port}/actuator/health"

            local status
            status=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")

            if [ "$status" != "200" ]; then
                all_healthy=false
                break
            fi
        done

        if [ "$all_healthy" = true ]; then
            log "All services healthy!"
            return 0
        fi

        sleep $HEALTH_INTERVAL
        elapsed=$((elapsed + HEALTH_INTERVAL))

        # Progress every 30s
        if [ $((elapsed % 30)) -eq 0 ]; then
            log "  Still waiting... (${elapsed}s / ${HEALTH_TIMEOUT}s)"
        fi
    done

    die "Services did not become healthy within ${HEALTH_TIMEOUT}s"
}

# Docker stats monitor (runs in background, appends to log file)
run_stats_monitor() {
    local stats_file="$1"
    local interval="$2"

    while true; do
        echo "--- $(date +%H:%M:%S) ---" >> "$stats_file"
        docker stats --no-stream --format "{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}" >> "$stats_file" 2>/dev/null || true
        echo "" >> "$stats_file"
        sleep "$interval"
    done
}

# Periodic dashboard capture (runs in background)
run_capture_loop() {
    local run_dir="$1"
    local start_ms="$2"
    local interval="$3"

    local capture_num=0
    while true; do
        sleep "$interval"
        capture_num=$((capture_num + 1))

        local minutes=$((capture_num * interval / 60))
        local label="checkpoint-${minutes}m"
        local now_ms
        now_ms=$(epoch_ms)

        log "Capturing dashboard screenshots (${label})..."
        "$SCRIPT_DIR/capture-dashboards.sh" \
            --output-dir "$run_dir/screenshots" \
            --from "$start_ms" \
            --to "$now_ms" \
            --label "$label" 2>&1 || {
            echo -e "${YELLOW}Warning: Dashboard capture failed for ${label}${NC}"
        }
    done
}

# Check for OOM kills
check_oom_kills() {
    local oom_found=false

    for svc_info in $SERVICE_PORTS; do
        local svc_name="${svc_info%%:*}"

        local exit_code
        exit_code=$(docker inspect "$svc_name" --format='{{.State.ExitCode}}' 2>/dev/null || echo "N/A")
        local oom_killed
        oom_killed=$(docker inspect "$svc_name" --format='{{.State.OOMKilled}}' 2>/dev/null || echo "false")
        local running
        running=$(docker inspect "$svc_name" --format='{{.State.Running}}' 2>/dev/null || echo "false")

        if [ "$oom_killed" = "true" ] || [ "$exit_code" = "137" ]; then
            echo -e "  ${RED}OOM KILLED: ${svc_name}${NC} (exit code: ${exit_code})"
            oom_found=true
        elif [ "$running" != "true" ]; then
            echo -e "  ${YELLOW}NOT RUNNING: ${svc_name}${NC} (exit code: ${exit_code})"
        else
            echo -e "  ${GREEN}OK: ${svc_name}${NC}"
        fi
    done

    # Also check Hazelcast nodes
    for node in hazelcast-1 hazelcast-2 hazelcast-3; do
        local exit_code
        exit_code=$(docker inspect "$node" --format='{{.State.ExitCode}}' 2>/dev/null || echo "N/A")
        local oom_killed
        oom_killed=$(docker inspect "$node" --format='{{.State.OOMKilled}}' 2>/dev/null || echo "false")

        if [ "$oom_killed" = "true" ] || [ "$exit_code" = "137" ]; then
            echo -e "  ${RED}OOM KILLED: ${node}${NC} (exit code: ${exit_code})"
            oom_found=true
        fi
    done

    if [ "$oom_found" = true ]; then
        return 1
    fi
    return 0
}

# Find the newest k6 result JSON
find_latest_k6_result() {
    ls -t "$SCRIPT_DIR"/results/k6-results-sustained-*.json 2>/dev/null | head -1
}

# ---------------------------------------------------------------------------
# Parse arguments (bash 3.2 compatible)
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --duration)
            DURATION="$2"
            shift 2
            ;;
        --tps)
            TPS="$2"
            shift 2
            ;;
        --capture-interval)
            CAPTURE_INTERVAL="$2"
            shift 2
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --skip-data)
            SKIP_DATA=true
            shift
            ;;
        --help|-h)
            print_usage
            exit 0
            ;;
        *)
            die "Unknown option: $1"
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Validate prerequisites
# ---------------------------------------------------------------------------
for cmd in docker curl jq k6 python3; do
    if ! command -v "$cmd" > /dev/null 2>&1; then
        die "$cmd is required but not installed."
    fi
done

if ! docker info > /dev/null 2>&1; then
    die "Docker is not running. Please start Docker Desktop."
fi

# ---------------------------------------------------------------------------
# Setup run directory
# ---------------------------------------------------------------------------
RUN_TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RUN_DIR="$RESULTS_BASE/sustained-${DURATION}-${TPS}tps-${RUN_TIMESTAMP}"
mkdir -p "$RUN_DIR/screenshots"

DURATION_SECONDS=$(duration_to_seconds "$DURATION")

# Trap SIGINT/SIGTERM for clean shutdown
trap cleanup_background INT TERM

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------
echo ""
echo -e "${BOLD}${BLUE}================================================${NC}"
echo -e "${BOLD}${BLUE}       Sustained Load Test${NC}"
echo -e "${BOLD}${BLUE}================================================${NC}"
echo ""
echo "  Duration:          ${DURATION} (${DURATION_SECONDS}s)"
echo "  Target TPS:        ${TPS}"
echo "  Capture interval:  ${CAPTURE_INTERVAL}s ($(( CAPTURE_INTERVAL / 60 ))m)"
echo "  Output:            ${RUN_DIR}"
echo ""

# =========================================================================
# PHASE 1: SETUP
# =========================================================================
log_step "Phase 1: Setup"

# Build if needed
if [ "$SKIP_BUILD" = false ]; then
    log "Building Maven artifacts..."
    cd "$PROJECT_ROOT"
    mvn clean package -DskipTests -q 2>&1 || die "Maven build failed"
    log "Build complete."
fi

# Start services with renderer overlay
log "Starting services with Grafana Image Renderer..."
cd "$DOCKER_DIR"
docker compose -f docker-compose.yml -f docker-compose-renderer.yml up -d 2>&1

# Wait for health
wait_for_services

# Also wait for Grafana to be ready
log "Waiting for Grafana..."
GRAFANA_READY=false
for i in $(seq 1 30); do
    GRAFANA_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:3000/api/health" 2>/dev/null || echo "000")
    if [ "$GRAFANA_STATUS" = "200" ]; then
        GRAFANA_READY=true
        break
    fi
    sleep 2
done
if [ "$GRAFANA_READY" = false ]; then
    echo -e "${YELLOW}Warning: Grafana may not be fully ready (screenshot capture might fail initially)${NC}"
fi

# Load sample data
if [ "$SKIP_DATA" = false ]; then
    log "Loading sample data (100 customers + 100 products)..."
    "$SCRIPT_DIR/generate-sample-data.sh" --skip-health 2>&1 || {
        echo -e "${YELLOW}Warning: Sample data generation had errors (continuing anyway)${NC}"
    }
else
    log "Skipping sample data generation (--skip-data)"
fi

# Record start time
START_TIME_MS=$(epoch_ms)
START_TIME_EPOCH=$(date +%s)

log "Start time: $(date) (epoch ms: ${START_TIME_MS})"

# Stabilization pause
log "Stabilization pause (10 seconds)..."
sleep 10

# =========================================================================
# PHASE 2: LOAD TEST + PERIODIC CAPTURE
# =========================================================================
log_step "Phase 2: Load Test (${DURATION} at ${TPS} TPS)"

# Load sample data IDs
CUSTOMER_ID=""
PRODUCT_ID=""
PERF_IDS_FILE="$SCRIPT_DIR/data/perf-data-ids.sh"
if [ -f "$PERF_IDS_FILE" ]; then
    . "$PERF_IDS_FILE"
    CUSTOMER_ID="${PERF_CUSTOMER_ID:-}"
    PRODUCT_ID="${PERF_PRODUCT_ID:-}"
fi
if [ -z "$CUSTOMER_ID" ]; then
    CUSTOMER_ID="00000000-0000-4000-8000-000000000000"
fi
if [ -z "$PRODUCT_ID" ]; then
    PRODUCT_ID="00000000-0000-4000-8000-000000000000"
fi

# Start Docker stats monitor in background
STATS_FILE="$RUN_DIR/docker-stats.log"
log "Starting Docker stats monitor -> ${STATS_FILE}"
run_stats_monitor "$STATS_FILE" 60 &
STATS_PID=$!

# Start periodic capture loop in background
log "Starting periodic dashboard capture (every $(( CAPTURE_INTERVAL / 60 ))m)..."
run_capture_loop "$RUN_DIR" "$START_TIME_MS" "$CAPTURE_INTERVAL" &
CAPTURE_PID=$!

# Start k6 in foreground (so we get its exit code)
log "Starting k6 sustained load test..."
cd "$PROJECT_ROOT"

mkdir -p "$SCRIPT_DIR/results"

k6 run \
    -e CUSTOMER_ID="$CUSTOMER_ID" \
    -e PRODUCT_ID="$PRODUCT_ID" \
    -e TPS="$TPS" \
    -e DURATION="$DURATION" \
    -e MAX_VUS=200 \
    "$SCRIPT_DIR/k6-scenarios/sustained-load.js" 2>&1 || true

K6_EXIT_CODE=${PIPESTATUS[0]:-$?}

# =========================================================================
# PHASE 3: FINAL CAPTURE + SUMMARY
# =========================================================================
log_step "Phase 3: Final Capture + Summary"

# Stop background processes
cleanup_background

END_TIME_MS=$(epoch_ms)
END_TIME_EPOCH=$(date +%s)
TOTAL_ELAPSED=$((END_TIME_EPOCH - START_TIME_EPOCH))

# Final full-window screenshot capture
log "Capturing final dashboard screenshots (full time window)..."
"$SCRIPT_DIR/capture-dashboards.sh" \
    --output-dir "$RUN_DIR/screenshots" \
    --from "$START_TIME_MS" \
    --to "$END_TIME_MS" \
    --label "final" 2>&1 || {
    echo -e "${YELLOW}Warning: Final dashboard capture failed${NC}"
}

# Final Docker stats snapshot
log "Final Docker stats snapshot..."
echo "=== FINAL SNAPSHOT $(date +%H:%M:%S) ===" >> "$STATS_FILE"
docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.NetIO}}\t{{.PIDs}}" >> "$STATS_FILE" 2>/dev/null || true

# OOM kill check
log "Checking for OOM kills..."
OOM_STATUS="none"
if ! check_oom_kills; then
    OOM_STATUS="OOM DETECTED"
fi

# Copy k6 results
RESULT_FILE=$(find_latest_k6_result)
if [ -n "$RESULT_FILE" ]; then
    cp "$RESULT_FILE" "$RUN_DIR/k6-results.json"
    log "k6 results copied to run directory."
else
    echo -e "${YELLOW}Warning: No k6 result file found${NC}"
fi

# Count screenshots
SCREENSHOT_COUNT=0
if [ -d "$RUN_DIR/screenshots" ]; then
    SCREENSHOT_COUNT=$(ls -1 "$RUN_DIR/screenshots"/*.png 2>/dev/null | wc -l | tr -d ' ')
fi

# Write run metadata
cat > "$RUN_DIR/run-metadata.json" << EOF
{
  "duration": "${DURATION}",
  "durationSeconds": ${DURATION_SECONDS},
  "tps": ${TPS},
  "captureInterval": ${CAPTURE_INTERVAL},
  "startTime": "${START_TIME_MS}",
  "endTime": "${END_TIME_MS}",
  "totalElapsed": ${TOTAL_ELAPSED},
  "k6ExitCode": ${K6_EXIT_CODE:-1},
  "oomStatus": "${OOM_STATUS}",
  "screenshotCount": ${SCREENSHOT_COUNT},
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "gitSha": "$(cd "$PROJECT_ROOT" && git rev-parse --short HEAD 2>/dev/null || echo "unknown")",
  "gitBranch": "$(cd "$PROJECT_ROOT" && git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")"
}
EOF

# ---------------------------------------------------------------------------
# Final summary
# ---------------------------------------------------------------------------
echo ""
echo -e "${BOLD}${BLUE}================================================${NC}"
echo -e "${BOLD}${BLUE}       Sustained Load Test Complete${NC}"
echo -e "${BOLD}${BLUE}================================================${NC}"
echo ""
echo "  Duration:       ${DURATION} (${TOTAL_ELAPSED}s actual)"
echo "  Target TPS:     ${TPS}"
echo "  k6 exit code:   ${K6_EXIT_CODE:-unknown}"
echo "  OOM kills:      ${OOM_STATUS}"
echo "  Screenshots:    ${SCREENSHOT_COUNT}"
echo "  Run directory:  ${RUN_DIR}"
echo ""
echo "  Files:"
for f in "$RUN_DIR"/*; do
    if [ -f "$f" ]; then
        local_size=$(ls -lh "$f" | awk '{print $5}')
        echo "    $(basename "$f")  ($local_size)"
    fi
done
if [ -d "$RUN_DIR/screenshots" ] && [ "$SCREENSHOT_COUNT" -gt 0 ]; then
    echo "    screenshots/  (${SCREENSHOT_COUNT} PNGs)"
fi
echo ""

if [ "$OOM_STATUS" != "none" ]; then
    echo -e "${RED}WARNING: OOM kills detected. Check Docker memory limits.${NC}"
    echo ""
fi

echo -e "${YELLOW}Services are still running for manual Grafana inspection:${NC}"
echo "  Grafana:  http://localhost:3000"
echo "  Services: http://localhost:8081-8084"
echo ""
echo "  To shut down: cd docker && docker compose down"
echo ""

exit ${K6_EXIT_CODE:-1}
