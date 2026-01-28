# ADR 004: Six-Stage Event Processing Pipeline

## Status

**Accepted** - January 2026

## Context

Events submitted to the system need to be processed through multiple steps:

1. Read from the submission point
2. Persist to durable storage
3. Update materialized views
4. Notify interested parties
5. Acknowledge completion

We need a pipeline design that:
- Processes events reliably in order
- Handles failures gracefully
- Tracks processing metrics
- Scales horizontally
- Maintains consistency

### Options Considered

1. **Single-step processing**
   - One operation does everything
   - Pros: Simple
   - Cons: No separation of concerns, hard to debug, no partial progress

2. **Three-stage pipeline** (Persist → Update → Publish)
   - Minimal stages
   - Pros: Less complexity
   - Cons: Missing enrichment, completion signaling

3. **Six-stage pipeline** (Source → Enrich → Persist → Update → Publish → Complete)
   - Each concern in separate stage
   - Pros: Clear responsibilities, observable, extensible
   - Cons: More stages to understand

## Decision

We implement a **six-stage Hazelcast Jet pipeline** with clear separation of concerns.

### Pipeline Stages

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         EVENT SOURCING PIPELINE                             │
│                                                                             │
│  Stage 1        Stage 2       Stage 3       Stage 4       Stage 5       Stage 6
│  SOURCE         ENRICH        PERSIST       UPDATE        PUBLISH       COMPLETE
│                               VIEW
│  ┌────────┐    ┌────────┐    ┌────────┐    ┌────────┐    ┌────────┐    ┌────────┐
│  │Read    │    │Add     │    │Write to│    │Apply to│    │Notify  │    │Signal  │
│  │from    │───►│metadata│───►│Event   │───►│Material│───►│subscri-│───►│comple- │
│  │Journal │    │& time  │    │Store   │    │ized    │    │bers    │    │tion    │
│  │        │    │stamps  │    │        │    │View    │    │        │    │        │
│  └────────┘    └────────┘    └────────┘    └────────┘    └────────┘    └────────┘
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Stage Responsibilities

| Stage | Name | Purpose | Input | Output |
|-------|------|---------|-------|--------|
| 1 | Source | Read events from pending map | Event Journal | EventContext |
| 2 | Enrich | Add timestamps, extract metadata | EventContext | EventContext |
| 3 | Persist | Store in immutable event store | EventContext | EventContext |
| 4 | Update View | Apply to materialized view | EventContext | EventContext |
| 5 | Publish | Notify subscribers | EventContext | EventContext |
| 6 | Complete | Write completion record | EventContext | (Sink) |

### EventContext Carries State

```java
class EventContext<K> {
    // Event data
    PartitionedSequenceKey<K> key;
    GenericRecord eventRecord;
    String eventType;
    String eventId;

    // Processing timestamps
    Instant pipelineEntryTime;
    Instant persistTime;
    Instant viewUpdateTime;
    Instant publishTime;

    // Stage completion flags
    boolean persisted;
    boolean viewUpdated;
    boolean published;
}
```

## Consequences

### Positive

- **Clear separation**: Each stage has one responsibility
- **Observable**: Timestamps at each stage enable metrics
- **Debuggable**: Know exactly where processing failed
- **Extensible**: Add stages (e.g., validation, transformation)
- **Resilient**: Stages can skip on upstream failure
- **Testable**: Each stage can be unit tested

### Negative

- **More stages**: Six stages vs simpler alternatives
- **Context object**: EventContext adds memory overhead
- **Serialization**: Context must be serializable for distribution

### Design Decisions

**Why Event Journal (not polling)?**
- Real-time streaming
- No missed events
- Backpressure handling

**Why EventContext (not raw events)?**
- Carry metadata through stages
- Track success/failure
- Enable metrics collection

**Why ServiceFactory (not captured instances)?**
- Distributed execution
- Proper serialization
- Resource management per node

### Error Handling

```java
// Stage 3: Persist with error handling
.mapUsingService(eventStoreFactory, (store, ctx) -> {
    try {
        store.append(ctx.key, ctx.eventRecord);
        return ctx.withPersisted(true, Instant.now());
    } catch (Exception e) {
        logger.error("Persist failed for {}", ctx.eventId, e);
        return ctx.withPersisted(false, Instant.now());
    }
})

// Stage 4: Skip if persist failed
.mapUsingService(viewUpdaterFactory, (updater, ctx) -> {
    if (!ctx.persisted) {
        return ctx;  // Skip - don't update view for failed persist
    }
    // ... update view
})
```

### Metrics Collected

| Metric | Description | Source |
|--------|-------------|--------|
| Pipeline entry time | When event entered pipeline | Stage 2 |
| Persist latency | Time to write to event store | Stage 3 |
| View update latency | Time to update view | Stage 4 |
| Total latency | End-to-end processing time | Stage 6 |
| Events processed | Count by domain/type | All stages |

## References

- [Hazelcast Jet Pipeline](https://docs.hazelcast.com/hazelcast/latest/pipelines/building-pipelines)
- [Stream Processing Patterns](https://docs.hazelcast.com/hazelcast/latest/pipelines/transforms)
