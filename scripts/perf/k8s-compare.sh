#!/bin/bash
#
# k8s-compare.sh - Cross-Deployment Performance Comparison
#
# Compares performance results across K8s deployments or between Docker Compose
# and K8s results. Reads manifest.json from sweep directories and generates
# a markdown report with per-TPS comparison tables.
#
# Compatible with macOS bash 3.2 (no bash 4+ features).
#
# Usage:
#   # Compare two K8s sweep directories
#   ./scripts/perf/k8s-compare.sh <dir-a> <dir-b>
#
#   # Compare Docker Compose baseline vs K8s sweep
#   ./scripts/perf/k8s-compare.sh \
#     --docker-baseline <k6-result.json> \
#     --k8s <k8s-sweep-dir>/ \
#     --tps 50
#
#   # Output to file
#   ./scripts/perf/k8s-compare.sh <dir-a> <dir-b> --output report.md
#
# Options:
#   --docker-baseline FILE  k6 result JSON from Docker Compose run
#   --k8s DIR               K8s sweep directory (with manifest.json)
#   --tps TPS               TPS level to compare (for Docker vs K8s mode)
#   --output FILE           Write report to FILE (default: stdout)
#   --label-a NAME          Label for first dataset
#   --label-b NAME          Label for second dataset
#   --help                  Show this help message
#

set -e

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

DIR_A=""
DIR_B=""
DOCKER_BASELINE=""
K8S_DIR=""
COMPARE_TPS=""
OUTPUT=""
LABEL_A=""
LABEL_B=""

# Mode: "k8s-vs-k8s" or "docker-vs-k8s"
MODE=""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# ---------------------------------------------------------------------------
# Functions
# ---------------------------------------------------------------------------

print_usage() {
    echo "Usage: $0 <dir-a> <dir-b> [options]"
    echo "       $0 --docker-baseline <file> --k8s <dir> --tps <tps> [options]"
    echo ""
    echo "Modes:"
    echo "  K8s vs K8s:      Compare two K8s sweep directories"
    echo "  Docker vs K8s:   Compare a Docker Compose k6 result with K8s sweep"
    echo ""
    echo "Options:"
    echo "  --docker-baseline FILE  k6 result JSON from Docker Compose"
    echo "  --k8s DIR               K8s sweep directory (with manifest.json)"
    echo "  --tps TPS               TPS level for Docker vs K8s comparison"
    echo "  --output FILE           Write report to FILE (default: stdout)"
    echo "  --label-a NAME          Label for first dataset"
    echo "  --label-b NAME          Label for second dataset"
    echo "  --help                  Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 k8s-results/local-202602*/ k8s-results/aws-small-202602*/"
    echo "  $0 --docker-baseline results/k6-results-*.json --k8s k8s-results/local-*/ --tps 50"
}

die() {
    echo -e "${RED}Error: $1${NC}" >&2
    exit 1
}

# Extract a metric value from k6 JSON
extract_metric() {
    local file="$1"
    local metric="$2"
    local stat="$3"
    jq -r ".metrics[\"$metric\"].values[\"$stat\"] // empty" "$file" 2>/dev/null
}

# Format number to 2 decimal places
fmt() {
    local val="$1"
    if [ -z "$val" ] || [ "$val" = "null" ]; then
        echo "N/A"
        return
    fi
    printf "%.2f" "$val" 2>/dev/null || echo "$val"
}

# Format number to 0 decimal places
fmt_int() {
    local val="$1"
    if [ -z "$val" ] || [ "$val" = "null" ]; then
        echo "N/A"
        return
    fi
    printf "%.0f" "$val" 2>/dev/null || echo "$val"
}

