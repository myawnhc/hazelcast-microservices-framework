# Phase 3, Day 16: Microservice Helm Charts

**Date**: 2026-02-16
**Area**: 4 — Kubernetes Deployment (Days 15–18)
**Goal**: Create Deployment, Service, and ConfigMap templates for all four microservices (account, inventory, order, payment), with Spring Boot health probes, environment variable injection, and proper resource management.

---

## Kubernetes Concepts Explained

Day 15 introduced Helm, StatefulSets, and headless Services for Hazelcast. Today we focus on the patterns for deploying **stateless Spring Boot microservices** into Kubernetes. Understanding these concepts is essential for tuning, debugging, and extending the charts later.

### Deployments — Why Microservices Don't Need StatefulSets

On Day 15 we used a StatefulSet for Hazelcast because each member holds a shard of the distributed data and needs a stable network identity. Our microservices are different:

- **No local state** — all persistent data lives in the Hazelcast cluster (event store, materialized views). The microservice processes are stateless request handlers.
- **Interchangeable pods** — any pod can handle any request. If one dies, a new one takes its place with no data loss.
- **No startup ordering** — all replicas can start simultaneously.

A **Deployment** is the right fit. It manages a set of identical pods and handles:

| Capability | How It Works |
|-----------|-------------|
| **Scaling** | Change `replicas` and Kubernetes creates/removes pods |
| **Rolling updates** | Gradually replaces old pods with new ones (zero-downtime deploys) |
| **Self-healing** | If a pod crashes, the Deployment creates a replacement automatically |
| **Rollback** | `kubectl rollout undo` reverts to the previous pod template |

**Key Deployment fields you'll see in our templates**:

```yaml
apiVersion: apps/v1
kind: Deployment
spec:
  replicas: 1              # Number of identical pods
  strategy:
    type: RollingUpdate     # How to replace pods during an upgrade
    rollingUpdate:
      maxUnavailable: 0     # Never take a pod down before a new one is ready
      maxSurge: 1           # Create 1 extra pod during rollout, then drain the old one
  selector:
    matchLabels: ...        # Which pods this Deployment owns (must match pod template labels)
  template:
    spec:
      containers: [...]     # The actual container definition
```

**`maxUnavailable: 0` + `maxSurge: 1`** is the safest rolling update strategy: Kubernetes spins up a new pod first, waits for it to pass readiness checks, then terminates the old pod. This means your service never has fewer than `replicas` healthy pods during a deployment. The trade-off is that you temporarily use extra resources (one extra pod) during rollout.

### Spring Boot Actuator Health Probes — Making Kubernetes Understand Your App

Kubernetes needs to know three things about your pod:

1. **"Has it finished starting?"** → Startup probe
2. **"Is it still alive?"** → Liveness probe
3. **"Can it accept traffic?"** → Readiness probe

