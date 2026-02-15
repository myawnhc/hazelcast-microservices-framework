# Phase 3, Day 17: API Gateway, MCP Server & Monitoring Helm Charts

**Date**: 2026-02-17
**Area**: 4 — Kubernetes Deployment (Days 15–18)
**Goal**: Complete the three remaining subcharts — API Gateway (with Ingress for external access), MCP Server, and Monitoring (Prometheus, Grafana, Jaeger) — turning our stubs into fully templated Helm charts.

---

## Kubernetes Concepts Explained

Days 15–16 covered StatefulSets, Deployments, health probes, and ConfigMaps-as-environment-variables. Today introduces three new concepts that arise because Gateway, MCP, and Monitoring have different concerns from the core microservices.

### Ingress — The Single Front Door to Your Cluster

In Days 15–16, every Service we created was `ClusterIP` — accessible only from within the cluster. That's correct for internal microservices (account, inventory, order, payment), but the API Gateway is meant to receive traffic from **outside** the cluster (browser apps, CLI tools, external clients).

Kubernetes offers three ways to expose a service externally:

| Method | How It Works | When to Use |
|--------|-------------|-------------|
| **NodePort** | Opens a static port (30000–32767) on every cluster node | Quick demos, local development (minikube) |
| **LoadBalancer** | Provisions a cloud load balancer (AWS ALB, GCP LB, Azure LB) | Production on a cloud provider |
| **Ingress** | An HTTP reverse proxy inside the cluster, fronted by a LoadBalancer or NodePort | Production HTTP/HTTPS routing with path-based or host-based rules |

**Why Ingress is the right choice for our gateway**:

Our API Gateway is itself a reverse proxy (Spring Cloud Gateway). In theory, we could use a `LoadBalancer` Service directly. But Ingress adds value even in front of another proxy:

1. **TLS termination** — the Ingress Controller handles HTTPS certificates (via cert-manager), so the gateway receives plain HTTP internally.
2. **Host-based routing** — if you later add a separate frontend app or admin UI, one Ingress can route `api.example.com` to the gateway and `admin.example.com` to another service.
3. **Cloud portability** — the Ingress YAML is the same across AWS, GCP, and Azure. The only thing that changes is the Ingress Controller implementation (nginx, ALB Ingress Controller, etc.).
4. **Standardized annotations** — rate limiting, CORS, timeouts, and redirects can be configured via annotations, which many Ingress Controllers understand.

**How Ingress works internally**:

```
Internet
    │
    ▼
┌──────────────────────────────────────────┐
│ Ingress Controller (nginx, ALB, etc.)    │  ← Watches Ingress resources
│  - TLS termination                       │  ← Configures itself from annotations
│  - Host/path routing                     │
└──────────────┬───────────────────────────┘
               │
               ▼
┌──────────────────────────┐
│ ClusterIP Service:       │
│   api-gateway:8080       │  ← Normal ClusterIP, no external exposure
└──────────────┬───────────┘
               │
               ▼
┌──────────────────────────┐
│ api-gateway Pod          │  ← Spring Cloud Gateway
│   → routes to microsvcs  │
└──────────────────────────┘
```

The Ingress Controller is a separate pod (typically deployed as a DaemonSet or Deployment with a `LoadBalancer` or `NodePort` Service). When you create an Ingress resource, the controller reads it and updates its routing configuration. You don't install the Ingress Controller via our Helm chart — it's a cluster prerequisite, like the Kubernetes DNS addon.

**Ingress resource anatomy**:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: api-gateway-ingress
  annotations:
    # These are Ingress Controller-specific — nginx examples shown
    nginx.ingress.kubernetes.io/proxy-body-size: "10m"
    nginx.ingress.kubernetes.io/proxy-read-timeout: "60"
