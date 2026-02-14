# Phase 3 Day 8: Orchestrated Order Fulfillment Saga

## Context

Day 7 delivered `HazelcastSagaOrchestrator` — a working state machine that executes saga steps sequentially, compensates in reverse on failure, enforces timeouts, retries, and persists state via `SagaStateStore`. Day 8 wires this orchestrator to the real Order Fulfillment domain: creating an order, reserving stock, processing payment, and confirming the order — all driven by the orchestrator instead of the existing choreographed ITopic event chain.

**Key constraint**: Both saga modes (choreographed and orchestrated) must coexist. The existing choreographed saga listeners must not react to events produced by orchestrated saga steps.

## Design Decisions

### HTTP-based step execution
The orchestrator runs in order-service and makes **HTTP calls** to inventory-service and payment-service via `RestClient`. This avoids the Jet lambda serialization problem (ADR 008) while giving the orchestrator direct request-response control.

### No-sagaId guard for listener isolation
The existing choreographed saga listeners already guard on `sagaId != null && !sagaId.isEmpty()` — events without saga metadata are skipped. The orchestrated service methods create events **without** `SagaMetadata`, so the choreographed listeners naturally ignore them. The orchestrator tracks all saga state via `SagaStateStore`, so no duplicate recording occurs.

### 4-step saga definition
| Step | Action | Compensation | Location |
|------|--------|--------------|----------|
| 0 - CreateOrder | Create order (no saga metadata) | Cancel order | Local (order-service) |
| 1 - ReserveStock | HTTP → inventory-service | HTTP → release stock | Remote |
| 2 - ProcessPayment | HTTP → payment-service | HTTP → refund payment | Remote |
| 3 - ConfirmOrder | Confirm order locally | _(none — final step)_ | Local |

Step 0's compensation (CancelOrder) ensures the order is cleaned up automatically if any subsequent step fails.

### Interface extraction for SagaServiceClient
Following the Java 25 + Mockito pattern (from MEMORY.md), extract `SagaServiceClientOperations` interface so tests can mock the HTTP client.

---

## Files to Create

### 1. Framework-Core: Auto-Configuration

**`framework-core/src/main/java/com/theyawns/framework/saga/orchestrator/SagaOrchestratorAutoConfiguration.java`** (~50 lines)
- `@AutoConfiguration`, `@ConditionalOnBean(SagaStateStore.class)`
- Creates `LoggingSagaOrchestratorListener` bean
- Creates `HazelcastSagaOrchestrator` bean (injects `SagaStateStore`, list of `SagaOrchestratorListener`, and a `ScheduledExecutorService`)
- Register in `AutoConfiguration.imports`

**`framework-core/src/main/java/com/theyawns/framework/saga/orchestrator/LoggingSagaOrchestratorListener.java`** (~60 lines)
- Implements `SagaOrchestratorListener`
- Logs all lifecycle events at INFO level (saga started/completed, step started/completed, compensation events)

### 2. ecommerce-common: Shared Response DTO

**`ecommerce-common/src/main/java/com/theyawns/ecommerce/common/dto/OrchestratedStepResponse.java`** (~30 lines)
- Java record: `boolean success`, `Map<String, Object> data`, `String errorMessage`
- Static factories: `success(Map<String, Object> data)`, `failure(String errorMessage)`
- Used as the standard HTTP response from orchestrated endpoints

### 3. Order-Service: Orchestration Layer

**`order-service/.../order/saga/orchestrated/SagaServiceClientOperations.java`** (~30 lines)
- Interface with 4 methods:
  - `reserveStock(String productId, int quantity, String orderId)` → `OrchestratedStepResponse`
  - `releaseStock(String orderId, String reason)` → `OrchestratedStepResponse`
  - `processPayment(String orderId, String customerId, String amount, String currency, String method)` → `OrchestratedStepResponse`
  - `refundPayment(String paymentId, String reason)` → `OrchestratedStepResponse`

**`order-service/.../order/saga/orchestrated/SagaServiceClient.java`** (~120 lines)
- Implements `SagaServiceClientOperations`
- Constructor: `RestClient.Builder restClientBuilder`, inventory base URL, payment base URL
- URLs configured via `framework.saga.orchestrator.services.inventory.base-url` and `.payment.base-url`
- Each method: POST to the corresponding orchestrated endpoint, parse response as `OrchestratedStepResponse`
- On HTTP error: return `OrchestratedStepResponse.failure(...)` rather than throwing

