#!/bin/bash
#
# run-cluster-benchmarks.sh - Cluster JMH Benchmark Orchestrator
#
# Verifies the Docker Compose Hazelcast cluster is healthy, builds the JMH
# benchmark JAR, and runs cluster-specific benchmarks (IMap, ITopic,
# EventJournal) against the live 3-node cluster.
#
# Compatible with macOS bash 3.2 (no bash 4+ features).
#
# Usage:
#   ./scripts/perf/run-cluster-benchmarks.sh [options]
#
# Options:
#   --skip-build         Skip Maven build (reuse existing benchmarks.jar)
#   --quick              Quick mode: 1 fork, 2 warmup, 3 measurement iterations
#   --benchmark NAME     Run only benchmarks matching NAME (regex)
#   --help               Show this help message
#

set -e

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"
BENCHMARK_JAR="$PROJECT_ROOT/framework-core/target/benchmarks.jar"

SKIP_BUILD=false
QUICK_MODE=false
DOCKER_MODE=false
BENCHMARK_FILTER="Cluster.*"

# Hazelcast cluster REST health endpoints (bash 3.2 compatible: key:value pairs)
HZ_NODES="hazelcast-1:5701 hazelcast-2:5702 hazelcast-3:5703"

# Health check settings
HEALTH_TIMEOUT=30
HEALTH_INTERVAL=3

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# ---------------------------------------------------------------------------
# Functions
# ---------------------------------------------------------------------------

print_usage() {
    echo "Usage: $0 [options]"
    echo ""
    echo "Runs JMH cluster benchmarks against the Docker Compose Hazelcast cluster."
    echo ""
    echo "Prerequisites:"
    echo "  - Docker Compose cluster must be running (cd docker && docker compose up -d)"
    echo "  - At least the 3 Hazelcast nodes must be healthy"
    echo ""
    echo "Options:"
    echo "  --skip-build         Skip Maven build (reuse existing benchmarks.jar)"
    echo "  --quick              Quick mode: 1 fork, 2 warmup, 3 measurement iters"
    echo "  --docker             Run JMH inside Docker (smart routing, production-accurate)"
    echo "  --benchmark NAME     Run only benchmarks matching NAME (regex)"
    echo "  --help               Show this help message"
    echo ""
    echo "Modes:"
    echo "  Default (host):  Runs on host with smart routing disabled (single-gateway hop)."
    echo "                   Numbers are ~15-25% higher than in-Docker due to extra hop."
    echo "  --docker:        Runs JMH inside a container on the Docker network with smart"
    echo "                   routing enabled. Production-accurate measurements."
    echo ""
    echo "Examples:"
    echo "  $0                              # Full run from host (smart routing off)"
    echo "  $0 --docker                     # Full run inside Docker (smart routing on)"
    echo "  $0 --quick                      # Quick smoke test"
    echo "  $0 --benchmark ClusterIMap      # Only IMap benchmarks"
    echo "  $0 --skip-build --quick         # Re-run without rebuild"
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

# Check if all 3 Hazelcast nodes are healthy
check_cluster_health() {
    log "Checking Hazelcast cluster health (timeout: ${HEALTH_TIMEOUT}s)..."

    local elapsed=0

    while [ $elapsed -lt $HEALTH_TIMEOUT ]; do
        local all_healthy=true

        for node_info in $HZ_NODES; do
            local node_name="${node_info%%:*}"
            local node_port="${node_info#*:}"
            local url="http://localhost:${node_port}/hazelcast/health/ready"

            local status
            status=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")

            if [ "$status" != "200" ]; then
                all_healthy=false
                break
            fi
        done

        if [ "$all_healthy" = true ]; then
            log "All 3 Hazelcast nodes are healthy!"

            # Print cluster size from first node
            local cluster_state
            cluster_state=$(curl -s "http://localhost:5701/hazelcast/health" 2>/dev/null || echo "unavailable")
            echo "  Cluster health: $cluster_state"
            return 0
        fi

        sleep $HEALTH_INTERVAL
        elapsed=$((elapsed + HEALTH_INTERVAL))
    done

    echo ""
    echo -e "${RED}Cluster health check failed. Node status:${NC}"
    for node_info in $HZ_NODES; do
        local node_name="${node_info%%:*}"
        local node_port="${node_info#*:}"
        local url="http://localhost:${node_port}/hazelcast/health/ready"
        local status
        status=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")
        if [ "$status" = "200" ]; then
            echo -e "  ${GREEN}OK${NC}:   ${node_name} (port ${node_port})"
        else
            echo -e "  ${RED}DOWN${NC}: ${node_name} (port ${node_port}) — HTTP ${status}"
        fi
    done
    echo ""
    die "Docker Compose Hazelcast cluster is not healthy. Start it with: cd docker && docker compose up -d"
}

# ---------------------------------------------------------------------------
# Parse arguments (bash 3.2 compatible)
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --quick)
            QUICK_MODE=true
            shift
            ;;
        --docker)
            DOCKER_MODE=true
            shift
            ;;
        --benchmark)
            BENCHMARK_FILTER="$2"
            shift 2
            ;;
        --help|-h)
            print_usage
            exit 0
            ;;
        *)
            die "Unknown option: $1"
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------
echo ""
echo -e "${BOLD}${BLUE}================================================${NC}"
echo -e "${BOLD}${BLUE}       Hazelcast Cluster Benchmarks (JMH)${NC}"
echo -e "${BOLD}${BLUE}================================================${NC}"
echo ""
echo "  Filter:      ${BENCHMARK_FILTER}"
echo "  Quick mode:  ${QUICK_MODE}"
echo "  Docker mode: ${DOCKER_MODE}"
echo "  Skip build:  ${SKIP_BUILD}"
if [ "$DOCKER_MODE" = true ]; then
    echo ""
    echo -e "  ${GREEN}Smart routing ENABLED${NC} (running inside Docker network)"
