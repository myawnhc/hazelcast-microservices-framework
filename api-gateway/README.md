# API Gateway

Spring Cloud Gateway providing a single entry point for all eCommerce microservices.

## Overview

The API Gateway routes all client requests to downstream services, applying cross-cutting concerns uniformly:

- **Route Forwarding** - Path-based routing to account, inventory, order, and payment services
- **Rate Limiting** - Per-client IP token bucket backed by Hazelcast IMap (separate read/write limits)
- **Correlation ID** - Generates or propagates `X-Correlation-ID` on every request
- **Circuit Breakers** - Per-route Resilience4j circuit breakers with fallback responses
- **Error Handling** - Consistent JSON error responses for all gateway errors (502, 503, 504)
- **CORS** - Pre-configured for browser-based clients
- **Health Aggregation** - Aggregated health endpoint checking all downstream services
- **Request Logging** - Structured logging of all requests and responses
- **Request Timing** - `X-Response-Time` header on every response

## Architecture

```
                    Clients (browsers, curl, MCP server)
                              │
                              ▼
                    ┌───────────────────┐
                    │   API Gateway     │
                    │     :8080         │
                    │                   │
                    │  Filters:         │
                    │  1. Correlation   │
                    │  2. Rate Limit    │
                    │  3. Logging       │
                    │  4. Timing        │
                    │  5. CircuitBreaker│
                    └─────────┬─────────┘
             ┌────────┬───────┼───────┬────────┐
             ▼        ▼       ▼       ▼        ▼
         Account  Inventory  Order  Payment  Saga
          :8081    :8082     :8083   :8084   endpoints
```

## Routes

| Route ID | Path Pattern | Downstream Service | Port |
|----------|-------------|--------------------|------|
| `account-service` | `/api/customers/**` | Account Service | 8081 |
| `inventory-service` | `/api/products/**` | Inventory Service | 8082 |
| `inventory-saga` | `/api/saga/inventory/**` | Inventory Service | 8082 |
| `order-service` | `/api/orders/**` | Order Service | 8083 |
| `order-sagas` | `/api/sagas/**` | Order Service | 8083 |
| `order-metrics` | `/api/metrics/**` | Order Service | 8083 |
| `payment-service` | `/api/payments/**` | Payment Service | 8084 |
| `payment-saga` | `/api/saga/payment/**` | Payment Service | 8084 |

All routes have CircuitBreaker filters with fallback to `/fallback/{service}`.

## Configuration

### Default (Local Development)

Routes use `localhost` URLs by default. Override with environment variables:

```bash
export ACCOUNT_SERVICE_URL=http://localhost:8081
export INVENTORY_SERVICE_URL=http://localhost:8082
export ORDER_SERVICE_URL=http://localhost:8083
export PAYMENT_SERVICE_URL=http://localhost:8084
```

### Rate Limiting

```yaml
gateway:
  rate-limit:
    enabled: true
    read-limit: 100    # GET/HEAD requests per second per client IP
    write-limit: 20    # POST/PUT/DELETE/PATCH requests per second per client IP
```

### Circuit Breakers

Each route has its own Resilience4j circuit breaker instance:

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
```

### CORS

Allowed origins (configurable in `application.yml`):
- `http://localhost:3000` (React dev server)
- `http://localhost:5173` (Vite dev server)
- `http://localhost:8080` (gateway self)

Exposed headers: `X-Correlation-ID`, `X-Response-Time`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`

## Running

### Standalone

```bash
# Build
mvn clean package -pl api-gateway -am -DskipTests

# Run
java -jar api-gateway/target/api-gateway-1.0.0-SNAPSHOT.jar
```

### Docker

The gateway is included in the Docker Compose stack:

```bash
# Build all modules
mvn clean package -DskipTests

# Start full stack
cd docker && docker-compose up -d
```

The gateway runs on port **8080**. All API requests can be made through the gateway:

```bash
# Through gateway (recommended)
curl http://localhost:8080/api/customers
curl http://localhost:8080/api/products
curl http://localhost:8080/api/orders

