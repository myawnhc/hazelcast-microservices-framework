#!/bin/bash
#
# ab-compare.sh - A/B Test Comparison Engine
#
# Compares two k6 result JSON files and generates a markdown report with
# throughput, latency, error rate tables, and ASCII bar charts.
#
# Compatible with macOS bash 3.2 (no bash 4+ features).
#
# Usage:
#   ./scripts/perf/ab-compare.sh <result-a.json> <result-b.json> [options]
#   ./scripts/perf/ab-compare.sh <manifest-a.json> <manifest-b.json> [options]
#
# Options:
#   --output FILE     Write report to FILE (default: stdout)
#   --label-a NAME    Label for variant A (default: from manifest or "Variant A")
#   --label-b NAME    Label for variant B (default: from manifest or "Variant B")
#   --stats-a FILE    Docker stats file for variant A
#   --stats-b FILE    Docker stats file for variant B
#   --help            Show this help message
#

set -e

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

FILE_A=""
FILE_B=""
OUTPUT=""
LABEL_A=""
LABEL_B=""
STATS_A=""
STATS_B=""
MANIFEST_A=""
MANIFEST_B=""

# Colors for terminal output (not used in report)
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# ---------------------------------------------------------------------------
# Functions
# ---------------------------------------------------------------------------

print_usage() {
    echo "Usage: $0 <result-a.json> <result-b.json> [options]"
    echo ""
    echo "Compares two k6 result JSON files and generates a markdown report."
    echo ""
    echo "Arguments:"
    echo "  result-a.json    k6 result JSON or manifest JSON for variant A"
    echo "  result-b.json    k6 result JSON or manifest JSON for variant B"
    echo ""
    echo "Options:"
    echo "  --output FILE     Write report to FILE (default: stdout)"
    echo "  --label-a NAME    Label for variant A (default: auto-detected)"
    echo "  --label-b NAME    Label for variant B (default: auto-detected)"
    echo "  --stats-a FILE    Docker stats file for variant A"
    echo "  --stats-b FILE    Docker stats file for variant B"
    echo "  --help            Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 results/k6-run1.json results/k6-run2.json"
    echo "  $0 ab-results/run1/manifest-a.json ab-results/run1/manifest-b.json --output report.md"
}

die() {
    echo -e "${RED}Error: $1${NC}" >&2
    exit 1
}

# Check if a file is a manifest (has "version" and "resultFile" keys)
is_manifest() {
    local file="$1"
    jq -e '.version and .resultFile' "$file" > /dev/null 2>&1
}

# Extract a metric value from k6 JSON: extract_metric <file> <metric_name> <stat>
# Stats: avg, min, med, max, p(90), p(95), count, rate, passes, fails
extract_metric() {
    local file="$1"
    local metric="$2"
    local stat="$3"
    jq -r ".metrics[\"$metric\"].values[\"$stat\"] // empty" "$file" 2>/dev/null
}

# Format a number to 2 decimal places
fmt() {
    local val="$1"
    if [ -z "$val" ] || [ "$val" = "null" ]; then
        echo "N/A"
        return
    fi
    printf "%.2f" "$val" 2>/dev/null || echo "$val"
}

# Format a number to 0 decimal places
fmt_int() {
    local val="$1"
    if [ -z "$val" ] || [ "$val" = "null" ]; then
        echo "N/A"
        return
    fi
    printf "%.0f" "$val" 2>/dev/null || echo "$val"
}