else
    echo ""
    echo -e "  ${YELLOW}Smart routing DISABLED${NC} (host mode — single-gateway hop)"
fi
echo ""

# ---------------------------------------------------------------------------
# Step 1: Verify cluster health
# ---------------------------------------------------------------------------
log_step "Step 1: Verify Cluster Health"
check_cluster_health

# ---------------------------------------------------------------------------
# Step 2: Build benchmark JAR
# ---------------------------------------------------------------------------
if [ "$SKIP_BUILD" = false ]; then
    log_step "Step 2: Build Benchmark JAR"
    log "Running: mvn clean package -pl framework-core -Pbenchmark -DskipTests"
    cd "$PROJECT_ROOT"
    mvn clean package -pl framework-core -Pbenchmark -DskipTests 2>&1 || die "Maven build failed"
    log "Build complete."
else
    log_step "Step 2: Build (skipped)"
    log "Using existing JAR: ${BENCHMARK_JAR}"
fi

# Verify JAR exists
if [ ! -f "$BENCHMARK_JAR" ]; then
    die "Benchmark JAR not found at: ${BENCHMARK_JAR}\nRun without --skip-build to build it."
fi

JAR_SIZE=$(ls -lh "$BENCHMARK_JAR" | awk '{print $5}')
log "Benchmark JAR: ${BENCHMARK_JAR} (${JAR_SIZE})"

# ---------------------------------------------------------------------------
# Step 3: Run benchmarks
# ---------------------------------------------------------------------------
log_step "Step 3: Run JMH Benchmarks"

mkdir -p "$RESULTS_DIR"

# Build JMH args
JMH_ARGS=""

if [ "$QUICK_MODE" = true ]; then
    JMH_ARGS="-f 1 -wi 2 -i 3 -w 2 -r 3"
    log "Quick mode: 1 fork, 2 warmup (2s each), 3 measurement (3s each)"
