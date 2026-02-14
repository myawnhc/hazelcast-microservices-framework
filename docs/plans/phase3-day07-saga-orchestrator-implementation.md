# Phase 3 Day 7: Saga Orchestrator Implementation

## Context

Day 6 delivered 9 interface/model classes and 6 test files (106 tests) defining the orchestrated saga API. Day 7 transforms these interfaces into a working state machine that executes saga steps sequentially, handles failures with reverse-order compensation, enforces timeouts, and persists state via the existing `SagaStateStore`.

## Design Decisions

**Direct execution model**: The orchestrator calls `step.getAction().execute(context)` directly — actions are lambdas that encapsulate their own communication. No separate ITopic command/result events are needed at the orchestrator level. The `handleStepResult()` method supports an alternative async model where external processes complete pending step futures.

**No new event classes**: `SagaStepCommand` and `SagaStepResultEvent` (mentioned in the original plan) are deferred to Day 8, where the actual order fulfillment saga will wire up ITopic-based remote service calls inside action lambdas.

**Retry with delay**: Uses `CompletableFuture` chaining with `ScheduledExecutorService` for non-blocking retry delays.

## Files to Create

### 1. `HazelcastSagaOrchestrator.java` (~450 lines)
**Path**: `framework-core/src/main/java/com/theyawns/framework/saga/orchestrator/HazelcastSagaOrchestrator.java`

Constructor dependencies:
- `SagaStateStore stateStore` — persists saga state
- `List<SagaOrchestratorListener> listeners` — lifecycle observers (may be empty)
- `ScheduledExecutorService scheduler` — for timeouts and retry delays (optional; creates default if null)

Internal tracking:
- `ConcurrentHashMap<String, SagaExecution> activeExecutions` — tracks in-flight sagas

**SagaExecution** (private inner class):
- `sagaId`, `definition`, `context`, `startedAt`
- `resultFuture` (the CompletableFuture returned from `start()`)
- `completedStepNames` (List<String> — for reverse compensation)
- `currentStepIndex` (volatile int)
- `pendingStepFuture` (volatile CompletableFuture<SagaStepResult> — for `handleStepResult()`)

**State machine flow** (`start()` → `executeStep()` → `compensate()`):

```
start(sagaId, definition, context):
  1. Validate: null checks, duplicate sagaId
  2. Create SagaExecution, store in activeExecutions
  3. stateStore.startSaga(sagaId, definition.getName(), stepCount, sagaTimeout)
  4. Notify listeners: onSagaStarted
  5. Chain: executeStep(exec, 0)
  6. Return resultFuture

executeStep(exec, stepIndex):
  If stepIndex >= stepCount → success path
  1. Get SagaStep from definition
  2. Notify listeners: onStepStarted
  3. Call step.getAction().execute(context)
  4. Apply timeout: .orTimeout(step.getTimeout())
  5. Handle retries (loop up to maxRetries on failure, with retryDelay)
  6. On SUCCESS:
     - Merge result data into context
     - stateStore.recordStepCompleted(sagaId, stepIndex, stepName, "orchestrator", sagaId)
     - Notify listeners: onStepCompleted
     - Recurse: executeStep(exec, stepIndex + 1)
  7. On FAILURE/TIMEOUT/exception:
     - stateStore.recordStepFailed(sagaId, stepIndex, stepName, "orchestrator", reason)
     - Notify listeners: onStepCompleted (with failure result)
     - Enter: compensate(exec, failedStepName, failureReason, isTimeout)

compensate(exec, failedStep, reason, isTimeout):
  1. stateStore.recordCompensationStarted(sagaId)
  2. Notify listeners: onCompensationStarted
  3. For each completed step in REVERSE order:
     - If step has compensation: execute compensation action
     - stateStore.recordCompensationStep(sagaId, stepIndex, stepName, "orchestrator")
     - Notify listeners: onCompensationStepCompleted
  4. If all compensations succeed:
     - stateStore.completeSaga(sagaId, isTimeout ? TIMED_OUT : COMPENSATED)
     - Return SagaOrchestratorResult.compensated() or .timedOut()
  5. If a compensation fails:
     - stateStore.completeSaga(sagaId, FAILED)
     - Return SagaOrchestratorResult.failed()
  6. Remove from activeExecutions
  7. Notify listeners: onSagaCompleted

handleStepResult(sagaId, stepName, result):
  1. Look up SagaExecution from activeExecutions
  2. Validate: sagaId exists, stepName matches current pending step
  3. Complete the pendingStepFuture with the given result
  4. (The normal executeStep chain picks up from there)

getStatus(sagaId):
  1. Check activeExecutions — if found, build in-progress result
  2. Otherwise, query stateStore.getSagaState(sagaId) and map to SagaOrchestratorResult

cancel(sagaId, reason):
  1. Look up SagaExecution from activeExecutions
  2. If found and active: trigger compensate() with cancellation reason
  3. If not found: throw IllegalArgumentException
```

