#!/bin/bash
#
# ab-test.sh - A/B Test Orchestrator
#
# Runs two configuration variants sequentially, captures k6 results and Docker
# stats for each, then generates a comparison report using ab-compare.sh.
#
# Compatible with macOS bash 3.2 (no bash 4+ features).
#
# Usage:
#   ./scripts/perf/ab-test.sh --config-a <conf> --config-b <conf> [options]
#   ./scripts/perf/ab-test.sh --compare-only <manifest-a> <manifest-b>
#
# Options:
#   --config-a FILE     Configuration profile for variant A (required unless --compare-only)
#   --config-b FILE     Configuration profile for variant B (required unless --compare-only)
#   --name NAME         Run name (default: auto-generated from config names)
#   --scenario SCENARIO Override k6 scenario for both variants
#   --tps TPS           Override target TPS for both variants
#   --duration DURATION Override duration for both variants
#   --skip-build        Skip Docker image rebuild
#   --skip-data         Skip sample data generation
#   --compare-only      Compare two existing manifests without running tests
#   --help              Show this help message
#

set -e

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DOCKER_DIR="$PROJECT_ROOT/docker"
RESULTS_BASE="$SCRIPT_DIR/ab-results"

CONFIG_A=""
CONFIG_B=""
RUN_NAME=""
SCENARIO_OVERRIDE=""
TPS_OVERRIDE=""
DURATION_OVERRIDE=""
SKIP_BUILD=false
SKIP_DATA=false
COMPARE_ONLY=false
COMPARE_MANIFEST_A=""
COMPARE_MANIFEST_B=""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# Health check settings
HEALTH_TIMEOUT=180
HEALTH_INTERVAL=5

# Service health endpoints
SERVICE_PORTS="account-service:8081 inventory-service:8082 order-service:8083 payment-service:8084"

# ---------------------------------------------------------------------------
# Functions
# ---------------------------------------------------------------------------

