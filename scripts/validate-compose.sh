#!/bin/bash
#
# Validates all Docker Compose overlay combinations against the base file.
# Usage: ./scripts/validate-compose.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DOCKER_DIR="$SCRIPT_DIR/../docker"
BASE_FILE="$DOCKER_DIR/docker-compose.yml"

GREEN='\033[0;32m'
RED='\033[0;31m'
RESET='\033[0m'

OVERLAYS="
docker-compose-demo.yml
docker-compose-production.yml
docker-compose-perf-test.yml
docker-compose-profiling.yml
docker-compose-hd-memory.yml
docker-compose-renderer.yml
docker-compose-tpc.yml
docker-compose.clustered.yml
docker-compose.multi-replica.yml
"

pass_count=0
fail_count=0

validate() {
    label="$1"
    shift
    if docker compose "$@" config --quiet 2>/dev/null; then
        printf "${GREEN}PASS${RESET}  %s\n" "$label"
        pass_count=$((pass_count + 1))
    else
        printf "${RED}FAIL${RESET}  %s\n" "$label"
        fail_count=$((fail_count + 1))
    fi
}

echo "Validating Docker Compose configurations..."
echo ""

# Validate base file alone
validate "base (docker-compose.yml)" -f "$BASE_FILE"

# Validate each overlay with the base file
for overlay in $OVERLAYS; do
    overlay_path="$DOCKER_DIR/$overlay"
    if [ ! -f "$overlay_path" ]; then
        printf "${RED}FAIL${RESET}  %s (file not found)\n" "$overlay"
        fail_count=$((fail_count + 1))
        continue
    fi
    validate "base + $overlay" -f "$BASE_FILE" -f "$overlay_path"
done

echo ""
echo "Results: $pass_count passed, $fail_count failed"

if [ "$fail_count" -gt 0 ]; then
    exit 1
fi
