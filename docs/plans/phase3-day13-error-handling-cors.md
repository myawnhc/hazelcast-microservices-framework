# Phase 3 Day 13: Gateway Error Handling & CORS

## Context

The `api-gateway` module (Spring Cloud Gateway, WebFlux/Netty) has routing, rate limiting, correlation ID, logging, and timing filters from Days 11-12. Day 13 adds error handling, circuit breakers, CORS, and health aggregation to make the gateway production-ready.

## Deliverables

1. Global error handler (502, 503, 504 → consistent JSON)
2. Per-route circuit breakers (Resilience4j via Spring Cloud CircuitBreaker)
3. CORS configuration for browser-based clients
4. Aggregated downstream health endpoint
5. Unit tests

---

## Implementation Plan

### Step 1: Add circuit breaker dependency to `pom.xml`

**File**: `api-gateway/pom.xml`

Add `spring-cloud-starter-circuitbreaker-reactor-resilience4j` (version managed by Spring Cloud BOM 2023.0.0 already in root POM). This brings Resilience4j circuit breaker + time limiter + Spring Cloud integration.

### Step 2: Create `GatewayErrorResponse` DTO

**File**: `api-gateway/src/main/java/com/theyawns/ecommerce/gateway/error/GatewayErrorResponse.java`

Immutable DTO with fields: `status` (int), `errorCode`, `message`, `correlationId`, `path`, `timestamp` (Instant). Extends the service-level `ErrorResponse` pattern but adds `correlationId` and `path` for gateway-specific tracing.

### Step 3: Create `GatewayErrorHandler`

**File**: `api-gateway/src/main/java/com/theyawns/ecommerce/gateway/error/GatewayErrorHandler.java`

