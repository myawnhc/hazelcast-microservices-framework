#!/bin/bash
#
# load-sample-data.sh - Loads sample data for the eCommerce demo
#
# Usage: ./scripts/load-sample-data.sh
#
# Prerequisites: Services must be running (use ./scripts/start-docker.sh first)
#

set -e

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
OUTPUT_DIR="$(dirname "$0")/../demo-data"
mkdir -p "$OUTPUT_DIR"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Loading Sample Data for eCommerce Demo${NC}"
echo -e "${BLUE}========================================${NC}"
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

# Function to create customer and extract ID
# Handles eventual consistency by extracting ID from success response or error message
create_customer() {
    local email=$1
    local name=$2
    local address=$3
    local phone=$4

    local response=$(curl -s -X POST "$ACCOUNT_SERVICE/api/customers" \
        -H "Content-Type: application/json" \
        -d "{
            \"email\": \"$email\",
            \"name\": \"$name\",
            \"address\": \"$address\",
            \"phone\": \"$phone\"
        }")

    # Try to extract ID from success response first
    local customer_id=$(echo "$response" | grep -oE '"customerId" *: *"[^"]*"' | sed 's/.*: *"//;s/"$//')

    # If not found, extract from error message (eventual consistency case)
    if [ -z "$customer_id" ]; then
        customer_id=$(echo "$response" | grep -o 'Customer not found: [a-f0-9-]*' | cut -d' ' -f4)
    fi

    # Wait briefly for view to update, then verify
    if [ -n "$customer_id" ]; then
        sleep 0.5
        local verify=$(curl -s "$ACCOUNT_SERVICE/api/customers/$customer_id")
        if echo "$verify" | grep -q '"customerId"'; then
            echo "$customer_id"
            return 0
        fi
    fi

    echo "$customer_id"
}

# Function to create product and extract ID
# Handles eventual consistency by extracting ID from success response or error message
create_product() {
    local sku=$1
    local name=$2
    local description=$3
    local price=$4
    local quantity=$5
    local category=$6

    local response=$(curl -s -X POST "$INVENTORY_SERVICE/api/products" \
        -H "Content-Type: application/json" \
        -d "{
            \"sku\": \"$sku\",
            \"name\": \"$name\",
            \"description\": \"$description\",
            \"price\": $price,
            \"quantityOnHand\": $quantity,
            \"category\": \"$category\"
        }")

    # Try to extract ID from success response first
    local product_id=$(echo "$response" | grep -oE '"productId" *: *"[^"]*"' | sed 's/.*: *"//;s/"$//')

    # If not found, extract from error message (eventual consistency case)
    if [ -z "$product_id" ]; then
        product_id=$(echo "$response" | grep -o 'Product not found: [a-f0-9-]*' | cut -d' ' -f4)
    fi

    # Wait briefly for view to update, then verify
    if [ -n "$product_id" ]; then
        sleep 0.5
        local verify=$(curl -s "$INVENTORY_SERVICE/api/products/$product_id")
        if echo "$verify" | grep -q '"productId"'; then
            echo "$product_id"
            return 0
        fi
    fi

    echo "$product_id"
}

# Check all services are healthy
echo -e "${YELLOW}Step 1: Checking service health...${NC}"
check_health "Account Service" "$ACCOUNT_SERVICE" || { echo "Account Service not ready. Run ./scripts/start-docker.sh first."; exit 1; }
check_health "Inventory Service" "$INVENTORY_SERVICE" || { echo "Inventory Service not ready. Run ./scripts/start-docker.sh first."; exit 1; }
check_health "Order Service" "$ORDER_SERVICE" || { echo "Order Service not ready. Run ./scripts/start-docker.sh first."; exit 1; }
echo ""

# Create Customers
echo -e "${YELLOW}Step 2: Creating customers...${NC}"

echo -n "  Creating Alice Smith... "
ALICE_ID=$(create_customer "alice@techcorp.com" "Alice Smith" "100 Tech Park Drive, San Jose, CA 95134" "555-0101")
if [ -n "$ALICE_ID" ]; then
    echo -e "${GREEN}$ALICE_ID${NC}"
else
    echo -e "${RED}FAILED${NC}"
fi

echo -n "  Creating Bob Johnson... "
BOB_ID=$(create_customer "bob@startup.io" "Bob Johnson" "456 Innovation Way, Austin, TX 78701" "555-0102")
if [ -n "$BOB_ID" ]; then
    echo -e "${GREEN}$BOB_ID${NC}"
else
    echo -e "${RED}FAILED${NC}"
fi

echo -n "  Creating Carol Davis... "
CAROL_ID=$(create_customer "carol@enterprise.net" "Carol Davis" "789 Corporate Blvd, Seattle, WA 98101" "555-0103")
if [ -n "$CAROL_ID" ]; then
    echo -e "${GREEN}$CAROL_ID${NC}"
else
    echo -e "${RED}FAILED${NC}"
fi

echo -n "  Creating Dave Wilson... "
DAVE_ID=$(create_customer "dave@freelance.dev" "Dave Wilson" "321 Remote Lane, Denver, CO 80202" "555-0104")
if [ -n "$DAVE_ID" ]; then
    echo -e "${GREEN}$DAVE_ID${NC}"
else
    echo -e "${RED}FAILED${NC}"
fi

