# Inventory Service

Product inventory management microservice built on the event sourcing framework.

## Overview

The Inventory Service manages product catalog and stock levels. It handles stock reservations for orders and releases for cancellations, ensuring accurate inventory tracking through event sourcing.

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/products` | Create a new product |
| GET | `/api/products/{id}` | Get product by ID |
| POST | `/api/products/{id}/stock/reserve` | Reserve stock for an order |
| POST | `/api/products/{id}/stock/release` | Release reserved stock |

## Domain Model

### Product

| Field | Type | Description |
|-------|------|-------------|
| productId | String | Unique identifier (UUID) |
| sku | String | Stock Keeping Unit |
| name | String | Product name |
| description | String | Product description |
| price | BigDecimal | Unit price |
| quantityOnHand | int | Total quantity in stock |
| quantityReserved | int | Quantity reserved for orders |
| category | String | Product category |
| status | String | ACTIVE or DISCONTINUED |

### Computed Fields

- **availableQuantity**: `quantityOnHand - quantityReserved`

### Events

- **ProductCreatedEvent**: New product added to catalog
- **StockReservedEvent**: Stock reserved for an order
- **StockReleasedEvent**: Reserved stock released (order cancelled/fulfilled)

## API Examples

### Create Product

```bash
curl -X POST http://localhost:8082/api/products \
  -H "Content-Type: application/json" \
  -d '{
    "sku": "LAPTOP-GAMING-001",
    "name": "Pro Gaming Laptop",
    "description": "High-performance gaming laptop with RTX 4080",
    "price": 1999.99,
    "quantityOnHand": 50,
    "category": "Electronics"
  }'
```

Response:
```json
{
  "productId": "prod-550e8400-e29b-41d4-a716-446655440000",
  "sku": "LAPTOP-GAMING-001",
  "name": "Pro Gaming Laptop",
  "description": "High-performance gaming laptop with RTX 4080",
  "price": 1999.99,
  "quantityOnHand": 50,
  "quantityReserved": 0,
  "category": "Electronics",
  "status": "ACTIVE"
}
```

### Get Product

```bash
curl http://localhost:8082/api/products/prod-550e8400-e29b-41d4-a716-446655440000
```

### Reserve Stock

```bash
curl -X POST http://localhost:8082/api/products/prod-550e8400-e29b-41d4-a716-446655440000/stock/reserve \
  -H "Content-Type: application/json" \
  -d '{
    "quantity": 2,
    "orderId": "order-123"
  }'
```

Response:
```json
{
  "productId": "prod-550e8400-e29b-41d4-a716-446655440000",
  "sku": "LAPTOP-GAMING-001",
  "name": "Pro Gaming Laptop",
  "price": 1999.99,
  "quantityOnHand": 50,
  "quantityReserved": 2,
  "status": "ACTIVE"
}
```

### Release Stock

```bash
curl -X POST http://localhost:8082/api/products/prod-550e8400-e29b-41d4-a716-446655440000/stock/release \
  -H "Content-Type: application/json" \
  -d '{
    "quantity": 2,
    "orderId": "order-123",
    "reason": "Order cancelled by customer"
  }'
```

## Stock Management

### Reservation Flow

```
Order Created → Reserve Stock → Stock Reserved
                     │
                     ├─── Success: quantityReserved += quantity
                     │
                     └─── Failure: Insufficient stock (400 Bad Request)
```

### Release Flow

```
Order Cancelled → Release Stock → Stock Released
                       │
                       └─── quantityReserved -= quantity
                            (cannot go below 0)
```

### Available Quantity Calculation

```
availableQuantity = quantityOnHand - quantityReserved
```

The `canReserve(quantity)` method checks if `availableQuantity >= quantity`.

## Configuration

### application.yml

```yaml
spring:
  application:
    name: inventory-service

server:
  port: 8082

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
| `SERVER_PORT` | HTTP server port | 8082 |
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
mvn clean package -pl inventory-service -am

# Run
java -jar inventory-service/target/inventory-service-1.0.0-SNAPSHOT.jar
```

### With Docker

```bash
# From project root
docker-compose up inventory-service
```

## Health Check

```bash
curl http://localhost:8082/actuator/health
```

## Metrics

Available at `/actuator/prometheus`:

- `eventsourcing.events.submitted{eventType="ProductCreated"}` - Product creation
- `eventsourcing.events.submitted{eventType="StockReserved"}` - Stock reservations
- `eventsourcing.events.submitted{eventType="StockReleased"}` - Stock releases
- `eventsourcing.view.updates{domain="Product"}` - View updates

## Event Flow

```
┌─────────────┐     ┌───────────────┐     ┌─────────────┐
│ REST API    │────▶│ ProductService │───▶│ Controller  │
└─────────────┘     └───────────────┘     └─────────────┘
                                                 │
                    ┌────────────────────────────┼────────────────────────┐
                    │                            ▼                        │
                    │  ┌─────────────┐   ┌─────────────┐   ┌───────────┐ │
                    │  │ Event Store │   │ View Store  │   │ Event Bus │ │
                    │  └─────────────┘   └─────────────┘   └───────────┘ │
                    │                                            │        │
                    │                      Hazelcast             │        │
                    └────────────────────────────────────────────┼────────┘
                                                                 │
                                                                 ▼
                                                    (Other services can
                                                     subscribe to stock events)
```

## Cross-Service Integration

The Inventory Service publishes events that other services can consume:

- **Order Service**: Listens for `StockReservedEvent` to confirm order can be fulfilled
- **Notification Service**: (future) Could listen for low-stock alerts

## Testing

```bash
# Unit tests
mvn test -pl inventory-service

# Integration tests
mvn verify -pl inventory-service -Pintegration-tests

# With coverage report
mvn test -pl inventory-service jacoco:report
```

## Error Handling

| HTTP Code | Scenario |
|-----------|----------|
| 201 | Product created successfully |
| 200 | Operation completed successfully |
| 400 | Invalid request (insufficient stock, invalid quantity) |
| 404 | Product not found |
| 500 | Internal server error |

### Insufficient Stock Example

```bash
curl -X POST http://localhost:8082/api/products/{id}/stock/reserve \
  -H "Content-Type: application/json" \
  -d '{"quantity": 1000, "orderId": "order-123"}'
```

Response (400):
```json
{
  "error": "Insufficient stock",
  "message": "Cannot reserve 1000 units. Available: 48",
  "productId": "prod-123"
}
```

## Dependencies

- framework-core
- ecommerce-common
- Spring Boot Web
- Spring Boot Actuator
- Hazelcast

## License

Apache License 2.0