# Compute delta: outputs "delta pct direction"
compute_delta() {
    local a="$1"
    local b="$2"

    if [ -z "$a" ] || [ -z "$b" ] || [ "$a" = "N/A" ] || [ "$b" = "N/A" ]; then
        echo "N/A N/A neutral"
        return
    fi

    local delta
    delta=$(echo "$b - $a" | bc -l 2>/dev/null)
    if [ -z "$delta" ]; then
        echo "N/A N/A neutral"
        return
    fi

    local abs_a
    abs_a=$(echo "$a" | sed 's/-//')
    if [ "$abs_a" = "0" ] || [ "$abs_a" = "0.0" ] || [ "$abs_a" = "0.00" ]; then
        if [ "$delta" = "0" ]; then
            echo "0.00 0.0 neutral"
        else
            echo "$(fmt "$delta") N/A neutral"
        fi
        return
    fi

    local pct
    pct=$(echo "scale=1; ($delta / $abs_a) * 100" | bc -l 2>/dev/null)

    local direction="neutral"
    local is_neg
    is_neg=$(echo "$delta < 0" | bc -l 2>/dev/null)
    local is_pos
    is_pos=$(echo "$delta > 0" | bc -l 2>/dev/null)
    if [ "$is_neg" = "1" ]; then
        direction="better"
    elif [ "$is_pos" = "1" ]; then
        direction="worse"
    fi

    echo "$(fmt "$delta") $pct $direction"
}

# Format delta with arrow
fmt_delta() {
    local delta="$1"
    local pct="$2"
    local direction="$3"
    local higher_is_better="${4:-false}"

    if [ "$delta" = "N/A" ]; then
        echo "N/A"
        return
    fi

    local effective_dir="$direction"
    if [ "$higher_is_better" = "true" ]; then
        if [ "$direction" = "better" ]; then
            effective_dir="worse"
        elif [ "$direction" = "worse" ]; then
            effective_dir="better"
        fi
    fi

    local arrow=""
    if [ "$effective_dir" = "better" ]; then
        arrow="v"
    elif [ "$effective_dir" = "worse" ]; then
        arrow="^"
    else
        arrow="="
    fi

    if [ "$pct" = "N/A" ]; then
        echo "${delta} ${arrow}"
    else
        echo "${delta} (${pct}%) ${arrow}"
    fi
}

