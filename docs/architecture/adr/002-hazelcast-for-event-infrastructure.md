# ADR 002: Use Hazelcast for Event Sourcing Infrastructure

## Status

**Accepted** - January 2026

## Context

We need infrastructure components for our event sourcing architecture:

1. **Event Store** - Append-only log of all events
2. **Materialized Views** - Fast lookup of current state
3. **Stream Processing** - Process events through multiple stages
4. **Event Bus** - Publish events to interested subscribers
5. **Sequence Generation** - Unique, ordered identifiers

We evaluated several technology combinations.

### Options Considered

1. **Kafka + PostgreSQL + Redis**
   - Kafka for event streaming
   - PostgreSQL for event store
   - Redis for materialized views
   - Pros: Industry standard, proven scale
   - Cons: Complex operations, multiple systems to manage

2. **Hazelcast (Single Platform)**
   - IMap for event store and views
   - Jet for stream processing
   - ITopic for event bus
   - FlakeIdGenerator for sequences
   - Pros: Single platform, low latency, simple operations
   - Cons: Less ecosystem, in-memory (Phase 1)

3. **AWS Managed Services**
   - DynamoDB Streams, EventBridge, ElastiCache
   - Pros: Fully managed
   - Cons: Vendor lock-in, complex local development

## Decision

We use **Hazelcast** as the unified platform for all event sourcing infrastructure.

### Component Mapping

| Requirement | Hazelcast Component | Rationale |
|-------------|---------------------|-----------|
| Event Store | IMap with Event Journal | Append-only, journaled for streaming |
| Materialized Views | IMap | Sub-millisecond reads, distributed |
| Stream Processing | Jet Pipeline | Native integration, low latency |
| Event Bus | ITopic | Pub/sub with reliable messaging |
| Sequence Generation | FlakeIdGenerator | Distributed, ordered, no coordination |

### Why Single Platform?

1. **Operational simplicity** - One system to deploy, monitor, scale
2. **Low latency** - No network hops between components
3. **Consistent APIs** - Same programming model everywhere
4. **Local development** - Single embedded instance for testing

## Consequences

### Positive

- **Sub-millisecond latency**: All data in-memory, co-located
- **Simple deployment**: Single cluster to manage
- **Consistent model**: IMap/Jet APIs throughout
- **Easy testing**: Embedded Hazelcast for unit tests
- **Cost effective**: No separate stream/cache/DB systems

### Negative

- **In-memory only (Phase 1)**: Events lost on full cluster restart
- **Memory constraints**: Limited by cluster RAM
- **Less ecosystem**: Fewer tools compared to Kafka/PostgreSQL
- **Learning curve**: Teams may be unfamiliar with Hazelcast

### Mitigations

| Risk | Mitigation |
|------|------------|
| Data loss on restart | Phase 2: Add PostgreSQL persistence via MapStore |
| Memory constraints | Monitor usage; plan archival strategy |
| Learning curve | Documentation, examples, educational content |
| Hazelcast expertise | Engage Hazelcast support if needed |

### Phase 2 Evolution

```
Phase 1 (Current):
  Events → IMap (in-memory only)

Phase 2 (Planned):
  Events → IMap → MapStore → PostgreSQL
                   ↑
            Write-through persistence
```

This allows us to add durability without changing application code.

## References

- [Hazelcast Jet Documentation](https://docs.hazelcast.com/hazelcast/latest/pipelines/overview)
- [Hazelcast IMap](https://docs.hazelcast.com/hazelcast/latest/data-structures/map)
- [Event Journal](https://docs.hazelcast.com/hazelcast/latest/data-structures/event-journal)
