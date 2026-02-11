# AI-Powered Microservices with Model Context Protocol

*Part 7 of 7 in the "Building Event-Driven Microservices with Hazelcast" series*

---

## Introduction

Over the past six articles, we built an event sourcing framework ([Part 1](01-event-sourcing-with-hazelcast-introduction.md)), a Jet pipeline ([Part 2](02-building-event-pipeline-with-hazelcast-jet.md)), materialized views ([Part 3](03-materialized-views-for-fast-queries.md)), an observability stack ([Part 4](04-observability-in-event-sourced-systems.md)), a choreographed saga pattern ([Part 5](05-saga-pattern-for-distributed-transactions.md)), and vector similarity search ([Part 6](06-vector-similarity-search-with-hazelcast.md)).

Now we give an AI assistant the ability to operate all of it.

The **Model Context Protocol** (MCP) is an open standard that lets AI assistants — Claude, ChatGPT, Copilot, and others — call tools exposed by external servers. Instead of the assistant guessing at curl commands or asking you to copy-paste output, it directly queries your materialized views, submits events, inspects saga state, and runs demo scenarios.

In this article, we'll build an MCP server that bridges AI assistants to our eCommerce microservices, covering:

- What MCP is and why it matters for microservices
- Designing tools around event sourcing primitives (query, command, observe)
- Implementing the MCP server with Spring AI
- The stdio vs. SSE transport decision for local and Docker deployments
- Example conversations showing the complete flow

---

## Why Give an AI Access to Your Microservices?

Consider a typical debugging session. A saga has failed, and you want to know why:

```bash
# Step 1: Find failed sagas
curl http://localhost:8083/api/sagas?status=FAILED

# Step 2: Copy a saga ID from the JSON output
curl http://localhost:8083/api/sagas/saga-a7f3e2

# Step 3: Check the order that triggered it
curl http://localhost:8083/api/orders/ord-12345

# Step 4: Check the event history
curl http://localhost:8083/api/orders/ord-12345/events

# Step 5: Check if stock was released as part of compensation
curl http://localhost:8082/api/products/prod-67890
```

Five commands, each requiring you to read JSON output, extract IDs, and construct the next request. With MCP, the same investigation is a single sentence:

> "Why did the most recent saga fail?"

The AI assistant calls `list_sagas(status="FAILED")`, then `inspect_saga(sagaId="saga-a7f3e2")`, then `get_event_history(aggregateId="ord-12345", aggregateType="Order")`, interprets all the responses, and gives you a plain-language summary:

> "Saga saga-a7f3e2 failed at the payment step. Order ORD-12345 had a total of $15,000 which exceeded the $10,000 payment limit. Compensation ran successfully — stock for product PROD-67890 was released."

The AI handles the multi-step investigation. You get the answer.

---

## What Is MCP?

MCP (Model Context Protocol) is an open specification by Anthropic that defines a standard interface between AI assistants and external tools. It works like a contract:

```
AI Assistant                MCP Server
     │                          │
     │── tools/list ──────────►│   "What can you do?"
     │◄── [7 tool definitions] ─│
     │                          │
     │── tools/call ──────────►│   "Call query_view with viewName=order"
     │◄── [JSON result] ────── │
     │                          │
```

The protocol uses JSON-RPC 2.0 over one of two transports:

| Transport | How It Works | Best For |
|-----------|-------------|----------|
| **stdio** | AI assistant launches the server as a subprocess; communicates via stdin/stdout | Local development with Claude Code or Claude Desktop |
| **SSE** (HTTP) | Server runs as a web service; AI connects over HTTP with Server-Sent Events | Docker, remote deployment, multi-user |

The key insight: the AI assistant doesn't need to know anything about Hazelcast, Jet pipelines, or event sourcing. It sees seven tools with descriptions and parameters. The MCP server handles the translation.

---

## Designing Tools Around Event Sourcing

The hardest part of building an MCP server isn't the protocol — it's deciding what tools to expose. Too many and the AI gets confused. Too few and it can't do useful work.

We organized our tools around the three concerns of an event-sourced system:

### Queries (Read Current State)

| Tool | What It Does |
|------|-------------|
| `query_view` | Read materialized views — the current state of customers, products, orders, payments |
| `get_event_history` | Read the event log — how an entity reached its current state |