# Compute delta and percentage change: compute_delta <val_a> <val_b>
# Outputs: "delta pct_change direction"
# direction: "better" if lower (for latency), "worse" if higher
compute_delta() {
    local a="$1"
    local b="$2"

    if [ -z "$a" ] || [ -z "$b" ] || [ "$a" = "N/A" ] || [ "$b" = "N/A" ]; then
        echo "N/A N/A neutral"
        return
    fi

    local delta
    local pct
    delta=$(echo "$b - $a" | bc -l 2>/dev/null)
    if [ -z "$delta" ]; then
        echo "N/A N/A neutral"
        return
    fi

    # Avoid division by zero
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

    pct=$(echo "scale=1; ($delta / $abs_a) * 100" | bc -l 2>/dev/null)

    # Determine direction (negative delta = B is lower = better for latency)
    local direction="neutral"
    local is_negative
    is_negative=$(echo "$delta < 0" | bc -l 2>/dev/null)
    local is_positive
    is_positive=$(echo "$delta > 0" | bc -l 2>/dev/null)
    if [ "$is_negative" = "1" ]; then
        direction="better"
    elif [ "$is_positive" = "1" ]; then
        direction="worse"
    fi

    echo "$(fmt "$delta") $pct $direction"
}

# Generate an ASCII bar: ascii_bar <value> <max_value> <width>
ascii_bar() {
    local val="$1"
    local max_val="$2"
    local width="${3:-40}"

    if [ -z "$val" ] || [ "$val" = "N/A" ] || [ -z "$max_val" ] || [ "$max_val" = "0" ]; then
        echo ""
        return
    fi

    local bar_len
    bar_len=$(echo "scale=0; ($val / $max_val) * $width" | bc -l 2>/dev/null)
    if [ -z "$bar_len" ] || [ "$bar_len" = "0" ]; then
        bar_len=1
    fi

    local bar=""
    local i=0
    while [ "$i" -lt "$bar_len" ] && [ "$i" -lt "$width" ]; do
        bar="${bar}#"
        i=$((i + 1))
    done
    echo "$bar"
}

# Format delta with arrow indicator
fmt_delta() {
    local delta="$1"
    local pct="$2"
    local direction="$3"
    local higher_is_better="${4:-false}"

    if [ "$delta" = "N/A" ]; then
        echo "N/A"
        return
    fi

    local arrow=""
    local effective_dir="$direction"
    if [ "$higher_is_better" = "true" ]; then
        # For throughput, higher is better so flip direction
        if [ "$direction" = "better" ]; then
            effective_dir="worse"
        elif [ "$direction" = "worse" ]; then
            effective_dir="better"
        fi
    fi

    if [ "$effective_dir" = "better" ]; then
        arrow="v"  # improved
    elif [ "$effective_dir" = "worse" ]; then
        arrow="^"  # regressed
    else
        arrow="="
    fi

    if [ "$pct" = "N/A" ]; then
        echo "${delta} ${arrow}"
    else
        echo "${delta} (${pct}%) ${arrow}"
    fi
}

# ---------------------------------------------------------------------------
# Parse arguments (bash 3.2 compatible)
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
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
        --stats-a)
            STATS_A="$2"
            shift 2
            ;;
        --stats-b)
            STATS_B="$2"
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
            if [ -z "$FILE_A" ]; then
                FILE_A="$1"
            elif [ -z "$FILE_B" ]; then
                FILE_B="$1"
            else
                die "Too many positional arguments"
            fi
            shift
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Validate inputs
# ---------------------------------------------------------------------------
if [ -z "$FILE_A" ] || [ -z "$FILE_B" ]; then
    die "Two result files are required.\n\nUsage: $0 <result-a.json> <result-b.json> [options]"
fi

if [ ! -f "$FILE_A" ]; then
    die "File not found: $FILE_A"
fi

if [ ! -f "$FILE_B" ]; then
    die "File not found: $FILE_B"
fi

if ! command -v jq > /dev/null 2>&1; then
    die "jq is required but not installed. Install with: brew install jq"
fi

if ! command -v bc > /dev/null 2>&1; then
    die "bc is required but not installed."
fi

# ---------------------------------------------------------------------------
# Resolve manifests to result files
# ---------------------------------------------------------------------------
RESULT_A="$FILE_A"
RESULT_B="$FILE_B"

