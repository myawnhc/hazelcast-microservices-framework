# Demo Walkthrough Guide

This guide walks you through demonstrating the Hazelcast Event Sourcing Microservices Framework using the eCommerce sample application.

## Prerequisites

Before running the demo, ensure you have:

- Docker and Docker Compose installed
- At least 4GB RAM available for containers
- `curl` and `jq` (optional, for pretty-printing JSON)
- Bash shell (macOS/Linux or WSL on Windows)

## Quick Start

```bash
# 1. Build and start all services
./scripts/build-docker.sh
./scripts/start-docker.sh

# 2. Load sample data
./scripts/load-sample-data.sh

# 3. Run interactive demo
./scripts/demo-scenarios.sh
```

## Demo Architecture

```
                    ┌─────────────────────────────────────────────────────┐
                    │                   Docker Network                     │
                    │                                                      │
   ┌────────────┐   │   ┌────────────┐  ┌────────────┐  ┌────────────┐   │
   │   Client   │───┼──►│  Account   │  │ Inventory  │  │   Order    │   │
   │  (curl)    │   │   │  Service   │  │  Service   │  │  Service   │   │
   └────────────┘   │   │  :8081     │  │  :8082     │  │  :8083     │   │
                    │   └─────┬──────┘  └─────┬──────┘  └─────┬──────┘   │
                    │         │               │               │          │
                    │         └───────────────┼───────────────┘          │
                    │                         │                          │
                    │                         ▼                          │
                    │   ┌─────────────────────────────────────────────┐  │
                    │   │           Hazelcast Cluster (3 nodes)        │  │
                    │   │  ┌─────────┐  ┌─────────┐  ┌─────────┐      │  │
                    │   │  │ Node 1  │  │ Node 2  │  │ Node 3  │      │  │
                    │   │  │ :5701   │  │ :5702   │  │ :5703   │      │  │
                    │   │  └─────────┘  └─────────┘  └─────────┘      │  │
                    │   │                                              │  │
                    │   │  - Event Store (IMap)                        │  │
                    │   │  - Materialized Views (IMap)                 │  │
                    │   │  - Event Bus (ITopic)                        │  │
                    │   │  - Jet Pipeline (stream processing)          │  │
                    │   └─────────────────────────────────────────────┘  │
                    │                                                      │
                    │   ┌────────────┐                                    │
                    │   │ Prometheus │ Metrics collection :9090          │
                    │   └────────────┘                                    │
                    └─────────────────────────────────────────────────────┘
```

## Service Endpoints

| Service | Port | Endpoints |
|---------|------|-----------|
| Account | 8081 | `POST /api/customers`, `GET /api/customers/{id}`, `PUT /api/customers/{id}`, `PATCH /api/customers/{id}/status` |
| Inventory | 8082 | `POST /api/products`, `GET /api/products/{id}`, `POST /api/products/{id}/stock/reserve`, `POST /api/products/{id}/stock/release` |
| Order | 8083 | `POST /api/orders`, `GET /api/orders/{id}`, `GET /api/orders/customer/{id}`, `PATCH /api/orders/{id}/confirm`, `PATCH /api/orders/{id}/cancel` |

---

## Demo Scenario 1: Happy Path Order Flow

This scenario demonstrates the complete order lifecycle with event sourcing.

### What You'll See

1. **Event-driven state changes** - Each API call creates an event that flows through the pipeline
2. **Materialized views** - Data is denormalized for fast queries
3. **Cross-service data enrichment** - Orders contain customer and product info without service calls

### Step-by-Step

#### 1. Create a Customer

```bash
curl -X POST http://localhost:8081/api/customers \
  -H "Content-Type: application/json" \
  -d '{
    "email": "alice@example.com",
    "name": "Alice Smith",
    "address": "123 Main Street, Anytown, USA",
    "phone": "555-1234"
  }' | jq
```

**Response:**
```json
{
  "customerId": "cust-12345-uuid",
  "email": "alice@example.com",
  "name": "Alice Smith",
  "address": "123 Main Street, Anytown, USA",
  "phone": "555-1234",
  "status": "ACTIVE"
}
```

**What happened:**
1. `CustomerCreatedEvent` was created
2. Event written to pending events map
3. Jet pipeline processed the event
4. Event stored in event store
5. Customer view updated
6. Event published to subscribers

