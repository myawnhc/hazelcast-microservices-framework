# Baseline Performance Measurements

**Date:** 2026-02-17
**Environment:** Docker Compose (local), macOS
**Tool:** k6 v1.6.1 (`constant-arrival-rate` executor, fixes coordinated omission)

---

## Test Environment

| Component | Configuration |
|-----------|--------------|
| **Host** | macOS (Apple Silicon), Docker Desktop |
| **Docker Memory** | ~4.5 GB allocated across all services |
| **Hazelcast Cluster** | 3 nodes, Community Edition 5.6.0 |
| **Services** | account (8081), inventory (8082), order (8083), payment (8084) |
| **Database** | PostgreSQL 16 (write-behind persistence enabled) |
| **JVM** | Java 25, 512MB heap per service |
| **Network** | Docker bridge (172.28.0.0/16) |

## Test Configuration

| Parameter | Value |
|-----------|-------|
| **Duration** | 3 minutes per test (`constant-arrival-rate`) |
| **Workload Mix** | 60% create orders, 25% stock reserves, 15% customer creates |
| **Sample Data** | 100 customers, 100 products (from `generate-sample-data.sh`) |
| **k6 Executor** | `constant-arrival-rate` (true TPS, not iteration-based) |
| **Saga Tracking** | 10% of order creates poll for saga completion (10s timeout) |

---

## Baseline Results

### Concurrency Level: 10 TPS

| Metric | Value |
|--------|-------|
| **Total Iterations** | 1,801 |
| **Total HTTP Requests** | 2,551 |
| **Successful** | 2,551 |
| **Failed** | 0 |
| **Effective TPS** | 9.98 |
| **Error Rate** | 0.00% |
| **http_req_duration p50** | 15.76ms |
| **http_req_duration p95** | 27.44ms |
| **http_req_duration p99** | _not reported_ |

| Per-Operation Latency | avg | p50 | p90 | p95 | max |
|------------------------|-----|-----|-----|-----|-----|
| **order_create** | 21.99ms | 18.12ms | 25.72ms | 29.99ms | 704.77ms |
| **stock_reserve** | 20.43ms | 17.13ms | 23.36ms | 25.88ms | 571.09ms |
| **customer_create** | 21.55ms | 18.03ms | 27.22ms | 31.78ms | 523.20ms |
| **saga_e2e** | 1,550ms | 1,430ms | 2,040ms | 2,440ms | 2,660ms |

### Concurrency Level: 25 TPS

| Metric | Value |
|--------|-------|
| **Total Iterations** | 4,501 |
| **Total HTTP Requests** | 7,544 |
| **Successful** | 7,543 |
| **Failed** | 1 |
| **Effective TPS** | 24.68 |
| **Error Rate** | 0.01% |
| **http_req_duration p50** | 12.60ms |
| **http_req_duration p95** | 21.43ms |
| **http_req_duration p99** | _not reported_ |

| Per-Operation Latency | avg | p50 | p90 | p95 | max |
|------------------------|-----|-----|-----|-----|-----|
| **order_create** | 16.49ms | 16.10ms | 21.24ms | 22.85ms | 56.31ms |
| **stock_reserve** | 15.99ms | 15.54ms | 20.49ms | 22.09ms | 85.24ms |
| **customer_create** | 16.70ms | 16.25ms | 21.54ms | 23.69ms | 62.84ms |
| **saga_e2e** | 2,180ms | 2,230ms | 2,790ms | 2,840ms | 3,050ms |

### Concurrency Level: 50 TPS

| Metric | Value |
|--------|-------|
| **Total Iterations** | 8,990 |
| **Total HTTP Requests** | 32,523 |
| **Successful** | 32,290 |
| **Failed** | 233 |
| **Effective TPS** | 47.37 |
| **Error Rate** | 0.71% (HTTP), 2.59% (mixed workload) |
| **Dropped Iterations** | 11 |
| **Max VUs Used** | 61 (exceeded pre-allocated 50) |
| **http_req_duration p50** | 0.92ms |
| **http_req_duration p95** | 18.60ms |
| **http_req_duration p99** | _not reported_ |

| Per-Operation Latency | avg | p50 | p90 | p95 | max |
|------------------------|-----|-----|-----|-----|-----|
| **order_create** | 15.72ms | 15.21ms | 20.17ms | 22.47ms | 101.49ms |
| **stock_reserve** | 13.83ms | 14.33ms | 19.47ms | 21.24ms | 60.47ms |
| **customer_create** | 15.86ms | 15.62ms | 20.24ms | 21.92ms | 49.79ms |
| **saga_e2e** | 8,890ms | 10,080ms | 10,110ms | 10,130ms | 10,160ms |

