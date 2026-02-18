#!/bin/bash
#
# capture-dashboards.sh - Capture Grafana dashboard screenshots as PNGs
#
# Uses the Grafana Image Renderer plugin (server-side) to render all 6
# dashboards to PNG files. Requires docker-compose-renderer.yml overlay.
#
# Compatible with macOS bash 3.2 (no bash 4+ features).
#
# Usage:
#   ./scripts/perf/capture-dashboards.sh [options]
#
# Options:
#   --output-dir DIR   Directory for PNGs (default: ./screenshots)
#   --from EPOCH_MS    Start time in epoch milliseconds (default: now-30m)
#   --to EPOCH_MS      End time in epoch milliseconds (default: now)
#   --label LABEL      Label prefix for filenames (default: "capture")
#   --width WIDTH      Image width in pixels (default: 1600)
#   --height HEIGHT    Image height in pixels (default: 900)
#   --grafana-url URL  Grafana base URL (default: http://localhost:3000)
#   --help             Show this help message
#

set -e

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
OUTPUT_DIR="./screenshots"
FROM_MS=""
TO_MS=""
LABEL="capture"
WIDTH=1600
HEIGHT=900
GRAFANA_URL="http://localhost:3000"
GRAFANA_USER="admin"
GRAFANA_PASS="admin"

# Dashboard UIDs and slugs (space-separated uid:slug pairs, bash 3.2 compatible)
DASHBOARDS="performance-testing:performance-testing system-overview:system-overview event-flow:event-flow materialized-views:materialized-views saga-dashboard:saga-dashboard persistence-layer:persistence-layer"

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
    echo "Usage: $0 [options]"
    echo ""
    echo "Captures all 6 Grafana dashboards as PNG screenshots."
    echo ""
    echo "Options:"
    echo "  --output-dir DIR   Directory for PNGs (default: ./screenshots)"
    echo "  --from EPOCH_MS    Start time in epoch ms (default: now-30m)"
    echo "  --to EPOCH_MS      End time in epoch ms (default: now)"
    echo "  --label LABEL      Label prefix for filenames (default: capture)"
    echo "  --width WIDTH      Image width in pixels (default: 1600)"
    echo "  --height HEIGHT    Image height in pixels (default: 900)"
    echo "  --grafana-url URL  Grafana base URL (default: http://localhost:3000)"
    echo "  --help             Show this help message"
}

die() {
    echo -e "${RED}Error: $1${NC}" >&2
    exit 1
}

log() {
    echo -e "${CYAN}[$(date +%H:%M:%S)]${NC} $1"
}

# Get current time in epoch milliseconds (macOS compatible)
epoch_ms() {
    python3 -c "import time; print(int(time.time() * 1000))"
}

# ---------------------------------------------------------------------------
# Parse arguments (bash 3.2 compatible)
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --from)
            FROM_MS="$2"
            shift 2
            ;;
        --to)
            TO_MS="$2"
            shift 2
            ;;
        --label)
            LABEL="$2"
            shift 2
            ;;
        --width)
            WIDTH="$2"
            shift 2
            ;;
        --height)
            HEIGHT="$2"
            shift 2
            ;;
        --grafana-url)
            GRAFANA_URL="$2"
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
# Defaults for time range
# ---------------------------------------------------------------------------
if [ -z "$TO_MS" ]; then
    TO_MS=$(epoch_ms)
fi

if [ -z "$FROM_MS" ]; then
    # Default: 30 minutes before TO_MS
    FROM_MS=$((TO_MS - 1800000))
fi

# ---------------------------------------------------------------------------
# Validate prerequisites
# ---------------------------------------------------------------------------
if ! command -v curl > /dev/null 2>&1; then
    die "curl is required but not installed."
fi

if ! command -v python3 > /dev/null 2>&1; then
    die "python3 is required for epoch_ms calculation."
fi

# Quick check that Grafana is responding
GRAFANA_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${GRAFANA_URL}/api/health" 2>/dev/null || echo "000")
if [ "$GRAFANA_STATUS" != "200" ]; then
    die "Grafana is not accessible at ${GRAFANA_URL} (HTTP ${GRAFANA_STATUS}). Is it running?"
fi

# ---------------------------------------------------------------------------
# Create output directory
# ---------------------------------------------------------------------------
mkdir -p "$OUTPUT_DIR"

# ---------------------------------------------------------------------------
# Capture dashboards
# ---------------------------------------------------------------------------
log "Capturing dashboards to: ${OUTPUT_DIR}/"
log "  Time range: ${FROM_MS} - ${TO_MS}"
log "  Label: ${LABEL}"
log "  Size: ${WIDTH}x${HEIGHT}"
echo ""

SUCCESS_COUNT=0
FAIL_COUNT=0

for entry in $DASHBOARDS; do
    uid="${entry%%:*}"
    slug="${entry#*:}"

    filename="${LABEL}-${slug}.png"
    filepath="${OUTPUT_DIR}/${filename}"

    # Build render URL with time range
    render_url="${GRAFANA_URL}/render/d/${uid}/${slug}?orgId=1&from=${FROM_MS}&to=${TO_MS}&width=${WIDTH}&height=${HEIGHT}&kiosk=1&tz=UTC"

    # Capture with curl (10s timeout per dashboard)
    http_code=$(curl -s -o "$filepath" -w "%{http_code}" \
        -u "${GRAFANA_USER}:${GRAFANA_PASS}" \
        --max-time 30 \
        "$render_url" 2>/dev/null || echo "000")

    if [ "$http_code" = "200" ] && [ -s "$filepath" ]; then
        file_size=$(ls -lh "$filepath" | awk '{print $5}')
        echo -e "  ${GREEN}OK${NC}  ${slug} (${file_size})"
        SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
    else
        echo -e "  ${RED}FAIL${NC}  ${slug} (HTTP ${http_code})"
        rm -f "$filepath"
        FAIL_COUNT=$((FAIL_COUNT + 1))
    fi
done

echo ""
log "Capture complete: ${SUCCESS_COUNT} succeeded, ${FAIL_COUNT} failed"

if [ $FAIL_COUNT -gt 0 ]; then
    echo ""
    echo -e "${YELLOW}Hint: Ensure docker-compose-renderer.yml is active:${NC}"
    echo "  cd docker && docker compose -f docker-compose.yml -f docker-compose-renderer.yml up -d"
    exit 1
fi

exit 0
