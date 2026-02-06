#!/bin/bash
#
# demo-scenarios.sh - Interactive demo scenarios for the eCommerce Event Sourcing Framework
#
# Usage:
#   ./scripts/demo-scenarios.sh           # Interactive menu
#   ./scripts/demo-scenarios.sh 1         # Run scenario 1 directly
#   ./scripts/demo-scenarios.sh 4         # Run scenario 4 directly
#   ./scripts/demo-scenarios.sh all       # Run all scenarios
#
# Prerequisites:
#   1. Services running (./scripts/start-docker.sh)
#   2. Sample data loaded (./scripts/load-sample-data.sh)
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/../demo-data"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m' # No Color

# Service URLs (defaults, can be overridden by sample-data-ids.sh)
ACCOUNT_SERVICE="${ACCOUNT_SERVICE:-http://localhost:8081}"
INVENTORY_SERVICE="${INVENTORY_SERVICE:-http://localhost:8082}"
ORDER_SERVICE="${ORDER_SERVICE:-http://localhost:8083}"
PAYMENT_SERVICE="${PAYMENT_SERVICE:-http://localhost:8084}"

# Load sample data IDs if available, then validate they still exist
# (Hazelcast is in-memory; IDs go stale after Docker restarts)
if [ -f "$OUTPUT_DIR/sample-data-ids.sh" ]; then
    source "$OUTPUT_DIR/sample-data-ids.sh"

    # Validate a known ID — if the service returns 404 or error, IDs are stale
    if [ -n "${ALICE_ID:-}" ]; then
        _check=$(curl -s -o /dev/null -w "%{http_code}" "$ACCOUNT_SERVICE/api/customers/$ALICE_ID" 2>/dev/null)
        if [ "$_check" != "200" ]; then
            echo -e "${YELLOW}Stale sample data detected (services were restarted). Re-loading...${NC}"
            if [ -x "$SCRIPT_DIR/load-sample-data.sh" ]; then
                "$SCRIPT_DIR/load-sample-data.sh"
                # Re-source the freshly written IDs
                source "$OUTPUT_DIR/sample-data-ids.sh"
            else
                echo -e "${YELLOW}Clearing stale IDs — scenarios will create data on the fly.${NC}"
                unset ALICE_ID BOB_ID LAPTOP_ID WATCH_ID BAND_ID
            fi
        fi
    fi
fi

# Helper functions
print_header() {
    echo ""
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}═══════════════════════════════════════════════════════════════${NC}"
    echo ""
}

print_step() {
    echo -e "${CYAN}▶ Step $1: $2${NC}"
}

print_substep() {
    echo -e "  ${YELLOW}→ $1${NC}"
}

print_success() {
    echo -e "  ${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "  ${RED}✗ $1${NC}"
}

print_json() {
    echo "$1" | python3 -m json.tool 2>/dev/null || echo "$1"
}

wait_for_keypress() {
    echo ""
    echo -e "${MAGENTA}Press Enter to continue...${NC}"
    read -r
}

# API helper functions
api_get() {
    curl -s "$1"
}

api_post() {
    curl -s -X POST "$1" -H "Content-Type: application/json" -d "$2"
}

api_patch() {
    curl -s -X PATCH "$1" -H "Content-Type: application/json" ${2:+-d "$2"}
}

extract_id() {
    echo "$1" | grep -oE '"[a-zA-Z]*Id" *: *"[^"]*"' | head -1 | sed 's/.*: *"//;s/"$//'
}

# Wait for an order to reach a target status (saga completion polling)
wait_for_order_status() {
    local order_id=$1
    local target_status=$2
    local max_wait=${3:-30}
    local elapsed=0

    echo -n "  Waiting for order to reach $target_status"
    while [ $elapsed -lt $max_wait ]; do
        local response=$(api_get "$ORDER_SERVICE/api/orders/$order_id")
        local status=$(echo "$response" | grep -oE '"status" *: *"[^"]*"' | sed 's/.*: *"//;s/"$//')
        if [ "$status" = "$target_status" ]; then
            echo ""
            return 0
        fi
        echo -n "."
        sleep 1
        elapsed=$((elapsed + 1))
    done
    echo " (timed out after ${max_wait}s, last status: $status)"
    return 1
}

