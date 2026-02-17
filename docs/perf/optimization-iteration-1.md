# Optimization Iteration 1: Outbox Polling

**Date:** 2026-02-17
**Plan:** `docs/plans/perf-session6-optimization-iteration-1.md`

---

## Summary

Eliminated the #1 CPU/allocation hotspot (outbox polling at ~25% of order-service) by adding IMap indexes and switching to `PagingPredicate` for server-side sort+limit.

## Changes

| Change | Before | After |
|--------|--------|-------|
| Outbox map indexes | None (full partition scan) | HASH on `status`, SORTED on `createdAt` |
| `pollPending()` query | `values(predicate)` + Java sort + limit | `PagingPredicate` with server-side sort + page size |
| `pendingCount()` | `values(predicate).size()` (full deserialization) | `keySet(predicate).size()` (keys only) |
| Publisher timer | Only recorded non-empty polls | Records all poll cycles via try/finally |
| Empty poll counter | Not tracked | `outbox.poll.empty` counter |

---

## Before (Session 5 Baseline @ 25 TPS)

| Metric | Value |
|--------|-------|
| Outbox polling CPU % | ~25% |
| Outbox polling allocation % | ~25% |
| p95 latency | _TBD_ |
| p99 latency | _TBD_ |

## After (Session 6 @ 25 TPS)

| Metric | Value |
|--------|-------|
| Outbox polling CPU % | _TBD_ |
| Outbox polling allocation % | _TBD_ |
| p95 latency | _TBD_ |
| p99 latency | _TBD_ |

---

## Flame Graph Comparison

### CPU — Before
_Attach: `docs/perf/flamegraphs/session5-cpu-flamegraph.html`_

### CPU — After
_Attach: `docs/perf/flamegraphs/session6-cpu-flamegraph.html`_

### Allocation — Before
_Attach: `docs/perf/flamegraphs/session5-alloc-flamegraph.html`_

### Allocation — After
_Attach: `docs/perf/flamegraphs/session6-alloc-flamegraph.html`_

---

## Observations

_Fill in after re-profiling:_

1.
2.
3.

## Next Steps

_Identify the next hotspot from the post-optimization flame graph._
