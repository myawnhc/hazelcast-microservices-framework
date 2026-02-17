# Performance Session 6: First Optimization Iteration

**Date:** 2026-02-17
**Permanent copy:** `docs/plans/perf-session6-optimization-iteration-1.md`

---

## Context

Session 5 flame graph profiling at 25 TPS identified **outbox polling** as the #1 hotspot — ~25% of both CPU and allocation in order-service. The root cause: `HazelcastOutboxStore.pollPending()` calls `outboxMap.values(Predicates.equal("status", "PENDING"))` **without an index**, forcing Hazelcast to deserialize every entry in the map to evaluate the predicate. After the query, the results are sorted in Java and limited to `maxBatchSize` — but the full deserialization has already happened.

Secondary finding: `pendingCount()` does the same full-scan query and deserializes all matching entries just to return a count.

---

## Optimizations (3 changes, all in framework-core)

### 1. Add indexes on outbox map

**Why:** Without an index, `Predicates.equal("status", ...)` triggers a full partition scan that deserializes every entry. A HASH index on `status` lets Hazelcast skip non-matching entries entirely.

**Where:** `HazelcastConfig.java` — add `configureOutboxMaps(Config config)` method

**What:**
- Create `MapConfig("framework_OUTBOX")`
- Add `IndexConfig(IndexType.HASH, "status")` — optimal for equality predicates
- Add `IndexConfig(IndexType.SORTED, "createdAt")` — enables server-side sort
- Set backup count same as other maps

### 2. Use PagingPredicate for server-side limiting

**Why:** Currently `pollPending()` fetches ALL matching entries, then sorts/limits in Java. `PagingPredicate` pushes the sort + limit to the Hazelcast query engine, reducing deserialization, memory, and network transfer.

**Where:** `HazelcastOutboxStore.java` — replace `pollPending()` body

**What:**
- Wrap `Predicates.equal("status", "PENDING")` in a `PagingPredicate` with a `Comparator` on the `createdAt` field and page size = `maxBatchSize`
- Fetch first page: `outboxMap.values(pagingPredicate)`
- Map results to `OutboxEntry` POJOs (sort is now server-side, no Java `.sorted()` needed)
- Fix `pendingCount()`: use `outboxMap.keySet(predicate).size()` instead of `values()` to avoid full deserialization

### 3. Fix outbox publisher timer for empty polls

**Why:** The flame graph showed CPU consumption on every poll cycle, but the timer only records non-empty polls. Recording all cycles helps tune the interval and gives accurate cost accounting.

**Where:** `OutboxPublisher.java`

**What:**
- Move `Timer.Sample` start and `sample.stop()` to bracket the entire method (including the empty-list early return)
- Add `outbox.poll.empty` counter for cycles that found no pending entries

---

## Files Modified

| # | File | Change |
|---|------|--------|
| 1 | `framework-core/.../config/HazelcastConfig.java` | Add `configureOutboxMaps()` with HASH+SORTED indexes |
| 2 | `framework-core/.../outbox/HazelcastOutboxStore.java` | PagingPredicate in `pollPending()`; `keySet()` in `pendingCount()` |
| 3 | `framework-core/.../outbox/OutboxPublisher.java` | Timer covers empty polls; add empty-poll counter |

---

## Verification

1. `mvn clean test` — all modules pass
2. Rebuild and restart services with profiling overlay
3. Run `profile-service.sh --service order-service --event cpu --duration 30 --tps 25`
4. Compare outbox polling subsystem % against Session 5 baseline (was ~25%)
5. Run k6 at 25 TPS and compare p95/p99 latency
6. Document before/after in `docs/perf/optimization-iteration-1.md`
