# Deployment Performance Comparison

**Generated**: 2026-02-19T16:19:23Z

**local** vs **aws-small**

## Workload Description

Each "transaction" in the TPS measurement executes **one** randomly-selected operation from a weighted mix:

| Weight | Operation | Service | Complexity |
|--------|-----------|---------|------------|
| 60% | **Create Order** | order-service | Triggers a 3-service distributed saga via Hazelcast ITopic: inventory stock reservation, payment processing, and order confirmation |
| 25% | **Reserve Stock** | inventory-service | Single-service event-sourced command |
| 15% | **Create Customer** | account-service | Single-service event-sourced command |

**What "1 TPS" actually means:** Each iteration sends 1 HTTP request to the target service, but 60% of those requests trigger a multi-service saga that generates approximately 3 additional internal cross-service messages (stock reservation, payment processing, order confirmation) via Hazelcast ITopic. At 100 TPS, the system processes ~100 external HTTP requests/second plus ~180 internal saga messages/second.

10% of order creations (6% of all iterations) also poll for full saga completion — this is reported as the `Saga E2E` metric in the per-operation breakdown, measuring the time from order submission to final CONFIRMED/CANCELLED status.

## Testing Methodology

The load generator (k6) runs on the test operator's workstation and reaches the Kubernetes services via `kubectl port-forward`. This means every HTTP request traverses the network between the test client and the cluster, adding real-world network latency to the measurements.

This is a deliberate choice for two reasons:

1. **Realistic measurement:** In production, ecommerce orders arrive from external clients (web browsers, mobile apps, API consumers) — never from inside the cluster. Measuring latency from outside the cluster reflects what end users would actually experience.

2. **Consistent methodology:** Using port-forward for all deployment targets (local Docker Desktop, AWS small/medium/large) ensures relative comparisons between tiers are valid, even though absolute latency differs by network distance.

**Implication for local vs. AWS comparison:** The local Docker Desktop deployment shows lower absolute latency (~1ms p50) because the test client and services share the same machine. The AWS deployment shows higher absolute latency (~45ms p50) because requests traverse the internet to us-east-1. This difference reflects network distance, not service performance. The meaningful comparisons are: error rates, throughput capacity, resource utilization, and relative latency between AWS tiers.

## Environment

| Setting | local | aws-small |
|---------|--------|--------|
| Target | local | aws-small |
| Scenario | constant | constant |
| Duration | 3m | 3m |
| Nodes | 1 | 2 |
| Instance types |  | t3.xlarge t3.xlarge |

## Summary Table

| TPS | Iter/s (local) | Iter/s (aws-small) | p95 (local) | p95 (aws-small) | p95 Delta |
|-----|--------|--------|--------|--------|-------|
| 10 | 7.70 | 9.87 | 29933.38 | 115.93 | -29817.46 (-90.0%) v |
| 25 | 23.64 | 24.60 | 20.59 | 84.55 | 63.96 (310.0%) ^ |
| 50 | 47.07 | 47.24 | 18.94 | 96.12 | 77.19 (400.0%) ^ |

## Detailed Comparison

### 10 TPS

**Throughput**

| Metric | local | aws-small | Delta |
|--------|--------|--------|-------|
| Iterations | 1618 | 1800 | |
| Iter/s | 7.70 | 9.87 | 2.17 (20.0%) v |
| HTTP req/s | 23.38 | 14.65 | |

**Latency (ms)**

| Percentile | local | aws-small | Delta |
|------------|--------|--------|-------|
| avg | 2997.86 | 59.69 | -2938.17 (-90.0%) v |
| p50 | 5.06 | 50.92 | 45.86 (900.0%) ^ |
| p95 | 29933.38 | 115.93 | -29817.46 (-90.0%) v |
| p99 | N/A | N/A | N/A |
| max | 60001.56 | 1711.25 | -58290.31 (-90.0%) v |

| Order Create p95 | 21605.65 | 121.79 | -21483.85 (-90.0%) v |
| Stock Reserve p95 | 60000.36 | 123.94 | -59876.41 (-90.0%) v |
| Customer Create p95 | 35405.41 | 132.88 | -35272.54 (-90.0%) v |
| Saga E2E p95 | 21087.55 | 2685.50 | -18402.05 (-80.0%) v |