**50 TPS Notes:**
- Saga e2e timed out for most tracked orders (10s timeout exceeded)
- VUs grew to 61 (backpressure from saga polling iterations holding VUs open)
- 233 HTTP errors (2.59% of iterations) — likely saga-related failures under load
- p50 of overall http_req_duration drops to 0.92ms because saga poll GETs (fast reads) dominate the request count

---

## Prometheus Metrics Snapshot

> Captured after the 50 TPS test run (cumulative across all test runs).

### Pipeline Latency (from Prometheus)

| Metric | p50 | p95 | p99 |
|--------|-----|-----|-----|
| `eventsourcing_pipeline_latency_end_to_end_seconds` | 8.7ms | 16.8ms | 21.7ms |
| `eventsourcing_pipeline_latency_queue_wait_seconds` | 0.7ms | 2.1ms | 3.1ms |
| `eventsourcing_pipeline_stage_duration_seconds{stage="persist"}` | 1.9ms | 6.5ms | 7.5ms |
| `eventsourcing_pipeline_stage_duration_seconds{stage="update_view"}` | 1.9ms | 6.4ms | 7.0ms |
| `eventsourcing_pipeline_stage_duration_seconds{stage="publish"}` | 0.5ms | 1.0ms | 2.0ms |

### Saga Metrics

| Metric | Value |
|--------|-------|
| `sagas_active_count` | 1,933 |
| `saga_started_total` | 9,256 |
| `saga_completed_total` | 7,323 |
| `saga_compensated_total` | 0 |
| `saga_failed_total` | 0 |
| `saga_timedout_total` | 0 |
| **Saga completion rate** | 79.1% (7,323 / 9,256) |

### System Resources

| Metric | account-service | inventory-service | order-service | payment-service |
|--------|----------------|-------------------|---------------|-----------------|
| `jvm_memory_used_bytes{area="heap"}` | 60 MB | 16 MB | 46 MB | 37 MB |
| `jvm_threads_live_threads` | 144 | 151 | 140 | 152 |
| `jvm_gc_pause_seconds_count` | 557 | 594 | 640 | 558 |

---

## Observations

### Performance Notes
- At 10 and 25 TPS, all thresholds pass with 0% error rate (or negligible 0.01%)
- Per-request latency is remarkably consistent across operation types (~15-22ms avg)
- Pipeline end-to-end latency (p95: 16.8ms) is well within acceptable range
- Queue wait time is negligible (p95: 2.1ms) — pipeline is not queue-bound
- `persist` and `update_view` stages contribute equally (~2ms p50 each); `publish` is the cheapest (0.5ms)
- JVM heap usage is modest: 16-60MB across services (well under 512MB limit)
- Thread counts stable at ~140-152 per service

### Bottleneck Indicators
- **Saga completion is the primary bottleneck**: 1,933 sagas still active (stuck), 79.1% completion rate
- At 50 TPS, saga e2e times out for most orders — saga orchestration can't keep up with throughput
- The saga backlog causes VU accumulation (VUs held open waiting for saga polls), which led to 61 VUs at 50 TPS
- Circuit breaker on `inventory-stock-reservation` tripped during initial smoke test (stale product IDs) — highlights fragility when product data is missing
- Max latencies at 10 TPS (500-700ms) suggest occasional GC pauses or IMap contention

### Metric Gaps Found
- `saga_duration_seconds` histogram returns no data despite 7,323 completions — may not be recording properly
- Gateway latency (`spring_cloud_gateway_routes_count` present but no `gateway_request_duration_seconds_bucket`) — tests hit services directly, not through gateway
- No `eventsourcing.events.inflight` gauge (identified in metrics-inventory.md gap #1)
- No IMap operation timing or ITopic publish duration metrics

---

## Next Steps

1. Investigate saga completion bottleneck — why are 1,933 sagas stuck active?
2. Profile hotspots with async-profiler (Session 5) to find CPU-level bottlenecks
3. Run gateway-routed tests (`--gateway` flag) to measure gateway overhead
4. Add missing metrics identified above (Session 3 remaining work)
5. Investigate `saga_duration_seconds` histogram not recording data