#### 2. Create a Product

```bash
curl -X POST http://localhost:8082/api/products \
  -H "Content-Type: application/json" \
  -d '{
    "sku": "LAPTOP-001",
    "name": "Gaming Laptop",
    "description": "High-performance gaming laptop",
    "price": 1299.99,
    "quantityOnHand": 50,
    "category": "Electronics"
  }' | jq
```

**Response:**
```json
{
  "productId": "prod-12345-uuid",
  "sku": "LAPTOP-001",
  "name": "Gaming Laptop",
  "description": "High-performance gaming laptop",
  "price": 1299.99,
  "quantityOnHand": 50,
  "quantityReserved": 0,
  "category": "Electronics",
  "status": "ACTIVE"
}
```

#### 3. Place an Order

```bash
# Use the IDs from previous responses
CUSTOMER_ID="cust-12345-uuid"
PRODUCT_ID="prod-12345-uuid"

curl -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d "{
    \"customerId\": \"$CUSTOMER_ID\",
    \"lineItems\": [
      {
        \"productId\": \"$PRODUCT_ID\",
        \"quantity\": 2,
        \"unitPrice\": 1299.99
      }
    ],
    \"shippingAddress\": \"123 Main Street, Anytown, USA\"
  }" | jq
```

**Response:**
```json
{
  "orderId": "order-12345-uuid",
  "customerId": "cust-12345-uuid",
  "customerName": "Alice Smith",
  "customerEmail": "alice@example.com",
  "lineItems": [
    {
      "productId": "prod-12345-uuid",
      "productName": "Gaming Laptop",
      "sku": "LAPTOP-001",
      "quantity": 2,
      "unitPrice": 1299.99,
      "lineTotal": 2599.98
    }
  ],
  "subtotal": 2599.98,
  "tax": 259.998,
  "total": 2859.978,
  "shippingAddress": "123 Main Street, Anytown, USA",
  "status": "PENDING"
}
```

**Notice:** The order response already contains `customerName` and `customerEmail` - this data was denormalized from the customer view, NOT fetched via a service call!

#### 4. Reserve Stock

```bash
curl -X POST "http://localhost:8082/api/products/$PRODUCT_ID/stock/reserve" \
  -H "Content-Type: application/json" \
  -d '{
    "quantity": 2,
    "orderId": "order-12345-uuid"
  }' | jq
```

#### 5. Confirm the Order

```bash
ORDER_ID="order-12345-uuid"

curl -X PATCH "http://localhost:8083/api/orders/$ORDER_ID/confirm" | jq
```

**Response:**
```json
{
  "orderId": "order-12345-uuid",
  "status": "CONFIRMED",
  ...
}
```

#### 6. Query Customer Order Summary

```bash
curl "http://localhost:8083/api/orders/customer/$CUSTOMER_ID" | jq
```

This returns an aggregated view of all orders for the customer - another materialized view!

---

## Demo Scenario 2: Order Cancellation

This scenario shows how cancellation events flow through the system and release reserved stock.

### Step-by-Step

#### 1. Create and Reserve Stock for an Order

```bash
# Create order (same as above)
# Reserve stock

# Verify stock is reserved
curl "http://localhost:8082/api/products/$PRODUCT_ID" | jq
# Shows quantityReserved > 0
```

#### 2. Cancel the Order

```bash
curl -X PATCH "http://localhost:8083/api/orders/$ORDER_ID/cancel" | jq
```

**Response:**
```json
{
  "orderId": "order-12345-uuid",
  "status": "CANCELLED",
  ...
}
```

#### 3. Release Stock

```bash
curl -X POST "http://localhost:8082/api/products/$PRODUCT_ID/stock/release" \
  -H "Content-Type: application/json" \
  -d '{
    "quantity": 2,
    "orderId": "order-12345-uuid"
  }' | jq
```

#### 4. Verify Stock Released

```bash
curl "http://localhost:8082/api/products/$PRODUCT_ID" | jq
# Shows quantityReserved back to original value
```

### Key Points

- `OrderCancelledEvent` records the cancellation with reason
- `StockReleasedEvent` returns reserved quantity to available
- All events are permanently stored for audit
- Views update automatically to reflect the cancellation

---

## Demo Scenario 3: View Rebuilding

This scenario explains how event sourcing enables view rebuilding.

