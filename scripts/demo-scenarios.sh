#!/bin/bash
#
# demo-scenarios.sh - Interactive demo scenarios for the eCommerce Event Sourcing Framework
#
# Usage:
#   ./scripts/demo-scenarios.sh           # Interactive menu
#   ./scripts/demo-scenarios.sh 1         # Run scenario 1 directly
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

# Load sample data IDs if available
if [ -f "$OUTPUT_DIR/sample-data-ids.sh" ]; then
    source "$OUTPUT_DIR/sample-data-ids.sh"
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
    echo "$1" | grep -o '"[a-zA-Z]*Id":"[^"]*"' | head -1 | cut -d'"' -f4
}

# ============================================================================
# SCENARIO 1: Happy Path - Complete Order Flow
# ============================================================================
scenario_1_happy_path() {
    print_header "SCENARIO 1: Happy Path - Complete Order Flow"

    echo "This scenario demonstrates the complete order lifecycle:"
    echo "  1. Create a customer"
    echo "  2. Create products"
    echo "  3. Place an order"
    echo "  4. Reserve stock"
    echo "  5. Confirm the order"
    echo "  6. Query the enriched order (denormalized view)"
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
        echo ""
        echo "Response:"
        print_json "$customer_response"
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

    # Brief pause for event processing
    sleep 1

    # Step 3: Place an order
    print_step "3" "Placing an order"
    print_substep "POST /api/orders"

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
        print_success "Order created with ID: $order_id"
        echo ""
        echo "Order details:"
        print_json "$order_response"
    else
        print_error "Failed to create order"
        echo "$order_response"
        return 1
    fi
    wait_for_keypress

    # Step 4: Reserve stock
    print_step "4" "Reserving stock for order items"
    print_substep "POST /api/products/$watch_id/stock/reserve"

    local reserve_watch=$(api_post "$INVENTORY_SERVICE/api/products/$watch_id/stock/reserve" "{
        \"quantity\": 1,
        \"orderId\": \"$order_id\"
    }")
    print_success "Reserved 1 Smart Watch"

    print_substep "POST /api/products/$band_id/stock/reserve"

    local reserve_band=$(api_post "$INVENTORY_SERVICE/api/products/$band_id/stock/reserve" "{
        \"quantity\": 2,
        \"orderId\": \"$order_id\"
    }")
    print_success "Reserved 2 Watch Bands"

    # Check product inventory after reservation
    echo ""
    echo "Product inventory after reservation:"
    local watch_after=$(api_get "$INVENTORY_SERVICE/api/products/$watch_id")
    local band_after=$(api_get "$INVENTORY_SERVICE/api/products/$band_id")

    local watch_reserved=$(echo "$watch_after" | grep -o '"quantityReserved":[0-9]*' | cut -d':' -f2)
    local band_reserved=$(echo "$band_after" | grep -o '"quantityReserved":[0-9]*' | cut -d':' -f2)

    echo "  - Smart Watch: $watch_reserved reserved"
    echo "  - Watch Band: $band_reserved reserved"
    wait_for_keypress

    # Step 5: Confirm the order
    print_step "5" "Confirming the order"
    print_substep "PATCH /api/orders/$order_id/confirm"

    local confirm_response=$(api_patch "$ORDER_SERVICE/api/orders/$order_id/confirm")

    if echo "$confirm_response" | grep -q '"status":"CONFIRMED"'; then
        print_success "Order confirmed!"
    else
        print_error "Failed to confirm order"
    fi
    wait_for_keypress

    # Brief pause for view updates
    sleep 1

    # Step 6: Query the enriched order
    print_step "6" "Querying the enriched order (denormalized view)"
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

    # Step 7: Check customer order summary
    print_step "7" "Checking customer order summary (materialized view)"
    print_substep "GET /api/orders/customer/$demo_customer_id"

    local customer_orders=$(api_get "$ORDER_SERVICE/api/orders/customer/$demo_customer_id")

    print_success "Customer order summary retrieved!"
    echo ""
    echo "This view aggregates all orders for a customer:"
    print_json "$customer_orders"

    echo ""
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  SCENARIO 1 COMPLETE: Happy Path Order Flow${NC}"
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo ""
    echo "Key observations:"
    echo "  1. Events flow through the pipeline automatically"
    echo "  2. Materialized views update in near real-time"
    echo "  3. Queries read from views (fast!) not via service calls"
    echo "  4. Customer and product data is denormalized into orders"

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
    print_header "SCENARIO 2: Order Cancellation with Stock Release"

    echo "This scenario demonstrates:"
    echo "  1. Create an order"
    echo "  2. Reserve stock"
    echo "  3. Cancel the order"
    echo "  4. Verify stock is released"
    echo "  5. Check views are updated"
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
    local initial_qty=$(echo "$initial_product" | grep -o '"quantityOnHand":[0-9]*' | cut -d':' -f2)
    local initial_reserved=$(echo "$initial_product" | grep -o '"quantityReserved":[0-9]*' | cut -d':' -f2)

    echo "  Initial state:"
    echo "    - Quantity on hand: $initial_qty"
    echo "    - Quantity reserved: $initial_reserved"
    echo "    - Available: $((initial_qty - initial_reserved))"
    wait_for_keypress

    # Step 2: Create order
    print_step "2" "Creating an order"
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
    print_json "$order_response"
    wait_for_keypress

    # Step 3: Reserve stock
    print_step "3" "Reserving stock (3 units)"
    local reserve_response=$(api_post "$INVENTORY_SERVICE/api/products/$product_id/stock/reserve" "{
        \"quantity\": 3,
        \"orderId\": \"$order_id\"
    }")
    print_success "Stock reserved"

    # Check stock after reservation
    local reserved_product=$(api_get "$INVENTORY_SERVICE/api/products/$product_id")
    local reserved_qty=$(echo "$reserved_product" | grep -o '"quantityReserved":[0-9]*' | cut -d':' -f2)
    echo "  After reservation:"
    echo "    - Quantity reserved: $reserved_qty"
    echo "    - Available: $((initial_qty - reserved_qty))"
    wait_for_keypress

    # Step 4: Cancel the order
    print_step "4" "Cancelling the order"
    print_substep "PATCH /api/orders/$order_id/cancel"

    local cancel_response=$(api_patch "$ORDER_SERVICE/api/orders/$order_id/cancel" '{
        "reason": "Customer changed mind",
        "cancelledBy": "customer"
    }')

    if echo "$cancel_response" | grep -q '"status":"CANCELLED"'; then
        print_success "Order cancelled!"
        print_json "$cancel_response"
    else
        print_error "Failed to cancel order"
        echo "$cancel_response"
    fi
    wait_for_keypress

    # Step 5: Release the stock
    print_step "5" "Releasing reserved stock"
    print_substep "POST /api/products/$product_id/stock/release"

    local release_response=$(api_post "$INVENTORY_SERVICE/api/products/$product_id/stock/release" "{
        \"quantity\": 3,
        \"orderId\": \"$order_id\"
    }")
    print_success "Stock released"

    # Brief pause for event processing
    sleep 1

    # Step 6: Verify stock is restored
    print_step "6" "Verifying stock is restored"
    local final_product=$(api_get "$INVENTORY_SERVICE/api/products/$product_id")
    local final_reserved=$(echo "$final_product" | grep -o '"quantityReserved":[0-9]*' | cut -d':' -f2)

    echo "  Final state:"
    echo "    - Quantity on hand: $initial_qty"
    echo "    - Quantity reserved: $final_reserved"
    echo "    - Available: $((initial_qty - final_reserved))"

    if [ "$final_reserved" -eq "$initial_reserved" ]; then
        print_success "Stock correctly restored to original state!"
    else
        print_error "Stock mismatch - expected $initial_reserved reserved, got $final_reserved"
    fi

    echo ""
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}  SCENARIO 2 COMPLETE: Order Cancellation${NC}"
    echo -e "${GREEN}═══════════════════════════════════════════════════════════════${NC}"
    echo ""
    echo "Key observations:"
    echo "  1. Order status changed from PENDING to CANCELLED"
    echo "  2. Stock release event restored available inventory"
    echo "  3. Events capture the full audit trail of the cancellation"
    echo "  4. Views update to reflect the cancellation"
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
    echo "  all) Run all scenarios"
    echo "  q) Quit"
    echo ""
    echo -n "Select scenario [1-3, all, q]: "
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
        all)
            scenario_1_happy_path
            wait_for_keypress
            scenario_2_cancellation
            wait_for_keypress
            scenario_3_view_rebuild
            ;;
        *)
            echo "Unknown scenario: $1"
            echo "Usage: $0 [1|2|3|all]"
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
        all)
            scenario_1_happy_path
            wait_for_keypress
            scenario_2_cancellation
            wait_for_keypress
            scenario_3_view_rebuild
            ;;
        q|Q)
            echo "Goodbye!"
            exit 0
            ;;
        *)
            echo -e "${RED}Invalid choice. Please select 1, 2, 3, all, or q.${NC}"
            ;;
    esac

    echo ""
    echo -e "${MAGENTA}Press Enter to return to menu...${NC}"
    read -r
done
