# Account Service

Customer account management microservice built on the event sourcing framework.

## Overview

The Account Service manages customer lifecycle including registration, profile updates, and account status changes. All state changes are captured as domain events, enabling full audit trails and event-driven architecture.

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/customers` | Create a new customer |
| GET | `/api/customers/{id}` | Get customer by ID |
| PUT | `/api/customers/{id}` | Update customer information |
| PATCH | `/api/customers/{id}/status` | Change account status |

## Domain Model

### Customer

| Field | Type | Description |
|-------|------|-------------|
| customerId | String | Unique identifier (UUID) |
| email | String | Customer email address |
| name | String | Full name |
| address | String | Shipping/billing address |
| phone | String | Phone number (optional) |
| status | String | ACTIVE, SUSPENDED, or CLOSED |
| createdAt | Instant | Account creation timestamp |
| updatedAt | Instant | Last update timestamp |

### Events

- **CustomerCreatedEvent**: New customer registration
- **CustomerUpdatedEvent**: Profile information updated
- **CustomerStatusChangedEvent**: Account status changed

## API Examples

### Create Customer

```bash
curl -X POST http://localhost:8081/api/customers \
  -H "Content-Type: application/json" \
  -d '{
    "email": "john.doe@example.com",
    "name": "John Doe",
    "address": "123 Main St, City, ST 12345",
    "phone": "555-123-4567"
  }'
```

Response:
```json
{
  "customerId": "550e8400-e29b-41d4-a716-446655440000",
  "email": "john.doe@example.com",
  "name": "John Doe",
  "address": "123 Main St, City, ST 12345",
  "phone": "555-123-4567",
  "status": "ACTIVE",
  "createdAt": "2024-01-15T10:30:00Z"
}
```

### Get Customer

```bash
curl http://localhost:8081/api/customers/550e8400-e29b-41d4-a716-446655440000
```

### Update Customer

```bash
curl -X PUT http://localhost:8081/api/customers/550e8400-e29b-41d4-a716-446655440000 \
  -H "Content-Type: application/json" \
  -d '{
    "email": "john.doe@example.com",
    "name": "John Doe",
    "address": "456 Oak Ave, New City, ST 67890",
    "phone": "555-987-6543"
  }'
```

### Change Status

```bash
curl -X PATCH http://localhost:8081/api/customers/550e8400-e29b-41d4-a716-446655440000/status \
  -H "Content-Type: application/json" \
  -d '{
    "status": "SUSPENDED",
    "reason": "Payment overdue"
  }'
```

## Configuration

### application.yml

```yaml
spring:
  application:
    name: account-service

server:
  port: 8081

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
| `SERVER_PORT` | HTTP server port | 8081 |
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
mvn clean package -pl account-service -am

# Run
java -jar account-service/target/account-service-1.0.0-SNAPSHOT.jar
```

### With Docker

```bash
# From project root
docker-compose up account-service
```

## Health Check

```bash
curl http://localhost:8081/actuator/health
```

Response:
```json
{
  "status": "UP",
  "components": {
    "hazelcast": {
      "status": "UP",
      "details": {
        "clusterSize": 3,
        "clusterName": "ecommerce-demo"
      }
    }
  }
}
```

## Metrics

Available at `/actuator/prometheus`:

- `eventsourcing.events.submitted{eventType="CustomerCreated"}` - Customer creation events
- `eventsourcing.events.submitted{eventType="CustomerUpdated"}` - Customer update events
- `eventsourcing.view.updates{domain="Customer"}` - View update operations

## Event Flow

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│ REST API    │────▶│ CustomerService ──▶│ Controller  │
└─────────────┘     └──────────────┘     └─────────────┘
                                                │
                    ┌───────────────────────────┼──────────────────────────┐
                    │                           ▼                          │
                    │  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐ │
                    │  │ Event Store │   │ View Store  │   │ Event Bus   │ │
                    │  │ (append)    │   │ (update)    │   │ (publish)   │ │
                    │  └─────────────┘   └─────────────┘   └─────────────┘ │
                    │                         Hazelcast                     │
                    └──────────────────────────────────────────────────────┘
```

## Testing

```bash
# Unit tests
mvn test -pl account-service

# Integration tests
mvn verify -pl account-service -Pintegration-tests

# With coverage report
mvn test -pl account-service jacoco:report
```

## Dependencies

- framework-core
- ecommerce-common
- Spring Boot Web
- Spring Boot Actuator
- Hazelcast

## License

Apache License 2.0
