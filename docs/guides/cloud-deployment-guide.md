# Cloud Deployment & Cost Guide

Cost estimates for deploying the Hazelcast Microservices Framework on managed Kubernetes across AWS, GCP, and Azure.

> **Prices are approximate** and based on US regions (us-east-1, us-central1, eastus) as of early 2026. Actual costs vary by region, reserved instances, and negotiated discounts. Always verify with the provider's pricing calculator.

## Deployment Tiers

### Demo Tier

Minimal deployment for demonstrations and local testing.

| Component | Instances | CPU Request | Memory Request |
|-----------|-----------|-------------|----------------|
| Hazelcast Cluster | 3 | 250m each | 256Mi each |
| Account Service | 1 | 250m | 256Mi |
| Inventory Service | 1 | 250m | 256Mi |
| Order Service | 1 | 250m | 256Mi |
| Payment Service | 1 | 250m | 256Mi |
| API Gateway | 1 | 250m | 128Mi |
| **Total** | **8 pods** | **2,250m** | **1,920Mi** |

**Node requirement**: 1 node with 4 vCPU / 16 GB RAM

### Staging Tier

Adds monitoring stack and more headroom for testing.

| Component | Instances | CPU Request | Memory Request |
|-----------|-----------|-------------|----------------|
| Hazelcast Cluster | 3 | 250m each | 256Mi each |
| Account Service | 1 | 250m | 256Mi |
| Inventory Service | 1 | 250m | 256Mi |
| Order Service | 1 | 250m | 256Mi |
| Payment Service | 1 | 250m | 256Mi |
| API Gateway | 1 | 250m | 128Mi |
| MCP Server | 1 | 250m | 128Mi |
| Prometheus | 1 | 250m | 256Mi |
| Grafana | 1 | 100m | 128Mi |
| Jaeger | 1 | 250m | 256Mi |
| **Total** | **12 pods** | **3,100m** | **2,688Mi** |

**Node requirement**: 2 nodes with 4 vCPU / 16 GB RAM each

### Production Tier

HPA-managed replicas, PDBs, and monitoring for real workloads.

| Component | Instances | CPU Request | Memory Request |
|-----------|-----------|-------------|----------------|
| Hazelcast Cluster | 3 | 1000m each | 1Gi each |
| Account Service | 2-5 (HPA) | 500m each | 512Mi each |
| Inventory Service | 2-5 (HPA) | 500m each | 512Mi each |
| Order Service | 2-5 (HPA) | 500m each | 512Mi each |
| Payment Service | 2-5 (HPA) | 500m each | 512Mi each |
| API Gateway | 2-5 (HPA) | 250m each | 256Mi each |
| MCP Server | 1 | 250m | 128Mi |
| Prometheus | 1 | 500m | 1Gi |
| Grafana | 1 | 250m | 256Mi |
| Jaeger | 1 | 500m | 512Mi |
| **Total (min)** | **20 pods** | **9,500m** | **8,448Mi** |

**Node requirement**: 3 nodes with 4 vCPU / 16 GB RAM each

---

## Cost Estimates by Provider

### AWS EKS

| Component | Demo | Staging | Production |
|-----------|------|---------|------------|
| EKS control plane | $73/mo | $73/mo | $73/mo |
| EC2 nodes (m6i.xlarge, on-demand) | $140/mo (1 node) | $280/mo (2 nodes) | $420/mo (3 nodes) |
| EC2 nodes (m6i.xlarge, spot) | $42-56/mo (1 node) | $84-112/mo (2 nodes) | $126-168/mo (3 nodes) |
| ALB (Ingress) | $16/mo + usage | $16/mo + usage | $16/mo + usage |
| EBS (30 GB gp3 per node) | $2.40/mo | $4.80/mo | $7.20/mo |
| **Total (on-demand)** | **~$231/mo** | **~$374/mo** | **~$516/mo** |
| **Total (spot)** | **~$133/mo** | **~$178/mo** | **~$222/mo** |

**m6i.xlarge**: 4 vCPU, 16 GB RAM, ~$0.192/hr on-demand, ~$0.058-0.077/hr spot

### GCP GKE

| Component | Demo | Staging | Production |
|-----------|------|---------|------------|
| GKE control plane (Autopilot or Standard) | $73/mo (Standard) | $73/mo | $73/mo |
| Compute (e2-standard-4, on-demand) | $98/mo (1 node) | $196/mo (2 nodes) | $294/mo (3 nodes) |
| Compute (e2-standard-4, spot) | $29/mo (1 node) | $59/mo (2 nodes) | $88/mo (3 nodes) |
| Load Balancer | $18/mo + usage | $18/mo + usage | $18/mo + usage |
| Persistent Disk (30 GB pd-ssd per node) | $5.10/mo | $10.20/mo | $15.30/mo |
| **Total (on-demand)** | **~$194/mo** | **~$297/mo** | **~$400/mo** |
| **Total (spot)** | **~$125/mo** | **~$160/mo** | **~$194/mo** |

**e2-standard-4**: 4 vCPU, 16 GB RAM, ~$0.134/hr on-demand, ~$0.040/hr spot

### Azure AKS