# ============================================================================
# SCENARIO 1: Happy Path - Complete Order Flow
# ============================================================================
scenario_1_happy_path() {
    print_header "SCENARIO 1: Happy Path - Saga-Driven Order Flow"

    echo "This scenario demonstrates the full saga-orchestrated order lifecycle:"
    echo "  1. Create a customer and products"
    echo "  2. Place an order (starts the Order Fulfillment saga)"
    echo "  3. Saga automatically: reserves stock → processes payment → confirms order"
    echo "  4. Verify final state: order, stock, payment"
    echo ""
    echo "The saga flow: OrderCreated → StockReserved → PaymentProcessed → OrderConfirmed"
    wait_for_keypress

    # Step 1: Create a new customer
    print_step "1" "Creating a new customer"
    print_substep "POST /api/customers"

    local customer_response=$(api_post "$ACCOUNT_SERVICE/api/customers" '{
        "email": "demo.user@example.com",
        "name": "Demo User",
        "address": "123 Demo Street, Demo City, DC 12345",
        "phone": "555-DEMO"
    }')

    local demo_customer_id=$(extract_id "$customer_response")

    if [ -n "$demo_customer_id" ]; then
        print_success "Customer created with ID: $demo_customer_id"
    else
        print_error "Failed to create customer"
        echo "$customer_response"
        return 1
    fi
    wait_for_keypress

    # Step 2: Create products for the order
    print_step "2" "Creating products"
    print_substep "POST /api/products (Smart Watch)"

    local watch_response=$(api_post "$INVENTORY_SERVICE/api/products" '{
        "sku": "WATCH-DEMO-001",
        "name": "Smart Watch Pro",
        "description": "Feature-rich smartwatch with health tracking",
        "price": 299.99,
        "quantityOnHand": 25,
        "category": "Electronics"
    }')

    local watch_id=$(extract_id "$watch_response")

    if [ -n "$watch_id" ]; then
        print_success "Smart Watch created with ID: $watch_id"
    else
        print_error "Failed to create Smart Watch"
    fi

    print_substep "POST /api/products (Watch Band)"

    local band_response=$(api_post "$INVENTORY_SERVICE/api/products" '{
        "sku": "BAND-DEMO-001",
        "name": "Premium Watch Band",
        "description": "Leather watch band for Smart Watch Pro",
        "price": 49.99,
        "quantityOnHand": 100,
        "category": "Accessories"
    }')

    local band_id=$(extract_id "$band_response")

    if [ -n "$band_id" ]; then
        print_success "Watch Band created with ID: $band_id"
    else
        print_error "Failed to create Watch Band"
    fi

    echo ""
    echo "Products created:"
    echo "  - Smart Watch Pro (ID: $watch_id) - \$299.99"
    echo "  - Premium Watch Band (ID: $band_id) - \$49.99"
    wait_for_keypress

    # Brief pause for view updates
    sleep 1

    # Step 3: Place an order (this starts the Order Fulfillment saga)
    print_step "3" "Placing an order (starts Order Fulfillment saga)"
    print_substep "POST /api/orders"
    echo ""
    echo "  The saga will automatically orchestrate:"
    echo "    Step 0: OrderCreated (order-service)"
    echo "    Step 1: StockReserved (inventory-service via ITopic)"
    echo "    Step 2: PaymentProcessed (payment-service via ITopic)"
    echo "    Step 3: OrderConfirmed (order-service via ITopic)"

    local order_response=$(api_post "$ORDER_SERVICE/api/orders" "{
        \"customerId\": \"$demo_customer_id\",
        \"lineItems\": [
            {
                \"productId\": \"$watch_id\",
                \"quantity\": 1,
                \"unitPrice\": 299.99
            },
            {
                \"productId\": \"$band_id\",
                \"quantity\": 2,
                \"unitPrice\": 49.99
            }
        ],
        \"shippingAddress\": \"123 Demo Street, Demo City, DC 12345\"
    }")

    local order_id=$(extract_id "$order_response")

    if [ -n "$order_id" ]; then
        print_success "Order created with ID: $order_id (saga started)"
    else
        print_error "Failed to create order"
        echo "$order_response"
        return 1
    fi
    wait_for_keypress

    # Step 4: Wait for saga to complete (order reaches CONFIRMED)
    print_step "4" "Waiting for saga to complete (order → CONFIRMED)"

    if wait_for_order_status "$order_id" "CONFIRMED" 30; then
        print_success "Saga completed! Order is CONFIRMED"
    else
        print_error "Saga did not complete within 30s"
        echo "  Current order state:"
        local current_order=$(api_get "$ORDER_SERVICE/api/orders/$order_id")
        print_json "$current_order"
    fi
    wait_for_keypress

    # Step 5: Check stock levels (reserved by saga)
    print_step "5" "Checking stock levels (reserved by saga)"

    local watch_after=$(api_get "$INVENTORY_SERVICE/api/products/$watch_id")
    local band_after=$(api_get "$INVENTORY_SERVICE/api/products/$band_id")

    local watch_reserved=$(echo "$watch_after" | grep -oE '"quantityReserved" *: *[0-9]+' | grep -oE '[0-9]+$')
    local band_reserved=$(echo "$band_after" | grep -oE '"quantityReserved" *: *[0-9]+' | grep -oE '[0-9]+$')

    echo "  Stock after saga reservation:"
    echo "    - Smart Watch: ${watch_reserved:-0} reserved"
    echo "    - Watch Band: ${band_reserved:-0} reserved"
    wait_for_keypress

    # Step 6: Check payment (created by saga)
    print_step "6" "Checking payment (processed by saga)"
    print_substep "GET /api/payments/order/$order_id"

    local payment_response=$(api_get "$PAYMENT_SERVICE/api/payments/order/$order_id")
    local payment_status=$(echo "$payment_response" | grep -oE '"status" *: *"[^"]*"' | sed 's/.*: *"//;s/"$//')

    if [ -n "$payment_status" ]; then
        print_success "Payment status: $payment_status"
        print_json "$payment_response"
    else
        echo "  No payment found yet (saga may still be processing)"
    fi
    wait_for_keypress

    # Step 7: Query the enriched order
    print_step "7" "Querying the enriched order (denormalized view)"
    print_substep "GET /api/orders/$order_id"

    local enriched_order=$(api_get "$ORDER_SERVICE/api/orders/$order_id")

    print_success "Enriched order retrieved!"
    echo ""
    echo "Notice the denormalized data:"
    echo "  - Customer name and email are embedded (no service call needed)"
    echo "  - Product availability info is embedded"
    echo ""
    print_json "$enriched_order"
    wait_for_keypress

    # Step 8: Check customer order summary
    print_step "8" "Checking customer order summary (materialized view)"
    print_substep "GET /api/orders/customer/$demo_customer_id"

    local customer_orders=$(api_get "$ORDER_SERVICE/api/orders/customer/$demo_customer_id")

    print_success "Customer order summary retrieved!"
    echo ""
    echo "This view aggregates all orders for a customer:"
    print_json "$customer_orders"

    echo ""
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  SCENARIO 1 COMPLETE: Saga-Driven Order Flow${NC}"
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo ""
    echo "Key observations:"
    echo "  1. The saga orchestrated 4 steps across 3 services automatically"
    echo "  2. Stock was reserved by inventory-service via ITopic event"
    echo "  3. Payment was processed by payment-service via ITopic event"
    echo "  4. Order was confirmed by order-service via ITopic event"
    echo "  5. Materialized views update in near real-time"
    echo "  6. Queries read from views (fast!) not via service calls"

    # Save demo order ID for subsequent scenarios
    echo "export DEMO_ORDER_ID=\"$order_id\"" >> "$OUTPUT_DIR/sample-data-ids.sh"
    echo "export DEMO_CUSTOMER_ID=\"$demo_customer_id\"" >> "$OUTPUT_DIR/sample-data-ids.sh"
    echo "export DEMO_WATCH_ID=\"$watch_id\"" >> "$OUTPUT_DIR/sample-data-ids.sh"
    echo "export DEMO_BAND_ID=\"$band_id\"" >> "$OUTPUT_DIR/sample-data-ids.sh"
}

