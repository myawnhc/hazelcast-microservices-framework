#!/bin/bash
#
# generate-sample-data.sh - Loads 100 customers + 100 products for performance testing
#
# Usage: ./scripts/perf/generate-sample-data.sh [options]
#
# Options:
#   --gateway        Use API gateway (localhost:8080) instead of direct service URLs
#   --skip-health    Skip health checks
#   --dry-run        Print what would be done without executing
#
# Prerequisites: Services must be running (use docker compose up first)
#
# Data files: scripts/perf/data/customers.json, scripts/perf/data/products.json
#

set -e

# ── Configuration ──────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DATA_DIR="$SCRIPT_DIR/data"
IDS_FILE="$SCRIPT_DIR/data/perf-data-ids.sh"

ACCOUNT_SERVICE="http://localhost:8081"
INVENTORY_SERVICE="http://localhost:8082"
USE_GATEWAY=false
SKIP_HEALTH=false
DRY_RUN=false

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# ── Parse arguments ────────────────────────────────────────────────────────────

while test $# -gt 0; do
    case "$1" in
        --gateway)
            USE_GATEWAY=true
            ACCOUNT_SERVICE="http://localhost:8080"
            INVENTORY_SERVICE="http://localhost:8080"
            shift
            ;;
        --skip-health)
            SKIP_HEALTH=true
            shift
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# ── Validate data files exist ──────────────────────────────────────────────────

if [ ! -f "$DATA_DIR/customers.json" ]; then
    echo -e "${RED}Error: $DATA_DIR/customers.json not found${NC}"
    echo "Run this script from the project root."
    exit 1
fi

if [ ! -f "$DATA_DIR/products.json" ]; then
    echo -e "${RED}Error: $DATA_DIR/products.json not found${NC}"
    exit 1
fi

# ── Check prerequisites ───────────────────────────────────────────────────────

if ! command -v curl > /dev/null 2>&1; then
    echo -e "${RED}Error: curl is required${NC}"
    exit 1
fi

if ! command -v python3 > /dev/null 2>&1; then
    echo -e "${RED}Error: python3 is required (for JSON parsing)${NC}"
    exit 1
fi

# ── Health checks ──────────────────────────────────────────────────────────────

if [ "$SKIP_HEALTH" = false ]; then
    echo -e "${YELLOW}Checking service health...${NC}"

    for svc_info in "Account:$ACCOUNT_SERVICE/api/customers" "Inventory:$INVENTORY_SERVICE/api/products"; do
        svc_name="${svc_info%%:*}"
        svc_url="${svc_info#*:}"
        # Just check the base URL responds (GET list endpoint)
        base_url="${svc_url}"
        status=$(curl -s -o /dev/null -w "%{http_code}" "$base_url" 2>/dev/null || echo "000")
        if [ "$status" = "200" ]; then
            echo -e "  ${svc_name}: ${GREEN}OK${NC}"
        else
            echo -e "  ${svc_name}: ${RED}FAILED (HTTP $status)${NC}"
            echo -e "${RED}Please start services first.${NC}"
            exit 1
        fi
    done
    echo ""
fi

# ── Header ─────────────────────────────────────────────────────────────────────

