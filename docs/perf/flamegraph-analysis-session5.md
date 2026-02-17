# Flame Graph Analysis — Session 5

**Date:** 2026-02-17
**Tool:** async-profiler v4.3 (itimer mode on macOS Docker Desktop)
**Load:** k6 constant-arrival-rate at 25 TPS

---

## Profiling Configuration

| Parameter | Value |
|-----------|-------|
| async-profiler version | 4.3 |
| Profiling mode | itimer (wall-clock) — perf_events unavailable in Docker Desktop |
| JVM flags | `-XX:+PreserveFramePointer -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints` |
| Java runtime | Eclipse Temurin 17 (container) |
| Container memory | 768M |
| Profiling duration | 60s per capture |
| Load pattern | constant_rate at 25 TPS via k6 |
| Warmup | 30s at 25 TPS before each capture |

---

## Flame Graphs Captured

| # | Service | Event | File | Notes |
|---|---------|-------|------|-------|
| 1 | order-service | cpu | `order-service-cpu-YYYYMMDD-HHMMSS.html` | |
| 2 | order-service | alloc | `order-service-alloc-YYYYMMDD-HHMMSS.html` | |

---

## CPU Flame Graph Analysis (order-service)

### Top 5 CPU Hotspots

| Rank | Method / Area | % of Samples | Description |
|------|---------------|-------------|-------------|
| 1 | | | |
| 2 | | | |
| 3 | | | |
| 4 | | | |
| 5 | | | |

### Expected vs Actual Hotspots

| Expected Hotspot | Found? | Notes |
|-----------------|--------|-------|
| GenericRecord serialization (toGenericRecord/fromGenericRecord) | | |
| FlakeIdGenerator.newId() | | |
| IMap.set() / executeOnKey | | |
| CompletableFuture completion handling | | |
| HTTP parsing / Spring MVC dispatch | | |
| Jet pipeline processing | | |
| PostgreSQL write-behind (persistence) | | |

### Observations

_Fill in after reviewing the CPU flame graph._

---

## Allocation Flame Graph Analysis (order-service)

### Top 5 Allocation Hotspots

| Rank | Method / Area | % of Allocations | Object Types |
|------|---------------|------------------|--------------|
| 1 | | | |
| 2 | | | |
| 3 | | | |
| 4 | | | |
| 5 | | | |

### Observations

_Fill in after reviewing the allocation flame graph._

---

## Optimization Recommendations for Session 6

### High Priority (likely measurable impact)

1. **TBD** — _description_
2. **TBD** — _description_

### Medium Priority (worth investigating)

3. **TBD** — _description_
4. **TBD** — _description_

### Low Priority (minor or speculative)

5. **TBD** — _description_

---

## Notes

- **itimer limitation**: Wall-clock profiling includes time spent in I/O wait (network, disk).
  Frames involving `epoll_wait`, `read`, `write` syscalls may appear large but represent
  blocking I/O, not CPU work. The allocation profile is unaffected and more actionable for
  GC optimization.
- **PreserveFramePointer impact**: The `-XX:+PreserveFramePointer` flag has a small JIT
  performance cost (~1-2%). Profiling numbers are representative but not identical to
  production without this flag.
