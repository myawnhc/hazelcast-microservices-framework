# Security Guide

This guide covers the three security layers in the Hazelcast Microservices Framework. All layers are **opt-in** and **backward compatible** — existing deployments continue to work without any configuration changes.

## Overview

| Layer | Protects | Mechanism | Scope |
|-------|----------|-----------|-------|
| **JWT Authentication** | REST API endpoints | OAuth2 Resource Server + JWT Bearer tokens | API Gateway + all microservices |
| **Service-to-Service Auth** | ITopic saga events | HMAC-SHA256 message signing | Inter-service communication |
| **MCP API Key Auth** | MCP tool invocations | API key header + role-based access control | MCP Server |

```
                   ┌──────────────┐
   Client ──JWT──► │ API Gateway  │ ──JWT──► Microservices
                   └──────────────┘              │
                                            HMAC-signed
                                            ITopic events
                                                 │
                   ┌──────────────┐              ▼
   AI Agent ─Key─► │  MCP Server  │         Hazelcast
                   └──────────────┘          Cluster
```

---

## Layer 1: JWT Authentication (API Gateway + Services)

### How It Works

The framework provides a `SecurityAutoConfiguration` that creates a Spring Security `SecurityFilterChain` configured for OAuth2 Resource Server JWT validation. When enabled:

1. All REST endpoints require a valid JWT Bearer token in the `Authorization` header
2. Configurable public paths (e.g., `/actuator/**`) are accessible without authentication
3. JWT validation (issuer, signature, expiry) is handled by Spring Security's built-in OAuth2 Resource Server

When disabled (default), a `PermitAllSecurityAutoConfiguration` creates a pass-through filter chain that allows all requests.

### Configuration

Add the following to each service's `application.yml`:

```yaml
framework:
  security:
    enabled: true
    public-paths:
      - /actuator/**
      - /swagger-ui/**
      - /v3/api-docs/**

spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://your-auth-server.example.com/realms/ecommerce
```

#### Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `framework.security.enabled` | boolean | `false` | Enable JWT authentication |
| `framework.security.public-paths` | String[] | `["/actuator/**"]` | Paths accessible without authentication |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | String | — | OAuth2 provider issuer URI |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | String | — | JWK set URI (alternative to issuer-uri) |

#### Custom SecurityFilterChain

The framework's `SecurityFilterChain` bean is annotated with `@ConditionalOnMissingBean`. To provide your own:

```java
@Configuration
public class CustomSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/api/public/**").permitAll();
                auth.requestMatchers("/api/admin/**").hasRole("ADMIN");
                auth.anyRequest().authenticated();
            })
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
```

### Testing with a Mock JWT Provider

For local development and testing, you can use a mock JWT decoder:

```java
@TestConfiguration
class MockJwtConfig {

    @Bean
    JwtDecoder jwtDecoder() {
        // Accept any token for testing
        return token -> {
            throw new UnsupportedOperationException("mock JwtDecoder");
        };
    }
}
```

---

## Layer 2: Service-to-Service Authentication (ITopic Events)

### How It Works

When services publish events to ITopic for saga coordination, the `EventAuthenticator` wraps each event in an `AuthenticatedEventEnvelope` containing:

- **sourceService** — the publishing service name (e.g., "order-service")
- **eventSignature** — HMAC-SHA256 signature of key event fields
- **signedAt** — timestamp (epoch milliseconds)
- **eventType** — the event type name
- **payload** — the original event as a nested GenericRecord

Consuming services unwrap the envelope and verify the signature using their own `ServiceIdentity` (which shares the same secret). The signing data is computed from: `eventId|eventType|source`.

**Warn-only mode**: Invalid signatures are currently logged as warnings but events are still processed. This allows gradual rollout — you can enable signing on some services while others catch up. Future versions will support strict rejection via configuration.

**Backward compatibility**: If a service receives a raw event (not wrapped in an envelope), the `EventAuthenticator` passes it through unchanged.

### Configuration

Add to each microservice's `application.yml`:

```yaml
framework:
  security:
    service-identity:
      enabled: true
      name: order-service          # Unique per service
      shared-secret: ${SERVICE_SHARED_SECRET:change-me-in-production}
```

**Important**: All services must share the **same `shared-secret`** for cross-service signature verification to work.

#### Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `framework.security.service-identity.enabled` | boolean | `false` | Enable event signing/verification |
| `framework.security.service-identity.name` | String | — | Unique service name |
| `framework.security.service-identity.shared-secret` | String | — | HMAC-SHA256 shared secret (same across all services) |

### Docker Compose Environment Variables

```yaml
environment:
  - FRAMEWORK_SECURITY_SERVICE_IDENTITY_ENABLED=true
  - FRAMEWORK_SECURITY_SERVICE_IDENTITY_NAME=order-service
  - FRAMEWORK_SECURITY_SERVICE_IDENTITY_SHARED_SECRET=my-production-secret
```

---

## Layer 3: MCP Server API Key Authentication

### How It Works

The MCP server supports API key authentication with role-based tool access control. Two modes are supported:

