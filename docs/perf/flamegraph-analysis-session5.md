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
| Profiling duration | 30s collapsed + 60s HTML per capture |
| Load pattern | constant_rate at 25 TPS via k6 |
| Warmup | 30s at 25 TPS before each capture |

---

## Flame Graphs Captured

| # | Service | Event | File | Samples |
|---|---------|-------|------|---------|
| 1 | order-service | cpu | `order-service-cpu-20260217-151939.html` | 514 |
| 2 | order-service | alloc | `order-service-alloc-20260217-152107.html` | 1,093 |

---

## CPU Flame Graph Analysis (order-service)

### CPU Time by Subsystem

| Subsystem | % of Samples | Notes |
|-----------|-------------|-------|
| Hazelcast (total) | 52% | IMap ops (28%), Operations (22%), Serialization (10%), Jet (9%), Networking (7%) |
| Spring Framework | 33% | Filter chain, DispatcherServlet, AOP proxy |
| Framework (theyawns) | 33% | Mostly OutboxPublisher path |
| Tomcat/HTTP | 32% | HTTP parsing, socket I/O |
| Thread Parking/Idle | 18% | BackoffIdleStrategy in Jet cooperative workers |
| JIT Compilation | 14% | C2 compiler (PhaseChaitin, PhaseIdealLoop) |
| Outbox Publisher | 9% | Scheduled polling via `IMap.values()` |
| Jackson/JSON | 4% | Request/response body serialization |

### Top 5 CPU Hotspots (self time)

| Rank | Method / Area | % of Samples | Description |
|------|---------------|-------------|-------------|
| 1 | `pthread_cond_signal` (native) | 8.9% | Thread unparking — signals from IMap operations completing |
| 2 | `write` (native) | 6.6% | Socket I/O — Hazelcast client protocol writes to shared cluster |
| 3 | libc (various) | 5.1% | Memory copy, syscall overhead |
| 4 | `ConcurrentHashMap.get` | 1.2% | Hazelcast internal lookups (partition, schema caches) |
| 5 | `epoll_ctl` / `epoll_pwait` | 2.2% | NIO event loop — Hazelcast networking + Tomcat |

### Expected vs Actual Hotspots

| Expected Hotspot | Found? | Notes |
|-----------------|--------|-------|
| GenericRecord serialization | Yes (10%) | `CompactInternalGenericRecord`, `ByteArrayObjectDataInput`, `readUTFInternal` |
| FlakeIdGenerator.newId() | Minimal (<1%) | Not a bottleneck at 25 TPS |
| IMap.set() / executeOnKey | Yes (28%) | `MapProxyImpl.putAllAsync`, `executePredicate`, `InvokeOnPartitions` |
| CompletableFuture completion | Minor (1-2%) | `AbstractInvocationFuture$ApplyNode.execute` present but small |
| HTTP parsing / Spring MVC | Yes (32%) | Full Tomcat + Spring filter chain visible |
| Jet pipeline processing | Yes (9%) | `ProcessorTasklet.stateMachineStep`, `CooperativeWorker.runTasklet` |
| PostgreSQL write-behind | Not seen | Persistence is async; no JDBC frames in hot path at 25 TPS |

### Observations

1. **Outbox polling is the dominant framework hotspot.** `OutboxPublisher.publishPendingEntries` calls `IMap.values()` on a scheduled loop, which triggers a full map scan with predicate evaluation on every invocation. This is ~24% inclusive CPU time.

2. **IMap operations dominate Hazelcast CPU.** The `putAllAsync` path (from Jet `WriteMapP` sink) and `executePredicate` (from outbox polling) together consume 28% of samples. The operation invocation chain (`OperationServiceImpl` → `InvokeOnPartitions` → `OperationRunnerImpl`) is deep but each frame is thin.

3. **Serialization is moderate.** CompactGenericRecord construction and UTF string reading account for ~10% — expected for event sourcing with string-heavy domain objects.

4. **Thread signaling overhead is the top single self-time item.** `pthread_cond_signal` at 8.9% reflects the cost of waking threads after async IMap operations complete. This is inherent to the async operation model.

5. **JIT compilation takes 14%.** The C2 compiler is still optimizing code during the 30s profiling window. In a long-running production scenario this would drop to near zero.

---

## Allocation Flame Graph Analysis (order-service)

### Allocation by Subsystem

