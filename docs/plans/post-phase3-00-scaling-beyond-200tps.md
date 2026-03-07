# Post-Phase 3 #00 — Scaling Beyond 200 TPS: Exploration & Options

**Date**: 2026-02-21
**Status**: Exploration (research complete, implementation deferred)
**Context**: Performance testing (Phase 3, Sessions 1-11) showed a hard ceiling at ~200 TPS sustained with sub-second saga completion. This document captures root cause analysis and evaluates options while context is fresh.

---

## 1. What Exactly Blocked 200 → 500 TPS?

### The Symptom

| Metric | 200 TPS | 500 TPS Target | Actual at 500 |
|--------|---------|----------------|---------------|
| HTTP p95 | 281 ms | — | 282 ms (fine) |
| Saga E2E p95 | 1,036 ms | < 2,000 ms | **9,486 ms** (timeout) |
| Achieved iter/s | 197.95 | 500 | **219.46** (capped) |
| Service CPU | 70% | — | **86-98%** (saturated) |
| Shared cluster CPU | 1-2% | — | 1-2% (idle) |

HTTP request processing remained excellent. The saga completion pipeline broke.

### The Root Cause: CPU Saturation on Service Nodes

The binding constraint is **CPU on the 2 service nodes** (each c7i.4xlarge, 16 vCPU). At 500 TPS with 60% order sagas:

- ~300 sagas/sec, each triggering a 6-stage Jet pipeline on 3 services
- Each pipeline event costs ~8.7ms p50 of CPU time (Jet scheduling 72%, Hazelcast ops 28%, framework 0.4%)
- With 5 replicas per service sharing the Jet workload, each replica handles ~60 events/sec
- At 8.7ms per event, that's ~522ms of CPU per second per replica — over 50% of a single core
- With Jet's multi-threaded processing, back-pressure, and GC overhead, CPUs saturate at 86-98%

**The shared Hazelcast cluster (3 nodes) sat at 1-2% CPU — it is NOT the bottleneck.**

### What About ITopic Specifically?

ITopic publish costs ~308μs per operation. At 200 TPS with 60% sagas generating ~3 cross-service messages each:

```
200 TPS × 0.6 × 3 messages = ~360 ITopic publishes/sec
360 × 308μs = ~111ms of wall-clock time per second
```

That's ~11% of a single core. ITopic throughput is **not the current bottleneck**.

However, ITopic has architectural scaling limits that would matter at higher TPS:

---

## 2. ITopic Architecture Analysis

### Current Setup

| Aspect | Current Implementation |
|--------|----------------------|
| Type | Standard `ITopic<GenericRecord>` (not ReliableTopic) |
| Location | Shared 3-node Hazelcast cluster (via `hazelcastClient`) |
| Naming | One topic per event type: `"OrderCreated"`, `"StockReserved"`, etc. |
| Dispatch | Broadcast — all listeners receive all messages |
| Threading | Single-threaded per listener registration |
| Durability | None (fire-and-forget); outbox provides durable retry |
| Partitioning | None — no sharding or key-based routing |

### Where ITopic Sits in the Event Flow

```
Service REST API
  → EventSourcingController.handleEvent()
    → Jet pipeline (6 stages, ~8.7ms p50)         ← 72% CPU here
      → Stage 6: PipelineCompletion written
        → republishToSharedCluster()
          → OutboxStore.write() [embedded IMap]
            → OutboxPublisher.publishPendingEntries()
              → sharedHazelcast.getTopic(eventType).publish(record)   ← ITopic publish
                → Shared cluster broadcasts to all subscribers
                  → SagaListener.onMessage() on receiving service
                    → executeWithResilience() → service method
                      → Next event in saga chain...
```

**The ITopic publish happens on the shared cluster (client side).** Each service's `OutboxPublisher` calls `topic.publish()` through the `hazelcastClient` connection.

### ITopic Characteristics That Limit Scaling

1. **Broadcast model**: Every listener on a topic receives every message. With 5 order-service replicas all listening to `"PaymentProcessed"`, all 5 receive the message but only the one owning the saga ID processes it. The other 4 discard it. At 500 TPS, that's ~1,500 wasted message deliveries/sec.

