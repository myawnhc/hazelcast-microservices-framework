#!/bin/bash
# =============================================================================
# AWS EKS Status Script
# Shows pod status, services, health, node info, and estimated cost
# =============================================================================
#
# Usage: ./scripts/k8s-aws/status.sh [--region REGION]

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
NAMESPACE="hazelcast-demo"
RELEASE_NAME="demo"
PID_FILE="$SCRIPT_DIR/port-forwards.pids"
REGION="${AWS_REGION:-us-east-1}"

while [ $# -gt 0 ]; do
    case "$1" in
        --region) shift; REGION="$1" ;;
    esac
    shift
done

echo "============================================"
echo "Hazelcast Microservices - EKS Status"
echo "============================================"
echo ""

# Check if namespace exists
if ! kubectl get namespace "$NAMESPACE" > /dev/null 2>&1; then
    echo "Namespace '${NAMESPACE}' does not exist."
    echo "Run ./scripts/k8s-aws/setup-cluster.sh to create the cluster."
    echo "============================================"
    exit 0
fi

# -----------------------------------------------
# Node Info
# -----------------------------------------------
echo "--- Nodes ---"
kubectl get nodes -o wide 2>/dev/null || echo "  Cannot reach cluster."
echo ""

# Instance types and AZs
echo "--- Node Details ---"
kubectl get nodes -o custom-columns=\
'NAME:.metadata.name,TYPE:.metadata.labels.node\.kubernetes\.io/instance-type,AZ:.metadata.labels.topology\.kubernetes\.io/zone,STATUS:.status.conditions[-1].type' \
    2>/dev/null || echo "  (unavailable)"
echo ""

# -----------------------------------------------
# Pods
# -----------------------------------------------
echo "--- Pods ---"
kubectl get pods -n "$NAMESPACE" -o wide
echo ""

# -----------------------------------------------
# Services
# -----------------------------------------------
echo "--- Services ---"
kubectl get svc -n "$NAMESPACE"
echo ""

# -----------------------------------------------
# Ingress / ALB
# -----------------------------------------------
echo "--- Ingress ---"
kubectl get ingress -n "$NAMESPACE" 2>/dev/null || echo "  No ingress resources found."
echo ""

# -----------------------------------------------
# Port-Forwards
# -----------------------------------------------
echo "--- Port-Forwards ---"
if [ -f "$PID_FILE" ]; then
    active=0
    stale=0
    while read -r pid; do
        if kill -0 "$pid" 2>/dev/null; then
            cmd=$(ps -p "$pid" -o args= 2>/dev/null || echo "unknown")
            echo "  [RUNNING] PID $pid - $cmd"
            active=$((active + 1))
        else
            stale=$((stale + 1))
        fi
    done < "$PID_FILE"
    echo ""
    echo "  Active: $active, Stale: $stale"
else
    echo "  No port-forward PID file found."
fi
echo ""

# -----------------------------------------------
# Health Checks
# -----------------------------------------------
echo "--- Health Checks ---"

# Try ALB first
ALB_URL=$(kubectl get ingress -n "$NAMESPACE" -o jsonpath='{.items[0].status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "")

if [ -n "$ALB_URL" ]; then
    echo "  Via ALB: http://${ALB_URL}"
    if curl -sf "http://${ALB_URL}/actuator/health" > /dev/null 2>&1; then
        echo "  ALB Gateway: UP"
    else
        echo "  ALB Gateway: DOWN or provisioning"
    fi
else
    # Fall back to port-forward health checks
    for entry in "API Gateway:8080" "Account Service:8081" "Inventory Service:8082" "Order Service:8083" "Payment Service:8084"; do
        name="${entry%%:*}"
        port="${entry##*:}"
        if curl -sf "http://localhost:${port}/actuator/health" > /dev/null 2>&1; then
            echo "  ${name} (${port})  UP"
        else
            echo "  ${name} (${port})  DOWN (port-forward may not be running)"
        fi
    done
fi
echo ""

# -----------------------------------------------
# HPA Status
# -----------------------------------------------
echo "--- HPA ---"
kubectl get hpa -n "$NAMESPACE" 2>/dev/null || echo "  No HPA configured."
echo ""

# -----------------------------------------------
# PDB Status
# -----------------------------------------------
echo "--- Pod Disruption Budgets ---"
kubectl get pdb -n "$NAMESPACE" 2>/dev/null || echo "  No PDBs configured."
echo ""

# -----------------------------------------------
# Resource Usage
# -----------------------------------------------
echo "--- Resource Usage ---"
if kubectl top pods -n "$NAMESPACE" 2>/dev/null; then
    : # output already printed
else
    echo "  (metrics-server not available or pods not yet reporting)"
fi
echo ""

# -----------------------------------------------
# Estimated Hourly Cost
# -----------------------------------------------
echo "--- Estimated Hourly Cost ---"

# Detect instance types from nodes
total_hourly=0
node_count=0
for instance_type in $(kubectl get nodes -o jsonpath='{.items[*].metadata.labels.node\.kubernetes\.io/instance-type}' 2>/dev/null); do
    # Approximate on-demand pricing (us-east-1)
    rate="0.00"
    case "$instance_type" in
        t3.xlarge)    rate="0.1664" ;;
        c7i.2xlarge)  rate="0.3570" ;;
        c7i.4xlarge)  rate="0.7140" ;;
        m5.xlarge)    rate="0.1920" ;;
        m5.2xlarge)   rate="0.3840" ;;
        *)            rate="0.20" ;;  # fallback estimate
    esac
    total_hourly=$(echo "$total_hourly + $rate" | bc 2>/dev/null || echo "?")
    node_count=$((node_count + 1))
done

# Add EKS control plane cost
if [ "$node_count" -gt 0 ]; then
    total_with_eks=$(echo "$total_hourly + 0.10" | bc 2>/dev/null || echo "?")
    echo "  Nodes:       ${node_count}"
    echo "  Compute:     \$${total_hourly}/hr"
    echo "  EKS control: \$0.10/hr"
    echo "  Total:       ~\$${total_with_eks}/hr"
else
    echo "  (unable to determine -- nodes not reachable)"
fi
echo ""

echo "============================================"
