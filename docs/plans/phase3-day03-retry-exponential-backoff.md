# Phase 3 Day 3: Retry with Exponential Backoff

## Context

Days 1-2 established the resilience foundation: `ResilientServiceInvoker` wraps saga operations with both circuit breaker AND retry (with exponential backoff), and all three saga listeners use it via `executeWithResilience()`. However, **all exceptions currently trigger retry**, which is wrong for business failures like "insufficient stock" or "payment declined" — these will never succeed on retry and waste time/resources. Day 3 adds non-retryable exception classification, retry observability logging, and a new `PaymentDeclinedException`.

## Implementation

### 1. Create `NonRetryableException` marker interface (framework-core)

**File**: `framework-core/src/main/java/com/theyawns/framework/resilience/NonRetryableException.java`

```java
public interface NonRetryableException {
    // Marker interface — business exceptions implement this to skip retry
}
```

Why a marker interface (not a base class): the service exceptions already extend `RuntimeException` — a marker lets them opt in without changing their inheritance hierarchy.

### 2. Create `RetryEventListener` (framework-core)

**File**: `framework-core/src/main/java/com/theyawns/framework/resilience/RetryEventListener.java`

Registers with `RetryRegistry.getEventPublisher().onEntryAdded()` to auto-hook every retry instance. Listens to four event types:

| Event | Log Level | Message |
|-------|-----------|---------|
| `onRetry` | WARN | Retry attempt #{n} for '{name}': {lastError} |
| `onSuccess` | INFO | '{name}' succeeded (attempt counted by Resilience4j) |
| `onError` | ERROR | '{name}' failed after all retry attempts |
| `onIgnoredError` | INFO | Non-retryable exception for '{name}', skipping retry |

Publishes a Micrometer counter `framework.resilience.retry.ignored` (tagged by name) for non-retryable exceptions — this complements `TaggedRetryMetrics` which doesn't track ignored errors.

### 3. Update `ResilienceAutoConfiguration` (framework-core)

**File**: `framework-core/src/main/java/com/theyawns/framework/resilience/ResilienceAutoConfiguration.java`

Changes:
- In `retryRegistry()`: add `.retryOnException(e -> !(e instanceof NonRetryableException))` to `RetryConfig`
- Add new bean method `retryEventListener()` that creates and returns a `RetryEventListener`

### 4. Make existing business exceptions non-retryable

Add `implements NonRetryableException` to these six existing exceptions (one-line change each):

| File | Exception |
|------|-----------|
| `inventory-service/.../exception/InsufficientStockException.java` | Stock shortage — will never resolve on retry |
| `inventory-service/.../exception/ProductNotFoundException.java` | Product doesn't exist |
| `payment-service/.../exception/InvalidPaymentStateException.java` | Wrong payment state |
| `payment-service/.../exception/PaymentNotFoundException.java` | Payment doesn't exist |
| `order-service/.../exception/InvalidOrderStateException.java` | Wrong order state |
| `order-service/.../exception/OrderNotFoundException.java` | Order doesn't exist |

### 5. Create `PaymentDeclinedException` (payment-service)

**File**: `payment-service/src/main/java/com/theyawns/ecommerce/payment/exception/PaymentDeclinedException.java`

New exception for when payment processor declines (bad card, insufficient funds, etc.). Implements `NonRetryableException`. Fields: `orderId`, `reason`, `declineCode`.

### 6. Tests

**New**: `framework-core/src/test/java/com/theyawns/framework/resilience/RetryEventListenerTest.java`
- Verify retry event hooks are registered on new retry instances
- Verify logging on retry, success, error, and ignored error events
- Verify `framework.resilience.retry.ignored` counter increments

**Update**: `framework-core/src/test/java/com/theyawns/framework/resilience/ResilientServiceInvokerTest.java`
- Add `NonRetryableExceptionBehavior` nested test class
- Test: non-retryable exception is NOT retried (attempt count = 1)
- Test: non-retryable exception still propagates as `ResilienceException`
- Test: retryable exception IS retried (existing behavior, but verify side-by-side)

**Update**: `framework-core/src/test/java/com/theyawns/framework/resilience/ResilienceAutoConfigurationTest.java`
- Verify `RetryEventListener` bean is created by default
- Verify retry config ignores `NonRetryableException` subclasses

## Files Changed (Summary)

| Action | File |
|--------|------|
| CREATE | `framework-core/.../resilience/NonRetryableException.java` |
| CREATE | `framework-core/.../resilience/RetryEventListener.java` |
| CREATE | `payment-service/.../exception/PaymentDeclinedException.java` |
| CREATE | `framework-core/.../resilience/RetryEventListenerTest.java` |
| MODIFY | `framework-core/.../resilience/ResilienceAutoConfiguration.java` |
| MODIFY | `framework-core/.../resilience/ResilientServiceInvokerTest.java` |
| MODIFY | `framework-core/.../resilience/ResilienceAutoConfigurationTest.java` |
| MODIFY | `inventory-service/.../exception/InsufficientStockException.java` |
| MODIFY | `inventory-service/.../exception/ProductNotFoundException.java` |
| MODIFY | `payment-service/.../exception/InvalidPaymentStateException.java` |
| MODIFY | `payment-service/.../exception/PaymentNotFoundException.java` |
| MODIFY | `order-service/.../exception/InvalidOrderStateException.java` |
| MODIFY | `order-service/.../exception/OrderNotFoundException.java` |

## Verification

1. `mvn test -pl framework-core` — all resilience tests pass including new non-retryable and event listener tests
2. `mvn test -pl inventory-service,payment-service,order-service` — existing saga listener tests still pass (exceptions now implement marker but behavior unchanged in tests)
3. `mvn compile` — full project compiles cleanly
