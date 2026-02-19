#!/bin/bash
#
# k8s-perf-test.sh - Kubernetes Performance Test Orchestrator
#
# Runs a TPS sweep against a K8s-deployed Hazelcast Microservices stack,
# capturing k6 results and kubectl resource metrics at each level.
#
# Compatible with macOS bash 3.2 (no bash 4+ features).
#
# Usage:
#   ./scripts/perf/k8s-perf-test.sh --target local --tps-levels "10,25,50"
#   ./scripts/perf/k8s-perf-test.sh --target aws-small --tps-levels "10,25,50,100"
#   ./scripts/perf/k8s-perf-test.sh --target aws-medium --duration 3m
#
# Options:
#   --target TARGET       Deployment target (required: local|aws-small|aws-medium|aws-large)
#   --scenario SCENARIO   k6 scenario (default: constant)
#   --tps-levels LEVELS   Comma-separated TPS levels (default: "10,25,50")
#   --duration DURATION   Duration per TPS level, k6 format (default: 3m)
#   --namespace NS        Kubernetes namespace (default: hazelcast-demo)
#   --skip-data           Skip sample data generation
#   --skip-health-wait    Skip health check waiting
#   --help                Show this help message
#
# Prerequisites:
#   - kubectl configured with target cluster
#   - k6 installed (brew install k6)
#   - jq installed (brew install jq)
#   - K8s deployment running (via k8s-local/start.sh or k8s-aws/start.sh)
#   - Port-forwards active to localhost:808x
#

set -e

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RESULTS_BASE="$SCRIPT_DIR/k8s-results"

TARGET=""
SCENARIO="constant"
TPS_LEVELS="10,25,50"
DURATION="3m"
NAMESPACE="hazelcast-demo"
SKIP_DATA=false
SKIP_HEALTH_WAIT=false

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# Health check settings
HEALTH_TIMEOUT=300
HEALTH_INTERVAL=5

# Service health endpoints
SERVICE_PORTS="account-service:8081 inventory-service:8082 order-service:8083 payment-service:8084"

# Port-forward PID files by target
PF_PID_FILE_LOCAL="/tmp/hazelcast-k8s-pf.pids"
PF_PID_FILE_AWS="$PROJECT_ROOT/scripts/k8s-aws/port-forwards.pids"

# Stabilization between TPS levels (seconds)
STABILIZATION_PAUSE=15

# ---------------------------------------------------------------------------
# Functions
# ---------------------------------------------------------------------------

print_usage() {
    echo "Usage: $0 --target <local|aws-small|aws-medium|aws-large> [options]"
    echo ""
    echo "Options:"
    echo "  --target TARGET       Deployment target (required)"
    echo "                        Values: local, aws-small, aws-medium, aws-large"
    echo "  --scenario SCENARIO   k6 scenario (default: constant)"
    echo "  --tps-levels LEVELS   Comma-separated TPS levels (default: \"10,25,50\")"
    echo "  --duration DURATION   Duration per level, k6 format (default: 3m)"
    echo "  --namespace NS        Kubernetes namespace (default: hazelcast-demo)"
    echo "  --skip-data           Skip sample data generation"
    echo "  --skip-health-wait    Skip health check waiting"
    echo "  --help                Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 --target local --tps-levels \"10,25,50\""
    echo "  $0 --target aws-small --tps-levels \"10,25,50,100\""
    echo "  $0 --target aws-medium --duration 5m"
    echo "  $0 --target local --scenario mixed --tps-levels \"10,25\" --duration 1m"
}

die() {
    echo -e "${RED}Error: $1${NC}" >&2
    exit 1
}

log() {
    echo -e "${CYAN}[$(date +%H:%M:%S)]${NC} $1"
}

log_step() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

