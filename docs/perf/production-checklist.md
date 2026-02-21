# Production Checklist — Hazelcast Microservices Framework

> Quick-reference checklist for deploying event-sourced microservices built on this framework.
> Each item includes a one-line action and the reasoning behind it.

---

## Memory & JVM

- [ ] **Set container memory limit to at least 1Gi per service**
  _Why:_ Services running embedded Hazelcast + Jet hit 512MiB ceiling within 12 minutes at 50 TPS, causing GC thrashing and latency spikes (Session 8).

- [ ] **Set JVM heap to ~75% of container limit** (e.g., `-Xmx768m` for 1Gi container)
  _Why:_ Leaves headroom for native memory (Hazelcast off-heap structures, thread stacks, NIO buffers).

- [ ] **Use G1GC** (default on JDK 17+)
  _Why:_ G1GC handles the mixed allocation profile (short-lived event objects + long-lived IMap data) well. Avoid ZGC unless heap exceeds 4Gi.

- [ ] **Enable IMap eviction for event store and view maps when persistence is active**
  _Why:_ Without eviction, IMaps grow unbounded. At 50 TPS for 30 min, order-service accumulates ~90K events. PostgreSQL becomes the durable store; IMaps become a bounded hot cache.

- [ ] **Set `MaxSizePolicy.PER_NODE` on completions map as safety net**
  _Why:_ The 1-hour TTL handles normal cleanup, but a size cap prevents unbounded growth during multi-hour runs.

---

## Hazelcast Configuration

- [ ] **Set event journal capacity to at least 10,000 per map**
  _Why:_ The Jet pipeline reads from the event journal. If capacity is too small, events are evicted before the pipeline processes them, causing data loss.

- [ ] **Add HASH index on outbox `status` field and SORTED index on `createdAt`**
  _Why:_ Without indexes, outbox polling performs a full partition scan on every cycle. Indexes reduced outbox CPU by 51% (Session 6).

- [ ] **Increase FlakeIdGenerator prefetch count for high-concurrency services**
  _Why:_ FlakeIdGenerator is the most expensive per-event operation (15.6 us). Contention scales super-linearly: 4 threads = 69 us, 8 threads = 151 us. Larger batches reduce lock contention.

- [ ] **Enable per-service embedded clustering for K8s multi-replica deployments**
  _Why:_ Without clustering, HPA-scaled replicas can't participate in Jet pipeline processing or serve saga state polls. Clustering improved saga E2E by 95% (Session 11).

- [ ] **Set backup-count to 1 for saga state and outbox maps**
  _Why:_ One synchronous backup provides data safety without the latency cost of 2+ backups. Event journal overhead is only 4.7% (Session 10).

- [ ] **Enable Near Cache for frequently-read, rarely-written maps** (e.g., view maps)
  _Why:_ Eliminates network round-trip (~270 us per IMap.get) for read-heavy access patterns.

---

## Monitoring

- [ ] **Enable Prometheus scraping on all services at `/actuator/prometheus`**
  _Why:_ The framework registers 46 application-level metrics (25 counters, 10 gauges, 11 timers) covering pipeline, saga, and persistence performance.

- [ ] **Deploy all 6 Grafana dashboards** (System Overview, Event Flow, Saga, Materialized Views, Persistence, Performance Testing)
  _Why:_ Pre-built dashboards provide immediate visibility into pipeline latency, saga completion rates, GC pressure, and persistence health.

- [ ] **Set alerts on: `sagas.active.count` sustained growth, `persistence.errors` > 0, `jvm.memory.used` > 85% of max**
  _Why:_ These three signals catch the most common production issues: saga backlog, persistence failures, and memory exhaustion.

- [ ] **Monitor `eventsourcing.pending.completions` gauge for monotonic growth**
  _Why:_ Growth indicates pipeline completions arriving after the 30-second timeout — a sign of backpressure or processing delays.

- [ ] **Track `eventsourcing.completions.orphaned` for late completion rate**
  _Why:_ Non-zero values indicate events completing after their caller has timed out, suggesting the pipeline is falling behind.

---

## Load Testing

- [ ] **Establish a baseline at 10, 25, and 50 TPS before any optimization**
  _Why:_ Baseline measurements provide the reference point for all future comparisons. Use `constant-arrival-rate` executor to avoid coordinated omission.

- [ ] **Use k6 `constant-arrival-rate` executor** (not `constant-vus`)
  _Why:_ `constant-vus` masks latency increases behind queue depth — coordinated omission makes slow services appear faster than they are.

