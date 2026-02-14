# Phase 2 Day 7: Order Fulfillment Saga Compensation (Reconstructed)

> Reconstructed from git history and implementation plan. May not capture all details from the original session.

## Context

Day 7 implements the compensation path for the Order Fulfillment saga when payment fails, and also delivers the Payment Service REST layer, saga listener, and full service implementation that was originally planned for Day 5. This is the largest commit in the saga series, touching all four services.

## What Was Built

### Payment Service (completing Day 5 scope)
- `PaymentServiceApplication`: Spring Boot application class
- `PaymentService`: Business logic with saga state recording for step 2 success/failure
- `PaymentOperations`: Interface for PaymentService (testability)
- `PaymentController`: REST endpoints for payment processing, lookup, and refund
- `PaymentSagaListener`: Listens for StockReserved events to process payment
- `ErrorResponse`, `GlobalExceptionHandler`, `InvalidPaymentStateException`, `PaymentNotFoundException`: Error handling
- `application.yml` for payment service configuration

### Compensation Path
- `InventorySagaListener`: Added PaymentFailed listener to release reserved stock
- `InventoryService`: Added reservation tracking IMap and `releaseStockForSaga()` method
- `OrderSagaListener`: Added PaymentFailed listener for order cancellation
- `OrderService`: Added `cancelOrderForSaga()` with compensation recording in SagaStateStore
- `OrderOperations` and `ProductService`: Added saga compensation method signatures

### State Tracking
- PaymentService records step 2 success or failure in SagaStateStore
- Step failure triggers COMPENSATING status in SagaStateStore
- Compensation steps tracked through to COMPENSATED final state

## Key Decisions

- **PaymentService records both success and failure**: PaymentService writes to SagaStateStore on step 2 completion (success or failure), making it the authoritative source for payment step status.
- **Parallel compensation**: Both InventorySagaListener and OrderSagaListener listen for PaymentFailed independently, releasing stock and cancelling the order in parallel rather than sequentially.
- **Reservation tracking IMap**: InventoryService maintains a separate IMap tracking stock reservations by orderId/sagaId, enabling targeted release during compensation.
- **Payment REST layer delivered with compensation**: Rather than a separate Day 5, the full payment service including REST endpoints was delivered alongside the compensation logic.

## Test Coverage

12 new compensation tests (unit + integration). All 1,095 tests pass across all modules.

## Files Changed

| File | Change |
|------|--------|
| `inventory-service/src/main/java/.../inventory/saga/InventorySagaListener.java` | Modified -- Added PaymentFailed listener for stock release |
| `inventory-service/src/main/java/.../inventory/service/InventoryService.java` | Modified -- Added reservation tracking IMap, releaseStockForSaga() |
| `inventory-service/src/main/java/.../inventory/service/ProductService.java` | Modified -- Added compensation method signatures |
| `inventory-service/src/test/java/.../inventory/saga/InventorySagaListenerTest.java` | Modified -- Added compensation tests |
| `order-service/src/main/java/.../order/saga/OrderSagaListener.java` | Modified -- Added PaymentFailed listener for order cancellation |
| `order-service/src/main/java/.../order/service/OrderOperations.java` | Modified -- Added cancelOrderForSaga() signature |
| `order-service/src/main/java/.../order/service/OrderService.java` | Modified -- Added cancelOrderForSaga() implementation |
| `order-service/src/test/java/.../order/saga/OrderFulfillmentSagaCompensationTest.java` | Created -- Compensation integration test |
| `order-service/src/test/java/.../order/saga/OrderSagaListenerTest.java` | Modified -- Added compensation tests |
| `payment-service/pom.xml` | Modified -- Added SagaStateStore dependency |
| `payment-service/src/main/java/.../payment/PaymentServiceApplication.java` | Created -- Spring Boot app class |
| `payment-service/src/main/java/.../payment/controller/PaymentController.java` | Created -- REST endpoints |
| `payment-service/src/main/java/.../payment/exception/ErrorResponse.java` | Created -- Error response DTO |
| `payment-service/src/main/java/.../payment/exception/GlobalExceptionHandler.java` | Created -- Exception handler |
| `payment-service/src/main/java/.../payment/exception/InvalidPaymentStateException.java` | Created -- Domain exception |
| `payment-service/src/main/java/.../payment/exception/PaymentNotFoundException.java` | Created -- Domain exception |
| `payment-service/src/main/java/.../payment/saga/PaymentSagaListener.java` | Created -- Listens for StockReserved, processes payment |
| `payment-service/src/main/java/.../payment/service/PaymentOperations.java` | Created -- Service interface |
| `payment-service/src/main/java/.../payment/service/PaymentService.java` | Created -- Full payment business logic |
| `payment-service/src/main/resources/application.yml` | Created -- Service configuration |
| `payment-service/src/test/java/.../payment/controller/PaymentControllerIntegrationTest.java` | Created -- REST endpoint tests |
| `payment-service/src/test/java/.../payment/service/PaymentServiceTest.java` | Created -- Service unit tests |

## Commit

- **Hash**: `6922e60`
- **Date**: 2026-01-29
- **Stats**: 22 files changed, 2677 insertions(+), 1 deletion(-)
