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

### Local (stdio transport)

The default configuration uses stdio transport for direct integration with AI assistants.

**Build:**

```bash
mvn clean package -DskipTests -pl mcp-server
```

**Claude Code configuration** (`~/.claude/claude_code_config.json`):

```json
{
  "mcpServers": {
    "hazelcast-ecommerce": {
      "command": "java",
      "args": ["-jar", "/path/to/mcp-server/target/mcp-server-1.0.0-SNAPSHOT.jar"]
    }
  }
}
```

**Claude Desktop configuration** (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "hazelcast-ecommerce": {
      "command": "java",
      "args": ["-jar", "/path/to/mcp-server/target/mcp-server-1.0.0-SNAPSHOT.jar"]
    }
  }
}
```

### Docker (HTTP/SSE transport)

When running in Docker, the MCP server uses HTTP/SSE transport on port 8085. This is configured automatically via the `docker` Spring profile.

```bash
cd docker && docker-compose up -d mcp-server
```

The SSE endpoint is available at `http://localhost:8085/mcp`.

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
