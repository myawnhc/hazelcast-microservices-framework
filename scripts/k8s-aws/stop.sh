#!/bin/bash
# =============================================================================
# AWS EKS Stop Script
# Uninstalls the Helm release and cleans up port-forwards
# =============================================================================
#
# Usage: ./scripts/k8s-aws/stop.sh [--clean]
#
# Options:
#   --clean   Also delete the namespace (but NOT the EKS cluster)
#
# To delete the EKS cluster entirely, use: ./scripts/k8s-aws/teardown-cluster.sh

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
NAMESPACE="hazelcast-demo"
RELEASE_NAME="demo"
PID_FILE="$SCRIPT_DIR/port-forwards.pids"

echo "============================================"
echo "Hazelcast Microservices - EKS Stop"
echo "============================================"
echo ""

# Step 1: Kill port-forward processes
if [ -f "$PID_FILE" ]; then
    echo "Stopping port-forward processes..."
    while read -r pid; do
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null && echo "  Stopped PID $pid"
        fi
    done < "$PID_FILE"
    rm -f "$PID_FILE"
    echo ""
else
    echo "No port-forward PID file found."
    echo ""
fi

# Step 2: Uninstall Helm release
if helm status "$RELEASE_NAME" -n "$NAMESPACE" > /dev/null 2>&1; then
    echo "Uninstalling Helm release '${RELEASE_NAME}'..."
    helm uninstall "$RELEASE_NAME" -n "$NAMESPACE"
    echo "Helm release uninstalled."
else
    echo "Helm release '${RELEASE_NAME}' not found in namespace '${NAMESPACE}'."
fi

echo ""

# Step 3: Optionally remove namespace
if [ "$1" = "--clean" ] || [ "$1" = "-c" ]; then
    echo "Removing namespace '${NAMESPACE}'..."
    kubectl delete namespace "$NAMESPACE" --ignore-not-found
    echo "Namespace removed."
else
    echo "Note: Use --clean to also remove the namespace."
fi

echo ""
echo "Services stopped."
echo ""
echo "The EKS cluster is still running (and incurring costs)."
echo "To delete the cluster: ./scripts/k8s-aws/teardown-cluster.sh"
echo "============================================"
