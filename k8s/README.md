# Kubernetes Deployment Guide

Deploy the Hazelcast Microservices Framework to Kubernetes using Helm.

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| kubectl | 1.25+ | Cluster access |
| Helm | 3.12+ | Chart installation |
| Kubernetes | 1.25+ | Target cluster |
| metrics-server | 0.6+ | Required for HPA (autoscaling) |

Verify your environment:

```bash
kubectl version --client
helm version
kubectl get nodes
```

## Quick Start

```bash
cd k8s/hazelcast-microservices

# Update Helm dependencies
helm dependency update .

# Install with defaults (Community Edition)
helm install demo .

# Verify pods are running
kubectl get pods -w

# Access the API Gateway
kubectl port-forward svc/demo-api-gateway 8080:8080
curl http://localhost:8080/actuator/health
```

## Architecture

```
                           ┌──────────────────────────────┐
                           │         Ingress               │
                           │    (nginx / cloud ALB)        │
                           └──────────┬───────────────────┘
                                      │
                           ┌──────────▼───────────────────┐
                           │      API Gateway (:8080)      │
                           │    (reverse proxy + routing)   │
                           └──┬───────┬────────┬───────┬──┘
                              │       │        │       │
               ┌──────────────▼──┐ ┌──▼─────┐ ┌▼─────┐ ┌▼────────┐
               │ Account (:8081) │ │Inventory│ │Order │ │ Payment  │
               │                 │ │ (:8082) │ │(:8083)│ │ (:8084)  │
               └────────┬────────┘ └───┬─────┘ └──┬───┘ └────┬────┘
                        │              │           │          │
              ┌─────────▼──────────────▼───────────▼──────────▼────┐
              │                Hazelcast Cluster                    │
              │         (3-node StatefulSet, :5701)                 │
              │   ITopic (saga events) + IMap (shared state)       │
              └────────────────────────────────────────────────────┘

Each microservice also runs an embedded Hazelcast instance (standalone,
no cluster join) for local Jet pipelines and event sourcing.
See docs/architecture/adr/008-dual-instance-hazelcast-architecture.md
```

## Services Reference

| Service | Port | Health Check | Default Replicas | HPA Support |
|---------|------|-------------|------------------|-------------|
| Hazelcast Cluster | 5701 | `/hazelcast/health/ready` | 3 (StatefulSet) | No |
| Account Service | 8081 | `/actuator/health` | 1 | Yes |
| Inventory Service | 8082 | `/actuator/health` | 1 | Yes |
| Order Service | 8083 | `/actuator/health` | 1 | Yes |
| Payment Service | 8084 | `/actuator/health` | 1 | Yes |
| API Gateway | 8080 | `/actuator/health` | 1 | Yes |
| MCP Server | 8085 | `/actuator/health` | 1 | No |
| Prometheus | 9090 | `-` | 1 | No |
| Grafana | 3000 | `-` | 1 | No |
| Jaeger | 16686 | `-` | 1 | No |

## Configuration

### Enable Monitoring

```bash
helm install demo . --set monitoring.enabled=true
```

### Enable MCP Server

```bash
helm install demo . --set mcp-server.enabled=true
```

### Enable Autoscaling (HPA)

