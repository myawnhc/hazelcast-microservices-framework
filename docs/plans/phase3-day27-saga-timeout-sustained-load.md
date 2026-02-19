# Phase 3, Day 27: Fix Saga Timeouts for Sustained Load (8+ Hours)

## Context

At 50 TPS with a single product, stock exhausts after ~195 orders. Then:
1. `InsufficientStockException` trips the circuit breaker OPEN
2. All subsequent saga events are rejected by CB and sent to DLQ
3. Sagas never progress past step 1 and time out (64K timeouts out of 53K started)

The system needs to run reliably for 8+ hours (trade show booth) or even days.

## Fixes Implemented

### Fix A: Circuit Breaker Exception Classification
- Added `recordExceptionPredicate(e -> !(e instanceof NonRetryableException))` to `CircuitBreakerConfig`
- `InsufficientStockException` (and all `NonRetryableException` subtypes) no longer count toward CB failure rate
- Updated `NonRetryableException` JavaDoc to document dual behavior (no retry + no CB recording)
- Added 2 new tests in `ResilienceAutoConfigurationTest`

### Fix B: Saga Compensation on Out-of-Stock
- New event: `StockReservationFailedEvent` in `ecommerce-common`
- `InventorySagaListener.OrderCreatedListener`: catches `InsufficientStockException`, publishes `StockReservationFailed` to shared ITopic (not DLQ)
- `OrderSagaListener.StockReservationFailedListener`: listens on `StockReservationFailed` ITopic, calls `cancelOrderForSaga()`, increments `saga.compensated` counter
- Added `STOCK_RESERVATION_FAILED` constant to `SagaCompensationConfig`

### Fix C: Automatic Stock Replenishment
- New event: `StockReplenishedEvent` in `ecommerce-common`
- New config: `StockReplenishmentProperties` (`framework.inventory.replenishment.*`)
- New component: `StockReplenishmentMonitor` — `@Scheduled` background monitor
- Added `replenishStock()` to `ProductService` interface and `InventoryService`
- Updated `ProductViewUpdater` with `StockReplenished` case
- Added REST endpoint: `POST /api/products/{id}/stock/replenish`
- Added `@EnableScheduling` to `InventoryServiceApplication`
- Configured defaults in `application.yml`

### Fix D: Multi-Product Load Test
- Updated `sustained-load.js` with `SharedArray` for multi-product/customer selection from `product-ids.json`/`customer-ids.json`
- Falls back to single `PRODUCT_ID`/`CUSTOMER_ID` env vars for backwards compatibility
- Updated all 100 products in `products.json` to `quantityOnHand: 100000`

## Files Changed

| File | Change |
|------|--------|
| `ResilienceAutoConfiguration.java` | `recordExceptionPredicate` excluding `NonRetryableException` |
| `NonRetryableException.java` | Updated JavaDoc |
| `ResilienceAutoConfigurationTest.java` | 2 new CB exception classification tests |
| `SagaCompensationConfig.java` | `STOCK_RESERVATION_FAILED` constant |
| `InventorySagaListener.java` | Catch `InsufficientStockException` -> compensation event |
| `OrderSagaListener.java` | `StockReservationFailedListener`, `saga.compensated` counter |
| `ProductViewUpdater.java` | `StockReplenished` case |
| `InventoryService.java` | `replenishStock()` method |
| `ProductService.java` | `replenishStock()` interface method |
| `InventoryController.java` | `POST /stock/replenish` endpoint + request record |
| `InventoryServiceApplication.java` | `@EnableScheduling` |
| `application.yml` (inventory) | Replenishment config |
| `sustained-load.js` | Multi-product via SharedArray |
| `products.json` | `quantityOnHand: 100000` for all products |

## New Files

| File | Purpose |
|------|---------|
| `StockReservationFailedEvent.java` | Compensation event for failed reservation |
| `StockReplenishedEvent.java` | Event for stock replenishment |
| `StockReplenishmentMonitor.java` | Scheduled monitor that triggers reorders |
| `StockReplenishmentProperties.java` | Configuration properties |

## Verification

1. `mvn clean test` — all existing + new tests pass
2. Docker Compose sustained test at 50 TPS: zero TIMED_OUT sagas, stock replenishment visible
3. Circuit breaker stays CLOSED throughout (InsufficientStockException excluded)
4. k6 output shows orders distributed across multiple products