These map directly to the read side of CQRS. Materialized views give you the "what," event history gives you the "why."

### Commands (Produce New Events)

| Tool | What It Does |
|------|-------------|
| `submit_event` | Create customers, products, orders; cancel orders; process payments; refund payments |
| `run_demo` | Execute multi-step scenarios (happy path, payment failure, saga timeout, sample data) |

These map to the write side. Each command produces one or more domain events that flow through the Jet pipeline.

### Observability (Inspect the System)

| Tool | What It Does |
|------|-------------|
| `inspect_saga` | View a saga's status, steps completed, timing, and failure reason |
| `list_sagas` | Browse sagas filtered by status |
| `get_metrics` | Aggregated system metrics — saga counts, event throughput, active gauges |

These give the AI visibility into the distributed coordination layer — the sagas and metrics that tie the services together.

### Why Seven?

Seven is enough for an AI assistant to handle any reasonable request about the system, but few enough that tool selection stays reliable. Each tool maps to a distinct concern. There's no overlap: you'd never call `get_metrics` when you meant `query_view`.

---

## Architecture: A Pure REST Proxy

The MCP server sits between the AI assistant and the microservices:

```
AI Assistant (Claude)
       │
       │ MCP Protocol (stdio or SSE)
       ▼
┌──────────────────┐
│    MCP Server    │     No Hazelcast dependency.
│   (Spring Boot)  │     No domain logic.
│     :8085        │     Pure translation layer.
└────────┬─────────┘
         │ REST (HTTP/JSON)
    ┌────┴────┬──────────┬──────────┐
    ▼         ▼          ▼          ▼
┌────────┐┌────────┐┌────────┐┌────────┐
│Account ││Invent. ││ Order  ││Payment │
│ :8081  ││ :8082  ││ :8083  ││ :8084  │
└────────┘└────────┘└────────┘└────────┘
```

A critical design decision: **the MCP server has no Hazelcast dependency**. It doesn't join any cluster, doesn't read IMaps, doesn't run Jet jobs. It's a thin REST proxy that translates MCP tool calls into HTTP requests against the existing service APIs.

This has several advantages:

- **No coupling to the data layer.** If you replace Hazelcast with another store, the MCP server doesn't change.
- **No classpath conflicts.** The MCP server's dependencies (Spring AI, RestClient) are completely separate from the services' dependencies (Hazelcast, Jet).
- **Testable in isolation.** Mock the REST responses and you can test every tool without running any services.
- **Lightweight.** The server needs 128-256 MB of heap — no partition data, no pipeline state.

---

## Implementation

### The ServiceClient

All HTTP communication goes through a single class:

```java
@Component
public class ServiceClient implements ServiceClientOperations {

    private final McpServerProperties properties;
    private final RestClient restClient;

    public Map<String, Object> getEntity(String viewName, String id) {
        String url = resolveUrl(viewName) + "/" + id;
        String json = restClient.get().uri(url).retrieve().body(String.class);
        return parseMap(json);
    }

    String resolveUrl(String viewName) {
        return switch (viewName.toLowerCase()) {
            case "customer" -> properties.getAccountUrl() + "/api/customers";
            case "product"  -> properties.getInventoryUrl() + "/api/products";
            case "order"    -> properties.getOrderUrl() + "/api/orders";
            case "payment"  -> properties.getPaymentUrl() + "/api/payments";
            default -> throw new IllegalArgumentException("Unknown view: " + viewName);
        };
    }
}
```

The `resolveUrl` switch is the only place that knows which service owns which view. Every tool delegates to `ServiceClient` rather than making HTTP calls directly.

Note the `ServiceClientOperations` interface — this exists because Mockito's inline mock maker on Java 25 cannot mock concrete classes. Extracting the interface lets us mock the client cleanly in tests.

### A Tool Implementation

Each tool is a Spring `@Service` with a `@Tool`-annotated method. Here's `QueryViewTool`:

```java
@Service
public class QueryViewTool {

    private final ServiceClientOperations serviceClient;

    @Tool(description = "Query a materialized view. "
            + "Available views: customer, product, order, payment. "
            + "Provide a key to get a specific entity, or omit to list entities.")
    public String queryView(
            @ToolParam(description = "View to query: customer, product, order, or payment")
            String viewName,
            @ToolParam(description = "Optional: specific entity ID", required = false)
            String key,
            @ToolParam(description = "Max results when listing (default: 10)", required = false)
            Integer limit) {

        if (key != null && !key.isBlank()) {
            return toJson(serviceClient.getEntity(viewName, key));
        } else {
            int effectiveLimit = (limit != null && limit > 0) ? limit : 10;
            List<Map<String, Object>> results = serviceClient.listEntities(viewName, effectiveLimit);
            return toJson(Map.of(
                    "view", viewName,
                    "count", results.size(),
                    "entities", results
            ));
        }
    }
}
```

Three things to notice:

1. **The `@Tool` description matters.** The AI reads it to decide which tool to call and what parameters to provide. Be specific: name the available views, explain what happens with vs. without a key.

2. **Optional parameters with defaults.** The `key` and `limit` parameters are `required = false`. When the AI omits them, the tool lists entities with a default limit of 10. This makes the tool flexible without requiring the AI to figure out every parameter.

3. **Return JSON strings.** The MCP protocol transmits tool results as strings. We serialize to JSON so the AI can parse structured data from the result.

### Tool Registration

All seven tools are registered in a single configuration class:

```java
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider mcpTools(QueryViewTool queryView,
                                         SubmitEventTool submitEvent,
                                         GetEventHistoryTool getEventHistory,
                                         InspectSagaTool inspectSaga,
                                         ListSagasTool listSagas,
                                         GetMetricsTool getMetrics,
                                         RunDemoTool runDemo) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(queryView, submitEvent, getEventHistory,
                        inspectSaga, listSagas, getMetrics, runDemo)
                .build();
    }
}
```

Spring AI's `MethodToolCallbackProvider` scans each object for `@Tool` methods and registers them with the MCP server. When the AI calls `tools/list`, it gets back all seven tool definitions with their descriptions and parameter schemas.

---

## The Event Dispatch Pattern

`SubmitEventTool` deserves a closer look because it maps a single tool to seven different service endpoints:

```java
Map<String, Object> dispatch(String eventType, Map<String, Object> payload) {
    return switch (eventType) {
        case "CreateCustomer"  -> serviceClient.createEntity("customer", payload);
        case "CreateProduct"   -> serviceClient.createEntity("product", payload);
        case "CreateOrder"     -> serviceClient.createEntity("order", payload);
        case "CancelOrder"     -> {
            String orderId = requireField(payload, "orderId");
            yield serviceClient.performAction("order", orderId, "cancel", payload, true);
        }
        case "ReserveStock"    -> {
            String productId = requireField(payload, "productId");
            yield serviceClient.performAction("product", productId, "stock/reserve", payload, false);
        }
        case "ProcessPayment"  -> serviceClient.createEntity("payment", payload);
        case "RefundPayment"   -> {
            String paymentId = requireField(payload, "paymentId");
            yield serviceClient.performAction("payment", paymentId, "refund", payload, false);
        }
        default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
    };
}
```

The alternative would be seven separate tools — `create_customer`, `create_product`, etc. We chose a single `submit_event` tool with an `eventType` discriminator because:

- **It mirrors the event sourcing model.** The system is event-driven; the tool should feel event-driven.
- **It reduces tool count.** Seven tools for commands plus three for queries plus three for observability would be thirteen tools. That's too many for reliable tool selection.
- **The AI handles dispatch naturally.** When you say "create a customer named Alice," the AI maps that to `eventType="CreateCustomer"` without difficulty.

---

## The Demo Tool: Multi-Step Orchestration

`RunDemoTool` is the most complex tool because each scenario chains multiple service calls:

```java
private Map<String, Object> runHappyPath() {
    // Step 1: Create customer
    Map<String, Object> customer = serviceClient.createEntity("customer", Map.of(
            "name", "Demo Customer",
            "email", "demo-" + shortId() + "@example.com",
            "address", "123 Demo Street"
    ));

    // Step 2: Create product
    Map<String, Object> product = serviceClient.createEntity("product", Map.of(
            "sku", "DEMO-" + shortId(),
            "name", "Demo Widget",
            "price", "29.99",
            "quantityOnHand", 100
    ));

    // Step 3: Create order (uses IDs from previous steps)
    String customerId = extractId(customer, "customerId");
    String productId = extractId(product, "productId");
    Map<String, Object> order = serviceClient.createEntity("order", Map.of(
            "customerId", customerId,
            "customerName", "Demo Customer",
            "lineItems", List.of(Map.of(
                    "productId", productId,
                    "productName", "Demo Widget",
                    "quantity", 2,
                    "unitPrice", 29.99
            ))
    ));

    return Map.of("scenario", "happy_path", "steps", List.of(...));
}
```

