#!/bin/bash
# =============================================================================
# EKS Cluster Setup Script
# Creates an EKS cluster, ECR repositories, and installs prerequisites
# =============================================================================
#
# Usage: ./scripts/k8s-aws/setup-cluster.sh [--tier small|medium|large] [--region REGION] [--profile PROFILE]
#
# Prerequisites:
#   - AWS CLI v2 configured with appropriate IAM permissions
#   - eksctl installed (https://eksctl.io)
#   - kubectl installed
#   - helm installed
#
# Environment variables (optional):
#   EKS_ADDITIONAL_ADMIN_ARN  Grant cluster-admin to another IAM principal
#                             (e.g., root account for AWS Console EKS access)
#                             Example: export EKS_ADDITIONAL_ADMIN_ARN="arn:aws:iam::123456789012:root"
#
# Duration: ~15-20 minutes (EKS cluster creation is the bottleneck)

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/../.." && pwd )"

# -----------------------------------------------
# Defaults and argument parsing
# -----------------------------------------------
TIER="small"
REGION="${AWS_REGION:-us-east-1}"
PROFILE="${AWS_PROFILE:-default}"
CLUSTER_NAME="hazelcast-demo"
NAMESPACE="hazelcast-demo"
ECR_PREFIX="${ECR_REPOSITORY_PREFIX:-hazelcast-microservices}"
SERVICES="account-service inventory-service order-service payment-service api-gateway mcp-server"

for arg in "$@"; do
    case "$arg" in
        --tier)     shift; TIER="$1"; shift ;;
        --region)   shift; REGION="$1"; shift ;;
        --profile)  shift; PROFILE="$1"; shift ;;
        --help)
            echo "Usage: $0 [--tier small|medium|large] [--region REGION] [--profile PROFILE]"
            echo ""
            echo "Options:"
            echo "  --tier      Deployment tier: small, medium, large (default: small)"
            echo "  --region    AWS region (default: us-east-1 or AWS_REGION env var)"
            echo "  --profile   AWS CLI profile (default: default or AWS_PROFILE env var)"
            exit 0
            ;;
    esac
done

# Validate tier
case "$TIER" in
    small|medium|large) ;;
    *) echo "ERROR: Invalid tier '${TIER}'. Must be small, medium, or large."; exit 1 ;;
esac

echo "============================================"
echo "Hazelcast Microservices - EKS Setup"
echo "============================================"
echo ""
echo "  Tier:     ${TIER}"
echo "  Region:   ${REGION}"
echo "  Profile:  ${PROFILE}"
echo "  Cluster:  ${CLUSTER_NAME}"
echo ""

# -----------------------------------------------
# Preflight checks
# -----------------------------------------------
echo "Preflight checks..."

for cmd in aws eksctl kubectl helm; do
    if ! command -v "$cmd" > /dev/null 2>&1; then
        echo "ERROR: '${cmd}' not found. Please install it first."
        case "$cmd" in
            aws)     echo "  Install: https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html" ;;
            eksctl)  echo "  Install: https://eksctl.io/installation/" ;;
            kubectl) echo "  Install: https://kubernetes.io/docs/tasks/tools/" ;;
            helm)    echo "  Install: https://helm.sh/docs/intro/install/" ;;
        esac
        exit 1
    fi
done

# Verify AWS credentials
if ! aws sts get-caller-identity --profile "$PROFILE" --region "$REGION" > /dev/null 2>&1; then
    echo "ERROR: AWS credentials not configured or expired."
    echo "  Run: aws configure --profile ${PROFILE}"
    exit 1
fi

AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-$(aws sts get-caller-identity --profile "$PROFILE" --query 'Account' --output text)}"
echo "  AWS Account: ${AWS_ACCOUNT_ID}"
echo "  All preflight checks passed."
echo ""

# -----------------------------------------------
# Define node group config per tier
# -----------------------------------------------
case "$TIER" in
    small)
        NODE_TYPE="t3.xlarge"
        NODE_COUNT=2
        NODE_MIN=2
        NODE_MAX=2
        ;;
    medium)
        NODE_TYPE="c7i.2xlarge"
        NODE_COUNT=3
        NODE_MIN=3
        NODE_MAX=4
        ;;
    large)
        NODE_TYPE="c7i.4xlarge"
        NODE_COUNT=5
        NODE_MIN=3
        NODE_MAX=6
        ;;
