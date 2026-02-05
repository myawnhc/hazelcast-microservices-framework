# Edition Configuration Design Specification

**Status**: Draft
**Version**: 1.0
**Date**: 2026-02-05
**Related ADR**: [ADR 009 - Flexible Edition Configuration](../architecture/adr/009-flexible-edition-configuration.md)

---

## Executive Summary

This document specifies the design for a flexible configuration system that supports running the Hazelcast Microservices Framework with either Community Edition (free, open source) or Enterprise Edition (licensed, premium features).

**Key Design Goals**:
1. Community Edition works fully out-of-box (zero configuration)
2. Enterprise features are opt-in and gracefully degrade when unavailable
3. License keys managed securely via environment variables only
4. Pattern is extensible to other tiered products in the stack

---

## 1. Configuration Hierarchy

### 1.1 Configuration Sources (Priority Order)

```
┌─────────────────────────────────────────────────────────────┐
│  1. Environment Variables (highest priority)                │
│     HZ_LICENSEKEY=xxx                        │
│     FEATURE_VECTOR_STORE=true                               │
├─────────────────────────────────────────────────────────────┤
│  2. System Properties                                        │
│     -Dframework.features.vector-store.enabled=true          │
├─────────────────────────────────────────────────────────────┤
│  3. application-{profile}.yml                                │
│     Profile-specific overrides                               │
├─────────────────────────────────────────────────────────────┤
│  4. application.yml (lowest priority)                        │
│     Default configuration                                    │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Complete Configuration Schema

```yaml
# application.yml - Complete edition configuration schema
framework:
  # ==========================================================
  # EDITION CONFIGURATION
  # ==========================================================
  edition:
    # Which Hazelcast edition to use
    # Values: auto (detect from license), community, enterprise
    # Default: auto
    mode: ${HAZELCAST_EDITION_MODE:auto}

    # Override for testing - force community even if license present
    # ONLY use in tests, not production
    force-community: false

    # License configuration
    license:
      # Environment variable containing the license key
      # The actual key is NEVER stored in config files
      env-var: HZ_LICENSEKEY

      # Behavior when license is required but missing
      # Values: fail (throw exception), warn (log warning), silent
      missing-behavior: warn

      # License rotation: requires service restart (runtime reload not supported)

  # ==========================================================
  # FEATURE GROUPS
  # ==========================================================
  # Features are organized into logical groups. Each group can be
  # enabled/disabled as a unit, or individual features within
  # the group can be controlled separately.
  #
  # Group-level setting applies to all features in group unless
  # individual feature has explicit override.

  feature-groups:
    # ----------------------------------------------------------
    # AI/ML GROUP - Machine learning and AI capabilities
    # ----------------------------------------------------------
    ai-ml:
      enabled: ${FEATURE_GROUP_AI_ML:auto}
      features:
        vector-store:
          enabled: ${FEATURE_VECTOR_STORE:inherit}  # inherit from group
          fallback-behavior: empty-results
          config:
            dimension: 384
            index-type: HNSW
            max-connections: 16
            ef-construction: 200
            collection-name: product-embeddings

    # ----------------------------------------------------------
    # CONSISTENCY GROUP - Strong consistency primitives
    # ----------------------------------------------------------
    consistency:
      enabled: ${FEATURE_GROUP_CONSISTENCY:auto}
      features:
        cp-subsystem:
          enabled: ${FEATURE_CP_SUBSYSTEM:inherit}
          fallback-behavior: optimistic
          config:
            member-count: 3
            group-name: ecommerce-cp
            session-ttl-seconds: 300
            heartbeat-interval-seconds: 5

    # ----------------------------------------------------------
    # PERSISTENCE GROUP - Data durability features
    # ----------------------------------------------------------
    persistence:
      enabled: ${FEATURE_GROUP_PERSISTENCE:auto}
      features:
        hot-restart:
          enabled: ${FEATURE_HOT_RESTART:inherit}
          fallback-behavior: mapstore
          config:
            base-dir: ${HAZELCAST_HOT_RESTART_DIR:/data/hazelcast/hot-restart}
            parallelism: 4
            validation-timeout-seconds: 120
            data-load-timeout-seconds: 900

    # ----------------------------------------------------------
    # SECURITY GROUP - All security-related features
    # Grouped together since they're typically enabled/disabled together
    # ----------------------------------------------------------
    security:
      enabled: ${FEATURE_GROUP_SECURITY:auto}
      features:
        tls:
          enabled: ${FEATURE_TLS:inherit}
          fallback-behavior: warn
          config:
            protocol: TLSv1.3
            keystore-path: ${HAZELCAST_TLS_KEYSTORE:}
            keystore-password-env: HAZELCAST_TLS_KEYSTORE_PASSWORD
            truststore-path: ${HAZELCAST_TLS_TRUSTSTORE:}
            truststore-password-env: HAZELCAST_TLS_TRUSTSTORE_PASSWORD
            mutual-auth: REQUIRED

        authentication:
          enabled: ${FEATURE_AUTHENTICATION:inherit}
          fallback-behavior: warn
          config:
            # JAAS login module configuration
            login-module: com.hazelcast.security.loginmodule.ClusterLoginModule

        authorization:
          enabled: ${FEATURE_AUTHORIZATION:inherit}
          fallback-behavior: warn
          config:
            # Permission policy
            policy-class: com.hazelcast.security.IPermissionPolicy

    # ----------------------------------------------------------
    # PERFORMANCE GROUP - Performance optimization features
    # ----------------------------------------------------------
    performance:
      enabled: ${FEATURE_GROUP_PERFORMANCE:auto}
      features:
        hd-memory:
          enabled: ${FEATURE_HD_MEMORY:inherit}
          fallback-behavior: on-heap
          config:
            size: ${HAZELCAST_HD_MEMORY_SIZE:1g}
            allocator-type: POOLED
            min-block-size: 16
            page-size: 4194304

  # ==========================================================
  # FLAT FEATURE ACCESS (alternative to groups)
  # ==========================================================
  # For simple cases, features can also be accessed directly
  # without group hierarchy. These map to the grouped features above.
  features:
    vector-store: ${FEATURE_VECTOR_STORE:auto}
    cp-subsystem: ${FEATURE_CP_SUBSYSTEM:auto}
    hot-restart: ${FEATURE_HOT_RESTART:auto}
    tls: ${FEATURE_TLS:auto}
    hd-memory: ${FEATURE_HD_MEMORY:auto}

  # ==========================================================
  # OBSERVABILITY FOR EDITION FEATURES
  # ==========================================================
  observability:
    # Log edition detection at startup
    log-edition-status: true
    # Log feature availability at startup
    log-feature-status: true
    # Expose edition info in /actuator/info (DEFAULT: true)
    # This is enabled by default so operators always know which edition is running
    expose-in-actuator: true
    # Metrics for feature usage
    metrics-enabled: true

