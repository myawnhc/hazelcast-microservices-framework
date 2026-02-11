# MCP Example Conversations

These examples show how an AI assistant uses the MCP tools to interact with the Hazelcast Microservices Framework.

## 1. Querying Materialized Views

**User:** "Show me all customers"

**Assistant:** *calls `query_view(viewName="customer")`*

> "You have 3 customers:
>
> | Customer ID | Name | Email |
> |-------------|------|-------|
> | c-a1b2 | Alice Johnson | alice@example.com |
> | c-c3d4 | Bob Smith | bob@example.com |
> | c-e5f6 | Carol Williams | carol@example.com |"

---

**User:** "Get details for customer c-a1b2"

**Assistant:** *calls `query_view(viewName="customer", key="c-a1b2")`*

> "Customer c-a1b2:
> - **Name:** Alice Johnson
> - **Email:** alice@example.com
> - **Address:** 100 Main St
> - **Status:** ACTIVE"

---

## 2. Creating Entities via Events

**User:** "Create a new customer named Dan with email dan@example.com"

**Assistant:** *calls `submit_event(eventType="CreateCustomer", payload="{\"name\":\"Dan\",\"email\":\"dan@example.com\",\"address\":\"500 Elm St\"}")`*

> "Customer created successfully:
> - **Customer ID:** c-g7h8
> - **Name:** Dan
> - **Email:** dan@example.com"

---

**User:** "Add a product called 'Wireless Headphones' priced at $79.99 with 200 in stock"

**Assistant:** *calls `submit_event(eventType="CreateProduct", payload="{\"sku\":\"WH-001\",\"name\":\"Wireless Headphones\",\"price\":\"79.99\",\"quantityOnHand\":200}")`*

> "Product created:
> - **Product ID:** p-i9j0
> - **SKU:** WH-001
> - **Name:** Wireless Headphones
> - **Price:** $79.99
> - **Stock:** 200"

---

## 3. Inspecting Saga State

**User:** "Show me all pending orders"

**Assistant:** *calls `query_view(viewName="order")`*

> "You have 3 orders:
>
> | Order ID | Customer | Status | Total |
> |----------|----------|--------|-------|
> | ORD-001 | Alice Johnson | PENDING | $199.98 |
> | ORD-002 | Bob Smith | CONFIRMED | $49.99 |
> | ORD-003 | Carol Williams | PENDING | $249.95 |"

---

**User:** "What's happening with order ORD-001's saga?"

**Assistant:** *calls `list_sagas(status="IN_PROGRESS")`*

*calls `inspect_saga(sagaId="saga-a7f3e2")`*

> "Order ORD-001 is in an active saga:
> - **Saga ID:** saga-a7f3e2
> - **Status:** IN_PROGRESS
> - **Step 2/4:** Stock Reserved
> - **Waiting for:** Payment processing
> - **Duration:** 1.2 seconds"

---

## 4. Viewing Event History

**User:** "Show me the event history for order ORD-002"

**Assistant:** *calls `get_event_history(aggregateId="ORD-002", aggregateType="Order")`*

> "Event history for order ORD-002 (4 events):
>
> 1. **OrderCreated** - 2026-01-15 10:00:00
>    - Customer: Bob Smith, Total: $49.99
> 2. **StockReserved** - 2026-01-15 10:00:01
>    - Product: Standard Widget, Qty: 1
> 3. **PaymentProcessed** - 2026-01-15 10:00:02
>    - Amount: $49.99, Method: CREDIT_CARD
> 4. **OrderConfirmed** - 2026-01-15 10:00:02
>    - Status: CONFIRMED"

---

## 5. Checking System Metrics

**User:** "How's the system doing?"

**Assistant:** *calls `get_metrics()`*

> "System metrics summary:
>
> **Sagas:**
> - Total: 142
> - Active: 3
> - Completed: 128
> - Failed: 8
> - Timed out: 3
>
> **Events:**
> - Total processed: 12,580
> - Events/second: 45.2
>
> **Services:** All 4 services healthy"

---

## 6. Running Demo Scenarios

**User:** "Run the happy path demo"

**Assistant:** *calls `run_demo(scenario="happy_path")`*

> "Demo completed successfully!
>
> **Steps:**
> 1. Created customer: **Demo Customer** (c-demo-123)
> 2. Created product: **Demo Widget** at $29.99 (p-demo-456)
> 3. Created order: 2x Demo Widget = $59.98 (o-demo-789)
>
> The order will trigger a saga that reserves stock, processes payment, and confirms the order."

---

**User:** "Now show me a payment failure"

**Assistant:** *calls `run_demo(scenario="payment_failure")`*

> "Payment failure demo completed!
>
> **Steps:**
> 1. Created customer: **Big Spender** (c-fail-123)
> 2. Created product: **Premium Item** at $5,500.00 (p-fail-456)
> 3. Created order: 3x Premium Item = $16,500.00 (o-fail-789)
>
> **Expected outcome:** The total exceeds $10,000, so payment will be rejected. This triggers saga compensation: the reserved stock will be released."

---

## 7. Multi-Tool Investigation

**User:** "I see some failed sagas. Can you investigate?"

**Assistant:** *calls `list_sagas(status="FAILED")`*

> "Found 2 failed sagas. Let me investigate..."

*calls `inspect_saga(sagaId="saga-fail-1")`*

*calls `inspect_saga(sagaId="saga-fail-2")`*

> "Investigation results:
>
> **Saga saga-fail-1:**
> - Order: ORD-088
> - Failed at: Payment processing
> - Reason: Payment declined (amount $15,000 exceeds limit)
> - Compensation: Stock released successfully
>
> **Saga saga-fail-2:**
> - Order: ORD-091
> - Failed at: Stock reservation
> - Reason: Insufficient stock (requested 50, available 12)
> - Compensation: No compensation needed (failed before reservation)"

---

## 8. Loading Sample Data

**User:** "Set up some sample data for testing"

**Assistant:** *calls `run_demo(scenario="load_sample_data")`*

> "Sample data loaded:
>
> **Customers (3):**
> - Alice Johnson (alice@example.com)
> - Bob Smith (bob@example.com)
> - Carol Williams (carol@example.com)
>
> **Products (3):**
> - Standard Widget - $9.99 (500 in stock)
> - Premium Gadget - $49.99 (200 in stock)
> - Deluxe Gizmo - $99.99 (100 in stock)
>
> You can now place orders, run demos, or query these entities."