**`order-service/.../order/saga/orchestrated/OrderFulfillmentSagaFactory.java`** (~150 lines)
- Constructor: `OrderOperations orderOps`, `SagaServiceClientOperations serviceClient`
- Method: `create()` → `SagaDefinition` with 4 steps
- Step 0 (CreateOrder) action:
  - Read `customerId`, `lineItems` (JSON), `shippingAddress` from `SagaContext`
  - Build `OrderDTO`, call `orderOps.createOrder(dto)` ← **uses existing non-saga `createOrder` after modification (see below)**
  - Put `orderId` into context
  - Return `SagaStepResult.success()`
- Step 0 compensation:
  - Read `orderId` from context, call `orderOps.cancelOrder(orderId, reason, "orchestrator")`
- Step 1 (ReserveStock) action:
  - Read `orderId`, `productId`, `quantity` from context
  - Call `serviceClient.reserveStock(productId, quantity, orderId)`
  - Return success/failure based on response
- Step 1 compensation:
  - Call `serviceClient.releaseStock(orderId, reason)`
- Step 2 (ProcessPayment) action:
  - Read order details from context
  - Call `serviceClient.processPayment(orderId, customerId, amount, currency, method)`
  - Put `paymentId` from response data into context
  - Return success/failure
- Step 2 compensation:
  - Read `paymentId` from context, call `serviceClient.refundPayment(paymentId, reason)`
- Step 3 (ConfirmOrder) action:
  - Read `orderId` from context
  - Call `orderOps.confirmOrder(orderId)`
  - Return success
- Step 3: no compensation (final step)
- Saga timeout: 60s, step timeouts: 15s each (step 3: 10s)

**`order-service/.../order/saga/orchestrated/OrchestratedOrderController.java`** (~80 lines)
- `@RestController`, `@RequestMapping("/api/orders/orchestrated")`
- `POST /api/orders/orchestrated` — accepts `OrderDTO`
- Builds `SagaContext` from DTO fields (productId, quantity, customerId, amount, currency, paymentMethod, shippingAddress, lineItems as JSON)
- Generates sagaId, calls `orchestrator.start(sagaId, sagaFactory.create(), context)`
- Returns `CompletableFuture<ResponseEntity<OrderDTO>>`:
  - On COMPLETED: fetch order from `OrderOperations.getOrder()`, return 201
  - On failure: return 409 with error details

### 4. Inventory-Service: Orchestrated Endpoints

**`inventory-service/.../inventory/controller/OrchestratedInventoryController.java`** (~80 lines)
- `@RestController`, `@RequestMapping("/api/saga/inventory")`
- `POST /api/saga/inventory/reserve-stock`
  - Accepts: `productId`, `quantity`, `orderId` (as JSON body or params)
  - Calls `inventoryService.reserveStockOrchestrated(productId, quantity, orderId)`
  - Returns `OrchestratedStepResponse`
- `POST /api/saga/inventory/release-stock`
  - Accepts: `orderId`, `reason`
  - Calls `inventoryService.releaseStockOrchestrated(orderId, reason)`
  - Returns `OrchestratedStepResponse`

### 5. Payment-Service: Orchestrated Endpoints

**`payment-service/.../payment/controller/OrchestratedPaymentController.java`** (~80 lines)
- `@RestController`, `@RequestMapping("/api/saga/payment")`
- `POST /api/saga/payment/process`
  - Accepts: `orderId`, `customerId`, `amount`, `currency`, `method`
  - Calls `paymentService.processPaymentOrchestrated(orderId, customerId, amount, currency, method)`
  - Returns `OrchestratedStepResponse`
- `POST /api/saga/payment/refund`
  - Accepts: `paymentId`, `reason`
  - Calls `paymentService.refundPaymentOrchestrated(paymentId, reason)`
  - Returns `OrchestratedStepResponse`

---

## Files to Modify

### 1. `OrderService.java` → Add `createOrderPlain()`
- New method: `createOrderPlain(OrderDTO dto)` → `CompletableFuture<Order>`
- Same as `createOrder()` but **without** saga state store calls, saga metadata, or SagaMetadata on the event
- Generates orderId, creates `OrderCreatedEvent` (plain), handles via EventSourcingController, indexes by customer
- Choreographed `createOrder()` remains unchanged
- Add to `OrderOperations.java` interface

### 2. `InventoryService.java` → Add orchestrated methods
- `reserveStockOrchestrated(String productId, int quantity, String orderId)` → `CompletableFuture<Product>`
  - Same business logic as `reserveStockForSaga()` but: no SagaMetadata on event, no sagaStateStore calls, no payment context in event
  - Still tracks reservation in `OrderStockReservations` IMap
- `releaseStockOrchestrated(String orderId, String reason)` → `CompletableFuture<Product>`
  - Same business logic as `releaseStockForSaga()` but: no SagaMetadata, no sagaStateStore calls
