#!/bin/bash
#
# load-sample-data.sh - Loads sample data for the eCommerce demo
#
# Usage: ./scripts/load-sample-data.sh [--small]
#
#   --small   Load minimal dataset (4 customers, 6 products) instead of full catalog
#
# Prerequisites: Services must be running (use ./scripts/docker/start.sh first)
#

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Configuration
ACCOUNT_SERVICE="http://localhost:8081"
INVENTORY_SERVICE="http://localhost:8082"
ORDER_SERVICE="http://localhost:8083"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Output file for storing IDs
OUTPUT_DIR="$SCRIPT_DIR/../demo-data"
mkdir -p "$OUTPUT_DIR"

# Parse flags
DATASET="full"
for arg in "$@"; do
    case "$arg" in
        --small) DATASET="small" ;;
        --help)
            echo "Usage: $0 [--small]"
            echo ""
            echo "  --small   Load minimal dataset (4 customers, 6 products)"
            echo "  (default) Load full catalog (100 customers, 100 products)"
            exit 0
            ;;
    esac
done

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Loading Sample Data for eCommerce Demo${NC}"
echo -e "${BLUE}========================================${NC}"
echo -e "  Dataset: ${YELLOW}${DATASET}${NC}"
echo ""

# Function to check service health
check_health() {
    local service_name=$1
    local url=$2

    echo -n "Checking $service_name health... "
    if curl -s "$url/actuator/health" | grep -qE '"status" *: *"UP"'; then
        echo -e "${GREEN}OK${NC}"
        return 0
    else
        echo -e "${RED}FAILED${NC}"
        return 1
    fi
}

# Function to create a single customer from JSON fields and return the ID
create_customer() {
    local json_payload=$1

    local response=$(curl -s -X POST "$ACCOUNT_SERVICE/api/customers" \
        -H "Content-Type: application/json" \
        -d "$json_payload")

    local customer_id=$(echo "$response" | grep -oE '"customerId" *: *"[^"]*"' | sed 's/.*: *"//;s/"$//')
    if [ -z "$customer_id" ]; then
        customer_id=$(echo "$response" | grep -o 'Customer not found: [a-f0-9-]*' | cut -d' ' -f4)
    fi

    echo "$customer_id"
}

# Function to create a single product from JSON fields and return the ID
create_product() {
    local json_payload=$1

    local response=$(curl -s -X POST "$INVENTORY_SERVICE/api/products" \
        -H "Content-Type: application/json" \
        -d "$json_payload")

    local product_id=$(echo "$response" | grep -oE '"productId" *: *"[^"]*"' | sed 's/.*: *"//;s/"$//')
    if [ -z "$product_id" ]; then
        product_id=$(echo "$response" | grep -o 'Product not found: [a-f0-9-]*' | cut -d' ' -f4)
    fi

    echo "$product_id"
}

# Check all services are healthy
echo -e "${YELLOW}Step 1: Checking service health...${NC}"
check_health "Account Service" "$ACCOUNT_SERVICE" || { echo "Account Service not ready. Run ./scripts/docker/start.sh first."; exit 1; }
check_health "Inventory Service" "$INVENTORY_SERVICE" || { echo "Inventory Service not ready. Run ./scripts/docker/start.sh first."; exit 1; }
check_health "Order Service" "$ORDER_SERVICE" || { echo "Order Service not ready. Run ./scripts/docker/start.sh first."; exit 1; }
echo ""

# -----------------------------------------------
# Load data based on dataset selection
# -----------------------------------------------

