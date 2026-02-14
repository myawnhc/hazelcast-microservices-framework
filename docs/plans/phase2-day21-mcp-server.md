# Day 21: MCP Server Setup & Core Tools

## Context

The framework needs an MCP (Model Context Protocol) server so AI assistants can interact with the running microservices — querying materialized views, submitting domain events, and inspecting event history. This is Phase 2 Day 21 of the implementation plan.

**Key architectural constraint**: Due to the dual-instance Hazelcast architecture (ADR 008), each service's event store and views live on its isolated embedded Hazelcast instance. The MCP server cannot connect to these directly via a Hazelcast client. Therefore, the MCP server uses a **REST proxy** pattern — calling each service's REST APIs.

**Decisions already made**:
- Transport: **stdio** (SSE/HTTP deferred to Phase 3)
- Data access: **REST proxy** (no Hazelcast dependency in MCP module)
- SDK: **Spring AI MCP Server** (`spring-ai-starter-mcp-server`)
- Scope: 3 tools — `query_view`, `submit_event`, `get_event_history`

---

## Step 1: Add `mcp-server` Module to Root POM

**File**: `pom.xml` (root)

Add to `<modules>`:
```xml
<module>mcp-server</module>
```

Add to `<dependencyManagement>`:
```xml
<dependency>
    <groupId>com.theyawns</groupId>
    <artifactId>mcp-server</artifactId>
    <version>${project.version}</version>
</dependency>
```

