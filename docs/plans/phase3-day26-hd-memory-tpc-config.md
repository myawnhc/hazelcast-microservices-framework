# Phase 3 Day 26 — HD Memory and Thread-Per-Core Enterprise Feature Configuration

## Status: COMPLETE

## Summary

Implemented the Config Customizer pattern to transparently enable HD Memory and
Thread-Per-Core (TPC) Enterprise features on service-owned Hazelcast instances.

## Changes

### Framework Core (8 files modified/created)

| File | Change |
|------|--------|
| `EditionDetector.java` | Added `THREAD_PER_CORE` enum + `isTpcAvailable()` detection |
| `EditionProperties.java` | Added `HdMemoryFeatureConfig`, `TpcFeatureConfig` subclasses; TPC to `FeaturesConfig` |
| `HazelcastConfigCustomizer.java` | **NEW** — `@FunctionalInterface` for embedded Config customization |
| `HazelcastClientConfigCustomizer.java` | **NEW** — `@FunctionalInterface` for ClientConfig customization |
| `HazelcastFeatureAutoConfiguration.java` | **NEW** — Registers 3 customizer beans (HD Memory, TPC server, TPC client) |
| `AutoConfiguration.imports` | Registered `HazelcastFeatureAutoConfiguration` |
| `HazelcastConfig.java` | Injects + applies `List<HazelcastConfigCustomizer>` |

### Service Modules (4 files modified)

| File | Change |
|------|--------|
| `AccountServiceConfig.java` | Injects + applies config/client customizers |
| `InventoryServiceConfig.java` | Injects + applies config/client customizers |
| `OrderServiceConfig.java` | Injects + applies config/client customizers |
| `PaymentServiceConfig.java` | Injects + applies config/client customizers |

### API Gateway

Not modified — does not depend on framework-core. JavaDoc note added about
future integration path.

### Tests (7 files modified/created)

| File | Change |
|------|--------|
| `EditionDetectorTest.java` | Updated enum size 6→7, added TPC metadata test |
| `EditionPropertiesTest.java` | Added TPC defaults, HD Memory extended settings, `getConfigFor(THREAD_PER_CORE)` |
| `EditionAutoConfigurationTest.java` | Added TPC + HD Memory property binding tests |
| `HazelcastConfigCustomizerTest.java` | **NEW** — Functional interface contract tests |
| `HazelcastFeatureAutoConfigurationTest.java` | **NEW** — Community creates no beans, disabled features, property binding |
| `HdMemoryConfigCustomizerTest.java` | **NEW** — NativeMemoryConfig applied correctly |
| `TpcConfigCustomizerTest.java` | **NEW** — TpcConfig + ClientTpcConfig applied correctly |

## Configuration

```yaml
framework:
  features:
    hd-memory:
      enabled: auto          # auto | true | false
      fallback-behavior: on-heap
      capacity-mb: 512       # HD memory pool size in MB
      allocator-type: POOLED # STANDARD or POOLED
    tpc:
      enabled: auto          # auto | true | false
      fallback-behavior: standard-threading
      eventloop-count: 0     # 0 = auto-detect (available processors)
      client-enabled: true   # Also enable on hazelcastClient?
```

## Verification

- `mvn clean test` — BUILD SUCCESS, 0 failures across all 10 modules
- Community Edition: no customizer beans created, no errors
- Enterprise features individually disableable via `enabled: false`
- Feature settings configurable via properties
