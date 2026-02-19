#!/bin/bash
# =============================================================================
# AWS EKS Deploy Script
# Deploys the Hazelcast Microservices stack to EKS via Helm
# =============================================================================
#
# Usage: ./scripts/k8s-aws/start.sh [--tier small|medium|large] [--region REGION] [--profile PROFILE]
#
# Prerequisites:
#   - EKS cluster created (./scripts/k8s-aws/setup-cluster.sh)
#   - Images pushed to ECR (./scripts/k8s-aws/build.sh)

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
RELEASE_NAME="demo"
CHART_DIR="$PROJECT_ROOT/k8s/hazelcast-microservices"
ECR_PREFIX="${ECR_REPOSITORY_PREFIX:-hazelcast-microservices}"
PID_FILE="$SCRIPT_DIR/port-forwards.pids"

while [ $# -gt 0 ]; do
    case "$1" in
        --tier)     shift; TIER="$1" ;;
        --region)   shift; REGION="$1" ;;
        --profile)  shift; PROFILE="$1" ;;
        --help)
            echo "Usage: $0 [--tier small|medium|large] [--region REGION] [--profile PROFILE]"
            exit 0
            ;;
    esac
    shift
done

# Validate tier
case "$TIER" in
    small|medium|large) ;;
    *) echo "ERROR: Invalid tier '${TIER}'. Must be small, medium, or large."; exit 1 ;;
esac

VALUES_FILE="${CHART_DIR}/values-aws-${TIER}.yaml"
if [ ! -f "$VALUES_FILE" ]; then
    echo "ERROR: Values file not found: ${VALUES_FILE}"
    exit 1
fi

AWS_ACCOUNT_ID="${AWS_ACCOUNT_ID:-$(aws sts get-caller-identity --profile "$PROFILE" --query 'Account' --output text)}"
ECR_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

echo "============================================"
echo "Hazelcast Microservices - EKS Deploy"
echo "============================================"
echo ""
echo "  Tier:       ${TIER}"
echo "  Region:     ${REGION}"
echo "  Namespace:  ${NAMESPACE}"
echo "  ECR URI:    ${ECR_URI}"
echo ""

# -----------------------------------------------
# Preflight checks
# -----------------------------------------------
echo "Preflight checks..."

# Cluster reachable
if ! kubectl cluster-info > /dev/null 2>&1; then
    echo "ERROR: Cannot reach Kubernetes cluster."
    echo "  Run: aws eks update-kubeconfig --name ${CLUSTER_NAME} --region ${REGION}"
    exit 1
fi

# Namespace exists
if ! kubectl get namespace "$NAMESPACE" > /dev/null 2>&1; then
    echo "Creating namespace '${NAMESPACE}'..."
    kubectl create namespace "$NAMESPACE"
fi

echo "  All preflight checks passed."
echo ""

# -----------------------------------------------
# Helm dependency update (if needed)
# -----------------------------------------------
if [ -f "$CHART_DIR/Chart.lock" ]; then
    lock_time=$(stat -f %m "$CHART_DIR/Chart.lock" 2>/dev/null || stat -c %Y "$CHART_DIR/Chart.lock" 2>/dev/null)
    chart_time=$(stat -f %m "$CHART_DIR/Chart.yaml" 2>/dev/null || stat -c %Y "$CHART_DIR/Chart.yaml" 2>/dev/null)
    if [ "$chart_time" -gt "$lock_time" ]; then
        echo "Chart.yaml is newer than Chart.lock -- updating dependencies..."
        helm dependency update "$CHART_DIR"
        echo ""
    fi
fi

# -----------------------------------------------
# Check for existing release
# -----------------------------------------------
if helm status "$RELEASE_NAME" -n "$NAMESPACE" > /dev/null 2>&1; then
    echo "Helm release '${RELEASE_NAME}' already exists. Upgrading..."
    HELM_CMD="upgrade"
else
    HELM_CMD="install"
fi

# -----------------------------------------------
# Helm install/upgrade with tier-specific values
# -----------------------------------------------
echo "Running helm ${HELM_CMD} with ${TIER} tier values..."
helm $HELM_CMD "$RELEASE_NAME" "$CHART_DIR" \
    -n "$NAMESPACE" \
    -f "$VALUES_FILE" \
    --set account-service.image.repository="${ECR_URI}/${ECR_PREFIX}/account-service" \
    --set inventory-service.image.repository="${ECR_URI}/${ECR_PREFIX}/inventory-service" \
    --set order-service.image.repository="${ECR_URI}/${ECR_PREFIX}/order-service" \
    --set payment-service.image.repository="${ECR_URI}/${ECR_PREFIX}/payment-service" \
    --set api-gateway.image.repository="${ECR_URI}/${ECR_PREFIX}/api-gateway" \
    --set mcp-server.image.repository="${ECR_URI}/${ECR_PREFIX}/mcp-server"

