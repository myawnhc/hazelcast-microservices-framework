# Circuit Breakers and Retry for Saga Resilience

*Part 8 in the "Building Event-Driven Microservices with Hazelcast" series*

---

## Introduction

In [Part 5](05-saga-pattern-for-distributed-transactions.md), we built a choreographed saga for order fulfillment. Three services — Inventory, Payment, and Order — coordinate through Hazelcast ITopic events published on a shared cluster. The happy path works beautifully. But what happens when the Payment Service is slow? When the Inventory Service is temporarily unreachable? When a network partition isolates the shared cluster?

Without resilience patterns, a single failing service can cascade failures across the entire saga. A slow Payment Service causes the Inventory Service's thread pool to fill up waiting for responses. A transient network blip permanently loses an event. A burst of errors overwhelms every service simultaneously.

This article covers the circuit breaker and retry patterns we added to the framework — not as abstract theory, but as concrete code wired into the saga listeners from Part 5. We'll cover:

- Why microservice communication needs resilience patterns
- Circuit breakers: preventing cascade failures with automatic service isolation
- Retry with exponential backoff: surviving transient failures
- The `NonRetryableException` marker: knowing when NOT to retry
- Observability: retry event listeners and Micrometer metrics
- Spring Boot auto-configuration that makes it all optional
- Per-instance tuning for different saga steps

---

## The Problem: Cascading Failures in Choreographed Sagas

Consider our order fulfillment saga's happy path:

```
Order Service           Inventory Service        Payment Service
     |                        |                        |
     |── OrderCreated ──────►|                        |
     |                        |── StockReserved ─────►|
     |                        |                        |── PaymentProcessed ──►|
     |◄── OrderConfirmed ─────────────────────────────|
```

Each step is an ITopic message on the shared Hazelcast cluster. Each listener calls a local service method that may involve IMap operations, Jet pipeline processing, and further ITopic publishing.

Now imagine the Payment Service is experiencing high latency due to a downstream provider issue. Every `StockReserved` event that arrives at the Payment Service takes 30 seconds to process instead of the normal 50 milliseconds. Here's what happens without resilience:

1. The Inventory Service keeps publishing `StockReserved` events at normal rate
2. The Payment Service's listener thread pool fills up with slow calls
3. New events queue up behind the blocked threads
4. ITopic backpressure eventually slows down the shared cluster
5. Other listeners on the same cluster — including the Inventory and Order listeners — start seeing delays
6. The entire saga pipeline grinds to a halt

This is a **cascade failure**. One struggling service drags down every other service that shares the communication fabric.

---

## Circuit Breakers: Automatic Service Isolation

A circuit breaker monitors the failure rate of an operation and automatically stops calling it when the failure rate exceeds a threshold. It's an electrical metaphor: when too much current (failures) flows through a circuit, the breaker trips to prevent damage.

### The Three States

```
         ┌──────────────────────────────────────────┐
         │                                          │
    ┌────▼────┐    failure rate    ┌────────┐      │
    │  CLOSED │   > threshold     │  OPEN  │      │
    │ (normal)├──────────────────►│ (reject)│     │
    └────┬────┘                    └───┬────┘      │
         │                              │            │
         │                    wait duration          │
    calls │                    expires               │
    pass   │                              │            │
    through│                    ┌─────▼──────┐      │
         │                    │  HALF-OPEN  │      │
         │                    │  (test calls)├──────┘
         │                    └─────┬───────┘  success
         │                          │
         └──────────────────────────┘
                  all test calls succeed
```

- **CLOSED** (normal): All calls pass through. The circuit breaker records outcomes in a sliding window.
- **OPEN** (rejecting): All calls are immediately rejected with `CallNotPermittedException`. No load reaches the downstream service.
- **HALF-OPEN** (testing): A limited number of test calls pass through. If they succeed, the breaker returns to CLOSED. If they fail, it returns to OPEN.

### The Framework's ResilientServiceInvoker

Rather than applying Resilience4j decorators individually at every call site, the framework provides `ResilientServiceInvoker` — a single class that wraps any operation with both circuit breaker and retry:

```java
public class ResilientServiceInvoker implements ResilientOperations {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final ResilienceProperties properties;

    public <T> T execute(final String name, final Supplier<T> operation) {
        if (!properties.isEnabled()) {
            return operation.get();
        }

        final CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(name);
        final Retry retry = retryRegistry.retry(name);

        final Supplier<T> decoratedSupplier = CircuitBreaker.decorateSupplier(circuitBreaker,
                Retry.decorateSupplier(retry, operation));

        try {
            return decoratedSupplier.get();
        } catch (CallNotPermittedException e) {
            logger.warn("Circuit breaker '{}' is OPEN — rejecting call", name);
            throw new ResilienceException(
                    "Circuit breaker '" + name + "' is open, call rejected", name, e);
        } catch (Exception e) {
            logger.error("Operation '{}' failed after retries: {}", name, e.getMessage());
            throw new ResilienceException(
                    "Operation '" + name + "' failed after retries", name, e);
        }
    }
}
```

