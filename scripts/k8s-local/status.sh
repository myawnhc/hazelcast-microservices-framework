#!/bin/bash
# Status script for local Kubernetes deployment
# Shows pod status, services, port-forwards, and health checks

NAMESPACE="hazelcast-demo"
PID_FILE="/tmp/hazelcast-k8s-pf.pids"

echo "============================================"
echo "Hazelcast Microservices - K8s Local Status"
echo "============================================"
echo ""

# Check if namespace exists
if ! kubectl get namespace "$NAMESPACE" > /dev/null 2>&1; then
    echo "Namespace '${NAMESPACE}' does not exist."
    echo "Run ./scripts/k8s-local/start.sh to deploy."
    echo "============================================"
    exit 0
fi

# Pods
echo "--- Pods ---"
kubectl get pods -n "$NAMESPACE" -o wide
echo ""

# Services
echo "--- Services ---"
kubectl get svc -n "$NAMESPACE"
echo ""

# Port-forwards
echo "--- Port-Forwards ---"
if [ -f "$PID_FILE" ]; then
    active=0
    stale=0
    while read -r pid; do
        if kill -0 "$pid" 2>/dev/null; then
            # Show the command for this PID
            cmd=$(ps -p "$pid" -o args= 2>/dev/null || echo "unknown")
            echo "  [RUNNING] PID $pid - $cmd"
            active=$((active + 1))
        else
            stale=$((stale + 1))
        fi
    done < "$PID_FILE"
    echo ""
    echo "  Active: $active, Stale: $stale"
    if [ $stale -gt 0 ]; then
        echo "  (Stale entries will be cleaned on next start)"
    fi
else
    echo "  No port-forward PID file found."
fi
echo ""

# Health checks on forwarded ports
echo "--- Health Checks ---"
for entry in "API Gateway:8080" "Account Service:8081" "Inventory Service:8082" "Order Service:8083" "Payment Service:8084"; do
    name="${entry%%:*}"
    port="${entry##*:}"
    if curl -sf "http://localhost:${port}/actuator/health" > /dev/null 2>&1; then
        echo "  ${name} (${port})  UP"
    else
        echo "  ${name} (${port})  DOWN (port-forward may not be running)"
    fi
done

# Monitoring health (only if monitoring pods exist)
if kubectl get deployment -n "$NAMESPACE" -l app.kubernetes.io/component=grafana &>/dev/null 2>&1; then
    echo ""
    echo "--- Monitoring ---"
    for entry in "Grafana:3000" "Prometheus:9090" "Jaeger UI:16686"; do
        name="${entry%%:*}"
        port="${entry##*:}"
        if curl -sf "http://localhost:${port}/" > /dev/null 2>&1; then
            echo "  ${name} (${port})  UP"
        else
            echo "  ${name} (${port})  DOWN (port-forward may not be running)"
        fi
    done
fi
echo ""

# Resource usage (if metrics-server is available)
echo "--- Resource Usage ---"
if kubectl top pods -n "$NAMESPACE" 2>/dev/null; then
    : # output already printed
else
    echo "  (metrics-server not available — install it for resource usage)"
fi

echo ""
echo "============================================"
