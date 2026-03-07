# Post-Phase 3 #01: Operational Modes & 12-Hour Load Test Fixes

**Date**: 2026-03-07
**Status**: Implemented

## Summary

Implemented three operational modes (DEMO, PRODUCTION, PERF_TEST) with Docker Compose overlays,
Spring profiles, K8s Helm values, and infrastructure fixes from the 12-hour load test findings.

## Changes Made

### Part 1: Infrastructure Fixes
- **HazelcastConfig.java**: Added configurable ES/VIEW eviction via `@Value` properties
  (`hazelcast.eviction.event-store.max-size`, `.policy`, same for view-store)
- **HazelcastConfig.java**: Added `configureDlqMaps()` with TTL and LRU eviction
- **docker-compose.yml**: Bumped api-gateway to 512M (was 256M), management-center to 768M (was 512M)
- **All 4 service application.yml**: Parameterized tracing (`TRACING_ENABLED`, `TRACING_SAMPLING`)
  and added `io.opentelemetry.exporter` log level control (`OTEL_LOG_LEVEL`)

### Part 2: Spring Profile Files (12 files)
- `application-demo.yml` — aggressive eviction, no archival, WARN logging
- `application-production.yml` — large eviction, write-behind tuning, 30-day archival
- `application-perf-test.yml` — production-like eviction, no archival, ERROR otel logging
- Created for all 4 services (account, inventory, order, payment)

### Part 3: Docker Compose Overlays (3 files)
- `docker-compose-demo.yml` — no postgres/jaeger, G1GC 100ms, ~5.5GB total
- `docker-compose-production.yml` — tuned postgres, G1GC 200ms, ~10GB total
- `docker-compose-perf-test.yml` — no MC, GC logging, rate limiting off, ~8.5GB total

### Part 4: Start Script Updates
- `scripts/docker/start.sh` — added `--mode` flag, compose file stacking
- `scripts/k8s-local/start.sh` — added `--mode` flag, helm values stacking
- `scripts/k8s-aws/start.sh` — added `--mode` flag, helm values stacking
- 3 K8s mode values files: `values-mode-{demo,production,perf-test}.yaml`

### Part 5: PostgreSQL Archival
- `V3__add_archival_support.sql` — adds `archived` column, archive table, indexes
- `EventArchivalService.java` — scheduled batch archival (copy → mark → delete)
- `PersistenceProperties.ArchivalConfig` — enabled, retentionHours, runIntervalMs, batchSize
- Registered in `PostgresPersistenceAutoConfiguration` with `@ConditionalOnProperty`

### Part 6: Demo Load Generator
- `demo-ambient.js` — k6 scenario: 30 TPS / 10m default (quick demo), entity reuse, mixed saga outcomes
  - Trade show override: `--tps 3 --duration 8h`
- `start-demo.sh` — one-command demo setup (start → load data → run load generator)
