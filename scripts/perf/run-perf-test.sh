#!/bin/bash
#
# run-perf-test.sh - Wrapper script for k6 performance tests
#
# Runs k6 load tests against the Hazelcast Microservices Framework eCommerce services.
# Compatible with macOS bash 3.2 (no bash 4+ features).
#
# Usage:
#   ./scripts/perf/run-perf-test.sh [options]
#
# Options:
#   --scenario SCENARIO  Test scenario to run (default: smoke)
#                        Values: smoke, rampup, constant, orders, mixed, saga, all
#   --duration DURATION  Test duration, k6 format (default: varies by scenario)
#   --tps TPS            Target transactions per second (default: 50)
#   --vus VUS            Max virtual users (default: varies by scenario)
#   --gateway            Route requests through API gateway (localhost:8080)
#   --help               Show this help message
#
# Prerequisites:
#   - k6 must be installed (brew install k6)
#   - Services must be running (./scripts/docker/start.sh)
#   - Sample data should be loaded (./scripts/load-sample-data.sh)
#

set -e

# ---------------------------------------------------------------------------
# Configuration defaults
# ---------------------------------------------------------------------------
SCENARIO="smoke"
DURATION=""
TPS="50"
MAX_VUS=""
USE_GATEWAY="false"

# Resolve script directory (works with symlinks)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"

# Colors for output
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
    echo "Usage: $0 [options]"
    echo ""
    echo "Options:"
    echo "  --scenario SCENARIO  Test scenario to run (default: smoke)"
    echo "                       Values: smoke, rampup, constant, orders, mixed, saga, all"
    echo "  --duration DURATION  Test duration, k6 format (e.g., '3m', '30s')"
    echo "  --tps TPS            Target transactions per second (default: 50)"
    echo "  --vus VUS            Max virtual users"
    echo "  --gateway            Route requests through API gateway (localhost:8080)"
    echo "  --help               Show this help message"
    echo ""
    echo "Scenarios:"
    echo "  smoke     - Quick sanity check: 1 VU, 30 iterations"
    echo "  rampup    - Ramp from 0 to 100 VUs over 5.5 minutes"
    echo "  constant  - Constant arrival rate at target TPS for 3 minutes"
    echo "  orders    - Focused: order creation only at target TPS"
    echo "  mixed     - Focused: mixed workload (60/25/15) at target TPS"
    echo "  saga      - Focused: end-to-end saga latency with polling"
    echo "  all       - Run smoke + rampup + constant sequentially"
    echo ""
    echo "Examples:"
    echo "  $0 --scenario smoke"
    echo "  $0 --scenario orders --tps 100 --duration 5m"
    echo "  $0 --scenario saga --vus 10"
    echo "  $0 --scenario mixed --gateway"
}

# ---------------------------------------------------------------------------
# Parse arguments (bash 3.2 compatible - no associative arrays)
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --scenario)
            SCENARIO="$2"
            shift 2
            ;;
        --duration)
            DURATION="$2"
            shift 2
            ;;
        --tps)
            TPS="$2"
            shift 2
            ;;
        --vus)
            MAX_VUS="$2"
            shift 2
            ;;
        --gateway)
            USE_GATEWAY="true"
            shift
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

# ---------------------------------------------------------------------------
# Check k6 is installed
# ---------------------------------------------------------------------------
if ! command -v k6 > /dev/null 2>&1; then
    echo -e "${RED}Error: k6 is not installed.${NC}"
    echo ""
    echo "Install k6 with Homebrew:"
    echo "  brew install k6"
    echo ""
    echo "Or see https://k6.io/docs/get-started/installation/ for other methods."
    exit 1
fi

K6_VERSION=$(k6 version 2>&1 | head -1)
echo -e "${CYAN}Using: ${K6_VERSION}${NC}"

# ---------------------------------------------------------------------------
# Load sample data IDs if available
# ---------------------------------------------------------------------------
CUSTOMER_ID=""
PRODUCT_ID=""

# Prefer perf data IDs (100 customers/products) over demo data IDs (4 customers/6 products)
PERF_IDS_FILE="$SCRIPT_DIR/data/perf-data-ids.sh"
DEMO_IDS_FILE="$PROJECT_ROOT/demo-data/sample-data-ids.sh"

if [ -f "$PERF_IDS_FILE" ]; then
    . "$PERF_IDS_FILE"
    CUSTOMER_ID="${PERF_CUSTOMER_ID:-}"
    PRODUCT_ID="${PERF_PRODUCT_ID:-}"
    if [ -n "$CUSTOMER_ID" ] && [ -n "$PRODUCT_ID" ]; then
        echo -e "${GREEN}Loaded perf data IDs from ${PERF_IDS_FILE}${NC}"
        echo "  Customer ID: $CUSTOMER_ID (fallback; k6 uses full pool of ${PERF_CUSTOMER_COUNT:-?} IDs)"
        echo "  Product ID:  $PRODUCT_ID (fallback; k6 uses full pool of ${PERF_PRODUCT_COUNT:-?} IDs)"
    else
        CUSTOMER_ID=""
        PRODUCT_ID=""
    fi
fi

if [ -z "$CUSTOMER_ID" ] && [ -f "$DEMO_IDS_FILE" ]; then
    . "$DEMO_IDS_FILE"
    CUSTOMER_ID="${ALICE_ID:-}"
    PRODUCT_ID="${LAPTOP_ID:-}"
    if [ -n "$CUSTOMER_ID" ] && [ -n "$PRODUCT_ID" ]; then
        echo -e "${YELLOW}Loaded demo data IDs from ${DEMO_IDS_FILE} (run generate-sample-data.sh for better coverage)${NC}"
        echo "  Customer ID: $CUSTOMER_ID"
        echo "  Product ID:  $PRODUCT_ID"
    else
        CUSTOMER_ID=""
        PRODUCT_ID=""
    fi