2. **Single-threaded dispatch per listener**: Each `addMessageListener()` call gets one dispatch thread. If `onMessage()` takes time (even with async `executeWithResilience()`), messages queue up in the dispatch thread.

3. **No backpressure**: ITopic is fire-and-forget. If listeners can't keep up, messages are delivered but processing falls behind. There's no mechanism to signal producers to slow down.

4. **No partitioning**: Unlike Kafka topics with partitions, ITopic has no way to route specific messages to specific consumers. Every subscriber gets everything.

---

## 3. Options for Scaling Beyond 200 TPS

### Option A: Add More Service Nodes (Simplest)

**What**: Add a 3rd or 4th service node group to EKS, spreading the 5+ replicas per service across more physical CPU.

**Estimated impact**: Linear scaling. Current 2 service nodes at 86-98% CPU → 3 nodes at ~60% → room for ~330 TPS. 4 nodes → ~440 TPS.

**Cost**: +$0.90/hr per c7i.4xlarge node.

**Pros**:
- Zero code changes
- Proven architecture (ADR 013 already handles multi-replica)
- Immediate results

**Cons**:
- Doesn't address architectural scaling limits
- Cost grows linearly with TPS
- ITopic broadcast waste grows with replica count
- Eventually hits ITopic dispatch thread ceiling

**Verdict**: Good first step. Gets us to ~300-400 TPS with no code changes.

### Option B: Sharded ITopics (Multiple Topics per Event Type)

**What**: Instead of one `"OrderCreated"` topic, use N sharded topics: `"OrderCreated-0"`, `"OrderCreated-1"`, ..., `"OrderCreated-N"`. Publishers hash on saga ID to pick a shard. Each consumer subscribes to a subset of shards.

**Implementation sketch**:
```java
// Publisher side (OutboxPublisher)
int shard = Math.abs(sagaId.hashCode()) % SHARD_COUNT;
ITopic<GenericRecord> topic = sharedHazelcast.getTopic(eventType + "-" + shard);
topic.publish(record);

// Consumer side (SagaListener)
for (int i = 0; i < SHARD_COUNT; i++) {
    if (i % replicaCount == myReplicaIndex) {
        ITopic<GenericRecord> topic = hazelcast.getTopic(eventType + "-" + i);
        topic.addMessageListener(new MyListener());
    }
}
```

**Estimated impact**: With 4 shards, each consumer handles 25% of messages (vs 100% today). Effective throughput per consumer 4x.

**Pros**:
- Eliminates broadcast waste (targeted delivery)
- Multiple dispatch threads (one per shard subscription)
- Works within existing Hazelcast architecture
- Configurable shard count

**Cons**:
- Requires consumer-to-shard assignment logic (replica index awareness)
- Rebalancing on scale-up/down needs coordination
- Adds operational complexity (monitoring N×M topics instead of M)
- Not a built-in Hazelcast pattern — custom implementation
- Saga ID must be in the outbox entry (it is — `GenericRecord` includes `sagaId`)

**Verdict**: Moderate complexity, meaningful improvement. Best Hazelcast-native approach to ITopic scaling.

### Option C: ReliableTopic (Ringbuffer-Backed)

**What**: Replace `ITopic` with `ReliableTopic`, which is backed by a `Ringbuffer` and supports configurable read-batch sizes and store-and-forward semantics.

**Key differences from ITopic**:
| Feature | ITopic | ReliableTopic |
|---------|--------|---------------|
| Backing store | None (in-memory dispatch) | Ringbuffer (configurable capacity) |
| Slow consumer handling | Message loss | Configurable (discard oldest or block) |
| Message ordering | Per-publisher | Global (within topic) |
| Durability | None | Ringbuffer overflow policy |
| Thread model | Shared executor | Dedicated or shared executor |

