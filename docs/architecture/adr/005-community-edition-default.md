# ADR 005: Hazelcast Community Edition as Default Configuration

## Status

**Accepted** - January 2026

## Context

Hazelcast is available in two editions:

1. **Community Edition** - Open source, free
2. **Enterprise Edition** - Commercial, additional features

Our framework needs to work for:
- Demos and tutorials
- Development environments
- Production deployments (various scales)
- Educational blog posts

### Options Considered

1. **Require Enterprise Edition**
   - Use Enterprise features freely
   - Pros: Access to all capabilities
   - Cons: License required, limits adoption

2. **Community Edition Only**
   - No Enterprise features
   - Pros: Maximum accessibility
   - Cons: Miss useful features

3. **Community Default, Enterprise Optional**
   - Work fully on Community
   - Enterprise features as opt-in enhancements
   - Pros: Accessible + extensible
   - Cons: Requires feature detection, dual paths

## Decision

We adopt **Community Edition as the default** with Enterprise features as optional enhancements.

### Guiding Principles

1. **Framework MUST work fully with Community Edition**
2. **Enterprise features are OPTIONAL enhancements**
3. **Every Enterprise feature has a Community fallback**
4. **Feature flags control Enterprise capabilities**

### Feature Matrix

| Capability | Community Solution | Enterprise Enhancement |
|------------|-------------------|----------------------|
| Sequence generation | FlakeIdGenerator | CP AtomicLong (stronger consistency) |
| Persistence | None (Phase 1) | Hot Restart |
| Security | None (Phase 1) | TLS, Auth, RBAC |
| Consistency | AP (eventual) | CP Subsystem (strong) |
| Memory | On-heap | HD Memory (off-heap) |
| Similarity Search | None | Vector Store |

### Implementation Pattern

```java
@Service
public class SequenceService {

    @Value("${hazelcast.enterprise.cp-subsystem.enabled:false}")
    private boolean cpSubsystemEnabled;

    private final HazelcastInstance hazelcast;

    public long getNextSequence(String name) {
        if (cpSubsystemEnabled && isCpSubsystemAvailable()) {
            // Enterprise: Strong consistency via CP Subsystem
            return hazelcast.getCPSubsystem()
                .getAtomicLong(name)
                .incrementAndGet();
        } else {
            // Community: FlakeIdGenerator (default)
            return hazelcast.getFlakeIdGenerator(name).newId();
        }
    }

    private boolean isCpSubsystemAvailable() {
        try {
            hazelcast.getCPSubsystem();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

### Configuration

```yaml
# application.yml
hazelcast:
  enterprise:
    enabled: false  # Default: Community Edition

    # Optional Enterprise features (all disabled by default)
    cp-subsystem:
      enabled: false
    hot-restart:
      enabled: false
    tls:
      enabled: false
```

## Consequences

### Positive

- **Maximum adoption**: Anyone can use the framework
- **Educational value**: Demos work without license
- **Gradual adoption**: Start free, add Enterprise when needed
- **Clear documentation**: Know what requires Enterprise
- **No vendor lock-in**: Community features are sufficient

### Negative

- **Feature detection**: Code must check what's available
- **Dual code paths**: Some features have two implementations
- **Documentation burden**: Must document both paths
- **Testing complexity**: Test with and without Enterprise

### Enterprise Feature Documentation

Each Enterprise-only feature must:
1. Be documented as "Enterprise Only" in JavaDoc
2. Have a Community fallback
3. Be controlled by configuration flag
4. Degrade gracefully if unavailable

```java
/**
 * Gets the next sequence number.
 *
 * <p><b>Community Edition:</b> Uses FlakeIdGenerator (distributed, unique,
 * roughly ordered). Suitable for most use cases.
 *
 * <p><b>Enterprise Edition:</b> When {@code hazelcast.enterprise.cp-subsystem.enabled}
 * is true, uses CP AtomicLong for strong consistency guarantees.
 *
 * @param name the sequence name
 * @return the next sequence number
 */
public long getNextSequence(String name) { ... }
```

### Phase 2 Considerations

Enterprise features planned for Phase 2:

| Feature | Use Case | Fallback |
|---------|----------|----------|
| Hot Restart | Event store persistence | PostgreSQL MapStore |
| CP Subsystem | Distributed locks for sagas | Optimistic concurrency |
| TLS | Secure cluster communication | Network isolation |

## References

- [Hazelcast Editions Comparison](https://hazelcast.com/pricing/)
- [CP Subsystem](https://docs.hazelcast.com/hazelcast/latest/cp-subsystem/cp-subsystem)
- [FlakeIdGenerator](https://docs.hazelcast.com/hazelcast/latest/data-structures/flake-id-generator)
