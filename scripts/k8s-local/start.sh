#!/bin/bash
# Start script for local Kubernetes deployment (Docker Desktop)
# Deploys via Helm, waits for health, and sets up port-forwarding

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$( cd "$SCRIPT_DIR/../.." && pwd )"

NAMESPACE="hazelcast-demo"
RELEASE_NAME="demo"
CHART_DIR="$PROJECT_ROOT/k8s/hazelcast-microservices"
PID_FILE="/tmp/hazelcast-k8s-pf.pids"

# Parse flags
MONITORING=true
for arg in "$@"; do
    case "$arg" in
        --no-monitoring) MONITORING=false ;;
    esac
done

echo "============================================"
echo "Hazelcast Microservices - K8s Local Start"
echo "============================================"
echo ""

# -----------------------------------------------
# Preflight checks
# -----------------------------------------------
echo "Preflight checks..."

# kubectl
if ! command -v kubectl &> /dev/null; then
    echo "ERROR: kubectl not found. Install it or enable Docker Desktop Kubernetes."
    exit 1
fi

# helm
if ! command -v helm &> /dev/null; then
    echo "ERROR: helm not found. Install Helm: https://helm.sh/docs/intro/install/"
    exit 1
fi

# Cluster reachable
if ! kubectl cluster-info &> /dev/null; then
    echo "ERROR: Cannot reach Kubernetes cluster."
    echo "Ensure Docker Desktop Kubernetes is enabled (Settings > Kubernetes > Enable)."
    exit 1
fi

# Docker images exist
missing_images=()
for svc in account-service inventory-service order-service payment-service api-gateway mcp-server; do
    if ! docker images "hazelcast-microservices/${svc}" --format '{{.Repository}}' 2>/dev/null | grep -q .; then
        missing_images+=("$svc")
    fi
done