fi

# Result file
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
MODE_LABEL="host"
if [ "$DOCKER_MODE" = true ]; then
    MODE_LABEL="docker"
fi
RESULT_JSON="$RESULTS_DIR/cluster-benchmarks-${MODE_LABEL}-${TIMESTAMP}.json"

log "Running benchmarks matching: ${BENCHMARK_FILTER}"
log "Results will be saved to: ${RESULT_JSON}"
echo ""

cd "$PROJECT_ROOT"

if [ "$DOCKER_MODE" = true ]; then
    # Run JMH inside a Docker container on the ecommerce network.
    # Smart routing works naturally because Docker-internal hostnames resolve.
    # The -Dbenchmark.docker=true system property switches ClusterBenchmarkState
    # to use hazelcast-1:5701, hazelcast-2:5701, hazelcast-3:5701.

    # Find the Docker network name (prefix varies by directory name)
    DOCKER_NETWORK=$(docker network ls --format "{{.Name}}" 2>/dev/null | grep "ecommerce" | head -1)
    if [ -z "$DOCKER_NETWORK" ]; then
        die "Could not find ecommerce Docker network. Is Docker Compose running?"
    fi
    log "Docker network: ${DOCKER_NETWORK}"

    # JMH forks child JVM processes, so we pass -Dbenchmark.docker via jvmArgs.
    # The fork JVM args in @Fork annotations already set heap size; we add our
    # property via JMH's -jvmArgs flag which APPENDS to @Fork args.
    docker run --rm \
        --network "$DOCKER_NETWORK" \
        -v "$BENCHMARK_JAR:/app/benchmarks.jar:ro" \
        -v "$RESULTS_DIR:/app/results" \
        eclipse-temurin:24-jre \
        java -Dbenchmark.docker=true -jar /app/benchmarks.jar "$BENCHMARK_FILTER" \
            -rf json -rff "/app/results/cluster-benchmarks-${MODE_LABEL}-${TIMESTAMP}.json" \
            -jvmArgs "-Dbenchmark.docker=true" \
            $JMH_ARGS 2>&1

    JMH_EXIT_CODE=$?
else
    # Run JMH on host with smart routing disabled (single-gateway hop).
    java -jar "$BENCHMARK_JAR" "$BENCHMARK_FILTER" \
        -rf json -rff "$RESULT_JSON" \
        $JMH_ARGS 2>&1

    JMH_EXIT_CODE=$?
fi

# ---------------------------------------------------------------------------
# Step 4: Summary
# ---------------------------------------------------------------------------
echo ""
echo -e "${BOLD}${BLUE}================================================${NC}"
if [ $JMH_EXIT_CODE -eq 0 ]; then
    echo -e "${BOLD}${GREEN}       Benchmarks Complete${NC}"
else
    echo -e "${BOLD}${RED}       Benchmarks Failed (exit code: ${JMH_EXIT_CODE})${NC}"
fi
echo -e "${BOLD}${BLUE}================================================${NC}"
echo ""

if [ -f "$RESULT_JSON" ]; then
    RESULT_SIZE=$(ls -lh "$RESULT_JSON" | awk '{print $5}')
    echo "  Results: ${RESULT_JSON} (${RESULT_SIZE})"

    # Print summary if jq is available
    if command -v jq > /dev/null 2>&1; then
        echo ""
        echo "  Summary (avg us/op):"
        jq -r '.[] | "    \(.benchmark | split(".") | last): \(.primaryMetric.score | . * 100 | round / 100) ± \(.primaryMetric.scoreError | . * 100 | round / 100) us/op"' "$RESULT_JSON" 2>/dev/null || true
    fi
else
    echo "  No result file generated."
fi

echo ""
echo "  To re-run without rebuild: $0 --skip-build"
echo "  To run quick smoke test:   $0 --skip-build --quick"
echo ""

exit $JMH_EXIT_CODE
