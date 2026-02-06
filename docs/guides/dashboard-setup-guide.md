# Dashboard Setup Guide

This guide covers the observability stack — Grafana dashboards, Prometheus metrics, and Jaeger tracing — included in the Docker deployment.

## Prerequisites

The observability stack runs as part of the Docker Compose deployment. Start all services:

```bash
./scripts/start-docker.sh
```

Or manually:

```bash
cd docker
docker-compose up -d
```

All observability services start automatically alongside the application services.

## Grafana

**URL:** http://localhost:3000

**Login:** `admin` / `admin` (you will be prompted to change the password on first login)

### Pre-Provisioned Dashboards

Four dashboards are provisioned automatically and load on startup:

| Dashboard | Description |
|-----------|-------------|
| **System Overview** | Home dashboard — JVM memory, HTTP request rates, service health, cluster status |
| **Event Flow** | Event sourcing pipeline metrics — events submitted, processing latency, pipeline throughput |
| **Materialized Views** | View update rates, map sizes, entry processor performance |
| **Saga Dashboard** | Saga lifecycle metrics — started, completed, failed, timed-out, compensation rates |

The **System Overview** dashboard is set as the default home dashboard. It loads automatically when you open Grafana.

### Datasource

Prometheus is pre-configured as the default datasource. No manual setup is required.

- **Name:** Prometheus
- **URL:** `http://prometheus:9090` (internal Docker network)
- **Provisioned at:** `docker/grafana/provisioning/datasources/datasources.yml`

### Pre-Configured Alerts

Alerts are provisioned from `docker/grafana/provisioning/alerting/` and activate automatically.

**Saga Alerts** (checked every 1 minute):

| Alert | Severity | Condition |
|-------|----------|-----------|
| High Saga Failure Rate | Critical | Any saga failures in a 5-minute window (sustained 2 min) |
| Saga Timeouts Detected | Warning | Any saga timeouts in a 5-minute window (sustained 2 min) |
| Saga Compensation Failures | Critical | Any compensation failures in a 5-minute window (sustained 1 min) |
| Low Saga Success Rate | Warning | Success rate < 90% over 10 minutes (sustained 5 min) |

**Service Health Alerts** (checked every 1 minute):

| Alert | Severity | Condition |
|-------|----------|-----------|
| Service Down | Critical | Any of the 4 services reports `up < 1` (sustained 1 min) |
| High Event Processing Error Rate | Warning | Error rate > 5% over 5 minutes (sustained 3 min) |

Alert notifications are sent to the default email contact point (`admin@localhost`). In production, update `docker/grafana/provisioning/alerting/contactpoints.yml` with your notification channel (Slack, PagerDuty, etc.).

### Adding Custom Dashboards

1. Create your dashboard JSON file
2. Place it in `docker/grafana/dashboards/`
3. Restart Grafana: `docker-compose restart grafana`

Dashboards in this directory are auto-loaded by the provisioner (configured in `docker/grafana/provisioning/dashboards/dashboards.yml`). The provisioner checks for new dashboards every 30 seconds.

## Prometheus

**URL:** http://localhost:9090

Prometheus scrapes metrics from all services and Hazelcast nodes at a 15-second interval.

### Scrape Targets

| Job | Target(s) | Metrics Path |
|-----|-----------|--------------|
| `account-service` | account-service:8081 | `/actuator/prometheus` |
| `inventory-service` | inventory-service:8082 | `/actuator/prometheus` |
| `order-service` | order-service:8083 | `/actuator/prometheus` |
| `payment-service` | payment-service:8084 | `/actuator/prometheus` |
| `hazelcast` | hazelcast-1:5701, hazelcast-2:5701, hazelcast-3:5701 | `/hazelcast/rest/cluster` |
| `prometheus` | localhost:9090 | default |

Configuration: `docker/prometheus/prometheus.yml`

### Useful Queries

**Service Health:**
```promql
up{job=~".*-service"}
```

**HTTP Request Rate (per service):**
```promql
rate(http_server_requests_seconds_count[5m])
```

**Event Processing:**
```promql
rate(events_submitted_total[5m])
```

**Saga Metrics:**
```promql
# Saga completion rate
rate(saga_completed_total[5m])

# Saga failure rate
rate(saga_failed_total[5m])

# Saga duration (p99)
histogram_quantile(0.99, rate(saga_duration_seconds_bucket[5m]))

# Active timeouts
increase(saga_timeouts_detected_total[5m])
```

**JVM Memory:**
```promql
jvm_memory_used_bytes{area="heap"}
```

### Verifying Targets

Open http://localhost:9090/targets to confirm all scrape targets are UP. If a service target shows DOWN, check that the service is healthy:

```bash
docker-compose ps
docker-compose logs <service-name>
```

## Jaeger

**URL:** http://localhost:16686

Jaeger collects distributed traces via OTLP (OpenTelemetry Protocol), enabling you to trace requests across service boundaries.

### Viewing Traces

1. Open the Jaeger UI at http://localhost:16686
2. Select a service from the **Service** dropdown
3. Click **Find Traces**
4. Click on a trace to see the span breakdown across services

### What to Look For

- **Saga flow traces** — Follow an order placement through Order → Inventory → Payment → Order confirmation
- **Event processing latency** — Identify slow pipeline stages
- **Cross-service hops** — See how events propagate between services via Hazelcast ITopic

### Configuration

Jaeger runs as a single all-in-one container in the Docker stack:
- Receives traces via OTLP on port 4317 (gRPC) and 4318 (HTTP)
- UI accessible on port 16686
- Configured via environment variables in `docker/docker-compose.yml`

## File Reference

```
docker/
├── docker-compose.yml              # Service definitions including Grafana, Prometheus, Jaeger
├── prometheus/
│   └── prometheus.yml              # Scrape configuration
└── grafana/
    ├── dashboards/
    │   ├── system-overview.json    # Home dashboard
    │   ├── event-flow.json         # Event sourcing metrics
    │   ├── materialized-views.json # View update metrics
    │   └── saga-dashboard.json     # Saga lifecycle metrics
    └── provisioning/
        ├── datasources/
        │   └── datasources.yml     # Prometheus datasource
        ├── dashboards/
        │   └── dashboards.yml      # Dashboard auto-loading config
        └── alerting/
            ├── alerts.yml          # Alert rule definitions
            ├── contactpoints.yml   # Notification channels
            └── policies.yml        # Alert routing policies
```

## Troubleshooting

### Grafana shows "No data"

1. Verify Prometheus is running: http://localhost:9090
2. Check Prometheus targets are UP: http://localhost:9090/targets
3. In Grafana, go to **Connections > Data sources > Prometheus > Test** to verify connectivity
4. Ensure services have been running long enough to produce metrics (at least 30 seconds)

### Prometheus target is DOWN

```bash
# Check if the service is running
docker-compose ps

# Check service logs for errors
docker-compose logs <service-name>

# Verify the metrics endpoint is accessible
curl http://localhost:8081/actuator/prometheus
```

### Jaeger shows no traces

1. Verify Jaeger is running: http://localhost:16686
2. Ensure the services have OTLP tracing configured
3. Generate some traffic (e.g., run the demo scenarios) and wait a few seconds
4. Check Jaeger logs: `docker-compose logs jaeger`

## References

- [Saga Pattern Guide](saga-pattern-guide.md) — Understanding saga metrics
- [Docker Deployment](../../docker/README.md) — Full Docker stack documentation
- [Grafana Documentation](https://grafana.com/docs/grafana/latest/) — Official Grafana docs
- [Prometheus Documentation](https://prometheus.io/docs/) — Official Prometheus docs