| Subsystem | % of Alloc Samples | Notes |
|-----------|-------------------|-------|
| Hazelcast (total) | 67% | Serialization (39%), IMap (45%), Query (23%), Operations (27%) |
| Framework (theyawns) | 60% | Outbox polling is the biggest allocator |
| Spring Framework | 60% | Overlaps with framework — AOP proxy, filter chain |
| Tomcat/HTTP | 34% | HTTP request/response buffers |
| Outbox Publisher | 28% | Drives most of the Hz serialization/query allocations |
| Hazelcast Jet | 14% | ProcessorTasklet, metrics collection |
| Jackson/JSON | 2% | Small — JSON payloads are modest |

### Top 5 Allocation Hotspots

| Rank | Method / Area | % of Allocations | Object Types |
|------|---------------|------------------|--------------|
| 1 | Hazelcast Compact deserialization | 22.6% | `byte[]` — raw serialization buffers |
| 2 | Hazelcast query/predicate evaluation | 7.0% | `String` objects from `readUTFInternal` |
| 3 | `CompactInternalGenericRecord.<init>` | 3.6% | GenericRecord instances per deserialized entry |
| 4 | `ByteArrayObjectDataInput.<init>` | 3.3% | New input stream per deserialization |
| 5 | `TreeMap$Entry` / `HashMap$Node` | 8.6% | Metadata maps in GenericRecord, query results |

### Observations

1. **`byte[]` dominates allocations (22.6%).** Every Compact Serialization read creates a `byte[]` buffer via `ByteArrayObjectDataInput`. With the outbox polling scanning all entries every cycle, this adds up fast.

2. **Outbox polling drives 28% of all allocations.** The `publishPendingEntries` → `IMap.values()` → predicate evaluation path allocates GenericRecords, query readers, byte arrays, and String objects for every entry scanned.

3. **`CompactInternalGenericRecord` allocation is significant (3.6%).** Each deserialized map entry creates a new GenericRecord object with internal TreeMap/HashMap for field storage. This is inherent to the schemaless GenericRecord design.

4. **`NamespaceThreadLocalContext` appears in 1.4% of alloc samples.** Hazelcast namespace isolation creates ThreadLocal entries on every operation — minor but visible.

5. **Jet pipeline allocations are modest (14%).** The cooperative execution model reuses tasklets efficiently; allocations come mainly from metrics collection (`provideDynamicMetrics`).

---

## Optimization Recommendations for Session 6

### High Priority (likely measurable impact)

1. **Optimize outbox polling** — Replace `IMap.values(predicate)` with `IMap.entrySet(predicate)` or an EntryProcessor that returns only pending entries. Better yet, switch to an event-driven approach (Event Journal listener) instead of polling, eliminating the periodic full-scan entirely. This single change could reduce both CPU and allocation by ~25%.

2. **Batch and throttle outbox reads** — If polling must remain, add a configurable `maxBatchSize` limit to `pollPending()` so it doesn't scan the entire map on each invocation. Also consider increasing the polling interval under low load.

### Medium Priority (worth investigating)

3. **Reduce GenericRecord allocations in query path** — The query/deserialization path creates `ByteArrayObjectDataInput`, `CompactInternalGenericRecord`, and `GenericRecordQueryReader` per entry. For the outbox use case where we only need a few fields, consider using Projections to fetch only the required fields instead of full deserialization.

4. **Evaluate Jet sink batching** — The `WriteMapP.submitPending` → `putAllAsync` path is hot. Verify that the Jet pipeline's batch size is tuned appropriately for the throughput level. Larger batches reduce per-item overhead.

### Low Priority (minor or speculative)

5. **Reduce Spring AOP overhead on hot paths** — The CGLIB proxy on `OutboxPublisher.publishPendingEntries` adds interceptor overhead on every scheduled invocation. Consider using direct method calls for this internal scheduler path.

6. **JIT warmup** — The 14% JIT compilation overhead would disappear in steady state. For benchmarking accuracy, consider longer warmup periods (2+ minutes) before measurement windows.

---

## Notes

- **itimer limitation**: Wall-clock profiling includes time spent in I/O wait (network, disk).
  Frames involving `epoll_wait`, `read`, `write` syscalls may appear large but represent
  blocking I/O, not CPU work. The allocation profile is unaffected and more actionable for
  GC optimization.
- **PreserveFramePointer impact**: The `-XX:+PreserveFramePointer` flag has a small JIT
  performance cost (~1-2%). Profiling numbers are representative but not identical to
  production without this flag.
- **Sample counts**: 514 CPU samples / 1,093 alloc samples over 30s under 25 TPS load.
  Sufficient for identifying hotspot areas; percentages have ~2-3% margin of error.