# Wait for all services to be healthy via port-forwarded localhost ports
wait_for_services() {
    if [ "$SKIP_HEALTH_WAIT" = true ]; then
        log "Skipping health check wait (--skip-health-wait)"
        return 0
    fi

    log "Waiting for services to become healthy (timeout: ${HEALTH_TIMEOUT}s)..."

    local elapsed=0
    local all_healthy=false

    while [ $elapsed -lt $HEALTH_TIMEOUT ]; do
        all_healthy=true

        for svc_info in $SERVICE_PORTS; do
            local svc_name="${svc_info%%:*}"
            local svc_port="${svc_info#*:}"
            local url="http://localhost:${svc_port}/actuator/health"

            local status
            status=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")

            if [ "$status" != "200" ]; then
                all_healthy=false
                break
            fi
        done

        if [ "$all_healthy" = true ]; then
            log "All services healthy!"
            return 0
        fi

        sleep $HEALTH_INTERVAL
        elapsed=$((elapsed + HEALTH_INTERVAL))

        # Progress every 30s
        if [ $((elapsed % 30)) -eq 0 ]; then
            log "  Still waiting... (${elapsed}s / ${HEALTH_TIMEOUT}s)"
        fi
    done

    die "Services did not become healthy within ${HEALTH_TIMEOUT}s"
}

# Find the newest k6 result JSON
find_latest_k6_result() {
    ls -t "$SCRIPT_DIR"/results/k6-results-*.json 2>/dev/null | head -1
}

# Capture kubectl top pods (graceful - metrics-server may not be available)
capture_pod_metrics() {
    local outfile="$1"
    log "Capturing pod resource metrics..."
    {
        echo "--- kubectl top pods ($(date -u +"%Y-%m-%dT%H:%M:%SZ")) ---"
        kubectl top pods -n "$NAMESPACE" 2>/dev/null || echo "(metrics-server not available)"
        echo ""
    } >> "$outfile"
}

# Capture kubectl top nodes (useful for AWS)
capture_node_metrics() {
    local outfile="$1"
    {
        echo "--- kubectl top nodes ($(date -u +"%Y-%m-%dT%H:%M:%SZ")) ---"
        kubectl top nodes 2>/dev/null || echo "(metrics-server not available)"
        echo ""
    } >> "$outfile"
}

# Capture K8s environment info
capture_environment() {
    local outfile="$1"
    log "Capturing K8s environment info..."

    {
        echo "=== K8s Environment ==="
        echo "Timestamp: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
        echo "Target: $TARGET"
        echo "Namespace: $NAMESPACE"
        echo ""

        echo "--- Nodes ---"
        kubectl get nodes -o wide 2>/dev/null || echo "(not available)"
        echo ""

        echo "--- Node Details ---"
        kubectl get nodes -o custom-columns=\
'NAME:.metadata.name,TYPE:.metadata.labels.node\.kubernetes\.io/instance-type,AZ:.metadata.labels.topology\.kubernetes\.io/zone,STATUS:.status.conditions[-1].type' \
            2>/dev/null || echo "(not available)"
        echo ""

        echo "--- Pods ---"
        kubectl get pods -n "$NAMESPACE" -o wide 2>/dev/null || echo "(not available)"
        echo ""

        echo "--- Services ---"
        kubectl get svc -n "$NAMESPACE" 2>/dev/null || echo "(not available)"
        echo ""

        echo "--- HPA ---"
        kubectl get hpa -n "$NAMESPACE" 2>/dev/null || echo "(no HPA configured)"
        echo ""

        echo "--- Helm Values ---"
        helm get values demo -n "$NAMESPACE" 2>/dev/null || echo "(not available)"
        echo ""
    } > "$outfile"
}

# Check port-forwards are running
check_port_forwards() {
    local pf_file=""
    case "$TARGET" in
        local)     pf_file="$PF_PID_FILE_LOCAL" ;;
        aws-*)     pf_file="$PF_PID_FILE_AWS" ;;
    esac

    if [ -z "$pf_file" ] || [ ! -f "$pf_file" ]; then
        echo -e "${YELLOW}Warning: Port-forward PID file not found ($pf_file).${NC}"
        echo -e "${YELLOW}Ensure port-forwards are running (localhost:8081-8084).${NC}"
        return 0
    fi

    local active=0
    local stale=0
    while read -r pid; do
        if kill -0 "$pid" 2>/dev/null; then
            active=$((active + 1))
        else
            stale=$((stale + 1))
        fi
    done < "$pf_file"

    log "Port-forwards: ${active} active, ${stale} stale"

    if [ "$active" -eq 0 ]; then
        echo -e "${YELLOW}Warning: No active port-forwards detected.${NC}"
        echo -e "${YELLOW}Run k8s-local/start.sh or k8s-aws/start.sh to set up port-forwards.${NC}"
    fi
}

