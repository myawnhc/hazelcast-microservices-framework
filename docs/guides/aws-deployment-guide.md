# AWS EKS Deployment Guide

Deploy the Hazelcast Microservices Framework to Amazon EKS with tiered configurations for proof-of-concept, scaling demos, and production extrapolation.

## Prerequisites

| Tool | Minimum Version | Install |
|------|----------------|---------|
| AWS CLI | v2.x | [docs.aws.amazon.com](https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html) |
| eksctl | 0.170+ | [eksctl.io](https://eksctl.io/installation/) |
| kubectl | 1.25+ | [kubernetes.io](https://kubernetes.io/docs/tasks/tools/) |
| Helm | 3.12+ | [helm.sh](https://helm.sh/docs/intro/install/) |
| Docker | 24+ | [docker.com](https://docs.docker.com/get-docker/) |
| k6 | 0.47+ | `brew install k6` |

### IAM Permissions

The AWS user/role needs permissions for:
- **EKS**: `eks:*` (cluster management)
- **EC2**: `ec2:*` (node groups, networking)
- **ECR**: `ecr:*` (container registry)
- **IAM**: `iam:CreateRole`, `iam:AttachRolePolicy` (service accounts)
- **CloudFormation**: `cloudformation:*` (eksctl uses CF stacks)
- **ELB**: `elasticloadbalancing:*` (ALB Ingress)

---

## Environment Setup

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `AWS_REGION` | No | `us-east-1` | AWS region for all operations |
| `AWS_ACCOUNT_ID` | No | auto-detected | Used for ECR URIs |
| `AWS_PROFILE` | No | `default` | AWS CLI named profile |
| `ECR_REPOSITORY_PREFIX` | No | `hazelcast-microservices` | ECR repo prefix |

```bash
# Configure AWS CLI (if not already done)
aws configure --profile default

# Verify credentials
aws sts get-caller-identity
```

---

## Quick Start

```bash
# 1. Create EKS cluster and ECR repositories (~15 min)
./scripts/k8s-aws/setup-cluster.sh --tier small

# 2. Build images and push to ECR (~5 min)
./scripts/k8s-aws/build.sh

# 3. Deploy to EKS
./scripts/k8s-aws/start.sh --tier small

# 4. Load sample data
./scripts/load-sample-data.sh

# 5. Run performance tests
./scripts/perf/run-perf-test.sh --scenario order-saga

# 6. Tear down when done
./scripts/k8s-aws/teardown-cluster.sh
```

---

## Deployment Tiers

### Small — Proof of Concept

| Setting | Value |
|---------|-------|
| **Nodes** | 2x t3.xlarge (4 vCPU, 16GB each) |
| **Hazelcast** | 3 replicas, 1GB heap, 2Gi limit |
| **Services** | 1 replica each, 768MB heap, 1.5Gi limit |
| **Networking** | Single AZ, ALB Ingress |
| **Scaling** | No HPA, no PDBs (except Hazelcast) |
| **Cost** | ~$0.76/hr |

Best for: Smoke tests, baseline benchmarks, quick demos.

### Medium — Scaling Demo

| Setting | Value |
|---------|-------|
| **Nodes** | 3x c7i.2xlarge (8 vCPU, 16GB each) |
| **Hazelcast** | 3 replicas, 4GB heap, 6Gi limit, ZONE_AWARE |
| **Services** | 1-3 replicas (HPA enabled), 1GB heap, 2Gi limit |
| **Networking** | Multi-AZ, topology spread constraints |
| **Scaling** | HPA on all services, PDBs enabled |
| **Cost** | ~$1.18/hr |

Best for: Multi-AZ resilience demos, A-B performance testing, scaling demonstrations.

**Hazelcast features enabled:**
- ZONE_AWARE partition groups (backups always in a different AZ)
- Back-pressure (prevents OOM under sustained load)
- RBAC for Kubernetes API discovery
- Pod anti-affinity (prefer spreading across nodes)

### Large — Production Extrapolation

> **WARNING**: This tier is documented but untested. It extrapolates from medium-tier benchmarks.

| Setting | Value |
|---------|-------|
| **Nodes** | 5x c7i.4xlarge (16 vCPU, 32GB each) |
| **Hazelcast** | 5 replicas, 8GB heap, 12Gi limit, dedicated node pool |
| **Services** | 2-5 replicas (HPA enabled), 2GB heap, 3Gi limit |
| **Networking** | Multi-AZ, strict topology spread |
| **Scaling** | HPA on all services, PDBs, hard anti-affinity |
| **Cost** | ~$3.50/hr |

Best for: Capacity planning documentation, production architecture extrapolation.

---

## Hazelcast AWS Best Practices Applied

### G1 Garbage Collector
All tiers use G1GC. Hazelcast recommends G1GC for heaps up to 16GB, which covers all our tiers.

```yaml
jvm:
  gcOpts: "-XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### ZONE_AWARE Partition Groups (Medium+)
Ensures backup partitions are always stored in a different availability zone than primary partitions, providing AZ-level fault tolerance.

```yaml
partitionGroup:
  enabled: true
  type: "ZONE_AWARE"
```

### Back-Pressure (Medium+)
Prevents memory exhaustion under sustained high throughput by throttling operations when the system is overloaded.

```yaml
backPressure:
  enabled: true
```

### RBAC for Kubernetes Discovery
Hazelcast uses the Kubernetes API for cluster member discovery. RBAC grants the minimum permissions needed (get/list on pods, endpoints, services).

### Topology Spread
Medium tier uses `ScheduleAnyway` (soft constraint) for flexibility. Large tier uses `DoNotSchedule` (hard constraint) to enforce strict AZ distribution.

---

## Customizing for A-B Testing

Create a custom values file by copying an existing tier and adjusting:

```bash
# Copy medium as a starting point
cp k8s/hazelcast-microservices/values-aws-medium.yaml \
   k8s/hazelcast-microservices/values-aws-custom.yaml

# Edit as needed (e.g., change heap sizes, instance counts)
vim k8s/hazelcast-microservices/values-aws-custom.yaml

# Deploy with custom values
./scripts/k8s-aws/start.sh --tier custom
```

### Pinning to a Specific Image Version

The build script tags images with the git short hash. To pin a specific version:

```bash
helm upgrade demo k8s/hazelcast-microservices/ \
  -n hazelcast-demo \
  -f k8s/hazelcast-microservices/values-aws-medium.yaml \
  --set account-service.image.tag=abc1234 \
  --set inventory-service.image.tag=abc1234 \
  --set order-service.image.tag=abc1234 \
  --set payment-service.image.tag=abc1234 \
  --set api-gateway.image.tag=abc1234
```

---

## Performance Testing on AWS

### Running k6 Against ALB

When ALB is provisioned, run load tests directly against it:

```bash
# Get ALB URL
ALB_URL=$(kubectl get ingress -n hazelcast-demo \
  -o jsonpath='{.items[0].status.loadBalancer.ingress[0].hostname}')

# Run k6 via API Gateway
K6_BASE_URL="http://${ALB_URL}" ./scripts/perf/run-perf-test.sh \
  --scenario order-saga --tps 100 --duration 180
```

### Comparing with Docker Compose Baseline

1. Run Docker Compose locally, capture baseline metrics
2. Deploy to EKS small tier, run same test
3. Compare latency percentiles and throughput

---

## Cost Estimation

### Per-Tier Hourly Costs

| Component | Small | Medium | Large |
|-----------|-------|--------|-------|
| Compute (nodes) | $0.33/hr (2x t3.xl) | $1.07/hr (3x c7i.2xl) | $3.57/hr (5x c7i.4xl) |
| EKS control plane | $0.10/hr | $0.10/hr | $0.10/hr |
| ALB | $0.025/hr | $0.025/hr | $0.025/hr |
| EBS (gp3) | ~$0.003/hr | ~$0.005/hr | ~$0.008/hr |
| **Total** | **~$0.46/hr** | **~$1.20/hr** | **~$3.70/hr** |

### Session 11 Estimate

| Phase | Cluster | Duration | Cost |
|-------|---------|----------|------|
| Small: setup + test | small | ~1.5 hr | ~$0.70 |
| Transition | -- | ~0.5 hr | $0.00 |
| Medium: setup + test | medium | ~2 hr | ~$2.40 |
| ECR storage (1 day) | -- | -- | ~$0.10 |
| **Total** | | **~4 hours** | **~$4-8** |

Buffer for re-runs or debugging: **$10-15 max**.

---

## Troubleshooting

### ECR Authentication Errors

```
Error: pull access denied, repository does not exist or may require authentication
```

**Fix**: Re-authenticate with ECR:
```bash
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin \
  $(aws sts get-caller-identity --query Account --output text).dkr.ecr.us-east-1.amazonaws.com
```

### ALB Not Provisioning

The AWS Load Balancer Controller must be installed. Check:
```bash
kubectl get deployment aws-load-balancer-controller -n kube-system
kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller
```

If not installed, see the [AWS documentation](https://docs.aws.amazon.com/eks/latest/userguide/aws-load-balancer-controller.html).

### Image Pull Failures

```
ErrImagePull: failed to pull image
```

1. Verify images exist in ECR: `aws ecr list-images --repository-name hazelcast-microservices/account-service`
2. Check the image repository URL matches: `kubectl describe pod <pod-name> -n hazelcast-demo`
3. Verify architecture: images must be `linux/amd64` for x86 EKS nodes

### Hazelcast Discovery Issues

If Hazelcast pods can't find each other:
```bash
# Check RBAC
kubectl get role,rolebinding -n hazelcast-demo

# Check Hazelcast logs
kubectl logs -n hazelcast-demo demo-hazelcast-cluster-0 | grep -i "members"
```

Ensure `rbac.create: true` is set in the tier values file.

### Node Capacity / Scheduling Failures

```
0/3 nodes are available: insufficient memory
```

Check node allocatable resources:
```bash
kubectl describe nodes | grep -A5 "Allocatable"
kubectl top nodes
```

Consider scaling up node count or instance type.

### Pods OOMKilled

```
OOMKilled
```

Check the values file resource limits. Services running embedded Hazelcast + Jet need at least 1Gi memory limit. See the memory notes in `MEMORY.md`.

---

## Related Documentation

- [Local K8s Deployment](../../k8s/README.md) — Docker Desktop Kubernetes
- [Cloud Deployment Guide](cloud-deployment-guide.md) — General cloud guidance
- [ADR 008](../architecture/adr/008-dual-instance-hazelcast-architecture.md) — Dual-instance architecture
- [Performance Testing](../../scripts/perf/) — k6 load test scripts
