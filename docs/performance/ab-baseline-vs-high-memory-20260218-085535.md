# A/B Test Comparison Report

**Generated**: 2026-02-18T14:04:50Z
**Variant A**: community-baseline
**Variant B**: high-memory

## Configuration

| Setting | community-baseline | high-memory |
|---------|--------------------|-------------|
| Config | community-baseline | high-memory |
| Compose files | docker-compose.yml | docker-compose.yml |
| k6 scenario | constant | constant |
| Target TPS | 50 | 50 |
| Duration | 3m | 3m |

## Throughput

| Metric | community-baseline | high-memory | Delta |
|--------|--------------------|-------------|-------|
| Iterations | 8987 | 8986 | 0.13 (0%) v |
| Iterations/s | 47.19 | 47.32 | 0.13 (0%) v |
| HTTP Requests | 33154 | 32860 | |
| HTTP req/s | 174.08 | 173.04 | |
| Test duration (s) | 190.45 | 189.90 | |
| Data received | 19452806 B | 19278246 B | |
| Data sent | 7119986 B | 7072012 B | |

## Latency (ms)

### HTTP Request Duration (all endpoints)

| Percentile | community-baseline | high-memory | Delta |
|------------|--------------------|-------------|-------|
| avg | 5.20 | 5.32 | 0.12 (0%) ^ |
| p50 | 0.90 | 0.96 | 0.06 (0%) ^ |
| p90 | 16.84 | 17.37 | 0.53 (0%) ^ |
| p95 | 19.12 | 19.56 | 0.44 (0%) ^ |
| max | 304.26 | 283.81 | -20.46 (0%) v |

### Order Create

| Stat | community-baseline | high-memory | Delta |
|------|--------------------|-------------|-------|
| avg | 16.55 | 16.77 | 0.22 (0%) ^ |
| p50 | 15.34 | 15.89 | 0.55 (0%) ^ |
| p95 | 23.34 | 23.37 | 0.03 (0%) ^ |

### Stock Reserve

| Stat | community-baseline | high-memory | Delta |
|------|--------------------|-------------|-------|
| avg | 15.52 | 16.21 | 0.69 (0%) ^ |
| p50 | 15.12 | 15.86 | 0.74 (0%) ^ |
| p95 | 21.86 | 22.68 | 0.82 (0%) ^ |

### Customer Create

| Stat | community-baseline | high-memory | Delta |
|------|--------------------|-------------|-------|
| avg | 16.81 | 16.76 | -0.05 (0%) v |
| p50 | 16.13 | 16.35 | 0.21 (0%) ^ |
| p95 | 22.99 | 22.82 | -0.17 (0%) v |

### Saga E2E

| Stat | community-baseline | high-memory | Delta |
|------|--------------------|-------------|-------|
| avg | 8940.93 | 8792.22 | -148.71 (0%) v |
| p50 | 10079.00 | 10094.00 | 15.00 (0%) ^ |
| p95 | 10108.00 | 10125.00 | 17.00 (0%) ^ |

## Error Rates

| Metric | community-baseline | high-memory |
|--------|--------------------|-------------|
| HTTP failure rate | 0.11% | 0.06% |
| HTTP failures | 37 | 20 |
| Mixed workload errors | 0.41% | 0.22% |

## Resource Usage (Docker Stats)

### community-baseline

```
NAME                CPU %     MEM USAGE / LIMIT   MEM %     NET I/O           PIDS
api-gateway         0.98%     255.2MiB / 256MiB   99.70%    40.5kB / 516kB    87
payment-service     21.03%    392MiB / 512MiB     76.57%    8.78MB / 13.3MB   160
account-service     16.68%    376.5MiB / 512MiB   73.53%    1.03MB / 4.42MB   155
order-service       24.75%    429.9MiB / 512MiB   83.97%    27.4MB / 51.9MB   169
inventory-service   20.25%    391.6MiB / 512MiB   76.49%    8.74MB / 16.7MB   162
hazelcast-3         5.98%     283.1MiB / 512MiB   55.29%    25MB / 24.8MB     107
grafana             0.04%     89.46MiB / 256MiB   34.94%    42.5kB / 20.6kB   20
hazelcast-2         6.46%     279.1MiB / 512MiB   54.52%    22.4MB / 21.6MB   113
management-center   5.22%     463.3MiB / 512MiB   90.49%    9.31MB / 571kB    230
postgres            0.00%     23.9MiB / 256MiB    9.34%     5.35kB / 126B     6
prometheus          0.00%     57.03MiB / 256MiB   22.28%    7.68MB / 132kB    15
jaeger              0.02%     177.7MiB / 256MiB   69.41%    11.9MB / 119kB    15
hazelcast-1         4.19%     275.6MiB / 512MiB   53.83%    24.6MB / 23.8MB   116
```

### high-memory

```
NAME                CPU %     MEM USAGE / LIMIT   MEM %     NET I/O           PIDS
api-gateway         12.98%    254MiB / 256MiB     99.20%    43.2kB / 517kB    88
order-service       21.95%    412.9MiB / 512MiB   80.64%    27.3MB / 51.8MB   169
payment-service     23.60%    378.7MiB / 512MiB   73.96%    8.81MB / 13.4MB   157
account-service     20.80%    365.5MiB / 512MiB   71.39%    1.03MB / 4.43MB   158
inventory-service   22.97%    405.7MiB / 512MiB   79.23%    8.71MB / 16.6MB   166
hazelcast-3         4.37%     283.1MiB / 512MiB   55.28%    26MB / 26.2MB     105
grafana             0.06%     82.2MiB / 256MiB    32.11%    47.9kB / 26kB     16
management-center   2.77%     466.7MiB / 512MiB   91.16%    9.31MB / 584kB    232
hazelcast-2         3.95%     280.8MiB / 512MiB   54.84%    28.2MB / 25.5MB   107
postgres            0.00%     23.93MiB / 256MiB   9.35%     5.14kB / 126B     6
prometheus          0.32%     58.61MiB / 256MiB   22.89%    7.88MB / 142kB    15
hazelcast-1         3.70%     275.4MiB / 512MiB   53.78%    21.7MB / 22.2MB   118
jaeger              1.69%     180.1MiB / 256MiB   70.33%    11.8MB / 122kB    15
```

## P95 Latency Comparison (ASCII)

```
P95 Latency (ms) - lower is better
Scale: each # = 253.12 ms

http_req_duration 
  A #                                        19.12 ms
  B #                                        19.56 ms

order_create      
  A #                                        23.34 ms
  B #                                        23.37 ms

stock_reserve     
  A #                                        21.86 ms
  B #                                        22.68 ms

customer_create   
  A #                                        22.99 ms
  B #                                        22.82 ms

saga_e2e          
  A #                                        10108.00 ms
  B ######################################## 10125.00 ms

```

## Summary

**community-baseline** wins on 3 of 4 p95 latency metrics.

- community-baseline p95 wins: 3
- high-memory p95 wins: 1
- Ties: 0

---
*Report generated by ab-compare.sh*