# Compare a single pair of k6 result files and output a markdown section
compare_results() {
    local file_a="$1"
    local file_b="$2"
    local label_a="$3"
    local label_b="$4"
    local tps_label="$5"

    if [ ! -f "$file_a" ]; then
        echo "**${label_a}**: Result file not found"
        echo ""
        return
    fi
    if [ ! -f "$file_b" ]; then
        echo "**${label_b}**: Result file not found"
        echo ""
        return
    fi

    # Extract metrics
    local iter_a iter_b iter_rate_a iter_rate_b http_rate_a http_rate_b
    iter_a=$(extract_metric "$file_a" "iterations" "count")
    iter_b=$(extract_metric "$file_b" "iterations" "count")
    iter_rate_a=$(extract_metric "$file_a" "iterations" "rate")
    iter_rate_b=$(extract_metric "$file_b" "iterations" "rate")
    http_rate_a=$(extract_metric "$file_a" "http_reqs" "rate")
    http_rate_b=$(extract_metric "$file_b" "http_reqs" "rate")

    local p50_a p50_b p95_a p95_b p99_a p99_b avg_a avg_b max_a max_b
    p50_a=$(extract_metric "$file_a" "http_req_duration" "med")
    p50_b=$(extract_metric "$file_b" "http_req_duration" "med")
    p95_a=$(extract_metric "$file_a" "http_req_duration" "p(95)")
    p95_b=$(extract_metric "$file_b" "http_req_duration" "p(95)")
    p99_a=$(extract_metric "$file_a" "http_req_duration" "p(99)")
    p99_b=$(extract_metric "$file_b" "http_req_duration" "p(99)")
    avg_a=$(extract_metric "$file_a" "http_req_duration" "avg")
    avg_b=$(extract_metric "$file_b" "http_req_duration" "avg")
    max_a=$(extract_metric "$file_a" "http_req_duration" "max")
    max_b=$(extract_metric "$file_b" "http_req_duration" "max")

    local fail_rate_a fail_rate_b
    fail_rate_a=$(extract_metric "$file_a" "http_req_failed" "rate")
    fail_rate_b=$(extract_metric "$file_b" "http_req_failed" "rate")

    # Throughput section
    if [ -n "$tps_label" ]; then
        echo "### ${tps_label} TPS"
    fi
    echo ""
    echo "**Throughput**"
    echo ""

    local rate_delta_info rate_delta rate_pct rate_dir
    rate_delta_info=$(compute_delta "$iter_rate_a" "$iter_rate_b")
    rate_delta=$(echo "$rate_delta_info" | awk '{print $1}')
    rate_pct=$(echo "$rate_delta_info" | awk '{print $2}')
    rate_dir=$(echo "$rate_delta_info" | awk '{print $3}')

    echo "| Metric | ${label_a} | ${label_b} | Delta |"
    echo "|--------|--------|--------|-------|"
    echo "| Iterations | $(fmt_int "$iter_a") | $(fmt_int "$iter_b") | |"
    echo "| Iter/s | $(fmt "$iter_rate_a") | $(fmt "$iter_rate_b") | $(fmt_delta "$rate_delta" "$rate_pct" "$rate_dir" true) |"
    echo "| HTTP req/s | $(fmt "$http_rate_a") | $(fmt "$http_rate_b") | |"
    echo ""

    # Latency section
    echo "**Latency (ms)**"
    echo ""
    echo "| Percentile | ${label_a} | ${label_b} | Delta |"
    echo "|------------|--------|--------|-------|"

    for stat_info in "avg:${avg_a}:${avg_b}" "p50:${p50_a}:${p50_b}" "p95:${p95_a}:${p95_b}" "p99:${p99_a}:${p99_b}" "max:${max_a}:${max_b}"; do
        local stat_name="${stat_info%%:*}"
        local rest="${stat_info#*:}"
        local val_a="${rest%%:*}"
        local val_b="${rest#*:}"

        local d_info d_val d_pct d_dir
        d_info=$(compute_delta "$val_a" "$val_b")
        d_val=$(echo "$d_info" | awk '{print $1}')
        d_pct=$(echo "$d_info" | awk '{print $2}')
        d_dir=$(echo "$d_info" | awk '{print $3}')

        echo "| ${stat_name} | $(fmt "$val_a") | $(fmt "$val_b") | $(fmt_delta "$d_val" "$d_pct" "$d_dir") |"
    done
    echo ""

    # Custom metrics (if available)
    for custom_info in "order_create_duration:Order Create" "stock_reserve_duration:Stock Reserve" "customer_create_duration:Customer Create" "saga_e2e_duration:Saga E2E"; do
        local custom_metric="${custom_info%%:*}"
        local custom_label="${custom_info#*:}"

        local cm_p95_a cm_p95_b cm_avg_a cm_avg_b
        cm_p95_a=$(extract_metric "$file_a" "$custom_metric" "p(95)")
        cm_p95_b=$(extract_metric "$file_b" "$custom_metric" "p(95)")
        cm_avg_a=$(extract_metric "$file_a" "$custom_metric" "avg")
        cm_avg_b=$(extract_metric "$file_b" "$custom_metric" "avg")

        if [ -z "$cm_p95_a" ] && [ -z "$cm_p95_b" ]; then
            continue
        fi

        local cm_d_info cm_d_val cm_d_pct cm_d_dir
        cm_d_info=$(compute_delta "$cm_p95_a" "$cm_p95_b")
        cm_d_val=$(echo "$cm_d_info" | awk '{print $1}')
        cm_d_pct=$(echo "$cm_d_info" | awk '{print $2}')
        cm_d_dir=$(echo "$cm_d_info" | awk '{print $3}')

        echo "| ${custom_label} p95 | $(fmt "$cm_p95_a") | $(fmt "$cm_p95_b") | $(fmt_delta "$cm_d_val" "$cm_d_pct" "$cm_d_dir") |"
    done
    echo ""

    # Error rates
    echo "**Error Rates**"
    echo ""
    local fail_pct_a="N/A" fail_pct_b="N/A"
    if [ -n "$fail_rate_a" ]; then
        fail_pct_a="$(fmt "$(echo "$fail_rate_a * 100" | bc -l)")%"
    fi
    if [ -n "$fail_rate_b" ]; then
        fail_pct_b="$(fmt "$(echo "$fail_rate_b * 100" | bc -l)")%"
    fi
    echo "| Metric | ${label_a} | ${label_b} |"
    echo "|--------|--------|--------|"
    echo "| HTTP failure rate | ${fail_pct_a} | ${fail_pct_b} |"
    echo ""
}

# Extract resource usage from k8s-metrics file
extract_resource_summary() {
    local metrics_file="$1"
    if [ -f "$metrics_file" ]; then
        echo '```'
        cat "$metrics_file"
        echo '```'
    else
        echo "(no metrics captured)"
    fi
}