# ============================================================================
# SCENARIO 2: Order Cancellation
# ============================================================================
scenario_2_cancellation() {
    print_header "SCENARIO 2: Order Cancellation with Automatic Stock Release"

    echo "This scenario demonstrates:"
    echo "  1. Create an order (saga reserves stock and processes payment)"
    echo "  2. Wait for saga to complete (order CONFIRMED)"
    echo "  3. Cancel the order"
    echo "  4. Verify stock is automatically released (via OrderCancelled event)"
    wait_for_keypress

    # Use existing customer or create new one
    local customer_id="${ALICE_ID:-}"

    if [ -z "$customer_id" ]; then
        print_step "0" "Creating a customer for this scenario"
        local customer_response=$(api_post "$ACCOUNT_SERVICE/api/customers" '{
            "email": "cancel.demo@example.com",
            "name": "Cancel Demo User",
            "address": "456 Cancel Street",
            "phone": "555-CANCEL"
        }')
        customer_id=$(extract_id "$customer_response")
        print_success "Customer created: $customer_id"
    fi

    # Use existing product or create new one
    local product_id="${LAPTOP_ID:-}"

    if [ -z "$product_id" ]; then
        print_step "0.5" "Creating a product for this scenario"
        local product_response=$(api_post "$INVENTORY_SERVICE/api/products" '{
            "sku": "CANCEL-DEMO-001",
            "name": "Cancel Demo Product",
            "description": "Product for cancellation demo",
            "price": 499.99,
            "quantityOnHand": 10,
            "category": "Demo"
        }')
        product_id=$(extract_id "$product_response")
        print_success "Product created: $product_id"
    fi

    # Step 1: Check initial stock
    print_step "1" "Checking initial product stock"
    local initial_product=$(api_get "$INVENTORY_SERVICE/api/products/$product_id")
    local initial_qty=$(echo "$initial_product" | grep -oE '"quantityOnHand" *: *[0-9]+' | grep -oE '[0-9]+$')
    local initial_reserved=$(echo "$initial_product" | grep -oE '"quantityReserved" *: *[0-9]+' | grep -oE '[0-9]+$')

    echo "  Initial state:"
    echo "    - Quantity on hand: ${initial_qty:-0}"
    echo "    - Quantity reserved: ${initial_reserved:-0}"
    echo "    - Available: $((${initial_qty:-0} - ${initial_reserved:-0}))"
    wait_for_keypress

    # Step 2: Create order (starts saga - will reserve stock automatically)
    print_step "2" "Creating an order (saga starts automatically)"
    local order_response=$(api_post "$ORDER_SERVICE/api/orders" "{
        \"customerId\": \"$customer_id\",
        \"lineItems\": [
            {
                \"productId\": \"$product_id\",
                \"quantity\": 3,
                \"unitPrice\": 499.99
            }
        ],
        \"shippingAddress\": \"456 Cancel Street\"
    }")

    local order_id=$(extract_id "$order_response")
    print_success "Order created: $order_id"
    wait_for_keypress

    # Step 3: Wait for saga to complete (stock reserved + payment + confirmed)
    print_step "3" "Waiting for saga to complete"

    if wait_for_order_status "$order_id" "CONFIRMED" 30; then
        print_success "Saga completed! Order is CONFIRMED"
    else
        print_error "Saga did not complete within 30s"
    fi

    # Show stock after saga reservation
    local reserved_product=$(api_get "$INVENTORY_SERVICE/api/products/$product_id")
    local reserved_qty=$(echo "$reserved_product" | grep -oE '"quantityReserved" *: *[0-9]+' | grep -oE '[0-9]+$')
    echo "  After saga reservation:"
    echo "    - Quantity reserved: ${reserved_qty:-0} (saga reserved 3 units)"
    echo "    - Available: $((${initial_qty:-0} - ${reserved_qty:-0}))"
    wait_for_keypress

    # Step 4: Cancel the order (triggers OrderCancelled → automatic stock release)
    print_step "4" "Cancelling the order"
    print_substep "PATCH /api/orders/$order_id/cancel"
    echo ""
    echo "  The OrderCancelled event will be published to ITopic."
    echo "  Inventory service listens for this and releases stock automatically."

    local cancel_response=$(api_patch "$ORDER_SERVICE/api/orders/$order_id/cancel" '{
        "reason": "Customer changed mind",
        "cancelledBy": "customer"
    }')

    if echo "$cancel_response" | grep -qE '"status" *: *"CANCELLED"'; then
        print_success "Order cancelled!"
    else
        print_error "Failed to cancel order"
        echo "$cancel_response"
    fi
    wait_for_keypress

    # Brief pause for event processing (stock release via ITopic)
    sleep 3

    # Step 5: Verify stock is restored (automatic release)
    print_step "5" "Verifying stock is automatically restored"
    local final_product=$(api_get "$INVENTORY_SERVICE/api/products/$product_id")
    local final_reserved=$(echo "$final_product" | grep -oE '"quantityReserved" *: *[0-9]+' | grep -oE '[0-9]+$')

    echo "  Final state:"
    echo "    - Quantity on hand: ${initial_qty:-0}"
    echo "    - Quantity reserved: ${final_reserved:-0}"
    echo "    - Available: $((${initial_qty:-0} - ${final_reserved:-0}))"

    if [ "${final_reserved:-0}" -eq "${initial_reserved:-0}" ]; then
        print_success "Stock correctly restored to original state!"
    else
        echo "  Note: Stock may take a moment to fully release via async event processing"
    fi

    echo ""
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  SCENARIO 2 COMPLETE: Order Cancellation${NC}"
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo ""
    echo "Key observations:"
    echo "  1. Order was created and confirmed via saga (automatic)"
    echo "  2. Cancellation published OrderCancelled event to ITopic"
    echo "  3. Inventory service received the event and released stock automatically"
    echo "  4. No manual stock release API call was needed"
}