esac

# -----------------------------------------------
# Create EKS cluster
# -----------------------------------------------
if eksctl get cluster --name "$CLUSTER_NAME" --region "$REGION" --profile "$PROFILE" > /dev/null 2>&1; then
    echo "EKS cluster '${CLUSTER_NAME}' already exists. Skipping creation."
else
    echo "Creating EKS cluster '${CLUSTER_NAME}' (this takes ~15 minutes)..."
    echo "  Node type:  ${NODE_TYPE}"
    echo "  Node count: ${NODE_COUNT}"
    echo ""

    if [ "$TIER" = "large" ]; then
        # Large tier: two node groups (hazelcast-dedicated + services)
        eksctl create cluster \
            --name "$CLUSTER_NAME" \
            --region "$REGION" \
            --profile "$PROFILE" \
            --version 1.33 \
            --without-nodegroup

        eksctl create nodegroup \
            --cluster "$CLUSTER_NAME" \
            --region "$REGION" \
            --profile "$PROFILE" \
            --name hazelcast-dedicated \
            --node-type c7i.4xlarge \
            --nodes 3 \
            --nodes-min 3 \
            --nodes-max 3 \
            --node-labels "nodegroup=hazelcast-dedicated"

        eksctl create nodegroup \
            --cluster "$CLUSTER_NAME" \
            --region "$REGION" \
            --profile "$PROFILE" \
            --name services \
            --node-type c7i.4xlarge \
            --nodes 2 \
            --nodes-min 2 \
            --nodes-max 4

        # Apply taint to hazelcast-dedicated nodes (CLI doesn't support --node-taints)
        echo "  Applying taint to hazelcast-dedicated nodes..."
        kubectl taint nodes -l nodegroup=hazelcast-dedicated dedicated=hazelcast:NoSchedule --overwrite
    else
        eksctl create cluster \
            --name "$CLUSTER_NAME" \
            --region "$REGION" \
            --profile "$PROFILE" \
            --version 1.33 \
            --node-type "$NODE_TYPE" \
            --nodes "$NODE_COUNT" \
            --nodes-min "$NODE_MIN" \
            --nodes-max "$NODE_MAX" \
            --managed
    fi

    echo "  EKS cluster created successfully."
fi
echo ""

# -----------------------------------------------
# Update kubeconfig
# -----------------------------------------------
echo "Updating kubeconfig..."
aws eks update-kubeconfig \
    --name "$CLUSTER_NAME" \
    --region "$REGION" \
    --profile "$PROFILE"
echo ""

# -----------------------------------------------
# Grant additional admin access (optional)
# -----------------------------------------------
# Set EKS_ADDITIONAL_ADMIN_ARN to grant cluster-admin to another IAM principal
# (e.g., your root account for AWS Console access to EKS resources).
#
# Example:
#   export EKS_ADDITIONAL_ADMIN_ARN="arn:aws:iam::123456789012:root"
#   ./scripts/k8s-aws/setup-cluster.sh --tier small
#
if [ -n "${EKS_ADDITIONAL_ADMIN_ARN:-}" ]; then
    echo "Granting cluster-admin access to: ${EKS_ADDITIONAL_ADMIN_ARN}"

    # Create access entry (ignore error if it already exists)
    aws eks create-access-entry \
        --cluster-name "$CLUSTER_NAME" \
        --principal-arn "$EKS_ADDITIONAL_ADMIN_ARN" \
        --region "$REGION" \
        --profile "$PROFILE" 2>/dev/null \
    && echo "  Access entry created." \
    || echo "  Access entry already exists."

    # Associate cluster-admin policy
    aws eks associate-access-policy \
        --cluster-name "$CLUSTER_NAME" \
        --principal-arn "$EKS_ADDITIONAL_ADMIN_ARN" \
        --policy-arn arn:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy \
        --access-scope type=cluster \
        --region "$REGION" \
        --profile "$PROFILE" 2>/dev/null \
    && echo "  Cluster-admin policy associated." \
    || echo "  Policy already associated."

    echo ""
fi