Each scenario uses `shortId()` (a UUID fragment) to generate unique emails, SKUs, and identifiers — so you can run the same scenario multiple times without conflicts.

The `payment_failure` scenario creates a $16,500 order (exceeding the $10,000 payment limit), which triggers saga compensation. The `saga_timeout` scenario creates an order with minimal stock, designed to hit the saga timeout deadline. These are pre-built investigation targets for the AI assistant.

---

## Stdio vs. SSE: Two Transport Modes

The MCP server supports two deployment modes via Spring profiles:

### Default: stdio (Local Development)

```properties
# application.properties
spring.main.web-application-type=none
spring.ai.mcp.server.name=ecommerce-mcp-server
```

The AI assistant launches the server as a subprocess. Communication flows through stdin/stdout using JSON-RPC:

```
Claude Code → spawns → java -jar mcp-server.jar
                ↕ stdin/stdout (JSON-RPC)
```

This is the default for local development with Claude Code or Claude Desktop. No network port needed.

### Docker: SSE/HTTP (Networked Deployment)

```properties
# application-docker.properties
spring.main.web-application-type=servlet
spring.ai.mcp.server.stdio=false
server.port=8085
```

In Docker, the MCP server runs as a web service with Server-Sent Events transport on port 8085:

```yaml
mcp-server:
  build: ../mcp-server
  ports:
    - "8085:8085"
  environment:
    - SPRING_PROFILES_ACTIVE=docker
    - MCP_SERVICES_ACCOUNT_URL=http://account-service:8081
    - MCP_SERVICES_INVENTORY_URL=http://inventory-service:8082
    - MCP_SERVICES_ORDER_URL=http://order-service:8083
    - MCP_SERVICES_PAYMENT_URL=http://payment-service:8084
```

The profile switch is the only difference between the two modes. The same tool code runs in both.

---

## Testing

### Unit Tests: Mock the ServiceClient

Each tool has a unit test class that mocks `ServiceClientOperations`:

```java
@ExtendWith(MockitoExtension.class)
class QueryViewToolTest {

    @Mock
    private ServiceClientOperations serviceClient;

    private QueryViewTool queryViewTool;

    @BeforeEach
    void setUp() {
        queryViewTool = new QueryViewTool(serviceClient);
    }

    @Test
    void shouldQueryByKey() throws JsonProcessingException {
        when(serviceClient.getEntity("customer", "c1"))
                .thenReturn(Map.of("customerId", "c1", "name", "Alice"));

        String result = queryViewTool.queryView("customer", "c1", null);

        verify(serviceClient).getEntity("customer", "c1");
        Map<String, Object> parsed = objectMapper.readValue(result, new TypeReference<>() {});
        assertNotNull(parsed.get("customerId"));
    }
}
```

Eight test classes cover all seven tools plus the `ServiceClient` itself. Total: 62 tests.

### Integration Tests: Verify Wiring

Integration tests use Spring's `ApplicationContextRunner` to verify that all beans are created and wired correctly — without starting the MCP stdio transport (which would block in a test):

```java
@DisplayName("MCP Tool Integration")
class McpToolIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(McpToolConfig.class))
            .withUserConfiguration(TestServiceClientConfig.class)
            .withBean(McpServerProperties.class);

    @Test
    void shouldCreateAllToolBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(QueryViewTool.class);
            assertThat(context).hasSingleBean(SubmitEventTool.class);
            // ... all 7 tools
        });
    }

    @Test
    void shouldRegisterToolCallbackProvider() {
        contextRunner.run(context -> {
            ToolCallbackProvider provider = context.getBean(ToolCallbackProvider.class);
            assertThat(provider.getToolCallbacks()).hasSize(7);
        });
    }
}
```

These tests catch wiring issues — missing `@Bean` definitions, circular dependencies, incorrect constructor injection — before you ever launch the server.

