# Phase 1 Day 8: Inventory Service (Reconstructed)

> Reconstructed from git history and implementation plan. May not capture all details from the original session.

## Context

With the account service established as a template (Day 7), Day 8 builds the second microservice: `inventory-service`. This service manages the product catalog and stock levels. It follows the same structural patterns as the account service but introduces inventory-specific concerns -- notably stock reservation and release logic, which are critical for the order fulfillment saga that will be built in later phases.

The inventory service runs on port 8082 and exposes REST endpoints for product management and stock operations. Stock reservation is a key operation in the eCommerce flow: when an order is placed, inventory must be reserved to prevent overselling, and released if the order is cancelled.

## What Was Built

### Service Application and Configuration (2 files)
- **InventoryServiceApplication** -- Spring Boot entry point with `@SpringBootApplication`
- **InventoryServiceConfig** -- Hazelcast and pipeline configuration: creates the embedded `HazelcastInstance`, `EventStore`, `ViewStore`, `EventSourcingPipeline`, and `EventSourcingController` beans for the product domain

### REST Controller (1 file)
- **InventoryController** -- REST endpoints:
  - `POST /api/products` -- create a new product
  - `GET /api/products/{productId}` -- retrieve product by ID
  - `POST /api/products/{productId}/stock/reserve` -- reserve stock for an order
  - `POST /api/products/{productId}/stock/release` -- release previously reserved stock

### Service Layer (2 files)
- **ProductService** -- interface defining the service contract (create, get, reserve stock, release stock)
- **InventoryService** -- implementation using `EventSourcingController` with stock reservation/release business logic, including validation for sufficient stock

### View Updater (1 file)
- **ProductViewUpdater** -- implements the framework's `ViewUpdater` interface; applies `ProductCreated`, `StockReserved`, and `StockReleased` events to the product materialized view, updating stock quantities accordingly

### Exception Handling (3 files)
- **ProductNotFoundException** -- thrown when a product ID is not found in the view
- **InsufficientStockException** -- thrown when a stock reservation request exceeds available inventory; includes productId, requested quantity, and available quantity for diagnostic clarity
- **ErrorResponse** -- standard error response DTO (same pattern as account service)
- **GlobalExceptionHandler** -- `@RestControllerAdvice` mapping exceptions to HTTP responses, including 409 Conflict for insufficient stock

### Configuration (1 file)
- **application.yml** -- server port 8082, Spring application name, Hazelcast config, logging and actuator settings

### Tests (3 files)
- **ProductViewUpdaterTest** -- unit tests for view update logic across all product event types, including stock arithmetic
- **InventoryServiceTest** -- unit tests for service business logic including product creation, stock reservation with sufficient/insufficient stock, and stock release
- **InventoryControllerIntegrationTest** -- integration tests for REST endpoints using `@SpringBootTest`

## Key Decisions

1. **InsufficientStockException with diagnostic fields**: Unlike a simple not-found exception, the `InsufficientStockException` carries `requestedQuantity` and `availableQuantity` fields, enabling the controller to return a detailed 409 Conflict response that helps API consumers understand why a reservation failed.
2. **Stock reservation as explicit events**: Rather than simple CRUD updates, stock changes produce `StockReservedEvent` and `StockReleasedEvent` events that carry orderId context. This enables the saga pattern -- the order service can listen for these events to know whether stock was successfully reserved.
3. **Same structural pattern as account service**: The inventory service intentionally mirrors the account service's structure (application, config, controller, service interface + impl, view updater, exception handler) to establish a consistent pattern across all services.
4. **409 Conflict for business rule violations**: The `GlobalExceptionHandler` maps `InsufficientStockException` to HTTP 409 Conflict rather than 400 Bad Request, distinguishing between "your request is malformed" and "your request conflicts with current state."

## Test Results

34 tests, all passing (cumulative across inventory-service module). This includes the three test files created in this commit.

## Files Changed

| File | Change |
|------|--------|
| `inventory-service/pom.xml` | Created -- Maven module configuration (117 lines) |
| `inventory-service/src/main/java/.../inventory/InventoryServiceApplication.java` | Created -- Spring Boot application entry point (41 lines) |
| `inventory-service/src/main/java/.../inventory/config/InventoryServiceConfig.java` | Created -- Hazelcast and pipeline bean configuration (165 lines) |
| `inventory-service/src/main/java/.../inventory/controller/InventoryController.java` | Created -- REST controller with 4 endpoints (167 lines) |
| `inventory-service/src/main/java/.../inventory/domain/ProductViewUpdater.java` | Created -- View updater for product events (155 lines) |
| `inventory-service/src/main/java/.../inventory/exception/ErrorResponse.java` | Created -- Error response DTO (58 lines) |
| `inventory-service/src/main/java/.../inventory/exception/GlobalExceptionHandler.java` | Created -- REST exception handler (109 lines) |
| `inventory-service/src/main/java/.../inventory/exception/InsufficientStockException.java` | Created -- Business rule exception (58 lines) |
| `inventory-service/src/main/java/.../inventory/exception/ProductNotFoundException.java` | Created -- Not found exception (33 lines) |
| `inventory-service/src/main/java/.../inventory/service/InventoryService.java` | Created -- Service implementation with stock logic (193 lines) |
| `inventory-service/src/main/java/.../inventory/service/ProductService.java` | Created -- Service interface (61 lines) |
| `inventory-service/src/main/resources/application.yml` | Created -- Service configuration (38 lines) |
| `inventory-service/src/test/java/.../inventory/controller/InventoryControllerIntegrationTest.java` | Created -- REST integration tests (306 lines) |
| `inventory-service/src/test/java/.../inventory/domain/ProductViewUpdaterTest.java` | Created -- View updater unit tests (269 lines) |
| `inventory-service/src/test/java/.../inventory/service/InventoryServiceTest.java` | Created -- Service unit tests (379 lines) |
| `pom.xml` | Modified -- added inventory-service to reactor modules |

**Total: 16 files changed, 2,155 insertions**
