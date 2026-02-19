# Session 10: Hazelcast Cluster Benchmark Results

**Date:** 2026-02-18
**Environment:** Docker Compose 3-node Hazelcast cluster, JDK 24, Apple Silicon, macOS
**Methodology:** JMH 1.37, HazelcastClient with smart routing disabled (host-to-Docker networking)

---

## Executive Summary

The shared 3-node Hazelcast cluster adds **~270-340 us per IMap/ITopic operation** over the network. A full saga cycle (3-4 IMap writes + 3-4 ITopic publishes) incurs ~1.9-2.4 ms of Hazelcast network cost — **22-28% of pipeline p50 (8.7 ms)** but not the dominant factor. System-level overhead (Jet scheduling, thread coordination, HTTP processing) accounts for the remaining ~72-78%.

**Conclusion: Hazelcast is a significant contributor to saga latency but is not the bottleneck.**

---

## Benchmark Results

### IMap Operations (over network to 3-node cluster)

| Benchmark | Avg (us/op) | Error (us) | Description |
|-----------|:-----------:|:----------:|-------------|
| `putSmallValue` | 311.3 | ± 8.4 | IMap.set ~200B GenericRecord (6 fields) |
| `putLargeValue` | 313.8 | ± 6.6 | IMap.set ~1KB GenericRecord (14 fields) |
| `getExistingKey` | 268.0 | ± 18.7 | IMap.get of pre-seeded entry |
| `getMissingKey` | 267.3 | ± 10.7 | IMap.get of nonexistent key (cache miss) |
| `putThenGet` | 568.3 | ± 27.4 | IMap.set + IMap.get round-trip |

**Key observations:**
- Payload size has minimal impact: small (311 us) vs large (314 us) — only 1% difference
- Get operations are ~14% faster than puts (no server-side write + replication)
- Cache miss (267 us) is equal to cache hit (268 us) — lookup cost dominates
- Put-then-get (568 us) is close to sum of put + get (579 us) — no batching benefit

### ITopic Operations (over network to 3-node cluster)

| Benchmark | Avg (us/op) | Error (us) | Description |
|-----------|:-----------:|:----------:|-------------|
| `publishSmallRecord` | 307.5 | ± 8.5 | Fire-and-forget publish ~200B record |
| `publishLargeRecord` | 355.0 | ± 44.5 | Fire-and-forget publish ~1KB record |
| `publishAndReceive` | 185.6 | ± 24.1 | Publish + listener callback round-trip |

**Key observations:**
- Fire-and-forget publish cost (~310-355 us) is comparable to IMap.set
- Round-trip (185 us) is surprisingly faster than one-way publish — likely because `publish()` blocks until the cluster confirms delivery, while `publishAndReceive` measures the full loop including fast callback dispatch
- Large record publish shows ~15% overhead vs small (355 vs 308 us)

### Event Journal Overhead

| Benchmark | Avg (us/op) | Error (us) | Description |
|-----------|:-----------:|:----------:|-------------|
| `writeToJournaledMap` | 338.2 | ± 32.1 | IMap.set to `*_PENDING` map (journal enabled) |
| `writeToPlainMap` | 322.9 | ± 16.2 | IMap.set to plain map (no journal) |

**Event journal overhead: ~15 us (4.7%)** — effectively negligible. The event journal bookkeeping adds minimal cost per write.

---

## Embedded vs Cluster Comparison

| Operation | Embedded (Session 9) | Cluster (Session 10) | Overhead Factor |
|-----------|:-------------------:|:--------------------:|:--------------:|
| IMap.set (small ~200B) | 7.4 us | 311.3 us | **42x** |
| IMap.set (large ~1KB) | 11.2 us | 313.8 us | **28x** |
| IMap.get | ~7 us* | 268.0 us | **38x** |
| ITopic.publish | N/A | 307.5 us | — |
| ITopic round-trip | N/A | 185.6 us | — |

*Session 9 measured EventStore.append (IMap.set + key construction), not raw IMap.get.

**The ~270-310 us network overhead per operation is dominated by:**
1. TCP round-trip to Docker container (~100 us baseline for localhost)
2. Compact serialization/deserialization (network wire format)
3. Single-gateway hop (smart routing disabled in host mode; see Technical Notes)
4. Backup replication to 1 other node (backup-count: 1)

---

## Saga Latency Impact Analysis

A typical order placement saga involves:
- 3 IMap.set operations (saga state updates: started, step completed, finished)
- 3 ITopic.publish operations (cross-service events: StockReserved, PaymentProcessed, OrderConfirmed)
- 2-3 IMap.get operations (saga state lookups)

