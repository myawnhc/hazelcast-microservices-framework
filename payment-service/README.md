# Payment Service

Payment processing microservice built on the event sourcing framework.

## Overview

The Payment Service handles payment processing and refunds as part of the order fulfillment flow. It participates in the choreographed Order Fulfillment saga (Step 2), processing payments after stock is reserved and triggering compensation if payment fails.

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/payments` | Process a new payment |
| GET | `/api/payments/{paymentId}` | Get payment by ID |
| GET | `/api/payments/order/{orderId}` | Get payment for an order |
| POST | `/api/payments/{paymentId}/refund` | Refund a payment |

## Domain Model

### Payment

| Field | Type | Description |
|-------|------|-------------|
| paymentId | String | Unique identifier (UUID) |
| orderId | String | Reference to order |
| customerId | String | Reference to customer |
| amount | BigDecimal | Transaction amount (min 0.01) |
| currency | String | ISO currency code (3 chars, e.g., "USD") |
| method | PaymentMethod | Payment method (see below) |
| status | PaymentStatus | Payment status (see below) |
| transactionId | String | External processor transaction ID |
| processedAt | Instant | When payment was processed |
| failureReason | String | Error message if payment failed |
| createdAt | Instant | Creation timestamp |
| updatedAt | Instant | Last update timestamp |

### Payment Methods

| Method | Description |
|--------|-------------|
| CREDIT_CARD | Credit card payment (default) |
| DEBIT_CARD | Debit card payment |
| BANK_TRANSFER | Bank transfer |
| DIGITAL_WALLET | Digital wallet (e.g., Apple Pay) |

### Payment Status

| Status | Description |
|--------|-------------|
| PENDING | Payment created, awaiting processing |
| AUTHORIZED | Payment authorized by processor |
| CAPTURED | Payment captured successfully |
| FAILED | Payment declined or processing error |
| REFUNDED | Payment refunded |

### Events

- **PaymentProcessedEvent**: Payment captured successfully (saga step 2)
- **PaymentFailedEvent**: Payment declined — triggers saga compensation
- **PaymentRefundedEvent**: Payment refunded (saga compensation event)

## API Examples

### Process a Payment

```bash
curl -X POST http://localhost:8084/api/payments \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "order-550e8400-e29b-41d4-a716-446655440001",
    "customerId": "cust-550e8400-e29b-41d4-a716-446655440000",
    "amount": 999.99,
    "currency": "USD",
    "method": "CREDIT_CARD"
  }'
```

Response:
```json
{
  "paymentId": "pay-550e8400-e29b-41d4-a716-446655440002",
  "orderId": "order-550e8400-e29b-41d4-a716-446655440001",
  "customerId": "cust-550e8400-e29b-41d4-a716-446655440000",
  "amount": 999.99,
  "currency": "USD",
  "method": "CREDIT_CARD",
  "status": "CAPTURED",
  "transactionId": "txn-abc123"
}
```

### Get Payment

```bash
curl http://localhost:8084/api/payments/pay-550e8400-e29b-41d4-a716-446655440002
```

### Get Payment by Order

```bash
curl http://localhost:8084/api/payments/order/order-550e8400-e29b-41d4-a716-446655440001
```

### Refund a Payment

```bash
curl -X POST http://localhost:8084/api/payments/pay-550e8400-e29b-41d4-a716-446655440002/refund \
  -H "Content-Type: application/json" \
  -d '{"reason": "Customer requested cancellation"}'
```

Response:
```json
{
  "paymentId": "pay-550e8400-e29b-41d4-a716-446655440002",
  "status": "REFUNDED"
}
```

## Saga Integration

The Payment Service participates in the **Order Fulfillment** saga as Step 2:

```
OrderCreated (Step 0) → StockReserved (Step 1) → PaymentProcessed (Step 2) → OrderConfirmed (Step 3)
```

### Forward Flow

The `PaymentSagaListener` subscribes to `StockReserved` events via Hazelcast ITopic. When stock is reserved, it processes payment using the amount, currency, and method from the event context.

### Compensation Flow

If payment fails, a `PaymentFailed` event triggers compensation:
- Inventory Service releases reserved stock
- Order Service cancels the order

If a refund is needed (post-confirmation), a `PaymentRefundRequested` event triggers the `PaymentRefundedEvent`.

### Payment Simulation

The service simulates a payment processor:
- Payments with amount > $10,000 are declined (for demo purposes)
- All other payments are captured successfully
- Refunds are always processed

## Configuration

### application.yml

```yaml
spring:
  application:
    name: payment-service

server:
  port: 8084

saga:
  timeout:
    enabled: true
    check-interval: 5000
    default-deadline: 30000
    auto-compensate: true

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | HTTP server port | 8084 |
| `HAZELCAST_CLUSTER_NAME` | Hazelcast cluster name | ecommerce-cluster |
| `HAZELCAST_CLUSTER_MEMBERS` | Cluster member addresses | localhost:5701 |

## Running Locally

### Prerequisites

- Java 17+
- Maven 3.8+
- Hazelcast cluster (or embedded)

### Build and Run

```bash
# Build
mvn clean package -pl payment-service -am

# Run
java -jar payment-service/target/payment-service-1.0.0-SNAPSHOT.jar
```

### With Docker

```bash
# From project root
docker-compose up payment-service
```

## Health Check

```bash
curl http://localhost:8084/actuator/health
```

## Metrics

Available at `/actuator/prometheus`:

- `eventsourcing.events.submitted{eventType="PaymentProcessed"}` - Successful payments
- `eventsourcing.events.submitted{eventType="PaymentFailed"}` - Failed payments
- `eventsourcing.events.submitted{eventType="PaymentRefunded"}` - Refunds
- `eventsourcing.view.updates{domain="Payment"}` - View updates

## Error Handling

| HTTP Code | Scenario |
|-----------|----------|
| 201 | Payment processed successfully |
| 200 | Operation completed successfully |
| 400 | Invalid request data (missing fields, invalid amount) |
| 404 | Payment not found |
| 409 | Invalid state transition (e.g., refund a failed payment) |
| 500 | Internal server error |

## Dependencies

- framework-core
- ecommerce-common
- Spring Boot Web
- Spring Boot Actuator
- Hazelcast

## License

Apache License 2.0
