# Phase 1 Day 7: Account Service (Reconstructed)

> Reconstructed from git history and implementation plan. May not capture all details from the original session.

## Context

With the ecommerce-common module complete (Day 6), Day 7 builds the first microservice: `account-service`. This service manages customer accounts using the event sourcing framework. It serves as the template that the inventory and order services will follow, so it establishes the patterns for service configuration, REST controllers, view updaters, exception handling, and testing.

The account service runs on port 8081 and exposes REST endpoints for customer CRUD operations. All state changes go through the event sourcing pipeline -- the service creates events, submits them via `EventSourcingController.handleEvent()`, and reads current state from the materialized view.

## What Was Built

### Service Application and Configuration (2 files)
- **AccountServiceApplication** -- Spring Boot entry point with `@SpringBootApplication`
- **AccountServiceConfig** -- Hazelcast and pipeline configuration: creates the embedded `HazelcastInstance`, `EventStore`, `ViewStore`, `EventSourcingPipeline`, and `EventSourcingController` beans; configures the event journal on the pending events map

### REST Controller (1 file)
- **AccountController** -- REST endpoints:
  - `POST /api/customers` -- create a new customer
  - `GET /api/customers/{customerId}` -- retrieve customer by ID
  - `PUT /api/customers/{customerId}` -- update customer profile
  - `PATCH /api/customers/{customerId}/status` -- change customer status (activate, suspend, close)

### Service Layer (2 files)
- **CustomerService** -- interface defining the service contract (create, get, update, change status)
- **AccountService** -- implementation using `EventSourcingController` to handle events and read from the materialized view

### View Updater (1 file)
- **CustomerViewUpdater** -- implements the framework's `ViewUpdater` interface; applies `CustomerCreated`, `CustomerUpdated`, and `CustomerStatusChanged` events to the customer materialized view

### Exception Handling (3 files)
- **CustomerNotFoundException** -- thrown when a customer ID is not found in the view
- **ErrorResponse** -- standard error response DTO with message and error code
- **GlobalExceptionHandler** -- `@RestControllerAdvice` mapping exceptions to HTTP responses (404 for not found, 400 for validation, 500 for unexpected)

### Configuration Files (2 files)
- **application.yml** (main) -- server port 8081, Spring application name, Hazelcast config, logging levels, actuator endpoints
- **application.yml** (test) -- test-specific configuration with reduced logging and in-memory setup

### Framework Fixes (4 files modified)
- **HazelcastEventBus** -- made `Serializable` for Jet pipeline compatibility
- **HazelcastEventStore** -- made `Serializable` for Jet pipeline compatibility
- **HazelcastViewStore** -- made `Serializable` for Jet pipeline compatibility
- **ViewUpdater** -- made `Serializable` for Jet pipeline compatibility

### Tests (3 files)
- **CustomerViewUpdaterTest** -- 8 tests verifying that each event type correctly updates the customer GenericRecord in the materialized view
- **AccountServiceTest** -- 9 tests for service business logic including customer creation, retrieval, updates, status changes, and error cases
- **AccountControllerIntegrationTest** -- 5 integration tests for REST endpoints using `@SpringBootTest` with `TestRestTemplate`

## Key Decisions

1. **Service interface + implementation pattern**: `CustomerService` interface with `AccountService` implementation, allowing future alternative implementations and cleaner testing with mocks.
2. **Serializable framework classes**: Discovered during development that `HazelcastEventStore`, `HazelcastViewStore`, `ViewUpdater`, and `HazelcastEventBus` needed to implement `Serializable` for Jet pipeline compatibility. This was a framework-level fix driven by the first real service integration.
3. **`GlobalExceptionHandler` per service**: Each service has its own exception handler rather than sharing one, because each service has domain-specific exceptions.
4. **Async responses with CompletableFuture**: Controller methods return `CompletableFuture<ResponseEntity<...>>` for POST/PUT/PATCH operations, keeping the event sourcing pipeline non-blocking.
5. **Separate test application.yml**: Test configuration overrides the main config to avoid port conflicts and reduce log verbosity during test runs.

## Test Results

22 tests passing:
- CustomerViewUpdaterTest: 8 tests
- AccountServiceTest: 9 tests
- AccountControllerIntegrationTest: 5 tests

## Files Changed

| File | Change |
|------|--------|
| `account-service/pom.xml` | Created -- Maven module with Spring Boot web, Hazelcast, and test dependencies (117 lines) |
| `account-service/src/main/java/.../account/AccountServiceApplication.java` | Created -- Spring Boot application entry point (40 lines) |
| `account-service/src/main/java/.../account/config/AccountServiceConfig.java` | Created -- Hazelcast and pipeline bean configuration (165 lines) |
| `account-service/src/main/java/.../account/controller/AccountController.java` | Created -- REST controller with 4 endpoints (139 lines) |
| `account-service/src/main/java/.../account/domain/CustomerViewUpdater.java` | Created -- View updater for customer events (142 lines) |
| `account-service/src/main/java/.../account/exception/CustomerNotFoundException.java` | Created -- Not found exception (33 lines) |
| `account-service/src/main/java/.../account/exception/ErrorResponse.java` | Created -- Error response DTO (58 lines) |
| `account-service/src/main/java/.../account/exception/GlobalExceptionHandler.java` | Created -- REST exception handler (88 lines) |
| `account-service/src/main/java/.../account/service/AccountService.java` | Created -- Service implementation (206 lines) |
| `account-service/src/main/java/.../account/service/CustomerService.java` | Created -- Service interface (59 lines) |
| `account-service/src/main/resources/application.yml` | Created -- Service configuration (38 lines) |
| `account-service/src/test/java/.../account/controller/AccountControllerIntegrationTest.java` | Created -- REST integration tests (190 lines) |
| `account-service/src/test/java/.../account/domain/CustomerViewUpdaterTest.java` | Created -- View updater unit tests (246 lines) |
| `account-service/src/test/java/.../account/service/AccountServiceTest.java` | Created -- Service unit tests (273 lines) |
| `account-service/src/test/resources/application.yml` | Created -- Test configuration (26 lines) |
| `framework-core/src/main/java/.../framework/pipeline/HazelcastEventBus.java` | Modified -- added Serializable (11 lines changed) |
| `framework-core/src/main/java/.../framework/store/HazelcastEventStore.java` | Modified -- added Serializable (7 lines changed) |
| `framework-core/src/main/java/.../framework/view/HazelcastViewStore.java` | Modified -- added Serializable (7 lines changed) |
| `framework-core/src/main/java/.../framework/view/ViewUpdater.java` | Modified -- added Serializable (5 lines changed) |
| `pom.xml` | Modified -- added account-service to reactor modules |

**Total: 20 files changed, 1,843 insertions, 13 deletions**
