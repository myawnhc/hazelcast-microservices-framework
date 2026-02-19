#!/bin/bash
# =============================================================================
# AWS EKS Build & Push Script
# Builds Maven project, creates Docker images, and pushes to ECR
# =============================================================================
#
# Usage: ./scripts/k8s-aws/build.sh [--region REGION] [--profile PROFILE]
#
# Images are tagged with both 'latest' and the git short hash for A-B pinning.
# Forces --platform linux/amd64 for cross-architecture compatibility (M-series Mac -> x86 EKS).

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/../.." && pwd )"

# -----------------------------------------------
# Defaults and argument parsing
# -----------------------------------------------
REGION="${AWS_REGION:-us-east-1}"
PROFILE="${AWS_PROFILE:-default}"
ECR_PREFIX="${ECR_REPOSITORY_PREFIX:-hazelcast-microservices}"
SERVICES="account-service inventory-service order-service payment-service api-gateway mcp-server"

while [ $# -gt 0 ]; do
    case "$1" in
        --region)   shift; REGION="$1" ;;
        --profile)  shift; PROFILE="$1" ;;
        --help)
            echo "Usage: $0 [--region REGION] [--profile PROFILE]"
            exit 0
            ;;
    esac
    shift
done

AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-$(aws sts get-caller-identity --profile "$PROFILE" --query 'Account' --output text)}"
ECR_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"
GIT_SHA=$(cd "$PROJECT_ROOT" && git rev-parse --short HEAD 2>/dev/null || echo "unknown")

echo "============================================"
echo "Hazelcast Microservices - EKS Build & Push"
echo "============================================"
echo ""
echo "  ECR URI:  ${ECR_URI}"
echo "  Git SHA:  ${GIT_SHA}"
echo "  Region:   ${REGION}"
echo ""

# -----------------------------------------------
# Step 1: Maven build
# -----------------------------------------------
echo "Step 1: Building Maven project..."
cd "$PROJECT_ROOT"
./mvnw clean package -DskipTests -q

if [ $? -ne 0 ]; then
    echo "ERROR: Maven build failed!"
    exit 1
fi
echo "  Maven build completed."
echo ""

# -----------------------------------------------
# Step 2: ECR login
# -----------------------------------------------
echo "Step 2: Logging into ECR..."
aws ecr get-login-password --region "$REGION" --profile "$PROFILE" | \
    docker login --username AWS --password-stdin "$ECR_URI"
echo ""

# -----------------------------------------------
# Step 3: Build and push Docker images
# -----------------------------------------------
echo "Step 3: Building and pushing Docker images..."
echo ""

for svc in $SERVICES; do
    repo="${ECR_URI}/${ECR_PREFIX}/${svc}"
    echo "  Building ${svc}..."
    docker build \
        --platform linux/amd64 \
        -t "${repo}:latest" \
        -t "${repo}:${GIT_SHA}" \
        "$PROJECT_ROOT/${svc}/" \
        -q

    echo "  Pushing ${repo}:latest ..."
    docker push "${repo}:latest" -q
    echo "  Pushing ${repo}:${GIT_SHA} ..."
    docker push "${repo}:${GIT_SHA}" -q
    echo "  ${svc} done."
    echo ""
done

# -----------------------------------------------
# Summary
# -----------------------------------------------
echo "============================================"
echo "Build & Push Complete!"
echo ""
echo "Images pushed to ECR:"
for svc in $SERVICES; do
    echo "  ${ECR_URI}/${ECR_PREFIX}/${svc}:latest"
    echo "  ${ECR_URI}/${ECR_PREFIX}/${svc}:${GIT_SHA}"
done
echo ""
echo "Next: ./scripts/k8s-aws/start.sh --tier <TIER> --region ${REGION}"
echo "============================================"
