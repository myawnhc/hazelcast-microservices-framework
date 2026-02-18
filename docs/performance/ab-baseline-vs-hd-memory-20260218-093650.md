# A/B Test Comparison Report

**Generated**: 2026-02-18T14:46:04Z
**Variant A**: community-baseline
**Variant B**: hd-memory

## Configuration

| Setting | community-baseline | hd-memory |
|---------|--------------------|-----------|
| Config | community-baseline | hd-memory |
| Compose files | docker-compose.yml | docker-compose.yml, docker-compose-hd-memory.yml |
| k6 scenario | constant | constant |
| Target TPS | 50 | 50 |
| Duration | 3m | 3m |

## Throughput

| Metric | community-baseline | hd-memory | Delta |
|--------|--------------------|-----------|-------|
| Iterations | 8985 | 8990 | 0.09 (0%) v |
| Iterations/s | 47.17 | 47.26 | 0.09 (0%) v |
| HTTP Requests | 32972 | 33456 | |
| HTTP req/s | 173.10 | 175.87 | |
| Test duration (s) | 190.48 | 190.23 | |
| Data received | 19351917 B | 19643317 B | |
| Data sent | 7088904 B | 7176458 B | |

## Latency (ms)

### HTTP Request Duration (all endpoints)

| Percentile | community-baseline | hd-memory | Delta |
|------------|--------------------|-----------|-------|
| avg | 5.69 | 5.96 | 0.27 (0%) ^ |
| p50 | 1.08 | 1.10 | 0.02 (0%) ^ |
| p90 | 17.48 | 17.72 | 0.24 (0%) ^ |
| p95 | 19.96 | 20.54 | 0.58 (0%) ^ |
| max | 314.60 | 384.22 | 69.62 (20.0%) ^ |

### Order Create

| Stat | community-baseline | hd-memory | Delta |
|------|--------------------|-----------|-------|
| avg | 17.20 | 17.92 | 0.72 (0%) ^ |
| p50 | 15.83 | 16.06 | 0.23 (0%) ^ |
| p95 | 25.35 | 28.75 | 3.40 (10.0%) ^ |

### Stock Reserve

| Stat | community-baseline | hd-memory | Delta |
|------|--------------------|-----------|-------|
| avg | 16.27 | 17.24 | 0.97 (0%) ^ |
| p50 | 15.82 | 15.99 | 0.17 (0%) ^ |
| p95 | 23.34 | 26.28 | 2.94 (10.0%) ^ |

### Customer Create

| Stat | community-baseline | hd-memory | Delta |
|------|--------------------|-----------|-------|
| avg | 17.51 | 18.76 | 1.25 (0%) ^ |
| p50 | 16.68 | 16.91 | 0.24 (0%) ^ |
| p95 | 25.82 | 32.01 | 6.19 (20.0%) ^ |

### Saga E2E

| Stat | community-baseline | hd-memory | Delta |
|------|--------------------|-----------|-------|
| avg | 8744.98 | 8991.03 | 246.04 (0%) ^ |
| p50 | 10087.00 | 10092.00 | 5.00 (0%) ^ |
| p95 | 10141.30 | 10141.00 | -0.30 (0%) v |

## Error Rates

| Metric | community-baseline | hd-memory |
|--------|--------------------|-----------|
| HTTP failure rate | 0.08% | 0.10% |
| HTTP failures | 25 | 33 |
| Mixed workload errors | 0.28% | 0.37% |

## Resource Usage (Docker Stats)

### community-baseline

```
NAME                CPU %     MEM USAGE / LIMIT   MEM %     NET I/O           PIDS
api-gateway         0.46%     254.3MiB / 256MiB   99.34%    42.9kB / 552kB    88
order-service       22.94%    433.2MiB / 512MiB   84.60%    27.2MB / 51.8MB   169
inventory-service   24.34%    395.7MiB / 512MiB   77.28%    8.64MB / 16.5MB   163
account-service     14.58%    357.8MiB / 512MiB   69.89%    1.02MB / 4.42MB   160
payment-service     20.56%    377.6MiB / 512MiB   73.75%    8.76MB / 13.4MB   162
hazelcast-3         4.22%     279.1MiB / 512MiB   54.51%    21.9MB / 20.1MB   115
grafana             1.14%     76.43MiB / 256MiB   29.86%    43.8kB / 22.6kB   18
hazelcast-2         5.52%     275.6MiB / 512MiB   53.83%    26.2MB / 28.2MB   107
management-center   2.22%     473.3MiB / 512MiB   92.44%    9.35MB / 588kB    232
postgres            0.00%     24.01MiB / 256MiB   9.38%     5.05kB / 126B     6
prometheus          0.40%     49.85MiB / 256MiB   19.47%    7.81MB / 134kB    15
jaeger              0.53%     183MiB / 256MiB     71.47%    11.8MB / 117kB    15
hazelcast-1         5.01%     269.3MiB / 512MiB   52.61%    20.4MB / 18.5MB   112
```

### hd-memory

```
NAME                CPU %     MEM USAGE / LIMIT   MEM %     NET I/O           PIDS
api-gateway         0.88%     253.3MiB / 256MiB   98.95%    43kB / 516kB      87
payment-service     21.38%    368.7MiB / 512MiB   72.02%    8.68MB / 13.3MB   160
account-service     18.68%    373MiB / 512MiB     72.85%    1.02MB / 4.32MB   157
order-service       26.57%    426MiB / 512MiB     83.21%    27.3MB / 52.2MB   168
inventory-service   20.25%    395.1MiB / 512MiB   77.16%    8.62MB / 16.6MB   165
hazelcast-3         7.37%     343.9MiB / 1GiB     33.59%    19.8MB / 17.5MB   116
hazelcast-2         11.09%    346.1MiB / 1GiB     33.80%    21.9MB / 20MB     109
grafana             0.05%     83.9MiB / 256MiB    32.77%    42.8kB / 20.7kB   16
management-center   4.93%     469.8MiB / 512MiB   91.76%    9.9MB / 591kB     232
prometheus          0.21%     51.69MiB / 256MiB   20.19%    7.81MB / 135kB    15
hazelcast-1         5.64%     363.2MiB / 1GiB     35.46%    27.9MB / 30.7MB   114
postgres            0.00%     23.86MiB / 256MiB   9.32%     5.18kB / 126B     6
jaeger              3.83%     172.5MiB / 256MiB   67.39%    11.9MB / 110kB    16
```

## P95 Latency Comparison (ASCII)

```
P95 Latency (ms) - lower is better
Scale: each # = 253.53 ms

http_req_duration 
  A #                                        19.96 ms
  B #                                        20.54 ms

order_create      
  A #                                        25.35 ms
  B #                                        28.75 ms

stock_reserve     
  A #                                        23.34 ms
  B #                                        26.28 ms

customer_create   
  A #                                        25.82 ms
  B #                                        32.01 ms

saga_e2e          
  A ######################################## 10141.30 ms
  B #                                        10141.00 ms

```

## Summary

**community-baseline** wins on 4 of 4 p95 latency metrics.

- community-baseline p95 wins: 4
- hd-memory p95 wins: 0
- Ties: 0

---
*Report generated by ab-compare.sh*
