# Phase 3, Day 22: MCP Server Security & Role-Based Access

## Context

Third day of **Area 5: Security**. Adds API key authentication and role-based authorization to the MCP server. AI assistants connecting to the MCP server must present a valid API key, and each key maps to a role that controls which tools are accessible.

## Roles & Tool Access Matrix

| Tool | VIEWER | OPERATOR | ADMIN |
|------|--------|----------|-------|
| `queryView` | Yes | Yes | Yes |
| `getEventHistory` | Yes | Yes | Yes |
| `inspectSaga` | Yes | Yes | Yes |
| `listSagas` | Yes | Yes | Yes |
| `getMetrics` | Yes | Yes | Yes |
| `submitEvent` | **No** | Yes | Yes |
| `runDemo` | **No** | Yes | Yes |

- **VIEWER**: Read-only. Can query views, inspect sagas, read metrics. Cannot modify state.
- **OPERATOR**: Read + write. Can also submit events and run demo scenarios.
- **ADMIN**: Full access. Same as OPERATOR today; future-proofed for admin-only tools (e.g., config changes, DLQ management).

## Architecture

```
AI Assistant
    |
    | API Key in environment / MCP init params
    v
+-------------------+
| MCP Server        |
| (port 8085 SSE)   |
|                   |
|  McpApiKeyFilter  |  ← Validates API key, resolves role
|        |          |
|  ToolAuthorizer   |  ← Checks role ↔ tool permission
|        |          |
|  Tool methods     |  ← queryView, submitEvent, runDemo, etc.
+-------------------+
```

**Stdio transport note**: When running in stdio mode (default), the MCP server is launched as a subprocess by the AI client. In this mode, the API key is typically passed as an environment variable (`MCP_API_KEY`). The MCP server validates it on startup and applies the associated role for the entire session. There is no per-request HTTP filter in stdio mode — the key is validated once at initialization.

**SSE/HTTP transport** (Docker profile): When running with `spring.main.web-application-type=servlet`, a servlet filter validates the `X-API-Key` header on each HTTP request.

## Design Decisions

1. **API key over JWT for MCP** — MCP servers are typically accessed by AI assistants (not browser users), so a simple API key is more practical than OAuth2/JWT. API keys are simpler to configure in `claude_desktop_config.json` or environment variables.

2. **Role per API key** — Each API key maps to exactly one role. Configured via properties with format `mcp.security.api-keys.<key>=<role>`.

3. **Authorization at the tool level** — A `ToolAuthorizer` bean checks permissions before each tool invocation. Tools call `authorizer.checkAccess(toolName)` at the top of their `@Tool` method. Unauthorized access returns a JSON error message (does not throw).

4. **Disabled by default** — When `mcp.security.enabled=false` (default), all tools are accessible without authentication. Existing behavior preserved.

5. **No Spring Security dependency** — The MCP server is lightweight (no Hazelcast, no Spring Security). Authorization is handled with a simple custom `ToolAuthorizer` bean, avoiding a heavy dependency for what amounts to a lookup table.

## Files to Create

### Configuration & Auth (`mcp-server/.../security/`)

| File | Purpose |
|------|---------|
| `McpSecurityProperties.java` | `@ConfigurationProperties(prefix="mcp.security")` — enabled flag, API key→role map |
| `McpRole.java` | Enum: `VIEWER`, `OPERATOR`, `ADMIN` with tool permission sets |
| `ToolAuthorizer.java` | Validates current session role against tool's required permission |
| `McpApiKeyFilter.java` | Servlet `OncePerRequestFilter` for HTTP/SSE mode — validates `X-API-Key` header |
| `McpSecurityAutoConfiguration.java` | Conditional auto-config: creates beans when `mcp.security.enabled=true` |

### Tests (`mcp-server/.../security/`)

| File | Purpose |
|------|---------|
| `McpRoleTest.java` | Role permission matrix tests |
| `ToolAuthorizerTest.java` | Authorization accept/deny tests per role |
| `McpApiKeyFilterTest.java` | HTTP filter key validation tests |
| `McpSecurityAutoConfigurationTest.java` | Conditional bean creation tests |

## Files to Modify

| File | Change |
|------|--------|
| `QueryViewTool.java` | Add `ToolAuthorizer` injection + `checkAccess("queryView")` |
| `SubmitEventTool.java` | Add `ToolAuthorizer` injection + `checkAccess("submitEvent")` |
| `GetEventHistoryTool.java` | Add `ToolAuthorizer` injection + `checkAccess("getEventHistory")` |
| `InspectSagaTool.java` | Add `ToolAuthorizer` injection + `checkAccess("inspectSaga")` |
| `ListSagasTool.java` | Add `ToolAuthorizer` injection + `checkAccess("listSagas")` |
| `GetMetricsTool.java` | Add `ToolAuthorizer` injection + `checkAccess("getMetrics")` |
| `RunDemoTool.java` | Add `ToolAuthorizer` injection + `checkAccess("runDemo")` |
| `application.properties` | Add disabled-by-default security config |
| `application-docker.properties` | Add API key example config |

## Configuration

```yaml
# application.properties (default — security disabled)
mcp.security.enabled=false

# application-docker.properties (example with security enabled)
mcp.security.enabled=true
mcp.security.api-keys.viewer-key-12345=VIEWER
mcp.security.api-keys.operator-key-67890=OPERATOR
mcp.security.api-keys.admin-key-99999=ADMIN
```

**Environment variable override** (stdio mode):
```bash
MCP_API_KEY=operator-key-67890  # Set before launching MCP server
```

## Implementation Details

### McpRole Enum

```java
public enum McpRole {
    VIEWER(Set.of("queryView", "getEventHistory", "inspectSaga", "listSagas", "getMetrics")),
    OPERATOR(Set.of("queryView", "getEventHistory", "inspectSaga", "listSagas", "getMetrics",
                     "submitEvent", "runDemo")),
    ADMIN(Set.of("queryView", "getEventHistory", "inspectSaga", "listSagas", "getMetrics",
                  "submitEvent", "runDemo"));

    private final Set<String> allowedTools;
    // ...
    public boolean canAccess(String toolName) { return allowedTools.contains(toolName); }
}
```

### ToolAuthorizer Pattern

```java
// In each tool method:
@Tool(description = "...")
public String queryView(...) {
    String denied = toolAuthorizer.checkAccess("queryView");
    if (denied != null) return denied;  // Returns JSON error
    // ... normal logic
}
```

When security is disabled, `ToolAuthorizer` is not injected (`@Autowired(required=false)`), so the check is a null-safe no-op.

### McpApiKeyFilter (HTTP/SSE mode only)

- Reads `X-API-Key` header from each request
- Looks up role in `McpSecurityProperties.apiKeys` map
- Stores role in a `ThreadLocal` (or request attribute) for `ToolAuthorizer` to read
- Returns 401 if key is missing or invalid

### Stdio Mode Key Resolution

- On startup, reads `MCP_API_KEY` environment variable
- Resolves role from `McpSecurityProperties.apiKeys`
- Sets a "session role" on `ToolAuthorizer` (single role for entire process lifetime)
- If key is invalid, logs error and defaults to VIEWER (most restrictive)

## Verification

1. `mvn install -pl framework-core -DskipTests` (if needed)
2. `mvn clean test` (full suite — all existing + new tests)
3. When `mcp.security.enabled=false` (default): all tools accessible, no behavior change
4. When enabled with VIEWER key: `queryView`, `getEventHistory`, `inspectSaga`, `listSagas`, `getMetrics` work; `submitEvent`, `runDemo` return access denied JSON
5. When enabled with OPERATOR key: all tools accessible
