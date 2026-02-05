# ADR 009: Flexible Edition Configuration System

## Status

**Proposed** - February 2026

## Context

Phase 2 introduces features that require Hazelcast Enterprise Edition:
- **Vector Store** - similarity search for product recommendations
- **CP Subsystem** - strong consistency for distributed locks (optional enhancement)
- **Hot Restart** - persistence without external database (future)
- **TLS/Security** - encrypted cluster communication (future)

The framework must support three deployment scenarios:

| Scenario | Description | License Required |
|----------|-------------|------------------|
| **Development** | Local laptop, `docker-compose up` | No |
| **Demo/Tutorial** | Educational use, blog examples | No |
| **Production** | Enterprise deployment with premium features | Yes |

### Requirements

1. **Community Edition is always sufficient** - all core functionality works without Enterprise
2. **Enterprise features are opt-in** - disabled by default
3. **License keys stay out of Git** - environment variables only
4. **Graceful degradation** - Enterprise unavailable = feature disabled, not error
5. **Clear logging** - users know which features are active
6. **Extensible pattern** - works for other tiered products (databases, monitoring, etc.)

### Challenges

1. **License key security**: Keys must never appear in config files, logs, or error messages
2. **Runtime detection**: Some features only detectable at runtime (e.g., CP Subsystem availability)
3. **Configuration sprawl**: Many Enterprise features, each with its own toggle
4. **Testing complexity**: Need to test both editions
5. **Documentation burden**: Clear docs for which features need Enterprise

## Decision

Implement a **three-tier configuration architecture**:

```
┌─────────────────────────────────────────────────────────────────┐
│  Tier 1: Edition Detection                                      │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │  EditionDetector                                           │ │
│  │  - Detects if Enterprise license is present and valid      │ │
│  │  - Single source of truth for edition capabilities         │ │
│  │  - License loaded from environment variable only           │ │
│  └───────────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────────┤
│  Tier 2: Feature Flags                                          │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │  FeatureConfiguration                                      │ │
│  │  - Per-feature enable/disable flags                        │ │
│  │  - Respects edition capabilities (can't enable without EE) │ │
│  │  - YAML configuration + environment overrides              │ │
│  └───────────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────────┤
│  Tier 3: Feature Implementations                                │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │  Conditional Beans                                         │ │
│  │  - @ConditionalOnEdition annotations                       │ │
│  │  - Primary/Fallback bean pairs                             │ │
│  │  - Automatic selection based on Tier 1 + Tier 2            │ │
│  └───────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

### Tier 1: Edition Detection

```java
/**
 * Central service for detecting Hazelcast edition capabilities.
 *
 * <p>This is the ONLY place that checks for Enterprise license.
 * All other code should query this service, not check directly.
 */
@Service
public class EditionDetector {

    // License loaded ONLY from environment variable
    private static final String LICENSE_ENV_VAR = "HZ_LICENSEKEY";

    private final boolean enterpriseAvailable;
    private final Set<EnterpriseFeature> availableFeatures;

    public EditionDetector(HazelcastInstance hazelcast) {
        String license = System.getenv(LICENSE_ENV_VAR);
        this.enterpriseAvailable = detectEnterprise(hazelcast, license);
        this.availableFeatures = detectFeatures(hazelcast);
        logEditionStatus();
    }

    public boolean isEnterpriseAvailable() {
        return enterpriseAvailable;
    }

    public boolean isFeatureAvailable(EnterpriseFeature feature) {
        return availableFeatures.contains(feature);
    }

    public enum EnterpriseFeature {
        VECTOR_STORE,
        CP_SUBSYSTEM,
        HOT_RESTART,
        TLS,
        HD_MEMORY,
        WAN_REPLICATION
    }
}
```

### Tier 2: Feature Flags

Features are organized into **logical groups** for easier management. Groups can be enabled/disabled as a unit, with individual feature overrides available.

```yaml
# application.yml - Feature configuration with groups
framework:
  edition:
    mode: ${HAZELCAST_EDITION_MODE:auto}

  # Feature Groups - enable/disable related features together
  feature-groups:
    # AI/ML capabilities (Vector Store, embeddings)
    ai-ml:
      enabled: ${FEATURE_GROUP_AI_ML:auto}

    # Strong consistency (CP Subsystem, distributed locks)
    consistency:
      enabled: ${FEATURE_GROUP_CONSISTENCY:auto}

    # Data persistence (Hot Restart)
    persistence:
      enabled: ${FEATURE_GROUP_PERSISTENCE:auto}

    # Security features (TLS, authentication, authorization)
    # Typically enabled/disabled together in production
    security:
      enabled: ${FEATURE_GROUP_SECURITY:auto}

    # Performance optimizations (HD Memory)
    performance:
      enabled: ${FEATURE_GROUP_PERFORMANCE:auto}

  # Individual feature overrides (optional)
  # Use 'inherit' to follow group setting, or explicit value to override
  features:
    vector-store:
      enabled: ${FEATURE_VECTOR_STORE:inherit}  # follows ai-ml group
      fallback-behavior: empty-results

    cp-subsystem:
      enabled: ${FEATURE_CP_SUBSYSTEM:inherit}  # follows consistency group
      fallback-behavior: optimistic

    tls:
      enabled: ${FEATURE_TLS:inherit}           # follows security group
      fallback-behavior: warn

    authentication:
      enabled: ${FEATURE_AUTHENTICATION:inherit} # follows security group

    authorization:
      enabled: ${FEATURE_AUTHORIZATION:inherit}  # follows security group
