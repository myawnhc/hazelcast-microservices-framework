# Phase 3, Day 22: Security Testing & Documentation

## Context

Days 19-21 implemented three security layers: JWT authentication (framework + gateway), service-to-service HMAC signing (ITopic events), and MCP API key RBAC. Unit test coverage is solid (64+ tests), but there are **no integration tests** validating end-to-end security behavior, **no security guide**, and **no Docker Compose security env vars**. Day 22 closes these gaps to complete Area 5 (Security).

Additionally, the plan files for days 19-21 are numbered incorrectly (off by one) and need renaming.

---

## Step 0: Fix Plan File Numbering

Rename existing plan files to match the Phase 3 roadmap:

| Current Name | Correct Name | Roadmap Day |
|---|---|---|
| `phase3-day20-spring-security-oauth2.md` | `phase3-day19-spring-security-oauth2.md` | Day 19 |
| `phase3-day21-service-to-service-auth.md` | `phase3-day20-service-to-service-auth.md` | Day 20 |
| `phase3-day22-mcp-security-rbac.md` | `phase3-day21-mcp-security-rbac.md` | Day 21 |

---

## Files to Create (5)

### Security Integration Tests

| # | File | Purpose |
|---|------|---------|
| 1 | `framework-core/.../security/SecurityFilterChainIntegrationTest.java` | Test JWT-secured SecurityFilterChain: valid JWT -> 200, no JWT -> 401, public paths -> 200 |
| 2 | `framework-core/.../security/identity/EventAuthenticatorIntegrationTest.java` | End-to-end: wrap event -> verify signature -> unwrap, cross-service simulation, tampered signature warning |
| 3 | `mcp-server/.../security/McpSecurityIntegrationTest.java` | Test HTTP filter + authorizer together: no key -> 401, viewer key -> read OK / write denied, operator key -> all OK |

### Documentation

| # | File | Purpose |
|---|------|---------|
| 4 | `docs/guides/security-guide.md` | Comprehensive security setup guide covering all 3 layers |
| 5 | `docs/plans/phase3-day22-security-testing-docs.md` | This plan |

## Files to Modify (2)

| # | File | Change |
|---|------|--------|
| 1 | `docker/docker-compose.yml` | Add security env vars (commented out by default for easy demo setup) |
| 2 | `README.md` | Add Security section under Phase 3 Features |

---

## Detailed Implementation

### 1. `SecurityFilterChainIntegrationTest.java`

Uses `WebApplicationContextRunner` to test that:
- Multiple custom public paths are correctly bound
- Default actuator path is used when not overridden
- SecurityProperties not created when security disabled
- Custom SecurityFilterChain overrides framework default (both enabled and disabled)
- JWT and permit-all configurations are mutually exclusive
- JwtDecoder bean is required when security is enabled

### 2. `EventAuthenticatorIntegrationTest.java`

Tests the full wrap -> transmit -> unwrap cycle using real Hazelcast GenericRecord objects:
- Cross-service: wrap with service A -> unwrap/verify with service B (same secret) -> success
- Field preservation through full cycle
- Multiple events in sequence
- Tampered signature detection (different secret, modified payload)
- Backward compatibility (raw event passthrough)
- Null payload handling (returns raw record with warning)
- Envelope metadata verification (source, type, timestamp, signature)

### 3. `McpSecurityIntegrationTest.java`

Tests the security layer interaction:
- VIEWER role: access read-only tools, denied write tools
- OPERATOR role: access all 7 tools
- ADMIN role: access all 7 tools
- No role: denied all tools
- Request role overrides session role
- Properties role resolution (VIEWER, OPERATOR, ADMIN, case-insensitive, null/blank)
- Auto-config bean creation (enabled, disabled, absent)
- API key binding from configuration
- Filter creation in servlet vs non-servlet context
- Access denied JSON response format

### 4. `docs/guides/security-guide.md`

Comprehensive guide covering:
- Overview of all 3 layers with architecture diagram
- Layer 1: JWT Authentication (configuration, properties, custom chain, testing)
- Layer 2: Service-to-Service Auth (HMAC-SHA256, configuration, warn-only mode)
- Layer 3: MCP API Key Auth (roles/permissions matrix, stdio vs HTTP mode)
- Docker Compose setup with environment variables reference
- Troubleshooting for each layer

### 5. Docker Compose Updates

Added commented-out security env vars to:
- API Gateway: JWT only
- 4 microservices: JWT + service identity
- MCP Server: MCP security with demo keys

### 6. README Security Section

Added security bullet under Phase 3 Features and security guide link to Documentation section.

---

## Test Strategy

All new tests are pure unit/component tests (no external dependencies, no Testcontainers):
- `EventAuthenticatorIntegrationTest` — uses real `GenericRecordBuilder`, no Hazelcast instance needed
- `SecurityFilterChainIntegrationTest` — uses `WebApplicationContextRunner`
- `McpSecurityIntegrationTest` — uses `ApplicationContextRunner`

---

## Verification

1. `mvn clean test` from project root — all existing + new tests pass
2. Security guide renders correctly in markdown
3. Docker Compose still starts without errors (commented env vars don't affect anything)
4. README displays correctly with new security section

---

## What Comes Next

After Day 22 (Security Testing & Documentation) completes Area 5, we proceed to **Day 23: Persistence Interface & PostgreSQL Implementation** (first day of Area 6).