# ==========================================================
# SPRING PROFILES FOR DIFFERENT DEPLOYMENT SCENARIOS
# ==========================================================
---
spring:
  config:
    activate:
      on-profile: development
framework:
  edition:
    mode: auto
  features:
    vector-store:
      enabled: auto
      fallback-behavior: empty-results

---
spring:
  config:
    activate:
      on-profile: demo
framework:
  edition:
    mode: community
  features:
    vector-store:
      enabled: false  # Disable for demos to keep simple

---
spring:
  config:
    activate:
      on-profile: enterprise
framework:
  edition:
    mode: enterprise
    license:
      missing-behavior: fail  # Fail fast if no license
  features:
    vector-store:
      enabled: true  # Require feature
```

---

## 2. Component Design

### 2.1 EditionDetector Service

```java
package com.theyawns.framework.edition;

import com.hazelcast.core.HazelcastInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.EnumSet;
import java.util.Set;

/**
 * Central service for detecting and reporting Hazelcast edition capabilities.
 *
 * <p>This is the <strong>single source of truth</strong> for edition detection.
 * All components should inject this service rather than detecting edition themselves.
 *
 * <p><strong>Security:</strong> This class handles license keys securely:
 * <ul>
 *   <li>License is read ONLY from environment variable</li>
 *   <li>License value is NEVER logged, even partially</li>
 *   <li>License value is NEVER included in error messages</li>
 * </ul>
 *
 * @author Generated by Claude Code
 * @since 2.0
 * @see EnterpriseFeature
 */
@Service
public class EditionDetector {

    private static final Logger logger = LoggerFactory.getLogger(EditionDetector.class);

    private final EditionProperties properties;
    private final HazelcastInstance hazelcast;

    private Edition detectedEdition;
    private Set<EnterpriseFeature> availableFeatures;
    private boolean licensePresent;

    /**
     * Available Hazelcast editions.
     */
    public enum Edition {
        COMMUNITY,
        ENTERPRISE
    }

    /**
     * Enterprise-only features that can be detected.
     */
    public enum EnterpriseFeature {
        /** Vector Store for similarity search */
        VECTOR_STORE("Vector Store", "Similarity search for recommendations"),

        /** CP Subsystem for strong consistency */
        CP_SUBSYSTEM("CP Subsystem", "Strong consistency primitives"),

        /** Hot Restart for persistence */
        HOT_RESTART("Hot Restart", "In-memory data persistence"),

        /** TLS for encrypted communication */
        TLS("TLS Security", "Encrypted cluster communication"),