fi

if [ -z "$CUSTOMER_ID" ]; then
    echo -e "${YELLOW}No sample data IDs found${NC}"
    echo "  Run ./scripts/perf/generate-sample-data.sh first to create test data"
    echo "  Tests will use placeholder UUIDs (expect failures for order/stock tests)"
fi

# Use placeholder if not loaded
if [ -z "$CUSTOMER_ID" ]; then
    CUSTOMER_ID="00000000-0000-4000-8000-000000000000"
fi
if [ -z "$PRODUCT_ID" ]; then
    PRODUCT_ID="00000000-0000-4000-8000-000000000000"
fi

# ---------------------------------------------------------------------------
# Create results directory
# ---------------------------------------------------------------------------
mkdir -p "$RESULTS_DIR"

# ---------------------------------------------------------------------------
# Determine which k6 script and env vars to use
# ---------------------------------------------------------------------------
K6_SCRIPT=""
K6_ENV_ARGS=""

# Build common env args (bash 3.2 compatible - no arrays for this)
K6_ENV_ARGS="-e CUSTOMER_ID=$CUSTOMER_ID -e PRODUCT_ID=$PRODUCT_ID -e TPS=$TPS -e USE_GATEWAY=$USE_GATEWAY"

if [ -n "$DURATION" ]; then
    K6_ENV_ARGS="$K6_ENV_ARGS -e DURATION=$DURATION"
fi

if [ -n "$MAX_VUS" ]; then
    K6_ENV_ARGS="$K6_ENV_ARGS -e MAX_VUS=$MAX_VUS"
fi

case "$SCENARIO" in
    smoke)
        K6_SCRIPT="$SCRIPT_DIR/k6-load-test.js"
        K6_ENV_ARGS="$K6_ENV_ARGS -e K6_SCENARIO=smoke"
        ;;
    rampup|ramp_up|ramp-up)
        K6_SCRIPT="$SCRIPT_DIR/k6-load-test.js"
        K6_ENV_ARGS="$K6_ENV_ARGS -e K6_SCENARIO=ramp_up"
        ;;
    constant|constant_rate|constant-rate)
        K6_SCRIPT="$SCRIPT_DIR/k6-load-test.js"
        K6_ENV_ARGS="$K6_ENV_ARGS -e K6_SCENARIO=constant_rate"
        ;;
    orders|create-orders|create_orders)
        K6_SCRIPT="$SCRIPT_DIR/k6-scenarios/create-orders.js"
        ;;
    mixed|mixed-workload|mixed_workload)
        K6_SCRIPT="$SCRIPT_DIR/k6-scenarios/mixed-workload.js"
        ;;
    saga|saga-e2e|saga_e2e)
        K6_SCRIPT="$SCRIPT_DIR/k6-scenarios/saga-e2e.js"
        ;;
    all)
        K6_SCRIPT="$SCRIPT_DIR/k6-load-test.js"
        K6_ENV_ARGS="$K6_ENV_ARGS -e K6_SCENARIO=all"
        ;;
    *)
        echo -e "${RED}Unknown scenario: $SCENARIO${NC}"
        echo "Valid scenarios: smoke, rampup, constant, orders, mixed, saga, all"
        exit 1
        ;;
esac

# ---------------------------------------------------------------------------
# Print test configuration
# ---------------------------------------------------------------------------
echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}     k6 Performance Test${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "  Scenario:    $SCENARIO"
echo "  Script:      $(basename "$K6_SCRIPT")"
echo "  TPS:         $TPS"
if [ -n "$DURATION" ]; then
    echo "  Duration:    $DURATION"
fi
if [ -n "$MAX_VUS" ]; then
    echo "  Max VUs:     $MAX_VUS"
fi
if [ "$USE_GATEWAY" = "true" ]; then
    echo "  Routing:     via API Gateway (localhost:8080)"
else
    echo "  Routing:     direct to services"
fi
echo "  Results:     $RESULTS_DIR/"
echo ""

# ---------------------------------------------------------------------------
# Run k6
# ---------------------------------------------------------------------------
echo -e "${YELLOW}Starting k6 test...${NC}"
echo ""

# Change to project root so relative result paths in k6 scripts resolve correctly
cd "$PROJECT_ROOT"

# Execute k6 (eval to properly expand env args with spaces)
eval k6 run $K6_ENV_ARGS "\"$K6_SCRIPT\""
K6_EXIT_CODE=$?

echo ""

# ---------------------------------------------------------------------------
# Print summary
# ---------------------------------------------------------------------------
if [ $K6_EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}     Test PASSED${NC}"
    echo -e "${GREEN}========================================${NC}"
else
    echo -e "${RED}========================================${NC}"
    echo -e "${RED}     Test FAILED (exit code: $K6_EXIT_CODE)${NC}"
    echo -e "${RED}========================================${NC}"
fi

echo ""
echo "Results saved to: $RESULTS_DIR/"

# List recent result files
RECENT_RESULTS=$(ls -t "$RESULTS_DIR"/k6-results-*.json 2>/dev/null | head -3)
if [ -n "$RECENT_RESULTS" ]; then
    echo ""
    echo "Latest result files:"
    for f in $RECENT_RESULTS; do
        SIZE=$(ls -lh "$f" | awk '{print $5}')
        echo "  $(basename "$f")  ($SIZE)"
    done
fi

echo ""
exit $K6_EXIT_CODE
