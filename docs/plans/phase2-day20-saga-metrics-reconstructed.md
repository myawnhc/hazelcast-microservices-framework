# Phase 2 Day 20: Event Submission Counter & Active Saga Tracking Metrics (Reconstructed)

> Reconstructed from git history and implementation plan. May not capture all details from the original session.

## Context

Day 20 was the final day of Week 4 (Testing, Documentation & Polish). Rather than a pure integration-and-polish day as originally planned, it focused on filling gaps in the metrics instrumentation — specifically adding an event submission counter to `PipelineMetrics` and active/compensating saga gauges plus finer-grained compensation tracking to `SagaMetrics`. These metrics were needed to support the Grafana dashboards and the `get_metrics` MCP tool (Day 22).

This was a focused, small commit (147 lines across 4 files) that extended existing metrics classes rather than creating new ones.

## What Was Built

- **`PipelineMetrics.recordEventSubmitted()`**: New method tracking events submitted to the controller by event type. Uses a Micrometer counter tagged by event type, providing per-type throughput visibility.
- **`SagaMetrics` active saga gauges**: `sagas.active.count` and `sagas.compensating.count` gauges that increment/decrement with saga lifecycle events (start, complete, compensate). Provides real-time visibility into in-flight sagas.
- **`SagaMetrics.recordCompensationCompleted()`**: Counter for successful compensation completions, tagged by saga type.
- **`SagaMetrics.recordCompensationFailed()`**: Counter for failed compensations, tagged by saga type and failure reason.
- **Corresponding unit tests**: Tests for all new metrics methods in both `PipelineMetricsTest` and `SagaMetricsTest`.

## Key Decisions

- Extended existing metrics classes rather than creating new ones — kept the metrics surface area consolidated
- Used Micrometer gauges (not counters) for active saga counts so they reflect current state, not cumulative totals
- Tagged counters by event type and saga type for Grafana drill-down capability
- Compensation tracking split into completed vs failed for alerting on compensation failures

## Files Changed

| File | Change |
|------|--------|
| `framework-core/src/main/java/com/theyawns/framework/pipeline/PipelineMetrics.java` | Modified — added `recordEventSubmitted()` method (+15 lines) |
| `framework-core/src/main/java/com/theyawns/framework/saga/SagaMetrics.java` | Modified — added active/compensating gauges, `recordCompensationCompleted()`, `recordCompensationFailed()` (+55 lines) |
| `framework-core/src/test/java/com/theyawns/framework/pipeline/PipelineMetricsTest.java` | Modified — added test for `recordEventSubmitted()` (+14 lines) |
| `framework-core/src/test/java/com/theyawns/framework/saga/SagaMetricsTest.java` | Modified — added tests for gauges and compensation tracking (+63 lines) |