**Error Rates**

| Metric | local | aws-small |
|--------|--------|--------|
| HTTP failure rate | 7.37% | 0.11% |

### 25 TPS

**Throughput**

| Metric | local | aws-small | Delta |
|--------|--------|--------|-------|
| Iterations | 4501 | 4501 | |
| Iter/s | 23.64 | 24.60 | 0.96 (0%) v |
| HTTP req/s | 93.28 | 37.38 | |

**Latency (ms)**

| Percentile | local | aws-small | Delta |
|------------|--------|--------|-------|
| avg | 5.92 | 49.83 | 43.90 (740.0%) ^ |
| p50 | 1.17 | 46.37 | 45.20 (3850.0%) ^ |
| p95 | 20.59 | 84.55 | 63.96 (310.0%) ^ |
| p99 | N/A | N/A | N/A |
| max | 667.71 | 634.13 | -33.58 (0%) v |

| Order Create p95 | 30.70 | 92.29 | 61.59 (200.0%) ^ |
| Stock Reserve p95 | 1.72 | 102.61 | 100.89 (5850.0%) ^ |
| Customer Create p95 | 39.85 | 114.12 | 74.26 (180.0%) ^ |
| Saga E2E p95 | 10174.70 | 2748.50 | -7426.20 (-70.0%) v |

**Error Rates**

| Metric | local | aws-small |
|--------|--------|--------|
| HTTP failure rate | 6.47% | 0.09% |

### 50 TPS

**Throughput**

| Metric | local | aws-small | Delta |
|--------|--------|--------|-------|
| Iterations | 8975 | 8991 | |
| Iter/s | 47.07 | 47.24 | 0.18 (0%) v |
| HTTP req/s | 190.53 | 146.20 | |

**Latency (ms)**

| Percentile | local | aws-small | Delta |
|------------|--------|--------|-------|
| avg | 7.19 | 48.71 | 41.52 (570.0%) ^ |
| p50 | 1.03 | 40.86 | 39.83 (3850.0%) ^ |
| p95 | 18.94 | 96.12 | 77.19 (400.0%) ^ |
| p99 | N/A | N/A | N/A |
| max | 1913.75 | 413.18 | -1500.57 (-70.0%) v |

| Order Create p95 | 28.81 | 109.95 | 81.14 (280.0%) ^ |
| Stock Reserve p95 | 1.59 | 105.88 | 104.29 (6560.0%) ^ |
| Customer Create p95 | 24.84 | 111.64 | 86.81 (340.0%) ^ |
| Saga E2E p95 | 10177.20 | 10228.00 | 50.80 (0%) ^ |

**Error Rates**

| Metric | local | aws-small |
|--------|--------|--------|
| HTTP failure rate | 6.25% | 0.73% |

## Resource Usage Comparison

### 10 TPS

**local**

```
=== Pre-test metrics (10 TPS) ===
--- kubectl top pods (2026-02-19T14:26:04Z) ---
(metrics-server not available)


=== Post-test metrics (10 TPS) ===
--- kubectl top pods (2026-02-19T14:29:35Z) ---
(metrics-server not available)

```

**aws-small**

```
=== Pre-test metrics (10 TPS) ===
--- kubectl top pods (2026-02-19T16:04:37Z) ---
NAME                                      CPU(cores)   MEMORY(bytes)   
demo-account-service-59cbc8cfd-sjvs5      340m         576Mi           
demo-api-gateway-75fbdcb796-qbmfr         18m          240Mi           
demo-hazelcast-cluster-0                  28m          338Mi           
demo-hazelcast-cluster-1                  25m          287Mi           
demo-hazelcast-cluster-2                  64m          804Mi           
demo-inventory-service-78ccc7b686-qmvmh   332m         585Mi           
demo-order-service-798d89cf5f-ftdqw       98m          589Mi           
demo-payment-service-5dfb84c9fd-s4dbv     107m         567Mi           


=== Post-test metrics (10 TPS) ===
--- kubectl top pods (2026-02-19T16:07:41Z) ---
NAME                                      CPU(cores)   MEMORY(bytes)   
demo-account-service-59cbc8cfd-sjvs5      149m         590Mi           
demo-api-gateway-75fbdcb796-qbmfr         10m          242Mi           
demo-hazelcast-cluster-0                  33m          355Mi           
demo-hazelcast-cluster-1                  31m          296Mi           
demo-hazelcast-cluster-2                  79m          813Mi           
demo-inventory-service-78ccc7b686-qmvmh   194m         614Mi           
demo-order-service-798d89cf5f-ftdqw       233m         626Mi           
demo-payment-service-5dfb84c9fd-s4dbv     140m         583Mi           

```