# Direct to services (still works)
curl http://localhost:8081/api/customers
```

### Docker Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ACCOUNT_SERVICE_URL` | `http://localhost:8081` | Account service base URL |
| `INVENTORY_SERVICE_URL` | `http://localhost:8082` | Inventory service base URL |
| `ORDER_SERVICE_URL` | `http://localhost:8083` | Order service base URL |
| `PAYMENT_SERVICE_URL` | `http://localhost:8084` | Payment service base URL |
| `SPRING_PROFILES_ACTIVE` | — | Set to `docker` for Docker deployment |

## API Examples

All examples route through the gateway on port 8080:

```bash
# Create a customer
curl -X POST http://localhost:8080/api/customers \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@example.com","name":"Alice","address":"123 Main St"}'

# List products
curl http://localhost:8080/api/products

# Place an order
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"<id>","lineItems":[{"productId":"<id>","quantity":1,"unitPrice":29.99}]}'

# Check gateway health (includes downstream service status)
curl http://localhost:8080/actuator/health

# Check Prometheus metrics
curl http://localhost:8080/actuator/prometheus
```

## Response Headers

Every response includes these headers:

| Header | Description | Example |
|--------|-------------|---------|
| `X-Correlation-ID` | Request correlation ID (generated or propagated) | `a1b2c3d4-...` |
| `X-Response-Time` | Time to process request (ms) | `23ms` |
| `X-RateLimit-Limit` | Current rate limit | `100` |

When rate limited (HTTP 429):

| Header | Description | Example |
|--------|-------------|---------|
| `Retry-After` | Seconds to wait before retrying | `1` |
| `X-RateLimit-Remaining` | Remaining requests in window | `0` |

## Error Responses

All errors return consistent JSON:

```json
{
  "status": 503,
  "errorCode": "SERVICE_UNAVAILABLE",
  "message": "Downstream service is unavailable",
  "correlationId": "a1b2c3d4-...",
  "path": "/api/customers",
  "timestamp": "2026-02-14T10:30:00Z"
}
```

| Status | Error Code | Cause |
|--------|-----------|-------|
| 429 | `TOO_MANY_REQUESTS` | Client exceeded rate limit |
| 502 | `BAD_GATEWAY` | Unexpected downstream error |
| 503 | `SERVICE_UNAVAILABLE` | Downstream service unreachable |
| 503 | `CIRCUIT_BREAKER_OPEN` | Circuit breaker tripped |
| 504 | `GATEWAY_TIMEOUT` | Downstream did not respond in time |

## Filters (Execution Order)

| Order | Filter | Description |
|-------|--------|-------------|
| -4 | `CorrelationIdFilter` | Generates/propagates correlation ID |
| -2 | `RateLimitFilter` | Enforces per-IP rate limits |
| -1 | `RequestLoggingFilter` | Logs request/response details |
| 0 | `RequestTimingFilter` | Measures and reports response time |
| — | `CircuitBreaker` | Per-route circuit breaker (Spring Cloud) |

## Health Check

The `/actuator/health` endpoint aggregates downstream service health:

```json
{
  "status": "UP",
  "components": {
    "downstreamServices": {
      "status": "UP",
      "details": {
        "account-service": "UP",
        "inventory-service": "UP",
        "order-service": "UP",
        "payment-service": "UP"
      }
    }
  }
}
```

## Troubleshooting

### Gateway returns 502 Bad Gateway
The downstream service is running but returned an unexpected error. Check:
1. Service logs: `docker logs account-service` (or whichever service)
2. Service health: `curl http://localhost:8081/actuator/health`

### Gateway returns 503 Service Unavailable
The downstream service is unreachable or the circuit breaker is open. Check:
1. Is the service running? `docker ps`
2. Check circuit breaker state in Prometheus metrics
3. Wait for `wait-duration-in-open-state` (10s default) for half-open transition

### Gateway returns 504 Gateway Timeout
The downstream service is too slow. Check:
1. Time limiter config: `resilience4j.timelimiter.configs.default.timeout-duration` (5s default)
2. Service performance: check Grafana dashboards for latency spikes

### Rate limiting is too aggressive
Adjust limits in `application.yml`:
```yaml
gateway:
  rate-limit:
    read-limit: 200    # increase from default 100
    write-limit: 50    # increase from default 20
```

### CORS errors in browser
Verify the origin is in the allowed list in `application.yml` under `spring.cloud.gateway.globalcors`. Add your frontend's origin if it's not listed.
