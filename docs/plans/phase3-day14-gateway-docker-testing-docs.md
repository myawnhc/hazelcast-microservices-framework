# Phase 3 Day 14: Gateway Docker, Testing & Documentation

## Context

The `api-gateway` module is functionally complete from Days 11-13 (routing, rate limiting, correlation ID, logging, timing, error handling, circuit breakers, CORS, health aggregation). Day 14 integrates it into the Docker stack, adds integration tests, creates documentation, and connects the MCP server for optional gateway routing.

## Deliverables

1. API Gateway in Docker Compose (port 8080)
2. Prometheus scrape target for gateway metrics
3. Integration tests with WireMock (route forwarding, correlation ID, rate limiting, CORS, error handling)
4. `api-gateway/README.md`
5. Main README updated with gateway in architecture diagram, modules, project structure
6. MCP server optional gateway routing (`mcp.services.gateway-enabled`)
7. Area 3 (API Gateway) complete

---

## Implementation Plan

### Step 1: Add api-gateway to Docker Compose

**File**: `docker/docker-compose.yml`

- Add `api-gateway` service block after `mcp-server`, before monitoring section
- Port: 8080 (host) → 8080 (container)
- Environment variables: `ACCOUNT_SERVICE_URL`, `INVENTORY_SERVICE_URL`, `ORDER_SERVICE_URL`, `PAYMENT_SERVICE_URL` pointing to Docker container hostnames
- `depends_on` all 4 microservices (condition: service_healthy)
- Memory limit: 256M
- Move Management Center from port 8080 to 8888 to resolve conflict

### Step 2: Add Prometheus scrape target

**File**: `docker/prometheus/prometheus.yml`

- Add `api-gateway` job scraping `/actuator/prometheus` on `api-gateway:8080`
- Relabel instance to `api-gateway`

### Step 3: Add WireMock dependency to gateway pom.xml

**File**: `api-gateway/pom.xml`

- Add `org.wiremock:wiremock-standalone:3.3.1` (test scope)

### Step 4: Write GatewayIntegrationTest

**File**: `api-gateway/src/test/java/com/theyawns/ecommerce/gateway/GatewayIntegrationTest.java`

- `@SpringBootTest(webEnvironment = RANDOM_PORT)` with `WebTestClient`
- WireMock server started in static initializer (must be running before `@DynamicPropertySource`)
- `@DynamicPropertySource` points all service URLs to WireMock
- Rate limiting disabled for this test class (tested separately)
- Nested test classes:
  - **RouteForwarding** (8 tests): GET/POST forwarded to correct backend for all 8 routes
  - **CorrelationId** (3 tests): generation, propagation, downstream forwarding
  - **ErrorHandling** (3 tests): backend 500, backend 404, unrouted paths
  - **CircuitBreakerFallback** (2 tests): /fallback endpoint returns 503 with service name
  - **Cors** (3 tests): allowed origin preflight, exposed headers, disallowed origin rejection
  - **Timing** (1 test): X-Response-Time header present
  - **ActuatorHealth** (2 tests): health endpoint reachable, metrics endpoint reachable

### Step 5: Write RateLimitIntegrationTest

**File**: `api-gateway/src/test/java/com/theyawns/ecommerce/gateway/RateLimitIntegrationTest.java`

- Separate Spring context with rate limiting enabled (low limit: 2 req/s for testability)
- 3 tests: 429 on limit exceeded, rate limit headers present, Retry-After header on 429

### Step 6: Fix GatewayHazelcastConfig for test isolation

**File**: `api-gateway/src/main/java/com/theyawns/ecommerce/gateway/config/GatewayHazelcastConfig.java`

- Use dynamic instance name (`"gateway-hazelcast-" + System.identityHashCode(this)`) instead of fixed name
- Prevents "HazelcastInstance already exists" error when multiple Spring contexts share a JVM
- Add `destroyMethod = "shutdown"` to `@Bean` for clean teardown

### Step 7: Create api-gateway/README.md

**File**: `api-gateway/README.md`

Sections: Overview, Architecture diagram, Routes table, Configuration (rate limiting, circuit breakers, CORS), Running (standalone + Docker), Docker env vars table, API examples through gateway, Response headers table, Error responses (JSON format + status code table), Filters execution order table, Health check example, Troubleshooting (502/503/504, rate limiting, CORS)

### Step 8: Update main README

**File**: `README.md`

- Add `api-gateway` to modules table
- Update architecture diagram to show gateway as single entry point
- Add `api-gateway/` to project structure tree
- Add gateway health check to verify services section
- Add Spring Cloud Gateway to technology stack table
- Add gateway link to documentation section

### Step 9: Add MCP server gateway routing

**Files**:
- `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/config/McpServerProperties.java` — add `gatewayEnabled` (default false), `gatewayUrl` (default `http://localhost:8080`), `resolveBaseUrl(directUrl)` helper
- `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/client/ServiceClient.java` — update `resolveUrl()` and direct `orderUrl` usages to call `properties.resolveBaseUrl()`
- `mcp-server/src/main/resources/application.properties` — add commented-out gateway config
- `mcp-server/src/main/resources/application-docker.properties` — add gateway config (disabled by default)

### Step 10: Add gateway routing tests to ServiceClientTest

**File**: `mcp-server/src/test/java/com/theyawns/ecommerce/mcp/client/ServiceClientTest.java`

- New `GatewayRoutingTests` nested class with 3 tests: routes through gateway when enabled, uses direct URLs when disabled, defaults to gateway disabled

---

## Test Results

| Module | Tests | New | Status |
|--------|-------|-----|--------|
| api-gateway | 95 | 25 | All pass |
| mcp-server | 69 | 3 | All pass |
| **Total** | **164** | **28** | **All pass** |

## Files Changed

| File | Change |
|------|--------|
| `docker/docker-compose.yml` | Added api-gateway service; moved management-center to port 8888 |
| `docker/prometheus/prometheus.yml` | Added api-gateway scrape target |
| `api-gateway/pom.xml` | Added WireMock test dependency |
| `api-gateway/src/main/java/.../config/GatewayHazelcastConfig.java` | Dynamic instance name + destroyMethod |
| `api-gateway/src/test/.../GatewayIntegrationTest.java` | **New** — 22 integration tests |
| `api-gateway/src/test/.../RateLimitIntegrationTest.java` | **New** — 3 rate limit integration tests |
| `api-gateway/README.md` | **New** — comprehensive module documentation |
| `README.md` | Updated architecture diagram, modules, structure, tech stack |
| `mcp-server/src/main/java/.../config/McpServerProperties.java` | Added gateway routing properties |
| `mcp-server/src/main/java/.../client/ServiceClient.java` | Gateway-aware URL resolution |
| `mcp-server/src/main/resources/application.properties` | Added gateway config (commented) |
| `mcp-server/src/main/resources/application-docker.properties` | Added gateway config |
| `mcp-server/src/test/.../client/ServiceClientTest.java` | 3 new gateway routing tests |

---

*Phase 3 Area 3 (API Gateway) — COMPLETE*
*Created: 2026-02-14*