        /** HD Memory for off-heap storage */
        HD_MEMORY("HD Memory", "Off-heap storage for large datasets"),

        /** WAN Replication for multi-datacenter */
        WAN_REPLICATION("WAN Replication", "Multi-datacenter replication");

        private final String displayName;
        private final String description;

        EnterpriseFeature(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }
    }

    public EditionDetector(EditionProperties properties, HazelcastInstance hazelcast) {
        this.properties = properties;
        this.hazelcast = hazelcast;
    }

    @PostConstruct
    public void detectEdition() {
        // Check for license in environment variable
        String envVar = properties.getLicense().getEnvVar();
        String license = System.getenv(envVar);
        this.licensePresent = license != null && !license.isBlank();

        // Detect edition based on mode setting
        this.detectedEdition = detectEditionFromMode();

        // Detect available features
        this.availableFeatures = detectAvailableFeatures();

        // Log status (safely, no license exposure)
        logEditionStatus();
    }

    private Edition detectEditionFromMode() {
        String mode = properties.getMode();

        return switch (mode.toLowerCase()) {
            case "community" -> Edition.COMMUNITY;
            case "enterprise" -> {
                if (!licensePresent) {
                    handleMissingLicense();
                }
                yield Edition.ENTERPRISE;
            }
            case "auto" -> licensePresent ? Edition.ENTERPRISE : Edition.COMMUNITY;
            default -> {
                logger.warn("Unknown edition mode '{}', defaulting to 'auto'", mode);
                yield licensePresent ? Edition.ENTERPRISE : Edition.COMMUNITY;
            }
        };
    }

    private void handleMissingLicense() {
        String behavior = properties.getLicense().getMissingBehavior();

        switch (behavior.toLowerCase()) {
            case "fail" -> throw new EditionConfigurationException(
                "Enterprise Edition requested but license not found. " +
                "Set environment variable: " + properties.getLicense().getEnvVar()
            );
            case "warn" -> logger.warn(
                "Enterprise Edition requested but license not found in {}. " +
                "Falling back to Community Edition.",
                properties.getLicense().getEnvVar()
            );
            case "silent" -> {
                // No logging
            }
        }
    }

    private Set<EnterpriseFeature> detectAvailableFeatures() {
        if (detectedEdition == Edition.COMMUNITY) {
            return EnumSet.noneOf(EnterpriseFeature.class);
        }

        Set<EnterpriseFeature> features = EnumSet.noneOf(EnterpriseFeature.class);

        // Detect each feature availability
        // These checks are runtime detection, not just configuration
        if (isVectorStoreAvailable()) {
            features.add(EnterpriseFeature.VECTOR_STORE);
        }
        if (isCpSubsystemAvailable()) {
            features.add(EnterpriseFeature.CP_SUBSYSTEM);
        }
        if (isHotRestartAvailable()) {
            features.add(EnterpriseFeature.HOT_RESTART);
        }
        if (isTlsAvailable()) {
            features.add(EnterpriseFeature.TLS);
        }
        if (isHdMemoryAvailable()) {
            features.add(EnterpriseFeature.HD_MEMORY);
        }

        return features;
    }