- No interface to modify (InventoryService has no separate interface)

### 3. `PaymentService.java` → Add orchestrated methods
- `processPaymentOrchestrated(String orderId, String customerId, String amount, String currency, String method)` → `CompletableFuture<Payment>`
  - Same business logic as `processPaymentForOrder()` but: no SagaMetadata, no sagaStateStore calls
  - Still simulates payment processing
- `refundPaymentOrchestrated(String paymentId, String reason)` → `CompletableFuture<Payment>`
  - Same as `refundPaymentForSaga()` but: no SagaMetadata, no sagaStateStore calls
- Add to `PaymentOperations.java` interface

### 4. `AutoConfiguration.imports` → Register `SagaOrchestratorAutoConfiguration`

### 5. `order-service/src/main/resources/application.yml` → Add service URLs
```yaml
framework:
  saga:
    orchestrator:
      services:
        inventory:
          base-url: ${INVENTORY_SERVICE_URL:http://localhost:8082}
        payment:
          base-url: ${PAYMENT_SERVICE_URL:http://localhost:8084}
```

---

## Test Files

### Framework-Core Tests
1. **`SagaOrchestratorAutoConfigurationTest.java`** (~60 lines)
   - `ApplicationContextRunner` pattern
   - Verify bean creation when `SagaStateStore` is present
   - Verify no bean when `SagaStateStore` is absent

2. **`LoggingSagaOrchestratorListenerTest.java`** (~80 lines)
   - Verify all lifecycle callbacks log at correct level
   - Use Logback `ListAppender` to capture log output

### Order-Service Tests
3. **`OrderFulfillmentSagaFactoryTest.java`** (~100 lines)
   - Verify SagaDefinition has 4 steps with correct names
   - Verify timeouts and saga-level timeout
   - Verify step 3 has no compensation

4. **`SagaServiceClientTest.java`** (~120 lines)
   - Use `MockRestServiceServer` to mock HTTP endpoints
   - Test each of the 4 methods (reserve, release, process, refund)
   - Test error handling (HTTP 500, timeout, connection refused)

5. **`OrchestratedOrderControllerTest.java`** (~80 lines)
   - MockMvc test for `POST /api/orders/orchestrated`
   - Mock the `SagaOrchestrator` and verify start() is called
   - Test success response (201) and failure response (409)

### Inventory/Payment Tests
6. **`OrchestratedInventoryControllerTest.java`** (~80 lines)
   - MockMvc tests for reserve-stock and release-stock endpoints
   - Mock InventoryService, verify orchestrated methods called

7. **`OrchestratedPaymentControllerTest.java`** (~80 lines)
   - MockMvc tests for process-payment and refund-payment endpoints
   - Mock PaymentService, verify orchestrated methods called

---

## Existing Files Reused (Not Modified)

| File | Role |
|------|------|
| `HazelcastSagaOrchestrator.java` | Executes the saga (Day 7) |
| `SagaDefinition.java` / `SagaStep.java` | Saga blueprint with builder DSL |
| `SagaAction.java` / `SagaCompensation.java` | Functional interfaces for step lambdas |
| `SagaContext.java` | Thread-safe context for passing data between steps |
| `SagaStepResult.java` | Step outcome (SUCCESS/FAILURE/TIMEOUT) |
| `SagaOrchestratorResult.java` | Final saga result |
| `SagaStateStore.java` | State persistence interface |
| `SagaCompensationConfig.java` | Step number / event type constants |
| Choreographed saga listeners | Unchanged — natural sagaId guard filters orchestrated events |

---

## Verification

```bash
# Compile all modules
mvn compile

# Run framework-core tests (auto-config + listener)
mvn test -pl framework-core -Dtest="SagaOrchestratorAutoConfigurationTest,LoggingSagaOrchestratorListenerTest"

# Run order-service tests
mvn test -pl order-service -Dtest="OrderFulfillmentSagaFactoryTest,SagaServiceClientTest,OrchestratedOrderControllerTest"

# Run inventory-service tests
mvn test -pl inventory-service -Dtest="OrchestratedInventoryControllerTest"

# Run payment-service tests
mvn test -pl payment-service -Dtest="OrchestratedPaymentControllerTest"

# Full regression (all modules)
mvn test
```

**Success criteria**:
- All new tests pass (~25-30 tests)
- All existing tests still pass (no regressions)
- `POST /api/orders/orchestrated` endpoint is available
- Orchestrated saga steps execute via HTTP without triggering choreographed listeners
- Compensation flows correctly on step failure
