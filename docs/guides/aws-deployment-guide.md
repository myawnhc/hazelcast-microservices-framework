# AWS EKS Deployment Guide

Deploy the Hazelcast Microservices Framework to Amazon EKS with tiered configurations for proof-of-concept, scaling demos, and production extrapolation.

## Prerequisites

### Step 1: Install Required Tools

**macOS (Homebrew)**:

```bash
# AWS CLI v2
brew install awscli
aws --version   # Should show aws-cli/2.x.x

# eksctl (creates and manages EKS clusters)
brew tap weaveworks/tap
brew install weaveworks/tap/eksctl
eksctl version  # Should show 0.170+

# kubectl (Kubernetes CLI)
brew install kubectl
kubectl version --client

# Helm (Kubernetes package manager)
brew install helm
helm version

# Docker Desktop — download from https://www.docker.com/products/docker-desktop/
# After install, verify:
docker --version  # Should show 24+

# k6 (load testing)
brew install k6
```

**Linux (Ubuntu/Debian)**:

```bash
# AWS CLI v2
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip && sudo ./aws/install

# eksctl
ARCH=amd64
PLATFORM=$(uname -s)_$ARCH
curl -sLO "https://github.com/eksctl-io/eksctl/releases/latest/download/eksctl_$PLATFORM.tar.gz"
tar -xzf eksctl_$PLATFORM.tar.gz -C /tmp && sudo mv /tmp/eksctl /usr/local/bin

# kubectl
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl

# Helm
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

# k6
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
  --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D68
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | \
  sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6
```

**Verify all tools**:

```bash
aws --version && eksctl version && kubectl version --client && helm version --short && docker --version && k6 version
```

### Step 2: AWS Account and IAM User Setup

If you're creating a dedicated IAM user for this demo (recommended), follow these steps in the AWS Console.

#### 2a. Create a Dedicated IAM User

1. Go to **IAM > Users > Create user**
2. User name: `hazelcast-demo-admin` (or your preference)
3. Select **Provide user access to the AWS Management Console** if you want Console access
4. Click **Next**

#### 2b. Create a Custom IAM Policy