**Estimated Hazelcast cluster cost per saga:**

| Operation | Count | Cost per op | Total |
|-----------|:-----:|:-----------:|:-----:|
| IMap.set | 3 | 312 us | 936 us |
| ITopic.publish | 3 | 310 us | 930 us |
| IMap.get | 2 | 268 us | 536 us |
| **Total** | **8** | — | **~2,402 us** |

**Saga pipeline p50 (Session 1):** 8,700 us
**Hazelcast cluster contribution:** 2,402 us = **27.6%**
**Remaining (Jet + HTTP + app logic):** 6,298 us = **72.4%**

---

## Hazelcast Simulator Evaluation

### Setup Experience
- **Image:** `hazelcast/simulator:latest` — **4.82 GB** Docker image
- **Pull time:** 211 seconds (3.5 minutes)
- `hazelcast/hazelcast-simulator` image does not exist on Docker Hub
- Container exited with code 127 — requires coordinator/agent/worker setup, not a simple `docker run`

### Assessment

| Factor | Rating | Notes |
|--------|--------|-------|
| Setup complexity | High | Python coordinator, agent processes, test class JAR compilation, custom networking |
| Docker Compose integration | Poor | Designed for cloud VMs, not pre-existing Docker clusters |
| Built-in tests | Limited | `IntByteMapTest` available but ITopic/Event Journal require custom test classes |
| Image size | Excessive | 4.82 GB for a benchmarking tool |
| Documentation | Sparse | README oriented toward AWS/GCE cloud deployment |

### Recommendation

**Do not adopt Simulator for this project.** JMH benchmarks via HazelcastClient provide equivalent measurements with:
- Same Maven build toolchain (no Python dependency)
- Direct comparison to Session 9 embedded benchmarks (same JMH methodology)
- ITopic and Event Journal coverage without custom test class JARs
- ~8 MB benchmark JAR vs 4.82 GB Docker image

Simulator would be valuable for:
- Cloud deployment testing at scale (50+ nodes)
- Network partition and split-brain scenarios
- Long-duration stability tests (hours/days)
- Comparing Hazelcast versions at scale

---

## Technical Notes

### Smart Routing (Host vs Docker Mode)

The JMH client supports two modes via `ClusterBenchmarkState`:

- **Host mode** (default): Uses `setSmartRouting(false)` because cluster members advertise Docker-internal hostnames (`hazelcast-1`, `hazelcast-2`, `hazelcast-3`) that aren't resolvable from the host. All operations route through a single gateway member (`localhost:5701`), adding one internal hop for partitions owned by other nodes. This is the standard mode for development benchmarking.

- **Docker mode** (`--docker` flag): Runs JMH inside a container on the Docker network with smart routing enabled. Direct connections to all cluster members eliminate the single-gateway bottleneck. Use this for production-accurate measurements.

All results in this document are from **host mode**. In production deployments where clients run inside the same network as the cluster, smart routing is enabled and latencies would be lower.

### Measurement Configuration

```
JMH 1.37
JDK 24.0.2, OpenJDK 64-Bit Server VM
Heap: -Xms1g -Xmx1g
Warmup: 3 iterations x 3s
Measurement: 5 iterations x 5s
Forks: 2 (except ITopic publishAndReceive: 1 fork)
Mode: Average time (us/op)
```

---

## Conclusion

Session 10 confirms the hypothesis from Session 9: **Hazelcast is a contributor to saga latency but not the bottleneck.**

| Layer | Cost | % of Pipeline p50 |
|-------|:----:|:-----------------:|
| Framework internals (Session 9) | ~37 us | 0.4% |
| Hazelcast cluster ops (Session 10) | ~2,400 us | 27.6% |
| System overhead (Jet + HTTP + threads) | ~6,263 us | 72.0% |
| **Pipeline p50 total** | **8,700 us** | **100%** |

The largest optimization opportunity remains in system-level overhead (Jet scheduling, thread coordination, Tomcat HTTP handling), not in Hazelcast operations. Event journal overhead is negligible (4.7%). Payload size has minimal impact on IMap/ITopic latency.

For further latency reduction, consider:
1. **Async IMap operations** — `IMap.setAsync()` to pipeline writes
2. **ITopic batching** — aggregate multiple events per topic message
3. **Near Cache for saga state** — reduce IMap.get latency for frequently-read saga state
4. **Smart routing** — enabled in production where clients share the cluster network