| Component | Demo | Staging | Production |
|-----------|------|---------|------------|
| AKS control plane | Free | Free | $73/mo (Uptime SLA) |
| VMs (D4s_v5, on-demand) | $140/mo (1 node) | $280/mo (2 nodes) | $420/mo (3 nodes) |
| VMs (D4s_v5, spot) | $28-42/mo (1 node) | $56-84/mo (2 nodes) | $84-126/mo (3 nodes) |
| Load Balancer | $18/mo + usage | $18/mo + usage | $18/mo + usage |
| Managed Disk (32 GB P4 per node) | $5.80/mo | $11.60/mo | $17.40/mo |
| **Total (on-demand)** | **~$164/mo** | **~$310/mo** | **~$528/mo** |
| **Total (spot)** | **~$52/mo** | **~$86/mo** | **~$192/mo** |

**D4s_v5**: 4 vCPU, 16 GB RAM, ~$0.192/hr on-demand, ~$0.038-0.058/hr spot

---

## Cost Comparison Summary

### On-Demand Pricing

| Tier | AWS EKS | GCP GKE | Azure AKS |
|------|---------|---------|-----------|
| Demo | ~$231/mo | ~$194/mo | ~$164/mo |
| Staging | ~$374/mo | ~$297/mo | ~$310/mo |
| Production | ~$516/mo | ~$400/mo | ~$528/mo |

### Spot/Preemptible Pricing

| Tier | AWS EKS | GCP GKE | Azure AKS |
|------|---------|---------|-----------|
| Demo | ~$133/mo | ~$125/mo | ~$52/mo |
| Staging | ~$178/mo | ~$160/mo | ~$86/mo |
| Production | ~$222/mo | ~$194/mo | ~$192/mo |

---

## Cost Optimization Tips

### Use Spot/Preemptible Instances

Spot instances provide 60-80% savings. The framework's architecture supports this well:

- **Microservices** (stateless): Safe for spot — HPA + PDB handle interruptions gracefully
- **API Gateway** (stateless): Safe for spot with multiple replicas
- **Hazelcast Cluster** (stateful): Use spot cautiously — PDB (minAvailable: 2) protects quorum, but rapid multi-node eviction can cause data loss without persistence enabled

```yaml
# Example: Mixed node pools (on-demand for Hazelcast, spot for services)
# Use nodeSelector or affinity in values.yaml:
hazelcast-cluster:
  nodeSelector:
    cloud.google.com/gke-provisioning: standard  # GKE on-demand pool
account-service:
  nodeSelector:
    cloud.google.com/gke-provisioning: spot       # GKE spot pool
```

### Right-Size Resources

The default resource requests are conservative. Monitor actual usage with Prometheus/Grafana and adjust:

```bash
# Check actual resource consumption
kubectl top pods

# Lower limits for demo/dev environments
helm install demo . \
  --set account-service.resources.requests.cpu=100m \
  --set account-service.resources.requests.memory=128Mi \
  --set account-service.jvm.xmx=256m
```

### Use Smaller Node Types for Demo

For demo/dev, a single node with 2 vCPU / 8 GB works if you reduce resource requests:

| Provider | Instance | Price |
|----------|----------|-------|
| AWS | t3.large (2 vCPU, 8 GB) | ~$60/mo on-demand |
| GCP | e2-standard-2 (2 vCPU, 8 GB) | ~$49/mo on-demand |
| Azure | B2ms (2 vCPU, 8 GB) | ~$60/mo on-demand |

### GKE Autopilot

GKE Autopilot charges per-pod resource requests instead of per-node. For small deployments, this eliminates idle node capacity waste:

- Demo tier: ~$80-100/mo (pay only for what pods request)
- No node management overhead

### Committed Use Discounts

For production workloads running 24/7:

| Provider | Commitment | Discount |
|----------|-----------|----------|
| AWS | 1-year Savings Plan | ~30% off on-demand |
| AWS | 3-year Savings Plan | ~50% off on-demand |
| GCP | 1-year CUD | ~28% off on-demand |
| GCP | 3-year CUD | ~46% off on-demand |
| Azure | 1-year Reserved | ~30% off on-demand |
| Azure | 3-year Reserved | ~50% off on-demand |

### Shut Down Non-Production

Use scheduled scaling or cluster deletion for dev/staging environments:

```bash
# Scale down staging at night
kubectl scale deployment --all --replicas=0

# Or delete the Helm release entirely
helm uninstall staging

# Re-create in the morning
helm install staging .
```

---

## Network Costs

Egress charges are often overlooked:

| Provider | Intra-AZ | Cross-AZ | Internet Egress |
|----------|----------|----------|-----------------|
| AWS | Free | $0.01/GB | $0.09/GB (first 10 TB) |
| GCP | Free | $0.01/GB | $0.12/GB (first 1 TB) |
| Azure | Free | Free (same region) | $0.087/GB (first 5 TB) |

**Recommendation**: Deploy all pods in a single availability zone for demo/staging to avoid cross-AZ charges. For production, use multi-AZ for resilience and accept the cost.

---

## Choosing a Provider

| Factor | AWS EKS | GCP GKE | Azure AKS |
|--------|---------|---------|-----------|
| Control plane cost | $73/mo always | $73/mo (Standard) | Free (no SLA) / $73 (SLA) |
| Cheapest spot | Moderate | Good | Best |
| Autopilot option | Fargate (limited) | Yes (excellent) | No |
| Hazelcast support | Good (EKS) | Good (GKE) | Good (AKS) |
| Best for demo | GCP (Autopilot) | GCP (Autopilot) | Azure (free control plane) |
| Best for production | Any (mature) | GCP (Autopilot) | Any (mature) |

For this framework's demo purposes, **Azure AKS** offers the lowest entry cost (free control plane + cheapest spot VMs). For hands-off production, **GKE Autopilot** eliminates node management entirely.