---

## Configuration

The MCP server has exactly four configuration properties:

```properties
mcp.services.account-url=http://localhost:8081
mcp.services.inventory-url=http://localhost:8082
mcp.services.order-url=http://localhost:8083
mcp.services.payment-url=http://localhost:8084
```

In Docker, these are overridden by environment variables that point to container hostnames. That's it. No Hazelcast configuration, no cluster membership, no pipeline setup. The MCP server is configuration-minimal by design.

---

## Setting Up the AI Assistant

### Claude Code

Add to `~/.claude/claude_code_config.json`:

```json
{
  "mcpServers": {
    "hazelcast-ecommerce": {
      "command": "java",
      "args": ["-jar", "mcp-server/target/mcp-server-1.0.0-SNAPSHOT.jar"]
    }
  }
}
```

### Claude Desktop

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "hazelcast-ecommerce": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/mcp-server-1.0.0-SNAPSHOT.jar"]
    }
  }
}
```

### Docker (SSE)

Point the AI assistant to the SSE endpoint:

```
http://localhost:8085/mcp
```

In all cases, the AI assistant discovers the seven tools automatically via `tools/list` and starts using them immediately.

---

## A Real Conversation

Here's what a debugging session looks like with MCP connected:

**You:** "Something's wrong with recent orders. Can you check?"

**Assistant:** *calls `list_sagas(status="FAILED")`*

*calls `get_metrics()`*

> "I found 2 failed sagas out of 142 total. Your overall success rate is 94.4%. Let me investigate the failures."

*calls `inspect_saga(sagaId="saga-fail-1")`*

*calls `inspect_saga(sagaId="saga-fail-2")`*

> "Both failures are payment rejections:
>
> 1. **saga-fail-1** (Order ORD-088): Total $15,000 exceeded the $10,000 payment limit. Stock was released via compensation.
> 2. **saga-fail-2** (Order ORD-091): Total $16,500, same payment limit issue. Stock also released.
>
> These aren't bugs — the payment service is correctly rejecting orders above the limit. You might want to either raise the payment limit or add client-side validation to prevent orders over $10,000 from being submitted."

Five tool calls, zero curl commands, a root-cause analysis, and a recommendation — all from a single question.

---

## Where to Go From Here

### Streaming Responses

The current tools return complete JSON responses. For large result sets (e.g., listing thousands of events), consider streaming results using MCP's streaming content support.

### Resource Endpoints

MCP also supports **resources** — read-only data endpoints that the AI can reference without explicitly calling a tool. You could expose the materialized views as resources, letting the AI browse entity state as context for its responses.

### Authentication

The current MCP server has no authentication — it's designed for local development. For production, you'd add:

- **API key validation** for the MCP connection
- **OAuth token forwarding** from the AI assistant to the backend services
- **Role-based tool access** (e.g., `run_demo` only in non-production environments)

### Custom AI Agents

With the MCP server as a foundation, you could build specialized AI agents:

- **Operations agent**: Monitors sagas, detects anomalies, suggests fixes
- **Demo agent**: Walks users through scenarios, explains event sourcing concepts
- **Testing agent**: Creates targeted test data, verifies saga compensation paths

---

## Summary

The MCP server adds a natural-language interface to the event-sourced microservices framework:

- **Seven tools** cover the three concerns of event sourcing: queries, commands, and observability
- **Pure REST proxy** architecture means no Hazelcast dependency and minimal configuration
- **Stdio and SSE transports** support both local development and Docker deployment
- **Spring AI `@Tool` annotations** make tool implementation a single annotated method
- **62 tests** (unit + integration) verify tools individually and wired together

The MCP server doesn't change how the microservices work. It doesn't add new capabilities to the data layer. What it does is make every existing capability accessible through conversation — and that changes how you interact with the system.

Instead of remembering endpoint paths, constructing JSON payloads, and chaining curl commands, you describe what you want in plain language. The AI handles the protocol. The MCP server handles the translation. The microservices handle the work.

---

*Previous: [Part 6 - Vector Similarity Search with Hazelcast](06-vector-similarity-search-with-hazelcast.md)*

*[Back to Part 1 - Event Sourcing with Hazelcast Introduction](01-event-sourcing-with-hazelcast-introduction.md)*