CUSTOMER_COUNT=$(python3 -c "import json; print(len(json.load(open('$DATA_DIR/customers.json'))))")
PRODUCT_COUNT=$(python3 -c "import json; print(len(json.load(open('$DATA_DIR/products.json'))))")

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Performance Test Data Generator${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "  Customers to create: $CUSTOMER_COUNT"
echo "  Products to create:  $PRODUCT_COUNT"
if [ "$USE_GATEWAY" = true ]; then
    echo "  Routing: via API Gateway (localhost:8080)"
else
    echo "  Routing: direct to services"
fi
echo ""

if [ "$DRY_RUN" = true ]; then
    echo -e "${YELLOW}DRY RUN — no requests will be sent${NC}"
    exit 0
fi

# ── Create customers ───────────────────────────────────────────────────────────

echo -e "${CYAN}Creating $CUSTOMER_COUNT customers...${NC}"

CUSTOMER_IDS=""
CUSTOMER_SUCCESS=0
CUSTOMER_FAIL=0
CUSTOMER_START=$(date +%s)

# Process each customer from the JSON array
CUSTOMER_INDEX=0
while [ $CUSTOMER_INDEX -lt "$CUSTOMER_COUNT" ]; do
    # Extract one customer JSON object using python3
    CUSTOMER_JSON=$(python3 -c "
import json, sys
customers = json.load(open('$DATA_DIR/customers.json'))
print(json.dumps(customers[$CUSTOMER_INDEX]))
")

    RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$ACCOUNT_SERVICE/api/customers" \
        -H "Content-Type: application/json" \
        -d "$CUSTOMER_JSON" 2>/dev/null)

    HTTP_CODE=$(echo "$RESPONSE" | tail -1)
    BODY=$(echo "$RESPONSE" | sed '$d')

    if [ "$HTTP_CODE" = "201" ] || [ "$HTTP_CODE" = "200" ]; then
        # Extract customer ID from response
        CID=$(echo "$BODY" | python3 -c "import json,sys; print(json.load(sys.stdin).get('customerId',''))" 2>/dev/null || echo "")
        if [ -n "$CID" ]; then
            CUSTOMER_IDS="$CUSTOMER_IDS $CID"
            CUSTOMER_SUCCESS=$((CUSTOMER_SUCCESS + 1))
        else
            CUSTOMER_FAIL=$((CUSTOMER_FAIL + 1))
        fi
    elif [ "$HTTP_CODE" = "409" ]; then
        # Conflict — customer already exists, extract ID from error
        CID=$(echo "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('customerId', d.get('id','')))" 2>/dev/null || echo "")
        if [ -n "$CID" ]; then
            CUSTOMER_IDS="$CUSTOMER_IDS $CID"
            CUSTOMER_SUCCESS=$((CUSTOMER_SUCCESS + 1))
        else
            CUSTOMER_FAIL=$((CUSTOMER_FAIL + 1))
        fi
    else
        CUSTOMER_FAIL=$((CUSTOMER_FAIL + 1))
    fi

    CUSTOMER_INDEX=$((CUSTOMER_INDEX + 1))

    # Progress indicator every 10 customers
    if [ $((CUSTOMER_INDEX % 10)) -eq 0 ]; then
        echo -e "  ${GREEN}$CUSTOMER_INDEX${NC}/$CUSTOMER_COUNT customers created"
    fi
done

CUSTOMER_END=$(date +%s)
CUSTOMER_ELAPSED=$((CUSTOMER_END - CUSTOMER_START))

echo -e "  ${GREEN}Customers: $CUSTOMER_SUCCESS created, $CUSTOMER_FAIL failed (${CUSTOMER_ELAPSED}s)${NC}"
echo ""

# Brief pause for view updates
sleep 1

# ── Create products ────────────────────────────────────────────────────────────

echo -e "${CYAN}Creating $PRODUCT_COUNT products...${NC}"

PRODUCT_IDS=""
PRODUCT_SUCCESS=0
PRODUCT_FAIL=0
PRODUCT_START=$(date +%s)

PRODUCT_INDEX=0
while [ $PRODUCT_INDEX -lt "$PRODUCT_COUNT" ]; do
    PRODUCT_JSON=$(python3 -c "
import json, sys
products = json.load(open('$DATA_DIR/products.json'))
print(json.dumps(products[$PRODUCT_INDEX]))
")

    RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$INVENTORY_SERVICE/api/products" \
        -H "Content-Type: application/json" \
        -d "$PRODUCT_JSON" 2>/dev/null)

    HTTP_CODE=$(echo "$RESPONSE" | tail -1)
    BODY=$(echo "$RESPONSE" | sed '$d')

    if [ "$HTTP_CODE" = "201" ] || [ "$HTTP_CODE" = "200" ]; then
        PID=$(echo "$BODY" | python3 -c "import json,sys; print(json.load(sys.stdin).get('productId',''))" 2>/dev/null || echo "")
        if [ -n "$PID" ]; then
            PRODUCT_IDS="$PRODUCT_IDS $PID"
            PRODUCT_SUCCESS=$((PRODUCT_SUCCESS + 1))
        else
            PRODUCT_FAIL=$((PRODUCT_FAIL + 1))
        fi
    elif [ "$HTTP_CODE" = "409" ]; then
        PID=$(echo "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('productId', d.get('id','')))" 2>/dev/null || echo "")
        if [ -n "$PID" ]; then
            PRODUCT_IDS="$PRODUCT_IDS $PID"
            PRODUCT_SUCCESS=$((PRODUCT_SUCCESS + 1))
        else
            PRODUCT_FAIL=$((PRODUCT_FAIL + 1))
        fi
    else
        PRODUCT_FAIL=$((PRODUCT_FAIL + 1))
    fi

    PRODUCT_INDEX=$((PRODUCT_INDEX + 1))

    if [ $((PRODUCT_INDEX % 10)) -eq 0 ]; then
        echo -e "  ${GREEN}$PRODUCT_INDEX${NC}/$PRODUCT_COUNT products created"
    fi
done

PRODUCT_END=$(date +%s)
PRODUCT_ELAPSED=$((PRODUCT_END - PRODUCT_START))

echo -e "  ${GREEN}Products: $PRODUCT_SUCCESS created, $PRODUCT_FAIL failed (${PRODUCT_ELAPSED}s)${NC}"
echo ""

# ── Save IDs for k6 scripts ───────────────────────────────────────────────────

echo -e "${YELLOW}Saving IDs to $IDS_FILE...${NC}"

# Convert space-separated IDs to arrays in the output file
# (bash 3.2 compatible — no associative arrays)
{
    echo "# Generated by generate-sample-data.sh on $(date)"
    echo "# Source these IDs in k6 wrapper scripts or load-test.sh"
    echo ""
    echo "PERF_CUSTOMER_IDS=\"$(echo $CUSTOMER_IDS | xargs)\""
    echo "PERF_PRODUCT_IDS=\"$(echo $PRODUCT_IDS | xargs)\""
    echo ""
    echo "# First customer and product (convenience vars)"
    FIRST_CID=$(echo $CUSTOMER_IDS | awk '{print $1}')
    FIRST_PID=$(echo $PRODUCT_IDS | awk '{print $1}')
    echo "PERF_CUSTOMER_ID=\"$FIRST_CID\""
    echo "PERF_PRODUCT_ID=\"$FIRST_PID\""
    echo ""
    echo "PERF_CUSTOMER_COUNT=$CUSTOMER_SUCCESS"
    echo "PERF_PRODUCT_COUNT=$PRODUCT_SUCCESS"
} > "$IDS_FILE"

# Also write a JSON file for k6 SharedArray consumption
python3 -c "
import sys
cids = '''$CUSTOMER_IDS'''.split()
pids = '''$PRODUCT_IDS'''.split()
import json
with open('$DATA_DIR/customer-ids.json', 'w') as f:
    json.dump(cids, f, indent=2)
with open('$DATA_DIR/product-ids.json', 'w') as f:
    json.dump(pids, f, indent=2)
print('  Wrote customer-ids.json (' + str(len(cids)) + ' IDs)')
print('  Wrote product-ids.json (' + str(len(pids)) + ' IDs)')
"

echo ""

# ── Summary ────────────────────────────────────────────────────────────────────

TOTAL_ELAPSED=$((CUSTOMER_ELAPSED + PRODUCT_ELAPSED))

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Data Generation Complete${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "  Customers: $CUSTOMER_SUCCESS / $CUSTOMER_COUNT"
echo "  Products:  $PRODUCT_SUCCESS / $PRODUCT_COUNT"
echo "  Total time: ${TOTAL_ELAPSED}s"
echo ""
echo "  ID files:"
echo "    $IDS_FILE (shell)"
echo "    $DATA_DIR/customer-ids.json (k6)"
echo "    $DATA_DIR/product-ids.json (k6)"
echo ""

if [ $CUSTOMER_FAIL -gt 0 ] || [ $PRODUCT_FAIL -gt 0 ]; then
    echo -e "${YELLOW}Warning: Some items failed to create. Re-run to retry.${NC}"
    exit 1
fi

echo -e "${GREEN}Ready for performance testing!${NC}"