### Conceptual Overview

In a traditional CRUD system, if your view logic has a bug, you're stuck with corrupted data. With event sourcing:

1. **Events are immutable** - The complete history is preserved
2. **Views are derived** - They can be rebuilt from events at any time
3. **Time travel** - You can query state at any point in history

### When to Rebuild Views

1. **Bug Fix** - View update logic had a bug, replay to fix
2. **New View** - Adding a new denormalized view, populate from events
3. **Disaster Recovery** - View data corrupted, restore from events
4. **Schema Migration** - View structure changed, replay with new schema

### How View Rebuilding Works

```
┌─────────────────────────────────────────────────────────────┐
│                        EVENT STORE                          │
│                                                             │
│  Seq 1: CustomerCreatedEvent (T1)                          │
│  Seq 2: ProductCreatedEvent (T2)                           │
│  Seq 3: OrderCreatedEvent (T3)                             │
│  Seq 4: StockReservedEvent (T4)                            │
│  Seq 5: OrderConfirmedEvent (T5)                           │
│  ...                                                        │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            │ Replay events in sequence order
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    VIEW UPDATER                             │
│                                                             │
│  For each event:                                            │
│    1. Deserialize event                                     │
│    2. Apply event.apply(currentState)                       │
│    3. Store updated state in view                           │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                  MATERIALIZED VIEW                          │
│                                                             │
│  Customer View: {id, name, email, status}                   │
│  Product View: {id, sku, name, price, qty, reserved}        │
│  Order View: {id, customerId, items, total, status}         │
│  Enriched Order: {order + customer name + product names}    │
└─────────────────────────────────────────────────────────────┘
```

---

## Monitoring the Demo

### Hazelcast Management

Each Hazelcast node exposes health endpoints:

```bash
# Check Hazelcast cluster health
curl http://localhost:5701/hazelcast/health
curl http://localhost:5702/hazelcast/health
curl http://localhost:5703/hazelcast/health
```

### Prometheus Metrics

Access Prometheus at http://localhost:9090

Useful queries:
- `events_submitted_total` - Total events submitted
- `events_processed_total` - Total events processed by pipeline
- `view_updates_total` - Total view updates
- `http_server_requests_seconds_count` - HTTP request counts

### Service Health

```bash
# Check each service
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
```

---

## Troubleshooting

### Services Won't Start

```bash
# Check Docker logs
docker-compose -f docker/docker-compose.yml logs

# Check specific service
docker-compose -f docker/docker-compose.yml logs account-service
```

### Hazelcast Cluster Issues

```bash
# Ensure all 3 nodes are healthy before starting services
docker-compose -f docker/docker-compose.yml ps

# Restart Hazelcast cluster
docker-compose -f docker/docker-compose.yml restart hazelcast-1 hazelcast-2 hazelcast-3
```

### View Not Updating

1. Check the event was created (look at service logs)
2. Check Jet pipeline is running
3. Verify event journal is enabled on the map

```bash
# View service logs for event processing
docker-compose -f docker/docker-compose.yml logs -f order-service
```

---

## Demo Script Summary

### Quick Demo (5 minutes)

```bash
# Start everything
./scripts/start-docker.sh
./scripts/load-sample-data.sh

# Show the happy path
./scripts/demo-scenarios.sh 1
```

### Full Demo (15 minutes)

```bash
# Start everything
./scripts/start-docker.sh
./scripts/load-sample-data.sh

# Run all scenarios with explanations
./scripts/demo-scenarios.sh all
```

### Manual Demo

Use the `curl` commands in this guide to walk through each step manually, explaining the event sourcing concepts as you go.

---

## Key Talking Points

When presenting this demo, emphasize:

1. **Events are First-Class Citizens**
   - Every state change is captured as an immutable event
   - Complete audit trail is automatic
   - No data is ever lost

2. **Materialized Views for Performance**
   - Queries read from pre-computed views
   - No service-to-service calls for data
   - Eventual consistency (usually milliseconds)

3. **Hazelcast Powers It All**
   - Event store: IMap with persistence
   - Stream processing: Jet pipeline
   - Event bus: ITopic for pub/sub
   - Views: IMap with near-cache

4. **Production-Ready Patterns**
   - Correlation IDs for tracing
   - Saga support for distributed transactions
   - Metrics for observability
   - Health checks for orchestration