if [ "$DATASET" = "small" ]; then
    # -----------------------------------------------
    # Small dataset: inline (original behavior)
    # -----------------------------------------------
    echo -e "${YELLOW}Step 2: Creating customers (small dataset)...${NC}"

    CUSTOMER_IDS=()
    for entry in \
        '{"email":"alice@techcorp.com","name":"Alice Smith","address":"100 Tech Park Drive, San Jose, CA 95134","phone":"555-0101"}' \
        '{"email":"bob@startup.io","name":"Bob Johnson","address":"456 Innovation Way, Austin, TX 78701","phone":"555-0102"}' \
        '{"email":"carol@enterprise.net","name":"Carol Davis","address":"789 Corporate Blvd, Seattle, WA 98101","phone":"555-0103"}' \
        '{"email":"dave@freelance.dev","name":"Dave Wilson","address":"321 Remote Lane, Denver, CO 80202","phone":"555-0104"}'; do

        name=$(echo "$entry" | grep -oE '"name" *: *"[^"]*"' | sed 's/.*: *"//;s/"$//')
        echo -n "  Creating ${name}... "
        cid=$(create_customer "$entry")
        if [ -n "$cid" ]; then
            echo -e "${GREEN}${cid}${NC}"
            CUSTOMER_IDS+=("$cid")
        else
            echo -e "${RED}FAILED${NC}"
        fi
        sleep 0.3
    done
    echo ""

    echo -e "${YELLOW}Step 3: Creating products (small dataset)...${NC}"

    PRODUCT_IDS=()
    for entry in \
        '{"sku":"LAPTOP-GAME-001","name":"Pro Gaming Laptop","description":"High-performance gaming laptop with RTX 4080, 32GB RAM, 1TB SSD","price":1999.99,"quantityOnHand":50,"category":"Electronics"}' \
        '{"sku":"MOUSE-WIRELESS-001","name":"Ergonomic Wireless Mouse","description":"Bluetooth/2.4GHz wireless mouse with ergonomic design","price":49.99,"quantityOnHand":200,"category":"Electronics"}' \
        '{"sku":"KEYBOARD-MECH-001","name":"Mechanical Gaming Keyboard","description":"RGB mechanical keyboard with Cherry MX switches","price":149.99,"quantityOnHand":100,"category":"Electronics"}' \
        '{"sku":"HUB-USBC-001","name":"10-in-1 USB-C Hub","description":"Universal USB-C hub with HDMI, USB 3.0, SD card reader","price":79.99,"quantityOnHand":150,"category":"Electronics"}' \
        '{"sku":"STAND-MON-001","name":"Adjustable Monitor Stand","description":"Height-adjustable monitor stand with cable management","price":129.99,"quantityOnHand":75,"category":"Furniture"}' \
        '{"sku":"WEBCAM-HD-001","name":"4K Streaming Webcam","description":"4K webcam with auto-focus and built-in microphone","price":199.99,"quantityOnHand":80,"category":"Electronics"}'; do

        name=$(echo "$entry" | grep -oE '"name" *: *"[^"]*"' | sed 's/.*: *"//;s/"$//')
        echo -n "  Creating ${name}... "
        pid=$(create_product "$entry")
        if [ -n "$pid" ]; then
            echo -e "${GREEN}${pid}${NC}"
            PRODUCT_IDS+=("$pid")
        else
            echo -e "${RED}FAILED${NC}"
        fi
        sleep 0.3
    done

    CUSTOMER_COUNT=${#CUSTOMER_IDS[@]}
    PRODUCT_COUNT=${#PRODUCT_IDS[@]}

else
    # -----------------------------------------------
    # Full dataset: load from perf/data JSON files
    # -----------------------------------------------
    DATA_DIR="$SCRIPT_DIR/perf/data"
    CUSTOMERS_FILE="$DATA_DIR/customers.json"
    PRODUCTS_FILE="$DATA_DIR/products.json"

    if [ ! -f "$CUSTOMERS_FILE" ] || [ ! -f "$PRODUCTS_FILE" ]; then
        echo -e "${RED}ERROR: Data files not found in ${DATA_DIR}${NC}"
        echo "Expected: customers.json, products.json"
        exit 1
    fi

    CUSTOMER_COUNT=$(python3 -c "import json; print(len(json.load(open('$CUSTOMERS_FILE'))))")
    PRODUCT_COUNT=$(python3 -c "import json; print(len(json.load(open('$PRODUCTS_FILE'))))")

    echo -e "${YELLOW}Step 2: Creating ${CUSTOMER_COUNT} customers...${NC}"

    CUSTOMER_IDS=()
    created=0
    failed=0

    while IFS= read -r line; do
        cid=$(create_customer "$line")
        created=$((created + 1))
        if [ -n "$cid" ]; then
            CUSTOMER_IDS+=("$cid")
        else
            failed=$((failed + 1))
        fi
        # Progress update every 10 items
        if [ $((created % 10)) -eq 0 ]; then
            echo -e "  ${created}/${CUSTOMER_COUNT} customers created..."
        fi
    done < <(python3 -c "
import json, sys
for item in json.load(open('$CUSTOMERS_FILE')):
    print(json.dumps(item))
")

    echo -e "  ${GREEN}Done: ${#CUSTOMER_IDS[@]} created${NC}"
    if [ $failed -gt 0 ]; then
        echo -e "  ${RED}${failed} failed${NC}"
    fi
    echo ""

    echo -e "${YELLOW}Step 3: Creating ${PRODUCT_COUNT} products...${NC}"

    PRODUCT_IDS=()
    created=0
    failed=0

    while IFS= read -r line; do
        pid=$(create_product "$line")
        created=$((created + 1))
        if [ -n "$pid" ]; then
            PRODUCT_IDS+=("$pid")
        else
            failed=$((failed + 1))
        fi
        if [ $((created % 10)) -eq 0 ]; then
            echo -e "  ${created}/${PRODUCT_COUNT} products created..."
        fi
    done < <(python3 -c "
import json, sys
for item in json.load(open('$PRODUCTS_FILE')):
    print(json.dumps(item))
")

    echo -e "  ${GREEN}Done: ${#PRODUCT_IDS[@]} created${NC}"
    if [ $failed -gt 0 ]; then
        echo -e "  ${RED}${failed} failed${NC}"
    fi
fi

# Brief pause for event processing
sleep 2
echo ""

# -----------------------------------------------
# Save IDs for demo scripts
# -----------------------------------------------
echo -e "${YELLOW}Step 4: Saving data IDs for demo scenarios...${NC}"

# Write all IDs as arrays
{
    echo "# Sample Data IDs - Generated $(date)"
    echo "# Dataset: ${DATASET} (${#CUSTOMER_IDS[@]} customers, ${#PRODUCT_IDS[@]} products)"
    echo "# Source this file to use IDs in demo scripts"
    echo ""
    echo "# Service URLs"
    echo "export ACCOUNT_SERVICE=\"$ACCOUNT_SERVICE\""
    echo "export INVENTORY_SERVICE=\"$INVENTORY_SERVICE\""
    echo "export ORDER_SERVICE=\"$ORDER_SERVICE\""
    echo ""
    echo "# Customer IDs (array)"
    echo "CUSTOMER_IDS=("
    for cid in "${CUSTOMER_IDS[@]}"; do
        echo "  \"$cid\""
    done
    echo ")"
    echo ""
    echo "# Product IDs (array)"
    echo "PRODUCT_IDS=("
    for pid in "${PRODUCT_IDS[@]}"; do
        echo "  \"$pid\""
    done
    echo ")"
    echo ""
    echo "# Convenience aliases (first few IDs for quick interactive use)"
    if [ ${#CUSTOMER_IDS[@]} -ge 1 ]; then echo "export CUSTOMER_1=\"${CUSTOMER_IDS[0]}\""; fi
    if [ ${#CUSTOMER_IDS[@]} -ge 2 ]; then echo "export CUSTOMER_2=\"${CUSTOMER_IDS[1]}\""; fi
    if [ ${#CUSTOMER_IDS[@]} -ge 3 ]; then echo "export CUSTOMER_3=\"${CUSTOMER_IDS[2]}\""; fi
    if [ ${#PRODUCT_IDS[@]} -ge 1 ]; then echo "export PRODUCT_1=\"${PRODUCT_IDS[0]}\""; fi
    if [ ${#PRODUCT_IDS[@]} -ge 2 ]; then echo "export PRODUCT_2=\"${PRODUCT_IDS[1]}\""; fi
    if [ ${#PRODUCT_IDS[@]} -ge 3 ]; then echo "export PRODUCT_3=\"${PRODUCT_IDS[2]}\""; fi
} > "$OUTPUT_DIR/sample-data-ids.sh"

echo -e "  Saved to ${GREEN}$OUTPUT_DIR/sample-data-ids.sh${NC}"
echo ""

# -----------------------------------------------
# Summary
# -----------------------------------------------
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Sample Data Loaded Successfully!${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "${GREEN}Created:${NC}"
echo "  - ${#CUSTOMER_IDS[@]} Customers"
echo "  - ${#PRODUCT_IDS[@]} Products"
echo ""
echo -e "${YELLOW}Next Steps:${NC}"
echo "  1. Run demo scenarios:  ./scripts/demo-scenarios.sh"
if [ ${#CUSTOMER_IDS[@]} -ge 1 ]; then
    echo "  2. View customer:      curl $ACCOUNT_SERVICE/api/customers/${CUSTOMER_IDS[0]}"
fi
if [ ${#PRODUCT_IDS[@]} -ge 1 ]; then
    echo "  3. View product:       curl $INVENTORY_SERVICE/api/products/${PRODUCT_IDS[0]}"
    echo "  4. Similar products:   curl $INVENTORY_SERVICE/api/products/${PRODUCT_IDS[0]}/similar"
fi
echo ""
echo -e "${YELLOW}To use IDs in other scripts:${NC}"
echo "  source $OUTPUT_DIR/sample-data-ids.sh"
echo ""
