# ADR 003: Use GenericRecord for Event and View Serialization

## Status

**Accepted** - January 2026

## Context

Events and materialized views need to be serialized for:

1. Storage in Hazelcast IMap
2. Transfer between cluster nodes
3. Processing in Jet pipelines
4. Potential future persistence to external stores

We need a serialization strategy that supports:
- Schema flexibility without redeploying cluster
- Cross-service communication
- Schema evolution over time
- Efficient storage and transfer

### Options Considered

1. **Java Serialization**
   - Native Java `Serializable`
   - Pros: Simple, works out of box
   - Cons: Slow, large payloads, requires same classes on cluster

2. **Hazelcast Portable**
   - Hazelcast's cross-language serialization
   - Pros: Schema evolution, cross-language
   - Cons: Verbose, requires factory registration

3. **Hazelcast Compact (GenericRecord)**
   - Schema-free serialization with GenericRecord
   - Pros: No classes needed on cluster, flexible schema, efficient
   - Cons: Newer API, less type safety at compile time

4. **Protocol Buffers / Avro**
   - External serialization frameworks
   - Pros: Industry standard, schema registry
   - Cons: Additional dependency, integration complexity

## Decision

We use **Hazelcast Compact serialization with GenericRecord** for all events and views.

### Key Benefits

1. **No domain classes on cluster** - Cluster nodes don't need application classes
2. **Schema flexibility** - Add fields without breaking existing code
3. **Efficient** - Compact binary format with schema sharing
4. **Built-in** - Native Hazelcast feature, no external dependencies

### Implementation Pattern

```java
// Creating a GenericRecord
GenericRecord customerEvent = GenericRecordBuilder.compact("CustomerCreatedEvent")
    .setString("eventId", eventId)
    .setString("eventType", "CustomerCreated")
    .setString("key", customerId)
    .setString("email", email)
    .setString("name", name)
    .setInt64("timestamp", Instant.now().toEpochMilli())
    .build();

// Reading from a GenericRecord
String email = record.getString("email");
long timestamp = record.getInt64("timestamp");
```

### Event Base Class Pattern

```java
public abstract class DomainEvent<D, K> {
    // Convert to GenericRecord for storage
    public abstract GenericRecord toGenericRecord();

    // Apply event to domain state
    public abstract GenericRecord apply(GenericRecord currentState);
}
```

## Consequences

### Positive

- **Cluster independence**: No application JARs on Hazelcast nodes
- **Schema evolution**: Add/remove fields without cluster restart
- **Efficient storage**: Compact binary format
- **Pipeline compatibility**: GenericRecord flows through Jet seamlessly
- **Future-proof**: Easy to add fields for new requirements

### Negative

- **No compile-time type safety**: Field names are strings, types unchecked
- **Verbose construction**: Builder pattern is more code than POJO
- **Learning curve**: Team must understand GenericRecord API
- **IDE support**: Less autocomplete than typed objects

### Mitigations

| Risk | Mitigation |
|------|------------|
| Type safety | Constants for field names; comprehensive tests |
| Verbosity | Helper methods, builder patterns |
| Learning curve | Documentation, code examples |
| IDE support | Schema documentation in JavaDoc |

### Code Conventions

```java
// Define field name constants
public static final String FIELD_EVENT_ID = "eventId";
public static final String FIELD_EVENT_TYPE = "eventType";

// Use in builders and readers
.setString(FIELD_EVENT_ID, eventId)
record.getString(FIELD_EVENT_ID)
```

## References

- [Hazelcast Compact Serialization](https://docs.hazelcast.com/hazelcast/latest/serialization/compact-serialization)
- [GenericRecord API](https://docs.hazelcast.com/hazelcast/latest/serialization/generic-record)