# Brief pause for event processing
sleep 1
echo ""

# Create Products
echo -e "${YELLOW}Step 3: Creating products...${NC}"

echo -n "  Creating Gaming Laptop... "
LAPTOP_ID=$(create_product "LAPTOP-GAME-001" "Pro Gaming Laptop" "High-performance gaming laptop with RTX 4080, 32GB RAM, 1TB SSD" 1999.99 50 "Electronics")
if [ -n "$LAPTOP_ID" ]; then
    echo -e "${GREEN}$LAPTOP_ID${NC}"
else
    echo -e "${RED}FAILED${NC}"
fi

echo -n "  Creating Wireless Mouse... "
MOUSE_ID=$(create_product "MOUSE-WIRELESS-001" "Ergonomic Wireless Mouse" "Bluetooth/2.4GHz wireless mouse with ergonomic design" 49.99 200 "Electronics")
if [ -n "$MOUSE_ID" ]; then
    echo -e "${GREEN}$MOUSE_ID${NC}"
else
    echo -e "${RED}FAILED${NC}"
fi

echo -n "  Creating Mechanical Keyboard... "
KEYBOARD_ID=$(create_product "KEYBOARD-MECH-001" "Mechanical Gaming Keyboard" "RGB mechanical keyboard with Cherry MX switches" 149.99 100 "Electronics")
if [ -n "$KEYBOARD_ID" ]; then
    echo -e "${GREEN}$KEYBOARD_ID${NC}"
else
    echo -e "${RED}FAILED${NC}"
fi

echo -n "  Creating USB-C Hub... "
HUB_ID=$(create_product "HUB-USBC-001" "10-in-1 USB-C Hub" "Universal USB-C hub with HDMI, USB 3.0, SD card reader" 79.99 150 "Electronics")
if [ -n "$HUB_ID" ]; then
    echo -e "${GREEN}$HUB_ID${NC}"
else
    echo -e "${RED}FAILED${NC}"
fi

echo -n "  Creating Monitor Stand... "
STAND_ID=$(create_product "STAND-MON-001" "Adjustable Monitor Stand" "Height-adjustable monitor stand with cable management" 129.99 75 "Furniture")
if [ -n "$STAND_ID" ]; then
    echo -e "${GREEN}$STAND_ID${NC}"
else
    echo -e "${RED}FAILED${NC}"
fi

echo -n "  Creating Webcam... "
WEBCAM_ID=$(create_product "WEBCAM-HD-001" "4K Streaming Webcam" "4K webcam with auto-focus and built-in microphone" 199.99 80 "Electronics")
if [ -n "$WEBCAM_ID" ]; then
    echo -e "${GREEN}$WEBCAM_ID${NC}"
else
    echo -e "${RED}FAILED${NC}"
fi

# Brief pause for event processing
sleep 1
echo ""

# Save IDs to file for later use in demo scenarios
echo -e "${YELLOW}Step 4: Saving data IDs for demo scenarios...${NC}"
cat > "$OUTPUT_DIR/sample-data-ids.sh" << EOF
# Sample Data IDs - Generated $(date)
# Source this file to use IDs in demo scripts

# Customer IDs
export ALICE_ID="$ALICE_ID"
export BOB_ID="$BOB_ID"
export CAROL_ID="$CAROL_ID"
export DAVE_ID="$DAVE_ID"

# Product IDs
export LAPTOP_ID="$LAPTOP_ID"
export MOUSE_ID="$MOUSE_ID"
export KEYBOARD_ID="$KEYBOARD_ID"
export HUB_ID="$HUB_ID"
export STAND_ID="$STAND_ID"
export WEBCAM_ID="$WEBCAM_ID"

# Service URLs
export ACCOUNT_SERVICE="$ACCOUNT_SERVICE"
export INVENTORY_SERVICE="$INVENTORY_SERVICE"
export ORDER_SERVICE="$ORDER_SERVICE"
EOF

echo -e "  Saved to ${GREEN}$OUTPUT_DIR/sample-data-ids.sh${NC}"
echo ""

# Summary
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Sample Data Loaded Successfully!${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo -e "${GREEN}Created:${NC}"
echo "  - 4 Customers (Alice, Bob, Carol, Dave)"
echo "  - 6 Products (Laptop, Mouse, Keyboard, Hub, Stand, Webcam)"
echo ""
echo -e "${YELLOW}Next Steps:${NC}"
echo "  1. Run demo scenarios: ./scripts/demo-scenarios.sh"
echo "  2. View customer: curl $ACCOUNT_SERVICE/api/customers/$ALICE_ID"
echo "  3. View product:  curl $INVENTORY_SERVICE/api/products/$LAPTOP_ID"
echo ""
echo -e "${YELLOW}Quick Reference - Customer IDs:${NC}"
echo "  Alice: $ALICE_ID"
echo "  Bob:   $BOB_ID"
echo "  Carol: $CAROL_ID"
echo "  Dave:  $DAVE_ID"
echo ""
echo -e "${YELLOW}Quick Reference - Product IDs:${NC}"
echo "  Laptop:   $LAPTOP_ID"
echo "  Mouse:    $MOUSE_ID"
echo "  Keyboard: $KEYBOARD_ID"
echo "  Hub:      $HUB_ID"
echo "  Stand:    $STAND_ID"
echo "  Webcam:   $WEBCAM_ID"