# Generate sweep-summary.md from collected results
generate_sweep_summary() {
    local run_dir="$1"
    local summary_file="$run_dir/sweep-summary.md"

    log "Generating sweep summary..."

    {
        echo "# K8s Performance Sweep Summary"
        echo ""
        echo "**Target**: ${TARGET}"
        echo "**Scenario**: ${SCENARIO}"
        echo "**Duration per level**: ${DURATION}"
        echo "**Date**: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
        echo ""

        echo "## Workload Description"
        echo ""
        echo "Each iteration executes **one** randomly-selected operation from a weighted mix:"
        echo ""
        echo "| Weight | Operation | Service | Complexity |"
        echo "|--------|-----------|---------|------------|"
        echo "| 60% | **Create Order** | order-service | Triggers 3-service saga (inventory reserve, payment, order confirm) via Hazelcast ITopic |"
        echo "| 25% | **Reserve Stock** | inventory-service | Single-service event-sourced command |"
        echo "| 15% | **Create Customer** | account-service | Single-service event-sourced command |"
        echo ""
        echo "> **Note:** 10% of orders (6% of all iterations) also poll for full saga completion, measured as \`saga_e2e_duration\`."
        echo "> Each iteration generates 1 HTTP call to the target service. The 60% that create orders also trigger ~3 additional"
        echo "> internal cross-service messages (stock reservation, payment processing, order confirmation) via Hazelcast ITopic —"
        echo "> so actual internal throughput is significantly higher than the headline TPS number."
        echo ""
        echo "## Results by TPS Level"
        echo ""
        echo "| TPS | Iterations | Iter/s | HTTP req/s | p50 (ms) | p95 (ms) | p99 (ms) | Error Rate |"
        echo "|-----|-----------|--------|------------|----------|----------|----------|------------|"

        # Format helper: format number or return N/A
        fmt_num() {
            local val="$1" decimals="${2:-2}"
            if [ -z "$val" ] || [ "$val" = "N/A" ] || [ "$val" = "null" ]; then
                echo "N/A"
            else
                printf "%.${decimals}f" "$val" 2>/dev/null || echo "$val"
            fi
        }

        # Iterate over TPS levels
        local remaining="$TPS_LEVELS"
        while [ -n "$remaining" ]; do
            local tps="${remaining%%,*}"
            if [ "$tps" = "$remaining" ]; then
                remaining=""
            else
                remaining="${remaining#*,}"
            fi

            local result_file="$run_dir/k6-results-${tps}tps.json"
            if [ ! -f "$result_file" ]; then
                echo "| ${tps} | — | — | — | — | — | — | — |"
                continue
            fi

            local iterations iter_rate http_rate p50 p95 p99 fail_rate
            iterations=$(jq -r '.metrics.iterations.values.count // "N/A"' "$result_file" 2>/dev/null)
            iter_rate=$(jq -r '.metrics.iterations.values.rate // "N/A"' "$result_file" 2>/dev/null)
            http_rate=$(jq -r '.metrics.http_reqs.values.rate // "N/A"' "$result_file" 2>/dev/null)
            p50=$(jq -r '.metrics.http_req_duration.values.med // "N/A"' "$result_file" 2>/dev/null)
            p95=$(jq -r '.metrics.http_req_duration.values["p(95)"] // "N/A"' "$result_file" 2>/dev/null)
            p99=$(jq -r '.metrics.http_req_duration.values["p(99)"] // "N/A"' "$result_file" 2>/dev/null)
            fail_rate=$(jq -r '.metrics.http_req_failed.values.rate // "N/A"' "$result_file" 2>/dev/null)

            # Format values
            local fmt_iter fmt_iter_rate fmt_http_rate fmt_p50 fmt_p95 fmt_p99 fmt_fail
            fmt_iter=$(fmt_num "$iterations" 0)
            fmt_iter_rate=$(fmt_num "$iter_rate" 2)
            fmt_http_rate=$(fmt_num "$http_rate" 2)
            fmt_p50=$(fmt_num "$p50" 2)
            fmt_p95=$(fmt_num "$p95" 2)
            fmt_p99=$(fmt_num "$p99" 2)

            if [ "$fail_rate" != "N/A" ] && [ -n "$fail_rate" ]; then
                fmt_fail=$(printf "%.4f" "$fail_rate" 2>/dev/null || echo "$fail_rate")
                # Convert to percentage
                local fail_pct
                fail_pct=$(echo "$fail_rate * 100" | bc -l 2>/dev/null)
                if [ -n "$fail_pct" ]; then
                    fmt_fail="$(printf "%.2f" "$fail_pct")%"
                fi
            else
                fmt_fail="N/A"
            fi

            echo "| ${tps} | ${fmt_iter} | ${fmt_iter_rate} | ${fmt_http_rate} | ${fmt_p50} | ${fmt_p95} | ${fmt_p99} | ${fmt_fail} |"
        done

        echo ""

        # Per-operation latency breakdown
        echo "## Per-Operation Latency Breakdown"
        echo ""
        echo "Latency by operation type at each TPS level (milliseconds)."
        echo ""
        echo "| TPS | Operation | p50 | p95 | avg | max |"
        echo "|-----|-----------|-----|-----|-----|-----|"

        local remaining_ops="$TPS_LEVELS"
        while [ -n "$remaining_ops" ]; do
            local tps_op="${remaining_ops%%,*}"
            if [ "$tps_op" = "$remaining_ops" ]; then
                remaining_ops=""
            else
                remaining_ops="${remaining_ops#*,}"
            fi

            local result_file_op="$run_dir/k6-results-${tps_op}tps.json"
            if [ ! -f "$result_file_op" ]; then
                continue
            fi

            for op_entry in \
                "Create Order:order_create_duration" \
                "Reserve Stock:stock_reserve_duration" \
                "Create Customer:customer_create_duration" \
                "Saga E2E:saga_e2e_duration"; do

                local op_label="${op_entry%%:*}"
                local op_metric="${op_entry##*:}"

                local op_p50 op_p95 op_avg op_max
                op_p50=$(jq -r ".metrics.\"$op_metric\".values.med // \"N/A\"" "$result_file_op" 2>/dev/null)
                op_p95=$(jq -r ".metrics.\"$op_metric\".values[\"p(95)\"] // \"N/A\"" "$result_file_op" 2>/dev/null)
                op_avg=$(jq -r ".metrics.\"$op_metric\".values.avg // \"N/A\"" "$result_file_op" 2>/dev/null)
                op_max=$(jq -r ".metrics.\"$op_metric\".values.max // \"N/A\"" "$result_file_op" 2>/dev/null)

                # Skip if metric not present (e.g., saga may not fire at very low TPS)
                if [ "$op_p50" = "N/A" ] && [ "$op_avg" = "N/A" ]; then
                    continue
                fi

                echo "| ${tps_op} | ${op_label} | $(fmt_num "$op_p50") | $(fmt_num "$op_p95") | $(fmt_num "$op_avg") | $(fmt_num "$op_max" 0) |"
            done
        done

        echo ""

        # Resource usage section
        echo "## Resource Usage"
        echo ""

        local remaining2="$TPS_LEVELS"
        while [ -n "$remaining2" ]; do
            local tps2="${remaining2%%,*}"
            if [ "$tps2" = "$remaining2" ]; then
                remaining2=""
            else
                remaining2="${remaining2#*,}"
            fi

            local metrics_file="$run_dir/k8s-metrics-${tps2}tps.txt"
            if [ -f "$metrics_file" ]; then
                echo "### ${tps2} TPS"
                echo ""
                echo '```'
                cat "$metrics_file"
                echo '```'
                echo ""
            fi
        done

        # Final metrics
        if [ -f "$run_dir/k8s-final-metrics.txt" ]; then
            echo "## Final Metrics Snapshot"
            echo ""
            echo '```'
            cat "$run_dir/k8s-final-metrics.txt"
            echo '```'
            echo ""
        fi

        echo "---"
        echo "*Generated by k8s-perf-test.sh*"
    } > "$summary_file"

    log "Sweep summary written: $(basename "$summary_file")"
}