```

**Feature Flag Semantics**:

| Value | Behavior |
|-------|----------|
| `auto` | Enable if Enterprise available, otherwise disable gracefully |
| `true` | Require feature - fail startup if Enterprise unavailable |
| `false` | Disable feature even if Enterprise available |
| `inherit` | Follow the parent group's setting (for individual features) |

**Feature Groups**:

| Group | Features | Typical Use Case |
|-------|----------|------------------|
| `ai-ml` | Vector Store | Product recommendations, semantic search |
| `consistency` | CP Subsystem | Distributed locks, leader election |
| `persistence` | Hot Restart | Data durability without external DB |
| `security` | TLS, Auth, AuthZ | Production deployments |
| `performance` | HD Memory | Large datasets, memory optimization |

### Tier 3: Conditional Beans

```java
/**
 * Custom condition for Enterprise Edition features.
 */
public class OnEnterpriseCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // Check if this specific feature is enabled
        String feature = (String) metadata.getAnnotationAttributes(
            ConditionalOnEnterpriseFeature.class.getName()
        ).get("feature");

        String enabledProp = "framework.features." + feature + ".enabled";
        String enabled = context.getEnvironment().getProperty(enabledProp, "auto");

        if ("false".equals(enabled)) {
            return false;
        }

        // For "auto" or "true", check if Enterprise is available
        EditionDetector detector = context.getBeanFactory().getBean(EditionDetector.class);
        boolean available = detector.isFeatureAvailable(
            EnterpriseFeature.valueOf(feature.toUpperCase().replace("-", "_"))
        );

        if ("true".equals(enabled) && !available) {
            throw new IllegalStateException(
                "Feature '" + feature + "' requires Enterprise Edition but is not available. " +
                "Set HZ_LICENSEKEY or change feature to 'auto' or 'false'."
            );
        }

        return available;
    }
}

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(OnEnterpriseCondition.class)
public @interface ConditionalOnEnterpriseFeature {
    String feature();
}

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(OnCommunityFallbackCondition.class)
public @interface ConditionalOnCommunityFallback {
    String feature();
}
```

### Usage Example: Vector Store

```java
/**
 * Vector Store service interface.
 * Works with both Enterprise (real) and Community (no-op) implementations.
 */
public interface VectorStoreService {
    void storeEmbedding(String id, float[] embedding);
    List<String> findSimilar(String id, int limit);
    boolean isAvailable();
}

/**
 * Enterprise implementation using Hazelcast Vector Store.
 * Only instantiated when Enterprise Edition is available AND feature enabled.
 */
@Service
@ConditionalOnEnterpriseFeature(feature = "vector-store")
@Primary
public class HazelcastVectorStoreService implements VectorStoreService {

    private final VectorCollection<String, String, GenericRecord> vectorCollection;

    public HazelcastVectorStoreService(HazelcastInstance hazelcast) {
        this.vectorCollection = hazelcast.getVectorCollection("product-embeddings");
        logger.info("Vector Store initialized (Enterprise Edition)");
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<String> findSimilar(String id, int limit) {
        return vectorCollection.searchNearestNeighbors(id, limit);
    }
}

/**
 * Community fallback - returns empty results gracefully.
 */
@Service
@ConditionalOnCommunityFallback(feature = "vector-store")
public class NoOpVectorStoreService implements VectorStoreService {

