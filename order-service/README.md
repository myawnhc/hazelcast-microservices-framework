# Order Service

Order management microservice built on the event sourcing framework.

## Overview

The Order Service manages the complete order lifecycle from creation through fulfillment or cancellation. It coordinates with Account and Inventory services through event-driven communication and maintains enriched views of orders with denormalized customer and product data.

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/orders` | Create a new order |
| GET | `/api/orders/{id}` | Get order by ID |
| GET | `/api/orders/customer/{customerId}` | Get all orders for a customer |
| PATCH | `/api/orders/{id}/confirm` | Confirm an order |
| PATCH | `/api/orders/{id}/cancel` | Cancel an order |

## Domain Model

### Order

| Field | Type | Description |
|-------|------|-------------|
| orderId | String | Unique identifier (UUID) |
| customerId | String | Reference to customer |
| lineItems | List<OrderLineItem> | Products in the order |
| shippingAddress | String | Delivery address |
| subtotal | BigDecimal | Sum of line item totals |
| tax | BigDecimal | Calculated tax (10%) |
| total | BigDecimal | subtotal + tax |
| status | String | Order status (see below) |
| createdAt | Instant | Order creation timestamp |
| confirmedAt | Instant | Confirmation timestamp |
| cancelledAt | Instant | Cancellation timestamp |

### OrderLineItem

| Field | Type | Description |
|-------|------|-------------|
| productId | String | Reference to product |
| productName | String | Product name (denormalized) |
| sku | String | Product SKU |
| quantity | int | Quantity ordered |
| unitPrice | BigDecimal | Price per unit |
| lineTotal | BigDecimal | quantity * unitPrice |

### Order Status

| Status | Description |
|--------|-------------|
| PENDING | Order created, awaiting confirmation |
| CONFIRMED | Order confirmed, ready for fulfillment |
| SHIPPED | Order shipped (future) |
| DELIVERED | Order delivered (future) |
| CANCELLED | Order cancelled |

### Events

- **OrderCreatedEvent**: New order placed
- **OrderConfirmedEvent**: Order confirmed
- **OrderCancelledEvent**: Order cancelled

## API Examples

### Create Order

```bash
curl -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "cust-550e8400-e29b-41d4-a716-446655440000",
    "lineItems": [
      {
        "productId": "prod-123",
        "productName": "Gaming Laptop",
        "sku": "LAPTOP-001",
        "quantity": 1,
        "unitPrice": 1999.99
      },
      {
        "productId": "prod-456",
        "productName": "Wireless Mouse",
        "sku": "MOUSE-001",
        "quantity": 2,
        "unitPrice": 49.99
      }
    ],
    "shippingAddress": "123 Main St, City, ST 12345"
  }'
```

Response:
```json
{
  "orderId": "order-550e8400-e29b-41d4-a716-446655440001",
  "customerId": "cust-550e8400-e29b-41d4-a716-446655440000",
  "lineItems": [
    {
      "productId": "prod-123",
      "productName": "Gaming Laptop",
      "sku": "LAPTOP-001",
      "quantity": 1,
      "unitPrice": 1999.99,
      "lineTotal": 1999.99
    },
    {
      "productId": "prod-456",
      "productName": "Wireless Mouse",
      "sku": "MOUSE-001",
      "quantity": 2,
      "unitPrice": 49.99,
      "lineTotal": 99.98
    }
  ],
  "shippingAddress": "123 Main St, City, ST 12345",
  "subtotal": 2099.97,
  "tax": 209.997,
  "total": 2309.967,
  "status": "PENDING",
  "createdAt": "2024-01-15T14:30:00Z"
}
```

### Get Order

```bash
curl http://localhost:8083/api/orders/order-550e8400-e29b-41d4-a716-446655440001
```

### Get Orders by Customer

```bash
curl http://localhost:8083/api/orders/customer/cust-550e8400-e29b-41d4-a716-446655440000
```

### Confirm Order

```bash
curl -X PATCH http://localhost:8083/api/orders/order-550e8400-e29b-41d4-a716-446655440001/confirm
```

Response:
```json
{
  "orderId": "order-550e8400-e29b-41d4-a716-446655440001",
  "status": "CONFIRMED",
  "confirmedAt": "2024-01-15T14:35:00Z"
}
```

### Cancel Order

```bash
curl -X PATCH http://localhost:8083/api/orders/order-550e8400-e29b-41d4-a716-446655440001/cancel \
  -H "Content-Type: application/json" \
  -d '{
    "reason": "Customer changed their mind",
    "cancelledBy": "customer"
  }'