# Write manifest.json
write_manifest() {
    local run_dir="$1"
    local manifest_file="$run_dir/manifest.json"

    local git_sha git_branch
    git_sha=$(cd "$PROJECT_ROOT" && git rev-parse --short HEAD 2>/dev/null || echo "unknown")
    git_branch=$(cd "$PROJECT_ROOT" && git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")

    # Build TPS levels as JSON array (bash 3.2 compatible)
    local tps_json="["
    local first=true
    local remaining="$TPS_LEVELS"
    while [ -n "$remaining" ]; do
        local tps="${remaining%%,*}"
        if [ "$tps" = "$remaining" ]; then
            remaining=""
        else
            remaining="${remaining#*,}"
        fi
        if [ "$first" = true ]; then
            tps_json="${tps_json}${tps}"
            first=false
        else
            tps_json="${tps_json}, ${tps}"
        fi
    done
    tps_json="${tps_json}]"

    # Build result files JSON array (bash 3.2 compatible)
    local files_json="["
    first=true
    remaining="$TPS_LEVELS"
    while [ -n "$remaining" ]; do
        local tps="${remaining%%,*}"
        if [ "$tps" = "$remaining" ]; then
            remaining=""
        else
            remaining="${remaining#*,}"
        fi
        if [ "$first" = true ]; then
            files_json="${files_json}\"k6-results-${tps}tps.json\""
            first=false
        else
            files_json="${files_json}, \"k6-results-${tps}tps.json\""
        fi
    done
    files_json="${files_json}]"

    # Detect node info for AWS targets
    local node_count="0"
    local instance_types="unknown"
    node_count=$(kubectl get nodes --no-headers 2>/dev/null | wc -l | tr -d ' ')
    instance_types=$(kubectl get nodes -o jsonpath='{.items[*].metadata.labels.node\.kubernetes\.io/instance-type}' 2>/dev/null || echo "unknown")

    cat > "$manifest_file" << MANIFEST_EOF
{
  "version": 1,
  "type": "k8s-sweep",
  "target": "${TARGET}",
  "namespace": "${NAMESPACE}",
  "scenario": "${SCENARIO}",
  "tpsLevels": ${tps_json},
  "duration": "${DURATION}",
  "resultFiles": ${files_json},
  "nodeCount": ${node_count},
  "instanceTypes": "${instance_types}",
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "gitSha": "${git_sha}",
  "gitBranch": "${git_branch}"
}
MANIFEST_EOF

    log "Manifest written: $(basename "$manifest_file")"
}

# ---------------------------------------------------------------------------
# Parse arguments (bash 3.2 compatible)
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --target)
            TARGET="$2"
            shift 2
            ;;
        --scenario)
            SCENARIO="$2"
            shift 2
            ;;
        --tps-levels)
            TPS_LEVELS="$2"
            shift 2
            ;;
        --duration)
            DURATION="$2"
            shift 2
            ;;
        --namespace)
            NAMESPACE="$2"
            shift 2
            ;;
        --skip-data)
            SKIP_DATA=true
            shift
            ;;
        --skip-health-wait)
            SKIP_HEALTH_WAIT=true
            shift
            ;;
        --help|-h)
            print_usage
            exit 0
            ;;
        -*)
            die "Unknown option: $1"
            ;;
        *)
            die "Unexpected argument: $1"
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Validate inputs
# ---------------------------------------------------------------------------
if [ -z "$TARGET" ]; then
    die "--target is required.\n\n$(print_usage)"
