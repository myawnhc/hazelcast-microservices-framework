# A/B Test Comparison Report

**Generated**: 2026-02-21T13:42:49Z
**Variant A**: single-replica-clustered
**Variant B**: multi-replica-clustered

## Configuration

| Setting | single-replica-clustered | multi-replica-clustered |
|---------|--------------------------|-------------------------|
| Config | single-replica-clustered | multi-replica-clustered |
| Compose files | docker-compose.yml, docker-compose.clustered.yml | docker-compose.yml, docker-compose.clustered.yml, docker-compose.multi-replica.yml |
| k6 scenario | constant | constant |
| Target TPS | 50 | 100 |
| Duration | 3m | 3m |

## Throughput

| Metric | single-replica-clustered | multi-replica-clustered | Delta |
|--------|--------------------------|-------------------------|-------|
| Iterations | 8998 | 9361 | -0.45 (0%) ^ |
| Iterations/s | 49.85 | 49.40 | -0.45 (0%) ^ |
| HTTP Requests | 9702 | 19159 | |
| HTTP req/s | 53.75 | 101.10 | |
| Test duration (s) | 180.51 | 189.50 | |
| Data received | 5495058 B | 11136052 B | |
| Data sent | 3020648 B | 4724668 B | |

## Latency (ms)

### HTTP Request Duration (all endpoints)

| Percentile | single-replica-clustered | multi-replica-clustered | Delta |
|------------|--------------------------|-------------------------|-------|
| avg | 33.02 | 1564.23 | 1531.21 (4630.0%) ^ |
| p50 | 17.51 | 122.62 | 105.12 (600.0%) ^ |
| p90 | 36.94 | 5671.07 | 5634.13 (15250.0%) ^ |
| p95 | 78.80 | 9823.84 | 9745.04 (12360.0%) ^ |
| max | 1355.32 | 20087.33 | 18732.01 (1380.0%) ^ |

### Order Create

| Stat | single-replica-clustered | multi-replica-clustered | Delta |
|------|--------------------------|-------------------------|-------|
| avg | 40.20 | 5036.38 | 4996.18 (12420.0%) ^ |
| p50 | 18.02 | 3288.70 | 3270.68 (18140.0%) ^ |
| p95 | 111.32 | 13436.32 | 13325.00 (11960.0%) ^ |

### Stock Reserve

| Stat | single-replica-clustered | multi-replica-clustered | Delta |
|------|--------------------------|-------------------------|-------|
| avg | 23.18 | 206.24 | 183.06 (780.0%) ^ |
| p50 | 16.20 | 96.71 | 80.50 (490.0%) ^ |
| p95 | 50.24 | 813.50 | 763.26 (1510.0%) ^ |

### Customer Create

| Stat | single-replica-clustered | multi-replica-clustered | Delta |
|------|--------------------------|-------------------------|-------|
| avg | 31.01 | 274.75 | 243.74 (780.0%) ^ |
| p50 | 19.96 | 111.05 | 91.09 (450.0%) ^ |
| p95 | 76.21 | 1074.18 | 997.97 (1300.0%) ^ |

### Saga E2E

| Stat | single-replica-clustered | multi-replica-clustered | Delta |
|------|--------------------------|-------------------------|-------|
| avg | 329.77 | 10567.31 | 10237.53 (3100.0%) ^ |
| p50 | 221.00 | 10192.50 | 9971.50 (4510.0%) ^ |
| p95 | 974.25 | 13210.05 | 12235.80 (1250.0%) ^ |

## Error Rates

| Metric | single-replica-clustered | multi-replica-clustered |
|--------|--------------------------|-------------------------|
| HTTP failure rate | 0.00% | 0.00% |
| HTTP failures | 0 | 0 |
| Mixed workload errors | 0.00% | 0.00% |

## Resource Usage (Docker Stats)

### single-replica-clustered