Several design decisions here are worth noting:

**Named instances**: Each call to `execute("inventory-stock-reservation", ...)` creates (or retrieves) a circuit breaker and retry instance named `inventory-stock-reservation`. This means each saga step has its own independent circuit breaker. A payment failure won't trip the inventory circuit breaker.

**Decoration order**: Retry wraps the operation first, then the circuit breaker wraps the retry. This means the circuit breaker sees the *final outcome* after retries are exhausted. A transient failure that succeeds on retry counts as a success for the circuit breaker.

**Kill switch**: When `framework.resilience.enabled=false`, all methods delegate directly to the underlying operation with zero overhead. This is essential for testing and for environments where resilience is handled at a different layer.

### The ResilientOperations Interface

For testability on Java 25 (where Mockito's inline mock maker cannot mock concrete classes with certain JVM configurations), we extract an interface:

```java
public interface ResilientOperations {
    <T> T execute(String name, Supplier<T> operation);
    void executeRunnable(String name, Runnable operation);
    <T> CompletableFuture<T> executeAsync(String name, Supplier<CompletableFuture<T>> operation);
}
```

This follows the same pattern we used for `ServiceClientOperations` in the MCP server — extract an interface from a concrete class so that tests can mock the interface.

### Three Execution Modes

The invoker supports three signatures for different calling patterns:

```java
// Synchronous — returns a value
String result = invoker.execute("orderSaga", () -> processEvent(event));

// Fire-and-forget — void operation
invoker.executeRunnable("paymentListener", () -> publishToTopic(event));

// Async — returns CompletableFuture
CompletableFuture<Product> future = invoker.executeAsync("inventory-stock-reservation",
        () -> inventoryService.reserveStockForSaga(productId, quantity, ...));
```

The async variant is the most important for our saga listeners, because inventory, payment, and order service calls all return `CompletableFuture`.

---

## Wiring into Saga Listeners

The saga listeners from Part 5 now inject `ResilientOperations` as an optional dependency:

```java
@Component
public class InventorySagaListener {

    private final ProductService inventoryService;
    private final HazelcastInstance hazelcast;
    private ResilientOperations resilientServiceInvoker;

    @Autowired(required = false)
    public void setResilientOperations(ResilientOperations resilientServiceInvoker) {
        this.resilientServiceInvoker = resilientServiceInvoker;
    }
```

The `@Autowired(required = false)` annotation is critical. If resilience is disabled or the Resilience4j dependency isn't on the classpath, the listener still works — it just calls the service directly without resilience wrapping.

Each listener uses a shared helper method:

```java
private <T> CompletableFuture<T> executeWithResilience(
        final String name, final Supplier<CompletableFuture<T>> operation) {
    if (resilientServiceInvoker != null) {
        return resilientServiceInvoker.executeAsync(name, operation);
    }
    return operation.get();
}
```

And the actual saga step wraps its service call:

```java
executeWithResilience("inventory-stock-reservation",
        () -> inventoryService.reserveStockForSaga(
                productId, quantity, orderId, sagaId, correlationId,
                customerId, total, currency, "CREDIT_CARD"
        )
).whenComplete((product, error) -> {
    if (error != null) {
        sendToDeadLetterQueue(record, "OrderCreated", error);
    } else {
        logger.info("Stock reserved for saga: productId={}, quantity={}, orderId={}, sagaId={}",
                productId, quantity, orderId, sagaId);
    }
});
```

The circuit breaker name `inventory-stock-reservation` is specific to this saga step. The Inventory Service uses different names for its different operations:

| Circuit Breaker Name | Saga Step | Service |
|---------------------|-----------|---------|
| `inventory-stock-reservation` | Reserve stock on OrderCreated | Inventory |
| `inventory-stock-release` | Release stock on compensation | Inventory |
| `payment-processing` | Process payment on StockReserved | Payment |
| `payment-refund` | Refund payment on compensation | Payment |
| `order-confirmation` | Confirm order on PaymentProcessed | Order |
| `order-cancellation` | Cancel order on compensation | Order |

Each of these can be tuned independently.

---

## Retry with Exponential Backoff

Transient failures — network blips, temporary overload, brief GC pauses — are the most common failure mode in distributed systems. Retry is the first line of defense.

### Why Exponential Backoff Matters

A naive retry strategy (retry immediately, same interval) can make things worse. If a service is overloaded and 100 clients all retry simultaneously at 500ms intervals, the service sees periodic spikes of 100 simultaneous requests — exactly the pattern that caused the overload in the first place.

Exponential backoff spaces retries further apart with each attempt:

```
Attempt 1: immediate
Attempt 2: wait 500ms
Attempt 3: wait 1000ms  (500ms × 2.0)
Attempt 4: wait 2000ms  (1000ms × 2.0)
```

The growing interval gives the struggling service time to recover. Each subsequent wave of retries is smaller and more spread out.

### Configuration

The framework exposes retry settings through `ResilienceProperties`:

```yaml
framework:
  resilience:
    enabled: true
    retry:
      max-attempts: 3
      wait-duration: 500ms
      enable-exponential-backoff: true
      exponential-backoff-multiplier: 2.0
```

The auto-configuration translates these properties into a Resilience4j `RetryConfig`:

```java
@Bean
@ConditionalOnMissingBean
public RetryRegistry retryRegistry(final ResilienceProperties properties) {
    final ResilienceProperties.RetryProperties retryProps = properties.getRetry();

    final RetryConfig.Builder<?> builder = RetryConfig.custom()
            .maxAttempts(retryProps.getMaxAttempts())
            .retryOnException(e -> !(e instanceof NonRetryableException));

    if (retryProps.isEnableExponentialBackoff()) {
        builder.intervalFunction(IntervalFunction
                .ofExponentialBackoff(
                        retryProps.getWaitDuration(),
                        retryProps.getExponentialBackoffMultiplier()));
    } else {
        builder.waitDuration(retryProps.getWaitDuration());
    }

    return RetryRegistry.of(builder.build());
}
```

Two key details:

1. **`retryOnException` predicate**: Exceptions implementing `NonRetryableException` are excluded from retry (more on this below).
2. **Conditional backoff**: When `enable-exponential-backoff` is `false`, a fixed interval is used instead.

---

## NonRetryableException: Knowing When NOT to Retry

Not all failures are transient. A "payment declined" error will never succeed on retry — the credit card is invalid. An "insufficient stock" error is deterministic — the warehouse genuinely doesn't have the product. Retrying these wastes time and resources.

The framework defines a marker interface:

```java
public interface NonRetryableException {
    // Marker interface — business exceptions implement this to skip retry
}
```

Service exceptions opt in by implementing the interface:

```java
public class InsufficientStockException extends RuntimeException
        implements NonRetryableException {

    public InsufficientStockException(String message) {
        super(message);
    }
}
```

```java
public class PaymentDeclinedException extends RuntimeException
        implements NonRetryableException {

    public PaymentDeclinedException(String message) {
        super(message);
    }
}
```

This is a marker interface rather than a base class because service exceptions already extend `RuntimeException`. A marker lets them opt in without changing their inheritance hierarchy.

The retry configuration's `retryOnException` predicate checks this:

```java
.retryOnException(e -> !(e instanceof NonRetryableException))
```

When the retry mechanism encounters a `NonRetryableException`:
- The exception is **not retried** — it fails immediately
- The circuit breaker **still records it as a failure** (it counts toward the failure rate threshold)
- The `RetryEventListener` logs it as an "ignored error" and increments a dedicated metric

This distinction is important for circuit breaker behavior. If a service consistently returns "payment declined" for every request, the circuit breaker will eventually trip — which is correct, because something is systematically wrong even if the individual failures are deterministic.

---

## Retry Observability: The RetryEventListener

Resilience4j publishes events for every retry attempt. The framework hooks into these events for structured logging and custom metrics:

```java
public class RetryEventListener {

    public RetryEventListener(final RetryRegistry retryRegistry,
                              final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Hook into all existing and future retry instances
        retryRegistry.getAllRetries().forEach(this::registerListeners);
        retryRegistry.getEventPublisher().onEntryAdded(
                event -> registerListeners(event.getAddedEntry()));
    }

    private void registerListeners(final Retry retry) {
        final var eventPublisher = retry.getEventPublisher();
        eventPublisher.onRetry(this::onRetry);
        eventPublisher.onSuccess(this::onSuccess);
        eventPublisher.onError(this::onError);
        eventPublisher.onIgnoredError(this::onIgnoredError);
    }
}
```

Four event types provide a complete picture:

| Event | Log Level | Meaning |
|-------|-----------|---------|
| `onRetry` | WARN | An attempt failed, retrying |
| `onSuccess` | INFO | Operation eventually succeeded |
| `onError` | ERROR | All retries exhausted, giving up |
| `onIgnoredError` | INFO | Non-retryable exception, skipping retry |

The `onIgnoredError` handler publishes a custom counter because Resilience4j's built-in `TaggedRetryMetrics` doesn't track ignored errors:

```java
private void onIgnoredError(final RetryOnIgnoredErrorEvent event) {
    logger.info("Non-retryable exception for '{}', skipping retry: {}",
            event.getName(), event.getLastThrowable().getMessage());

    Counter.builder("framework.resilience.retry.ignored")
            .description("Count of non-retryable exceptions that skipped retry")
            .tag("name", event.getName())
            .register(meterRegistry)
            .increment();
}
```

A sample log sequence for a transient failure that eventually succeeds:

```
WARN  RetryEventListener - Retry attempt #1 for 'payment-processing': Connection refused
WARN  RetryEventListener - Retry attempt #2 for 'payment-processing': Connection refused
INFO  RetryEventListener - 'payment-processing' succeeded after 2 attempt(s)
```

And for a non-retryable business exception:

```
INFO  RetryEventListener - Non-retryable exception for 'payment-processing',
      skipping retry: Insufficient funds for amount 15000.00
```

---

## The ResilienceException Wrapper

When an operation exhausts all retries or is rejected by an open circuit breaker, the framework wraps the failure in `ResilienceException`:

```java
public class ResilienceException extends RuntimeException {

    private final String operationName;

    public ResilienceException(String message, String operationName, Throwable cause) {
        super(message, cause);
        this.operationName = operationName;
    }
}
```

The `operationName` field makes it easy to identify which circuit breaker failed in logs and monitoring. Downstream handlers (like the dead letter queue integration in [Part 10](10-dead-letter-queues-and-idempotency.md)) use this to classify the failure:

```java
if (error instanceof ResilienceException) {
    logger.warn("Circuit breaker open, saga step deferred: eventId={}", eventId);
} else {
    logger.error("Failed to process event: {}", eventId, error);
}
```

---

## Auto-Configuration

The entire resilience stack is wired through a single Spring Boot auto-configuration class:

```java
@Configuration
@ConditionalOnClass(CircuitBreakerRegistry.class)
@ConditionalOnProperty(name = "framework.resilience.enabled", matchIfMissing = true)
@EnableConfigurationProperties(ResilienceProperties.class)
public class ResilienceAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public CircuitBreakerRegistry circuitBreakerRegistry(ResilienceProperties properties) { ... }

    @Bean @ConditionalOnMissingBean
    public RetryRegistry retryRegistry(ResilienceProperties properties) { ... }

    @Bean @ConditionalOnMissingBean
    public ResilientServiceInvoker resilientServiceInvoker(...) { ... }

    @Bean @ConditionalOnMissingBean(TaggedCircuitBreakerMetrics.class)
    public TaggedCircuitBreakerMetrics taggedCircuitBreakerMetrics(...) { ... }

    @Bean @ConditionalOnMissingBean(TaggedRetryMetrics.class)
    public TaggedRetryMetrics taggedRetryMetrics(...) { ... }

    @Bean @ConditionalOnMissingBean
    public RetryEventListener retryEventListener(...) { ... }
}
```

Key conditionals:

- **`@ConditionalOnClass(CircuitBreakerRegistry.class)`**: Only activates when Resilience4j is on the classpath. Services that don't include the dependency get no resilience beans.
- **`@ConditionalOnProperty(..., matchIfMissing = true)`**: Enabled by default. Set `framework.resilience.enabled=false` to disable.
- **`@ConditionalOnMissingBean`**: Every bean can be overridden by the application. Want a custom circuit breaker registry? Define your own bean and the auto-configuration backs off.

Six beans are registered:

1. `CircuitBreakerRegistry` — configured from `ResilienceProperties`
2. `RetryRegistry` — configured with optional exponential backoff
3. `ResilientServiceInvoker` — the decorator that wraps operations
4. `TaggedCircuitBreakerMetrics` — binds circuit breaker metrics to Micrometer
5. `TaggedRetryMetrics` — binds retry metrics to Micrometer
6. `RetryEventListener` — structured logging and custom ignored-error counter

---

## Per-Instance Configuration

Different saga steps have different tolerance for failure. Stock reservation might be highly sensitive (we want the circuit to trip fast if inventory is down), while payment processing might tolerate more failures before tripping (payment providers are notoriously flaky).

The framework supports per-instance overrides in each service's `application.yml`:

```yaml
framework:
  resilience:
    enabled: true
    circuit-breaker:
      failure-rate-threshold: 50
      wait-duration-in-open-state: 10s
      sliding-window-size: 10
      minimum-number-of-calls: 5
      permitted-number-of-calls-in-half-open-state: 3
    retry:
      max-attempts: 3
      wait-duration: 500ms
      enable-exponential-backoff: true
      exponential-backoff-multiplier: 2.0
    instances:
      inventory-stock-reservation:
        circuit-breaker:
          failure-rate-threshold: 40
          wait-duration-in-open-state: 5s
        retry:
          max-attempts: 2
      payment-processing:
        circuit-breaker:
          failure-rate-threshold: 60
          wait-duration-in-open-state: 15s
        retry:
          max-attempts: 5
          wait-duration: 1s
```

The `instances` map allows each named circuit breaker to override any default property:

```java
public CircuitBreakerProperties getCircuitBreakerForInstance(final String name) {
    final InstanceProperties instance = instances.get(name);
    if (instance != null && instance.getCircuitBreaker() != null) {
        return instance.getCircuitBreaker();
    }
    return circuitBreaker; // Fall back to defaults
}
```

In this example configuration:

- **`inventory-stock-reservation`**: More aggressive — trips at 40% failure rate with only a 5-second open state. Stock operations should be fast and reliable; if they're failing, something is seriously wrong. Only 2 retry attempts because stock checks are idempotent and quick.
- **`payment-processing`**: More tolerant — trips at 60% failure rate with a 15-second open state. Payment providers have occasional hiccups. 5 retry attempts with 1-second base delay (exponential backoff means the final attempt waits ~16 seconds).

---

## Metrics and Monitoring

The auto-configuration binds both circuit breaker and retry metrics to Micrometer, which exports to Prometheus for Grafana dashboards:

### Circuit Breaker Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `resilience4j_circuitbreaker_state` | Gauge | Current state (0=CLOSED, 1=OPEN, 2=HALF_OPEN) |
| `resilience4j_circuitbreaker_calls_total` | Counter | Total calls by outcome (successful, failed, not_permitted) |
| `resilience4j_circuitbreaker_failure_rate` | Gauge | Current failure rate percentage |
| `resilience4j_circuitbreaker_buffered_calls` | Gauge | Calls in sliding window |

### Retry Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `resilience4j_retry_calls_total` | Counter | Total calls by outcome (successful_without_retry, successful_with_retry, failed_with_retry, failed_without_retry) |
| `framework.resilience.retry.ignored` | Counter | Non-retryable exceptions (tagged by name) |

These metrics power Grafana dashboard panels for saga health:

- Circuit breaker state timeline (shows when breakers trip and recover)
- Retry rate over time (spike indicates transient issues)
- Failure rate by saga step (identifies which step is problematic)
- Non-retryable exception count (business logic failures vs. infrastructure failures)

---

## Configuration Reference

### `framework.resilience.*`

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Master toggle for all resilience features |
| `circuit-breaker.failure-rate-threshold` | `50` | Failure rate (%) to trip the breaker |
| `circuit-breaker.wait-duration-in-open-state` | `10s` | How long to stay open before testing |
| `circuit-breaker.sliding-window-size` | `10` | Number of calls in the measurement window |
| `circuit-breaker.sliding-window-type` | `COUNT_BASED` | `COUNT_BASED` or `TIME_BASED` |
| `circuit-breaker.minimum-number-of-calls` | `5` | Minimum calls before evaluating failure rate |
| `circuit-breaker.permitted-number-of-calls-in-half-open-state` | `3` | Test calls in half-open state |
| `retry.max-attempts` | `3` | Maximum retry attempts (including initial) |
| `retry.wait-duration` | `500ms` | Base wait between retries |
| `retry.enable-exponential-backoff` | `true` | Use exponential backoff |
| `retry.exponential-backoff-multiplier` | `2.0` | Backoff multiplier |
| `instances.<name>.circuit-breaker.*` | (defaults) | Per-instance circuit breaker overrides |
| `instances.<name>.retry.*` | (defaults) | Per-instance retry overrides |

---

## What's Next

Circuit breakers and retry protect against transient failures during event *consumption*. But what about the *producer* side? When `EventSourcingController` needs to republish an event to the shared cluster and the cluster is temporarily unreachable, the event is lost.

In [Part 9](09-transactional-outbox-pattern-with-hazelcast.md), we add the **Transactional Outbox Pattern** — a durable buffer between event production and cross-cluster delivery that guarantees no events are lost, even when the shared cluster is down.
