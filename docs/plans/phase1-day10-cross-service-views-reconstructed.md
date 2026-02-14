# Phase 1 Day 10: Cross-Service Materialized Views (Reconstructed)

> Reconstructed from git history and implementation plan. May not capture all details from the original session.

## Context

Day 9 built the order service with basic order CRUD and its own materialized view. Day 10 completes Week 2 by adding cross-service materialized views -- the defining feature of the CQRS pattern in this architecture. Instead of making synchronous REST calls to the account and inventory services to enrich order data, the order service maintains local denormalized views that are updated from events published by other services.

This is the "Part 2" of the order service described in the implementation plan (Day 10: "Order Service (Part 2) - Materialized Views"). Four new view updaters are added, each listening to events from different services and maintaining a local cache or aggregated view within the order service.

## What Was Built

### Cross-Service View Updaters (4 files)
- **CustomerCacheViewUpdater** -- maintains a local cache of customer data within the order service. Listens to `CustomerCreated`, `CustomerUpdated`, and `CustomerStatusChanged` events from the account service. Stores customer names, emails, and statuses so the enriched order view can include customer details without calling the account service.
- **ProductAvailabilityViewUpdater** -- maintains a local cache of product availability data within the order service. Listens to `ProductCreated`, `StockReserved`, and `StockReleased` events from the inventory service. Stores product names, prices, and available stock quantities.
- **EnrichedOrderViewUpdater** -- creates a fully denormalized order view that combines order data with customer and product information from the local caches. When an order is created, it looks up the customer name/email and product names/prices from the cache views and writes a single enriched record. This is the primary query view for the order service -- it returns everything a client needs in one read.
- **CustomerOrderSummaryViewUpdater** -- maintains aggregated order statistics per customer: total order count, total spend, last order date. Updated when orders are created, confirmed, or cancelled. Enables queries like "show me this customer's order history summary" without scanning all orders.

### Configuration Updates (1 file modified)
- **OrderServiceConfig** -- significantly expanded (135 lines added) to wire up the four new view updaters, configure their backing IMaps, and register them with the event sourcing pipeline. Defines map names for the customer cache, product availability cache, enriched order view, and customer order summary view.

### Tests (5 files)
- **CustomerCacheViewUpdaterTest** -- unit tests for customer cache updates from account service events
- **ProductAvailabilityViewUpdaterTest** -- unit tests for product availability cache updates from inventory service events
- **EnrichedOrderViewUpdaterTest** -- unit tests for denormalized order view construction and updates
- **CustomerOrderSummaryViewUpdaterTest** -- unit tests for order statistics aggregation per customer
- **OrderFlowIntegrationTest** -- end-to-end integration test simulating the complete order flow: create customer, create product, place order, verify enriched view contains denormalized data from all three services

## Key Decisions

1. **Local caches over service calls**: The fundamental CQRS decision -- the order service maintains its own copies of customer and product data rather than making REST calls. This eliminates runtime coupling between services and enables the order service to answer queries even when other services are down.
2. **Thread-safe design avoiding entry processor deadlocks**: The commit message explicitly calls out "thread-safe design avoiding Hazelcast entry processor deadlocks." This suggests that the view updaters were carefully designed to avoid nested entry processor calls or lock contention patterns that could cause deadlocks in Hazelcast.
3. **Four separate views with distinct purposes**:
   - Customer cache: raw data replication for lookup
   - Product availability cache: raw data replication for lookup
   - Enriched order view: fully denormalized for client queries
   - Customer order summary: pre-computed aggregation for analytics
4. **Enriched view as the primary query target**: The enriched order view is the CQRS "read model" -- it contains everything a client needs (order details + customer name/email + product names/prices) in a single Hazelcast IMap entry. This eliminates the need for joins at query time.
5. **End-to-end integration test**: The `OrderFlowIntegrationTest` validates the complete multi-service event flow: customer creation -> product creation -> order placement -> enriched view verification. This test exercises the full event sourcing pipeline and all view updaters together.

## Test Results

45 new unit tests for view updaters plus 1 end-to-end integration test. All 79 order-service tests pass (including the 34 from Day 9 plus the 45 new tests from Day 10).

## Files Changed

| File | Change |
|------|--------|
| `.gitignore` | Modified -- updated ignore patterns |
| `order-service/src/main/java/.../order/config/OrderServiceConfig.java` | Modified -- added bean definitions for 4 view updaters and their backing IMaps (135 lines added) |
| `order-service/src/main/java/.../order/domain/CustomerCacheViewUpdater.java` | Created -- local customer data cache (154 lines) |
| `order-service/src/main/java/.../order/domain/CustomerOrderSummaryViewUpdater.java` | Created -- aggregated order stats per customer (217 lines) |
| `order-service/src/main/java/.../order/domain/EnrichedOrderViewUpdater.java` | Created -- denormalized order view with customer/product data (331 lines) |
| `order-service/src/main/java/.../order/domain/ProductAvailabilityViewUpdater.java` | Created -- local product availability cache (179 lines) |
| `order-service/src/test/java/.../order/domain/CustomerCacheViewUpdaterTest.java` | Created -- customer cache unit tests (266 lines) |
| `order-service/src/test/java/.../order/domain/CustomerOrderSummaryViewUpdaterTest.java` | Created -- order summary unit tests (309 lines) |
| `order-service/src/test/java/.../order/domain/EnrichedOrderViewUpdaterTest.java` | Created -- enriched view unit tests (336 lines) |
| `order-service/src/test/java/.../order/domain/OrderFlowIntegrationTest.java` | Created -- end-to-end order flow integration test (422 lines) |
| `order-service/src/test/java/.../order/domain/ProductAvailabilityViewUpdaterTest.java` | Created -- product availability unit tests (283 lines) |

**Total: 11 files changed, 2,634 insertions, 3 deletions**
