# A/B Test Comparison Report

**Generated**: 2026-02-21T13:32:27Z
**Variant A**: community-baseline
**Variant B**: single-replica-clustered

## Configuration

| Setting | community-baseline | single-replica-clustered |
|---------|--------------------|--------------------------|
| Config | community-baseline | single-replica-clustered |
| Compose files | docker-compose.yml | docker-compose.yml, docker-compose.clustered.yml |
| k6 scenario | constant | constant |
| Target TPS | 50 | 50 |
| Duration | 3m | 3m |

## Throughput

| Metric | community-baseline | single-replica-clustered | Delta |
|--------|--------------------|--------------------------|-------|
| Iterations | 9000 | 9000 | -0.01 (0%) ^ |
| Iterations/s | 49.86 | 49.86 | -0.01 (0%) ^ |
| HTTP Requests | 9584 | 9547 | |
| HTTP req/s | 53.10 | 52.89 | |
| Test duration (s) | 180.49 | 180.51 | |
| Data received | 5439299 B | 5438071 B | |
| Data sent | 2993035 B | 2994249 B | |

## Latency (ms)

### HTTP Request Duration (all endpoints)

| Percentile | community-baseline | single-replica-clustered | Delta |
|------------|--------------------|--------------------------|-------|
| avg | 19.09 | 23.44 | 4.35 (20.0%) ^ |
| p50 | 16.46 | 16.39 | -0.07 (0%) v |
| p90 | 25.26 | 27.27 | 2.01 (0%) ^ |
| p95 | 31.92 | 38.77 | 6.86 (20.0%) ^ |
| max | 974.12 | 1007.63 | 33.51 (0%) ^ |

### Order Create

| Stat | community-baseline | single-replica-clustered | Delta |
|------|--------------------|--------------------------|-------|
| avg | 21.42 | 27.41 | 5.99 (20.0%) ^ |
| p50 | 16.81 | 16.74 | -0.07 (0%) v |
| p95 | 34.50 | 46.89 | 12.39 (30.0%) ^ |

### Stock Reserve

| Stat | community-baseline | single-replica-clustered | Delta |
|------|--------------------|--------------------------|-------|
| avg | 16.68 | 18.39 | 1.71 (10.0%) ^ |
| p50 | 15.63 | 15.59 | -0.04 (0%) v |
| p95 | 27.89 | 30.74 | 2.86 (10.0%) ^ |

### Customer Create

| Stat | community-baseline | single-replica-clustered | Delta |
|------|--------------------|--------------------------|-------|
| avg | 20.78 | 23.69 | 2.91 (10.0%) ^ |
| p50 | 18.82 | 18.68 | -0.14 (0%) v |
| p95 | 34.74 | 39.62 | 4.88 (10.0%) ^ |

### Saga E2E

| Stat | community-baseline | single-replica-clustered | Delta |
|------|--------------------|--------------------------|-------|
| avg | 242.20 | 247.57 | 5.36 (0%) ^ |
| p50 | 219.00 | 220.00 | 1.00 (0%) ^ |
| p95 | 238.40 | 268.35 | 29.95 (10.0%) ^ |

## Error Rates

| Metric | community-baseline | single-replica-clustered |
|--------|--------------------|--------------------------|
| HTTP failure rate | 0.00% | 0.00% |
| HTTP failures | 0 | 0 |
| Mixed workload errors | 0.00% | 0.00% |

## Resource Usage (Docker Stats)

### community-baseline

```
NAME                CPU %     MEM USAGE / LIMIT   MEM %     NET I/O           PIDS
api-gateway         1.26%     252.3MiB / 256MiB   98.56%    41.5kB / 479kB    88
account-service     17.92%    595MiB / 1GiB       58.11%    1.83MB / 8.26MB   165
order-service       20.69%    699.4MiB / 1.5GiB   45.53%    25MB / 54.8MB     174
payment-service     19.50%    587.7MiB / 1GiB     57.39%    12.3MB / 29.4MB   160
inventory-service   16.06%    587.2MiB / 1GiB     57.34%    11.7MB / 32.7MB   164
hazelcast-3         1.35%     317.6MiB / 768MiB   41.35%    24.1MB / 25.8MB   115
grafana             0.09%     188.8MiB / 256MiB   73.75%    49.7kB / 25.9kB   18
hazelcast-2         1.34%     314.5MiB / 768MiB   40.94%    26.4MB / 22.9MB   113
management-center   2.53%     464.3MiB / 512MiB   90.69%    9.64MB / 611kB    232
postgres            0.71%     152.1MiB / 256MiB   59.43%    58.8MB / 10.6MB   46
prometheus          2.41%     108.9MiB / 256MiB   42.55%    8.04MB / 141kB    16
hazelcast-1         1.44%     312.4MiB / 768MiB   40.68%    23.1MB / 22.8MB   111
jaeger              1.13%     135.8MiB / 256MiB   53.05%    5.8MB / 80.4kB    15
```

### single-replica-clustered

```
NAME                CPU %     MEM USAGE / LIMIT   MEM %     NET I/O           PIDS
api-gateway         8.27%     254.1MiB / 256MiB   99.26%    41.6kB / 547kB    88
inventory-service   17.60%    618.5MiB / 1GiB     60.40%    11.8MB / 32.9MB   163
account-service     14.40%    539.4MiB / 1GiB     52.68%    1.73MB / 7.94MB   164
payment-service     15.18%    599.6MiB / 1GiB     58.55%    12.5MB / 29.7MB   160
order-service       15.76%    764MiB / 1.5GiB     49.74%    25.3MB / 55.8MB   172
hazelcast-3         1.22%     316.7MiB / 768MiB   41.23%    29.9MB / 30MB     106
hazelcast-2         1.51%     315.7MiB / 768MiB   41.10%    20.6MB / 18.6MB   114
grafana             0.57%     89.84MiB / 256MiB   35.10%    42.4kB / 22kB     20
management-center   8.57%     480.1MiB / 512MiB   93.76%    9.55MB / 599kB    232
postgres            0.88%     153.6MiB / 256MiB   60.01%    59.4MB / 10.7MB   46
prometheus          0.64%     54.2MiB / 256MiB    21.17%    8.11MB / 136kB    15
hazelcast-1         1.13%     312.1MiB / 768MiB   40.64%    24.2MB / 23.8MB   115
jaeger              0.05%     96.13MiB / 256MiB   37.55%    5.85MB / 80.4kB   15
```

## P95 Latency Comparison (ASCII)

```
P95 Latency (ms) - lower is better
Scale: each # = 6.71 ms

http_req_duration 
  A #                                        31.92 ms
  B #                                        38.77 ms

order_create      
  A #                                        34.50 ms
  B #                                        46.89 ms

stock_reserve     
  A #                                        27.89 ms
  B #                                        30.74 ms

customer_create   
  A #                                        34.74 ms
  B #                                        39.62 ms

saga_e2e          
  A #                                        238.40 ms
  B ######################################## 268.35 ms

```

## Summary

**community-baseline** wins on 4 of 4 p95 latency metrics.

- community-baseline p95 wins: 4
- single-replica-clustered p95 wins: 0
- Ties: 0

---
*Report generated by ab-compare.sh*