# ============================================================================
# SCENARIO 3: View Rebuilding (Event Replay)
# ============================================================================
scenario_3_view_rebuild() {
    print_header "SCENARIO 3: View Rebuilding from Event Store"

    echo "This scenario demonstrates the power of event sourcing:"
    echo "  1. Show current view state"
    echo "  2. Explain how views can be rebuilt from events"
    echo "  3. Show the event history for an entity"
    echo ""
    echo -e "${YELLOW}Note: Full view rebuilding requires service restart or admin API.${NC}"
    echo "This demo shows the conceptual flow and event history."
    wait_for_keypress

    # Use existing data or explain
    local customer_id="${ALICE_ID:-}"

    if [ -z "$customer_id" ]; then
        echo -e "${YELLOW}No sample data found. Run ./scripts/load-sample-data.sh first.${NC}"
        echo ""
        echo "The view rebuilding concept works as follows:"
        echo ""
        echo "1. CURRENT STATE (Materialized View)"
        echo "   The view stores the current state of entities."
        echo "   Example: Customer has name 'Alice Smith', status 'ACTIVE'"
        echo ""
        echo "2. EVENT HISTORY (Event Store)"
        echo "   All changes are stored as immutable events:"
        echo "   - CustomerCreatedEvent (timestamp: T1)"
        echo "   - CustomerUpdatedEvent (timestamp: T2)"
        echo "   - CustomerStatusChangedEvent (timestamp: T3)"
        echo ""
        echo "3. REBUILD PROCESS"
        echo "   To rebuild a view:"
        echo "   a) Clear the current view map"
        echo "   b) Replay events from T=0 in sequence order"
        echo "   c) Apply each event to rebuild current state"
        echo ""
        echo "4. BENEFITS"
        echo "   - Fix bugs in view logic, then rebuild"
        echo "   - Add new views by replaying all events"
        echo "   - Audit trail is never lost"
        echo "   - Time travel: query state at any point in history"
        return
    fi

    # Step 1: Show current customer state
    print_step "1" "Current customer state (from materialized view)"
    local customer=$(api_get "$ACCOUNT_SERVICE/api/customers/$customer_id")
    print_json "$customer"
    wait_for_keypress

    # Step 2: Explain event sourcing
    print_step "2" "Understanding Event Sourcing"
    echo ""
    echo "  The materialized view shows the CURRENT state."
    echo "  But underneath, we have the COMPLETE HISTORY as events:"
    echo ""
    echo "  ┌─────────────────────────────────────────────────────┐"
    echo "  │                   EVENT STORE                        │"
    echo "  ├─────────────────────────────────────────────────────┤"
    echo "  │  Seq 1: CustomerCreatedEvent                         │"
    echo "  │         - email: alice@techcorp.com                  │"
    echo "  │         - name: Alice Smith                          │"
    echo "  │         - timestamp: ...                             │"
    echo "  │                                                       │"
    echo "  │  Seq 2: CustomerUpdatedEvent (if any updates)        │"
    echo "  │         - changes recorded                           │"
    echo "  │         - timestamp: ...                             │"
    echo "  └─────────────────────────────────────────────────────┘"
    echo ""
    echo "  ↓ Events are replayed in order ↓"
    echo ""
    echo "  ┌─────────────────────────────────────────────────────┐"
    echo "  │               MATERIALIZED VIEW                      │"
    echo "  ├─────────────────────────────────────────────────────┤"
    echo "  │  Customer:                                           │"
    echo "  │    id: $customer_id"
    echo "  │    email: alice@techcorp.com                         │"
    echo "  │    name: Alice Smith                                 │"
    echo "  │    status: ACTIVE                                    │"
    echo "  └─────────────────────────────────────────────────────┘"
    wait_for_keypress

    # Step 3: Show order with enrichment
    print_step "3" "Cross-service view enrichment"
    echo ""
    echo "  Views can aggregate data from multiple services:"
    echo ""
    echo "  ┌───────────────┐    ┌───────────────┐    ┌───────────────┐"
    echo "  │ Account Svc   │    │ Inventory Svc │    │ Order Service │"
    echo "  │ (Customers)   │    │ (Products)    │    │ (Orders)      │"
    echo "  └───────┬───────┘    └───────┬───────┘    └───────┬───────┘"
    echo "          │                    │                    │"
    echo "          │ CustomerCreated    │ ProductCreated     │ OrderCreated"
    echo "          │      Event         │      Event         │     Event"
    echo "          ▼                    ▼                    ▼"
    echo "  ┌─────────────────────────────────────────────────────────┐"
    echo "  │                  HAZELCAST EVENT BUS                     │"
    echo "  └─────────────────────────────────────────────────────────┘"
    echo "                              │"
    echo "                              ▼"
    echo "  ┌─────────────────────────────────────────────────────────┐"
    echo "  │              ENRICHED ORDER VIEW                         │"
    echo "  │  Order + Customer Name/Email + Product Availability      │"
    echo "  └─────────────────────────────────────────────────────────┘"
    wait_for_keypress

    # Step 4: View rebuild scenarios
    print_step "4" "When to rebuild views"
    echo ""
    echo "  Views should be rebuilt when:"
    echo ""
    echo "  1. BUG FIX: View update logic had a bug"
    echo "     → Fix the ViewUpdater code"
    echo "     → Replay all events to recalculate"
    echo ""
    echo "  2. NEW VIEW: Adding a new denormalized view"
    echo "     → Create new ViewUpdater"
    echo "     → Replay from beginning to populate"
    echo ""
    echo "  3. DISASTER RECOVERY: View data was corrupted"
    echo "     → Clear the view map"
    echo "     → Replay events to restore"
    echo ""
    echo "  4. SCHEMA MIGRATION: View structure changed"
    echo "     → Update view schema"
    echo "     → Replay events with new schema"

    echo ""
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  SCENARIO 3 COMPLETE: View Rebuilding Concepts${NC}"
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo ""
    echo "Key observations:"
    echo "  1. Events are immutable - the audit trail is permanent"
    echo "  2. Views are derived - they can always be rebuilt"
    echo "  3. Multiple views can be built from the same events"
    echo "  4. Event sourcing enables time travel and debugging"
}