- Implements `ErrorWebExceptionHandler` at `@Order(-2)` (before Spring Boot's default at -1)
- Exception mapping with **cause chain walking**:
  - `ConnectException` → 503 SERVICE_UNAVAILABLE
  - `TimeoutException` → 504 GATEWAY_TIMEOUT
  - `CallNotPermittedException` (by class name) → 503 CIRCUIT_BREAKER_OPEN
  - `ResponseStatusException` → preserved status
  - Fallback → 502 BAD_GATEWAY
- Writes JSON via `ObjectMapper` + `DataBufferFactory`
- Reads correlation ID from `exchange.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTR)`
- Package-private `resolveStatus()`, `resolveErrorCode()`, `resolveMessage()` for testability

### Step 4: Create `CircuitBreakerFallbackHandler`

**File**: `api-gateway/src/main/java/com/theyawns/ecommerce/gateway/error/CircuitBreakerFallbackHandler.java`

- `@RestController` at `/fallback/{serviceName}`
- Invoked by Spring Cloud Gateway's CircuitBreaker filter `fallbackUri: forward:/fallback/{service}`
- Returns `GatewayErrorResponse` with 503, CIRCUIT_BREAKER_OPEN, service name in message
- Handles all HTTP methods (GET + catch-all @RequestMapping)

### Step 5: Create `DownstreamServicesHealthIndicator`

**File**: `api-gateway/src/main/java/com/theyawns/ecommerce/gateway/health/DownstreamServicesHealthIndicator.java`

- Implements `ReactiveHealthIndicator`, registered as `@Component("downstreamServices")`
- Uses `WebClient` to check each service's `/actuator/health` with configurable timeout (default 3s)
- Service URLs via `@Value` with defaults: `${ACCOUNT_SERVICE_URL:http://localhost:8081}`, etc.
- Overall health UP only when all services reachable; individual statuses as detail entries
- Package-private constructor taking `WebClient` for test injection

### Step 6: Update `application.yml`

**File**: `api-gateway/src/main/resources/application.yml`

Add three sections:

**CORS** (`spring.cloud.gateway.globalcors`):
- `[/**]` pattern — applies to all routes
- Allowed origins: `http://localhost:3000`, `http://localhost:5173`, `http://localhost:8080`
- Allowed methods: GET, POST, PUT, DELETE, PATCH, OPTIONS
- Exposed headers: X-Correlation-ID, X-Response-Time, X-RateLimit-Limit, X-RateLimit-Remaining
- `allow-credentials: true`, `max-age: 3600`

**Per-route CircuitBreaker filters**: Add `CircuitBreaker` filter to each of the 8 routes with named instances (4 circuit breakers: account-service, inventory-service, order-service, payment-service) and `fallbackUri: forward:/fallback/{service}`

**Resilience4j config** (`resilience4j.circuitbreaker/timelimiter`):
- Default config: sliding window 10, min calls 5, 50% failure rate, 10s wait, 3 half-open calls
- Time limiter: 5s timeout
- Per-service instances inheriting default config

**Route URIs**: Change to use environment variables with defaults: `${ACCOUNT_SERVICE_URL:http://localhost:8081}` etc.

**Health timeout**: `gateway.health.timeout: 3s`

### Step 7: Update `application-docker.yml`

**File**: `api-gateway/src/main/resources/application-docker.yml`

Mirror Step 6 additions but with Docker service hostnames (`account-service:8081`, etc.). Add CORS, circuit breaker filters, and Resilience4j config.

### Step 8: Unit Tests

| Test File | Tests |
|-----------|-------|
| `error/GatewayErrorResponseTest.java` | 4 tests: field construction, auto-timestamp, null correlationId, null path |
| `error/GatewayErrorHandlerTest.java` | ~16 tests: status resolution (ConnectException→503, TimeoutException→504, CallNotPermittedException→503, ResponseStatusException→preserved, unknown→502, cause chain walking), error codes, messages, JSON writing, correlation ID inclusion, Content-Type header |
| `error/CircuitBreakerFallbackHandlerTest.java` | 4 tests: 503 status, service name in message, correlation ID, error code |
| `health/DownstreamServicesHealthIndicatorTest.java` | 5 tests: all UP, one DOWN, non-UP status, timeout→DOWN, detail keys present |
| `GatewayRouteConfigTest.java` (modify) | Add test verifying CircuitBreaker filter on routes |

Health indicator tests use `WebClient.builder().exchangeFunction(...)` to simulate responses — no actual HTTP server needed, no Mockito mocking of concrete classes.

---

## Files Summary

| Action | File |
|--------|------|
| Modify | `api-gateway/pom.xml` |
| Create | `api-gateway/src/main/java/.../gateway/error/GatewayErrorResponse.java` |
| Create | `api-gateway/src/main/java/.../gateway/error/GatewayErrorHandler.java` |
| Create | `api-gateway/src/main/java/.../gateway/error/CircuitBreakerFallbackHandler.java` |
| Create | `api-gateway/src/main/java/.../gateway/health/DownstreamServicesHealthIndicator.java` |
| Modify | `api-gateway/src/main/resources/application.yml` |
| Modify | `api-gateway/src/main/resources/application-docker.yml` |
| Create | `api-gateway/src/test/.../gateway/error/GatewayErrorResponseTest.java` |
| Create | `api-gateway/src/test/.../gateway/error/GatewayErrorHandlerTest.java` |
| Create | `api-gateway/src/test/.../gateway/error/CircuitBreakerFallbackHandlerTest.java` |
| Create | `api-gateway/src/test/.../gateway/health/DownstreamServicesHealthIndicatorTest.java` |
| Modify | `api-gateway/src/test/.../gateway/GatewayRouteConfigTest.java` |

## Verification

1. `mvn clean verify -pl api-gateway` — all existing + new tests pass
2. `mvn clean verify` — full project build succeeds
3. Check `/actuator/health` shows `downstreamServices` component with per-service status
4. CORS preflight: `curl -X OPTIONS -H "Origin: http://localhost:3000" http://localhost:8080/api/customers` returns CORS headers

## Commit Message

```
feat(gateway): add error handling, circuit breakers, CORS, and health aggregation (Phase 3 Day 13)
```
