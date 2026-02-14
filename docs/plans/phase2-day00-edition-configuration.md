# Edition Configuration Implementation Plan (Phase 2 - Day 0)

## Context

Phase 1 is complete (Days 1-14). Day 15 introduces Vector Store integration, which requires the Edition Configuration system to detect Community vs Enterprise and conditionally load beans. This plan implements the "Day 0" prerequisite from the phase 2 plan, based on the approved design in `docs/design/edition-configuration-design.md` and `docs/architecture/adr/009-flexible-edition-configuration.md`.

## Scope

Implement the edition detection and feature flag infrastructure in `framework-core`. This provides the foundation for Day 15 (Vector Store) and all future Enterprise-optional features.

**In scope:** EditionDetector, EditionProperties, conditional annotations, auto-configuration, actuator info, tests, YAML/Docker/.gitignore updates.

**Deferred:** Feature-specific config values (e.g., vector store dimension, CP group name) -- those belong to the features themselves (Day 15+). CI/CD GitHub Actions workflow -- no CI infrastructure currently exists in the repo.

---

## Files to Create (8 new Java files + 1 test)

All under `framework-core/src/main/java/com/theyawns/framework/edition/`:

### 1. `EditionProperties.java`
- `@ConfigurationProperties(prefix = "framework")`
- Nested classes: `EditionConfig`, `LicenseConfig`, `FeatureConfig`, `ObservabilityConfig`
- Binds `framework.edition.mode`, `framework.edition.license.*`, `framework.features.*`, `framework.observability.*`
- Flat feature access: `framework.features.vector-store`, `framework.features.cp-subsystem`, etc.
- Each feature has `enabled` (default: `auto`) and `fallback-behavior` (default: `graceful`)
- Pattern follows `SagaTimeoutConfig.java` (field defaults, getters/setters, no-arg + annotated constructors)

### 2. `EditionDetector.java`
- `@Service`, constructor injection of `EditionProperties` and `HazelcastInstance`
- `@PostConstruct` performs detection
- Inner enum `Edition` { COMMUNITY, ENTERPRISE }
- Inner enum `EnterpriseFeature` { VECTOR_STORE, CP_SUBSYSTEM, HOT_RESTART, TLS, HD_MEMORY, WAN_REPLICATION } with displayName/description
- Detection logic: check `mode` setting + license env var presence
- Runtime feature detection via classpath/config checks (Vector Store class, CP subsystem, SSL config, etc.)
- Startup logging: logs edition, license presence (never the value), available features
- Public API: `getEdition()`, `isEnterprise()`, `isCommunity()`, `isFeatureAvailable(feature)`, `getAvailableFeatures()`, `isLicensePresent()`

### 3. `EditionConfigurationException.java`
- Extends `RuntimeException`
- Used when `mode=enterprise` but no license, or `feature=true` but Enterprise unavailable

### 4. `ConditionalOnEnterpriseFeature.java` (annotation)
- `@Conditional(OnEnterpriseFeatureCondition.class)`
- Attribute: `EnterpriseFeature value()`
- Bean created only when Enterprise available AND feature enabled

### 5. `ConditionalOnCommunityFallback.java` (annotation)
- `@Conditional(OnCommunityFallbackCondition.class)`
- Attribute: `EnterpriseFeature value()`
- Bean created when the Enterprise feature is NOT available (fallback)

### 6. `OnEnterpriseFeatureCondition.java`
- Implements `Condition`
- Checks at context startup (before beans exist): reads `framework.edition.mode` + license env var from `context.getEnvironment()` and `System.getenv()`
- Derives feature property key from enum name: `VECTOR_STORE` -> `framework.features.vector-store.enabled`
- If `enabled=false` -> no match. If `enabled=true` and no Enterprise -> throw `EditionConfigurationException`. If `enabled=auto` -> match only if Enterprise detected

### 7. `OnCommunityFallbackCondition.java`
- Implements `Condition`
- Inverse logic of `OnEnterpriseFeatureCondition`
- If `enabled=false` -> match (feature disabled, use fallback). If Enterprise not available -> match

### 8. `EditionInfoContributor.java`
- Implements `InfoContributor` (Spring Boot Actuator)
- `@ConditionalOnProperty(name = "framework.observability.expose-in-actuator", havingValue = "true", matchIfMissing = true)`
- Exposes edition, license configured (boolean), available features, feature status at `/actuator/info`
- Never exposes actual license key

### 9. `EditionAutoConfiguration.java`
- `@Configuration`
- `@EnableConfigurationProperties(EditionProperties.class)`
- `@ConditionalOnClass(HazelcastInstance.class)`
- Creates `EditionDetector` bean and `EditionInfoContributor` bean
- Registered in `AutoConfiguration.imports`

## Files to Create (Tests)

Under `framework-core/src/test/java/com/theyawns/framework/edition/`:

### 10. `EditionDetectorTest.java`
- Unit test for detection logic with mocked HazelcastInstance
- Tests: community mode, enterprise mode, auto mode with/without license, feature detection

### 11. `EditionAutoConfigurationTest.java`
- Integration test using `ApplicationContextRunner` (same pattern as `SagaTimeoutAutoConfigurationTest`)
- Tests: bean creation, property binding, condition matching for Enterprise/Community, info contributor

### 12. `EditionPropertiesTest.java`
- Unit test for property defaults and binding

## Files to Modify

### 13. `AutoConfiguration.imports`
- **File:** `framework-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Add: `com.theyawns.framework.edition.EditionAutoConfiguration`

### 14. `.gitignore`
- Add license file patterns: `*.license`, `.env.enterprise`, `.env.*.enterprise`, `**/secrets/`, `**/credentials/`

### 15. `docker/docker-compose.yml`
- Add `HZ_LICENSEKEY=${HZ_LICENSEKEY:-}` to environment for all service containers

### 16. Service `application.yml` files (all 4 services)
- Add `framework:` config section with defaults:
  ```yaml
  framework:
    edition:
      mode: ${HAZELCAST_EDITION_MODE:auto}
      license:
        env-var: HZ_LICENSEKEY
        missing-behavior: warn
    features:
      vector-store:
        enabled: ${FEATURE_VECTOR_STORE:auto}
        fallback-behavior: empty-results
      cp-subsystem:
        enabled: ${FEATURE_CP_SUBSYSTEM:auto}
        fallback-behavior: optimistic
    observability:
      log-edition-status: true
      log-feature-status: true
      expose-in-actuator: true
  ```

---

## Implementation Order

1. **EditionConfigurationException** (simple, no deps)
2. **EditionProperties** (configuration binding)
3. **EditionDetector** (core detection logic)
4. **OnEnterpriseFeatureCondition + OnCommunityFallbackCondition** (Spring conditions)
5. **ConditionalOnEnterpriseFeature + ConditionalOnCommunityFallback** (annotations)
6. **EditionInfoContributor** (actuator)
7. **EditionAutoConfiguration** (wires it all together)
8. **AutoConfiguration.imports** update
9. **Tests** (EditionPropertiesTest, EditionDetectorTest, EditionAutoConfigurationTest)
10. **YAML/Docker/.gitignore** updates

---

## Verification

1. `mvn clean test -pl framework-core` -- all existing + new tests pass
2. `mvn clean package -DskipTests` -- full project builds
3. Start any service locally -- startup logs show edition detection banner
4. Hit `/actuator/info` -- response includes `hazelcast.edition` section
5. Verify Community Edition default: no license set -> logs show COMMUNITY, all features disabled gracefully