print_usage() {
    echo "Usage: $0 --config-a <conf> --config-b <conf> [options]"
    echo "       $0 --compare-only <manifest-a.json> <manifest-b.json>"
    echo ""
    echo "Options:"
    echo "  --config-a FILE     Configuration profile for variant A"
    echo "  --config-b FILE     Configuration profile for variant B"
    echo "  --name NAME         Run name (default: auto from config names)"
    echo "  --scenario SCENARIO Override k6 scenario for both variants"
    echo "  --tps TPS           Override target TPS for both variants"
    echo "  --duration DURATION Override duration for both variants"
    echo "  --skip-build        Skip Docker image rebuild"
    echo "  --skip-data         Skip sample data generation"
    echo "  --compare-only      Compare two existing manifests"
    echo "  --help              Show this help message"
    echo ""
    echo "Available configs:"
    if [ -d "$SCRIPT_DIR/ab-configs" ]; then
        for f in "$SCRIPT_DIR"/ab-configs/*.conf; do
            if [ -f "$f" ]; then
                local name
                name=$(grep '^NAME=' "$f" | cut -d= -f2-)
                local desc
                desc=$(grep '^DESCRIPTION=' "$f" | cut -d= -f2-)
                printf "  %-40s %s\n" "$(basename "$f")" "$desc"
            fi
        done
    fi
    echo ""
    echo "Examples:"
    echo "  $0 --config-a ab-configs/community-baseline.conf --config-b ab-configs/high-memory.conf"
    echo "  $0 --config-a ab-configs/community-baseline.conf --config-b ab-configs/hd-memory.conf --tps 100"
    echo "  $0 --compare-only ab-results/run-1/manifest-a.json ab-results/run-1/manifest-b.json"
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

# Load a config profile into variables prefixed with the given prefix
# load_config <file> <prefix>
# Sets: <prefix>_NAME, <prefix>_COMPOSE_FILES, <prefix>_K6_SCENARIO, etc.
load_config() {
    local file="$1"
    local prefix="$2"

    if [ ! -f "$file" ]; then
        die "Config file not found: $file"
    fi

    # Read each KEY=VALUE line (skip comments and blanks)
    while IFS= read -r line; do
        # Skip comments and blank lines
        case "$line" in
            '#'*|'') continue ;;
        esac

        local key="${line%%=*}"
        local val="${line#*=}"

        # Export with prefix
        eval "${prefix}_${key}=\"\$val\""
    done < "$file"
}

# Build docker compose command from comma-separated compose files
# build_compose_cmd <compose_files_csv> [memory_limit]
build_compose_cmd() {
    local compose_csv="$1"
    local mem_limit="$2"

    local cmd="docker compose"

    # Split comma-separated list into -f flags (bash 3.2 compatible)
    local remaining="$compose_csv"
    while [ -n "$remaining" ]; do
        local current="${remaining%%,*}"
        if [ "$current" = "$remaining" ]; then
            remaining=""
        else
            remaining="${remaining#*,}"
        fi
        cmd="$cmd -f $current"
    done

    echo "$cmd"
}

# Wait for all services to be healthy
wait_for_services() {
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

# Run a single variant
# run_variant <variant_label> <config_file> <run_dir>
run_variant() {
    local variant="$1"   # "A" or "B"
    local config_file="$2"
    local run_dir="$3"
    local prefix="V${variant}"

    load_config "$config_file" "$prefix"

    # Read config values
    local v_name v_compose v_scenario v_tps v_duration v_pre_hook v_post_hook v_mem_limit v_env_overrides v_notes
    eval "v_name=\${${prefix}_NAME}"
    eval "v_compose=\${${prefix}_COMPOSE_FILES}"
    eval "v_scenario=\${${prefix}_K6_SCENARIO}"
    eval "v_tps=\${${prefix}_K6_TPS}"
    eval "v_duration=\${${prefix}_K6_DURATION}"
    eval "v_pre_hook=\${${prefix}_PRE_HOOK}"
    eval "v_post_hook=\${${prefix}_POST_HOOK}"
    eval "v_mem_limit=\${${prefix}_MEMORY_LIMIT}"
    eval "v_env_overrides=\${${prefix}_ENV_OVERRIDES}"
    eval "v_notes=\${${prefix}_NOTES}"

    # Apply overrides from command line
    if [ -n "$SCENARIO_OVERRIDE" ]; then
        v_scenario="$SCENARIO_OVERRIDE"
    fi
    if [ -n "$TPS_OVERRIDE" ]; then
        v_tps="$TPS_OVERRIDE"
    fi
    if [ -n "$DURATION_OVERRIDE" ]; then
        v_duration="$DURATION_OVERRIDE"
    fi

    local variant_lower
    variant_lower=$(echo "$variant" | tr 'A-Z' 'a-z')

    log_step "Running Variant ${variant}: ${v_name}"

    echo "  Config: $(basename "$config_file")"
    echo "  Compose files: ${v_compose}"
    echo "  k6 scenario: ${v_scenario}"
    echo "  Target TPS: ${v_tps}"
    echo "  Duration: ${v_duration}"
    if [ -n "$v_mem_limit" ]; then
        echo "  Memory limit: ${v_mem_limit}"
    fi
    echo ""

    # Step 1: Clean shutdown
    log "Stopping existing containers..."
    cd "$DOCKER_DIR"
    docker compose down -v 2>/dev/null || true
    sleep 2

    # Step 2: Build compose command
    local compose_cmd
    compose_cmd=$(build_compose_cmd "$v_compose")

    # Step 3: Apply environment overrides
    if [ -n "$v_env_overrides" ]; then
        export $v_env_overrides
    fi

    # Step 4: Start services
    log "Starting services with: ${compose_cmd} up -d"
    cd "$DOCKER_DIR"

    if [ -n "$v_mem_limit" ]; then
        # Override memory limits via environment variable
        # This sets a uniform limit for services that use ${MEMORY_LIMIT:-512M}
        export MEMORY_LIMIT="$v_mem_limit"
    fi

    eval "$compose_cmd up -d" 2>&1

    # Step 5: Wait for health
    wait_for_services

    # Step 6: Load sample data
    if [ "$SKIP_DATA" = false ]; then
        log "Loading sample data..."
        "$SCRIPT_DIR/generate-sample-data.sh" --skip-health 2>&1 || {
            echo -e "${YELLOW}Warning: Sample data generation had errors (continuing anyway)${NC}"
        }
    else
        log "Skipping sample data generation (--skip-data)"
    fi

    # Step 7: Pre-hook
    if [ -n "$v_pre_hook" ]; then
        log "Running pre-hook: ${v_pre_hook}"
        cd "$PROJECT_ROOT"
        eval "$v_pre_hook" 2>&1 || {
            echo -e "${YELLOW}Warning: Pre-hook exited with non-zero status${NC}"
        }
    fi

    # Step 8: Stabilization pause
    log "Stabilization pause (10 seconds)..."
    sleep 10

    # Step 9: Run k6
    log "Running k6 performance test..."
    cd "$PROJECT_ROOT"

    local k6_args="--scenario ${v_scenario} --tps ${v_tps}"
    if [ -n "$v_duration" ]; then
        k6_args="$k6_args --duration ${v_duration}"
    fi

    "$SCRIPT_DIR/run-perf-test.sh" $k6_args 2>&1 || {
        echo -e "${YELLOW}Warning: k6 exited with non-zero status (thresholds may have failed)${NC}"
    }

    # Step 10: Find latest result
    local result_file
    result_file=$(find_latest_k6_result)
    if [ -z "$result_file" ]; then
        die "No k6 result file found after test run"
    fi
    log "k6 result: $(basename "$result_file")"

    # Step 11: Capture Docker stats
    local stats_file="$run_dir/variant-${variant_lower}-docker-stats.txt"
    log "Capturing Docker stats..."
    docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.NetIO}}\t{{.PIDs}}" > "$stats_file" 2>/dev/null || true

    # Step 12: Post-hook
    if [ -n "$v_post_hook" ]; then
        log "Running post-hook: ${v_post_hook}"
        cd "$PROJECT_ROOT"
        eval "$v_post_hook" 2>&1 || {
            echo -e "${YELLOW}Warning: Post-hook exited with non-zero status${NC}"
        }
    fi

    # Step 13: Copy results and write manifest
    local dest_result="$run_dir/variant-${variant_lower}-results.json"
    cp "$result_file" "$dest_result"

    # Build compose files JSON array (bash 3.2 compatible)
    local compose_json="["
    local first=true
    local remaining_compose="$v_compose"
    while [ -n "$remaining_compose" ]; do
        local current="${remaining_compose%%,*}"
        if [ "$current" = "$remaining_compose" ]; then
            remaining_compose=""
        else
            remaining_compose="${remaining_compose#*,}"
        fi
        if [ "$first" = true ]; then
            compose_json="${compose_json}\"${current}\""
            first=false
        else
            compose_json="${compose_json}, \"${current}\""
        fi
    done
    compose_json="${compose_json}]"

    local git_sha git_branch
    git_sha=$(cd "$PROJECT_ROOT" && git rev-parse --short HEAD 2>/dev/null || echo "unknown")
    git_branch=$(cd "$PROJECT_ROOT" && git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")

    local manifest_file="$run_dir/manifest-${variant_lower}.json"
    cat > "$manifest_file" << MANIFEST_EOF
{
  "version": 1,
  "variant": "${variant}",
  "configName": "${v_name}",
  "configFile": "${config_file}",
  "composeFiles": ${compose_json},
  "k6Scenario": "${v_scenario}",
  "k6Tps": ${v_tps},
  "k6Duration": "${v_duration}",
  "resultFile": "variant-${variant_lower}-results.json",
  "dockerStats": "variant-${variant_lower}-docker-stats.txt",
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "gitSha": "${git_sha}",
  "gitBranch": "${git_branch}",
  "notes": "${v_notes}"
}
MANIFEST_EOF

    log "Manifest written: $(basename "$manifest_file")"

    # Step 14: Shutdown
    log "Shutting down variant ${variant}..."
    cd "$DOCKER_DIR"
    docker compose down -v 2>/dev/null || true

    # Unset any exported env overrides
    if [ -n "$v_mem_limit" ]; then
        unset MEMORY_LIMIT
    fi

    echo ""
    echo -e "${GREEN}Variant ${variant} (${v_name}) complete.${NC}"
}

# ---------------------------------------------------------------------------
# Parse arguments (bash 3.2 compatible)
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --config-a)
            CONFIG_A="$2"
            shift 2
            ;;
        --config-b)
            CONFIG_B="$2"
            shift 2
            ;;
        --name)
            RUN_NAME="$2"
            shift 2
            ;;
        --scenario)
            SCENARIO_OVERRIDE="$2"
            shift 2
            ;;
        --tps)
            TPS_OVERRIDE="$2"
            shift 2
            ;;
        --duration)
            DURATION_OVERRIDE="$2"
            shift 2
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --skip-data)
            SKIP_DATA=true
            shift
            ;;
        --compare-only)
            COMPARE_ONLY=true
            shift
            # Next two positional args are manifest files
            if [ $# -ge 2 ]; then
                COMPARE_MANIFEST_A="$1"
                COMPARE_MANIFEST_B="$2"
                shift 2
            fi
            ;;
        --help|-h)
            print_usage
            exit 0
            ;;
        -*)
            die "Unknown option: $1"
            ;;
        *)
            # Positional args for --compare-only mode
            if [ "$COMPARE_ONLY" = true ]; then
                if [ -z "$COMPARE_MANIFEST_A" ]; then
                    COMPARE_MANIFEST_A="$1"
                elif [ -z "$COMPARE_MANIFEST_B" ]; then
                    COMPARE_MANIFEST_B="$1"
                fi
            fi
            shift
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Compare-only mode
# ---------------------------------------------------------------------------
if [ "$COMPARE_ONLY" = true ]; then
    if [ -z "$COMPARE_MANIFEST_A" ] || [ -z "$COMPARE_MANIFEST_B" ]; then
        die "Compare-only mode requires two manifest files.\nUsage: $0 --compare-only <manifest-a.json> <manifest-b.json>"
    fi

    log_step "Compare-Only Mode"
    "$SCRIPT_DIR/ab-compare.sh" "$COMPARE_MANIFEST_A" "$COMPARE_MANIFEST_B"
    exit 0
fi

# ---------------------------------------------------------------------------
# Validate inputs
# ---------------------------------------------------------------------------
if [ -z "$CONFIG_A" ] || [ -z "$CONFIG_B" ]; then
    die "Both --config-a and --config-b are required.\n\n$(print_usage)"
fi

if [ ! -f "$CONFIG_A" ]; then
    # Try relative to ab-configs
    if [ -f "$SCRIPT_DIR/ab-configs/$CONFIG_A" ]; then
        CONFIG_A="$SCRIPT_DIR/ab-configs/$CONFIG_A"
    elif [ -f "$SCRIPT_DIR/ab-configs/${CONFIG_A}.conf" ]; then
        CONFIG_A="$SCRIPT_DIR/ab-configs/${CONFIG_A}.conf"
    else
        die "Config file not found: $CONFIG_A"
    fi
fi

if [ ! -f "$CONFIG_B" ]; then
    if [ -f "$SCRIPT_DIR/ab-configs/$CONFIG_B" ]; then
        CONFIG_B="$SCRIPT_DIR/ab-configs/$CONFIG_B"
    elif [ -f "$SCRIPT_DIR/ab-configs/${CONFIG_B}.conf" ]; then
        CONFIG_B="$SCRIPT_DIR/ab-configs/${CONFIG_B}.conf"
    else
        die "Config file not found: $CONFIG_B"
    fi
fi

# Check prerequisites
for cmd in docker jq bc curl k6; do
    if ! command -v "$cmd" > /dev/null 2>&1; then
        die "$cmd is required but not installed."
    fi
done

# Check Docker is running
if ! docker info > /dev/null 2>&1; then
    die "Docker is not running. Please start Docker Desktop."
fi

# ---------------------------------------------------------------------------
# Setup run directory
# ---------------------------------------------------------------------------

# Load config names for auto-naming
load_config "$CONFIG_A" "TMP_A"
load_config "$CONFIG_B" "TMP_B"

if [ -z "$RUN_NAME" ]; then
    RUN_NAME="${TMP_A_NAME}-vs-${TMP_B_NAME}"
fi

# Add timestamp to make runs unique
RUN_TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RUN_DIR="$RESULTS_BASE/${RUN_NAME}-${RUN_TIMESTAMP}"
mkdir -p "$RUN_DIR"

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------
echo ""
echo -e "${BOLD}${BLUE}================================================${NC}"
echo -e "${BOLD}${BLUE}       A/B Performance Test${NC}"
echo -e "${BOLD}${BLUE}================================================${NC}"
echo ""
echo "  Run name:   ${RUN_NAME}"
echo "  Variant A:  ${TMP_A_NAME} ($(basename "$CONFIG_A"))"
echo "  Variant B:  ${TMP_B_NAME} ($(basename "$CONFIG_B"))"
echo "  Output dir: ${RUN_DIR}"
if [ -n "$SCENARIO_OVERRIDE" ]; then
    echo "  Scenario:   ${SCENARIO_OVERRIDE} (override)"
fi
if [ -n "$TPS_OVERRIDE" ]; then
    echo "  TPS:        ${TPS_OVERRIDE} (override)"
fi
if [ -n "$DURATION_OVERRIDE" ]; then
    echo "  Duration:   ${DURATION_OVERRIDE} (override)"
fi
echo ""

# ---------------------------------------------------------------------------
# Build Docker images (unless skipped)
# ---------------------------------------------------------------------------
if [ "$SKIP_BUILD" = false ]; then
    log_step "Building Docker Images"
    cd "$PROJECT_ROOT"
    log "Running mvn clean package -DskipTests..."
    mvn clean package -DskipTests -q 2>&1 || die "Maven build failed"
    log "Build complete."
fi

# ---------------------------------------------------------------------------
# Run variants
# ---------------------------------------------------------------------------
TOTAL_START=$(date +%s)

run_variant "A" "$CONFIG_A" "$RUN_DIR"
run_variant "B" "$CONFIG_B" "$RUN_DIR"

TOTAL_END=$(date +%s)
TOTAL_ELAPSED=$((TOTAL_END - TOTAL_START))

# ---------------------------------------------------------------------------
# Generate comparison report
# ---------------------------------------------------------------------------
log_step "Generating Comparison Report"

REPORT_FILE="$RUN_DIR/report.md"
"$SCRIPT_DIR/ab-compare.sh" \
    "$RUN_DIR/manifest-a.json" \
    "$RUN_DIR/manifest-b.json" \
    --output "$REPORT_FILE"

# Try optional chart generation
CHARTS_DIR="$RUN_DIR/charts"
if command -v python3 > /dev/null 2>&1; then
    log "Generating charts..."
    python3 "$SCRIPT_DIR/ab-chart.py" \
        "$RUN_DIR/variant-a-results.json" \
        "$RUN_DIR/variant-b-results.json" \
        --label-a "$TMP_A_NAME" \
        --label-b "$TMP_B_NAME" \
        --output-dir "$CHARTS_DIR" 2>&1 || {
        echo -e "${YELLOW}Chart generation skipped (matplotlib not available)${NC}"
    }
fi

# Copy report to docs/performance/
DOCS_REPORT="$PROJECT_ROOT/docs/performance/ab-${RUN_NAME}-${RUN_TIMESTAMP}.md"
cp "$REPORT_FILE" "$DOCS_REPORT"
log "Report copied to: docs/performance/$(basename "$DOCS_REPORT")"

# ---------------------------------------------------------------------------
# Final summary
# ---------------------------------------------------------------------------
echo ""
echo -e "${GREEN}${BOLD}================================================${NC}"
echo -e "${GREEN}${BOLD}       A/B Test Complete${NC}"
echo -e "${GREEN}${BOLD}================================================${NC}"
echo ""
echo "  Total time:    $(( TOTAL_ELAPSED / 60 ))m $(( TOTAL_ELAPSED % 60 ))s"
echo "  Run directory: $RUN_DIR"
echo "  Report:        $REPORT_FILE"
echo "  Docs report:   $DOCS_REPORT"
if [ -d "$CHARTS_DIR" ]; then
    echo "  Charts:        $CHARTS_DIR/"
fi
echo ""
echo "  Files:"
for f in "$RUN_DIR"/*; do
    if [ -f "$f" ]; then
        local_size=$(ls -lh "$f" | awk '{print $5}')
        echo "    $(basename "$f")  ($local_size)"
    fi
done
echo ""
echo "  To re-compare later:"
echo "    $0 --compare-only $RUN_DIR/manifest-a.json $RUN_DIR/manifest-b.json"
echo ""