Spring Boot Actuator provides dedicated endpoints for Kubernetes probes when the property `management.endpoint.health.probes.enabled=true` is set (which happens automatically when Spring detects it's running in Kubernetes via the `KUBERNETES_SERVICE_HOST` environment variable):

| Probe | Actuator Endpoint | What It Checks | Failure Action |
|-------|-------------------|----------------|----------------|
| **Startup** | `/actuator/health/liveness` | JVM is up, Spring context loaded | Keep waiting (doesn't restart yet) |
| **Liveness** | `/actuator/health/liveness` | App isn't deadlocked or stuck | **Restart the pod** |
| **Readiness** | `/actuator/health/readiness` | Dependencies ready (Hazelcast connected, Jet pipeline running) | **Remove from Service endpoints** (no traffic routed) |

**Critical distinction**: A liveness failure *kills and restarts* the pod. A readiness failure *stops routing traffic* but leaves the pod running. This matters because:

- During startup, the embedded Hazelcast instance needs time to initialize its Jet pipeline. If we used a liveness probe too aggressively, Kubernetes would kill the pod before it finished starting. That's why we use a **startup probe** with generous `failureThreshold × periodSeconds` budget.
- If the Hazelcast client temporarily loses connection to the shared cluster, we want to stop routing traffic (readiness failure) but NOT restart the pod — the connection will likely recover on its own.

**Probe timing parameters explained**:

```yaml
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: http
  initialDelaySeconds: 30    # Wait 30s before first check (JVM + Spring startup)
  periodSeconds: 10           # Check every 10s after that
  failureThreshold: 12        # Allow 12 failures = 30s + (12 × 10s) = 150s total startup budget
  timeoutSeconds: 5           # Each check must respond within 5s

livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: http
  initialDelaySeconds: 0      # Starts immediately AFTER startup probe succeeds
  periodSeconds: 30            # Check every 30s (don't hammer the app)
  failureThreshold: 3          # 3 consecutive failures = restart
  timeoutSeconds: 5

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
  initialDelaySeconds: 0      # Starts immediately AFTER startup probe succeeds
  periodSeconds: 10            # Check every 10s (faster than liveness — we want to stop traffic quickly)
  failureThreshold: 3          # 3 consecutive failures = remove from Service
  timeoutSeconds: 5
```

**Why `initialDelaySeconds: 0` on liveness/readiness?** Because the startup probe runs first and gates the other probes. Until the startup probe succeeds, liveness and readiness probes don't fire at all. So the startup probe handles the slow-start window, and liveness/readiness can start checking immediately once startup succeeds.

### Kubernetes Service DNS — How Microservices Find Each Other

In Docker Compose, services discover each other by container name on a shared bridge network:
```
http://account-service:8081/api/customers
```

Kubernetes Service DNS works similarly but with a fully-qualified domain name (FQDN):
```
http://<service-name>.<namespace>.svc.cluster.local:<port>
```

For our services within the same namespace, the short name works too:
```
http://account-service:8081/api/customers
```

This is because Kubernetes automatically configures pod DNS search domains to include the pod's namespace. So `account-service` resolves to `account-service.default.svc.cluster.local` when pods are in the `default` namespace.

**How this maps to our architecture**:

```
┌─────────────────────────────────────────────────────────────┐
│  Kubernetes Cluster                                         │
│                                                             │
│  ┌──────────────┐    ClusterIP Service   ┌──────────────┐  │
│  │ order-service │◄── order-service:8083 ─┤ api-gateway  │  │
│  │  (Deployment) │                        │ (Deployment) │  │
│  └──────┬───────┘                         └──────────────┘  │
│         │                                                   │
│         │ HAZELCAST_CLUSTER_MEMBERS                         │
│         │ = hazelcast-cluster:5701                           │
│         ▼                                                   │
│  ┌──────────────────────────────────────┐                   │
│  │ hazelcast-cluster (StatefulSet)      │                   │
│  │  hazelcast-cluster-0                 │                   │
│  │  hazelcast-cluster-1                 │◄── ClusterIP      │
│  │  hazelcast-cluster-2                 │    Service         │
│  └──────────────────────────────────────┘                   │
└─────────────────────────────────────────────────────────────┘
```

Each microservice connects to the Hazelcast cluster as a **client** via the regular ClusterIP Service (`hazelcast-cluster:5701`). The ClusterIP Service load-balances across all three Hazelcast pods — the Hazelcast client protocol handles the rest (discovering all members, partitioning data, etc.).

### Environment Variables vs. ConfigMaps vs. Secrets — When to Use Which

Kubernetes offers three ways to inject configuration into pods:

| Method | Use Case | Example |
|--------|----------|---------|
| **Environment variables** (inline in Deployment) | Simple key-value settings that change per environment | `SPRING_PROFILES_ACTIVE=kubernetes` |
| **ConfigMap** (mounted as env vars or files) | Structured configuration shared across pods | Service URLs, JVM options, feature flags |
| **Secret** (mounted as env vars or files) | Sensitive data (base64-encoded at rest) | `HZ_LICENSEKEY`, database passwords |

**Our approach**: We'll use a **ConfigMap per service** that holds environment variables, then reference it with `envFrom` in the Deployment. This keeps the Deployment template generic and moves all service-specific configuration into the ConfigMap. To change configuration, you update the ConfigMap and restart pods — no template changes needed.

```yaml
# ConfigMap holds the config
apiVersion: v1
kind: ConfigMap
data:
  SPRING_PROFILES_ACTIVE: "kubernetes"
  JAVA_OPTS: "-Xms256m -Xmx512m"
  HAZELCAST_CLUSTER_NAME: "ecommerce-cluster"

---
# Deployment references it
spec:
  containers:
    - envFrom:
        - configMapRef:
            name: account-service-config
```

**Why not inline env vars in the Deployment?** Two reasons:
1. **Separation of concerns** — the Deployment template describes *how* to run the container; the ConfigMap describes *what configuration* to use. Different teams/roles may own each.
2. **Reusability** — if all four microservices share the same Deployment template (they mostly do), the only difference is which ConfigMap they reference.

### Docker Compose `depends_on` vs. Kubernetes Init Containers

In Docker Compose, `depends_on` with `condition: service_healthy` blocks a service from starting until its dependency is healthy:

```yaml
# Docker Compose
account-service:
  depends_on:
    hazelcast-1:
      condition: service_healthy
```

Kubernetes has no built-in `depends_on`. Pods start as soon as they're scheduled, regardless of whether dependencies are ready. There are three approaches:

1. **Init containers** (what we'll use) — a short-lived container that runs *before* the main container. It blocks startup until a condition is met:
   ```yaml
   initContainers:
     - name: wait-for-hazelcast
       image: busybox:1.36
       command: ['sh', '-c', 'until nc -z hazelcast-cluster 5701; do sleep 2; done']
   ```

2. **Readiness-based resilience** — let the app start and fail its readiness probe until dependencies are available. Spring Boot retry + circuit breaker handle transient connection failures. This works but logs are noisy during startup.

3. **Kubernetes-native ordering** — use Argo CD sync waves or Helm hooks to deploy resources in order. More complex, better for production.

We'll use **init containers** because they're simple, visible in `kubectl describe pod`, and clearly communicate the dependency. The `busybox` image is tiny (~1.4MB) and includes `nc` (netcat) for TCP connectivity checks.

### Resource Requests and Limits — What They Mean for Scheduling

When you set `resources` on a container, you're telling Kubernetes two things:

```yaml
resources:
  requests:        # "I need at least this much to run"
    memory: "256Mi"
    cpu: "250m"
  limits:          # "Never let me use more than this"
    memory: "512Mi"
    cpu: "1000m"
```

**Requests** affect **scheduling**: the scheduler only places a pod on a node that has enough unrequested capacity. If you request 256Mi and the node only has 200Mi unrequested, the pod won't be scheduled there.

**Limits** affect **runtime**: if a container exceeds its memory limit, the kernel OOM-kills it. If it exceeds CPU limit, it gets throttled (slowed down, not killed).

**CPU units**: `1000m` = 1 full CPU core. `250m` = 25% of a core. This is a guaranteed minimum (request) and hard cap (limit).

**Why `requests < limits`?** This is called **burstable** QoS (Quality of Service). The container normally runs within its request, but can burst to its limit during temporary spikes (e.g., processing a large batch of events). This lets you pack more pods onto fewer nodes for cost efficiency, at the risk of resource contention during bursts.

**Our resource choices**:

| Service | Memory Request | Memory Limit | CPU Request | CPU Limit | Rationale |
|---------|---------------|-------------|-------------|-----------|-----------|
| Microservices | 256Mi | 512Mi | 250m | 1000m | JVM base ~200MB + headroom for request handling |
| API Gateway | 128Mi | 256Mi | 250m | 500m | Lightweight reverse proxy, minimal memory |
| MCP Server | 128Mi | 256Mi | 250m | 500m | REST proxy, no heavy processing |

### The `application-kubernetes.yml` Spring Profile

Today we'll add a `kubernetes` Spring profile (similar to the existing `docker` profile) for Kubernetes-specific configuration. This profile will:

1. Override Hazelcast cluster member addresses with Kubernetes Service DNS
2. Set OpenTelemetry endpoints for the Kubernetes monitoring stack
3. Enable Kubernetes-specific health probe groups

Spring resolves configuration in this order (later overrides earlier):
```
application.yml → application-kubernetes.yml → Environment variables
```

Environment variables set in the ConfigMap have the highest priority, so they can override anything in the YAML files. This is important because Helm values flow into ConfigMap env vars, giving operators full control without touching application code.

---

## Docker Compose → Kubernetes Mapping

This table shows how every Docker Compose configuration for our microservices translates to Kubernetes resources:

| Docker Compose | Kubernetes Resource | Location in Helm Chart |
|----------------|--------------------|-----------------------|
| `image: hazelcast-microservices/account-service:latest` | Deployment `.spec.template.spec.containers[].image` | `deployment.yaml` |
| `ports: "8081:8081"` | Service `.spec.ports[]` | `service.yaml` |
| `environment: SPRING_PROFILES_ACTIVE=docker` | ConfigMap `.data.SPRING_PROFILES_ACTIVE` | `configmap.yaml` |
| `environment: JAVA_OPTS=...` | ConfigMap `.data.JAVA_OPTS` | `configmap.yaml` |
| `environment: HAZELCAST_CLUSTER_MEMBERS=...` | ConfigMap `.data.HAZELCAST_CLUSTER_MEMBERS` | `configmap.yaml` |
| `environment: HZ_LICENSEKEY` | Secret reference in Deployment `.env[].valueFrom.secretKeyRef` | `deployment.yaml` |
| `deploy.resources.limits.memory: 512M` | `resources.limits.memory: 512Mi` | `deployment.yaml` |
| `healthcheck: curl /actuator/health` | `startupProbe` + `livenessProbe` + `readinessProbe` | `deployment.yaml` |
| `depends_on: hazelcast-1: condition: service_healthy` | `initContainers: wait-for-hazelcast` | `deployment.yaml` |
| `networks: ecommerce-network` | Kubernetes pod networking (automatic) | N/A |
| `deploy.replicas: 1` | `spec.replicas: 1` | `deployment.yaml` |

---

## Implementation Plan

### Task 1: Update Subchart `values.yaml` Files

Extend the existing stub `values.yaml` for each microservice with new sections for probes, JVM settings, environment variables, and init containers.

**New values to add** (applied to all four services, with port differences):

```yaml
# JVM configuration
jvm:
  xms: "256m"
  xmx: "512m"
  opts: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Spring configuration
spring:
  profiles: "kubernetes"

# Hazelcast client connection
hazelcast:
  clusterName: "ecommerce-cluster"
  # Service DNS — will resolve to the ClusterIP Service created by hazelcast-cluster subchart
  clusterMembers: "{{ .Release.Name }}-hazelcast-cluster:5701"

# OpenTelemetry / tracing
tracing:
  enabled: true
  endpoint: "http://jaeger:4317"

# Health probes
probes:
  startup:
    initialDelaySeconds: 30
    periodSeconds: 10
    failureThreshold: 12
    timeoutSeconds: 5
  liveness:
    initialDelaySeconds: 0
    periodSeconds: 30
    failureThreshold: 3
    timeoutSeconds: 5
  readiness:
    initialDelaySeconds: 0
    periodSeconds: 10
    failureThreshold: 3
    timeoutSeconds: 5

# Init container — wait for Hazelcast cluster
initContainers:
  waitForHazelcast:
    enabled: true
    image: busybox:1.36

# Pod scheduling
podAnnotations: {}
podSecurityContext: {}
securityContext: {}
nodeSelector: {}
tolerations: []
affinity: {}
```

**Service-specific additions for order-service**:
```yaml
# Order service needs to call inventory and payment services for saga orchestration
services:
  inventory:
    url: "http://{{ .Release.Name }}-inventory-service:8082"
  payment:
    url: "http://{{ .Release.Name }}-payment-service:8084"
```

### Task 2: Create Deployment Templates

Create `templates/deployment.yaml` for each of the four microservices. All four follow the same pattern — the differences are parameterized through `values.yaml`.

**Template structure** (educational breakdown of each section):

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "{service}.fullname" . }}
  labels:
    {{- include "{service}.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0        # Zero-downtime: never reduce below desired count
      maxSurge: 1              # Roll one pod at a time
  selector:
    matchLabels:
      {{- include "{service}.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "{service}.selectorLabels" . | nindent 8 }}
      annotations:
        # Force pod restart when ConfigMap changes (otherwise pods keep old env vars)
        checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}
    spec:
      serviceAccountName: {{ include "{service}.serviceAccountName" . }}
      {{- with .Values.podSecurityContext }}
      securityContext: {{- toYaml . | nindent 8 }}
      {{- end }}

      # Wait for Hazelcast to be reachable before starting the app
      {{- if .Values.initContainers.waitForHazelcast.enabled }}
      initContainers:
        - name: wait-for-hazelcast
          image: {{ .Values.initContainers.waitForHazelcast.image }}
          command: ['sh', '-c',
            'echo "Waiting for Hazelcast cluster...";
             until nc -z {{ .Release.Name }}-hazelcast-cluster {{ .Values.hazelcast.port | default 5701 }};
             do echo "Hazelcast not ready, retrying in 2s..."; sleep 2; done;
             echo "Hazelcast is ready!"']
      {{- end }}

      containers:
        - name: {{ .Chart.Name }}
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: {{ .Values.service.port }}
              protocol: TCP

          # All configuration injected from ConfigMap
          envFrom:
            - configMapRef:
                name: {{ include "{service}.fullname" . }}-config

          # Sensitive values from Secrets (optional)
          env:
            - name: HZ_LICENSEKEY
              valueFrom:
                secretKeyRef:
                  name: hazelcast-license
                  key: license-key
                  optional: true    # Don't fail if secret doesn't exist (Community Edition)

          # Health probes — see "Spring Boot Actuator Health Probes" section above
          startupProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            initialDelaySeconds: {{ .Values.probes.startup.initialDelaySeconds }}
            periodSeconds: {{ .Values.probes.startup.periodSeconds }}
            failureThreshold: {{ .Values.probes.startup.failureThreshold }}
            timeoutSeconds: {{ .Values.probes.startup.timeoutSeconds }}
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            initialDelaySeconds: {{ .Values.probes.liveness.initialDelaySeconds }}
            periodSeconds: {{ .Values.probes.liveness.periodSeconds }}
            failureThreshold: {{ .Values.probes.liveness.failureThreshold }}
            timeoutSeconds: {{ .Values.probes.liveness.timeoutSeconds }}
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            initialDelaySeconds: {{ .Values.probes.readiness.initialDelaySeconds }}
            periodSeconds: {{ .Values.probes.readiness.periodSeconds }}
            failureThreshold: {{ .Values.probes.readiness.failureThreshold }}
            timeoutSeconds: {{ .Values.probes.readiness.timeoutSeconds }}

          resources:
            {{- toYaml .Values.resources | nindent 12 }}

      {{- with .Values.nodeSelector }}
      nodeSelector: {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.affinity }}
      affinity: {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.tolerations }}
      tolerations: {{- toYaml . | nindent 8 }}
      {{- end }}
```

**Notable template techniques**:

- **`checksum/config` annotation**: Forces pod restarts when ConfigMap data changes. Without this, Kubernetes won't restart pods when you update a ConfigMap because the Deployment spec itself hasn't changed. The `sha256sum` of the rendered ConfigMap content is embedded as a pod annotation — when the ConfigMap changes, the checksum changes, which changes the pod template, which triggers a rolling update.

- **`{{- with .Values.nodeSelector }}`**: The `with` block only renders if the value is non-empty. This avoids rendering empty YAML fields like `nodeSelector: {}`, keeping manifests clean.

- **`nindent`**: Handles YAML indentation in templates. `nindent 12` means "insert a newline and indent 12 spaces". Getting indentation wrong is the most common Helm template error.

### Task 3: Create Service Templates

Create `templates/service.yaml` for each microservice. These are straightforward ClusterIP Services that expose the pod's HTTP port to the cluster.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "{service}.fullname" . }}
  labels:
    {{- include "{service}.labels" . | nindent 4 }}
spec:
  type: {{ .Values.service.type }}
  ports:
    - port: {{ .Values.service.port }}
      targetPort: http            # Matches the named port in the Deployment
      protocol: TCP
      name: http
  selector:
    {{- include "{service}.selectorLabels" . | nindent 4 }}
```

**Why `targetPort: http` instead of a number?** Using the named port (`http`) from the Deployment's container port definition means the Service automatically follows if you change the port number in one place. It's a best practice for maintainability.

### Task 4: Create ConfigMap Templates

Create `templates/configmap.yaml` for each microservice. This is where all environment variables are centralized.

**Common environment variables** (all four services):

| Variable | Value | Purpose |
|----------|-------|---------|
| `SPRING_PROFILES_ACTIVE` | `kubernetes` | Activates K8s-specific Spring configuration |
| `JAVA_OPTS` | `-Xms256m -Xmx512m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0` | JVM memory management |
| `HAZELCAST_CLUSTER_NAME` | `ecommerce-cluster` | Cluster name for the Hazelcast client connection |
| `HAZELCAST_CLUSTER_MEMBERS` | `{release}-hazelcast-cluster:5701` | Hazelcast Service DNS for client connection |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://jaeger:4317` | OpenTelemetry tracing endpoint |
| `OTEL_SERVICE_NAME` | `{service-name}` | Service identity in traces |

**Order-service-specific additions**:

| Variable | Value | Purpose |
|----------|-------|---------|
| `INVENTORY_SERVICE_URL` | `http://{release}-inventory-service:8082` | Saga orchestrator calls to inventory |
| `PAYMENT_SERVICE_URL` | `http://{release}-payment-service:8084` | Saga orchestrator calls to payment |

### Task 5: Create ServiceAccount Templates

Create `templates/serviceaccount.yaml` for each microservice. These are minimal — just establishing an identity for each service's pods.

```yaml
{{- if .Values.serviceAccount.create -}}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "{service}.serviceAccountName" . }}
  labels:
    {{- include "{service}.labels" . | nindent 4 }}
  {{- with .Values.serviceAccount.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
automountServiceAccountToken: true
{{- end }}
```

**Why create ServiceAccounts even for simple services?** Three reasons:
1. **Security audit trail** — each service has a distinct identity. RBAC policies can restrict what each service can do.
2. **Cloud IAM integration** — on AWS EKS, GCP GKE, or Azure AKS, you can map Kubernetes ServiceAccounts to cloud IAM roles for accessing managed services (S3, Cloud Storage, etc.) without embedding credentials.
3. **Future-proofing** — when we add Security (Phase 3, Days 19-22), having distinct ServiceAccounts makes it easy to apply per-service policies.

### Task 6: Update `_helpers.tpl` with ServiceAccountName Helper

Add a `serviceAccountName` helper function to each service's `_helpers.tpl` (matching the pattern used in the hazelcast-cluster subchart). This function returns the custom name if specified, or falls back to the fullname.

### Task 7: Add NOTES.txt for Each Subchart

Create a `templates/NOTES.txt` that prints useful information after `helm install`:

```
{{service-name}} has been deployed!

  Service URL: http://{{ include "{service}.fullname" . }}:{{ .Values.service.port }}

To check status:
  kubectl get pods -l app.kubernetes.io/name={{ include "{service}.name" . }}

To view logs:
  kubectl logs -l app.kubernetes.io/name={{ include "{service}.name" . }} -f

To port-forward for local access:
  kubectl port-forward svc/{{ include "{service}.fullname" . }} {{ .Values.service.port }}:{{ .Values.service.port }}
```

### Task 8: Add `application-kubernetes.yml` Spring Profiles

Create `src/main/resources/application-kubernetes.yml` for each microservice. This profile activates when `SPRING_PROFILES_ACTIVE=kubernetes` and provides Kubernetes-specific defaults.

**Why a dedicated profile instead of just environment variables?** Environment variables handle most configuration, but some settings are easier to express in YAML (especially nested structures like Resilience4j overrides). The `kubernetes` profile provides sensible defaults that environment variables can still override.

### Task 9: Validate with `helm lint` and `helm template`

Run validation to confirm all templates render correctly:

```bash
# Lint checks for common errors
helm lint k8s/hazelcast-microservices/

# Template renders all manifests to stdout for inspection
helm template my-release k8s/hazelcast-microservices/ > /dev/null

# Template a specific subchart for focused debugging
helm template my-release k8s/hazelcast-microservices/ \
  --show-only charts/account-service/templates/deployment.yaml
```

---

## Deliverables

- [ ] Updated `values.yaml` for all 4 microservice subcharts (probes, JVM, env vars, init containers)
- [ ] `deployment.yaml` template for each microservice (account, inventory, order, payment)
- [ ] `service.yaml` template for each microservice
- [ ] `configmap.yaml` template for each microservice
- [ ] `serviceaccount.yaml` template for each microservice
- [ ] Updated `_helpers.tpl` with `serviceAccountName` helper for each microservice
- [ ] `NOTES.txt` for each microservice subchart
- [ ] `application-kubernetes.yml` Spring profile for each microservice
- [ ] `helm lint` and `helm template` pass cleanly

---

## Key Files

| File | Purpose |
|------|---------|
| `k8s/hazelcast-microservices/charts/{service}/templates/deployment.yaml` | Pod spec with probes, env, init containers |
| `k8s/hazelcast-microservices/charts/{service}/templates/service.yaml` | ClusterIP Service for internal DNS |
| `k8s/hazelcast-microservices/charts/{service}/templates/configmap.yaml` | Environment variable configuration |
| `k8s/hazelcast-microservices/charts/{service}/templates/serviceaccount.yaml` | RBAC identity |
| `k8s/hazelcast-microservices/charts/{service}/values.yaml` | Parameterized defaults |
| `{service}/src/main/resources/application-kubernetes.yml` | K8s-specific Spring configuration |

---

## Design Decisions

### Why One ConfigMap Per Service (Not a Shared ConfigMap)?

Each microservice gets its own ConfigMap because:
- Services have different environment variables (e.g., order-service needs `INVENTORY_SERVICE_URL`, account-service doesn't)
- Independent lifecycle — updating one service's config doesn't affect others
- Clear ownership — the ConfigMap is part of the subchart, deployed and versioned together

### Why `envFrom` Instead of Individual `env` Entries?

Using `envFrom: configMapRef` injects *all* keys from the ConfigMap as environment variables. This is cleaner than listing each variable individually in the Deployment. The trade-off is less visibility in `kubectl describe pod` (you see "configMapRef" rather than individual values), but `kubectl get configmap -o yaml` shows everything.

### Why Init Containers Instead of Application-Level Retry?

Both approaches work. We use init containers because:
- They make the dependency **explicit and visible** — `kubectl describe pod` shows the init container status
- They prevent noisy startup logs from connection retry spam
- They work identically across all services regardless of application framework
- The application code stays cleaner (no startup-specific retry logic)

### Why `maxUnavailable: 0` Rolling Updates?

For a demo/educational framework, zero-downtime deploys are the right default. In production with many replicas, you might set `maxUnavailable: 1` to speed up deployments (two pods update simultaneously), but with `replicas: 1`, `maxUnavailable: 0` is the only safe option — otherwise Kubernetes would take down your only pod before the new one is ready.