spec:
  ingressClassName: nginx              # Which Ingress Controller handles this
  rules:
    - host: api.example.com            # Optional — omit for any hostname
      http:
        paths:
          - path: /                     # Route all paths to gateway
            pathType: Prefix
            backend:
              service:
                name: api-gateway
                port:
                  number: 8080
  tls:                                  # Optional — enables HTTPS
    - hosts:
        - api.example.com
      secretName: api-tls-cert          # Certificate stored as a K8s Secret
```

**For local development** (minikube/kind), you enable the Ingress addon:
```bash
minikube addons enable ingress    # Installs nginx Ingress Controller
```

### ConfigMap Volume Mounts — Configuration as Files, Not Environment Variables

On Day 16, we used ConfigMaps as environment variable sources (`envFrom: configMapRef`). Today we need ConfigMaps as **file mounts** for Prometheus and Grafana.

**Why files instead of environment variables?**

Environment variables work well for key-value settings (`SPRING_PROFILES_ACTIVE=kubernetes`). But Prometheus needs a `prometheus.yml` file, and Grafana needs JSON dashboard files and YAML provisioning files. These are structured, multi-line documents that don't fit in environment variables.

**How file mounts work**:

```yaml
# 1. Create a ConfigMap with file content
apiVersion: v1
kind: ConfigMap
metadata:
  name: prometheus-config
data:
  prometheus.yml: |          # Key = filename, Value = file content
    global:
      scrape_interval: 15s
    scrape_configs:
      - job_name: 'account-service'
        ...

---
# 2. Mount it as a volume in the pod
spec:
  containers:
    - name: prometheus
      volumeMounts:
        - name: config-volume
          mountPath: /etc/prometheus     # Directory in the container
          readOnly: true
  volumes:
    - name: config-volume
      configMap:
        name: prometheus-config          # Reference the ConfigMap
```

When the pod starts, Kubernetes creates the file `/etc/prometheus/prometheus.yml` inside the container with the content from the ConfigMap. If the ConfigMap has multiple keys, each key becomes a separate file in the mount directory.

**Key behaviors to understand**:

| Behavior | Details |
|----------|---------|
| **Auto-update** | When you update a ConfigMap, the mounted files update automatically (within ~30-60s). Environment variables do NOT auto-update. |
| **Read-only** | Volume mounts from ConfigMaps should be `readOnly: true`. The application can't write back to the ConfigMap. |
| **subPath** | If you need to mount a single file without replacing the entire directory, use `subPath`. Without it, the mount replaces ALL files in the target directory. |
| **Size limit** | ConfigMaps have a 1MiB size limit. For large dashboards, consider multiple ConfigMaps or external storage. |

**Comparison: `envFrom` vs volume mount**:

```
Day 16 pattern (env vars):          Day 17 pattern (file mounts):
┌─────────────────────┐             ┌─────────────────────┐
│ ConfigMap           │             │ ConfigMap           │
│  KEY1: "value1"     │  envFrom    │  app.yml: |         │  volume
│  KEY2: "value2"     │ ─────────►  │    server:          │  mount
│                     │  env vars   │      port: 8080     │ ─────────►
└─────────────────────┘             └─────────────────────┘
                                            ▼
Pod env:                            Pod filesystem:
  KEY1=value1                         /etc/config/app.yml
  KEY2=value2