# ---------------------------------------------------------------------------
# Parse arguments (bash 3.2 compatible)
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --docker-baseline)
            DOCKER_BASELINE="$2"
            shift 2
            ;;
        --k8s)
            K8S_DIR="$2"
            shift 2
            ;;
        --tps)
            COMPARE_TPS="$2"
            shift 2
            ;;
        --output)
            OUTPUT="$2"
            shift 2
            ;;
        --label-a)
            LABEL_A="$2"
            shift 2
            ;;
        --label-b)
            LABEL_B="$2"
            shift 2
            ;;
        --help|-h)
            print_usage
            exit 0
            ;;
        -*)
            die "Unknown option: $1"
            ;;
        *)
            # Positional args: dir-a and dir-b
            if [ -z "$DIR_A" ]; then
                DIR_A="$1"
            elif [ -z "$DIR_B" ]; then
                DIR_B="$1"
            else
                die "Too many positional arguments"
            fi
            shift
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Determine comparison mode
# ---------------------------------------------------------------------------
if [ -n "$DOCKER_BASELINE" ] && [ -n "$K8S_DIR" ]; then
    MODE="docker-vs-k8s"
    if [ -z "$COMPARE_TPS" ]; then
        die "--tps is required for Docker vs K8s comparison"
    fi
    if [ ! -f "$DOCKER_BASELINE" ]; then
        die "Docker baseline file not found: $DOCKER_BASELINE"
    fi
    # Strip trailing slash
    K8S_DIR="${K8S_DIR%/}"
    if [ ! -d "$K8S_DIR" ]; then
        die "K8s directory not found: $K8S_DIR"
    fi
    if [ ! -f "$K8S_DIR/manifest.json" ]; then
        die "manifest.json not found in $K8S_DIR"
    fi
elif [ -n "$DIR_A" ] && [ -n "$DIR_B" ]; then
    MODE="k8s-vs-k8s"
    # Strip trailing slashes
    DIR_A="${DIR_A%/}"
    DIR_B="${DIR_B%/}"
    if [ ! -d "$DIR_A" ]; then
        die "Directory not found: $DIR_A"
    fi
    if [ ! -d "$DIR_B" ]; then
        die "Directory not found: $DIR_B"
    fi
    if [ ! -f "$DIR_A/manifest.json" ]; then
        die "manifest.json not found in $DIR_A"
    fi
    if [ ! -f "$DIR_B/manifest.json" ]; then
        die "manifest.json not found in $DIR_B"
    fi
else
    die "Specify either two directories or --docker-baseline + --k8s + --tps.\n\n$(print_usage)"
fi

# Validate prerequisites
if ! command -v jq > /dev/null 2>&1; then
    die "jq is required but not installed. Install with: brew install jq"
fi
if ! command -v bc > /dev/null 2>&1; then
    die "bc is required but not installed."
fi

# ---------------------------------------------------------------------------
# Generate report
# ---------------------------------------------------------------------------