**Pros**:
- Better slow-consumer handling (won't silently drop messages)
- Configurable read-batch-size for throughput
- Built-in Hazelcast feature (no custom code)
- Can configure dedicated thread pool per topic

**Cons**:
- Still broadcast model — every subscriber gets every message (same waste as ITopic)
- Ringbuffer is a fixed-size circular buffer — overflow policy must be tuned
- Does NOT solve the partitioning problem
- Adds memory overhead (ringbuffer storage on cluster)

**Verdict**: Improves reliability but does NOT improve throughput scaling. The broadcast problem remains. Worth combining with Option B (sharded ReliableTopics), but not sufficient alone.

### Option D: Kafka for Cross-Service Saga Communication

**What**: Replace the shared Hazelcast cluster's ITopic with Kafka topics for saga event delivery. Keep the embedded Hazelcast instances for Jet pipeline processing (unchanged).

**Architecture change**:
```
CURRENT:
  OutboxPublisher → sharedHazelcast.getTopic().publish() → SagaListener.onMessage()

PROPOSED:
  OutboxPublisher → kafkaProducer.send(topic, sagaId, record) → SagaListener via @KafkaListener
```

**Kafka topic design**:
```
Topic: saga-events.OrderCreated      (partitions: 8, replication: 3)
Topic: saga-events.StockReserved     (partitions: 8, replication: 3)
Topic: saga-events.PaymentProcessed  (partitions: 8, replication: 3)
...
Key: sagaId (ensures all events for one saga go to same partition → ordering)
Consumer group: inventory-service (only one replica processes each message)
```

**Estimated impact**:
- Kafka routinely handles 100K+ messages/sec per broker
- Partitioned consumer groups = zero broadcast waste
- At 500 TPS × 3 saga messages = 1,500 msgs/sec — trivial for a 3-broker Kafka

**Pros**:
- Native partitioning with consumer groups (zero waste, automatic rebalancing)
- Battle-tested at massive scale (LinkedIn, Netflix, Uber all use Kafka for saga-like patterns)
- Built-in backpressure (consumer lag → auto-pause)
- Durable by default (configurable retention)
- Spring Boot has excellent Kafka support (`spring-kafka`)
- Eliminates need for shared Hazelcast cluster entirely (could reduce to just Kafka)
- Monitoring ecosystem (Kafka Lag Exporter, Burrow, etc.)

**Cons**:
- **New infrastructure dependency** (Kafka + ZooKeeper/KRaft cluster)
- Additional operational complexity (topic management, consumer group rebalancing)
- Higher latency floor (~2-5ms vs ~0.3ms for ITopic) — messages go to disk
- Cost of running Kafka cluster ($0.50-2.00/hr for 3 brokers on EKS)
- Serialization format change (Kafka uses byte arrays — would need Avro/Protobuf/JSON)
- **Breaks the "Hazelcast-only" educational narrative** of the project
- Migration effort: outbox publisher, all saga listeners, docker-compose, helm charts

**Verdict**: The nuclear option. Solves scaling definitively but adds significant operational complexity and changes the project's identity. Best suited if the target is genuinely >1,000 TPS.

### Option E: Hybrid — Hazelcast ITopics + Kafka for High-Volume Events

**What**: Keep ITopic for low-volume saga events (`PaymentFailed`, `StockReservationFailed`, `OrderCancelled`), use Kafka only for the high-volume event types (`OrderCreated`, `StockReserved`, `PaymentProcessed`).

**Pros**:
- Targeted — only changes the hot path
- Preserves Hazelcast architecture for most of the system
- Reduces Kafka footprint (fewer topics to manage)

**Cons**:
- Two messaging systems to monitor and maintain
- Inconsistent event delivery semantics (ITopic fire-and-forget vs Kafka durable)
- Debugging complexity (which path did this event take?)

**Verdict**: Complexity of two systems probably isn't worth it unless specific event types have vastly different throughput requirements.

### Option F: Optimize Per-Event CPU Cost

**What**: Reduce the ~8.7ms per-event pipeline cost so existing nodes can handle more events/sec.

**Specific optimizations**:

| Optimization | Estimated Savings | Complexity |
|--------------|-------------------|------------|
| Custom CompactSerializers for hot-path objects | 10-20% of serialization time | Low |
| Jet pipeline stage consolidation (merge enrich + persist) | 5-10% of pipeline time | Medium |
| Near Cache on saga-state IMap | Reduce cross-cluster gets by 80% | Low |
| Smart routing on client connections | 10-15% latency reduction | Config only |
| Reduce backup-count from 1 to 0 on transient maps (PENDING, COMPLETIONS) | 15-20% write time | Config only |
| Pre-sized IMap (avoid rehashing under load) | 5% write time | Config only |
| Dedicated executor for ITopic listeners | Better thread isolation | Config only |

**Combined estimated impact**: 30-50% reduction in per-event CPU → ~300 TPS on same hardware.

**Pros**:
- No architectural changes
- Many are config-only tweaks
- Improves latency at all TPS levels

**Cons**:
- Diminishing returns — 72% of time is Jet scheduling + system overhead
- Won't get to 500 TPS alone
- Some optimizations reduce durability (backup-count 0)

**Verdict**: Should be done regardless of other choices. Low risk, measurable impact.

---

## 4. Comparison Matrix

| Option | Target TPS | Code Changes | New Infra | Cost Impact | Risk | Time to Implement |
|--------|-----------|-------------|-----------|-------------|------|-------------------|
| **A: More nodes** | ~350 | None | More EKS nodes | +$0.90/hr per node | Minimal | Hours |
| **B: Sharded ITopics** | ~500 | Moderate | None | None | Low-Medium | 2-3 days |
| **C: ReliableTopic** | ~250 | Low | None | Slight memory increase | Low | 1 day |
| **D: Kafka replacement** | >1,000 | Large | Kafka cluster | +$1-2/hr | Medium | 1-2 weeks |
| **E: Hybrid** | ~600 | Large | Kafka cluster | +$1-2/hr | Medium-High | 1-2 weeks |
| **F: CPU optimization** | ~300 | Low-Medium | None | None | Low | 2-3 days |

---

## 5. Recommended Approach

### Phase 4 Implementation Order

**Step 1: Low-Hanging Fruit (Option F)**
- Enable smart routing on client connections
- Add Near Cache for saga-state IMap reads
- Reduce backup-count on transient maps
- Add custom CompactSerializers for outbox and saga-state entries
- Pre-size hot IMaps
- Configure dedicated executor for ITopic listeners
- **Expected outcome**: ~300 TPS on current hardware

**Step 2: Sharded ITopics (Option B)**
- Implement saga-ID-based topic sharding
- Add replica-aware shard assignment with rebalancing
- Make shard count configurable (start with 4)
- Consider ReliableTopic as the shard backing (Option B + C combined)
- **Expected outcome**: ~500 TPS on current hardware

**Step 3: Evaluate Kafka (Option D) — only if >500 TPS needed**
- Prototype Kafka-based saga delivery alongside existing ITopic
- Feature-flag to switch between Hazelcast and Kafka delivery
- Benchmark side-by-side
- **Expected outcome**: >1,000 TPS ceiling with clear operational tradeoffs documented

Each step is independent and provides measurable improvement. The blog post narrative works well too: "How we scaled from 200 to 500 TPS without leaving Hazelcast, and when you'd reach for Kafka."

---

## 6. Key Questions to Answer During Implementation

1. **ITopic dispatch thread saturation**: At what TPS does the single dispatch thread per listener become the bottleneck? (Need to instrument with `onMessage()` timing)
2. **Outbox publisher batch size**: Current `maxBatchSize` default — is it tuned? Larger batches reduce IMap query frequency.
3. **Smart routing impact**: Is smart routing actually disabled in EKS? (It should be auto-enabled for client connections, but the performance guide mentions "single-gateway mode")
4. **Near Cache coherence**: Saga-state IMap is written by multiple services — Near Cache invalidation latency could cause stale reads. Need to measure invalidation propagation time.
5. **Kafka latency floor**: The 2-5ms Kafka latency vs 0.3ms ITopic — does this meaningfully impact saga E2E at 500 TPS? (Likely not, since saga E2E is ~1s, but worth measuring)
6. **GC pressure**: At 86-98% CPU, how much is GC? ZGC or Shenandoah could reclaim significant headroom vs the default G1GC.

---

## 7. Blog Post Potential

This exploration maps to a strong blog post:

**"Scaling Event-Sourced Sagas: From 200 to 1,000 TPS with Hazelcast (and When to Reach for Kafka)"**

Sections:
1. Where we hit the wall (performance data, bottleneck analysis)
2. Why ITopic broadcast is architecturally limited
3. Sharded ITopics as a Hazelcast-native solution
4. CPU optimization quick wins
5. When Kafka becomes the right answer
6. Decision framework for choosing your messaging backbone

---

*This document captures exploration while context is fresh. Implementation is deferred to Phase 4.*