```

### Multi-Component Subcharts — One Chart, Multiple Deployments

The monitoring subchart is different from all our other subcharts because it contains **three independent applications** (Prometheus, Grafana, Jaeger) in a single chart. This is a common pattern for "supporting infrastructure" that's deployed as a unit.

**Why bundle them instead of separate charts?**

1. **Lifecycle coupling** — you want all monitoring components deployed together. If you delete monitoring, all three go away.
2. **Shared configuration** — Grafana's datasource config points to Prometheus's Service name. Having them in the same chart makes it easy to use Helm template references.
3. **Conditional deployment** — a single `monitoring.enabled: true/false` controls all three. Individual components can also be toggled: `monitoring.grafana.enabled: false` to skip Grafana.
4. **Simplified dependency management** — the umbrella chart has one dependency (`monitoring`) instead of three.

**Template organization for multi-component charts**:

```
monitoring/
├── templates/
│   ├── prometheus-deployment.yaml      # Prefix with component name
│   ├── prometheus-service.yaml
│   ├── prometheus-configmap.yaml
│   ├── grafana-deployment.yaml
│   ├── grafana-service.yaml
│   ├── grafana-configmap-datasources.yaml
│   ├── grafana-configmap-dashboards.yaml
│   ├── jaeger-deployment.yaml
│   ├── jaeger-service.yaml
│   └── _helpers.tpl
└── values.yaml                         # Sections: prometheus, grafana, jaeger
```

Each template is wrapped in an `{{- if .Values.{component}.enabled }}` block so individual components can be toggled.

### Prometheus Service Discovery in Kubernetes

In Docker Compose, we hardcoded scrape targets in `prometheus.yml`:

```yaml
# Docker Compose — static targets
scrape_configs:
  - job_name: 'account-service'
    static_configs:
      - targets: ['account-service:8081']
```

Kubernetes offers **two approaches** for Prometheus to find scrape targets:

**Approach 1: Static targets (what we'll use)**

Translate Docker Compose hostnames to Kubernetes Service DNS names. Simple and predictable:

```yaml
scrape_configs:
  - job_name: 'account-service'
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['{{ .Release.Name }}-account-service:8081']
```

**Approach 2: Kubernetes SD (service discovery)**

Prometheus can query the Kubernetes API to automatically discover all pods with a specific annotation. This is more dynamic — new services are automatically scraped without updating the config:

```yaml
scrape_configs:
  - job_name: 'kubernetes-pods'
    kubernetes_sd_configs:
      - role: pod
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: true
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
        target_label: __metrics_path__
```

Then pods opt in with annotations:
```yaml
annotations:
  prometheus.io/scrape: "true"
  prometheus.io/path: "/actuator/prometheus"
  prometheus.io/port: "8081"
```

**Why we're using static targets**: Our service list is fixed and small. Kubernetes SD requires RBAC permissions for Prometheus to list pods, adds complexity, and makes the scrape config harder to understand. Static targets are the right choice for an educational framework. We'll mention SD in comments for users who want to upgrade.

### The MCP Server's Unique Characteristics

The MCP Server subchart looks similar to the microservices from Day 16, but with key differences:

1. **No Hazelcast dependency** — the MCP server is a pure REST proxy. It doesn't connect to Hazelcast at all. No init container needed to wait for Hazelcast.
2. **Depends on all four microservices** — it needs `ACCOUNT_SERVICE_URL`, `INVENTORY_SERVICE_URL`, `ORDER_SERVICE_URL`, and `PAYMENT_SERVICE_URL` to proxy requests.
3. **HTTP transport in Kubernetes** — locally it runs as stdio (for Claude Desktop), but in Kubernetes it runs as an HTTP/SSE server on port 8085 using the `docker` profile (which also enables HTTP transport).
4. **Optional deployment** — `mcp-server.enabled: false` by default in the umbrella chart. Not everyone needs AI integration.

### The API Gateway's Unique Characteristics

The API Gateway subchart also differs from the Day 16 microservices:

1. **No Hazelcast dependency** — Spring Cloud Gateway doesn't use Hazelcast. No init container for Hazelcast.
2. **Routes to all four microservices** — needs `ACCOUNT_SERVICE_URL`, `INVENTORY_SERVICE_URL`, `ORDER_SERVICE_URL`, and `PAYMENT_SERVICE_URL`.
3. **External-facing** — needs an Ingress resource for cluster-external access.
4. **Lighter resources** — 128Mi/256Mi memory (it's a reverse proxy, not a JVM app with embedded Hazelcast). Actually, it IS a JVM app (Spring Cloud Gateway), but it doesn't embed Hazelcast, so it uses less memory.
5. **Wait for microservices** — an init container waits for at least one microservice to be ready before the gateway starts routing.

---

## Docker Compose → Kubernetes Mapping (Day 17 Services)

### API Gateway

| Docker Compose | Kubernetes | Location |
|----------------|-----------|----------|
| `image: hazelcast-microservices/api-gateway:latest` | Deployment `.spec.template.spec.containers[].image` | `deployment.yaml` |
| `ports: "8080:8080"` | Service port 8080 + Ingress | `service.yaml`, `ingress.yaml` |
| `SPRING_PROFILES_ACTIVE=docker` | ConfigMap: `kubernetes` profile | `configmap.yaml` |
| `ACCOUNT_SERVICE_URL=http://account-service:8081` | ConfigMap: `http://{release}-account-service:8081` | `configmap.yaml` |
| `deploy.resources.limits.memory: 256M` | `resources.limits.memory: 256Mi` | `deployment.yaml` |
| `depends_on: account-service` | Init container waits for account-service | `deployment.yaml` |
| Bridge network | K8s pod networking (automatic) | N/A |