fi

case "$TARGET" in
    local|aws-small|aws-medium|aws-large)
        ;;
    *)
        die "Invalid target: $TARGET. Must be one of: local, aws-small, aws-medium, aws-large"
        ;;
esac

# ---------------------------------------------------------------------------
# Preflight checks
# ---------------------------------------------------------------------------
log_step "Preflight Checks"

for cmd in kubectl k6 jq bc curl; do
    if ! command -v "$cmd" > /dev/null 2>&1; then
        die "$cmd is required but not installed."
    fi
done
log "All required tools present"

# Verify cluster accessible
if ! kubectl cluster-info > /dev/null 2>&1; then
    die "Cannot reach Kubernetes cluster. Check kubectl configuration."
fi
log "Kubernetes cluster reachable"

# Verify namespace exists
if ! kubectl get namespace "$NAMESPACE" > /dev/null 2>&1; then
    die "Namespace '$NAMESPACE' does not exist. Deploy services first."
fi
log "Namespace '$NAMESPACE' exists"

# Check pods are running
RUNNING_PODS=$(kubectl get pods -n "$NAMESPACE" --field-selector=status.phase=Running --no-headers 2>/dev/null | wc -l | tr -d ' ')
if [ "$RUNNING_PODS" -eq 0 ]; then
    die "No running pods in namespace '$NAMESPACE'."