### 2. `HazelcastSagaOrchestratorTest.java` (~500 lines)
**Path**: `framework-core/src/test/java/com/theyawns/framework/saga/orchestrator/HazelcastSagaOrchestratorTest.java`

Uses Mockito to mock `SagaStateStore` (interface — works on Java 25). Test action/compensation lambdas return controlled CompletableFutures.

**Test groups** (nested `@Nested` classes):

1. **Construction & Validation**
   - should reject null stateStore
   - should reject null sagaId / definition / context
   - should reject duplicate sagaId

2. **Happy Path (Forward Execution)**
   - should complete single-step saga
   - should complete multi-step saga (3 steps)
   - should pass data between steps via context
   - should record all steps in stateStore
   - should return COMPLETED result with correct counts

3. **Failure Path (Compensation)**
   - should compensate when step fails (3-step saga, step 2 fails -> compensate step 1)
   - should compensate in reverse order
   - should skip steps without compensation
   - should return COMPENSATED result
   - should record failure reason

4. **Timeout Path**
   - should timeout slow step and trigger compensation
   - should return TIMED_OUT result for step timeout
   - should handle saga-level timeout

5. **Retry Logic**
   - should retry on failure up to maxRetries
   - should succeed on retry after transient failure
   - should enter compensation after exhausting retries

6. **Compensation Failure**
   - should return FAILED when compensation step fails
   - should continue compensating remaining steps even after one fails

7. **handleStepResult (External Completion)**
   - should complete pending step with external result
   - should reject unknown sagaId
   - should reject wrong stepName

8. **cancel()**
   - should trigger compensation on cancel
   - should reject cancel for unknown saga
   - should reject cancel for already-completed saga

9. **getStatus()**
   - should return status for active saga
   - should return empty for unknown saga

10. **Listener Notifications**
    - should notify onSagaStarted
    - should notify onStepStarted / onStepCompleted for each step
    - should notify onCompensationStarted / onCompensationStepCompleted
    - should notify onSagaCompleted

## Existing Files to Reuse (Not Modify)

| File | Role |
|------|------|
| `SagaOrchestrator.java` | Interface being implemented |
| `SagaDefinition.java` | Saga blueprint with step list |
| `SagaStep.java` | Step with action, compensation, timeout, retries |
| `SagaAction.java` / `SagaCompensation.java` | Functional interfaces |
| `SagaContext.java` | Mutable thread-safe context |
| `SagaStepResult.java` | Step outcome (SUCCESS/FAILURE/TIMEOUT + data) |
| `SagaOrchestratorResult.java` | Final saga outcome factory methods |
| `SagaOrchestratorListener.java` | Lifecycle callbacks |
| `SagaStateStore.java` | State persistence interface |
| `SagaStatus.java` | Status enum (STARTED, IN_PROGRESS, COMPLETED, COMPENSATING, COMPENSATED, FAILED, TIMED_OUT) |

## Verification

```bash
# Compile
mvn compile -pl framework-core

# Run orchestrator tests only
mvn test -pl framework-core -Dtest="HazelcastSagaOrchestratorTest"

# Run all framework-core tests (ensure no regressions)
mvn test -pl framework-core
```

**Success criteria**:
- `HazelcastSagaOrchestrator` compiles and implements `SagaOrchestrator`
- All ~30 orchestrator tests pass
- All ~966 existing framework-core tests still pass
- Forward path, failure path, timeout path, retry, cancel, handleStepResult all covered
