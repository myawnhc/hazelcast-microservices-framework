# Phase 1 Day 6: eCommerce Common Module (Reconstructed)

> Reconstructed from git history and implementation plan. May not capture all details from the original session.

## Context

Phase 1 Week 1 (Days 1-5) completed the framework-core module with event sourcing abstractions, the event store, materialized views, the Jet pipeline, and the controller. Day 6 begins Week 2, shifting focus from the domain-agnostic framework to the eCommerce domain. The goal was to create the `ecommerce-common` module containing all shared domain objects, DTOs, and event classes that the three microservices (account, inventory, order) would depend on.

This module establishes the event vocabulary for the entire eCommerce demo system. Every event class must extend the framework's `DomainEvent` base class, implement `toGenericRecord()` for Hazelcast Compact Serialization, and implement `apply()` to mutate domain object state.

## What Was Built

### Domain Objects (4 files)
- **Customer** -- domain object with fields for customerId, email, name, address, status; includes `fromGenericRecord()` and `toDTO()` conversions
- **Product** -- domain object with fields for productId, name, description, price, stock quantities; includes stock reservation/release logic
- **Order** -- domain object with fields for orderId, customerId, line items, total amount, order status; includes state transition logic
- **OrderLineItem** -- value object representing a single line item within an order (productId, quantity, unit price)

### DTOs (4 files)
- **CustomerDTO** -- data transfer object for REST API customer payloads
- **ProductDTO** -- data transfer object for REST API product payloads
- **OrderDTO** -- data transfer object for REST API order payloads
- **OrderLineItemDTO** -- data transfer object for order line items in REST payloads

### Events (9 files)
- **CustomerCreatedEvent** -- captures new customer registration with email, name, address
- **CustomerUpdatedEvent** -- captures profile changes (email, name, address updates)
- **CustomerStatusChangedEvent** -- captures status transitions (ACTIVE, SUSPENDED, CLOSED)
- **ProductCreatedEvent** -- captures new product catalog entry with name, description, price, initial stock
- **StockReservedEvent** -- captures inventory reservation for an order (productId, quantity, orderId)
- **StockReleasedEvent** -- captures inventory release when an order is cancelled (productId, quantity, orderId)
- **OrderCreatedEvent** -- captures new order placement with line items, customerId, total amount
- **OrderConfirmedEvent** -- captures order confirmation after successful stock reservation
- **OrderCancelledEvent** -- captures order cancellation with a reason

### Tests (3 files)
- **CustomerCreatedEventTest** -- tests for event construction, GenericRecord serialization, and apply logic
- **ProductCreatedEventTest** -- tests for product event serialization and application
- **StockReservedEventTest** -- tests for stock reservation event serialization and application

### Build Configuration
- **ecommerce-common/pom.xml** -- Maven module depending on framework-core, with Spring Boot and Hazelcast dependencies
- **pom.xml** (root) -- updated to include `ecommerce-common` in the reactor module list

## Key Decisions

1. **Shared module pattern**: All domain objects, DTOs, and events live in `ecommerce-common` so that all three services depend on one shared artifact rather than duplicating event definitions.
2. **Domain objects in common, not in services**: `Customer`, `Product`, and `Order` are defined here rather than in their respective services, enabling cross-service materialized views (e.g., the order service needs to understand `Customer` records for enriched views).
3. **GenericRecord serialization for all events**: Every event implements `toGenericRecord()` for Hazelcast Compact Serialization storage, and `apply(GenericRecord)` for event replay against materialized views.
4. **Separate DTO layer**: DTOs are distinct from domain objects, providing a clean API contract that decouples REST representations from internal domain state.
5. **Nine events cover the full domain vocabulary**: The event set was designed to support the complete eCommerce demo flow -- customer registration, product cataloging, stock management, order lifecycle, and cancellation with compensation.

## Test Results

3 test files created with tests covering event construction, GenericRecord serialization round-trips, and event application to domain objects. Exact test count not specified in the commit message.

## Files Changed

| File | Change |
|------|--------|
| `.gitignore` | Modified -- added entries |
| `ecommerce-common/pom.xml` | Created -- Maven module configuration |
| `ecommerce-common/src/main/java/.../common/domain/Customer.java` | Created -- Customer domain object (228 lines) |
| `ecommerce-common/src/main/java/.../common/domain/Order.java` | Created -- Order domain object (335 lines) |
| `ecommerce-common/src/main/java/.../common/domain/OrderLineItem.java` | Created -- Order line item value object (183 lines) |
| `ecommerce-common/src/main/java/.../common/domain/Product.java` | Created -- Product domain object (295 lines) |
| `ecommerce-common/src/main/java/.../common/dto/CustomerDTO.java` | Created -- Customer DTO (149 lines) |
| `ecommerce-common/src/main/java/.../common/dto/OrderDTO.java` | Created -- Order DTO (219 lines) |
| `ecommerce-common/src/main/java/.../common/dto/OrderLineItemDTO.java` | Created -- Order line item DTO (163 lines) |
| `ecommerce-common/src/main/java/.../common/dto/ProductDTO.java` | Created -- Product DTO (205 lines) |
| `ecommerce-common/src/main/java/.../common/events/CustomerCreatedEvent.java` | Created -- Customer creation event (173 lines) |
| `ecommerce-common/src/main/java/.../common/events/CustomerStatusChangedEvent.java` | Created -- Customer status change event (151 lines) |
| `ecommerce-common/src/main/java/.../common/events/CustomerUpdatedEvent.java` | Created -- Customer update event (166 lines) |
| `ecommerce-common/src/main/java/.../common/events/OrderCancelledEvent.java` | Created -- Order cancellation event (169 lines) |
| `ecommerce-common/src/main/java/.../common/events/OrderConfirmedEvent.java` | Created -- Order confirmation event (143 lines) |
| `ecommerce-common/src/main/java/.../common/events/OrderCreatedEvent.java` | Created -- Order creation event (250 lines) |
| `ecommerce-common/src/main/java/.../common/events/ProductCreatedEvent.java` | Created -- Product creation event (206 lines) |
| `ecommerce-common/src/main/java/.../common/events/StockReleasedEvent.java` | Created -- Stock release event (177 lines) |
| `ecommerce-common/src/main/java/.../common/events/StockReservedEvent.java` | Created -- Stock reservation event (163 lines) |
| `ecommerce-common/src/test/java/.../common/events/CustomerCreatedEventTest.java` | Created -- Customer event tests (171 lines) |
| `ecommerce-common/src/test/java/.../common/events/ProductCreatedEventTest.java` | Created -- Product event tests (125 lines) |
| `ecommerce-common/src/test/java/.../common/events/StockReservedEventTest.java` | Created -- Stock reservation event tests (130 lines) |
| `pom.xml` | Modified -- added ecommerce-common to reactor modules |

**Total: 23 files changed, 3,875 insertions**
