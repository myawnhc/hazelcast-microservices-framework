#!/bin/bash
#
# load-test.sh - Runs load tests against the eCommerce services
#
# Usage: ./scripts/load-test.sh [options]
#
# Options:
#   -d, --duration    Test duration in seconds (default: 30)
#   -c, --concurrency Number of concurrent requests (default: 10)
#   -t, --target      Target TPS (default: 100)
#
# Prerequisites: Services must be running (use ./scripts/docker/start.sh first)
#

set -e

# Default configuration
DURATION=30
CONCURRENCY=10
TARGET_TPS=100

# Service URLs
ACCOUNT_SERVICE="http://localhost:8081"
INVENTORY_SERVICE="http://localhost:8082"
ORDER_SERVICE="http://localhost:8083"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -d|--duration)
            DURATION="$2"
            shift 2
            ;;
        -c|--concurrency)
            CONCURRENCY="$2"
            shift 2
            ;;
        -t|--target)
            TARGET_TPS="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}     eCommerce Load Test${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "Configuration:"
echo "  Duration: ${DURATION}s"
echo "  Concurrency: ${CONCURRENCY}"
echo "  Target TPS: ${TARGET_TPS}"
echo ""

# Check if curl is available
if ! command -v curl &> /dev/null; then
    echo -e "${RED}Error: curl is required but not installed${NC}"
    exit 1
fi

# Check service health
echo -e "${YELLOW}Checking service health...${NC}"
for service_url in "$ACCOUNT_SERVICE" "$INVENTORY_SERVICE" "$ORDER_SERVICE"; do
    if curl -s "${service_url}/actuator/health" | grep -qE '"status" *: *"UP"'; then
        echo -e "  ${service_url}: ${GREEN}OK${NC}"
    else
        echo -e "  ${service_url}: ${RED}FAILED${NC}"
        echo -e "${RED}Please start services first: ./scripts/docker/start.sh${NC}"
        exit 1
    fi
done
echo ""

# Load sample data IDs if available
IDS_FILE="$(dirname "$0")/../demo-data/sample-data-ids.sh"
STALE_IDS=false
if [ -f "$IDS_FILE" ]; then
    source "$IDS_FILE"
    # Validate IDs still exist (Hazelcast is in-memory; stale after restart)
    if [ -n "${ALICE_ID:-}" ]; then
        _check=$(curl -s -o /dev/null -w "%{http_code}" "$ACCOUNT_SERVICE/api/customers/$ALICE_ID" 2>/dev/null)
        if [ "$_check" != "200" ]; then
            echo -e "${YELLOW}Sample data IDs are stale — creating fresh test data${NC}"
            STALE_IDS=true
            unset ALICE_ID BOB_ID LAPTOP_ID WATCH_ID BAND_ID
        else
            echo -e "${GREEN}Loaded sample data IDs${NC}"
        fi
    fi
fi

if [ "$STALE_IDS" = true ] || [ ! -f "$IDS_FILE" ]; then
    # Create test customer and product on the fly
    echo -e "${YELLOW}Creating test data...${NC}"

    # Create test customer
    CUSTOMER_RESPONSE=$(curl -s -X POST "$ACCOUNT_SERVICE/api/customers" \
        -H "Content-Type: application/json" \
        -d '{"email":"loadtest@example.com","name":"Load Test User","address":"123 Test St"}')
    TEST_CUSTOMER_ID=$(echo "$CUSTOMER_RESPONSE" | grep -oE '"customerId" *: *"[^"]*"' | sed 's/.*: *"//;s/"$//')

    # Create test product
    PRODUCT_RESPONSE=$(curl -s -X POST "$INVENTORY_SERVICE/api/products" \
        -H "Content-Type: application/json" \
        -d '{"sku":"LOAD-TEST-001","name":"Load Test Product","price":10.00,"quantityOnHand":100000}')
    TEST_PRODUCT_ID=$(echo "$PRODUCT_RESPONSE" | grep -oE '"productId" *: *"[^"]*"' | sed 's/.*: *"//;s/"$//')

    if [ -z "$TEST_CUSTOMER_ID" ] || [ -z "$TEST_PRODUCT_ID" ]; then
        echo -e "${RED}Failed to create test data${NC}"
        exit 1
    fi

    sleep 1
    echo -e "${GREEN}Created test customer: $TEST_CUSTOMER_ID${NC}"
    echo -e "${GREEN}Created test product: $TEST_PRODUCT_ID${NC}"
fi
echo ""

# Run load test
echo -e "${YELLOW}Running load test...${NC}"
echo ""

