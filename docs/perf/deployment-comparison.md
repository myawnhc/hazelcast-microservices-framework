# Multi-Deployment Performance Comparison

**Status**: Template — populated after test runs
**Date**: 2026-02-19

## Test Matrix

| Deployment | Environment | Nodes | Instance Type | TPS Levels | Duration |
|------------|-------------|-------|---------------|------------|----------|
| Docker Compose | macOS local | 1 | — | 10, 25, 50 | 3m |
| K8s Local | Docker Desktop | 1 | — | 10, 25, 50 | 3m |
| K8s AWS Small | EKS | 2 | t3.xlarge | 10, 25, 50, 100 | 3m |
| K8s AWS Medium | EKS | 3 | c7i.2xlarge | 10, 25, 50, 100 | 3m |
| K8s AWS Large | EKS | 4 | c7i.4xlarge | 10, 25, 50, 100, 200 | 3m |

## Throughput Comparison

> Populated by `k8s-compare.sh` after test runs.

| TPS Target | Docker Compose Iter/s | K8s Local Iter/s | AWS Small Iter/s | AWS Medium Iter/s |
|------------|----------------------|------------------|------------------|-------------------|
| 10 | — | — | — | — |
| 25 | — | — | — | — |
| 50 | — | — | — | — |
| 100 | — | — | — | — |

## Latency Comparison (p95, ms)

| TPS Target | Docker Compose | K8s Local | AWS Small | AWS Medium |
|------------|---------------|-----------|-----------|------------|
| 10 | — | — | — | — |
| 25 | — | — | — | — |
| 50 | — | — | — | — |
| 100 | — | — | — | — |

## Resource Usage (per service, at 50 TPS)

| Service | Docker CPU | Docker Mem | K8s Local CPU | K8s Local Mem | AWS CPU | AWS Mem |
|---------|-----------|-----------|---------------|---------------|---------|---------|
| account-service | — | — | — | — | — | — |
| inventory-service | — | — | — | — | — | — |
| order-service | — | — | — | — | — | — |
| payment-service | — | — | — | — | — | — |
| hazelcast-cluster | — | — | — | — | — | — |

## Vertical Scaling Analysis

> How does performance change as we move from local to progressively larger AWS tiers?

### Throughput Scaling

- **Local → AWS Small**: —
- **AWS Small → AWS Medium**: —
- **AWS Medium → AWS Large**: —

### Latency Impact

- **Local → AWS Small**: —
- **AWS Small → AWS Medium**: —

## Key Findings

> To be populated after test runs.

1. **Docker Compose vs K8s Local**: (expected: similar performance, K8s adds ~5-15ms overhead from port-forwarding and network hops)
2. **Local vs Cloud**: (expected: cloud adds network latency but provides more consistent resource allocation)
3. **Scaling efficiency**: (how much throughput improves per tier upgrade)
4. **Resource bottlenecks**: (which service/component becomes the bottleneck at high TPS)
5. **Cost-performance ratio**: (throughput per dollar per hour across tiers)

## Recommendations

> To be populated after analysis.

- **Demo tier** recommendation: —
- **Staging tier** recommendation: —
- **Production tier** recommendation: —

## How to Reproduce

```bash
# 1. Run Docker Compose baseline
./scripts/perf/run-perf-test.sh --scenario constant --tps 50 --duration 3m

# 2. Run K8s local sweep
./scripts/k8s-local/build.sh
./scripts/k8s-local/start.sh
./scripts/perf/k8s-perf-test.sh --target local --tps-levels "10,25,50"

# 3. Run AWS EKS sweep (small tier)
./scripts/k8s-aws/setup-cluster.sh --tier small
./scripts/k8s-aws/build.sh
./scripts/k8s-aws/start.sh --tier small
./scripts/perf/k8s-perf-test.sh --target aws-small --tps-levels "10,25,50,100"

# 4. Generate comparison
./scripts/perf/k8s-compare.sh \
  --docker-baseline scripts/perf/results/<baseline>.json \
  --k8s scripts/perf/k8s-results/local-*/ \
  --tps 50

# 5. Cross-K8s comparison
./scripts/perf/k8s-compare.sh \
  scripts/perf/k8s-results/local-*/ \
  scripts/perf/k8s-results/aws-small-*/
```

---
*Part of Session 11: Multi-Deployment K8s Performance Testing*
