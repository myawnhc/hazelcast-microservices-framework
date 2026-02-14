# Phase 3 Day 1: Resilience4j Foundation

## Context

Phase 3 hardens the framework for production. Day 1 lays the Resilience4j foundation in `framework-core` — adding dependencies, configuration properties, a general-purpose `ResilientServiceInvoker`, auto-configuration, and unit tests. This foundation enables Day 2 (circuit breakers on saga listeners) and Day 3 (retry with exponential backoff).

**Key insight**: Services communicate via Hazelcast ITopic pub/sub, not REST. The `ResilientServiceInvoker` wraps generic `Supplier<T>` / `Runnable` / `CompletableFuture` operations — not HTTP calls.

**Design decision**: We use the **programmatic Resilience4j API** (`CircuitBreakerRegistry`, `RetryRegistry`) rather than `resilience4j-spring-boot3` annotation-driven AOP. Saga listeners instantiate inner `MessageListener` classes via `new` inside `@PostConstruct` — AOP proxies would not intercept those calls. The programmatic API works everywhere.

## Files to Create/Modify

### 1. Root POM — `pom.xml`
- Add `<resilience4j.version>2.2.0</resilience4j.version>` to `<properties>`
- Add Resilience4j BOM to `<dependencyManagement>`:
  ```xml
  <dependency>
      <groupId>io.github.resilience4j</groupId>
      <artifactId>resilience4j-bom</artifactId>
      <version>${resilience4j.version}</version>
      <type>pom</type>
      <scope>import</scope>
  </dependency>
  ```

### 2. Framework-Core POM — `framework-core/pom.xml`
- Add three dependencies (no version — managed by BOM):
  - `resilience4j-circuitbreaker`
  - `resilience4j-retry`
  - `resilience4j-micrometer`

### 3. NEW: `ResilienceProperties.java`
**Path**: `framework-core/src/main/java/com/theyawns/framework/resilience/ResilienceProperties.java`
- `@ConfigurationProperties(prefix = "framework.resilience")`
- Fields: `enabled` (default true), `circuitBreaker`, `retry`, `instances` (per-name overrides)
- Nested `CircuitBreakerProperties`: failureRateThreshold (50), waitDurationInOpenState (10s), slidingWindowSize (10), slidingWindowType (COUNT_BASED), minimumNumberOfCalls (5), permittedNumberOfCallsInHalfOpenState (3)
- Nested `RetryProperties`: maxAttempts (3), waitDuration (500ms), enableExponentialBackoff (true), exponentialBackoffMultiplier (2.0)
- Nested `InstanceProperties`: optional per-instance CB + retry overrides
- Follows `SagaTimeoutConfig` pattern: validation in setters, `toString()`

### 4. NEW: `ResilienceException.java`
**Path**: `framework-core/src/main/java/com/theyawns/framework/resilience/ResilienceException.java`
- `extends RuntimeException`
- Fields: `operationName`
- Wraps the underlying cause with diagnostic context

### 5. NEW: `ResilientServiceInvoker.java`
**Path**: `framework-core/src/main/java/com/theyawns/framework/resilience/ResilientServiceInvoker.java`
- Constructor: `(CircuitBreakerRegistry, RetryRegistry, ResilienceProperties)`
- Methods:
  - `<T> T execute(String name, Supplier<T> operation)` — sync with CB + retry
  - `void executeRunnable(String name, Runnable operation)` — void with CB + retry
  - `<T> CompletableFuture<T> executeAsync(String name, Supplier<CompletableFuture<T>> operation)` — async with CB + retry
  - `CircuitBreaker getCircuitBreaker(String name)` — for monitoring/testing
  - `Retry getRetry(String name)` — for monitoring/testing
  - `boolean isEnabled()` — passthrough check
- When `enabled=false`, all methods delegate directly without decoration
- Uses standard try-catch (no vavr dependency)

### 6. NEW: `ResilienceAutoConfiguration.java`
**Path**: `framework-core/src/main/java/com/theyawns/framework/resilience/ResilienceAutoConfiguration.java`
- `@ConditionalOnClass(CircuitBreakerRegistry.class)` + `@ConditionalOnProperty("framework.resilience.enabled", matchIfMissing = true)`
- Beans (all `@ConditionalOnMissingBean`):
  - `CircuitBreakerRegistry` — configured from properties
  - `RetryRegistry` — configured from properties (with exponential backoff via `IntervalFunction`)
  - `ResilientServiceInvoker`
  - `TaggedCircuitBreakerMetrics` — binds CB metrics to Micrometer
  - `TaggedRetryMetrics` — binds retry metrics to Micrometer
- Follows `SagaTimeoutAutoConfiguration` pattern

### 7. UPDATE: `AutoConfiguration.imports`
**Path**: `framework-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Append: `com.theyawns.framework.resilience.ResilienceAutoConfiguration`

### 8. NEW: `ResiliencePropertiesTest.java`
**Path**: `framework-core/src/test/java/com/theyawns/framework/resilience/ResiliencePropertiesTest.java`
- Tests: default values, setter validation (invalid ranges throw), per-instance overrides, toString
- ~25 tests

### 9. NEW: `ResilientServiceInvokerTest.java`
**Path**: `framework-core/src/test/java/com/theyawns/framework/resilience/ResilientServiceInvokerTest.java`
- Uses real `CircuitBreakerRegistry`/`RetryRegistry` instances (no mocking needed — Java 25 safe)
- Tests: successful call, retry on transient failure, ResilienceException after exhaustion, circuit breaker opens after repeated failures, rejects when circuit open, void runnable, async futures, disabled passthrough, accessors
- ~15 tests

### 10. NEW: `ResilienceAutoConfigurationTest.java`
**Path**: `framework-core/src/test/java/com/theyawns/framework/resilience/ResilienceAutoConfigurationTest.java`
- `ApplicationContextRunner` pattern (matches `SagaTimeoutAutoConfigurationTest`)
- Tests: all beans created by default, property binding for CB + retry, metrics registered, disabled skips all beans, custom bean override respected
- ~10 tests

### 11. NEW: `ResilienceExceptionTest.java`
**Path**: `framework-core/src/test/java/com/theyawns/framework/resilience/ResilienceExceptionTest.java`
- Simple: preserves operation name and cause
- ~2 tests

## Implementation Order

1. Root POM dependencies
2. Framework-core POM dependencies
3. `ResilienceProperties.java`
4. `ResilienceException.java`
5. `ResilientServiceInvoker.java`
6. `ResilienceAutoConfiguration.java`
7. Update `AutoConfiguration.imports`
8. `ResiliencePropertiesTest.java` — run tests
9. `ResilientServiceInvokerTest.java` — run tests
10. `ResilienceAutoConfigurationTest.java` — run tests
11. `ResilienceExceptionTest.java` — run tests
12. Full `mvn test` on framework-core

## Verification

1. `mvn test -pl framework-core` — all new + existing tests pass
2. `mvn compile -pl framework-core` — no compilation warnings
3. Check that `ResilienceAutoConfiguration` is picked up by creating a quick `ApplicationContextRunner` test that asserts all beans exist
4. Verify circuit breaker state transitions work in `ResilientServiceInvokerTest`
5. Full `mvn test` across all modules to ensure no regressions
