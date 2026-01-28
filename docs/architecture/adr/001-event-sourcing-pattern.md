# ADR 001: Adopt Event Sourcing as Core Architecture Pattern

## Status

**Accepted** - January 2026

## Context

We need an architecture for microservices that provides:

1. **Complete audit trail** - Track every state change for compliance and debugging
2. **Time travel capability** - Reconstruct state at any point in history
3. **Decoupled services** - Services should not make synchronous calls to each other
4. **High performance** - Sub-millisecond reads for API responses
5. **Rebuild capability** - Ability to fix bugs and rebuild state

Traditional CRUD architectures store only current state. Once updated, previous state is lost. Service-to-service calls create tight coupling and cascade failures.

### Options Considered

1. **Traditional CRUD with Change Data Capture (CDC)**
   - Store current state, capture changes via CDC
   - Pros: Simple, familiar pattern
   - Cons: CDC is secondary, not authoritative; limited history

2. **Event Sourcing with CQRS**
   - Events are source of truth; views derived from events
   - Pros: Complete history, rebuild capability, natural fit for async
   - Cons: More complex, eventual consistency

3. **Hybrid (CRUD + Event Log)**
   - CRUD for operations, append events for audit
   - Pros: Simple reads, audit trail
   - Cons: Two sources of truth, sync issues

## Decision

We adopt **Event Sourcing with CQRS** as the core architectural pattern.

### Key Principles

1. **Events are the source of truth** - All state changes captured as immutable events
2. **State is derived** - Current state computed by applying events to initial state
3. **Materialized views for reads** - Pre-computed projections for fast queries
4. **Commands create events** - Write operations produce events, not direct state changes

### Implementation

```
Write Path:
  API Request → Validate → Create Event → Pending Map → Jet Pipeline → Event Store
                                                               ↓
Read Path:                                              Update View
  API Request → Materialized View → Response
```

## Consequences

### Positive

- **Complete audit trail**: Every change is permanently recorded
- **Debugging**: Replay events to reproduce issues
- **View rebuilding**: Fix bugs and rebuild all views from events
- **Service decoupling**: Services maintain local views, no runtime calls
- **Flexibility**: Add new views without changing event schema
- **Natural async**: Events flow through pipeline asynchronously

### Negative

- **Eventual consistency**: Views may lag behind events (typically <5ms)
- **Complexity**: More concepts to understand (events, views, projections)
- **Storage growth**: Event store grows indefinitely (mitigate with archival)
- **Schema evolution**: Event schema changes require careful handling

### Mitigations

| Risk | Mitigation |
|------|------------|
| Eventual consistency | Accept for most reads; wait for completion when needed |
| Complexity | Comprehensive documentation, educational blog posts |
| Storage growth | Plan archival strategy for Phase 2 |
| Schema evolution | Include eventVersion field, plan upgrade path |

## References

- Martin Fowler: [Event Sourcing](https://martinfowler.com/eaaDev/EventSourcing.html)
- Microsoft: [CQRS Pattern](https://docs.microsoft.com/en-us/azure/architecture/patterns/cqrs)
- Greg Young: [CQRS Documents](https://cqrs.files.wordpress.com/2010/11/cqrs_documents.pdf)
