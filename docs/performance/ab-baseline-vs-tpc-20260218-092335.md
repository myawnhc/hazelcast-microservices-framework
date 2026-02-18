# A/B Test Comparison Report

**Generated**: 2026-02-18T14:32:49Z
**Variant A**: community-baseline
**Variant B**: tpc

## Configuration

| Setting | community-baseline | tpc |
|---------|--------------------|-----|
| Config | community-baseline | tpc |
| Compose files | docker-compose.yml | docker-compose.yml, docker-compose-tpc.yml |
| k6 scenario | constant | constant |
| Target TPS | 50 | 50 |
| Duration | 3m | 3m |

## Throughput

| Metric | community-baseline | tpc | Delta |
|--------|--------------------|-----|-------|
| Iterations | 8994 | 8991 | 0.06 (0%) v |
| Iterations/s | 47.33 | 47.39 | 0.06 (0%) v |
| HTTP Requests | 33518 | 31810 | |
| HTTP req/s | 176.37 | 167.67 | |
| Test duration (s) | 190.04 | 189.72 | |
| Data received | 19682210 B | 18671051 B | |
| Data sent | 7188518 B | 6891118 B | |

## Latency (ms)

### HTTP Request Duration (all endpoints)

| Percentile | community-baseline | tpc | Delta |
|------------|--------------------|-----|-------|
| avg | 5.46 | 5.56 | 0.10 (0%) ^ |
| p50 | 0.95 | 1.06 | 0.10 (10.0%) ^ |
| p90 | 17.19 | 17.56 | 0.37 (0%) ^ |
| p95 | 19.54 | 19.87 | 0.32 (0%) ^ |
| max | 338.23 | 289.76 | -48.47 (-10.0%) v |

### Order Create

| Stat | community-baseline | tpc | Delta |
|------|--------------------|-----|-------|
| avg | 17.12 | 16.73 | -0.39 (0%) v |
| p50 | 15.60 | 16.00 | 0.39 (0%) ^ |
| p95 | 24.80 | 24.15 | -0.65 (0%) v |

### Stock Reserve

| Stat | community-baseline | tpc | Delta |
|------|--------------------|-----|-------|
| avg | 16.51 | 16.15 | -0.36 (0%) v |
| p50 | 15.50 | 15.71 | 0.21 (0%) ^ |
| p95 | 24.05 | 22.93 | -1.12 (0%) v |

### Customer Create

| Stat | community-baseline | tpc | Delta |
|------|--------------------|-----|-------|
| avg | 17.50 | 17.24 | -0.26 (0%) v |
| p50 | 16.47 | 16.83 | 0.37 (0%) ^ |
| p95 | 24.34 | 23.81 | -0.53 (0%) v |

### Saga E2E

| Stat | community-baseline | tpc | Delta |
|------|--------------------|-----|-------|
| avg | 9014.17 | 8858.64 | -155.53 (0%) v |
| p50 | 10084.00 | 10100.00 | 16.00 (0%) ^ |
| p95 | 10115.55 | 10142.00 | 26.45 (0%) ^ |

## Error Rates

| Metric | community-baseline | tpc |
|--------|--------------------|-----|
| HTTP failure rate | 0.10% | 0.09% |
| HTTP failures | 34 | 28 |
| Mixed workload errors | 0.38% | 0.31% |

## Resource Usage (Docker Stats)

### community-baseline

```
NAME                CPU %     MEM USAGE / LIMIT   MEM %     NET I/O           PIDS
api-gateway         0.43%     253.7MiB / 256MiB   99.11%    43.8kB / 550kB    88
order-service       37.55%    425.7MiB / 512MiB   83.14%    27.5MB / 52.3MB   168
inventory-service   17.67%    379.1MiB / 512MiB   74.04%    8.72MB / 16.7MB   165
account-service     14.14%    362.4MiB / 512MiB   70.78%    1MB / 4.34MB      156
payment-service     21.90%    369.8MiB / 512MiB   72.23%    8.82MB / 13.3MB   160
hazelcast-3         9.22%     276.5MiB / 512MiB   54.00%    26.6MB / 22.6MB   110
hazelcast-2         5.09%     276MiB / 512MiB     53.90%    26.2MB / 26.4MB   111
management-center   3.21%     477.8MiB / 512MiB   93.31%    9.29MB / 582kB    229
grafana             1.04%     63.17MiB / 256MiB   24.67%    41.8kB / 20.8kB   20
postgres            0.01%     24.68MiB / 256MiB   9.64%     5.45kB / 126B     6
prometheus          0.00%     51.65MiB / 256MiB   20.18%    7.69MB / 131kB    15
hazelcast-1         9.09%     272.5MiB / 512MiB   53.22%    23MB / 25MB       116
jaeger              0.01%     177.8MiB / 256MiB   69.45%    12MB / 117kB      15
```

### tpc

```
NAME                CPU %     MEM USAGE / LIMIT   MEM %     NET I/O           PIDS
api-gateway         11.22%    253.4MiB / 256MiB   98.98%    43.4kB / 517kB    89
inventory-service   18.84%    397.9MiB / 512MiB   77.71%    8.73MB / 16.7MB   166
account-service     15.73%    361.7MiB / 512MiB   70.65%    992kB / 4.25MB    160
order-service       18.05%    424.6MiB / 512MiB   82.93%    27MB / 50.8MB     166
payment-service     21.41%    383.5MiB / 512MiB   74.90%    8.81MB / 13.4MB   159
hazelcast-3         4.86%     320.1MiB / 512MiB   62.52%    25.4MB / 23.1MB   97
hazelcast-2         2.66%     323.3MiB / 512MiB   63.15%    27MB / 22.8MB     101
grafana             0.05%     64.47MiB / 256MiB   25.18%    47kB / 25.9kB     20
management-center   2.14%     492.6MiB / 512MiB   96.22%    9.75MB / 578kB    232
postgres            0.00%     23.87MiB / 256MiB   9.32%     5.41kB / 126B     6
prometheus          0.44%     51.87MiB / 256MiB   20.26%    7.81MB / 140kB    15
hazelcast-1         3.24%     332.2MiB / 512MiB   64.88%    23.6MB / 28.7MB   108
jaeger              0.01%     180MiB / 256MiB     70.30%    11.5MB / 119kB    15
```

## P95 Latency Comparison (ASCII)

```
P95 Latency (ms) - lower is better
Scale: each # = 253.55 ms

http_req_duration 
  A #                                        19.54 ms
  B #                                        19.87 ms

order_create      
  A #                                        24.80 ms
  B #                                        24.15 ms

stock_reserve     
  A #                                        24.05 ms
  B #                                        22.93 ms

customer_create   
  A #                                        24.34 ms
  B #                                        23.81 ms

saga_e2e          
  A #                                        10115.55 ms
  B ######################################## 10142.00 ms

```

## Summary

**tpc** wins on 3 of 4 p95 latency metrics.

- community-baseline p95 wins: 1
- tpc p95 wins: 3
- Ties: 0

---
*Report generated by ab-compare.sh*