fi
log "Found $RUNNING_PODS running pods"

# Check port-forwards
check_port_forwards

echo ""

# ---------------------------------------------------------------------------
# Setup run directory
# ---------------------------------------------------------------------------
RUN_TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RUN_DIR="$RESULTS_BASE/${TARGET}-${RUN_TIMESTAMP}"
mkdir -p "$RUN_DIR"

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------
echo ""
echo -e "${BOLD}${BLUE}================================================${NC}"
echo -e "${BOLD}${BLUE}    K8s Performance Test Sweep${NC}"
echo -e "${BOLD}${BLUE}================================================${NC}"
echo ""
echo "  Target:      ${TARGET}"
echo "  Namespace:   ${NAMESPACE}"
echo "  Scenario:    ${SCENARIO}"
echo "  TPS levels:  ${TPS_LEVELS}"
echo "  Duration:    ${DURATION} per level"
echo "  Output dir:  ${RUN_DIR}"
echo ""

# ---------------------------------------------------------------------------
# Capture K8s environment
# ---------------------------------------------------------------------------
log_step "Capturing K8s Environment"
capture_environment "$RUN_DIR/k8s-environment.txt"

# ---------------------------------------------------------------------------
# Health checks
# ---------------------------------------------------------------------------
log_step "Service Health Checks"
wait_for_services

# ---------------------------------------------------------------------------
# Load sample data
# ---------------------------------------------------------------------------
if [ "$SKIP_DATA" = false ]; then
    log_step "Loading Sample Data"
    "$SCRIPT_DIR/generate-sample-data.sh" --skip-health 2>&1 || {
        echo -e "${YELLOW}Warning: Sample data generation had errors (continuing anyway)${NC}"
    }
else
    log "Skipping sample data generation (--skip-data)"
fi

# ---------------------------------------------------------------------------
# TPS Sweep
# ---------------------------------------------------------------------------
TOTAL_START=$(date +%s)
TPS_COUNT=0

# Count TPS levels for progress display
remaining_count="$TPS_LEVELS"
TOTAL_LEVELS=0
while [ -n "$remaining_count" ]; do
    current="${remaining_count%%,*}"
    if [ "$current" = "$remaining_count" ]; then
        remaining_count=""
    else
        remaining_count="${remaining_count#*,}"
    fi
    TOTAL_LEVELS=$((TOTAL_LEVELS + 1))
done

