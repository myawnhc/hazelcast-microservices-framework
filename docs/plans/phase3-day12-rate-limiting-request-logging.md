# Phase 3 Day 12: Rate Limiting & Request Logging

## Context

Day 12 hardens the API Gateway (created on Day 11) with production-grade cross-cutting concerns: rate limiting, correlation ID propagation, request timing metrics, and request/response logging. These are implemented as Spring Cloud Gateway `GlobalFilter` instances, each with a single responsibility and a well-defined execution order.

The rate limiter uses a Hazelcast IMap-backed token bucket algorithm — demonstrating how Hazelcast can serve infrastructure needs beyond event sourcing. The embedded standalone instance keeps the gateway self-contained with no external dependencies.

---

## Key Design Decisions

**Token bucket algorithm** (not fixed window or sliding window log) — token buckets allow smooth burst handling and are the industry standard for API rate limiting. The bucket refills continuously, so a client that pauses briefly regains capacity naturally.

**Hazelcast IMap + EntryProcessor** for atomic rate limit checks — the `TokenBucketProcessor` runs inside the IMap partition, providing atomic read-modify-write without external locking. For the embedded standalone instance this is an in-memory operation (sub-millisecond).

**Embedded standalone Hazelcast** (not client to shared cluster) — the gateway's rate limit state is local. For multi-instance gateway deployments, switching to a client connecting to the shared cluster requires only a config change. No Jet, no event journal, no cluster join.

**Separate read/write rate limits** — reads (GET, HEAD) at 100 req/s and writes (POST, PUT, DELETE, PATCH) at 20 req/s per client IP. This reflects real-world API patterns where writes are more expensive and should be throttled more aggressively.

**Scaled-integer token storage** — tokens are stored as `long` values multiplied by 1000 to avoid floating-point arithmetic in the EntryProcessor while maintaining sub-token precision for smooth refill.

**Path normalization for metrics** — the `RequestTimingFilter` replaces UUIDs and numeric path segments with `{id}` to prevent metric cardinality explosion in Prometheus/Grafana.

**Filter execution order:**

| Order | Filter | Responsibility |
|-------|--------|----------------|
| -4 | `CorrelationIdFilter` | Set up request identity |
| -3 | `RequestTimingFilter` | Start timer (uses `beforeCommit` for accurate header) |
| -2 | `RateLimitFilter` | Reject over-limit requests early |
| -1 | `RequestLoggingFilter` | Log request + response summary |

---

## Files Created

| File | Purpose |
|------|---------|
| `api-gateway/src/main/java/.../gateway/config/GatewayHazelcastConfig.java` | Embedded standalone HazelcastInstance for rate limit state |
| `api-gateway/src/main/java/.../gateway/config/RateLimitProperties.java` | `@ConfigurationProperties` for rate limit settings |
| `api-gateway/src/main/java/.../gateway/filter/CorrelationIdFilter.java` | Generates/propagates `X-Correlation-ID` header |
| `api-gateway/src/main/java/.../gateway/filter/TokenBucketProcessor.java` | Hazelcast `EntryProcessor` — atomic token bucket logic |
| `api-gateway/src/main/java/.../gateway/filter/RateLimitFilter.java` | Per-client rate limiting with 429 response |
| `api-gateway/src/main/java/.../gateway/filter/RequestLoggingFilter.java` | Structured request/response logging with correlation ID |
| `api-gateway/src/main/java/.../gateway/filter/RequestTimingFilter.java` | Duration metric + `X-Response-Time` header |
| `api-gateway/src/test/java/.../gateway/filter/CorrelationIdFilterTest.java` | 6 tests |
| `api-gateway/src/test/java/.../gateway/filter/RateLimitFilterTest.java` | 11 tests (real embedded Hazelcast) |
| `api-gateway/src/test/java/.../gateway/filter/RequestLoggingFilterTest.java` | 4 tests |
| `api-gateway/src/test/java/.../gateway/filter/RequestTimingFilterTest.java` | 6 tests |

## Files Modified

| File | Change |
|------|--------|
| `api-gateway/pom.xml` | Add `hazelcast` dependency |
| `api-gateway/src/main/resources/application.yml` | Add `gateway.rate-limit` config section |

---

## Implementation Order

1. Add Hazelcast dependency to gateway `pom.xml`
2. Create `GatewayHazelcastConfig` (embedded standalone instance)
3. Create `RateLimitProperties` configuration
4. Create `CorrelationIdFilter` (order -4)
5. Create `TokenBucketProcessor` (EntryProcessor)
6. Create `RateLimitFilter` (order -2)
7. Create `RequestTimingFilter` (order -3)
8. Create `RequestLoggingFilter` (order -1)
9. Update `application.yml` with rate limit defaults
10. Write unit tests for all four filters
11. Build and verify: `mvn clean test -pl api-gateway -am`

---

## Verification

1. `mvn clean test -pl api-gateway -am` — **36 tests pass** (14 existing + 22 new)
2. Rate limit test confirms token bucket exhaustion triggers 429
3. Separate read/write buckets verified per client IP
4. Correlation ID generation and propagation verified
5. Path normalization handles UUIDs and numeric IDs