# -----------------------------------------------
# Create ECR repositories
# -----------------------------------------------
echo "Creating ECR repositories..."
for svc in $SERVICES; do
    repo_name="${ECR_PREFIX}/${svc}"
    if aws ecr describe-repositories --repository-names "$repo_name" --region "$REGION" --profile "$PROFILE" > /dev/null 2>&1; then
        echo "  ${repo_name} (exists)"
    else
        aws ecr create-repository \
            --repository-name "$repo_name" \
            --region "$REGION" \
            --profile "$PROFILE" \
            --image-scanning-configuration scanOnPush=true \
            --query 'repository.repositoryUri' \
            --output text
        echo "  ${repo_name} (created)"
    fi
done
echo ""

# -----------------------------------------------
# Install metrics-server (if not present)
# -----------------------------------------------
echo "Checking metrics-server..."
if kubectl get deployment metrics-server -n kube-system > /dev/null 2>&1; then
    echo "  metrics-server already installed."
else
    echo "  Installing metrics-server..."
    kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
    echo "  metrics-server installed."
fi
echo ""

# -----------------------------------------------
# Install AWS Load Balancer Controller (if not present)
# -----------------------------------------------
echo "Checking AWS Load Balancer Controller..."
if kubectl get deployment aws-load-balancer-controller -n kube-system > /dev/null 2>&1; then
    echo "  AWS Load Balancer Controller already installed."
else
    echo "  Installing AWS Load Balancer Controller..."

    # Create IAM OIDC provider
    eksctl utils associate-iam-oidc-provider \
        --cluster "$CLUSTER_NAME" \
        --region "$REGION" \
        --profile "$PROFILE" \
        --approve

    # Create IAM service account
    eksctl create iamserviceaccount \
        --cluster "$CLUSTER_NAME" \
        --region "$REGION" \
        --profile "$PROFILE" \
        --namespace kube-system \
        --name aws-load-balancer-controller \
        --attach-policy-arn "arn:aws:iam::${AWS_ACCOUNT_ID}:policy/AWSLoadBalancerControllerIAMPolicy" \
        --approve \
        --override-existing-serviceaccounts 2>/dev/null || \
    echo "  NOTE: If the IAM policy doesn't exist, create it first."
    echo "  See: https://docs.aws.amazon.com/eks/latest/userguide/aws-load-balancer-controller.html"

    # Install via Helm
    helm repo add eks https://aws.github.io/eks-charts 2>/dev/null || true
    helm repo update eks 2>/dev/null || true
    helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
        -n kube-system \
        --set clusterName="$CLUSTER_NAME" \
        --set serviceAccount.create=false \
        --set serviceAccount.name=aws-load-balancer-controller 2>/dev/null || \
    echo "  AWS Load Balancer Controller may need manual setup. See deployment guide."
fi
echo ""

# -----------------------------------------------
# Create namespace
# -----------------------------------------------
if kubectl get namespace "$NAMESPACE" > /dev/null 2>&1; then
    echo "Namespace '${NAMESPACE}' already exists."
else
    echo "Creating namespace '${NAMESPACE}'..."
    kubectl create namespace "$NAMESPACE"
fi
echo ""

# -----------------------------------------------
# Summary
# -----------------------------------------------
ECR_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

echo "============================================"
echo "EKS Setup Complete!"
echo ""
echo "Cluster:    ${CLUSTER_NAME}"
echo "Region:     ${REGION}"
echo "Tier:       ${TIER}"
echo "ECR URI:    ${ECR_URI}"
echo "Namespace:  ${NAMESPACE}"
echo ""
echo "Nodes:"
kubectl get nodes -o wide 2>/dev/null || echo "  (kubectl not yet configured)"
echo ""
echo "Next steps:"
echo "  1. Build & push images:  ./scripts/k8s-aws/build.sh --region ${REGION}"
echo "  2. Deploy:               ./scripts/k8s-aws/start.sh --tier ${TIER} --region ${REGION}"
echo "  3. Load data:            ./scripts/load-sample-data.sh"
echo "  4. Run tests:            ./scripts/perf/run-perf-test.sh"
echo ""
echo "To tear down:              ./scripts/k8s-aws/teardown-cluster.sh --region ${REGION}"
echo "============================================"
