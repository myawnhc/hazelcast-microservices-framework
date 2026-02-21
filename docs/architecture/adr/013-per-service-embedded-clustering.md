# ADR 013: Per-Service Embedded Hazelcast Clustering

## Status

**Accepted** - February 2026

## Context

ADR 008 established the dual-instance architecture: each microservice runs an embedded standalone Hazelcast instance (for Jet pipeline processing) plus a client to the shared cluster (for cross-service ITopic pub/sub). ADR 010 documented single-replica as a known constraint, deferring horizontal scaling until PostgreSQL persistence.

Performance testing (Session 11, Large tier on AWS EKS) confirmed that adding replicas via HPA does **not** improve throughput — each replica runs its own isolated Jet pipeline against its own local IMap. The single-writer bottleneck caps saga processing at one pod per service. Worse, multi-replica deployments cause saga poll misses because Kubernetes load-balances requests across pods that don't share state.

**Root cause**: The embedded instance disables all discovery (`joinConfig.getMulticastConfig().setEnabled(false)`), making each replica a cluster-of-1. This was done to prevent cross-service Jet lambda distribution (ADR 008). But replicas of the **same** service share identical classes — clustering them is safe.

## Decision

Let same-service replicas form their own per-service embedded Hazelcast cluster. Different service types remain isolated from each other. The shared cluster client is unchanged.

```
BEFORE:  pod-0 [HZ standalone]    pod-1 [HZ standalone]     <- isolated, can't share work
AFTER:   pod-0 [HZ order-local] --- pod-1 [HZ order-local]  <- same cluster, distributed Jet
```

### Key Design Elements

1. **`EmbeddedClusteringConfigurer`** — Reusable utility in `framework-core` that replaces the hardcoded discovery-disable pattern. Configures Kubernetes DNS-based discovery when enabled, standalone mode otherwise.

2. **Kubernetes DNS Discovery** — Uses Hazelcast's built-in `service-dns` property (no extra Maven dependency) with a headless Kubernetes Service per microservice. Port 5801 avoids collision with the shared cluster's 5701.

3. **Jet Auto-Scaling + AT_LEAST_ONCE** — Pipeline JobConfig enables `autoScaling(true)` and `ProcessingGuarantee.AT_LEAST_ONCE` with 10-second snapshot intervals. When a member joins or leaves, Jet automatically rebalances pipeline processing. Pipeline stages are idempotent (event store uses `set(key, record)`, view update overwrites with identical state).

4. **Atomic Outbox Claiming** — With a distributed outbox IMap, multiple pods could poll the same entry. A `CLAIMED` status and `ClaimEntryProcessor` (EntryProcessor with CAS semantics: `PENDING -> CLAIMED`) ensure only one pod wins each entry. A periodic stale-claim sweeper (30s timeout) reverts claims from crashed members to PENDING.

5. **Backward Compatible** — Clustering defaults to `false`. Docker Compose and local development continue to work unchanged. Enabled via environment variables in Kubernetes (Helm values).

### What Didn't Need to Change

- **ServiceCreator classes**: Already `Serializable`, use `ctx.hazelcastInstance()` per-member
- **Pipeline lambdas**: Already avoid capturing `this`, use local finals and static method refs
- **Completions listener**: `addEntryListener()` fires on ALL cluster members; each pod only resolves futures for its own events
- **FlakeIdGenerator**: Auto-coordinates across cluster members
- **Shared cluster client**: Unchanged — still connects to the external 3-node cluster for ITopic

### Configuration

```yaml
# application.yml (disabled by default)
hazelcast:
  embedded:
    clustering:
      enabled: ${HAZELCAST_EMBEDDED_CLUSTERING_ENABLED:false}
      discovery-mode: ${HAZELCAST_EMBEDDED_DISCOVERY_MODE:dns}
      service-dns: ${HAZELCAST_EMBEDDED_SERVICE_DNS:}
      port: ${HAZELCAST_EMBEDDED_PORT:5801}
```

Kubernetes Helm charts inject the service DNS via a headless Service:

```yaml
# service-headless.yaml (conditional on embeddedClustering.enabled)
spec:
  clusterIP: None
  publishNotReadyAddresses: true
  ports:
    - name: hz-embedded
      port: 5801
```

## Consequences

### Positive

- **True horizontal scaling**: Same-service replicas share IMap state and distribute Jet processing
- **Linear throughput scaling**: Each additional replica adds processing capacity
- **No saga poll misses**: All replicas see the same outbox entries (with atomic claiming to prevent duplicates)
- **Backward compatible**: Default standalone mode unchanged for local development and Docker Compose
- **No extra dependencies**: Hazelcast 5.6.0's built-in Kubernetes discovery plugin (already on classpath)

### Negative

- **Increased memory per pod**: Each pod now participates in a distributed cluster with backup partitions (2x data for backup-count=1)
- **Startup time**: Pods must discover and join the per-service cluster before the Jet job starts
- **Operational complexity**: Headless Service and clustering configuration added to Helm charts

### Neutral

- ADR 008 (dual-instance architecture) is **unchanged** — the shared cluster client still handles cross-service communication
- ADR 010 is **superseded** for Kubernetes deployments — single-replica is no longer the only option
- ADR 010 remains valid for Docker Compose and local development (clustering disabled by default)

## Relationship to Other ADRs

| ADR | Relationship |
|-----|-------------|
| **ADR 008** | Unchanged. The embedded instance now clusters with same-service replicas, but the dual-instance pattern (embedded + shared client) remains |
| **ADR 010** | Superseded for K8s. Single-replica still applies to Docker Compose and local dev |
| **ADR 005** | Compatible. Uses Community Edition features only (Kubernetes discovery is CE) |

## Alternatives Considered

| Approach | Why Rejected |
|----------|-------------|
| Sticky sessions (consistent hash routing) | Requires API Gateway changes, doesn't solve Jet distribution |
| Redis for shared state | Adds operational dependency, Hazelcast already provides distributed maps |
| PostgreSQL-first scaling | Would work but takes longer to implement; clustering is faster path to horizontal scaling |
| TCP-IP discovery | Requires knowing pod IPs in advance; DNS discovery is dynamic |