# Iterate over TPS levels (bash 3.2 compatible - no arrays)
remaining="$TPS_LEVELS"
while [ -n "$remaining" ]; do
    tps="${remaining%%,*}"
    if [ "$tps" = "$remaining" ]; then
        remaining=""
    else
        remaining="${remaining#*,}"
    fi

    TPS_COUNT=$((TPS_COUNT + 1))

    log_step "TPS Level: ${tps} (${TPS_COUNT}/${TOTAL_LEVELS})"

    # Pre-test metrics
    local_metrics_file="$RUN_DIR/k8s-metrics-${tps}tps.txt"
    echo "=== Pre-test metrics (${tps} TPS) ===" > "$local_metrics_file"
    capture_pod_metrics "$local_metrics_file"

    # Run k6 via run-perf-test.sh
    log "Running k6 at ${tps} TPS for ${DURATION}..."

    "$SCRIPT_DIR/run-perf-test.sh" \
        --scenario "$SCENARIO" \
        --tps "$tps" \
        --duration "$DURATION" 2>&1 || {
        echo -e "${YELLOW}Warning: k6 exited with non-zero status at ${tps} TPS (thresholds may have failed)${NC}"
    }

    # Find and copy result
    result_file=$(find_latest_k6_result)
    if [ -n "$result_file" ]; then
        cp "$result_file" "$RUN_DIR/k6-results-${tps}tps.json"
        log "Result saved: k6-results-${tps}tps.json"
    else
        echo -e "${YELLOW}Warning: No k6 result file found for ${tps} TPS${NC}"
    fi

    # Post-test metrics
    echo "" >> "$local_metrics_file"
    echo "=== Post-test metrics (${tps} TPS) ===" >> "$local_metrics_file"
    capture_pod_metrics "$local_metrics_file"

    # Stabilization pause (unless this is the last level)
    if [ -n "$remaining" ]; then
        log "Stabilization pause (${STABILIZATION_PAUSE}s)..."
        sleep $STABILIZATION_PAUSE
    fi
done

# ---------------------------------------------------------------------------
# Final capture
# ---------------------------------------------------------------------------
log_step "Final Metrics Capture"

final_metrics="$RUN_DIR/k8s-final-metrics.txt"
{
    echo "=== Final pod metrics ==="
} > "$final_metrics"
capture_pod_metrics "$final_metrics"

# Node metrics (useful for AWS)
case "$TARGET" in
    aws-*)
        capture_node_metrics "$final_metrics"
        ;;
    local)
        # Docker Desktop typically doesn't have node-level metrics
        echo "" >> "$final_metrics"
        echo "=== Node metrics ===" >> "$final_metrics"
        kubectl top nodes 2>> "$final_metrics" || echo "(metrics-server not available on Docker Desktop)" >> "$final_metrics"
        ;;
esac

# Pod distribution
{
    echo ""
    echo "=== Pod distribution ==="
    kubectl get pods -n "$NAMESPACE" -o wide --no-headers 2>/dev/null || echo "(not available)"
} >> "$final_metrics"

# ---------------------------------------------------------------------------
# Write manifest and summary
# ---------------------------------------------------------------------------
log_step "Generating Reports"

write_manifest "$RUN_DIR"
generate_sweep_summary "$RUN_DIR"

TOTAL_END=$(date +%s)
TOTAL_ELAPSED=$((TOTAL_END - TOTAL_START))

# ---------------------------------------------------------------------------
# Final summary
# ---------------------------------------------------------------------------
echo ""
echo -e "${GREEN}${BOLD}================================================${NC}"
echo -e "${GREEN}${BOLD}    K8s Performance Sweep Complete${NC}"
echo -e "${GREEN}${BOLD}================================================${NC}"
echo ""
echo "  Target:        ${TARGET}"
echo "  TPS levels:    ${TPS_LEVELS}"
echo "  Total time:    $(( TOTAL_ELAPSED / 60 ))m $(( TOTAL_ELAPSED % 60 ))s"
echo "  Run directory: ${RUN_DIR}"
echo ""
echo "  Files:"
for f in "$RUN_DIR"/*; do
    if [ -f "$f" ]; then
        fsize=$(ls -lh "$f" | awk '{print $5}')
        echo "    $(basename "$f")  ($fsize)"
    fi
done
echo ""
echo "  Next steps:"
echo "    # View sweep summary"
echo "    cat ${RUN_DIR}/sweep-summary.md"
echo ""
echo "    # Compare with Docker Compose baseline"
echo "    ./scripts/perf/k8s-compare.sh \\"
echo "      --docker-baseline scripts/perf/results/<baseline>.json \\"
echo "      --k8s ${RUN_DIR}/ \\"
echo "      --tps 50"
echo ""
echo "    # Compare two K8s sweeps"
echo "    ./scripts/perf/k8s-compare.sh ${RUN_DIR}/ <other-run-dir>/"
echo ""
