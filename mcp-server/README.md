# MCP Server for Hazelcast Microservices Framework

Enables AI assistants (Claude, etc.) to interact with the event-sourced eCommerce microservices through the [Model Context Protocol](https://modelcontextprotocol.io/).

## Available Tools

| Tool | Description |
|------|-------------|
| `query_view` | Query materialized views (customer, product, order, payment) |
| `submit_event` | Submit domain events (CreateCustomer, CreateOrder, etc.) |
| `get_event_history` | Retrieve event history for domain entities |
| `inspect_saga` | View saga details (status, steps, timing) |
| `list_sagas` | List saga instances with optional status filter |
| `get_metrics` | Get system metrics (saga counts, event throughput) |
| `run_demo` | Execute demo scenarios (happy_path, payment_failure, etc.) |

## Setup

### Prerequisites

The eCommerce microservices must be running. Start them with Docker Compose:

```bash
mvn clean package -DskipTests
cd docker && docker-compose up -d
```

### Docker (HTTP/SSE transport) — Recommended

When running in Docker, the MCP server uses HTTP/SSE transport on port 8085, configured automatically via the `docker` Spring profile.

The project includes a `.mcp.json` at the repository root that registers the MCP server with Claude Code:

```json
{
  "mcpServers": {
    "hazelcast-ecommerce": {
      "type": "sse",
      "url": "http://localhost:8085/sse"
    }
  }
}
```

**To use it:**

1. Start Docker Compose (the MCP server starts automatically):
   ```bash
   cd docker && docker-compose up -d
   ```
2. Open Claude Code in the project directory. Claude Code reads `.mcp.json` automatically and will prompt once for approval to use the project-scoped MCP server.
3. Once approved, all 7 tools (`query_view`, `submit_event`, etc.) are available to Claude.

**Verify the MCP server is running:**
```bash
curl -s http://localhost:8085/actuator/health
```

### Local (stdio transport) — For Production or Non-Docker Setups

The stdio transport runs the MCP server as a subprocess managed by the AI assistant. No network port is needed — the assistant communicates via stdin/stdout.

**Build:**

```bash
mvn clean package -DskipTests -pl mcp-server
```

**Claude Code**: Add to your project's `.mcp.json` (or run `claude mcp add`):

```json
{
  "mcpServers": {
    "hazelcast-ecommerce": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "mcp-server/target/mcp-server-1.0.0-SNAPSHOT.jar"]
    }
  }
}
```

Or via CLI:
```bash
claude mcp add --transport stdio --scope project hazelcast-ecommerce -- \
  java -jar mcp-server/target/mcp-server-1.0.0-SNAPSHOT.jar
```

**Claude Desktop**: Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "hazelcast-ecommerce": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/mcp-server/target/mcp-server-1.0.0-SNAPSHOT.jar"]
    }
  }
}
```

**When to use stdio vs SSE:**

| | SSE (Docker) | stdio (Local) |
|---|---|---|
| **Setup** | `docker compose up` — zero config | Build jar, configure path |
| **Lifecycle** | Runs independently in Docker | AI assistant starts/stops the process |
| **Networking** | Port 8085 must be available | No ports needed |
| **Services** | Talks to services via Docker network | Services must be on localhost ports |
| **Best for** | Demos, development, shared environments | Production, CI/CD, air-gapped setups |

### Security (Optional)

Enable API key authentication for the MCP server:

```bash
# In Docker Compose environment:
MCP_SECURITY_ENABLED=true
MCP_SECURITY_API_KEYS_your-key-here=OPERATOR
```

See [Security Guide](../docs/guides/security-guide.md) for full details on roles (VIEWER, OPERATOR, ADMIN) and per-tool access control.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.services.account-url` | `http://localhost:8081` | Account Service base URL |
| `mcp.services.inventory-url` | `http://localhost:8082` | Inventory Service base URL |
| `mcp.services.order-url` | `http://localhost:8083` | Order Service base URL |
| `mcp.services.payment-url` | `http://localhost:8084` | Payment Service base URL |

## Tool Details

### query_view

Query the current state of domain entities from materialized views.

**Parameters:**
- `viewName` (required): `customer`, `product`, `order`, or `payment`
- `key` (optional): Specific entity ID to retrieve
- `limit` (optional): Maximum results when listing (default: 10)

### submit_event

Submit a domain event to the appropriate microservice.

**Parameters:**
- `eventType` (required): `CreateCustomer`, `CreateProduct`, `CreateOrder`, `CancelOrder`, `ReserveStock`, `ProcessPayment`, or `RefundPayment`
- `payload` (required): JSON string with event data

### get_event_history

Retrieve the event history for a domain entity, showing how current state was derived.

**Parameters:**
- `aggregateId` (required): Entity ID
- `aggregateType` (required): `Customer`, `Product`, `Order`, or `Payment`
- `limit` (optional): Maximum events to return (default: 20)

### inspect_saga

Inspect a saga by its ID, returning full state including step progress, timing, and failure details.

**Parameters:**
- `sagaId` (required): The saga identifier

### list_sagas

List saga instances with optional status filtering.

**Parameters:**
- `status` (optional): `STARTED`, `IN_PROGRESS`, `COMPLETED`, `COMPENSATING`, `COMPENSATED`, `FAILED`, or `TIMED_OUT`
- `limit` (optional): Maximum results (default: 10)

### get_metrics

Get aggregated system metrics including saga counts by status, event throughput, and active saga gauges.

**Parameters:** None

### run_demo

Execute a pre-built demo scenario that creates entities and triggers workflows.

**Parameters:**
- `scenario` (required): `happy_path`, `payment_failure`, `saga_timeout`, or `load_sample_data`

## Example Usage

See [MCP Example Conversations](../docs/guides/mcp-examples.md) for detailed interaction examples.

## Architecture

```
AI Assistant (Claude)
       │
       │ MCP Protocol (stdio or SSE)
       ▼
┌──────────────┐
│  MCP Server  │
│   :8085      │
└──────┬───────┘
       │ REST calls
       ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│   Account    │ │  Inventory   │ │    Order     │ │   Payment    │
│   :8081      │ │   :8082      │ │   :8083      │ │   :8084      │
└──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘
```

The MCP server is a pure REST proxy with no Hazelcast dependency. It translates MCP tool calls into REST API calls against the eCommerce microservices.