- [ ] **Run a 30-minute sustained test at target TPS before declaring production-ready**
  _Why:_ Short tests miss memory growth patterns. All four services hit their memory ceiling within 12-21 minutes at 50 TPS (Session 8).

- [ ] **Set error rate threshold to < 1% (excluding expected domain errors)**
  _Why:_ Systemic errors (timeouts, connection failures) indicate infrastructure problems. Domain errors (stock depletion) are expected.

- [ ] **Track saga E2E latency separately from HTTP request latency**
  _Why:_ HTTP request latency (p95 < 30ms) masks saga completion issues. Saga E2E timed out at 50 TPS even while HTTP stayed fast (Session 1).

- [ ] **Watch for memory trend line across the full test duration**
  _Why:_ A monotonically increasing memory trend that hits the ceiling indicates unbounded IMap growth that will eventually cause OOM.

---

## Persistence

- [ ] **Configure PostgreSQL write-behind with `write-delay-seconds: 5` and `write-batch-size: 100`**
  _Why:_ Write-behind batches amortize database round-trips. A 5-second delay with batch size 100 balances durability lag against throughput.

- [ ] **Enable write-coalescing for view maps** (`write-coalescing: true`)
  _Why:_ Multiple updates to the same view entry within the write-delay window are coalesced into a single database write, reducing PostgreSQL load.

- [ ] **Apply eviction to event store maps when persistence is enabled**
  _Why:_ Events are durably written to PostgreSQL. The IMap becomes a bounded hot cache with LRU eviction, preventing OOM under sustained load.

- [ ] **Test cold-start behavior** (service restart with empty IMap, full PostgreSQL)
  _Why:_ MapStore `loadAllKeys()` rebuilds IMaps from PostgreSQL on startup. Verify this completes within acceptable time for your dataset size. Use `InitialLoadMode.LAZY` for view maps to avoid deadlock.

---

## Kubernetes / Scaling

- [ ] **Configure HPA with CPU target of 70%** and appropriate min/max replicas
  _Why:_ 70% CPU target provides headroom for traffic spikes while keeping utilization efficient. Services scaled 2→5 replicas at 25 TPS with clustering.

- [ ] **Set PodDisruptionBudget** (`minAvailable: 2` for clustered services)
  _Why:_ Prevents K8s from evicting enough pods to break the embedded Hazelcast cluster during rolling updates or node maintenance.

- [ ] **Enable per-service embedded clustering** (`hazelcast.embedded.clustering.enabled=true`)
  _Why:_ Without clustering, replicas can't serve saga state polls or participate in Jet processing. Clustering reduced saga E2E from 10s timeout to 513ms at 10 TPS.

- [ ] **Create a headless Service per microservice for Hazelcast DNS discovery** (port 5801)
  _Why:_ Per-service clustering uses K8s DNS discovery. The headless service must be on a non-default port (5801) to avoid conflicts with the HTTP port.

- [ ] **Use dedicated node groups for services vs. Hazelcast cluster** (large tier)
  _Why:_ Co-locating services and Hazelcast on the same nodes causes CPU contention. Dedicated nodes ensure predictable performance.

- [ ] **Set pod anti-affinity** to spread replicas across nodes
  _Why:_ Prevents multiple replicas of the same service on one node, which would create a single point of failure for the embedded cluster.

- [ ] **Set resource requests close to limits** (e.g., requests=384Mi, limits=1Gi)
  _Why:_ Accurate requests ensure the K8s scheduler places pods on nodes with sufficient capacity. Under-requesting causes memory pressure when multiple pods compete.

---

## Resilience

- [ ] **Enable circuit breakers on all cross-service saga listeners**
  _Why:_ Prevents cascading failures when a downstream service is slow or unresponsive. Six circuit breakers protect all saga interaction points.

- [ ] **Configure retry with exponential backoff for transient failures**
  _Why:_ Brief network blips and pod restarts cause transient errors. Retry with backoff recovers automatically without manual intervention.

- [ ] **Monitor the dead letter queue (DLQ) for persistent failures**
  _Why:_ Events that fail after all retries land in the DLQ. A growing DLQ indicates a systematic problem that needs investigation.

- [ ] **Enable idempotency guards on saga state transitions**
  _Why:_ Network retries and at-least-once delivery can cause duplicate event processing. Idempotency guards ensure saga state transitions are applied exactly once.

- [ ] **Use the transactional outbox pattern for cross-service event publishing**
  _Why:_ Direct ITopic publish can fail after the local event is persisted, causing inconsistency. The outbox pattern guarantees that published events match persisted state.

---

*Last updated: 2026-02-21 — Performance Exercise Session 12*
