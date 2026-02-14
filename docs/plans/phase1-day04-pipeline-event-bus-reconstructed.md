# Phase 1 Day 4: Event Sourcing Pipeline and Event Bus (Reconstructed)

> Reconstructed from git history and implementation plan. May not capture all details from the original session.

## Context

Days 1-3 established the core abstractions, event storage, and materialized views. Day 4 tackled the most complex component of the framework: the Hazelcast Jet event sourcing pipeline and the event bus for pub/sub. The pipeline is the engine that drives the event sourcing workflow -- reading events from the pending events map, persisting them to the event store, applying them to materialized views, and publishing them to subscribers.

## What Was Built

### Pipeline Implementation
- `EventSourcingPipeline<D, K, E>` -- The core Jet pipeline that implements the 6-stage event sourcing workflow:
  1. **Source**: Read from the pending events IMap via Event Journal (streaming)
  2. **Enrich**: Add pipeline entry timestamp and any needed metadata
  3. **Persist**: Write the event to the EventStore (IMap-based)
  4. **Apply**: Apply the event to the materialized view via UpdateViewEntryProcessor
  5. **Publish**: Publish the event to the HazelcastEventBus for subscribers
  6. **Complete**: Update the completions map to signal that processing is done

  This is a 525-line implementation including pipeline construction, stage definitions, error handling, and lifecycle management (start/stop).

### Event Bus Implementation
- `HazelcastEventBus` -- Implementation of event publishing/subscribing using Hazelcast ITopic. Supports publishing events as GenericRecord, subscribing with type-filtered listeners, and unsubscribing. Wraps Hazelcast's distributed pub/sub mechanism to provide the `EventPublisher` and `EventSubscriber` interfaces defined on Day 1. (304 lines)

### Metrics Collection
- `PipelineMetrics` -- Instrumentation for the pipeline stages using Micrometer. Tracks counters and timers for events submitted, events persisted, events applied to views, events published, events completed, and events failed. Provides named metrics per domain object type for multi-service observability. (275 lines)

### Tests
- `EventSourcingPipelineTest` -- Initial pipeline test scaffolding (47 lines). The pipeline's Jet dependency made full unit testing challenging; more comprehensive integration tests came later.
- `HazelcastEventBusTest` -- Tests for event publishing, subscribing, type filtering, and unsubscribing (338 lines)
- `PipelineMetricsTest` -- Tests for counter increments, timer recordings, and metric naming conventions (305 lines)

## Key Decisions

1. **Hazelcast Jet for the pipeline** -- The pipeline uses Hazelcast Jet's streaming API with Event Journal as the source. This provides exactly-once processing semantics within the pipeline and leverages Hazelcast's built-in distributed compute.
2. **6-stage pipeline architecture** -- The stages (source, enrich, persist, apply, publish, complete) are clearly separated concerns. Each stage is a distinct pipeline step, making the flow traceable and debuggable.
3. **ITopic-based event bus** -- Hazelcast ITopic provides reliable pub/sub within the cluster. This was chosen over alternatives like external message brokers to keep the framework self-contained on Hazelcast.
4. **Micrometer for metrics** -- Aligns with Spring Boot's observability ecosystem and provides Prometheus-compatible metrics out of the box via the micrometer-registry-prometheus dependency added on Day 1.
5. **Pipeline test as scaffolding** -- The pipeline test was minimal (47 lines) because Jet pipelines require a running Hazelcast instance with properly configured Event Journals. Full pipeline testing was deferred to integration tests.

## Test Results

3 test classes were created:
- `EventSourcingPipelineTest` (47 lines -- scaffolding)
- `HazelcastEventBusTest` (338 lines)
- `PipelineMetricsTest` (305 lines)

## Files Changed

| File | Change |
|------|--------|
| `framework-core/src/main/java/com/theyawns/framework/pipeline/EventSourcingPipeline.java` | Created -- 6-stage Hazelcast Jet event sourcing pipeline (525 lines) |
| `framework-core/src/main/java/com/theyawns/framework/pipeline/HazelcastEventBus.java` | Created -- ITopic-based event pub/sub implementation (304 lines) |
| `framework-core/src/main/java/com/theyawns/framework/pipeline/PipelineMetrics.java` | Created -- Micrometer-based pipeline instrumentation (275 lines) |
| `framework-core/src/test/java/com/theyawns/framework/pipeline/EventSourcingPipelineTest.java` | Created -- Pipeline test scaffolding (47 lines) |
| `framework-core/src/test/java/com/theyawns/framework/pipeline/HazelcastEventBusTest.java` | Created -- Event bus unit tests (338 lines) |
| `framework-core/src/test/java/com/theyawns/framework/pipeline/PipelineMetricsTest.java` | Created -- Pipeline metrics unit tests (305 lines) |