SUCCESS_COUNT=0
ERROR_COUNT=0
START_TIME=$(date +%s%N)

# Create temporary file for results
RESULTS_FILE=$(mktemp)

# Mixed workload functions — exercises all 3 services to populate all dashboards
CUSTOMER_ID="${TEST_CUSTOMER_ID:-$ALICE_ID}"
PRODUCT_ID="${TEST_PRODUCT_ID:-$LAPTOP_ID}"

create_order() {
    local response=$(curl -s -w "%{http_code}" -o /dev/null -X POST "$ORDER_SERVICE/api/orders" \
        -H "Content-Type: application/json" \
        -d "{
            \"customerId\": \"$CUSTOMER_ID\",
            \"lineItems\": [
                {\"productId\": \"$PRODUCT_ID\", \"quantity\": 1, \"unitPrice\": \"10.00\"}
            ],
            \"shippingAddress\": \"$((RANDOM % 999)) Load Test St\"
        }")
    if [[ "$response" == "201" || "$response" == "200" ]]; then
        echo "1" >> "$RESULTS_FILE"
    else
        echo "0" >> "$RESULTS_FILE"
    fi
}

reserve_stock() {
    local response=$(curl -s -w "%{http_code}" -o /dev/null -X POST \
        "$INVENTORY_SERVICE/api/products/$PRODUCT_ID/stock/reserve" \
        -H "Content-Type: application/json" \
        -d "{\"quantity\": 1, \"orderId\": \"loadtest-$RANDOM\"}")
    if [[ "$response" == "200" ]]; then
        echo "1" >> "$RESULTS_FILE"
    else
        echo "0" >> "$RESULTS_FILE"
    fi
}

create_customer() {
    local response=$(curl -s -w "%{http_code}" -o /dev/null -X POST \
        "$ACCOUNT_SERVICE/api/customers" \
        -H "Content-Type: application/json" \
        -d "{\"email\": \"lt${RANDOM}@example.com\", \"name\": \"Load User $RANDOM\", \"address\": \"$RANDOM Test Ave\"}")
    if [[ "$response" == "201" || "$response" == "200" ]]; then
        echo "1" >> "$RESULTS_FILE"
    else
        echo "0" >> "$RESULTS_FILE"
    fi
}

# Mixed workload: 60% orders, 25% stock reservations, 15% customer creations
mixed_workload() {
    local roll=$((RANDOM % 100))
    if [ $roll -lt 60 ]; then
        create_order
    elif [ $roll -lt 85 ]; then
        reserve_stock
    else
        create_customer
    fi
}

# Run concurrent requests
echo -e "Running $CONCURRENCY concurrent workers for ${DURATION}s..."
echo -e "Workload mix: 60% orders, 25% stock reserves, 15% customer creates"
echo ""

END_TIME=$(($(date +%s) + DURATION))

# Launch background workers
for i in $(seq 1 $CONCURRENCY); do
    (
        while [ $(date +%s) -lt $END_TIME ]; do
            mixed_workload
        done
    ) &
done

# Wait for all workers
wait

ACTUAL_END_TIME=$(date +%s%N)
ELAPSED_NS=$((ACTUAL_END_TIME - START_TIME))
ELAPSED_S=$(echo "scale=2; $ELAPSED_NS / 1000000000" | bc)

# Count results
SUCCESS_COUNT=$(grep -c "1" "$RESULTS_FILE" 2>/dev/null || echo "0")
ERROR_COUNT=$(grep -c "0" "$RESULTS_FILE" 2>/dev/null || echo "0")
TOTAL_COUNT=$((SUCCESS_COUNT + ERROR_COUNT))
TPS=$(echo "scale=2; $TOTAL_COUNT / $ELAPSED_S" | bc)

rm -f "$RESULTS_FILE"

# Print results
echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}     Load Test Results${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "Duration:        ${ELAPSED_S}s"
echo "Total Requests:  ${TOTAL_COUNT}"
echo "Successful:      ${SUCCESS_COUNT}"
echo "Failed:          ${ERROR_COUNT}"
echo ""
echo -e "Throughput:      ${YELLOW}${TPS} TPS${NC}"
echo ""

# Check if target met
if (( $(echo "$TPS >= $TARGET_TPS" | bc -l) )); then
    echo -e "${GREEN}SUCCESS: Achieved target of ${TARGET_TPS} TPS${NC}"
    exit 0
else
    echo -e "${RED}FAILED: Did not achieve target of ${TARGET_TPS} TPS${NC}"
    exit 1
fi