if is_manifest "$FILE_A"; then
    MANIFEST_A="$FILE_A"
    MANIFEST_DIR_A="$(dirname "$FILE_A")"
    RESULT_A="$MANIFEST_DIR_A/$(jq -r '.resultFile' "$FILE_A")"
    if [ -z "$LABEL_A" ]; then
        LABEL_A=$(jq -r '.configName // "Variant A"' "$FILE_A")
    fi
    if [ -z "$STATS_A" ]; then
        local_stats="$MANIFEST_DIR_A/$(jq -r '.dockerStats // empty' "$FILE_A")"
        if [ -n "$local_stats" ] && [ -f "$local_stats" ]; then
            STATS_A="$local_stats"
        fi
    fi
fi

if is_manifest "$FILE_B"; then
    MANIFEST_B="$FILE_B"
    MANIFEST_DIR_B="$(dirname "$FILE_B")"
    RESULT_B="$MANIFEST_DIR_B/$(jq -r '.resultFile' "$FILE_B")"
    if [ -z "$LABEL_B" ]; then
        LABEL_B=$(jq -r '.configName // "Variant B"' "$FILE_B")
    fi
    if [ -z "$STATS_B" ]; then
        local_stats="$MANIFEST_DIR_B/$(jq -r '.dockerStats // empty' "$FILE_B")"
        if [ -n "$local_stats" ] && [ -f "$local_stats" ]; then
            STATS_B="$local_stats"
        fi
    fi
fi

# Validate resolved result files
if [ ! -f "$RESULT_A" ]; then
    die "Result file not found: $RESULT_A"
fi

if [ ! -f "$RESULT_B" ]; then
    die "Result file not found: $RESULT_B"
fi

# Default labels
if [ -z "$LABEL_A" ]; then
    LABEL_A="Variant A"
fi
if [ -z "$LABEL_B" ]; then
    LABEL_B="Variant B"
fi

echo -e "${CYAN}Comparing: ${LABEL_A} vs ${LABEL_B}${NC}" >&2
echo -e "  A: $(basename "$RESULT_A")" >&2
echo -e "  B: $(basename "$RESULT_B")" >&2

# ---------------------------------------------------------------------------
# Extract all metrics
# ---------------------------------------------------------------------------

# Throughput
ITERATIONS_A=$(extract_metric "$RESULT_A" "iterations" "count")
ITERATIONS_B=$(extract_metric "$RESULT_B" "iterations" "count")
ITER_RATE_A=$(extract_metric "$RESULT_A" "iterations" "rate")
ITER_RATE_B=$(extract_metric "$RESULT_B" "iterations" "rate")
HTTP_REQS_A=$(extract_metric "$RESULT_A" "http_reqs" "count")
HTTP_REQS_B=$(extract_metric "$RESULT_B" "http_reqs" "count")
HTTP_RATE_A=$(extract_metric "$RESULT_A" "http_reqs" "rate")
HTTP_RATE_B=$(extract_metric "$RESULT_B" "http_reqs" "rate")
DATA_RECV_A=$(extract_metric "$RESULT_A" "data_received" "count")
DATA_RECV_B=$(extract_metric "$RESULT_B" "data_received" "count")
DATA_SENT_A=$(extract_metric "$RESULT_A" "data_sent" "count")
DATA_SENT_B=$(extract_metric "$RESULT_B" "data_sent" "count")

# Duration
DURATION_A=$(jq -r '.state.testRunDurationMs // empty' "$RESULT_A")
DURATION_B=$(jq -r '.state.testRunDurationMs // empty' "$RESULT_B")

