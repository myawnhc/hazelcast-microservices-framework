# Phase 3 Day 9: Comparison Demo & Metrics

## Context

Days 6-8 added orchestrated saga support (interfaces, state machine, Order Fulfillment wiring). Day 9 makes the two saga patterns (choreographed vs orchestrated) **observable and comparable** — new MCP demo scenarios, per-step orchestrator metrics, Grafana comparison panels, and saga type filtering.

## Problem

1. Choreographed and orchestrated sagas both store `sagaType="OrderFulfillment"` — impossible to distinguish
2. No MCP demo scenarios exercise the orchestrated endpoint (`POST /api/orders/orchestrated`)
3. No per-step duration metrics for orchestrated sagas
4. Grafana has no way to compare choreography vs orchestration latency
5. `list_sagas` MCP tool has no type filter

---

## Plan (5 Work Units)

### WU1: Distinguish Orchestrated Saga Type Name

Rename the orchestrated saga definition from `"OrderFulfillment"` to `"OrderFulfillmentOrchestrated"`. Zero schema changes — just a different string in the SagaDefinition name.

**Files:**
- `order-service/.../saga/orchestrated/OrderFulfillmentSagaFactory.java` — line 62: `.name("OrderFulfillmentOrchestrated")`
- `order-service/.../saga/orchestrated/OrderFulfillmentSagaFactoryTest.java` — update expected name

### WU2: Add Step Duration Metrics to Orchestrator

Record per-step timing so Grafana can show step-by-step breakdown.

**Files:**
- `framework-core/.../saga/SagaMetrics.java` — add `recordStepDuration(sagaType, stepName, Duration)` → Timer `saga.step.duration` with tags `sagaType` + `stepName`
- `framework-core/.../saga/orchestrator/HazelcastSagaOrchestrator.java`:
  - Add `SagaMetrics sagaMetrics` field (nullable, optional)
  - Add 4-arg constructor `(stateStore, listeners, scheduler, sagaMetrics)`, keep 3-arg overload for backwards compat
  - Add `volatile Instant stepStartedAt` to `SagaExecution` inner class
  - Set `exec.stepStartedAt = Instant.now()` in `executeStep()` before step runs
  - Record `sagaMetrics.recordStepDuration(...)` in `handleStepSuccessInternal()`, `handleStepFailure()`, `handleStepTimeout()`
- `framework-core/.../saga/orchestrator/SagaOrchestratorAutoConfiguration.java` — inject `SagaMetrics` from context via `@Autowired(required = false)`, pass to 4-arg constructor
- **Tests:** `SagaMetricsTest` (step duration), `HazelcastSagaOrchestratorTest` (verify step metrics recorded), `SagaOrchestratorAutoConfigurationTest` (verify bean wiring)

### WU3: MCP Orchestrated Demo Scenarios + Type Filter

**3a — ServiceClientOperations interface changes:**
- Add `createOrchestratedOrder(Map<String, Object> payload)` — POSTs to `{orderUrl}/api/orders/orchestrated`
- Add `listSagas(String status, String type, int limit)` — passes `type` query param
- Convert existing `listSagas(String status, int limit)` to a `default` method delegating to the 3-param version

**3b — ServiceClient implementation:**
- Implement `createOrchestratedOrder()` — `POST {orderUrl}/api/orders/orchestrated`
- Implement `listSagas(status, type, limit)` — append `&type=` when present

**3c — RunDemoTool — 2 new scenarios:**
- `orchestrated_happy_path`: Create customer + product + call `createOrchestratedOrder()` (same payload pattern as happy_path, adds `"sagaPattern": "orchestrated"` to result)
- `orchestrated_payment_failure`: Create customer + expensive product + call `createOrchestratedOrder()` with total > $10,000
- Update `@Tool` description and switch cases

**3d — ListSagasTool — add `type` parameter:**
- Add `@ToolParam(required = false) String type` parameter
- Pass to `serviceClient.listSagas(status, type, effectiveLimit)`
- Include `type` in response JSON

**Tests:** `RunDemoToolTest` (2 new scenario tests), `ListSagasToolTest` (type filter test)

### WU4: SagaController Type Filter

Add `type` query parameter to `GET /api/sagas`.

**File:** `order-service/.../controller/SagaController.java`
- Add `@RequestParam(required = false) String type` to `listSagas()`
- When `type` is set: use `sagaStateStore.findSagasByType(type)`, then apply status filter if also set
- When both status and type: filter the findSagasByType result by status

**Tests:** `SagaControllerTest` — add type filter tests, update existing calls

### WU5: Grafana Comparison Dashboard Panels

Add a "Choreography vs Orchestration" row to `saga-dashboard.json` (between "Success Rate" and "Resilience: Circuit Breakers").

**3 new panels:**
1. **Duration Comparison** — p50/p95 side-by-side: `saga_duration_seconds{sagaType="OrderFulfillment"}` vs `saga_duration_seconds{sagaType="OrderFulfillmentOrchestrated"}`
2. **Orchestrated Step Timing** — per-step p50/p95 from new `saga_step_duration_seconds{sagaType="OrderFulfillmentOrchestrated"}` metric
3. **Success Rate Comparison** — stat panels: `completed / (completed + compensated + failed + timedout)` for each saga type

Shift subsequent panel y-positions down to make room.

---

## Execution Order

```
WU1 (saga name rename) ← foundation, do first
  ├── WU2 (step metrics)
  ├── WU3 (MCP tools)
  └── WU4 (SagaController type filter)
WU5 (Grafana) ← last, references metric names from WU2
```

WU2, WU3, WU4 can be done in any order after WU1.

## Verification

1. `mvn test -pl framework-core` — SagaMetrics, HazelcastSagaOrchestrator, auto-config tests
2. `mvn test -pl order-service` — OrderFulfillmentSagaFactory, SagaController tests
3. `mvn test -pl mcp-server` — RunDemoTool, ListSagasTool tests
4. Full build: `mvn clean verify` across all modules