    public NoOpVectorStoreService() {
        logger.info("Vector Store disabled (Community Edition or feature disabled)");
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public List<String> findSimilar(String id, int limit) {
        return Collections.emptyList();
    }
}
```

### License Key Management

**Security Requirements**:
1. License key ONLY from environment variable `HZ_LICENSEKEY`
2. NEVER log the license key (even partially)
3. NEVER include in error messages
4. NEVER persist to files

**License Rotation**: Requires service restart. Runtime reload not supported (simplicity over complexity).

**Docker Compose Pattern**:
```yaml
# docker-compose.yml - Reference environment file
services:
  account-service:
    environment:
      - HZ_LICENSEKEY=${HZ_LICENSEKEY:-}
```

```bash
# .env.enterprise (NOT committed to git, in .gitignore)
HZ_LICENSEKEY=your-license-key-here
```

```bash
# .gitignore
.env.enterprise
*.license
```

**Startup Logging** (safe, no key exposure):
```
2026-02-05 10:15:23.456 INFO  EditionDetector - Hazelcast edition detection:
2026-02-05 10:15:23.456 INFO  EditionDetector -   License environment variable: SET
2026-02-05 10:15:23.457 INFO  EditionDetector -   Edition: ENTERPRISE
2026-02-05 10:15:23.457 INFO  EditionDetector -   Available features: [VECTOR_STORE, CP_SUBSYSTEM, HOT_RESTART]
```

Or for Community:
```
2026-02-05 10:15:23.456 INFO  EditionDetector - Hazelcast edition detection:
2026-02-05 10:15:23.456 INFO  EditionDetector -   License environment variable: NOT SET
2026-02-05 10:15:23.457 INFO  EditionDetector -   Edition: COMMUNITY
2026-02-05 10:15:23.457 INFO  EditionDetector -   All Enterprise features disabled (this is normal for development)
```

## Extensibility to Other Components

This pattern extends to other tiered products in the tech stack:

### Pattern: Edition-Aware Configuration

```yaml
# Extensible pattern for any tiered product
framework:
  editions:
    hazelcast:
      license-env-var: HZ_LICENSEKEY
      features:
        vector-store: { enabled: auto, fallback: empty-results }
        cp-subsystem: { enabled: auto, fallback: optimistic }

    # Future: Database tiers
    postgresql:
      license-env-var: POSTGRES_ENTERPRISE_LICENSE
      features:
        partitioning: { enabled: auto, fallback: standard-tables }
        column-encryption: { enabled: auto, fallback: application-encryption }

    # Future: Monitoring tiers
    grafana:
      license-env-var: GRAFANA_ENTERPRISE_LICENSE
      features:
        enterprise-plugins: { enabled: auto, fallback: community-plugins }
        team-sync: { enabled: auto, fallback: manual-users }
```

### Generic Edition Detector Interface

```java
/**
 * Generic interface for detecting product editions.
 * Implement per-product for consistent edition handling.
 */
public interface ProductEditionDetector<F extends Enum<F>> {

    String getProductName();
    String getLicenseEnvVar();
    boolean isEnterpriseAvailable();
    boolean isFeatureAvailable(F feature);
    Set<F> getAvailableFeatures();
}
```

## Consequences

### Positive

1. **Clear separation of concerns**: Detection, configuration, and implementation are separate
2. **Safe defaults**: Community Edition works out-of-box, Enterprise is additive
3. **No accidental exposure**: License keys never in config files or logs
4. **Easy testing**: Can force edition via configuration override
5. **Self-documenting**: Startup logs show exactly what's available
6. **Extensible**: Pattern works for any tiered product

### Negative

1. **Additional complexity**: Three tiers vs. simple boolean
2. **Bean proliferation**: Primary + Fallback for each Enterprise feature
3. **Testing burden**: Must test both Community and Enterprise paths
4. **Documentation overhead**: Must document which features need Enterprise

### Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| License key leaked in logs | EditionDetector NEVER logs key value, only presence |
| Feature enabled without Enterprise | Startup fails fast with clear error message |
| Enterprise-only code path untested | CI/CD includes Enterprise test job |
| Configuration complexity | Sensible defaults, "auto" handles most cases |

## Implementation Plan

### Phase 1: Core Infrastructure (Days 1-2)
1. `EditionDetector` service
2. `@ConditionalOnEnterpriseFeature` annotation
3. `@ConditionalOnCommunityFallback` annotation
4. Feature flag YAML structure
5. Startup logging

### Phase 2: Vector Store (Day 3)
1. `VectorStoreService` interface
2. `HazelcastVectorStoreService` (Enterprise)
3. `NoOpVectorStoreService` (fallback)
4. Integration tests

### Phase 3: Documentation & Testing (Day 4)
1. Update CLAUDE.md with edition patterns
2. Create edition testing guide
3. CI/CD configuration for dual-edition testing

## References

- [ADR 005: Community Edition Default](./005-community-edition-default.md)
- [Hazelcast Enterprise Features](https://docs.hazelcast.com/hazelcast/latest/enterprise-features)
- [Spring Conditional Beans](https://docs.spring.io/spring-framework/reference/core/beans/java/conditional.html)