# Latency - http_req_duration
HTTP_P50_A=$(extract_metric "$RESULT_A" "http_req_duration" "med")
HTTP_P50_B=$(extract_metric "$RESULT_B" "http_req_duration" "med")
HTTP_P90_A=$(extract_metric "$RESULT_A" "http_req_duration" "p(90)")
HTTP_P90_B=$(extract_metric "$RESULT_B" "http_req_duration" "p(90)")
HTTP_P95_A=$(extract_metric "$RESULT_A" "http_req_duration" "p(95)")
HTTP_P95_B=$(extract_metric "$RESULT_B" "http_req_duration" "p(95)")
HTTP_AVG_A=$(extract_metric "$RESULT_A" "http_req_duration" "avg")
HTTP_AVG_B=$(extract_metric "$RESULT_B" "http_req_duration" "avg")
HTTP_MAX_A=$(extract_metric "$RESULT_A" "http_req_duration" "max")
HTTP_MAX_B=$(extract_metric "$RESULT_B" "http_req_duration" "max")

# Custom metrics - order_create_duration
ORDER_P50_A=$(extract_metric "$RESULT_A" "order_create_duration" "med")
ORDER_P50_B=$(extract_metric "$RESULT_B" "order_create_duration" "med")
ORDER_P95_A=$(extract_metric "$RESULT_A" "order_create_duration" "p(95)")
ORDER_P95_B=$(extract_metric "$RESULT_B" "order_create_duration" "p(95)")
ORDER_AVG_A=$(extract_metric "$RESULT_A" "order_create_duration" "avg")
ORDER_AVG_B=$(extract_metric "$RESULT_B" "order_create_duration" "avg")

# Custom metrics - stock_reserve_duration
STOCK_P50_A=$(extract_metric "$RESULT_A" "stock_reserve_duration" "med")
STOCK_P50_B=$(extract_metric "$RESULT_B" "stock_reserve_duration" "med")
STOCK_P95_A=$(extract_metric "$RESULT_A" "stock_reserve_duration" "p(95)")
STOCK_P95_B=$(extract_metric "$RESULT_B" "stock_reserve_duration" "p(95)")
STOCK_AVG_A=$(extract_metric "$RESULT_A" "stock_reserve_duration" "avg")
STOCK_AVG_B=$(extract_metric "$RESULT_B" "stock_reserve_duration" "avg")

# Custom metrics - customer_create_duration
CUST_P50_A=$(extract_metric "$RESULT_A" "customer_create_duration" "med")
CUST_P50_B=$(extract_metric "$RESULT_B" "customer_create_duration" "med")
CUST_P95_A=$(extract_metric "$RESULT_A" "customer_create_duration" "p(95)")
CUST_P95_B=$(extract_metric "$RESULT_B" "customer_create_duration" "p(95)")
CUST_AVG_A=$(extract_metric "$RESULT_A" "customer_create_duration" "avg")
CUST_AVG_B=$(extract_metric "$RESULT_B" "customer_create_duration" "avg")

# Custom metrics - saga_e2e_duration
SAGA_P50_A=$(extract_metric "$RESULT_A" "saga_e2e_duration" "med")
SAGA_P50_B=$(extract_metric "$RESULT_B" "saga_e2e_duration" "med")
SAGA_P95_A=$(extract_metric "$RESULT_A" "saga_e2e_duration" "p(95)")
SAGA_P95_B=$(extract_metric "$RESULT_B" "saga_e2e_duration" "p(95)")
SAGA_AVG_A=$(extract_metric "$RESULT_A" "saga_e2e_duration" "avg")
SAGA_AVG_B=$(extract_metric "$RESULT_B" "saga_e2e_duration" "avg")

# Error rates
HTTP_FAIL_RATE_A=$(extract_metric "$RESULT_A" "http_req_failed" "rate")
HTTP_FAIL_RATE_B=$(extract_metric "$RESULT_B" "http_req_failed" "rate")
HTTP_FAIL_COUNT_A=$(extract_metric "$RESULT_A" "http_req_failed" "passes")
HTTP_FAIL_COUNT_B=$(extract_metric "$RESULT_B" "http_req_failed" "passes")
MIXED_ERR_RATE_A=$(extract_metric "$RESULT_A" "mixed_workload_errors" "rate")
MIXED_ERR_RATE_B=$(extract_metric "$RESULT_B" "mixed_workload_errors" "rate")

