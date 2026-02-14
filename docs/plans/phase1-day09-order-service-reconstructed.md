# Phase 1 Day 9: Order Service (Reconstructed)

> Reconstructed from git history and implementation plan. May not capture all details from the original session.

## Context

Day 9 builds the third and most complex microservice: `order-service`. The order service orchestrates the core eCommerce flow -- it creates orders, confirms them after stock reservation, and handles cancellations. This is the "Part 1" of the order service as described in the implementation plan; Part 2 (Day 10) adds the cross-service materialized views.

The order service runs on port 8083 and exposes REST endpoints for the full order lifecycle. Unlike the account and inventory services which manage a single entity type, the order service has more complex state transitions (PENDING -> CONFIRMED -> CANCELLED) and must track relationships to customers and products via line items.

## What Was Built

### Service Application and Configuration (2 files)
- **OrderServiceApplication** -- Spring Boot entry point with `@SpringBootApplication`
- **OrderServiceConfig** -- Hazelcast and pipeline configuration: creates the embedded `HazelcastInstance`, `EventStore`, `ViewStore`, `EventSourcingPipeline`, and `EventSourcingController` beans for the order domain

### REST Controller (1 file)
- **OrderController** -- REST endpoints:
  - `POST /api/orders` -- create a new order with line items
  - `GET /api/orders/{orderId}` -- retrieve order by ID
  - `GET /api/orders/customer/{customerId}` -- get all orders for a customer
  - `PATCH /api/orders/{orderId}/confirm` -- confirm an order
  - `PATCH /api/orders/{orderId}/cancel` -- cancel an order with a reason

### Service Layer (2 files)
- **OrderOperations** -- interface defining the service contract (create, get, get by customer, confirm, cancel)
- **OrderService** -- implementation using `EventSourcingController` with order lifecycle management:
  - Order creation with line items and total calculation
  - Order confirmation with state validation (only PENDING orders can be confirmed)
  - Order cancellation with reason tracking and state validation
  - Customer order indexing using a separate IMap for efficient customer-to-orders lookup

### View Updater (1 file)
- **OrderViewUpdater** -- implements the framework's `ViewUpdater` interface; applies `OrderCreated`, `OrderConfirmed`, and `OrderCancelled` events to the order materialized view

### Exception Handling (3 files)
- **OrderNotFoundException** -- thrown when an order ID is not found in the view
- **InvalidOrderStateException** -- thrown when an operation is attempted on an order in an invalid state (e.g., confirming an already cancelled order); includes current state and attempted operation
- **ErrorResponse** -- standard error response DTO
- **GlobalExceptionHandler** -- `@RestControllerAdvice` mapping exceptions to HTTP responses, including 409 Conflict for invalid state transitions

### Configuration (1 file)
- **application.yml** -- server port 8083, Spring application name, Hazelcast config, logging and actuator settings

### Tests (3 files)
- **OrderViewUpdaterTest** -- unit tests for view update logic across order creation, confirmation, and cancellation events
- **OrderServiceTest** -- unit tests for service business logic including order creation with line items, state transition validation, customer order indexing, and error cases
- **OrderControllerIntegrationTest** -- integration tests for REST endpoints using `@SpringBootTest`

## Key Decisions

1. **`OrderOperations` instead of `OrderService` interface**: The service interface was named `OrderOperations` (not `OrderService`) because the implementation class is already named `OrderService`. This differs from the account and inventory services which use `CustomerService`/`ProductService` for interfaces and `AccountService`/`InventoryService` for implementations.
2. **Customer order index as a separate IMap**: The service maintains a `Map<String, List<String>>` (customerId -> list of orderIds) for efficient customer-order lookups, rather than scanning all orders. This is a denormalized index separate from the main order view.
3. **State machine validation**: The `OrderService` enforces valid state transitions. Only PENDING orders can be confirmed or cancelled. Attempting to confirm a CANCELLED order (or vice versa) throws `InvalidOrderStateException`, mapped to HTTP 409.
4. **Five REST endpoints vs. four in other services**: The order service has an additional endpoint (`GET /api/orders/customer/{customerId}`) because orders have a many-to-one relationship with customers, requiring a query-by-foreign-key pattern.
5. **PATCH for state transitions**: Confirm and cancel operations use PATCH rather than PUT, following REST conventions for partial state updates.

## Test Results

34 tests, all passing (within the order-service module for this commit). This mirrors the inventory service's test count.

## Files Changed

| File | Change |
|------|--------|
| `order-service/pom.xml` | Created -- Maven module configuration (117 lines) |
| `order-service/src/main/java/.../order/OrderServiceApplication.java` | Created -- Spring Boot application entry point (41 lines) |
| `order-service/src/main/java/.../order/config/OrderServiceConfig.java` | Created -- Hazelcast and pipeline bean configuration (169 lines) |
| `order-service/src/main/java/.../order/controller/OrderController.java` | Created -- REST controller with 5 endpoints (161 lines) |
| `order-service/src/main/java/.../order/domain/OrderViewUpdater.java` | Created -- View updater for order events (158 lines) |
| `order-service/src/main/java/.../order/exception/ErrorResponse.java` | Created -- Error response DTO (58 lines) |
| `order-service/src/main/java/.../order/exception/GlobalExceptionHandler.java` | Created -- REST exception handler (109 lines) |
| `order-service/src/main/java/.../order/exception/InvalidOrderStateException.java` | Created -- State transition exception (58 lines) |
| `order-service/src/main/java/.../order/exception/OrderNotFoundException.java` | Created -- Not found exception (33 lines) |
| `order-service/src/main/java/.../order/service/OrderOperations.java` | Created -- Service interface (67 lines) |
| `order-service/src/main/java/.../order/service/OrderService.java` | Created -- Service implementation with order lifecycle (271 lines) |
| `order-service/src/main/resources/application.yml` | Created -- Service configuration (38 lines) |
| `order-service/src/test/java/.../order/controller/OrderControllerIntegrationTest.java` | Created -- REST integration tests (325 lines) |
| `order-service/src/test/java/.../order/domain/OrderViewUpdaterTest.java` | Created -- View updater unit tests (265 lines) |
| `order-service/src/test/java/.../order/service/OrderServiceTest.java` | Created -- Service unit tests (305 lines) |
| `pom.xml` | Modified -- added order-service to reactor modules |

**Total: 16 files changed, 2,181 insertions**