    private boolean isVectorStoreAvailable() {
        try {
            // Check if VectorCollection class is available (Enterprise only)
            Class.forName("com.hazelcast.vector.VectorCollection");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private boolean isCpSubsystemAvailable() {
        try {
            return hazelcast.getCPSubsystem() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isHotRestartAvailable() {
        // Hot Restart availability check
        try {
            return hazelcast.getConfig().getHotRestartPersistenceConfig() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTlsAvailable() {
        // TLS is configured at cluster level
        try {
            return hazelcast.getConfig().getNetworkConfig()
                .getSSLConfig().isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isHdMemoryAvailable() {
        try {
            return hazelcast.getConfig().getNativeMemoryConfig().isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private void logEditionStatus() {
        if (!properties.getObservability().isLogEditionStatus()) {
            return;
        }

        logger.info("=== Hazelcast Edition Detection ===");
        logger.info("  License environment variable ({}): {}",
            properties.getLicense().getEnvVar(),
            licensePresent ? "SET" : "NOT SET");
        logger.info("  Configuration mode: {}", properties.getMode());
        logger.info("  Detected edition: {}", detectedEdition);

        if (detectedEdition == Edition.ENTERPRISE) {
            logger.info("  Available Enterprise features:");
            for (EnterpriseFeature feature : availableFeatures) {
                logger.info("    - {} ({})", feature.getDisplayName(), feature.getDescription());
            }
        } else {
            logger.info("  Running with Community Edition (all core features available)");
            logger.info("  To enable Enterprise features, set: {}",
                properties.getLicense().getEnvVar());
        }
        logger.info("===================================");
    }

    // Public API

    /**
     * Returns the detected Hazelcast edition.
     */
    public Edition getEdition() {
        return detectedEdition;
    }

    /**
     * Returns true if Enterprise Edition is available.
     */
    public boolean isEnterprise() {
        return detectedEdition == Edition.ENTERPRISE;
    }

    /**
     * Returns true if Community Edition is being used.
     */
    public boolean isCommunity() {
        return detectedEdition == Edition.COMMUNITY;
    }

    /**
     * Returns true if the specified Enterprise feature is available.
     */
    public boolean isFeatureAvailable(EnterpriseFeature feature) {
        return availableFeatures.contains(feature);
    }

    /**
     * Returns the set of available Enterprise features.
     * Empty set for Community Edition.
     */
    public Set<EnterpriseFeature> getAvailableFeatures() {
        return EnumSet.copyOf(availableFeatures);
    }

    /**
     * Returns true if license was found in environment.
     */
    public boolean isLicensePresent() {
        return licensePresent;
    }
}
```

### 2.2 Configuration Properties

```java
package com.theyawns.framework.edition;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for edition and feature management.
 */
@ConfigurationProperties(prefix = "framework")
public class EditionProperties {

    private final Edition edition;
    private final Features features;
    private final Observability observability;

    @ConstructorBinding
    public EditionProperties(
            @DefaultValue Edition edition,
            @DefaultValue Features features,
            @DefaultValue Observability observability) {
        this.edition = edition;
        this.features = features;
        this.observability = observability;
    }

    // Nested classes for configuration structure

    public static class Edition {
        private String mode = "auto";
        private boolean forceCommunity = false;
        private License license = new License();

        // Getters and setters
    }

    public static class License {
        private String envVar = "HZ_LICENSEKEY";
        private String missingBehavior = "warn";

        // Getters and setters
    }

    public static class Features {
        private FeatureConfig vectorStore = new FeatureConfig();
        private FeatureConfig cpSubsystem = new FeatureConfig();
        private FeatureConfig hotRestart = new FeatureConfig();
        private FeatureConfig tls = new FeatureConfig();
        private FeatureConfig hdMemory = new FeatureConfig();

        // Getters and setters
    }

    public static class FeatureConfig {
        private String enabled = "auto";
        private String fallbackBehavior = "graceful";

        // Getters and setters
    }

    public static class Observability {
        private boolean logEditionStatus = true;
        private boolean logFeatureStatus = true;
        private boolean exposeInActuator = true;
        private boolean metricsEnabled = true;

        // Getters and setters
    }

    // Getters
    public Edition getEdition() { return edition; }
    public Features getFeatures() { return features; }
    public Observability getObservability() { return observability; }

    // Convenience methods
    public String getMode() { return edition.getMode(); }
    public License getLicense() { return edition.getLicense(); }
}
```

### 2.3 Conditional Annotations

```java
package com.theyawns.framework.edition;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.*;
import java.util.Map;

/**
 * Marks a bean that requires a specific Enterprise feature.
 * The bean is only created if:
 * 1. Enterprise Edition is available, AND
 * 2. The specific feature is enabled in configuration
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnEnterpriseFeatureCondition.class)
public @interface ConditionalOnEnterpriseFeature {

    /**
     * The Enterprise feature required by this bean.
     */
    EditionDetector.EnterpriseFeature value();
}

/**
 * Marks a bean as the Community Edition fallback for an Enterprise feature.
 * The bean is created when the Enterprise feature is NOT available.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnCommunityFallbackCondition.class)
public @interface ConditionalOnCommunityFallback {

    /**
     * The Enterprise feature this bean provides fallback for.
     */
    EditionDetector.EnterpriseFeature value();
}

/**
 * Condition implementation for @ConditionalOnEnterpriseFeature.
 */
class OnEnterpriseFeatureCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(
            ConditionalOnEnterpriseFeature.class.getName()
        );

        if (attrs == null) {
            return false;
        }

        EditionDetector.EnterpriseFeature feature =
            (EditionDetector.EnterpriseFeature) attrs.get("value");

        // Check feature configuration
        String featureKey = feature.name().toLowerCase().replace("_", "-");
        String enabledProp = "framework.features." + featureKey + ".enabled";
        String enabled = context.getEnvironment().getProperty(enabledProp, "auto");

        if ("false".equalsIgnoreCase(enabled)) {
            return false;
        }

        // For auto or true, need to check runtime availability
        // This requires deferred evaluation after EditionDetector is initialized
        // Use a BeanFactoryPostProcessor or lazy initialization pattern

        return checkFeatureAvailability(context, feature, enabled);
    }

    private boolean checkFeatureAvailability(
            ConditionContext context,
            EditionDetector.EnterpriseFeature feature,
            String enabled) {

        // Check if license is present via environment
        String licenseEnvVar = context.getEnvironment().getProperty(
            "framework.edition.license.env-var",
            "HZ_LICENSEKEY"
        );
        String license = System.getenv(licenseEnvVar);
        boolean licensePresent = license != null && !license.isBlank();

        // Check edition mode
        String mode = context.getEnvironment().getProperty(
            "framework.edition.mode", "auto"
        );

        boolean isEnterprise = switch (mode.toLowerCase()) {
            case "enterprise" -> true;
            case "community" -> false;
            case "auto" -> licensePresent;
            default -> licensePresent;
        };

        if ("true".equalsIgnoreCase(enabled) && !isEnterprise) {
            throw new EditionConfigurationException(
                String.format(
                    "Feature '%s' is set to 'true' (required) but Enterprise Edition " +
                    "is not available. Either set %s environment variable or change " +
                    "feature setting to 'auto' or 'false'.",
                    feature.name(), licenseEnvVar
                )
            );
        }

        return isEnterprise;
    }
}

/**
 * Condition implementation for @ConditionalOnCommunityFallback.
 */
class OnCommunityFallbackCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(
            ConditionalOnCommunityFallback.class.getName()
        );

        if (attrs == null) {
            return false;
        }

        EditionDetector.EnterpriseFeature feature =
            (EditionDetector.EnterpriseFeature) attrs.get("value");

        // Check if feature is explicitly disabled
        String featureKey = feature.name().toLowerCase().replace("_", "-");
        String enabledProp = "framework.features." + featureKey + ".enabled";
        String enabled = context.getEnvironment().getProperty(enabledProp, "auto");

        if ("false".equalsIgnoreCase(enabled)) {
            // Feature disabled, use fallback
            return true;
        }

        // Check if Enterprise is NOT available
        String licenseEnvVar = context.getEnvironment().getProperty(
            "framework.edition.license.env-var",
            "HZ_LICENSEKEY"
        );
        String license = System.getenv(licenseEnvVar);
        boolean licensePresent = license != null && !license.isBlank();

        String mode = context.getEnvironment().getProperty(
            "framework.edition.mode", "auto"
        );

        boolean isEnterprise = switch (mode.toLowerCase()) {
            case "enterprise" -> licensePresent; // Only enterprise if license present
            case "community" -> false;
            case "auto" -> licensePresent;
            default -> licensePresent;
        };

        // Fallback is needed when Enterprise is NOT available
        return !isEnterprise;
    }
}
```

---

## 3. Implementation Examples

### 3.1 Vector Store Feature

```java
// Interface - used by all consumers
package com.theyawns.framework.vectorstore;

public interface VectorStoreService {
    void storeEmbedding(String id, float[] embedding, Map<String, Object> metadata);
    List<SimilarityResult> findSimilar(float[] queryVector, int limit);
    List<SimilarityResult> findSimilarById(String id, int limit);
    boolean isAvailable();
    String getImplementationType();
}

public record SimilarityResult(String id, float score, Map<String, Object> metadata) {}

// Enterprise Implementation
@Service
@ConditionalOnEnterpriseFeature(EnterpriseFeature.VECTOR_STORE)
@Primary
public class HazelcastVectorStoreService implements VectorStoreService {

    private static final Logger logger = LoggerFactory.getLogger(HazelcastVectorStoreService.class);

    private final VectorCollection<String, VectorDocument> collection;

    public HazelcastVectorStoreService(
            HazelcastInstance hazelcast,
            VectorStoreProperties properties) {

        VectorCollectionConfig config = new VectorCollectionConfig(properties.getCollectionName())
            .addVectorIndexConfig(new VectorIndexConfig()
                .setName("embedding-index")
                .setDimension(properties.getDimension())
                .setMetric(Metric.COSINE)
                .setIndexConfig(IndexConfig.HNSW()
                    .setMaxConnectionsPerElement(properties.getMaxConnections())
                    .setEfConstruction(properties.getEfConstruction())));

        hazelcast.getConfig().addVectorCollectionConfig(config);
        this.collection = hazelcast.getVectorCollection(properties.getCollectionName());

        logger.info("Vector Store initialized (Hazelcast Enterprise)");
        logger.info("  Collection: {}", properties.getCollectionName());
        logger.info("  Dimension: {}", properties.getDimension());
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getImplementationType() {
        return "Hazelcast Enterprise Vector Store";
    }

    @Override
    public List<SimilarityResult> findSimilar(float[] queryVector, int limit) {
        SearchResults<String, VectorDocument> results = collection.searchNearestNeighbors(
            VectorValues.of(queryVector),
            SearchOptions.builder().limit(limit).includePayload().build()
        );

        return results.stream()
            .map(r -> new SimilarityResult(r.getKey(), r.getScore(), r.getPayload()))
            .toList();
    }
}

// Community Fallback
@Service
@ConditionalOnCommunityFallback(EnterpriseFeature.VECTOR_STORE)
public class NoOpVectorStoreService implements VectorStoreService {

    private static final Logger logger = LoggerFactory.getLogger(NoOpVectorStoreService.class);

    public NoOpVectorStoreService() {
        logger.info("Vector Store fallback initialized (Community Edition)");
        logger.info("  Similarity search will return empty results");
        logger.info("  To enable Vector Store, set HZ_LICENSEKEY");
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String getImplementationType() {
        return "No-Op (Community Edition)";
    }

    @Override
    public List<SimilarityResult> findSimilar(float[] queryVector, int limit) {
        logger.debug("Vector search called but Vector Store not available (Community Edition)");
        return Collections.emptyList();
    }
}
```

### 3.2 Controller Using Edition-Aware Service

```java
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final VectorStoreService vectorStoreService;

    @GetMapping("/{id}/similar")
    @Operation(
        summary = "Find similar products",
        description = "Returns products similar to the specified product. " +
                      "Requires Hazelcast Enterprise for full functionality. " +
                      "Returns empty list with Community Edition."
    )
    public ResponseEntity<SimilarProductsResponse> findSimilarProducts(
            @PathVariable String id,
            @RequestParam(defaultValue = "5") int limit) {

        SimilarProductsResponse response = new SimilarProductsResponse();
        response.setProductId(id);
        response.setVectorStoreAvailable(vectorStoreService.isAvailable());
        response.setImplementation(vectorStoreService.getImplementationType());

        if (vectorStoreService.isAvailable()) {
            List<SimilarityResult> results = vectorStoreService.findSimilarById(id, limit);
            response.setSimilarProducts(
                results.stream()
                    .map(r -> productService.getProduct(r.id()).orElse(null))
                    .filter(Objects::nonNull)
                    .toList()
            );
        } else {
            // Graceful degradation - return empty list with explanation
            response.setSimilarProducts(Collections.emptyList());
            response.setMessage("Product recommendations require Hazelcast Enterprise Edition");
        }

        return ResponseEntity.ok(response);
    }
}
```

---

## 4. Actuator Integration

### 4.1 Edition Info Endpoint

```java
@Component
@ConditionalOnProperty(name = "framework.observability.expose-in-actuator", havingValue = "true", matchIfMissing = true)
public class EditionInfoContributor implements InfoContributor {

    private final EditionDetector editionDetector;
    private final EditionProperties properties;

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> editionInfo = new LinkedHashMap<>();

        editionInfo.put("edition", editionDetector.getEdition().name());
        editionInfo.put("licenseConfigured", editionDetector.isLicensePresent());

        // Available features
        List<Map<String, String>> features = editionDetector.getAvailableFeatures().stream()
            .map(f -> Map.of(
                "name", f.name(),
                "displayName", f.getDisplayName(),
                "description", f.getDescription()
            ))
            .toList();
        editionInfo.put("availableFeatures", features);

        // Feature configuration
        Map<String, String> featureStatus = new LinkedHashMap<>();
        featureStatus.put("vectorStore", getFeatureStatus(EnterpriseFeature.VECTOR_STORE));
        featureStatus.put("cpSubsystem", getFeatureStatus(EnterpriseFeature.CP_SUBSYSTEM));
        featureStatus.put("hotRestart", getFeatureStatus(EnterpriseFeature.HOT_RESTART));
        editionInfo.put("featureConfiguration", featureStatus);

        builder.withDetail("hazelcast", editionInfo);
    }

    private String getFeatureStatus(EnterpriseFeature feature) {
        boolean available = editionDetector.isFeatureAvailable(feature);
        String configured = getConfiguredValue(feature);

        return switch (configured.toLowerCase()) {
            case "true" -> available ? "ENABLED (required)" : "ERROR (required but unavailable)";
            case "false" -> "DISABLED (explicitly)";
            case "auto" -> available ? "ENABLED (auto-detected)" : "DISABLED (not available)";
            default -> "UNKNOWN";
        };
    }
}
```

**Example /actuator/info Response**:
```json
{
  "hazelcast": {
    "edition": "ENTERPRISE",
    "licenseConfigured": true,
    "availableFeatures": [
      {
        "name": "VECTOR_STORE",
        "displayName": "Vector Store",
        "description": "Similarity search for recommendations"
      },
      {
        "name": "CP_SUBSYSTEM",
        "displayName": "CP Subsystem",
        "description": "Strong consistency primitives"
      }
    ],
    "featureConfiguration": {
      "vectorStore": "ENABLED (auto-detected)",
      "cpSubsystem": "DISABLED (explicitly)",
      "hotRestart": "DISABLED (not available)"
    }
  }
}
```

---

## 5. Testing Strategy

### 5.1 Test Configuration

```java
// Test with Community Edition (no license)
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "framework.edition.mode=community"
})
class CommunityEditionTest {

    @Autowired
    private VectorStoreService vectorStoreService;

    @Test
    void vectorStoreShouldReturnFallback() {
        assertThat(vectorStoreService.isAvailable()).isFalse();
        assertThat(vectorStoreService.getImplementationType())
            .isEqualTo("No-Op (Community Edition)");
        assertThat(vectorStoreService.findSimilar(new float[]{1,2,3}, 5))
            .isEmpty();
    }
}

// Test with Enterprise Edition (mocked license)
@SpringBootTest
@ActiveProfiles("test")
class EnterpriseEditionTest {

    @BeforeAll
    static void setLicense() {
        // For testing only - use test license
        System.setProperty("HZ_LICENSEKEY", "test-license-key");
    }

    @Autowired
    private VectorStoreService vectorStoreService;

    @Test
    void vectorStoreShouldBeAvailable() {
        assertThat(vectorStoreService.isAvailable()).isTrue();
        assertThat(vectorStoreService.getImplementationType())
            .contains("Enterprise");
    }
}
```

### 5.2 CI/CD Configuration

**Testing Strategy**:
- **Community tests**: Run on every commit (fast, no license needed)
- **Enterprise tests**: Run when:
  - Working on Enterprise features (detected by file changes or label)
  - End of day (scheduled nightly build)
  - Pre-release (before any tag/release)

```yaml
# .github/workflows/test.yml
name: Test Suite

on:
  push:
    branches: [main, develop, 'feature/**']
  pull_request:
    branches: [main]
  schedule:
    # Run Enterprise tests nightly at end of day (6 PM local)
    - cron: '0 23 * * *'
  workflow_dispatch:
    inputs:
      run_enterprise_tests:
        description: 'Run Enterprise Edition tests'
        required: false
        default: 'false'
        type: boolean

jobs:
  test-community:
    name: Test (Community Edition)
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Run Community Edition tests
        run: mvn test -Dspring.profiles.active=test

  detect-enterprise-changes:
    name: Detect Enterprise Feature Changes
    runs-on: ubuntu-latest
    outputs:
      enterprise_changed: ${{ steps.check.outputs.enterprise_changed }}
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - id: check
        name: Check for Enterprise feature changes
        run: |
          # Files/paths that indicate Enterprise feature work
          ENTERPRISE_PATHS=(
            "framework-core/src/main/java/com/theyawns/framework/vectorstore"
            "framework-core/src/main/java/com/theyawns/framework/edition"
            "**/VectorStore*.java"
            "**/Enterprise*.java"
            "**/CpSubsystem*.java"
            "**/HotRestart*.java"
          )

          CHANGED_FILES=$(git diff --name-only ${{ github.event.before }} ${{ github.sha }} 2>/dev/null || echo "")

          for pattern in "${ENTERPRISE_PATHS[@]}"; do
            if echo "$CHANGED_FILES" | grep -q "$pattern"; then
              echo "enterprise_changed=true" >> $GITHUB_OUTPUT
              exit 0
            fi
          done

          echo "enterprise_changed=false" >> $GITHUB_OUTPUT

  test-enterprise:
    name: Test (Enterprise Edition)
    runs-on: ubuntu-latest
    needs: [test-community, detect-enterprise-changes]
    # Run Enterprise tests when:
    # 1. Enterprise features changed, OR
    # 2. Scheduled nightly run, OR
    # 3. Manual trigger with enterprise flag, OR
    # 4. PR has 'enterprise-tests' label
    if: |
      needs.detect-enterprise-changes.outputs.enterprise_changed == 'true' ||
      github.event_name == 'schedule' ||
      github.event.inputs.run_enterprise_tests == 'true' ||
      contains(github.event.pull_request.labels.*.name, 'enterprise-tests')
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Run Enterprise Edition tests
        env:
          HZ_LICENSEKEY: ${{ secrets.HZ_LICENSEKEY }}
        run: |
          echo "Running Enterprise Edition tests..."
          mvn test -Dspring.profiles.active=test,enterprise -Dframework.edition.mode=enterprise
      - name: Enterprise test summary
        if: always()
        run: |
          echo "### Enterprise Test Results" >> $GITHUB_STEP_SUMMARY
          echo "Trigger: ${{ github.event_name }}" >> $GITHUB_STEP_SUMMARY
          echo "Enterprise changes detected: ${{ needs.detect-enterprise-changes.outputs.enterprise_changed }}" >> $GITHUB_STEP_SUMMARY
```

---

## 6. Migration Guide

### 6.1 Migrating from Phase 1 Configuration

Phase 1 services don't have edition configuration. Migration steps:

1. **Add EditionProperties to framework-core**
2. **Update application.yml** with new `framework:` section
3. **No changes required for existing services** - defaults work

### 6.2 Docker Compose Changes

```yaml
# docker-compose.yml
version: '3.8'

services:
  account-service:
    build: ./account-service
    environment:
      # Existing config
      - HAZELCAST_CLUSTER_NAME=ecommerce-cluster
      - HAZELCAST_CLUSTER_MEMBERS=hazelcast-1:5701,hazelcast-2:5701,hazelcast-3:5701

      # NEW: Enterprise license (from .env.enterprise if exists)
      - HZ_LICENSEKEY=${HZ_LICENSEKEY:-}

      # NEW: Feature overrides (optional)
      - FEATURE_VECTOR_STORE=${FEATURE_VECTOR_STORE:-auto}

# .env (committed to git - defaults)
HAZELCAST_CLUSTER_NAME=ecommerce-cluster
FEATURE_VECTOR_STORE=auto

# .env.enterprise (NOT committed - contains license)
HZ_LICENSEKEY=your-key-here
```

---

## 7. Security Considerations

### 7.1 License Key Protection

| Threat | Mitigation |
|--------|------------|
| Key in config file | YAML references env var, never contains key |
| Key in logs | EditionDetector logs presence, never value |
| Key in error messages | Exceptions mention env var name, not value |
| Key in Git history | .gitignore includes *.license, .env.enterprise |
| Key exposed via API | /actuator/info shows "configured: true/false" only |
| Key in heap dump | Key read once at startup, not stored in field |

### 7.2 .gitignore Additions

```gitignore
# Enterprise license files
*.license
.env.enterprise
.env.*.enterprise

# IDE may create these
**/secrets/
**/credentials/
```

---

## 8. Appendix

### 8.1 Environment Variable Reference

**Edition & License**:

| Variable | Purpose | Default |
|----------|---------|---------|
| `HZ_LICENSEKEY` | Hazelcast Enterprise license key | (none) |
| `HAZELCAST_EDITION_MODE` | Force edition: auto, community, enterprise | auto |

**Feature Groups** (enable/disable entire groups):

| Variable | Purpose | Default |
|----------|---------|---------|
| `FEATURE_GROUP_AI_ML` | AI/ML features (Vector Store) | auto |
| `FEATURE_GROUP_CONSISTENCY` | Consistency features (CP Subsystem) | auto |
| `FEATURE_GROUP_PERSISTENCE` | Persistence features (Hot Restart) | auto |
| `FEATURE_GROUP_SECURITY` | Security features (TLS, Auth, AuthZ) | auto |
| `FEATURE_GROUP_PERFORMANCE` | Performance features (HD Memory) | auto |

**Individual Features** (override group settings):

| Variable | Purpose | Default |
|----------|---------|---------|
| `FEATURE_VECTOR_STORE` | Vector Store: auto, true, false, inherit | inherit |
| `FEATURE_CP_SUBSYSTEM` | CP Subsystem: auto, true, false, inherit | inherit |
| `FEATURE_HOT_RESTART` | Hot Restart: auto, true, false, inherit | inherit |
| `FEATURE_TLS` | TLS encryption: auto, true, false, inherit | inherit |
| `FEATURE_AUTHENTICATION` | Authentication: auto, true, false, inherit | inherit |
| `FEATURE_AUTHORIZATION` | Authorization: auto, true, false, inherit | inherit |
| `FEATURE_HD_MEMORY` | HD Memory: auto, true, false, inherit | inherit |

### 8.2 Startup Log Examples

**Community Edition (no license)**:
```
=== Hazelcast Edition Detection ===
  License environment variable (HZ_LICENSEKEY): NOT SET
  Configuration mode: auto
  Detected edition: COMMUNITY
  Running with Community Edition (all core features available)
  To enable Enterprise features, set: HZ_LICENSEKEY
===================================

Vector Store fallback initialized (Community Edition)
  Similarity search will return empty results
  To enable Vector Store, set HZ_LICENSEKEY
```

**Enterprise Edition (with license)**:
```
=== Hazelcast Edition Detection ===
  License environment variable (HZ_LICENSEKEY): SET
  Configuration mode: auto
  Detected edition: ENTERPRISE
  Available Enterprise features:
    - Vector Store (Similarity search for recommendations)
    - CP Subsystem (Strong consistency primitives)
===================================

Vector Store initialized (Hazelcast Enterprise)
  Collection: product-embeddings
  Dimension: 384
```

---

*Document Version: 1.0*
*Last Updated: 2026-02-05*