if [ ${#missing_images[@]} -gt 0 ]; then
    echo ""
    echo "Missing Docker images: ${missing_images[*]}"
    read -p "Run build.sh first? [Y/n] " answer
    if [ "$answer" != "n" ] && [ "$answer" != "N" ]; then
        "$SCRIPT_DIR/build.sh"
        echo ""
    else
        echo "ERROR: Cannot deploy without images."
        exit 1
    fi
fi

echo "  All preflight checks passed."
echo ""

# -----------------------------------------------
# Create namespace
# -----------------------------------------------
if ! kubectl get namespace "$NAMESPACE" &> /dev/null; then
    echo "Creating namespace '${NAMESPACE}'..."
    kubectl create namespace "$NAMESPACE"
else
    echo "Namespace '${NAMESPACE}' already exists."
fi
echo ""

# -----------------------------------------------
# Helm dependency update (if needed)
# -----------------------------------------------
if [ -f "$CHART_DIR/Chart.lock" ]; then
    lock_time=$(stat -f %m "$CHART_DIR/Chart.lock" 2>/dev/null || stat -c %Y "$CHART_DIR/Chart.lock" 2>/dev/null)
    chart_time=$(stat -f %m "$CHART_DIR/Chart.yaml" 2>/dev/null || stat -c %Y "$CHART_DIR/Chart.yaml" 2>/dev/null)
    if [ "$chart_time" -gt "$lock_time" ]; then
        echo "Chart.yaml is newer than Chart.lock — updating dependencies..."
        helm dependency update "$CHART_DIR"
        echo ""
    fi
fi

# -----------------------------------------------
# Check for existing release
# -----------------------------------------------
if helm status "$RELEASE_NAME" -n "$NAMESPACE" &> /dev/null; then
    echo "Helm release '${RELEASE_NAME}' already exists. Upgrading..."
    HELM_CMD="upgrade"
else
    HELM_CMD="install"
fi

# -----------------------------------------------
# Helm install/upgrade with local overrides
# -----------------------------------------------
echo "Running helm ${HELM_CMD}..."
helm $HELM_CMD "$RELEASE_NAME" "$CHART_DIR" \
    -n "$NAMESPACE" \
    --set account-service.image.pullPolicy=Never \
    --set inventory-service.image.pullPolicy=Never \
    --set order-service.image.pullPolicy=Never \
    --set payment-service.image.pullPolicy=Never \
    --set api-gateway.image.pullPolicy=Never \
    --set api-gateway.ingress.enabled=false \
    --set mcp-server.image.pullPolicy=Never \
    --set hazelcast-cluster.image.pullPolicy=IfNotPresent \
    --set monitoring.enabled=$MONITORING

echo ""

# -----------------------------------------------
# Wait for Hazelcast StatefulSet
# -----------------------------------------------
echo "Waiting for Hazelcast cluster pods to be ready (timeout 120s)..."
kubectl wait --for=condition=ready pod \
    -l app.kubernetes.io/name=hazelcast-cluster \
    -n "$NAMESPACE" \
    --timeout=120s

echo "  Hazelcast cluster is ready!"
echo ""

# -----------------------------------------------
# Wait for service deployments
# -----------------------------------------------
for svc in account-service inventory-service order-service payment-service api-gateway; do
    echo "Waiting for ${svc} rollout (timeout 180s)..."
    kubectl rollout status deployment "${RELEASE_NAME}-${svc}" \
        -n "$NAMESPACE" \
        --timeout=180s
    echo "  ${svc} is ready!"
done

if [ "$MONITORING" = "true" ]; then
    for component in prometheus grafana jaeger; do
        echo "Waiting for ${component} rollout (timeout 60s)..."
        kubectl rollout status deployment "${RELEASE_NAME}-monitoring-${component}" \
            -n "$NAMESPACE" \
            --timeout=60s
        echo "  ${component} is ready!"
    done
fi
echo ""

# -----------------------------------------------
# Port-forwarding
# -----------------------------------------------
echo "Setting up port-forwarding..."

# Kill any existing port-forwards from a previous run
if [ -f "$PID_FILE" ]; then
    while read -r pid; do
        kill "$pid" 2>/dev/null || true
    done < "$PID_FILE"
    rm -f "$PID_FILE"
fi

# Start port-forwards in background
for entry in \
    "hazelcast-cluster:5701" \
    "api-gateway:8080" \
    "account-service:8081" \
    "inventory-service:8082" \
    "order-service:8083" \
    "payment-service:8084"; do

    svc="${entry%%:*}"
    port="${entry##*:}"
    svc_name="${RELEASE_NAME}-${svc}"
    kubectl port-forward "svc/${svc_name}" "${port}:${port}" -n "$NAMESPACE" &>/dev/null &
    echo "$!" >> "$PID_FILE"
    echo "  localhost:${port} → ${svc_name}"
done

if [ "$MONITORING" = "true" ]; then
    # Grafana (port 3000)
    kubectl port-forward "svc/${RELEASE_NAME}-monitoring-grafana" 3000:3000 -n "$NAMESPACE" &>/dev/null &
    echo "$!" >> "$PID_FILE"
    echo "  localhost:3000 → ${RELEASE_NAME}-monitoring-grafana"

    # Prometheus (port 9090)
    kubectl port-forward "svc/${RELEASE_NAME}-monitoring-prometheus" 9090:9090 -n "$NAMESPACE" &>/dev/null &
    echo "$!" >> "$PID_FILE"
    echo "  localhost:9090 → ${RELEASE_NAME}-monitoring-prometheus"

    # Jaeger UI (port 16686)
    kubectl port-forward "svc/jaeger" 16686:16686 -n "$NAMESPACE" &>/dev/null &
    echo "$!" >> "$PID_FILE"
    echo "  localhost:16686 → jaeger"
fi

# Give port-forwards a moment to establish
sleep 3
echo ""

# -----------------------------------------------
# Health checks on forwarded ports
# -----------------------------------------------
echo "Verifying service health via port-forwards..."
timeout=60
for entry in "Account Service:8081" "Inventory Service:8082" "Order Service:8083" "Payment Service:8084" "API Gateway:8080"; do
    name="${entry%%:*}"
    port="${entry##*:}"

    counter=0
    until curl -sf "http://localhost:${port}/actuator/health" > /dev/null 2>&1; do
        sleep 2
        counter=$((counter + 2))
        if [ $counter -ge $timeout ]; then
            echo "  WARNING: ${name} health check timed out on localhost:${port}"
            break
        fi
    done

    if [ $counter -lt $timeout ]; then
        echo "  ${name} (localhost:${port})  UP"
    fi
done
echo ""

# -----------------------------------------------
# Summary
# -----------------------------------------------
echo "============================================"
echo "All services are running!"
echo ""
echo "Service Endpoints:"
echo "  Account Service:   http://localhost:8081/api/customers"
echo "  Inventory Service: http://localhost:8082/api/products"
echo "  Order Service:     http://localhost:8083/api/orders"
echo "  Payment Service:   http://localhost:8084/api/payments"
echo "  API Gateway:       http://localhost:8080"
echo ""
if [ "$MONITORING" = "true" ]; then
echo "Monitoring:"
echo "  Grafana:           http://localhost:3000  (admin/admin)"
echo "  Prometheus:        http://localhost:9090"
echo "  Jaeger UI:         http://localhost:16686"
echo ""
fi
echo "Hazelcast Cluster:"
echo "  Health:            http://localhost:5701/hazelcast/health"
echo ""
echo "Kubernetes:"
echo "  Namespace:         ${NAMESPACE}"
echo "  Release:           ${RELEASE_NAME}"
echo "  Pods:              kubectl get pods -n ${NAMESPACE}"
echo "  Logs:              kubectl logs -f deploy/${RELEASE_NAME}-account-service -n ${NAMESPACE}"
echo ""
echo "Port-forwards are running in the background (PIDs in ${PID_FILE})."
echo ""
echo "Next steps:"
echo "  Load sample data:  ./scripts/load-sample-data.sh"
echo "  Run demos:         ./scripts/demo-scenarios.sh"
echo "  Check status:      ./scripts/k8s-local/status.sh"
echo "  Stop:              ./scripts/k8s-local/stop.sh"
echo "============================================"
