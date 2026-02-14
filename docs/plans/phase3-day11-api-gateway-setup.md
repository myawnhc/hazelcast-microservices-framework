# Phase 3 Day 11: API Gateway Module Setup

## Context

Area 3 adds a Spring Cloud Gateway as the single entry point for all services. Day 11 creates the module, configures routes, and validates with a smoke test. Days 12-14 add rate limiting (Hazelcast-backed), error handling, CORS, Docker integration, and documentation.

---

## Key Design Decisions

**Spring Cloud Gateway** (not a custom reverse proxy) — it's the standard tool for this job and educational to show alongside Hazelcast. It uses WebFlux, which is fine as a separate module (no conflict with WebMvc services).

**Spring Cloud BOM**: `2023.0.0` (Leyton release train) is compatible with Spring Boot 3.2.1.

**Port**: 8080 for the gateway. Management Center currently occupies 8080 in Docker Compose — that conflict resolves on Day 14 when we update Docker Compose (move MC to 8888).

**No framework-core dependency**: The gateway is a pure routing/filtering layer. It does not participate in event sourcing. No Hazelcast dependency on Day 11; Day 12 adds Hazelcast client for rate limiting.

---

## Files to Create

### 1. Root POM updates (`pom.xml`)

- Add `<spring-cloud.version>2023.0.0</spring-cloud.version>` to `<properties>`
- Add Spring Cloud BOM to `<dependencyManagement>`:
  ```xml
  <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-dependencies</artifactId>
      <version>${spring-cloud.version}</version>
      <type>pom</type>
      <scope>import</scope>
  </dependency>
  ```
- Add `<module>api-gateway</module>` to `<modules>`
- Add `api-gateway` to `<dependencyManagement>` internal modules block

### 2. Gateway POM (`api-gateway/pom.xml`)

Dependencies:
- `spring-cloud-starter-gateway` (brings in WebFlux + Netty)
- `spring-boot-starter-actuator`
- `micrometer-registry-prometheus`
- `logstash-logback-encoder`
- `spring-boot-starter-test` (test scope)

NOT included (WebMvc conflicts with Gateway's WebFlux):
- `spring-boot-starter-web`
- `springdoc-openapi-starter-webmvc-ui`

Build plugins: `spring-boot-maven-plugin`, `maven-surefire-plugin` (with `--add-opens`), `jacoco-maven-plugin`

### 3. Gateway Application (`api-gateway/src/main/java/com/theyawns/ecommerce/gateway/GatewayApplication.java`)

Standard `@SpringBootApplication` main class.

### 4. Route Configuration (`api-gateway/src/main/resources/application.yml`)

Routes derived from existing `@RequestMapping` paths:

| Route ID | Path Predicate | Target | Service |
|----------|---------------|--------|---------|
| `account-service` | `/api/customers/**` | `http://localhost:8081` | Account |
| `inventory-service` | `/api/products/**` | `http://localhost:8082` | Inventory |
| `inventory-saga` | `/api/saga/inventory/**` | `http://localhost:8082` | Inventory |
| `order-service` | `/api/orders/**` | `http://localhost:8083` | Order |
| `order-sagas` | `/api/sagas/**` | `http://localhost:8083` | Order |
| `order-metrics` | `/api/metrics/**` | `http://localhost:8083` | Order |
| `payment-service` | `/api/payments/**` | `http://localhost:8084` | Payment |
| `payment-saga` | `/api/saga/payment/**` | `http://localhost:8084` | Payment |

Note: `/api/admin/dlq/**` is exposed by framework-core on every service — not routed through gateway (admin access is direct).

Config also includes:
- `server.port: 8080`
- Actuator exposure (health, prometheus, metrics, info)
- Logging (com.theyawns: DEBUG)

### 5. Docker Profile Config (`api-gateway/src/main/resources/application-docker.yml`)

Overrides service URIs to Docker container hostnames:
- `account-service:8081`, `inventory-service:8082`, `order-service:8083`, `payment-service:8084`

### 6. Dockerfile (`api-gateway/Dockerfile`)

Follows existing pattern: `eclipse-temurin:17-jre-jammy`, non-root user, port 8080, actuator health check.

### 7. Smoke Test (`api-gateway/src/test/java/.../GatewayApplicationTest.java`)

Uses `@SpringBootTest` to verify the application context loads and routes are configured. Validates route count matches expected number.

### 8. Route Configuration Test (`api-gateway/src/test/java/.../GatewayRouteConfigTest.java`)

Uses `@SpringBootTest` with `RouteDefinitionLocator` to verify:
- Each route ID exists
- Path predicates are correct
- Target URIs resolve

---

## Files Modified

| File | Change |
|------|--------|
| `pom.xml` | Add Spring Cloud BOM, add `api-gateway` module + dependency management |

## Files Created

| File | Purpose |
|------|---------|
| `api-gateway/pom.xml` | Module POM |
| `api-gateway/src/main/java/.../gateway/GatewayApplication.java` | Main class |
| `api-gateway/src/main/resources/application.yml` | Routes + config |
| `api-gateway/src/main/resources/application-docker.yml` | Docker service URLs |
| `api-gateway/Dockerfile` | Container image |
| `api-gateway/src/test/java/.../gateway/GatewayApplicationTest.java` | Context load test |
| `api-gateway/src/test/java/.../gateway/GatewayRouteConfigTest.java` | Route validation test |

---

## Verification

1. `mvn clean verify -pl api-gateway` — module builds and tests pass
2. `mvn clean verify` — full reactor build passes (all 8 modules)
3. Start gateway locally: `mvn spring-boot:run -pl api-gateway` → check `http://localhost:8080/actuator/health`
4. Commit on `main`