### 25 TPS

**local**

```
=== Pre-test metrics (25 TPS) ===
--- kubectl top pods (2026-02-19T14:29:50Z) ---
(metrics-server not available)


=== Post-test metrics (25 TPS) ===
--- kubectl top pods (2026-02-19T14:33:01Z) ---
(metrics-server not available)

```

**aws-small**

```
=== Pre-test metrics (25 TPS) ===
--- kubectl top pods (2026-02-19T16:07:57Z) ---
NAME                                      CPU(cores)   MEMORY(bytes)   
demo-account-service-59cbc8cfd-sjvs5      107m         591Mi           
demo-api-gateway-75fbdcb796-qbmfr         6m           242Mi           
demo-hazelcast-cluster-0                  44m          357Mi           
demo-hazelcast-cluster-1                  49m          296Mi           
demo-hazelcast-cluster-2                  37m          814Mi           
demo-inventory-service-78ccc7b686-qmvmh   155m         615Mi           
demo-order-service-798d89cf5f-ftdqw       287m         626Mi           
demo-payment-service-5dfb84c9fd-s4dbv     141m         584Mi           


=== Post-test metrics (25 TPS) ===
--- kubectl top pods (2026-02-19T16:11:01Z) ---
NAME                                      CPU(cores)   MEMORY(bytes)   
demo-account-service-59cbc8cfd-sjvs5      109m         594Mi           
demo-api-gateway-75fbdcb796-qbmfr         8m           240Mi           
demo-hazelcast-cluster-0                  47m          479Mi           
demo-hazelcast-cluster-1                  42m          403Mi           
demo-hazelcast-cluster-2                  40m          816Mi           
demo-inventory-service-78ccc7b686-qmvmh   193m         629Mi           
demo-order-service-798d89cf5f-ftdqw       299m         654Mi           
demo-payment-service-5dfb84c9fd-s4dbv     154m         600Mi           

```

### 50 TPS

**local**

```
=== Pre-test metrics (50 TPS) ===
--- kubectl top pods (2026-02-19T14:33:16Z) ---
(metrics-server not available)


=== Post-test metrics (50 TPS) ===
--- kubectl top pods (2026-02-19T14:36:27Z) ---
(metrics-server not available)

```

**aws-small**

```
=== Pre-test metrics (50 TPS) ===
--- kubectl top pods (2026-02-19T16:11:17Z) ---
NAME                                      CPU(cores)   MEMORY(bytes)   
demo-account-service-59cbc8cfd-sjvs5      101m         594Mi           
demo-api-gateway-75fbdcb796-qbmfr         8m           240Mi           
demo-hazelcast-cluster-0                  36m          505Mi           
demo-hazelcast-cluster-1                  37m          420Mi           
demo-hazelcast-cluster-2                  29m          817Mi           
demo-inventory-service-78ccc7b686-qmvmh   139m         629Mi           
demo-order-service-798d89cf5f-ftdqw       113m         654Mi           
demo-payment-service-5dfb84c9fd-s4dbv     97m          600Mi           


=== Post-test metrics (50 TPS) ===
--- kubectl top pods (2026-02-19T16:14:28Z) ---
NAME                                      CPU(cores)   MEMORY(bytes)   
demo-account-service-59cbc8cfd-sjvs5      127m         612Mi           
demo-api-gateway-75fbdcb796-qbmfr         8m           242Mi           
demo-hazelcast-cluster-0                  54m          594Mi           
demo-hazelcast-cluster-1                  46m          517Mi           
demo-hazelcast-cluster-2                  49m          817Mi           
demo-inventory-service-78ccc7b686-qmvmh   185m         648Mi           
demo-order-service-798d89cf5f-ftdqw       342m         685Mi           
demo-payment-service-5dfb84c9fd-s4dbv     124m         617Mi           

```

---
*Report generated by k8s-compare.sh*