### MCP Server

| Docker Compose | Kubernetes | Location |
|----------------|-----------|----------|
| `image: hazelcast-microservices/mcp-server:latest` | Deployment image | `deployment.yaml` |
| `ports: "8085:8085"` | Service port 8085 (ClusterIP, internal) | `service.yaml` |
| `SPRING_PROFILES_ACTIVE=docker` | ConfigMap: `docker` profile (HTTP transport) | `configmap.yaml` |
| `MCP_SERVICES_ACCOUNT_URL=http://account-service:8081` | ConfigMap: `http://{release}-account-service:8081` | `configmap.yaml` |
| `deploy.resources.limits.memory: 256M` | `resources.limits.memory: 256Mi` | `deployment.yaml` |

### Monitoring (Prometheus)

| Docker Compose | Kubernetes | Location |
|----------------|-----------|----------|
| `image: prom/prometheus:v2.48.0` | Deployment image | `prometheus-deployment.yaml` |
| `ports: "9090:9090"` | Service port 9090 (ClusterIP) | `prometheus-service.yaml` |
| `volumes: ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml` | ConfigMap volume mount | `prometheus-configmap.yaml` |

### Monitoring (Grafana)

| Docker Compose | Kubernetes | Location |
|----------------|-----------|----------|
| `image: grafana/grafana:10.3.1` | Deployment image | `grafana-deployment.yaml` |
| `ports: "3000:3000"` | Service port 3000 (ClusterIP) | `grafana-service.yaml` |
| `volumes: ./grafana/provisioning` | ConfigMap volume mounts | `grafana-configmap-*.yaml` |
| `volumes: ./grafana/dashboards` | ConfigMap volume mounts | `grafana-configmap-dashboards.yaml` |
| `GF_SECURITY_ADMIN_PASSWORD=admin` | Env var in Deployment | `grafana-deployment.yaml` |

### Monitoring (Jaeger)

| Docker Compose | Kubernetes | Location |
|----------------|-----------|----------|
| `image: jaegertracing/all-in-one:1.53` | Deployment image | `jaeger-deployment.yaml` |
| `ports: "16686:16686"` | Service port 16686 (Jaeger UI) | `jaeger-service.yaml` |
| `ports: "4317:4317"` | Service port 4317 (OTLP gRPC) | `jaeger-service.yaml` |
| `COLLECTOR_OTLP_ENABLED=true` | Env var in Deployment | `jaeger-deployment.yaml` |

---

## Implementation Plan

### Task 1: API Gateway — Update `values.yaml`

Extend the existing stub `values.yaml` with gateway-specific configuration:

```yaml
replicaCount: 1

image:
  repository: hazelcast-microservices/api-gateway
  tag: "latest"
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 8080

# Ingress for external access
ingress:
  enabled: true
  className: ""                  # Empty = use cluster default Ingress Controller
  annotations: {}
  hosts:
    - host: ""                   # Empty = match any hostname
      paths:
        - path: /
          pathType: Prefix
  tls: []

# JVM configuration (lighter than microservices — no embedded Hazelcast)
jvm:
  xms: "128m"
  xmx: "256m"
  opts: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Spring configuration
spring:
  profiles: "kubernetes"

# Upstream service URLs (Kubernetes Service DNS)
services:
  account:
    url: ""      # Default constructed from Release.Name in configmap template
  inventory:
    url: ""
  order:
    url: ""
  payment:
    url: ""

# OpenTelemetry / tracing
tracing:
  enabled: true
  endpoint: "http://jaeger:4317"

# Health probes (gateway starts faster — no Jet pipeline)
probes:
  startup:
    initialDelaySeconds: 15
    periodSeconds: 5
    failureThreshold: 12
    timeoutSeconds: 3
  liveness:
    initialDelaySeconds: 0
    periodSeconds: 30
    failureThreshold: 3
    timeoutSeconds: 3
  readiness:
    initialDelaySeconds: 0
    periodSeconds: 10
    failureThreshold: 3
    timeoutSeconds: 3

# Init container — wait for at least one microservice
initContainers:
  waitForServices:
    enabled: true
    image: busybox:1.36

# Resource management (lighter than microservices)
resources:
  requests:
    memory: "128Mi"
    cpu: "250m"
  limits:
    memory: "256Mi"
    cpu: "500m"

serviceAccount:
  create: true
  name: ""
  annotations: {}

podAnnotations: {}
podSecurityContext: {}
securityContext: {}
nodeSelector: {}
tolerations: []
affinity: {}
```

### Task 2: API Gateway — Create Templates

Create the following templates:

**`templates/deployment.yaml`** — Similar to Day 16 microservices but:
- Init container waits for `account-service:8081` (not hazelcast-cluster)
- No `HZ_LICENSEKEY` secret reference (gateway doesn't use Hazelcast)
- Lighter startup probe timing (faster startup without Jet pipeline)

**`templates/service.yaml`** — Standard ClusterIP Service on port 8080.

**`templates/configmap.yaml`** — Environment variables including:
- `SPRING_PROFILES_ACTIVE`: kubernetes
- `JAVA_OPTS`: JVM settings
- `ACCOUNT_SERVICE_URL`: `http://{release}-account-service:8081`
- `INVENTORY_SERVICE_URL`: `http://{release}-inventory-service:8082`
- `ORDER_SERVICE_URL`: `http://{release}-order-service:8083`
- `PAYMENT_SERVICE_URL`: `http://{release}-payment-service:8084`
- `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_SERVICE_NAME` (if tracing enabled)

**`templates/ingress.yaml`** — Ingress resource (gated by `.Values.ingress.enabled`):
- Routes all traffic to the gateway ClusterIP Service
- Supports configurable host, path, TLS
- Annotations passthrough for Ingress Controller-specific settings

**`templates/serviceaccount.yaml`** — Same pattern as Day 16.

**`templates/NOTES.txt`** — Post-install instructions including Ingress URL.

**Update `templates/_helpers.tpl`** — Add `serviceAccountName` helper.

### Task 3: MCP Server — Update `values.yaml`

Extend the existing stub with MCP-specific configuration:

```yaml
replicaCount: 1

image:
  repository: hazelcast-microservices/mcp-server
  tag: "latest"
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 8085

# JVM configuration
jvm:
  xms: "128m"
  xmx: "256m"
  opts: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Spring configuration — uses 'docker' profile for HTTP/SSE transport
spring:
  profiles: "docker"

# Upstream service URLs
services:
  account:
    url: ""
  inventory:
    url: ""
  order:
    url: ""
  payment:
    url: ""

# Health probes
probes:
  startup:
    initialDelaySeconds: 15
    periodSeconds: 5
    failureThreshold: 12
    timeoutSeconds: 3
  liveness:
    initialDelaySeconds: 0
    periodSeconds: 30
    failureThreshold: 3
    timeoutSeconds: 3
  readiness:
    initialDelaySeconds: 0
    periodSeconds: 10
    failureThreshold: 3
    timeoutSeconds: 3

# Init container — wait for at least one microservice
initContainers:
  waitForServices:
    enabled: true
    image: busybox:1.36

resources:
  requests:
    memory: "128Mi"
    cpu: "250m"
  limits:
    memory: "256Mi"
    cpu: "500m"

serviceAccount:
  create: true
  name: ""
  annotations: {}

podAnnotations: {}
podSecurityContext: {}
securityContext: {}
nodeSelector: {}
tolerations: []
affinity: {}
```

### Task 4: MCP Server — Create Templates

Same file set as the gateway:

**`templates/deployment.yaml`** — Similar to gateway but:
- Init container waits for `account-service:8081`
- No Ingress needed (MCP is internal)
- No Hazelcast dependency

**`templates/service.yaml`** — ClusterIP on port 8085.

**`templates/configmap.yaml`** — Environment variables including:
- `SPRING_PROFILES_ACTIVE`: docker (enables HTTP/SSE transport)
- `JAVA_OPTS`: JVM settings
- `MCP_SERVICES_ACCOUNT_URL`: `http://{release}-account-service:8081`
- `MCP_SERVICES_INVENTORY_URL`: `http://{release}-inventory-service:8082`
- `MCP_SERVICES_ORDER_URL`: `http://{release}-order-service:8083`
- `MCP_SERVICES_PAYMENT_URL`: `http://{release}-payment-service:8084`

**`templates/serviceaccount.yaml`**, **`templates/NOTES.txt`**, **`_helpers.tpl` update**.

### Task 5: Monitoring — Update `values.yaml`

Extend the existing monitoring `values.yaml` with detailed configuration for each component. Add service accounts, persistence toggles, and scrape target configuration.

Updated structure:

```yaml
# Prometheus
prometheus:
  enabled: true
  image:
    repository: prom/prometheus
    tag: "v2.48.0"
  service:
    type: ClusterIP
    port: 9090
  retention: "24h"
  resources:
    requests:
      memory: "128Mi"
      cpu: "100m"
    limits:
      memory: "256Mi"
      cpu: "500m"

# Grafana
grafana:
  enabled: true
  image:
    repository: grafana/grafana
    tag: "10.3.1"
  service:
    type: ClusterIP
    port: 3000
  adminPassword: "admin"
  resources:
    requests:
      memory: "128Mi"
      cpu: "100m"
    limits:
      memory: "256Mi"
      cpu: "500m"

# Jaeger
jaeger:
  enabled: true
  image:
    repository: jaegertracing/all-in-one
    tag: "1.53"
  service:
    type: ClusterIP
    queryPort: 16686
    otlpGrpcPort: 4317
    otlpHttpPort: 4318
  resources:
    requests:
      memory: "128Mi"
      cpu: "100m"
    limits:
      memory: "256Mi"
      cpu: "500m"

serviceAccount:
  create: true
  name: ""
  annotations: {}
```

### Task 6: Monitoring — Create Prometheus Templates

**`templates/prometheus-deployment.yaml`**:
- Single container: `prom/prometheus`
- ConfigMap volume mount at `/etc/prometheus/`
- Command args: `--config.file`, `--storage.tsdb.retention.time`
- No init container needed

**`templates/prometheus-service.yaml`**:
- ClusterIP Service on port 9090

**`templates/prometheus-configmap.yaml`**:
- Translates `docker/prometheus/prometheus.yml` to Helm template
- Scrape targets use Kubernetes Service DNS with `{{ .Release.Name }}` prefix
- Jobs: prometheus (self), account-service, inventory-service, order-service, payment-service, api-gateway, hazelcast-cluster
- All use `/actuator/prometheus` metrics path (except Hazelcast and Prometheus self)
- Comments explaining how to switch to Kubernetes SD for dynamic discovery

### Task 7: Monitoring — Create Grafana Templates

**`templates/grafana-deployment.yaml`**:
- Single container: `grafana/grafana`
- Environment variables: `GF_SECURITY_ADMIN_PASSWORD`, `GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH`
- Three volume mounts:
  1. Datasource provisioning → `/etc/grafana/provisioning/datasources/`
  2. Dashboard provider provisioning → `/etc/grafana/provisioning/dashboards/`
  3. Dashboard JSON files → `/var/lib/grafana/dashboards/`

**`templates/grafana-service.yaml`**:
- ClusterIP Service on port 3000

**`templates/grafana-configmap-datasources.yaml`**:
- Prometheus datasource pointing to `http://{release}-monitoring-prometheus:9090`

**`templates/grafana-configmap-dashboards.yaml`**:
- Dashboard provider YAML: loads from `/var/lib/grafana/dashboards/`
- The four dashboard JSON files from `docker/grafana/dashboards/` embedded as ConfigMap data keys
- Note: These JSON files are large. If any exceed the 1MiB ConfigMap limit, we'll split into multiple ConfigMaps.

### Task 8: Monitoring — Create Jaeger Templates

**`templates/jaeger-deployment.yaml`**:
- Single container: `jaegertracing/all-in-one`
- Environment variables: `COLLECTOR_OTLP_ENABLED=true`
- Exposes ports: 16686 (UI), 4317 (OTLP gRPC), 4318 (OTLP HTTP)

**`templates/jaeger-service.yaml`**:
- ClusterIP Service with three named ports:
  - `query` (16686) — Jaeger UI
  - `otlp-grpc` (4317) — trace ingestion
  - `otlp-http` (4318) — trace ingestion (HTTP)

### Task 9: Update Umbrella Chart Values

Update `k8s/hazelcast-microservices/values.yaml` to add gateway and MCP overrides (service URLs, ingress settings) at the umbrella level. The monitoring section already has `enabled: false`.

### Task 10: Add Prometheus Scrape Annotations to Microservice Pods

Add `prometheus.io/scrape`, `prometheus.io/path`, and `prometheus.io/port` annotations to the existing microservice Deployment templates. This doesn't change how our static Prometheus config works, but prepares the charts for users who switch to Kubernetes SD.

### Task 11: Validate with `helm lint` and `helm template`

```bash
helm lint k8s/hazelcast-microservices/
helm template my-release k8s/hazelcast-microservices/ > /dev/null

# Individual subchart inspection
helm template my-release k8s/hazelcast-microservices/ \
  --show-only charts/api-gateway/templates/ingress.yaml

helm template my-release k8s/hazelcast-microservices/ \
  --show-only charts/monitoring/templates/prometheus-deployment.yaml
```

---

## Deliverables

- [ ] API Gateway subchart: `values.yaml`, `deployment.yaml`, `service.yaml`, `configmap.yaml`, `ingress.yaml`, `serviceaccount.yaml`, `_helpers.tpl`, `NOTES.txt`
- [ ] MCP Server subchart: `values.yaml`, `deployment.yaml`, `service.yaml`, `configmap.yaml`, `serviceaccount.yaml`, `_helpers.tpl`, `NOTES.txt`
- [ ] Monitoring subchart: `values.yaml`, `_helpers.tpl` updated
- [ ] Prometheus: `prometheus-deployment.yaml`, `prometheus-service.yaml`, `prometheus-configmap.yaml`
- [ ] Grafana: `grafana-deployment.yaml`, `grafana-service.yaml`, `grafana-configmap-datasources.yaml`, `grafana-configmap-dashboards.yaml`
- [ ] Jaeger: `jaeger-deployment.yaml`, `jaeger-service.yaml`
- [ ] Updated umbrella `values.yaml` with gateway/MCP overrides
- [ ] Prometheus scrape annotations on all microservice Deployments
- [ ] `helm lint` and `helm template` pass cleanly

---

## Key Files

| File | Purpose |
|------|---------|
| `charts/api-gateway/templates/ingress.yaml` | External HTTP access via Ingress Controller |
| `charts/api-gateway/templates/configmap.yaml` | Upstream service URLs for routing |
| `charts/mcp-server/templates/configmap.yaml` | Upstream service URLs for MCP proxying |
| `charts/monitoring/templates/prometheus-configmap.yaml` | Scrape targets (K8s Service DNS) |
| `charts/monitoring/templates/grafana-configmap-datasources.yaml` | Prometheus datasource for Grafana |
| `charts/monitoring/templates/grafana-configmap-dashboards.yaml` | Dashboard JSON files |
| `charts/monitoring/templates/jaeger-deployment.yaml` | Distributed tracing collector |

---

## Design Decisions

### Why the Gateway Init Container Waits for Account Service (Not Hazelcast)

The gateway doesn't use Hazelcast. Its dependency is the microservices it routes to. We pick `account-service` as the canary — if it's ready, the others are likely ready too (they all depend on Hazelcast, which would have started first). This avoids a complex multi-target check while still providing a reasonable startup gate.

### Why MCP Server Uses the `docker` Spring Profile (Not `kubernetes`)

The MCP server needs HTTP/SSE transport enabled, which is configured in `application-docker.properties`. Creating a separate `application-kubernetes.properties` would duplicate the HTTP transport config. Since the `docker` profile already does what we need (enables HTTP on port 8085), we reuse it and override service URLs via environment variables (which take priority over profile properties).

### Why Grafana Dashboards Are Embedded in ConfigMaps (Not Persistent Volumes)

Three reasons:
1. **GitOps friendly** — dashboards are version-controlled as part of the Helm chart
2. **No storage dependency** — works on any cluster without provisioning PersistentVolumes
3. **Immutable** — dashboards reset to the known-good state on every deploy (prevents drift from manual edits)

The trade-off: you can't save dashboard changes in the Grafana UI (they'll be lost on pod restart). For a demo/educational framework, this is the right default. Production teams typically use Grafana's provisioning API or a separate dashboard repository.

