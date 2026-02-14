# Phase 2 Day 22: Saga & Metrics MCP Tools

## Context

Day 21 established the MCP server module with 3 tools (`queryView`, `submitEvent`, `getEventHistory`) that proxy REST calls to microservices. Day 22 adds 4 more tools for saga inspection, metrics, and demo execution — completing the MCP server's 7-tool suite.

The MCP server is a **pure REST proxy** (no Hazelcast dependency). All 4 new tools must follow this pattern: delegate to `ServiceClientOperations` which makes REST calls to the microservices.

## Implementation Summary

### Part A: Order Service — Saga REST Endpoints

Add a `SagaController` to the order service (which owns `SagaStateStore`) to expose saga data via REST.

**New file**: `order-service/src/main/java/com/theyawns/ecommerce/order/controller/SagaController.java`
- `GET /api/sagas/{sagaId}` — returns saga state as JSON (id, type, status, steps, duration, etc.)
- `GET /api/sagas?status={status}&limit={limit}` — list sagas filtered by status

The controller converts `SagaState` and `SagaStepRecord` to `Map<String, Object>` for JSON serialization (same pattern as `GenericRecordConverter.toMap()` used elsewhere).

### Part B: Order Service — Metrics Summary Endpoint

Add a `/api/metrics/summary` endpoint to the order service that returns a clean JSON summary of event and saga metrics from `SagaMetrics` and Micrometer.

**New file**: `order-service/src/main/java/com/theyawns/ecommerce/order/controller/MetricsController.java`
- `GET /api/metrics/summary` — returns aggregated metrics (saga counts, event counts, active sagas, etc.)

Uses `SagaMetrics` (already wired in order-service) for saga counters and `MeterRegistry` for event metrics.

### Part C: MCP Server — Extend ServiceClient

**Modify**: `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/client/ServiceClientOperations.java`
- Add `getSaga(String sagaId)` — calls order-service `GET /api/sagas/{id}`
- Add `listSagas(String status, int limit)` — calls order-service `GET /api/sagas?status=X&limit=Y`
- Add `getMetricsSummary()` — calls order-service `GET /api/metrics/summary`

**Modify**: `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/client/ServiceClient.java`
- Implement the 3 new methods

### Part D: MCP Server — 4 New Tools

**New file**: `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/tools/InspectSagaTool.java`
- `@Tool inspectSaga(String sagaId)` — calls `serviceClient.getSaga(sagaId)`, returns formatted JSON

**New file**: `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/tools/ListSagasTool.java`
- `@Tool listSagas(String status, Integer limit)` — calls `serviceClient.listSagas(status, limit)`

**New file**: `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/tools/GetMetricsTool.java`
- `@Tool getMetrics()` — calls `serviceClient.getMetricsSummary()`, returns system metrics

**New file**: `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/tools/RunDemoTool.java`
- `@Tool runDemo(String scenario)` — executes demo scenarios (happy_path, payment_failure, saga_timeout, load_sample_data) using existing `serviceClient.createEntity()` and `serviceClient.performAction()` calls
- Scenarios: creates customer, product, order; for `payment_failure`, uses a high-value order (>$10,000 limit); for `load_sample_data`, creates a set of sample entities

### Part E: Wire New Tools

**Modify**: `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/config/McpToolConfig.java`
- Add `InspectSagaTool`, `ListSagasTool`, `GetMetricsTool`, `RunDemoTool` to `MethodToolCallbackProvider.builder().toolObjects(...)`

**Modify**: `mcp-server/src/main/java/com/theyawns/ecommerce/mcp/McpServerApplication.java`
- Update JavaDoc to list all 7 tools

### Part F: Tests

**New files** (all in `mcp-server/src/test/java/com/theyawns/ecommerce/mcp/tools/`):
- `InspectSagaToolTest.java` — mock `ServiceClientOperations`, verify correct delegation
- `ListSagasToolTest.java` — mock, verify status filter and limit
- `GetMetricsToolTest.java` — mock, verify metrics summary call
- `RunDemoToolTest.java` — mock, verify scenario dispatching and error cases

**New file**: `order-service/src/test/java/com/theyawns/ecommerce/order/controller/SagaControllerTest.java`
- Test saga query endpoints with mocked `SagaStateStore`

**New file**: `order-service/src/test/java/com/theyawns/ecommerce/order/controller/MetricsControllerTest.java`
- Test metrics summary endpoint

All tests follow existing patterns: `@ExtendWith(MockitoExtension.class)`, mock `ServiceClientOperations` interface, Arrange-Act-Assert structure.

## Files to Create (10)

| # | File | Purpose |
|---|------|---------|
| 1 | `order-service/.../controller/SagaController.java` | REST endpoints for saga queries |
| 2 | `order-service/.../controller/MetricsController.java` | REST endpoint for metrics summary |
| 3 | `mcp-server/.../tools/InspectSagaTool.java` | MCP tool: inspect saga by ID |
| 4 | `mcp-server/.../tools/ListSagasTool.java` | MCP tool: list sagas by status |
| 5 | `mcp-server/.../tools/GetMetricsTool.java` | MCP tool: system metrics |
| 6 | `mcp-server/.../tools/RunDemoTool.java` | MCP tool: run demo scenarios |
| 7 | `mcp-server/.../tools/InspectSagaToolTest.java` | Test |
| 8 | `mcp-server/.../tools/ListSagasToolTest.java` | Test |
| 9 | `mcp-server/.../tools/GetMetricsToolTest.java` | Test |
| 10 | `mcp-server/.../tools/RunDemoToolTest.java` | Test |

## Files to Modify (5)

| # | File | Change |
|---|------|--------|
| 1 | `mcp-server/.../client/ServiceClientOperations.java` | Add 3 new methods |
| 2 | `mcp-server/.../client/ServiceClient.java` | Implement 3 new methods |
| 3 | `mcp-server/.../config/McpToolConfig.java` | Register 4 new tools |
| 4 | `mcp-server/.../McpServerApplication.java` | Update JavaDoc |
| 5 | `mcp-server/.../client/ServiceClientTest.java` | Add tests for new methods |

## Key Patterns to Follow

- **Tool class**: `@Service` + `@Tool` annotation on method (see `QueryViewTool.java`)
- **JSON serialization**: `ObjectMapper` with `INDENT_OUTPUT` (see existing tools)
- **Error handling**: Return `{"error": "message"}` JSON (see existing tools)
- **Test pattern**: `@ExtendWith(MockitoExtension.class)`, `@Mock ServiceClientOperations`, direct construction in `@BeforeEach`

## Verification

1. `mvn test -pl mcp-server` — all MCP tool tests pass
2. `mvn test -pl order-service` — saga/metrics controller tests pass
3. `mvn compile` — full project compiles