Requires [metrics-server](https://github.com/kubernetes-sigs/metrics-server) installed in the cluster.

```bash
# Enable HPA for all microservices
helm install demo . \
  --set account-service.autoscaling.enabled=true \
  --set inventory-service.autoscaling.enabled=true \
  --set order-service.autoscaling.enabled=true \
  --set payment-service.autoscaling.enabled=true \
  --set api-gateway.autoscaling.enabled=true

# Customize scaling bounds
helm install demo . \
  --set account-service.autoscaling.enabled=true \
  --set account-service.autoscaling.minReplicas=2 \
  --set account-service.autoscaling.maxReplicas=10 \
  --set account-service.autoscaling.targetCPUUtilizationPercentage=60
```

When HPA is enabled, the Deployment omits the `replicas` field so the autoscaler controls pod count. The HPA scales based on CPU utilization (always), with an optional memory metric.

**Not autoscaled**: Hazelcast StatefulSet (manual scaling only — HPA does not understand partition redistribution), MCP Server (single instance), Monitoring components.

### Enable Pod Disruption Budgets

PDBs protect pods during voluntary disruptions (node drains, cluster upgrades).

```bash
# Hazelcast PDB is enabled by default (minAvailable: 2 preserves quorum)
# Enable PDBs for microservices (useful when replicas > 1)
helm install demo . \
  --set account-service.podDisruptionBudget.enabled=true \
  --set inventory-service.podDisruptionBudget.enabled=true \
  --set order-service.podDisruptionBudget.enabled=true \
  --set payment-service.podDisruptionBudget.enabled=true \
  --set api-gateway.podDisruptionBudget.enabled=true
```

| Resource | Strategy | Default |
|----------|----------|---------|
| Hazelcast Cluster | `minAvailable: 2` (quorum) | **Enabled** |
| Microservices | `maxUnavailable: 1` | Disabled |
| API Gateway | `maxUnavailable: 1` | Disabled |

### Configure Ingress

```bash
# With nginx ingress controller
helm install demo . \
  --set api-gateway.ingress.enabled=true \
  --set api-gateway.ingress.className=nginx \
  --set api-gateway.ingress.hosts[0].host=api.example.com \
  --set api-gateway.ingress.hosts[0].paths[0].path=/ \
  --set api-gateway.ingress.hosts[0].paths[0].pathType=Prefix

# With TLS
helm install demo . \
  --set api-gateway.ingress.tls[0].secretName=api-tls-cert \
  --set api-gateway.ingress.tls[0].hosts[0]=api.example.com
```

### Enterprise Edition

To use Hazelcast Enterprise features, create a license secret:

```bash
kubectl create secret generic hazelcast-license \
  --from-literal=license-key=YOUR_LICENSE_KEY

# The secret is referenced as optional — Community Edition works without it
```

### Custom Values File

For production, use a values override file:

```bash
helm install prod . -f values-production.yaml
```

Example `values-production.yaml`:

```yaml
hazelcast-cluster:
  replicaCount: 3
  resources:
    requests:
      memory: "1Gi"
      cpu: "1000m"
    limits:
      memory: "2Gi"
      cpu: "2000m"

account-service:
  replicaCount: 2
  autoscaling:
    enabled: true
    minReplicas: 2
    maxReplicas: 8
  podDisruptionBudget:
    enabled: true
  resources:
    requests:
      memory: "512Mi"
      cpu: "500m"
    limits:
      memory: "1Gi"
      cpu: "2000m"

monitoring:
  enabled: true
```

## Startup Order

The Helm chart uses init containers to enforce startup ordering:

1. **Hazelcast Cluster** starts first (StatefulSet, parallel pod management)
2. **Microservices** wait for Hazelcast via init container (`nc -z hazelcast:5701`)
3. **API Gateway** waits for account-service via init container
4. **MCP Server** starts independently (connects to services via HTTP)

## Troubleshooting

### Init Container Stuck (Waiting for Hazelcast)

```bash
# Check if Hazelcast pods are running
kubectl get pods -l app.kubernetes.io/name=hazelcast-cluster

# Check Hazelcast logs
kubectl logs demo-hazelcast-cluster-0

# Check init container logs
kubectl logs demo-account-service-xxx -c wait-for-hazelcast
```

### ImagePullBackOff

Ensure images are built and available. For local development with minikube:

```bash
eval $(minikube docker-env)
# Then build images in the minikube Docker context
```

### HPA Shows `<unknown>` for Metrics

```bash
# Verify metrics-server is running
kubectl get pods -n kube-system -l k8s-app=metrics-server

# Check if metrics are available
kubectl top pods

# If using minikube, enable metrics-server addon
minikube addons enable metrics-server
```

### PDB Blocking Node Drain

```bash
# Check PDB status
kubectl get pdb

# If drain is blocked, ensure enough replicas are running
kubectl get pods -l app.kubernetes.io/name=account-service

# Force drain (use with caution — may cause downtime)
kubectl drain <node> --ignore-daemonsets --delete-emptydir-data --force
```

### Pods OOMKilled

Increase memory limits in values:

```bash
helm upgrade demo . \
  --set account-service.resources.limits.memory=1Gi \
  --set account-service.jvm.xmx=768m
```

### Check Service Connectivity

```bash
# Port-forward to a specific service
kubectl port-forward svc/demo-account-service 8081:8081

# Test health endpoint
curl http://localhost:8081/actuator/health

# Test from inside the cluster
kubectl run curl --image=curlimages/curl --rm -it -- \
  curl http://demo-account-service:8081/actuator/health
```

## Chart Directory Structure

```
k8s/hazelcast-microservices/
├── Chart.yaml                          # Umbrella chart definition
├── Chart.lock                          # Dependency lock file
├── values.yaml                         # Global default values
├── templates/
│   └── _helpers.tpl                    # Shared template helpers
└── charts/
    ├── hazelcast-cluster/              # 3-node StatefulSet
    │   ├── Chart.yaml
    │   ├── values.yaml
    │   └── templates/
    │       ├── statefulset.yaml
    │       ├── service.yaml
    │       ├── service-headless.yaml
    │       ├── configmap.yaml
    │       ├── pdb.yaml                # PDB (enabled by default)
    │       └── serviceaccount.yaml
    ├── account-service/                # Account microservice
    │   ├── Chart.yaml
    │   ├── values.yaml
    │   └── templates/
    │       ├── deployment.yaml
    │       ├── service.yaml
    │       ├── configmap.yaml
    │       ├── hpa.yaml                # HPA (disabled by default)
    │       ├── pdb.yaml                # PDB (disabled by default)
    │       └── serviceaccount.yaml
    ├── inventory-service/              # Inventory microservice
    │   └── (same structure as account-service)
    ├── order-service/                  # Order microservice (saga orchestrator)
    │   └── (same structure as account-service)
    ├── payment-service/                # Payment microservice
    │   └── (same structure as account-service)
    ├── api-gateway/                    # API Gateway + Ingress
    │   ├── values.yaml
    │   └── templates/
    │       ├── deployment.yaml
    │       ├── service.yaml
    │       ├── ingress.yaml
    │       ├── configmap.yaml
    │       ├── hpa.yaml
    │       ├── pdb.yaml
    │       └── serviceaccount.yaml
    ├── mcp-server/                     # MCP Server (AI tool proxy)
    │   └── templates/
    │       ├── deployment.yaml
    │       ├── service.yaml
    │       ├── configmap.yaml
    │       └── serviceaccount.yaml
    └── monitoring/                     # Prometheus + Grafana + Jaeger
        ├── dashboards/
        │   ├── event-flow.json
        │   ├── materialized-views.json
        │   ├── saga-dashboard.json
        │   └── system-overview.json
        └── templates/
            ├── prometheus-deployment.yaml
            ├── prometheus-configmap.yaml
            ├── prometheus-service.yaml
            ├── grafana-deployment.yaml
            ├── grafana-service.yaml
            ├── grafana-configmap-*.yaml
            ├── jaeger-deployment.yaml
            ├── jaeger-service.yaml
            └── serviceaccount.yaml
```

## Further Reading

- [Cloud Deployment & Cost Guide](../docs/guides/cloud-deployment-guide.md) — AWS EKS, GCP GKE, Azure AKS pricing
- [ADR 008: Dual-Instance Architecture](../docs/architecture/adr/008-dual-instance-hazelcast-architecture.md)
- [Hazelcast Kubernetes Discovery](https://docs.hazelcast.com/hazelcast/5.6/kubernetes/deploying-in-kubernetes)