- **Stdio mode**: A single API key is read from an environment variable at startup (default: `MCP_API_KEY`). The resolved role applies to all tool invocations for the process lifetime.
- **HTTP/SSE mode**: Each request includes an `X-API-Key` header. The `McpApiKeyFilter` resolves the role per-request and stores it in a `ThreadLocal` that the `ToolAuthorizer` checks.

### Roles and Permissions

| Tool | VIEWER | OPERATOR | ADMIN |
|------|--------|----------|-------|
| `queryView` | Yes | Yes | Yes |
| `getEventHistory` | Yes | Yes | Yes |
| `inspectSaga` | Yes | Yes | Yes |
| `listSagas` | Yes | Yes | Yes |
| `getMetrics` | Yes | Yes | Yes |
| `submitEvent` | **No** | Yes | Yes |
| `runDemo` | **No** | Yes | Yes |

- **VIEWER**: Read-only access to queries, metrics, and saga inspection
- **OPERATOR**: Read + write — can also submit events and run demo scenarios
- **ADMIN**: Full access (same as OPERATOR today; future-proofed for admin-only tools)

### Configuration

```yaml
mcp:
  security:
    enabled: true
    api-key-env-var: MCP_API_KEY    # Environment variable for stdio mode
    api-keys:
      viewer-key-12345: VIEWER
      operator-key-67890: OPERATOR
      admin-key-99999: ADMIN
```

#### Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `mcp.security.enabled` | boolean | `false` | Enable API key authentication |
| `mcp.security.api-key-env-var` | String | `MCP_API_KEY` | Env var for stdio mode |
| `mcp.security.api-keys.<key>` | String | — | Map of API key to role name |

### Stdio Mode Usage

Set the environment variable before starting the MCP server:

```bash
export MCP_API_KEY=operator-key-67890
java -jar mcp-server.jar
```

### HTTP/SSE Mode Usage

Include the API key in each request:

```bash
curl -H "X-API-Key: operator-key-67890" http://localhost:8085/mcp/...
```

### Access Denied Response

When a tool invocation is denied, the authorizer returns a JSON error:

```json
{
  "error": "access_denied",
  "tool": "submitEvent",
  "message": "Role VIEWER does not have permission to use 'submitEvent'"
}
```

---

## Docker Compose Setup

The `docker/docker-compose.yml` includes commented-out environment variables for all three security layers. Uncomment them to enable security.

### Quick Start with Security

1. Uncomment the security environment variables in `docker-compose.yml`
2. Set a real shared secret (replace `change-me-in-production`)
3. Configure your OAuth2 provider's issuer URI
4. Start the stack:

```bash
cd docker
docker-compose up -d
```

### Environment Variables Reference

**API Gateway + Microservices (JWT)**:
```
FRAMEWORK_SECURITY_ENABLED=true
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://auth.example.com/realms/ecommerce
```

**Microservices (Service Identity)**:
```
FRAMEWORK_SECURITY_SERVICE_IDENTITY_ENABLED=true
FRAMEWORK_SECURITY_SERVICE_IDENTITY_NAME=<service-name>
FRAMEWORK_SECURITY_SERVICE_IDENTITY_SHARED_SECRET=<shared-secret>
```

**MCP Server**:
```
MCP_SECURITY_ENABLED=true
MCP_SECURITY_API_KEYS_<key-name>=<ROLE>
```

---

## Troubleshooting

### JWT Authentication

**Problem**: All requests return 401 Unauthorized.
- Verify `framework.security.enabled=true` is set
- Check that the `issuer-uri` is reachable from the service
- Ensure the JWT token's `iss` claim matches the configured issuer
- Check that the token has not expired

**Problem**: Actuator endpoints return 401.
- Verify `/actuator/**` is in `framework.security.public-paths`
- Default public paths include `/actuator/**` — only override if needed

**Problem**: Security is enabled but all requests pass through.
- Check that Spring Security and OAuth2 Resource Server are on the classpath
- Verify the application is running as a servlet web application

### Service-to-Service Authentication

**Problem**: "Invalid event signature" warnings in logs.
- Verify all services use the **same shared secret**
- Check that the `name` property differs per service (but the secret is identical)
- Ensure the event's `eventId`, `eventType`, and `source` fields are not modified between signing and verification

**Problem**: Events are not wrapped in envelopes.
- Verify `framework.security.service-identity.enabled=true`
- Check that `ServiceIdentity` and `EventAuthenticator` beans are created (look for INFO log on startup)

### MCP API Key Authentication

**Problem**: All MCP tool calls fail with "No valid API key provided".
- In stdio mode: set the `MCP_API_KEY` environment variable with a key that matches `mcp.security.api-keys`
- In HTTP mode: include the `X-API-Key` header in requests

**Problem**: VIEWER can't access a read-only tool.
- Check the tool name matches exactly (case-sensitive): `queryView`, `getEventHistory`, `inspectSaga`, `listSagas`, `getMetrics`

**Problem**: MCP security filter not active.
- Verify `mcp.security.enabled=true`
- HTTP filter is only created in servlet web application mode (Docker profile with `spring.main.web-application-type=servlet`)
- In stdio mode, there is no HTTP filter — the session role is set from the environment variable
