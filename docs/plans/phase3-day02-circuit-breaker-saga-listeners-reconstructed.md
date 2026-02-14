# Phase 3 Day 2: Circuit Breaker Protection for Saga Listeners (Reconstructed)

> Reconstructed from git history and implementation plan. May not capture all details from the original session.

## Context

Day 1 established the Resilience4j foundation in `framework-core` with `ResilientServiceInvoker`, configuration properties, and auto-configuration. Day 2 applied that foundation to all three saga listeners (Inventory, Payment, Order), wrapping their service calls with circuit breakers. This was the first concrete resilience improvement to the saga flow.

A key challenge was Java 25 + Mockito compatibility: `ResilientServiceInvoker` is a concrete class that cannot be mocked by Mockito's inline mock maker on Java 25. The solution was to extract a `ResilientOperations` interface that `ResilientServiceInvoker` implements, allowing tests to mock the interface.

## What Was Built

### ResilientOperations Interface
- **New interface** in `framework-core` providing the mockable contract for `ResilientServiceInvoker`
- Methods: `execute(String name, Supplier<T>)`, `executeRunnable(String name, Runnable)`, `executeAsync(String name, Supplier<CompletableFuture<T>>)`, `getCircuitBreaker(String name)`, `getRetry(String name)`, `isEnabled()`
- `ResilientServiceInvoker` updated to implement this interface (1-line change)

### Saga Listener Circuit Breaker Integration
Each of the three saga listeners was modified to accept an optional `ResilientOperations` dependency (injected via `@Autowired(required = false)`) and use an `executeWithResilience()` helper method for clean, optional circuit breaker decoration:

- **InventorySagaListener**: Circuit breakers on stock reservation (`inventory-stock-reservation`) and stock release (`inventory-stock-release`). Fallback logs a warning when the circuit is open.
- **PaymentSagaListener**: Circuit breakers on payment processing (`payment-processing`) and payment refund (`payment-refund`). Same fallback pattern.
- **OrderSagaListener**: Circuit breakers on order confirmation (`order-confirmation`) and order cancellation (`order-cancellation`). Same fallback pattern.

### Per-Instance Configuration
Each service's `application.yml` was updated with circuit breaker configuration under `framework.resilience.instances`, allowing per-circuit-breaker tuning of failure rate threshold, wait duration, sliding window size, etc.

### Tests
- **InventorySagaListenerTest**: Tests for stock reservation and release with circuit breaker enabled, circuit breaker disabled/absent, and fallback behavior
- **PaymentSagaListenerTest**: Tests for payment processing and refund with circuit breaker enabled/disabled
- **OrderSagaListenerTest**: Tests for order confirmation and cancellation with circuit breaker enabled/disabled
- 12 new circuit breaker tests across 3 services (30 total saga listener tests passing)

## Key Decisions

- **Interface extraction for mockability**: `ResilientOperations` interface extracted from `ResilientServiceInvoker` specifically for Java 25 Mockito compatibility. This follows the same pattern used for `ServiceClientOperations` (MCP server).
- **Optional injection with `@Autowired(required = false)`**: Saga listeners work with or without resilience — if `ResilientOperations` is not in the context (e.g., resilience disabled), the listener operates without circuit breaker decoration.
- **Six named circuit breakers**: Each operation gets its own circuit breaker instance (not shared per service) for independent failure isolation. A stock reservation failure should not open the circuit for stock release.
- **Helper method pattern**: Each listener uses a private `executeWithResilience(String name, Runnable operation)` helper that checks whether `ResilientOperations` is available and decorates accordingly. This keeps the business logic clean.

## Files Changed

| File | Change |
|------|--------|
| `framework-core/src/main/java/com/theyawns/framework/resilience/ResilientOperations.java` | Created — mockable interface for ResilientServiceInvoker (65 lines) |
| `framework-core/src/main/java/com/theyawns/framework/resilience/ResilientServiceInvoker.java` | Modified — now implements `ResilientOperations` interface (1-line change) |
| `inventory-service/src/main/java/com/theyawns/ecommerce/inventory/saga/InventorySagaListener.java` | Modified — added circuit breaker wrapping on stock reserve/release (+130/-some lines) |
| `inventory-service/src/main/resources/application.yml` | Modified — added per-instance circuit breaker configuration (+23 lines) |
| `inventory-service/src/test/java/com/theyawns/ecommerce/inventory/saga/InventorySagaListenerTest.java` | Created — circuit breaker tests for inventory saga listener (240 lines) |
| `order-service/src/main/java/com/theyawns/ecommerce/order/saga/OrderSagaListener.java` | Modified — added circuit breaker wrapping on order confirm/cancel (+96/-some lines) |
| `order-service/src/main/resources/application.yml` | Modified — added per-instance circuit breaker configuration (+23 lines) |
| `order-service/src/test/java/com/theyawns/ecommerce/order/saga/OrderSagaListenerTest.java` | Created — circuit breaker tests for order saga listener (211 lines) |
| `payment-service/src/main/java/com/theyawns/ecommerce/payment/saga/PaymentSagaListener.java` | Modified — added circuit breaker wrapping on payment process/refund (+90/-some lines) |
| `payment-service/src/main/resources/application.yml` | Modified — added per-instance circuit breaker configuration (+23 lines) |
| `payment-service/src/test/java/com/theyawns/ecommerce/payment/saga/PaymentSagaListenerTest.java` | Created — circuit breaker tests for payment saga listener (190 lines) |