Add Spring AI BOM to `<dependencyManagement>`:
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-bom</artifactId>
    <version>${spring-ai.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

Add property:
```xml
<spring-ai.version>1.0.0</spring-ai.version>
```

Add Spring milestones/snapshots repo if needed for Spring AI artifacts.

---

## Step 2: Create `mcp-server` Module

**File**: `mcp-server/pom.xml`

```
Parent: hazelcast-microservices-framework
ArtifactId: mcp-server
Dependencies:
  - spring-ai-starter-mcp-server (provides stdio MCP transport + @Tool support)
  - spring-boot-starter-web (for RestClient — Tomcat disabled via config)
  - jackson-databind (JSON processing, likely transitive)
  - spring-boot-starter-test (test)
```

No dependency on framework-core, ecommerce-common, or Hazelcast — this module is a pure REST proxy.

### Directory structure:
```
mcp-server/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/theyawns/ecommerce/mcp/
    │   │   ├── McpServerApplication.java
    │   │   ├── config/
    │   │   │   ├── McpServerProperties.java
    │   │   │   └── McpToolConfig.java
    │   │   ├── client/
    │   │   │   └── ServiceClient.java
    │   │   └── tools/
    │   │       ├── QueryViewTool.java
    │   │       ├── SubmitEventTool.java
    │   │       └── GetEventHistoryTool.java
    │   └── resources/
    │       └── application.properties
    └── test/
        └── java/com/theyawns/ecommerce/mcp/
            ├── client/
            │   └── ServiceClientTest.java
            └── tools/
                ├── QueryViewToolTest.java
                ├── SubmitEventToolTest.java
                └── GetEventHistoryToolTest.java
```

---

## Step 3: `McpServerApplication.java`

**File**: `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/McpServerApplication.java`

Standard Spring Boot main class. No web server (configured in properties).

---

## Step 4: `McpServerProperties.java`

**File**: `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/config/McpServerProperties.java`

`@ConfigurationProperties(prefix = "mcp.services")` with base URLs for each service:
- `account-url` (default: `http://localhost:8081`)
- `inventory-url` (default: `http://localhost:8082`)
- `order-url` (default: `http://localhost:8083`)
- `payment-url` (default: `http://localhost:8084`)

---

## Step 5: `ServiceClient.java`

**File**: `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/client/ServiceClient.java`

A thin REST client that wraps Spring's `RestClient`. Methods:

| Method | Delegates to |
|--------|-------------|
| `getEntity(service, id)` | `GET /{service-base}/api/{entity}/{id}` |
| `listEntities(service, limit)` | `GET /{service-base}/api/{entity}?limit={limit}` |
| `createEntity(service, payload)` | `POST /{service-base}/api/{entity}` |
| `performAction(service, id, action, payload)` | `POST/PATCH /{service-base}/api/{entity}/{id}/{action}` |
| `getEventHistory(service, id, limit)` | `GET /{service-base}/api/{entity}/{id}/events?limit={limit}` |

Internally maps view/aggregate names to service URLs and endpoint paths. Returns `Map<String, Object>` or `List<Map<String, Object>>` (deserialized JSON).

Error handling: catches `RestClientResponseException`, returns structured error messages.

---

## Step 6: MCP Tool Implementations

### 6a. `QueryViewTool.java`

**File**: `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/tools/QueryViewTool.java`

```java
@Service
public class QueryViewTool {

    @Tool(description = "Query a materialized view. Available views: customer, product, order, payment. "
        + "Provide a key to get a specific entity, or omit to list entities.")
    public String queryView(
            @ToolParam(description = "View to query: customer, product, order, or payment") String viewName,
            @ToolParam(description = "Optional: specific entity ID to retrieve", required = false) String key,
            @ToolParam(description = "Maximum results when listing (default: 10)", required = false) Integer limit) {
        // Delegates to ServiceClient
    }
}
```

**View-to-service mapping**:
| View name | Service URL | Entity path |
|-----------|-------------|-------------|
| `customer` | account (8081) | `/api/customers` |
| `product` | inventory (8082) | `/api/products` |
| `order` | order (8083) | `/api/orders` |
| `payment` | payment (8084) | `/api/payments` |

### 6b. `SubmitEventTool.java`

**File**: `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/tools/SubmitEventTool.java`

```java
@Service
public class SubmitEventTool {

    @Tool(description = "Submit a domain event. Supported event types: "
        + "CreateCustomer, CreateProduct, CreateOrder, CancelOrder, ReserveStock, ProcessPayment, RefundPayment")
    public String submitEvent(
            @ToolParam(description = "Event type") String eventType,
            @ToolParam(description = "Event payload as JSON string") String payload) {
        // Parse payload, map event type to service endpoint, POST/PATCH
    }
}
```

**Event-type-to-endpoint mapping**:
| Event type | HTTP method | Endpoint |
|-----------|-------------|----------|
| `CreateCustomer` | POST | account `/api/customers` |
| `CreateProduct` | POST | inventory `/api/products` |
| `CreateOrder` | POST | order `/api/orders` |
| `CancelOrder` | PATCH | order `/api/orders/{id}/cancel` |
| `ReserveStock` | POST | inventory `/api/products/{id}/stock/reserve` |
| `ProcessPayment` | POST | payment `/api/payments` |
| `RefundPayment` | POST | payment `/api/payments/{id}/refund` |

### 6c. `GetEventHistoryTool.java`

**File**: `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/tools/GetEventHistoryTool.java`

```java
@Service
public class GetEventHistoryTool {

    @Tool(description = "Get event history for a domain entity. "
        + "Aggregate types: Customer, Product, Order, Payment")
    public String getEventHistory(
            @ToolParam(description = "Entity ID") String aggregateId,
            @ToolParam(description = "Aggregate type: Customer, Product, Order, or Payment") String aggregateType,
            @ToolParam(description = "Maximum events to return (default: 20)", required = false) Integer limit) {
        // Maps aggregate type to service, calls GET /api/{entity}/{id}/events
    }
}
```

---

## Step 7: `McpToolConfig.java`

**File**: `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/config/McpToolConfig.java`

Registers tool callbacks:
```java
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider mcpTools(QueryViewTool queryView,
                                         SubmitEventTool submitEvent,
                                         GetEventHistoryTool getEventHistory) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(queryView, submitEvent, getEventHistory)
                .build();
    }
}
```

---

## Step 8: `application.properties`

**File**: `mcp-server/src/main/resources/application.properties`

```properties
spring.application.name=ecommerce-mcp-server
spring.main.web-application-type=none
spring.ai.mcp.server.name=ecommerce-mcp-server
spring.ai.mcp.server.version=1.0.0

# Service URLs
mcp.services.account-url=http://localhost:8081
mcp.services.inventory-url=http://localhost:8082
mcp.services.order-url=http://localhost:8083
mcp.services.payment-url=http://localhost:8084
```

---

## Step 9: Add REST Endpoints to Existing Services

Each service needs two new endpoints to support MCP queries. These are lightweight additions to existing controllers.

### 9a. List-all endpoints (4 services)

| Service | Endpoint | Returns |
|---------|----------|---------|
| Account | `GET /api/customers?limit=10` | List of CustomerDTO |
| Inventory | `GET /api/products?limit=10` | List of ProductDTO |
| Order | `GET /api/orders?limit=10` | List of OrderDTO |
| Payment | `GET /api/payments?limit=10` | List of PaymentDTO |

Implementation: Each service's existing Service class exposes a method that iterates the view map (already available via `EventSourcingController.getViewMap()`), converts GenericRecords to DTOs, and returns a limited list.

### 9b. Event-history endpoints (4 services)

| Service | Endpoint | Returns |
|---------|----------|---------|
| Account | `GET /api/customers/{id}/events?limit=20` | List of event maps |
| Inventory | `GET /api/products/{id}/events?limit=20` | List of event maps |
| Order | `GET /api/orders/{id}/events?limit=20` | List of event maps |
| Payment | `GET /api/payments/{id}/events?limit=20` | List of event maps |

Implementation: Each service calls `EventSourcingController.getEventStore().getEventsByKey(id)`, converts each `GenericRecord` to a `Map<String, Object>` using a utility converter, and returns the list.

### 9c. GenericRecord-to-Map converter utility

**File**: `ecommerce-common/src/main/java/com/theyawns/ecommerce/common/util/GenericRecordConverter.java`

Static utility: `Map<String, Object> toMap(GenericRecord record)` — iterates `record.getFieldNames()`, reads each field using the appropriate typed getter based on `FieldKind`, and builds a map. This is reused by all 4 services.

**File**: `ecommerce-common/src/test/java/com/theyawns/ecommerce/common/util/GenericRecordConverterTest.java`

---

## Files Summary

### New files (14):
| File | Description |
|------|-------------|
| `mcp-server/pom.xml` | Module POM |
| `mcp-server/.../McpServerApplication.java` | Main class |
| `mcp-server/.../config/McpServerProperties.java` | Service URL config |
| `mcp-server/.../config/McpToolConfig.java` | Tool registration |
| `mcp-server/.../client/ServiceClient.java` | REST client wrapper |
| `mcp-server/.../tools/QueryViewTool.java` | View query tool |
| `mcp-server/.../tools/SubmitEventTool.java` | Event submission tool |
| `mcp-server/.../tools/GetEventHistoryTool.java` | Event history tool |
| `mcp-server/src/main/resources/application.properties` | Config |
| `mcp-server/.../client/ServiceClientTest.java` | REST client tests |
| `mcp-server/.../tools/QueryViewToolTest.java` | Query tool tests |
| `mcp-server/.../tools/SubmitEventToolTest.java` | Submit tool tests |
| `mcp-server/.../tools/GetEventHistoryToolTest.java` | History tool tests |
| `ecommerce-common/.../util/GenericRecordConverter.java` | GenericRecord->Map utility |

### Modified files (9):
| File | Change |
|------|--------|
| `pom.xml` (root) | Add mcp-server module, Spring AI BOM, spring-ai.version property |
| `AccountController.java` | Add `GET /api/customers` list endpoint |
| `CustomerService.java` (or equivalent) | Add `listAll(limit)` method |
| `InventoryController.java` | Add `GET /api/products` list endpoint |
| `ProductService.java` (or equivalent) | Add `listAll(limit)` method |
| `OrderController.java` | Add `GET /api/orders` list endpoint |
| `PaymentController.java` | Add `GET /api/payments` list endpoint |
| All 4 controllers | Add `GET /api/{entity}/{id}/events` event-history endpoint |
| `ecommerce-common` test dir | Add GenericRecordConverterTest |

---

## Verification

1. **Community build**: `mvn clean install` — all modules build including mcp-server
2. **Unit tests**: `mvn test -pl mcp-server` — all MCP tool tests pass
3. **Integration smoke test** (manual):
   - Start services: `docker compose up` (or run locally)
   - Run MCP server: `java -jar mcp-server/target/mcp-server-*.jar`
   - Test via stdio: pipe JSON-RPC `tools/list` -> verify 3 tools returned
   - Test `query_view`: create a customer via service API, then query via MCP tool
   - Test `submit_event`: submit CreateCustomer via MCP, verify via service API
   - Test `get_event_history`: query events for a known entity
4. **Existing tests**: `mvn test` — all existing service tests still pass (new endpoints are additive)