Instead of granting broad `AdministratorAccess`, create a scoped policy. Go to **IAM > Policies > Create policy**, switch to JSON, and paste:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "EKSFullAccess",
            "Effect": "Allow",
            "Action": "eks:*",
            "Resource": "*"
        },
        {
            "Sid": "EC2ForNodeGroups",
            "Effect": "Allow",
            "Action": [
                "ec2:Describe*",
                "ec2:CreateSecurityGroup",
                "ec2:DeleteSecurityGroup",
                "ec2:AuthorizeSecurityGroupIngress",
                "ec2:AuthorizeSecurityGroupEgress",
                "ec2:RevokeSecurityGroupIngress",
                "ec2:RevokeSecurityGroupEgress",
                "ec2:CreateTags",
                "ec2:DeleteTags",
                "ec2:RunInstances",
                "ec2:TerminateInstances",
                "ec2:CreateLaunchTemplate",
                "ec2:DeleteLaunchTemplate",
                "ec2:CreateLaunchTemplateVersion",
                "ec2:CreateVolume",
                "ec2:DeleteVolume",
                "ec2:AttachVolume",
                "ec2:DetachVolume",
                "ec2:AllocateAddress",
                "ec2:ReleaseAddress",
                "ec2:AssociateAddress",
                "ec2:DisassociateAddress",
                "ec2:CreateInternetGateway",
                "ec2:DeleteInternetGateway",
                "ec2:AttachInternetGateway",
                "ec2:DetachInternetGateway",
                "ec2:CreateSubnet",
                "ec2:DeleteSubnet",
                "ec2:CreateVpc",
                "ec2:DeleteVpc",
                "ec2:ModifyVpcAttribute",
                "ec2:CreateRouteTable",
                "ec2:DeleteRouteTable",
                "ec2:CreateRoute",
                "ec2:DeleteRoute",
                "ec2:AssociateRouteTable",
                "ec2:DisassociateRouteTable",
                "ec2:CreateNatGateway",
                "ec2:DeleteNatGateway"
            ],
            "Resource": "*"
        },
        {
            "Sid": "ECRContainerRegistry",
            "Effect": "Allow",
            "Action": [
                "ecr:GetAuthorizationToken",
                "ecr:CreateRepository",
                "ecr:DeleteRepository",
                "ecr:DescribeRepositories",
                "ecr:ListImages",
                "ecr:BatchDeleteImage",
                "ecr:BatchCheckLayerAvailability",
                "ecr:GetDownloadUrlForLayer",
                "ecr:BatchGetImage",
                "ecr:PutImage",
                "ecr:InitiateLayerUpload",
                "ecr:UploadLayerPart",
                "ecr:CompleteLayerUpload",
                "ecr:PutImageScanningConfiguration"
            ],
            "Resource": "*"
        },
        {
            "Sid": "IAMForServiceAccounts",
            "Effect": "Allow",
            "Action": [
                "iam:CreateRole",
                "iam:DeleteRole",
                "iam:AttachRolePolicy",
                "iam:DetachRolePolicy",
                "iam:PutRolePolicy",
                "iam:DeleteRolePolicy",
                "iam:GetRole",
                "iam:GetRolePolicy",
                "iam:ListRolePolicies",
                "iam:ListAttachedRolePolicies",
                "iam:PassRole",
                "iam:CreateOpenIDConnectProvider",
                "iam:DeleteOpenIDConnectProvider",
                "iam:GetOpenIDConnectProvider",
                "iam:ListOpenIDConnectProviders",
                "iam:TagOpenIDConnectProvider",
                "iam:CreateServiceLinkedRole",
                "iam:CreateInstanceProfile",
                "iam:DeleteInstanceProfile",
                "iam:AddRoleToInstanceProfile",
                "iam:RemoveRoleFromInstanceProfile",
                "iam:GetInstanceProfile",
                "iam:TagRole"
            ],
            "Resource": "*"
        },
        {
            "Sid": "CloudFormationForEksctl",
            "Effect": "Allow",
            "Action": "cloudformation:*",
            "Resource": "*"
        },
        {
            "Sid": "ELBForIngress",
            "Effect": "Allow",
            "Action": "elasticloadbalancing:*",
            "Resource": "*"
        },
        {
            "Sid": "AutoScalingForNodeGroups",
            "Effect": "Allow",
            "Action": "autoscaling:*",
            "Resource": "*"
        },
        {
            "Sid": "STSForIdentity",
            "Effect": "Allow",
            "Action": [
                "sts:GetCallerIdentity",
                "sts:AssumeRole"
            ],
            "Resource": "*"
        },
        {
            "Sid": "SSMForAMILookup",
            "Effect": "Allow",
            "Action": [
                "ssm:GetParameter"
            ],
            "Resource": "arn:aws:ssm:*:*:parameter/aws/service/eks/*"
        },
        {
            "Sid": "KMSForSecrets",
            "Effect": "Allow",
            "Action": [
                "kms:CreateKey",
                "kms:CreateAlias",
                "kms:Describe*",
                "kms:List*",
                "kms:CreateGrant"
            ],
            "Resource": "*"
        },
        {
            "Sid": "LogsForCluster",
            "Effect": "Allow",
            "Action": [
                "logs:CreateLogGroup",
                "logs:DeleteLogGroup",
                "logs:DescribeLogGroups",
                "logs:PutRetentionPolicy",
                "logs:TagResource"
            ],
            "Resource": "*"
        }
    ]
}
```

Name the policy `HazelcastDemoEKSPolicy` and click **Create policy**.

> **Shortcut**: If you prefer not to manage fine-grained permissions for a temporary demo user, attach the AWS-managed `AdministratorAccess` policy instead and delete the user when done.

#### 2c. Attach the Policy to Your User

1. Go to **IAM > Users > hazelcast-demo-admin > Permissions**
2. Click **Add permissions > Attach policies directly**
3. Search for `HazelcastDemoEKSPolicy` and attach it

#### 2d. Create Access Keys

1. Go to **IAM > Users > hazelcast-demo-admin > Security credentials**
2. Click **Create access key**
3. Select **Command Line Interface (CLI)**
4. Copy the **Access key ID** and **Secret access key** (you won't see the secret again)

### Step 3: Configure AWS CLI

```bash
aws configure
# AWS Access Key ID:     <paste your access key>
# AWS Secret Access Key: <paste your secret key>
# Default region name:   us-east-1
# Default output format: json

# Verify it works
aws sts get-caller-identity
# Should show your account ID and user ARN
```

### Step 4: Service Quotas (Required for Large Tier)

The Large tier runs 5x c7i.4xlarge instances (16 vCPU each = 80 vCPU total). New AWS accounts have a default vCPU quota that may be too low.

**Check your current limits**:

```bash
aws service-quotas get-service-quota \
  --service-code ec2 \
  --quota-code L-1216C47A \
  --query 'Quota.Value' \
  --output text
```

This shows your "Running On-Demand Standard instances" vCPU limit.

**Required quotas by tier**:

| Tier | Instance Type | Nodes | vCPU per Node | Total vCPU Required | Default Quota |
|------|--------------|-------|---------------|---------------------|---------------|
| Small | t3.xlarge | 2 | 4 | 8 | 32 (sufficient) |
| Medium | c7i.2xlarge | 3 | 8 | 24 | 32 (sufficient) |
| Large | c7i.4xlarge | 5 | 16 | **80** | 32 (**increase needed**) |

**To request a quota increase** (for Large tier):

1. Go to **Service Quotas > EC2 > Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances**
2. Click **Request quota increase**
3. Set new value to **128** (gives headroom for node group scaling)
4. AWS typically approves within minutes to a few hours for moderate increases

Alternatively via CLI:

```bash
aws service-quotas request-service-quota-increase \
  --service-code ec2 \
  --quota-code L-1216C47A \
  --desired-value 128
