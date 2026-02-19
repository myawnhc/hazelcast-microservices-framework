#!/bin/bash
# =============================================================================
# AWS EKS Teardown Script
# Deletes the EKS cluster, ECR repositories, and all associated resources
# =============================================================================
#
# Usage: ./scripts/k8s-aws/teardown-cluster.sh [--region REGION] [--profile PROFILE] [--force]
#
# WARNING: This is a DESTRUCTIVE operation. It will:
#   - Delete all ECR images
#   - Delete all ECR repositories
#   - Delete the EKS cluster and all node groups
#   - Delete associated CloudFormation stacks

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# -----------------------------------------------
# Defaults and argument parsing
# -----------------------------------------------
REGION="${AWS_REGION:-us-east-1}"
PROFILE="${AWS_PROFILE:-default}"
CLUSTER_NAME="hazelcast-demo"
ECR_PREFIX="${ECR_REPOSITORY_PREFIX:-hazelcast-microservices}"
SERVICES="account-service inventory-service order-service payment-service api-gateway mcp-server"
FORCE=false

while [ $# -gt 0 ]; do
    case "$1" in
        --region)   shift; REGION="$1" ;;
        --profile)  shift; PROFILE="$1" ;;
        --force)    FORCE=true ;;
        --help)
            echo "Usage: $0 [--region REGION] [--profile PROFILE] [--force]"
            echo ""
            echo "Options:"
            echo "  --region    AWS region (default: us-east-1 or AWS_REGION env var)"
            echo "  --profile   AWS CLI profile (default: default or AWS_PROFILE env var)"
            echo "  --force     Skip confirmation prompt (for CI/CD)"
            exit 0
            ;;
    esac
    shift
done

echo "============================================"
echo "Hazelcast Microservices - EKS Teardown"
echo "============================================"
echo ""
echo "  Cluster:  ${CLUSTER_NAME}"
echo "  Region:   ${REGION}"
echo ""
echo "WARNING: This will permanently delete:"
echo "  - EKS cluster '${CLUSTER_NAME}' and all node groups"
echo "  - All ECR repositories under '${ECR_PREFIX}/'"
echo "  - All container images in those repositories"
echo ""

# -----------------------------------------------
# Confirmation
# -----------------------------------------------
if [ "$FORCE" = "false" ]; then
    echo "Type 'DELETE' to confirm:"
    read -r confirmation
    if [ "$confirmation" != "DELETE" ]; then
        echo "Aborted."
        exit 0
    fi
    echo ""
fi

# -----------------------------------------------
# Clean up port-forwards
# -----------------------------------------------
PID_FILE="$SCRIPT_DIR/port-forwards.pids"
if [ -f "$PID_FILE" ]; then
    echo "Stopping port-forward processes..."
    while read -r pid; do
        kill "$pid" 2>/dev/null || true
    done < "$PID_FILE"
    rm -f "$PID_FILE"
fi

# -----------------------------------------------
# Delete Helm release (if still deployed)
# -----------------------------------------------
echo "Cleaning up Helm release..."
helm uninstall demo -n hazelcast-demo 2>/dev/null || echo "  No Helm release found."
echo ""

# -----------------------------------------------
# Delete ECR repositories
# -----------------------------------------------
echo "Deleting ECR repositories..."
for svc in $SERVICES; do
    repo_name="${ECR_PREFIX}/${svc}"
    if aws ecr describe-repositories --repository-names "$repo_name" --region "$REGION" --profile "$PROFILE" > /dev/null 2>&1; then
        aws ecr delete-repository \
            --repository-name "$repo_name" \
            --region "$REGION" \
            --profile "$PROFILE" \
            --force > /dev/null
        echo "  Deleted: ${repo_name}"
    else
        echo "  Not found: ${repo_name}"
    fi
done
echo ""

# -----------------------------------------------
# Delete EKS cluster
# -----------------------------------------------
echo "Deleting EKS cluster '${CLUSTER_NAME}' (this takes ~10 minutes)..."
if eksctl get cluster --name "$CLUSTER_NAME" --region "$REGION" --profile "$PROFILE" > /dev/null 2>&1; then
    eksctl delete cluster \
        --name "$CLUSTER_NAME" \
        --region "$REGION" \
        --profile "$PROFILE" \
        --wait
    echo "  EKS cluster deleted."
else
    echo "  Cluster '${CLUSTER_NAME}' not found."
fi
echo ""

# -----------------------------------------------
# Summary
# -----------------------------------------------
echo "============================================"
echo "Teardown Complete!"
echo ""
echo "All AWS resources have been deleted."
echo "Verify in the AWS Console that no orphaned resources remain:"
echo "  - EC2 instances"
echo "  - Load balancers"
echo "  - CloudFormation stacks"
echo "============================================"