```
NAME                CPU %     MEM USAGE / LIMIT   MEM %     NET I/O           PIDS
api-gateway         0.68%     249.6MiB / 256MiB   97.49%    42.4kB / 516kB    89
account-service     13.58%    573MiB / 1GiB       55.96%    1.84MB / 8.24MB   162
order-service       13.40%    727.2MiB / 1.5GiB   47.34%    25.4MB / 56.2MB   172
payment-service     13.43%    582.8MiB / 1GiB     56.91%    12.5MB / 29.7MB   161
inventory-service   13.20%    615.8MiB / 1GiB     60.14%    11.7MB / 32.8MB   163
hazelcast-3         1.02%     316.7MiB / 768MiB   41.23%    29.8MB / 29.2MB   109
hazelcast-2         3.95%     316MiB / 768MiB     41.15%    29.6MB / 26.8MB   107
management-center   3.26%     488.7MiB / 512MiB   95.45%    9.43MB / 592kB    230
grafana             0.07%     90.23MiB / 256MiB   35.24%    42.2kB / 20.9kB   20
postgres            5.70%     153.3MiB / 256MiB   59.89%    59.9MB / 10.8MB   46
prometheus          0.07%     53.34MiB / 256MiB   20.83%    8.01MB / 136kB    15
hazelcast-1         2.98%     315.7MiB / 768MiB   41.10%    21.6MB / 22.6MB   120
jaeger              0.91%     105.7MiB / 256MiB   41.28%    5.87MB / 80.3kB   15
```

### multi-replica-clustered

```
NAME                CPU %     MEM USAGE / LIMIT   MEM %     NET I/O           PIDS
order-lb            2.11%     8.246MiB / 64MiB    12.88%    11.7kB / 10.1kB   11
api-gateway         1.19%     253.6MiB / 256MiB   99.06%    43.2kB / 555kB    90
payment-service     123.85%   628.4MiB / 1GiB     61.37%    12.8MB / 26.5MB   165
order-service-3     80.15%    632.5MiB / 1.5GiB   41.18%    13.4MB / 11.2MB   157
order-service-2     90.88%    717MiB / 1.5GiB     46.68%    13.2MB / 10.7MB   161
order-service       144.62%   818.8MiB / 1.5GiB   53.31%    23.3MB / 52.5MB   244
inventory-service   116.16%   644.4MiB / 1GiB     62.93%    12.6MB / 31.6MB   191
account-service     17.70%    626.7MiB / 1GiB     61.20%    1.95MB / 8.6MB    178
hazelcast-3         28.25%    319.2MiB / 768MiB   41.57%    27.7MB / 36.5MB   114
hazelcast-2         22.36%    328.8MiB / 768MiB   42.81%    23.4MB / 23.1MB   131
grafana             1.08%     71.51MiB / 256MiB   27.93%    58.5kB / 25.9kB   18
management-center   3.88%     468.8MiB / 512MiB   91.56%    10.6MB / 627kB    232
postgres            28.23%    192.9MiB / 256MiB   75.33%    58.9MB / 14.4MB   66
prometheus          0.00%     55.32MiB / 256MiB   21.61%    8.39MB / 159kB    15
hazelcast-1         27.53%    323.4MiB / 768MiB   42.11%    29.3MB / 35.7MB   119
jaeger              3.29%     131.9MiB / 256MiB   51.52%    10.2MB / 177kB    16
```

## P95 Latency Comparison (ASCII)

```
P95 Latency (ms) - lower is better
Scale: each # = 335.91 ms

http_req_duration 
  A #                                        78.80 ms
  B #                                        9823.84 ms

order_create      
  A #                                        111.32 ms
  B ######################################## 13436.32 ms

stock_reserve     
  A #                                        50.24 ms
  B #                                        813.50 ms

customer_create   
  A #                                        76.21 ms
  B #                                        1074.18 ms

saga_e2e          
  A #                                        974.25 ms
  B #                                        13210.05 ms

```

## Summary

**single-replica-clustered** wins on 4 of 4 p95 latency metrics.

- single-replica-clustered p95 wins: 4
- multi-replica-clustered p95 wins: 0
- Ties: 0

---
*Report generated by ab-compare.sh*