# ---------------------------------------------------------------------------
# Generate report
# ---------------------------------------------------------------------------

generate_report() {
    local timestamp
    timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    echo "# A/B Test Comparison Report"
    echo ""
    echo "**Generated**: ${timestamp}"
    echo "**Variant A**: ${LABEL_A}"
    echo "**Variant B**: ${LABEL_B}"
    echo ""

    # --- Configuration summary from manifests ---
    if [ -n "$MANIFEST_A" ] || [ -n "$MANIFEST_B" ]; then
        echo "## Configuration"
        echo ""
        echo "| Setting | ${LABEL_A} | ${LABEL_B} |"
        echo "|---------|$(printf '%*s' ${#LABEL_A} '' | tr ' ' '-')--|$(printf '%*s' ${#LABEL_B} '' | tr ' ' '-')--|"

        if [ -n "$MANIFEST_A" ] && [ -n "$MANIFEST_B" ]; then
            local desc_a desc_b compose_a compose_b scenario_a scenario_b tps_a tps_b dur_a dur_b
            desc_a=$(jq -r '.configName // "N/A"' "$MANIFEST_A")
            desc_b=$(jq -r '.configName // "N/A"' "$MANIFEST_B")
            compose_a=$(jq -r '.composeFiles | join(", ") // "N/A"' "$MANIFEST_A" 2>/dev/null || echo "N/A")
            compose_b=$(jq -r '.composeFiles | join(", ") // "N/A"' "$MANIFEST_B" 2>/dev/null || echo "N/A")
            scenario_a=$(jq -r '.k6Scenario // "N/A"' "$MANIFEST_A")
            scenario_b=$(jq -r '.k6Scenario // "N/A"' "$MANIFEST_B")
            tps_a=$(jq -r '.k6Tps // "N/A"' "$MANIFEST_A")
            tps_b=$(jq -r '.k6Tps // "N/A"' "$MANIFEST_B")
            dur_a=$(jq -r '.k6Duration // "N/A"' "$MANIFEST_A")
            dur_b=$(jq -r '.k6Duration // "N/A"' "$MANIFEST_B")

            echo "| Config | ${desc_a} | ${desc_b} |"
            echo "| Compose files | ${compose_a} | ${compose_b} |"
            echo "| k6 scenario | ${scenario_a} | ${scenario_b} |"
            echo "| Target TPS | ${tps_a} | ${tps_b} |"
            echo "| Duration | ${dur_a} | ${dur_b} |"
        fi
        echo ""
    fi

    # --- Throughput ---
    echo "## Throughput"
    echo ""

    local iter_delta_info
    iter_delta_info=$(compute_delta "$ITER_RATE_A" "$ITER_RATE_B")
    local iter_delta iter_pct iter_dir
    iter_delta=$(echo "$iter_delta_info" | awk '{print $1}')
    iter_pct=$(echo "$iter_delta_info" | awk '{print $2}')
    iter_dir=$(echo "$iter_delta_info" | awk '{print $3}')

    echo "| Metric | ${LABEL_A} | ${LABEL_B} | Delta |"
    echo "|--------|$(printf '%*s' ${#LABEL_A} '' | tr ' ' '-')--|$(printf '%*s' ${#LABEL_B} '' | tr ' ' '-')--|-------|"
    echo "| Iterations | $(fmt_int "$ITERATIONS_A") | $(fmt_int "$ITERATIONS_B") | $(fmt_delta "$iter_delta" "$iter_pct" "$iter_dir" true) |"
    echo "| Iterations/s | $(fmt "$ITER_RATE_A") | $(fmt "$ITER_RATE_B") | $(fmt_delta "$iter_delta" "$iter_pct" "$iter_dir" true) |"
    echo "| HTTP Requests | $(fmt_int "$HTTP_REQS_A") | $(fmt_int "$HTTP_REQS_B") | |"
    echo "| HTTP req/s | $(fmt "$HTTP_RATE_A") | $(fmt "$HTTP_RATE_B") | |"

    if [ -n "$DURATION_A" ] && [ -n "$DURATION_B" ]; then
        echo "| Test duration (s) | $(fmt "$(echo "$DURATION_A / 1000" | bc -l)") | $(fmt "$(echo "$DURATION_B / 1000" | bc -l)") | |"
    fi

    echo "| Data received | $(fmt_int "$DATA_RECV_A") B | $(fmt_int "$DATA_RECV_B") B | |"
    echo "| Data sent | $(fmt_int "$DATA_SENT_A") B | $(fmt_int "$DATA_SENT_B") B | |"
    echo ""

    # --- Latency ---
    echo "## Latency (ms)"
    echo ""
    echo "### HTTP Request Duration (all endpoints)"
    echo ""
    echo "| Percentile | ${LABEL_A} | ${LABEL_B} | Delta |"
    echo "|------------|$(printf '%*s' ${#LABEL_A} '' | tr ' ' '-')--|$(printf '%*s' ${#LABEL_B} '' | tr ' ' '-')--|-------|"

    for stat_info in "avg:${HTTP_AVG_A}:${HTTP_AVG_B}" "p50:${HTTP_P50_A}:${HTTP_P50_B}" "p90:${HTTP_P90_A}:${HTTP_P90_B}" "p95:${HTTP_P95_A}:${HTTP_P95_B}" "max:${HTTP_MAX_A}:${HTTP_MAX_B}"; do
        local stat_name="${stat_info%%:*}"
        local rest="${stat_info#*:}"
        local val_a="${rest%%:*}"
        local val_b="${rest#*:}"

        local delta_info
        delta_info=$(compute_delta "$val_a" "$val_b")
        local d_val d_pct d_dir
        d_val=$(echo "$delta_info" | awk '{print $1}')
        d_pct=$(echo "$delta_info" | awk '{print $2}')
        d_dir=$(echo "$delta_info" | awk '{print $3}')

        echo "| ${stat_name} | $(fmt "$val_a") | $(fmt "$val_b") | $(fmt_delta "$d_val" "$d_pct" "$d_dir") |"
    done
    echo ""

    # --- Custom metric tables ---
    for metric_info in "Order Create:ORDER:${ORDER_P50_A}:${ORDER_P50_B}:${ORDER_P95_A}:${ORDER_P95_B}:${ORDER_AVG_A}:${ORDER_AVG_B}" \
                       "Stock Reserve:STOCK:${STOCK_P50_A}:${STOCK_P50_B}:${STOCK_P95_A}:${STOCK_P95_B}:${STOCK_AVG_A}:${STOCK_AVG_B}" \
                       "Customer Create:CUST:${CUST_P50_A}:${CUST_P50_B}:${CUST_P95_A}:${CUST_P95_B}:${CUST_AVG_A}:${CUST_AVG_B}" \
                       "Saga E2E:SAGA:${SAGA_P50_A}:${SAGA_P50_B}:${SAGA_P95_A}:${SAGA_P95_B}:${SAGA_AVG_A}:${SAGA_AVG_B}"; do

        local m_label="${metric_info%%:*}"
        local m_rest="${metric_info#*:}"
        local m_prefix="${m_rest%%:*}"
        m_rest="${m_rest#*:}"

        # Parse the 6 values: p50_a, p50_b, p95_a, p95_b, avg_a, avg_b
        local m_p50_a="${m_rest%%:*}"; m_rest="${m_rest#*:}"
        local m_p50_b="${m_rest%%:*}"; m_rest="${m_rest#*:}"
        local m_p95_a="${m_rest%%:*}"; m_rest="${m_rest#*:}"
        local m_p95_b="${m_rest%%:*}"; m_rest="${m_rest#*:}"
        local m_avg_a="${m_rest%%:*}"; m_rest="${m_rest#*:}"
        local m_avg_b="$m_rest"

        # Skip if no data for this metric
        if [ -z "$m_p50_a" ] && [ -z "$m_p50_b" ]; then
            continue
        fi

        echo "### ${m_label}"
        echo ""
        echo "| Stat | ${LABEL_A} | ${LABEL_B} | Delta |"
        echo "|------|$(printf '%*s' ${#LABEL_A} '' | tr ' ' '-')--|$(printf '%*s' ${#LABEL_B} '' | tr ' ' '-')--|-------|"

        for row in "avg:${m_avg_a}:${m_avg_b}" "p50:${m_p50_a}:${m_p50_b}" "p95:${m_p95_a}:${m_p95_b}"; do
            local row_name="${row%%:*}"
            local row_rest="${row#*:}"
            local rv_a="${row_rest%%:*}"
            local rv_b="${row_rest#*:}"

            local rd_info
            rd_info=$(compute_delta "$rv_a" "$rv_b")
            local rd_val rd_pct rd_dir
            rd_val=$(echo "$rd_info" | awk '{print $1}')
            rd_pct=$(echo "$rd_info" | awk '{print $2}')
            rd_dir=$(echo "$rd_info" | awk '{print $3}')

            echo "| ${row_name} | $(fmt "$rv_a") | $(fmt "$rv_b") | $(fmt_delta "$rd_val" "$rd_pct" "$rd_dir") |"
        done
        echo ""
    done

    # --- Error rates ---
    echo "## Error Rates"
    echo ""
    echo "| Metric | ${LABEL_A} | ${LABEL_B} |"
    echo "|--------|$(printf '%*s' ${#LABEL_A} '' | tr ' ' '-')--|$(printf '%*s' ${#LABEL_B} '' | tr ' ' '-')--|"

    local fail_pct_a="N/A"
    local fail_pct_b="N/A"
    if [ -n "$HTTP_FAIL_RATE_A" ]; then
        fail_pct_a="$(fmt "$(echo "$HTTP_FAIL_RATE_A * 100" | bc -l)")%"
    fi
    if [ -n "$HTTP_FAIL_RATE_B" ]; then
        fail_pct_b="$(fmt "$(echo "$HTTP_FAIL_RATE_B * 100" | bc -l)")%"
    fi

    echo "| HTTP failure rate | ${fail_pct_a} | ${fail_pct_b} |"
    echo "| HTTP failures | $(fmt_int "$HTTP_FAIL_COUNT_A") | $(fmt_int "$HTTP_FAIL_COUNT_B") |"

    if [ -n "$MIXED_ERR_RATE_A" ] || [ -n "$MIXED_ERR_RATE_B" ]; then
        local mixed_pct_a="N/A"
        local mixed_pct_b="N/A"
        if [ -n "$MIXED_ERR_RATE_A" ]; then
            mixed_pct_a="$(fmt "$(echo "$MIXED_ERR_RATE_A * 100" | bc -l)")%"
        fi
        if [ -n "$MIXED_ERR_RATE_B" ]; then
            mixed_pct_b="$(fmt "$(echo "$MIXED_ERR_RATE_B * 100" | bc -l)")%"
        fi
        echo "| Mixed workload errors | ${mixed_pct_a} | ${mixed_pct_b} |"
    fi
    echo ""

    # --- Docker stats ---
    if [ -n "$STATS_A" ] && [ -f "$STATS_A" ] && [ -n "$STATS_B" ] && [ -f "$STATS_B" ]; then
        echo "## Resource Usage (Docker Stats)"
        echo ""
        echo "### ${LABEL_A}"
        echo ""
        echo '```'
        cat "$STATS_A"
        echo '```'
        echo ""
        echo "### ${LABEL_B}"
        echo ""
        echo '```'
        cat "$STATS_B"
        echo '```'
        echo ""
    fi

    # --- ASCII Bar Charts ---
    echo "## P95 Latency Comparison (ASCII)"
    echo ""

    # Find the max p95 across all metrics for scaling
    local max_p95=1
    for val in "$HTTP_P95_A" "$HTTP_P95_B" "$ORDER_P95_A" "$ORDER_P95_B" "$STOCK_P95_A" "$STOCK_P95_B" "$CUST_P95_A" "$CUST_P95_B" "$SAGA_P95_A" "$SAGA_P95_B"; do
        if [ -n "$val" ] && [ "$val" != "N/A" ]; then
            local is_bigger
            is_bigger=$(echo "$val > $max_p95" | bc -l 2>/dev/null)
            if [ "$is_bigger" = "1" ]; then
                max_p95="$val"
            fi
        fi
    done

    echo '```'
    echo "P95 Latency (ms) - lower is better"
    echo "Scale: each # = $(fmt "$(echo "$max_p95 / 40" | bc -l)") ms"
    echo ""

    for chart_info in "http_req_duration:${HTTP_P95_A}:${HTTP_P95_B}" \
                      "order_create:${ORDER_P95_A}:${ORDER_P95_B}" \
                      "stock_reserve:${STOCK_P95_A}:${STOCK_P95_B}" \
                      "customer_create:${CUST_P95_A}:${CUST_P95_B}" \
                      "saga_e2e:${SAGA_P95_A}:${SAGA_P95_B}"; do

        local c_name="${chart_info%%:*}"
        local c_rest="${chart_info#*:}"
        local c_a="${c_rest%%:*}"
        local c_b="${c_rest#*:}"

        if [ -z "$c_a" ] && [ -z "$c_b" ]; then
            continue
        fi

        printf "%-18s\n" "${c_name}"
        printf "  A %-40s %s ms\n" "$(ascii_bar "$c_a" "$max_p95" 40)" "$(fmt "$c_a")"
        printf "  B %-40s %s ms\n" "$(ascii_bar "$c_b" "$max_p95" 40)" "$(fmt "$c_b")"
        echo ""
    done
    echo '```'
    echo ""

    # --- Summary ---
    echo "## Summary"
    echo ""

    # Count wins for B (lower latency = win)
    local b_wins=0
    local a_wins=0
    local ties=0

    for pair in "${HTTP_P95_A}:${HTTP_P95_B}" "${ORDER_P95_A}:${ORDER_P95_B}" "${STOCK_P95_A}:${STOCK_P95_B}" "${CUST_P95_A}:${CUST_P95_B}"; do
        local pa="${pair%%:*}"
        local pb="${pair#*:}"
        if [ -n "$pa" ] && [ -n "$pb" ]; then
            local cmp
            cmp=$(echo "$pb < $pa" | bc -l 2>/dev/null)
            if [ "$cmp" = "1" ]; then
                b_wins=$((b_wins + 1))
            else
                cmp=$(echo "$pa < $pb" | bc -l 2>/dev/null)
                if [ "$cmp" = "1" ]; then
                    a_wins=$((a_wins + 1))
                else
                    ties=$((ties + 1))
                fi
            fi
        fi
    done

    if [ $b_wins -gt $a_wins ]; then
        echo "**${LABEL_B}** wins on ${b_wins} of $((a_wins + b_wins + ties)) p95 latency metrics."
    elif [ $a_wins -gt $b_wins ]; then
        echo "**${LABEL_A}** wins on ${a_wins} of $((a_wins + b_wins + ties)) p95 latency metrics."
    else
        echo "Results are **mixed** - neither variant clearly dominates across all metrics."
    fi

    echo ""
    echo "- ${LABEL_A} p95 wins: ${a_wins}"
    echo "- ${LABEL_B} p95 wins: ${b_wins}"
    echo "- Ties: ${ties}"
    echo ""
    echo "---"
    echo "*Report generated by ab-compare.sh*"
}

# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------
if [ -n "$OUTPUT" ]; then
    generate_report > "$OUTPUT"
    echo -e "${GREEN}Report written to: ${OUTPUT}${NC}" >&2
else
    generate_report
fi