# ============================================================================
# SCENARIO 4: Similar Products (Vector Store)
# ============================================================================
scenario_4_similar_products() {
    print_header "SCENARIO 4: Similar Products (Vector Store)"

    echo "This scenario demonstrates the vector store integration:"
    echo "  1. Look up a product from sample data"
    echo "  2. Call the similar products endpoint"
    echo "  3. Display results (Enterprise) or fallback message (Community)"
    echo ""
    echo "The vector store uses cosine similarity on product embeddings."
    echo "Community Edition returns an informational message; Enterprise returns results."
    wait_for_keypress

    # Step 1: Determine product ID
    local product_id="${LAPTOP_ID:-}"

    if [ -z "$product_id" ]; then
        print_step "1" "Creating a product for this scenario"
        local product_response=$(api_post "$INVENTORY_SERVICE/api/products" '{
            "sku": "VECTOR-DEMO-001",
            "name": "Vector Demo Laptop",
            "description": "High-performance laptop for vector search demo",
            "price": 1299.99,
            "quantityOnHand": 50,
            "category": "Electronics"
        }')
        product_id=$(extract_id "$product_response")
        if [ -n "$product_id" ]; then
            print_success "Product created: $product_id"
        else
            print_error "Failed to create product"
            echo "$product_response"
            return 1
        fi
    else
        print_step "1" "Using sample data product (Laptop)"
        print_success "Product ID: $product_id"
    fi
    wait_for_keypress

    # Step 2: Fetch and display product details
    print_step "2" "Fetching product details"
    print_substep "GET /api/products/$product_id"

    local product_details=$(api_get "$INVENTORY_SERVICE/api/products/$product_id")

    if [ -n "$product_details" ]; then
        print_success "Product details retrieved"
        print_json "$product_details"
    else
        print_error "Failed to fetch product details"
        return 1
    fi
    wait_for_keypress

    # Step 3: Call similar products endpoint
    print_step "3" "Finding similar products"
    print_substep "GET /api/products/$product_id/similar?limit=5"

    local similar_response=$(api_get "$INVENTORY_SERVICE/api/products/$product_id/similar?limit=5")

    if [ -z "$similar_response" ]; then
        print_error "No response from similar products endpoint"
        return 1
    fi

    # Parse response fields
    local vector_available=$(echo "$similar_response" | grep -oE '"vectorStoreAvailable" *: *(true|false)' | grep -oE '(true|false)$')
    local implementation=$(echo "$similar_response" | grep -oE '"implementation" *: *"[^"]*"' | sed 's/.*: *"//;s/"$//')
    local message=$(echo "$similar_response" | grep -oE '"message" *: *"[^"]*"' | sed 's/.*: *"//;s/"$//')
    wait_for_keypress

    # Step 4: Display results based on edition
    print_step "4" "Results"

    if [ "$vector_available" = "true" ]; then
        print_success "Vector store is available! (Enterprise Edition)"
        echo ""
        echo "  Implementation: $implementation"
        echo ""
        echo "  Similar products:"
        print_json "$similar_response"
    else
        echo -e "  ${YELLOW}Vector store is not available (Community Edition)${NC}"
        echo ""
        echo "  Implementation: ${implementation:-No-Op (Community Edition)}"
        if [ -n "$message" ]; then
            echo "  Message: $message"
        fi
        echo ""
        echo "  Full response:"
        print_json "$similar_response"
        echo ""
        echo -e "  ${CYAN}To enable vector similarity search:${NC}"
        echo "    1. Obtain a Hazelcast Enterprise license"
        echo "    2. Set the HZ_LICENSEKEY environment variable"
        echo "    3. Restart the services"
        echo "    4. The framework auto-detects Enterprise and enables vector store"
    fi

    echo ""
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  SCENARIO 4 COMPLETE: Similar Products (Vector Store)${NC}"
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo ""
    echo "Key observations:"
    echo "  1. The similar products endpoint works in both editions"
    echo "  2. Community Edition gracefully returns an informational response"
    echo "  3. Enterprise Edition returns actual similarity results"
    echo "  4. Edition detection is fully automatic (no code changes needed)"
}

