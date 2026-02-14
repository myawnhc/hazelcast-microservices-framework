# Phase 2 Day 3: Saga Timeout Detection & Compensation (Reconstructed)

> Reconstructed from git history and implementation plan. May not capture all details from the original session.

## Context

Day 3 completes the saga infrastructure trio (state store, event contracts, timeout detection) by implementing the mechanism to detect stuck sagas and trigger compensation. This uses Spring's scheduling support with configurable intervals and a pluggable compensator interface.

## What Was Built

- `SagaTimeoutConfig`: Spring configuration properties for timeout settings including check interval, default deadline, and saga-type-specific timeouts
- `SagaCompensator` interface: Contract for triggering saga compensation (pluggable for different strategies)
- `DefaultSagaCompensator`: Publishes compensation requests via Hazelcast topics and Spring events, records metrics
- `SagaTimedOutEvent`: Application event for timeout notifications with a `TimeoutDetails` record for cross-service notification
- `SagaTimeoutDetector`: Scheduled service that detects timed-out sagas, marks them as TIMED_OUT, and triggers compensation with distributed lock support
- `SagaTimeoutAutoConfiguration`: Spring Boot auto-configuration wiring all timeout components together

## Key Decisions

- **Scheduled detection with configurable interval**: Uses `@Scheduled(fixedDelayString = "${saga.timeout.check-interval:5000}")` for polling rather than event-driven timeout to keep implementation simple and reliable.
- **SagaCompensator as interface**: Allows swapping compensation strategies (e.g., for testing or different messaging systems).
- **DefaultSagaCompensator publishes to both Hazelcast topics and Spring events**: Dual notification ensures both cross-service (Hazelcast) and in-process (Spring) listeners receive timeout notifications.
- **Distributed lock support**: SagaTimeoutDetector supports distributed locking to prevent multiple service instances from compensating the same timed-out saga.
- **Auto-configuration**: All timeout components are wired via Spring Boot auto-configuration, making them available automatically when the saga dependency is present.

## Test Coverage

258 total saga tests passing after Day 3 (cumulative across Days 1-3). Day 3 added tests for DefaultSagaCompensator, SagaTimedOutEvent, SagaTimeoutConfig, and SagaTimeoutDetector.

## Files Changed

| File | Change |
|------|--------|
| `framework-core/src/main/java/com/theyawns/framework/saga/SagaCompensator.java` | Created -- Interface for triggering compensation |
| `framework-core/src/main/java/com/theyawns/framework/saga/DefaultSagaCompensator.java` | Created -- Default implementation with Hazelcast topics and Spring events |
| `framework-core/src/main/java/com/theyawns/framework/saga/SagaTimedOutEvent.java` | Created -- Application event with TimeoutDetails record |
| `framework-core/src/main/java/com/theyawns/framework/saga/SagaTimeoutConfig.java` | Created -- Configuration properties for timeout settings |
| `framework-core/src/main/java/com/theyawns/framework/saga/SagaTimeoutDetector.java` | Created -- Scheduled service for detecting timed-out sagas |
| `framework-core/src/main/java/com/theyawns/framework/saga/SagaTimeoutAutoConfiguration.java` | Created -- Spring Boot auto-configuration |
| `framework-core/src/test/java/com/theyawns/framework/saga/DefaultSagaCompensatorTest.java` | Created -- Tests for DefaultSagaCompensator |
| `framework-core/src/test/java/com/theyawns/framework/saga/SagaTimedOutEventTest.java` | Created -- Tests for SagaTimedOutEvent |
| `framework-core/src/test/java/com/theyawns/framework/saga/SagaTimeoutConfigTest.java` | Created -- Tests for SagaTimeoutConfig |
| `framework-core/src/test/java/com/theyawns/framework/saga/SagaTimeoutDetectorTest.java` | Created -- Tests for SagaTimeoutDetector |

## Commit

- **Hash**: `814dee5`
- **Date**: 2026-01-28
- **Stats**: 10 files changed, 2807 insertions