echo ""

# -----------------------------------------------
# Wait for Hazelcast StatefulSet
# -----------------------------------------------
echo "Waiting for Hazelcast cluster pods to be ready (timeout 300s)..."
kubectl wait --for=condition=ready pod \
    -l app.kubernetes.io/name=hazelcast-cluster \
    -n "$NAMESPACE" \
    --timeout=300s

echo "  Hazelcast cluster is ready!"
echo ""

# -----------------------------------------------
# Wait for service deployments
# -----------------------------------------------
for svc in account-service inventory-service order-service payment-service api-gateway; do
    echo "Waiting for ${svc} rollout (timeout 300s)..."
    kubectl rollout status deployment "${RELEASE_NAME}-${svc}" \
        -n "$NAMESPACE" \
        --timeout=300s
    echo "  ${svc} is ready!"
done
echo ""

# -----------------------------------------------
# Detect ALB URL or fall back to port-forwarding
# -----------------------------------------------
echo "Checking for ALB Ingress..."
ALB_URL=""

# Wait up to 60s for ALB to provision
counter=0
while [ $counter -lt 60 ]; do
    ALB_URL=$(kubectl get ingress -n "$NAMESPACE" -o jsonpath='{.items[0].status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "")
    if [ -n "$ALB_URL" ]; then
        break
    fi
    sleep 5
    counter=$((counter + 5))
    echo "  Waiting for ALB provisioning... (${counter}s)"
done

if [ -n "$ALB_URL" ]; then
    echo "  ALB URL: http://${ALB_URL}"
    echo ""

    # Health check against ALB
    echo "Waiting for ALB health check..."
    counter=0
    until curl -sf "http://${ALB_URL}/actuator/health" > /dev/null 2>&1; do
        sleep 5
        counter=$((counter + 5))
        if [ $counter -ge 120 ]; then
            echo "  WARNING: ALB health check timed out. ALB may still be provisioning."
            break
        fi
        echo "  Waiting... (${counter}s)"
    done
    if [ $counter -lt 120 ]; then
        echo "  ALB is healthy!"
    fi
else
    echo "  No ALB detected. Setting up port-forwarding..."

    # Kill any existing port-forwards
    if [ -f "$PID_FILE" ]; then
        while read -r pid; do
            kill "$pid" 2>/dev/null || true
        done < "$PID_FILE"
        rm -f "$PID_FILE"
    fi

    for entry in \
        "api-gateway:8080" \
        "account-service:8081" \
        "inventory-service:8082" \
        "order-service:8083" \
        "payment-service:8084"; do

        svc="${entry%%:*}"
        port="${entry##*:}"
        svc_name="${RELEASE_NAME}-${svc}"
        kubectl port-forward "svc/${svc_name}" "${port}:${port}" -n "$NAMESPACE" > /dev/null 2>&1 &
        echo "$!" >> "$PID_FILE"
        echo "  localhost:${port} -> ${svc_name}"
    done

    sleep 3
fi
echo ""

# -----------------------------------------------
# Summary
# -----------------------------------------------
echo "============================================"
echo "EKS Deployment Complete!"
echo ""
echo "  Tier:       ${TIER}"
echo "  Namespace:  ${NAMESPACE}"
echo "  Release:    ${RELEASE_NAME}"
echo ""

if [ -n "$ALB_URL" ]; then
    echo "Access via ALB:"
    echo "  API Gateway: http://${ALB_URL}"
    echo ""
    echo "API Examples:"
    echo "  curl http://${ALB_URL}/api/customers"
    echo "  curl http://${ALB_URL}/api/products"
    echo "  curl http://${ALB_URL}/api/orders"
else
    echo "Access via port-forwarding:"
    echo "  API Gateway:       http://localhost:8080"
    echo "  Account Service:   http://localhost:8081"
    echo "  Inventory Service: http://localhost:8082"
    echo "  Order Service:     http://localhost:8083"
    echo "  Payment Service:   http://localhost:8084"
fi

echo ""
echo "Next steps:"
echo "  Load sample data:  ./scripts/load-sample-data.sh"
echo "  Check status:      ./scripts/k8s-aws/status.sh --region ${REGION}"
echo "  Run perf test:     ./scripts/perf/run-perf-test.sh"
echo "  Stop:              ./scripts/k8s-aws/stop.sh"
echo "============================================"