```

Response:
```json
{
  "orderId": "order-550e8400-e29b-41d4-a716-446655440001",
  "status": "CANCELLED",
  "cancelledAt": "2024-01-15T14:40:00Z"
}
```

## Order Lifecycle

```
       ┌──────────────────────────────────────────────────────┐
       │                                                      │
       ▼                                                      │
  ┌─────────┐      ┌───────────┐      ┌───────────┐      ┌───────────┐
  │ PENDING │─────▶│ CONFIRMED │─────▶│  SHIPPED  │─────▶│ DELIVERED │
  └─────────┘      └───────────┘      └───────────┘      └───────────┘
       │                │
       │                │
       ▼                ▼
  ┌───────────┐    ┌───────────┐
  │ CANCELLED │    │ CANCELLED │
  └───────────┘    └───────────┘
```

## Materialized Views

The Order Service maintains enriched materialized views:

### Order View (Order_VIEW)
- Current state of each order
- Updated on every order event

### Customer Orders View
- Secondary index by customer ID
- Enables fast customer order history queries

### Order Enrichment
- Customer name and email (from Account Service events)
- Product details (from Inventory Service events)

## Cross-Service Event Flow

```
┌─────────────────┐                           ┌──────────────────┐
│  Order Service  │                           │ Inventory Service │
├─────────────────┤                           ├──────────────────┤
│ Create Order    │──OrderCreatedEvent──────▶ │ Reserve Stock    │
│                 │                           │                  │
│                 │◀──StockReservedEvent───── │                  │
│ Confirm Order   │                           │                  │
└─────────────────┘                           └──────────────────┘

┌─────────────────┐                           ┌──────────────────┐
│  Order Service  │                           │ Inventory Service │
├─────────────────┤                           ├──────────────────┤
│ Cancel Order    │──OrderCancelledEvent────▶ │ Release Stock    │
│                 │                           │                  │
│                 │◀──StockReleasedEvent───── │                  │
└─────────────────┘                           └──────────────────┘
```

## Configuration

### application.yml

```yaml
spring:
  application:
    name: order-service

server:
  port: 8083

hazelcast:
  cluster-name: ecommerce-demo

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | HTTP server port | 8083 |
| `HAZELCAST_CLUSTER_NAME` | Hazelcast cluster name | ecommerce-demo |
| `HAZELCAST_MEMBERS` | Cluster member addresses | localhost:5701 |

## Running Locally

### Prerequisites

- Java 17+
- Maven 3.8+
- Hazelcast cluster (or embedded)

### Build and Run

```bash
# Build
mvn clean package -pl order-service -am

# Run
java -jar order-service/target/order-service-1.0.0-SNAPSHOT.jar
```

### With Docker

```bash
# From project root
docker-compose up order-service
```

## Health Check

```bash
curl http://localhost:8083/actuator/health
```

## Metrics

Available at `/actuator/prometheus`:

- `eventsourcing.events.submitted{eventType="OrderCreated"}` - Order creation
- `eventsourcing.events.submitted{eventType="OrderConfirmed"}` - Order confirmation
- `eventsourcing.events.submitted{eventType="OrderCancelled"}` - Order cancellation
- `eventsourcing.view.updates{domain="Order"}` - View updates

## Testing

```bash
# Unit tests
mvn test -pl order-service

# Integration tests
mvn verify -pl order-service -Pintegration-tests

# Load tests (100+ TPS)
mvn test -Dtest=LoadTest -pl order-service

# With coverage report
mvn test -pl order-service jacoco:report
```

## Load Testing Results

The Order Service has been tested to handle 100,000+ operations per second:

| Metric | Result |
|--------|--------|
| Target TPS | 100 |
| Achieved TPS | 100,000+ |
| P50 Latency | < 0.1ms |
| P99 Latency | < 1ms |

## Error Handling

| HTTP Code | Scenario |
|-----------|----------|
| 201 | Order created successfully |
| 200 | Operation completed successfully |
| 400 | Invalid request data |
| 404 | Order or customer not found |
| 409 | Invalid state transition (e.g., cancel a delivered order) |
| 500 | Internal server error |

## Dependencies

- framework-core
- ecommerce-common
- Spring Boot Web
- Spring Boot Actuator
- Hazelcast

## License

Apache License 2.0