### Why Static Scrape Targets Instead of Kubernetes Service Discovery

Our service list is fixed (6 services + Hazelcast). Static targets are:
- Easier to understand and debug
- Don't require RBAC for Prometheus to query the Kubernetes API
- More explicit about what's being scraped

We include comments showing how to switch to Kubernetes SD for users who want dynamic discovery.

### Why Monitoring Is Disabled by Default

The monitoring stack adds 3 extra pods (Prometheus, Grafana, Jaeger) consuming ~384Mi memory request. For users who just want to deploy the microservices, this overhead isn't needed. Enable with:

```bash
helm install my-release k8s/hazelcast-microservices/ \
  --set monitoring.enabled=true
```

---

## What to Look For If Things Go Wrong

| Symptom | Likely Cause | How to Debug |
|---------|-------------|-------------|
| Ingress returns 404 | Ingress Controller not installed, or `ingressClassName` mismatch | `kubectl get ingressclass`, `kubectl describe ingress` |
| Prometheus shows targets as DOWN | Service DNS mismatch, wrong metrics path, service not deployed yet | `kubectl exec prometheus-pod -- wget -qO- http://target:port/actuator/prometheus` |
| Grafana shows "No data" | Prometheus datasource URL wrong, metrics not being scraped | Check Grafana datasource config, check Prometheus Targets UI |
| MCP server can't reach services | ConfigMap service URLs wrong, services not deployed | Check ConfigMap env vars, `kubectl exec mcp-pod -- wget -qO- http://service:port/actuator/health` |
| Gateway routes return 503 | Upstream service not ready, circuit breaker open | Check gateway logs, check upstream service readiness |
