# Phase 3 Day 6: Saga Orchestrator Interfaces

## Context

Phase 2 implemented **choreographed sagas** where services react to events independently via ITopic. Day 6 begins Area 2: adding an **orchestrated saga** alternative with a centralized coordinator that explicitly directs each step. Day 6 delivers interfaces, model classes, and the builder DSL only — no Hazelcast implementation (that's Day 7).

All new code goes in `framework-core` under `com.theyawns.framework.saga.orchestrator`.

## Design Decisions

1. **SagaStepResult** is a class (not a plain enum) — carries `Status` enum + optional `Map<String, Object> data` + optional `errorMessage`. Steps produce data that downstream steps need (e.g., paymentId).
2. **Step action/compensation** return `CompletableFuture<SagaStepResult>` — explicit success/failure is cleaner than exception-based flow in async chains.
3. **SagaContext** wraps `ConcurrentHashMap<String, Object>` — mutable, thread-safe, with typed `get(key, Class)`.
4. **SagaDefinition** and **SagaStep** are immutable after build. Unmodifiable lists, final fields.
5. **SagaOrchestrator** interface does NOT depend on `SagaStateStore` — that's an implementation detail for Day 7's `HazelcastSagaOrchestrator`.
6. **Reuse existing `SagaStatus`** enum in `SagaOrchestratorResult` (no new status enum needed).

## Files to Create (9 source + 6 test)

All source under: `framework-core/src/main/java/com/theyawns/framework/saga/orchestrator/`
All tests under: `framework-core/src/test/java/com/theyawns/framework/saga/orchestrator/`

### Implementation Order (dependency-driven)

| # | File | Purpose | Dependencies |
|---|------|---------|-------------|
| 1 | `SagaContext.java` | Mutable key-value context for step data passing | None |
| 2 | `SagaStepResult.java` | Result of step execution (Status + data + error) | None |
| 3 | `SagaAction.java` | `@FunctionalInterface` for forward step action | SagaContext, SagaStepResult |
| 4 | `SagaCompensation.java` | `@FunctionalInterface` for rollback action | SagaContext, SagaStepResult |
| 5 | `SagaStep.java` | Immutable step definition with builder | SagaAction, SagaCompensation |
| 6 | `SagaDefinition.java` | Ordered step collection with fluent DSL builder | SagaStep |
| 7 | `SagaOrchestratorResult.java` | Final saga outcome (reuses `SagaStatus`) | `SagaStatus` (existing) |
| 8 | `SagaOrchestratorListener.java` | Callback interface with default no-op methods | SagaDefinition, SagaStepResult, SagaOrchestratorResult |
| 9 | `SagaOrchestrator.java` | Core orchestrator interface | SagaDefinition, SagaContext, SagaOrchestratorResult |

### Test Files

| # | Test File | Approx Tests |
|---|-----------|-------------|
| 1 | `SagaContextTest.java` | ~15 |
| 2 | `SagaStepResultTest.java` | ~12 |
| 3 | `SagaStepTest.java` | ~14 |
| 4 | `SagaDefinitionTest.java` | ~18 |
| 5 | `SagaOrchestratorResultTest.java` | ~12 |
| 6 | `SagaOrchestratorListenerTest.java` | ~6 |

## Verification

1. `mvn compile -pl framework-core` — all new classes compile
2. `mvn test -pl framework-core -Dtest="com.theyawns.framework.saga.orchestrator.*Test"` — all ~77 tests pass
3. `mvn test -pl framework-core` — existing tests unaffected