# ============================================================================
# Main menu
# ============================================================================
show_menu() {
    print_header "eCommerce Event Sourcing Demo"
    echo "Available scenarios:"
    echo ""
    echo "  1) Happy Path - Complete order flow"
    echo "     Create customer → Create products → Place order → Confirm"
    echo ""
    echo "  2) Order Cancellation"
    echo "     Create order → Reserve stock → Cancel → Verify stock released"
    echo ""
    echo "  3) View Rebuilding (Conceptual)"
    echo "     Understanding event replay and view reconstruction"
    echo ""
    echo "  4) Similar Products (Vector Store)"
    echo "     Vector similarity search with Enterprise/Community fallback"
    echo ""
    echo "  all) Run all scenarios"
    echo "  q) Quit"
    echo ""
    echo -n "Select scenario [1-4, all, q]: "
}

# ============================================================================
# Entry point
# ============================================================================

# Handle command line argument
if [ -n "$1" ]; then
    case "$1" in
        1) scenario_1_happy_path ;;
        2) scenario_2_cancellation ;;
        3) scenario_3_view_rebuild ;;
        4) scenario_4_similar_products ;;
        all)
            scenario_1_happy_path
            wait_for_keypress
            scenario_2_cancellation
            wait_for_keypress
            scenario_3_view_rebuild
            wait_for_keypress
            scenario_4_similar_products
            ;;
        *)
            echo "Unknown scenario: $1"
            echo "Usage: $0 [1|2|3|4|all]"
            exit 1
            ;;
    esac
    exit 0
fi

# Interactive menu
while true; do
    show_menu
    read -r choice

    case "$choice" in
        1) scenario_1_happy_path ;;
        2) scenario_2_cancellation ;;
        3) scenario_3_view_rebuild ;;
        4) scenario_4_similar_products ;;
        all)
            scenario_1_happy_path
            wait_for_keypress
            scenario_2_cancellation
            wait_for_keypress
            scenario_3_view_rebuild
            wait_for_keypress
            scenario_4_similar_products
            ;;
        q|Q)
            echo "Goodbye!"
            exit 0
            ;;
        *)
            echo -e "${RED}Invalid choice. Please select 1, 2, 3, 4, all, or q.${NC}"
            ;;
    esac

    echo ""
    echo -e "${MAGENTA}Press Enter to return to menu...${NC}"
    read -r
done
