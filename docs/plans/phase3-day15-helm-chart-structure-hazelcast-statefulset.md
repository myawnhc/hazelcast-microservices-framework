# Phase 3, Day 15: Helm Chart Structure & Hazelcast StatefulSet

**Date**: 2026-02-15
**Area**: 4 — Kubernetes Deployment (Days 15–18)
**Goal**: Create the Helm chart skeleton for the entire stack and deploy Hazelcast as a StatefulSet with Kubernetes-native discovery.

---

## Kubernetes Concepts Explained

Before we start writing YAML, here's a primer on the key concepts we'll use today and over the next few days. Understanding *why* things are structured this way will help you modify and extend these charts in the future.

### What Is Helm?

Helm is the **package manager for Kubernetes** — think of it like Maven or npm, but for deploying applications to a cluster. A **Helm chart** is a directory of YAML templates that describe a set of Kubernetes resources. You parameterize everything with a `values.yaml` file, so the same chart can deploy to dev, staging, or production with different settings.

**Key Helm concepts**:
- **Chart**: A package of Kubernetes resource templates
- **Release**: A specific installation of a chart in a cluster (you can install the same chart multiple times with different names)
- **Umbrella chart**: A chart that depends on other charts (subcharts). Our umbrella chart `hazelcast-microservices` will pull together all services into one deployable unit
- **values.yaml**: The configuration knobs — image tags, replica counts, resource limits, etc.
- **templates/**: Go-templated YAML files that generate the actual Kubernetes manifests

### Deployment vs. StatefulSet — Why Hazelcast Needs a StatefulSet

Kubernetes offers two main ways to run multiple copies of a pod:

| | **Deployment** | **StatefulSet** |
|--|----------------|-----------------|
| **Pod naming** | Random suffix (`hz-7f8d9c-xk2p9`) | Ordered, stable (`hz-0`, `hz-1`, `hz-2`) |
| **Startup order** | All at once (parallel) | One at a time (ordered) |
| **Storage** | Shared or none | Each pod gets its own PersistentVolume |
| **Network identity** | Ephemeral (changes on restart) | Stable DNS name (`hz-0.hz-headless.ns.svc.cluster.local`) |
| **Use case** | Stateless apps (our microservices) | Stateful apps (databases, Hazelcast, Kafka) |

Hazelcast is a **stateful, clustered system** — each member holds a portion of the distributed data. If a pod restarts, other members need to find it at the same address. StatefulSet provides:
1. **Stable network identity** — members can reliably discover each other
2. **Ordered rolling updates** — members restart one at a time so the cluster never loses quorum
3. **Per-pod storage** — each member can have its own PersistentVolumeClaim for hot restart data

Our microservices (account, inventory, etc.) are **stateless** — they store no persistent data locally (the event store lives in Hazelcast). So they'll use plain Deployments.

### Headless Service — How Hazelcast Pods Find Each Other

A normal Kubernetes Service gives you a single virtual IP that load-balances across all pods. But Hazelcast members need to discover *every individual pod* — not just one randomly chosen one. That's what a **headless Service** does.

A headless Service (specified with `clusterIP: None`) doesn't allocate a virtual IP. Instead, a DNS lookup returns the IP addresses of *all* pods behind the service. The Hazelcast Kubernetes discovery plugin uses this to find all members.

```
# Normal Service: DNS returns one virtual IP
nslookup my-service → 10.96.0.15

# Headless Service: DNS returns each pod IP
nslookup hz-headless → 10.244.0.5, 10.244.0.6, 10.244.0.7
```

### Hazelcast Kubernetes Discovery Plugin

In our Docker Compose setup, Hazelcast uses **TCP/IP member list** — we hardcode `hazelcast-1`, `hazelcast-2`, `hazelcast-3`. This works because Docker Compose gives stable hostnames.

In Kubernetes, pod IPs are dynamic and pods can be rescheduled to different nodes. So we replace TCP/IP discovery with the **Hazelcast Kubernetes discovery plugin** (`hazelcast-kubernetes`). This plugin queries the Kubernetes API to find all pods matching a label selector, then joins them into a cluster automatically.

```yaml
# Docker Compose discovery (what we have now):
join:
  tcp-ip:
    enabled: true
    member-list: [hazelcast-1, hazelcast-2, hazelcast-3]

# Kubernetes discovery (what we're building):
join:
  kubernetes:
    enabled: true
    service-dns: hazelcast-cluster-headless.default.svc.cluster.local
```

There are two modes:
- **DNS lookup** (`service-dns`): Uses the headless Service DNS. Simpler, doesn't need RBAC permissions. **We'll use this.**
- **API lookup** (`service-name`): Queries the Kubernetes API directly. More flexible but requires a ServiceAccount with RBAC permissions.

### ConfigMap — Externalizing Configuration

A **ConfigMap** is a Kubernetes object that holds key-value configuration data. Instead of baking `hazelcast.yaml` into the Docker image, we mount it from a ConfigMap. This means:
- You can change Hazelcast config without rebuilding images
- Different environments (dev/staging/prod) can use different configs
- Configuration is version-controlled as part of the Helm chart

### RBAC (Role-Based Access Control)

When using Kubernetes API-based discovery (not our default, but good to understand), the Hazelcast pod needs permission to query the Kubernetes API for other pods. This is done through:
- **ServiceAccount**: An identity for pods (like a user account, but for automated processes)
- **Role**: A set of permissions (e.g., "can list pods in this namespace")
- **RoleBinding**: Connects a ServiceAccount to a Role

We'll create these even with DNS discovery because they're a best practice — and they'll be needed if someone switches to API-based discovery.

### PersistentVolumeClaim (PVC) — Optional Data Persistence

A **PersistentVolumeClaim** requests storage from the cluster. For Hazelcast, this is optional but enables:
- **Hot Restart** (Enterprise): Members reload data from disk after restart instead of re-replicating from the cluster
- **Persistence** (Enterprise): Data survives full cluster restarts

We'll include a PVC template in the StatefulSet but make it **optional via values.yaml** (disabled by default for Community Edition).

---

## Implementation Plan

### Task 1: Create Helm Chart Directory Structure

Create the umbrella chart and all subchart skeletons. Each subchart will have the standard Helm layout.

**Files to create**:
```
k8s/
├── hazelcast-microservices/              # Umbrella chart
│   ├── Chart.yaml                        # Chart metadata + subchart dependencies
│   ├── values.yaml                       # Global + per-subchart values
│   ├── templates/
│   │   └── _helpers.tpl                  # Shared template helpers
│   └── charts/
│       ├── hazelcast-cluster/            # StatefulSet (Day 15)
│       │   ├── Chart.yaml
│       │   ├── values.yaml
│       │   └── templates/
│       │       ├── _helpers.tpl
│       │       ├── statefulset.yaml
│       │       ├── service-headless.yaml
│       │       ├── service.yaml
│       │       ├── configmap.yaml
│       │       ├── serviceaccount.yaml
│       │       └── NOTES.txt
│       ├── account-service/              # Stub (Day 16)
│       │   ├── Chart.yaml
│       │   ├── values.yaml
│       │   └── templates/
│       │       └── _helpers.tpl
│       ├── inventory-service/            # Stub (Day 16)
│       │   ├── Chart.yaml
│       │   ├── values.yaml
│       │   └── templates/
│       │       └── _helpers.tpl
│       ├── order-service/                # Stub (Day 16)
│       │   ├── Chart.yaml
│       │   ├── values.yaml
│       │   └── templates/
│       │       └── _helpers.tpl
│       ├── payment-service/              # Stub (Day 16)
│       │   ├── Chart.yaml
│       │   ├── values.yaml
│       │   └── templates/
│       │       └── _helpers.tpl
│       ├── api-gateway/                  # Stub (Day 17)
│       │   ├── Chart.yaml
│       │   ├── values.yaml
│       │   └── templates/
│       │       └── _helpers.tpl
│       ├── mcp-server/                   # Stub (Day 17)
│       │   ├── Chart.yaml
│       │   ├── values.yaml
│       │   └── templates/
│       │       └── _helpers.tpl
│       └── monitoring/                   # Stub (Day 17)
│           ├── Chart.yaml
│           ├── values.yaml
│           └── templates/
│               └── _helpers.tpl
```

**What each file does**:
- `Chart.yaml`: Declares chart name, version, dependencies (like a `pom.xml` for Helm)
- `values.yaml`: Default configuration values (image tag, replica count, resources, etc.)
- `_helpers.tpl`: Reusable Go template snippets (labels, names, selectors) — avoids copy-pasting the same label blocks everywhere
- `NOTES.txt`: Printed after `helm install` to show helpful information (like "access Hazelcast at...")

### Task 2: Umbrella Chart Configuration

The umbrella chart's `Chart.yaml` declares all subcharts as dependencies. Its `values.yaml` provides global overrides and per-subchart configuration. This is the single file where an operator controls the entire deployment.

**Key design decisions**:
- **Global values** (`global.imageRegistry`, `global.storageClass`) are inherited by all subcharts
- **Per-subchart values** override subchart defaults (e.g., `account-service.replicaCount: 2`)
- **Condition flags** let you enable/disable subcharts (e.g., `mcp-server.enabled: false`)

### Task 3: Hazelcast Cluster StatefulSet (Full Implementation)

This is the most important subchart. We're translating the Docker Compose Hazelcast cluster into Kubernetes-native resources.

**Docker Compose → Kubernetes mapping**:

| Docker Compose | Kubernetes | Purpose |
|----------------|------------|---------|
| `image: hazelcast/hazelcast:5.6.0` | StatefulSet `.spec.template.spec.containers[].image` | Container image |
| `ports: "5701:5701"` | Service `.spec.ports[]` | Network access |
| `JAVA_OPTS: -Xms256m...` | StatefulSet `.spec.template.spec.containers[].env[]` | JVM config |
| `volumes: hazelcast-docker.yaml` | ConfigMap + `volumeMounts` | Hazelcast config file |
| `healthcheck: curl ...` | `livenessProbe` + `readinessProbe` | Health monitoring |
| `deploy.resources.limits.memory` | `resources.requests` + `resources.limits` | Resource constraints |
| 3 separate service definitions | StatefulSet with `replicas: 3` | Multiple instances |
| `hostname: hazelcast-1` | Automatic: `hazelcast-cluster-0`, `-1`, `-2` | Stable identity |
| TCP/IP member list | Kubernetes discovery plugin via headless Service | Cluster formation |

**Resources we'll create**:

1. **StatefulSet** — Runs 3 Hazelcast pods with stable names and optional persistent storage
2. **Headless Service** (`clusterIP: None`) — Enables DNS-based pod discovery for cluster formation
3. **Regular Service** — Provides a stable endpoint for clients connecting to the cluster
4. **ConfigMap** — Holds `hazelcast.yaml` with Kubernetes discovery configuration
5. **ServiceAccount** — Identity for Hazelcast pods (needed for API-based discovery if someone enables it)

**Hazelcast configuration changes from Docker**:
- Replace TCP/IP join with `kubernetes` join using `service-dns` mode
- The `service-dns` value will be templated: `{{ .Release.Name }}-hazelcast-cluster-headless.{{ .Release.Namespace }}.svc.cluster.local`
- Keep all the map configurations (`*_PENDING`, `*_ES`, `*_VIEW`, `*_COMPLETIONS`) identical

**Liveness vs. Readiness probes** (important distinction):
- **Liveness probe**: "Is this pod alive?" If it fails repeatedly, Kubernetes *restarts* the pod. We use `/hazelcast/health/node-state` — checks if the Hazelcast process is running.
- **Readiness probe**: "Is this pod ready to receive traffic?" If it fails, the pod is removed from Service endpoints (no traffic routed to it), but it's NOT restarted. We use `/hazelcast/health/ready` — checks if the member has joined the cluster and is accepting operations.
- **Startup probe**: "Has this pod finished starting?" Gives slow-starting containers extra time. Prevents liveness probe from killing a pod that's still initializing. Critical for Hazelcast — data migration during startup can take time.

### Task 4: Hazelcast ConfigMap for Kubernetes Discovery

Create a `hazelcast.yaml` that replaces TCP/IP discovery with the Kubernetes discovery plugin. This ConfigMap is mounted into each Hazelcast pod as a file.

The key difference from our Docker config: instead of listing member hostnames, we point to the headless Service DNS name. The Hazelcast Kubernetes plugin resolves this DNS to discover all pod IPs.

### Task 5: Stub Subcharts for Remaining Services

Create minimal `Chart.yaml`, `values.yaml`, and `_helpers.tpl` for each remaining subchart. These won't have templates yet — those come on Days 16-17. The stubs ensure the umbrella chart's dependency list resolves.

### Task 6: Helm Lint Validation

Run `helm lint` and `helm template` to validate:
- All chart metadata is correct
- Template rendering produces valid YAML
- No undefined variable references
- Values cascade properly from umbrella to subcharts

**Note**: We won't deploy to a live cluster today. Full end-to-end testing with minikube/kind is planned for Day 18. Today's validation is purely static (template rendering and linting).

---

## Deliverables

- [ ] Helm umbrella chart with `Chart.yaml`, `values.yaml`, `_helpers.tpl`
- [ ] Hazelcast cluster subchart with StatefulSet, headless Service, regular Service, ConfigMap, ServiceAccount
- [ ] ConfigMap with Kubernetes-discovery-enabled `hazelcast.yaml`
- [ ] Stub subcharts for all remaining services (account, inventory, order, payment, api-gateway, mcp-server, monitoring)
- [ ] `helm lint` and `helm template` pass cleanly
- [ ] Plan file saved to `docs/plans/`

---

## Key Files

| File | Purpose |
|------|---------|
| `k8s/hazelcast-microservices/Chart.yaml` | Umbrella chart metadata and dependencies |
| `k8s/hazelcast-microservices/values.yaml` | Global configuration for entire stack |
| `k8s/hazelcast-microservices/charts/hazelcast-cluster/templates/statefulset.yaml` | Hazelcast 3-node StatefulSet |
| `k8s/hazelcast-microservices/charts/hazelcast-cluster/templates/configmap.yaml` | Hazelcast YAML with K8s discovery |
| `k8s/hazelcast-microservices/charts/hazelcast-cluster/templates/service-headless.yaml` | Headless Service for pod discovery |