```

> **Tip**: Request the quota increase before you need it. Small and Medium tiers work within default limits.

**Additional quotas to verify** (rarely an issue, but worth checking for new accounts):

| Quota | Service | Default | Required |
|-------|---------|---------|----------|
| VPCs per region | VPC | 5 | 1 |
| Elastic IPs per region | EC2 | 5 | 3 (NAT gateways) |
| Internet gateways per region | VPC | 5 | 1 |
| NAT gateways per AZ | VPC | 5 | 1 per AZ used |
| ECR repositories per region | ECR | 10,000 | 6 |

---

## Environment Setup

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `AWS_REGION` | No | `us-east-1` | AWS region for all operations |
| `AWS_ACCOUNT_ID` | No | auto-detected | Used for ECR URIs |
| `AWS_PROFILE` | No | `default` | AWS CLI named profile |
| `ECR_REPOSITORY_PREFIX` | No | `hazelcast-microservices` | ECR repo prefix |

```bash
# Verify credentials are working
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

> **Quota check**: This tier requires 80 vCPU (5x c7i.4xlarge). New AWS accounts default to 32 vCPU. See [Step 4: Service Quotas](#step-4-service-quotas-required-for-large-tier) above to request an increase before deploying.

| Setting | Value |
|---------|-------|
| **Nodes** | 5x c7i.4xlarge (16 vCPU, 32GB each) |
| **Hazelcast** | 3 replicas, 8GB heap, 12Gi limit, dedicated node group |
| **Services** | 2-5 replicas (HPA enabled), 2GB heap, 3Gi limit |
| **Networking** | Multi-AZ, strict topology spread |
| **Scaling** | HPA on all services, PDBs, hard anti-affinity |
| **Embedded clustering** | Enabled (ADR 013) — same-service replicas form per-service cluster |
| **Cost** | ~$3.50/hr |

Best for: Performance testing, scaling demos, production architecture validation. Sustains **200 TPS with sub-1s saga completion** (tested on AWS EKS, Session 11).

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

### Quota Exceeded on Cluster Creation

```
Cannot create cluster 'hazelcast-demo' because ... the maximum number of vCPUs ...
```

Your account's On-Demand vCPU quota is too low for the requested tier. See [Step 4: Service Quotas](#step-4-service-quotas-required-for-large-tier) to request an increase. The Large tier requires 80 vCPU (default quota is 32).

### IAM Permission Denied During Setup

```
An error occurred (AccessDeniedException) when calling the CreateCluster operation
```

The IAM user is missing required permissions. If using the custom `HazelcastDemoEKSPolicy`, verify it's attached to your user. `eksctl` needs broad permissions because it creates CloudFormation stacks that provision VPCs, subnets, NAT gateways, security groups, and IAM roles. As a quick workaround for demos, temporarily attach `AdministratorAccess` and narrow permissions later.

---

## Cleanup

### Delete AWS Resources

```bash
# Tear down the EKS cluster, ECR repos, and all associated resources
./scripts/k8s-aws/teardown-cluster.sh
```

The teardown script deletes: the EKS cluster and node groups, all ECR repositories and images, and the underlying CloudFormation stacks. After it completes, verify in the AWS Console that no orphaned resources remain (EC2 instances, load balancers, NAT gateways, Elastic IPs).

### Delete the Demo IAM User (Optional)

If you created a dedicated IAM user for this demo:

1. Go to **IAM > Users > hazelcast-demo-admin**
2. Delete the access keys under **Security credentials**
3. Click **Delete user**

Or via CLI:

```bash
aws iam delete-access-key --user-name hazelcast-demo-admin --access-key-id <YOUR_KEY_ID>
aws iam detach-user-policy --user-name hazelcast-demo-admin --policy-arn arn:aws:iam::<ACCOUNT_ID>:policy/HazelcastDemoEKSPolicy
aws iam delete-user --user-name hazelcast-demo-admin
aws iam delete-policy --policy-arn arn:aws:iam::<ACCOUNT_ID>:policy/HazelcastDemoEKSPolicy
```

---

## Related Documentation

- [Local K8s Deployment](../../k8s/README.md) — Docker Desktop Kubernetes
- [Cloud Deployment Guide](cloud-deployment-guide.md) — General cloud guidance
- [ADR 008](../architecture/adr/008-dual-instance-hazelcast-architecture.md) — Dual-instance architecture
- [Performance Testing](../../scripts/perf/) — k6 load test scripts
