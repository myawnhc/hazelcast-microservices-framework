# Phase 3, Day 20: Spring Security & OAuth2 Resource Server

## Context

First day of **Area 5: Security** in the Phase 3 roadmap. Adds JWT-based security at the API Gateway edge, with a framework-core security module that services can opt into for defense-in-depth. Security is **disabled by default** (`framework.security.enabled=false`) so all existing demos, tests, and local development continue to work unchanged.

## Architecture

```
               Internet
                  |
         +--------v--------+
         |   API Gateway   |  SecurityWebFilterChain (reactive)
         |  JWT Validation  |  Validates Bearer tokens
         |  (port 8080)    |  Public: actuator, health
         +--------+--------+
                  |
     +------------+------------+
     |            |            |
 +---v---+  +----v----+  +----v----+  +----------+
 |Account|  |Inventory|  | Order   |  | Payment  |
 |:8081  |  |:8082    |  |:8083    |  |:8084     |
 +-------+  +---------+  +---------+  +----------+
 (no security on Day 20 - behind gateway, not directly accessible)
```

## Design Decisions

1. **Security disabled by default** - `framework.security.enabled=false`
2. **Gateway-only enforcement on Day 20** - Services behind gateway don't need JWT yet
3. **Use Spring Boot's native JWT config** - `spring.security.oauth2.resourceserver.jwt.issuer-uri`
4. **Two auto-configs** - Enabled (JWT) and disabled (permit-all) to prevent Spring Boot default lockdown
5. **Reactive vs Servlet split** - Gateway (WebFlux) has own config; framework-core provides servlet chain

## Files Created

| File | Purpose |
|------|---------|
| `framework-core/.../security/SecurityProperties.java` | `@ConfigurationProperties(prefix="framework.security")` |
| `framework-core/.../security/SecurityAutoConfiguration.java` | Servlet SecurityFilterChain (JWT, enabled=true) |
| `framework-core/.../security/PermitAllSecurityAutoConfiguration.java` | Servlet permit-all (enabled=false, matchIfMissing=true) |
| `api-gateway/.../config/GatewaySecurityConfig.java` | Reactive SecurityWebFilterChain (both modes) |
| `framework-core/.../security/SecurityPropertiesTest.java` | Property binding + validation tests |
| `framework-core/.../security/SecurityAutoConfigurationTest.java` | Auto-config tests with WebApplicationContextRunner |

## Files Modified

| File | Change |
|------|--------|
| `framework-core/pom.xml` | Add optional security + oauth2-resource-server deps |
| `api-gateway/pom.xml` | Add security + oauth2-resource-server deps |
| `AutoConfiguration.imports` | Register both security auto-configs |
| `api-gateway/application.yml` | Add `framework.security.enabled: false` |

## Verification

1. `mvn install -pl framework-core -DskipTests`
2. `mvn clean test` (full suite - all existing tests must still pass)
3. Security disabled by default - no behavior change
4. `PermitAllSecurityAutoConfiguration` activates when `framework.security.enabled` is absent/false
5. `SecurityAutoConfiguration` activates only when `framework.security.enabled=true`