generate_report() {
    local timestamp
    timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    echo "# Deployment Performance Comparison"
    echo ""
    echo "**Generated**: ${timestamp}"
    echo ""

    if [ "$MODE" = "docker-vs-k8s" ]; then
        # --- Docker Compose vs K8s ---
        local k8s_target k8s_scenario k8s_duration k8s_nodes k8s_instance_types
        k8s_target=$(jq -r '.target // "unknown"' "$K8S_DIR/manifest.json")
        k8s_scenario=$(jq -r '.scenario // "unknown"' "$K8S_DIR/manifest.json")
        k8s_duration=$(jq -r '.duration // "unknown"' "$K8S_DIR/manifest.json")
        k8s_nodes=$(jq -r '.nodeCount // "?"' "$K8S_DIR/manifest.json")
        k8s_instance_types=$(jq -r '.instanceTypes // "unknown"' "$K8S_DIR/manifest.json")

        if [ -z "$LABEL_A" ]; then
            LABEL_A="Docker Compose"
        fi
        if [ -z "$LABEL_B" ]; then
            LABEL_B="K8s (${k8s_target})"
        fi

        echo "**${LABEL_A}** vs **${LABEL_B}**"
        echo ""

        echo "## Environment"
        echo ""
        echo "| Setting | ${LABEL_A} | ${LABEL_B} |"
        echo "|---------|--------|--------|"
        echo "| Platform | Docker Compose | Kubernetes (${k8s_target}) |"
        echo "| Scenario | — | ${k8s_scenario} |"
        echo "| Duration | — | ${k8s_duration} |"
        echo "| Nodes | 1 (local) | ${k8s_nodes} |"
        echo "| Instance types | — | ${k8s_instance_types} |"
        echo "| Compare TPS | ${COMPARE_TPS} | ${COMPARE_TPS} |"
        echo ""

        # Compare at the specified TPS
        local k8s_result="$K8S_DIR/k6-results-${COMPARE_TPS}tps.json"
        echo "## Comparison at ${COMPARE_TPS} TPS"
        echo ""
        compare_results "$DOCKER_BASELINE" "$k8s_result" "$LABEL_A" "$LABEL_B" ""

        # Resource usage from K8s
        local k8s_metrics="$K8S_DIR/k8s-metrics-${COMPARE_TPS}tps.txt"
        if [ -f "$k8s_metrics" ]; then
            echo "## K8s Resource Usage (${COMPARE_TPS} TPS)"
            echo ""
            extract_resource_summary "$k8s_metrics"
            echo ""
        fi

    elif [ "$MODE" = "k8s-vs-k8s" ]; then
        # --- K8s vs K8s ---
        local target_a target_b scenario_a scenario_b dur_a dur_b nodes_a nodes_b types_a types_b
        target_a=$(jq -r '.target // "unknown"' "$DIR_A/manifest.json")
        target_b=$(jq -r '.target // "unknown"' "$DIR_B/manifest.json")
        scenario_a=$(jq -r '.scenario // "unknown"' "$DIR_A/manifest.json")
        scenario_b=$(jq -r '.scenario // "unknown"' "$DIR_B/manifest.json")
        dur_a=$(jq -r '.duration // "unknown"' "$DIR_A/manifest.json")
        dur_b=$(jq -r '.duration // "unknown"' "$DIR_B/manifest.json")
        nodes_a=$(jq -r '.nodeCount // "?"' "$DIR_A/manifest.json")
        nodes_b=$(jq -r '.nodeCount // "?"' "$DIR_B/manifest.json")
        types_a=$(jq -r '.instanceTypes // "unknown"' "$DIR_A/manifest.json")
        types_b=$(jq -r '.instanceTypes // "unknown"' "$DIR_B/manifest.json")

        if [ -z "$LABEL_A" ]; then
            LABEL_A="$target_a"
        fi
        if [ -z "$LABEL_B" ]; then
            LABEL_B="$target_b"
        fi

        echo "**${LABEL_A}** vs **${LABEL_B}**"
        echo ""

        echo "## Environment"
        echo ""
        echo "| Setting | ${LABEL_A} | ${LABEL_B} |"
        echo "|---------|--------|--------|"
        echo "| Target | ${target_a} | ${target_b} |"
        echo "| Scenario | ${scenario_a} | ${scenario_b} |"
        echo "| Duration | ${dur_a} | ${dur_b} |"
        echo "| Nodes | ${nodes_a} | ${nodes_b} |"
        echo "| Instance types | ${types_a} | ${types_b} |"
        echo ""

        # Find common TPS levels
        local tps_list_a tps_list_b
        tps_list_a=$(jq -r '.tpsLevels[]' "$DIR_A/manifest.json" 2>/dev/null)
        tps_list_b=$(jq -r '.tpsLevels[]' "$DIR_B/manifest.json" 2>/dev/null)

        # Cross-deployment summary table
        echo "## Summary Table"
        echo ""
        echo "| TPS | Iter/s (${LABEL_A}) | Iter/s (${LABEL_B}) | p95 (${LABEL_A}) | p95 (${LABEL_B}) | p95 Delta |"
        echo "|-----|--------|--------|--------|--------|-------|"

        for tps in $tps_list_a; do
            # Check if this TPS exists in both
            local found=false
            for tps_b_val in $tps_list_b; do
                if [ "$tps" = "$tps_b_val" ]; then
                    found=true
                    break
                fi
            done

            local result_a="$DIR_A/k6-results-${tps}tps.json"
            local result_b="$DIR_B/k6-results-${tps}tps.json"

            if [ "$found" = true ] && [ -f "$result_a" ] && [ -f "$result_b" ]; then
                local ir_a ir_b p95_a_val p95_b_val
                ir_a=$(extract_metric "$result_a" "iterations" "rate")
                ir_b=$(extract_metric "$result_b" "iterations" "rate")
                p95_a_val=$(extract_metric "$result_a" "http_req_duration" "p(95)")
                p95_b_val=$(extract_metric "$result_b" "http_req_duration" "p(95)")

                local p95_d_info p95_d_val p95_d_pct p95_d_dir
                p95_d_info=$(compute_delta "$p95_a_val" "$p95_b_val")
                p95_d_val=$(echo "$p95_d_info" | awk '{print $1}')
                p95_d_pct=$(echo "$p95_d_info" | awk '{print $2}')
                p95_d_dir=$(echo "$p95_d_info" | awk '{print $3}')

                echo "| ${tps} | $(fmt "$ir_a") | $(fmt "$ir_b") | $(fmt "$p95_a_val") | $(fmt "$p95_b_val") | $(fmt_delta "$p95_d_val" "$p95_d_pct" "$p95_d_dir") |"
            elif [ -f "$result_a" ]; then
                local ir_a_only p95_a_only
                ir_a_only=$(extract_metric "$result_a" "iterations" "rate")
                p95_a_only=$(extract_metric "$result_a" "http_req_duration" "p(95)")
                echo "| ${tps} | $(fmt "$ir_a_only") | — | $(fmt "$p95_a_only") | — | — |"
            fi
        done
        echo ""

        # Detailed per-TPS comparison
        echo "## Detailed Comparison"
        echo ""

        for tps in $tps_list_a; do
            local found=false
            for tps_b_val in $tps_list_b; do
                if [ "$tps" = "$tps_b_val" ]; then
                    found=true
                    break
                fi
            done

            if [ "$found" = false ]; then
                continue
            fi

            local result_a="$DIR_A/k6-results-${tps}tps.json"
            local result_b="$DIR_B/k6-results-${tps}tps.json"

            if [ -f "$result_a" ] && [ -f "$result_b" ]; then
                compare_results "$result_a" "$result_b" "$LABEL_A" "$LABEL_B" "$tps"
            fi
        done

        # Resource comparison
        echo "## Resource Usage Comparison"
        echo ""

        for tps in $tps_list_a; do
            local found=false
            for tps_b_val in $tps_list_b; do
                if [ "$tps" = "$tps_b_val" ]; then
                    found=true
                    break
                fi
            done

            if [ "$found" = false ]; then
                continue
            fi

            local metrics_a="$DIR_A/k8s-metrics-${tps}tps.txt"
            local metrics_b="$DIR_B/k8s-metrics-${tps}tps.txt"

            if [ -f "$metrics_a" ] || [ -f "$metrics_b" ]; then
                echo "### ${tps} TPS"
                echo ""
                if [ -f "$metrics_a" ]; then
                    echo "**${LABEL_A}**"
                    echo ""
                    extract_resource_summary "$metrics_a"
                    echo ""
                fi
                if [ -f "$metrics_b" ]; then
                    echo "**${LABEL_B}**"
                    echo ""
                    extract_resource_summary "$metrics_b"
                    echo ""
                fi
            fi
        done
    fi

    echo "---"
    echo "*Report generated by k8s-compare.sh*"
}

# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------
echo -e "${CYAN}Comparison mode: ${MODE}${NC}" >&2

if [ "$MODE" = "docker-vs-k8s" ]; then
    echo -e "  Docker baseline: $(basename "$DOCKER_BASELINE")" >&2
    echo -e "  K8s sweep: $(basename "$K8S_DIR")" >&2
    echo -e "  TPS: ${COMPARE_TPS}" >&2
elif [ "$MODE" = "k8s-vs-k8s" ]; then
    echo -e "  A: $(basename "$DIR_A")" >&2
    echo -e "  B: $(basename "$DIR_B")" >&2
fi

if [ -n "$OUTPUT" ]; then
    generate_report > "$OUTPUT"
    echo -e "${GREEN}Report written to: ${OUTPUT}${NC}" >&2
else
    generate_report
fi
