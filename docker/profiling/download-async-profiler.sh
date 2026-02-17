#!/bin/bash
#
# download-async-profiler.sh - Downloads async-profiler for container profiling
#
# Downloads async-profiler v4.3 (Linux build) to docker/profiling/async-profiler/.
# Auto-detects host architecture (arm64/x86_64) and downloads the matching Linux
# binary, since containers run Linux regardless of host OS.
#
# Usage:
#   ./docker/profiling/download-async-profiler.sh
#
# Compatible with macOS bash 3.2.
#

set -e

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
VERSION="4.3"
BASE_URL="https://github.com/async-profiler/async-profiler/releases/download/v${VERSION}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALL_DIR="$SCRIPT_DIR/async-profiler"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# ---------------------------------------------------------------------------
# Detect architecture
# ---------------------------------------------------------------------------
ARCH=$(uname -m)
case "$ARCH" in
    arm64|aarch64)
        PLATFORM="linux-arm64"
        ;;
    x86_64|amd64)
        PLATFORM="linux-x64"
        ;;
    *)
        echo -e "${RED}Unsupported architecture: $ARCH${NC}"
        echo "async-profiler supports linux-arm64 and linux-x64."
        exit 1
        ;;
esac

TARBALL="async-profiler-${VERSION}-${PLATFORM}.tar.gz"
DOWNLOAD_URL="${BASE_URL}/${TARBALL}"

echo -e "${CYAN}async-profiler downloader${NC}"
echo ""
echo "  Version:      $VERSION"
echo "  Architecture: $ARCH -> $PLATFORM"
echo "  Install to:   $INSTALL_DIR"
echo ""

# ---------------------------------------------------------------------------
# Check if already downloaded
# ---------------------------------------------------------------------------
if [ -f "$INSTALL_DIR/bin/asprof" ]; then
    EXISTING_VERSION=$("$INSTALL_DIR/bin/asprof" --version 2>/dev/null || echo "unknown")
    echo -e "${YELLOW}async-profiler already exists: $EXISTING_VERSION${NC}"
    echo "  Delete $INSTALL_DIR to re-download."
    exit 0
fi

# ---------------------------------------------------------------------------
# Check prerequisites
# ---------------------------------------------------------------------------
if ! command -v curl > /dev/null 2>&1; then
    echo -e "${RED}Error: curl is required${NC}"
    exit 1
fi

if ! command -v tar > /dev/null 2>&1; then
    echo -e "${RED}Error: tar is required${NC}"
    exit 1
fi

# ---------------------------------------------------------------------------
# Download
# ---------------------------------------------------------------------------
TMPDIR=$(mktemp -d)
TMPFILE="$TMPDIR/$TARBALL"

echo -e "${YELLOW}Downloading $TARBALL ...${NC}"
echo "  URL: $DOWNLOAD_URL"

HTTP_CODE=$(curl -L -w "%{http_code}" -o "$TMPFILE" "$DOWNLOAD_URL" 2>/dev/null)

if [ "$HTTP_CODE" != "200" ]; then
    echo -e "${RED}Download failed (HTTP $HTTP_CODE)${NC}"
    echo "  URL: $DOWNLOAD_URL"
    echo "  Check that version $VERSION exists at:"
    echo "  https://github.com/async-profiler/async-profiler/releases"
    rm -rf "$TMPDIR"
    exit 1
fi

FILE_SIZE=$(ls -lh "$TMPFILE" | awk '{print $5}')
echo -e "  Downloaded: ${GREEN}$FILE_SIZE${NC}"

# ---------------------------------------------------------------------------
# Extract
# ---------------------------------------------------------------------------
echo -e "${YELLOW}Extracting to $INSTALL_DIR ...${NC}"

mkdir -p "$INSTALL_DIR"

# async-profiler tarballs extract to a directory like async-profiler-4.3-linux-arm64/
# We strip the top-level directory to get a clean install
tar xzf "$TMPFILE" -C "$INSTALL_DIR" --strip-components=1

# Cleanup temp files
rm -rf "$TMPDIR"

# ---------------------------------------------------------------------------
# Verify installation
# ---------------------------------------------------------------------------
if [ ! -f "$INSTALL_DIR/bin/asprof" ]; then
    echo -e "${RED}Error: bin/asprof not found after extraction${NC}"
    echo "  Contents of $INSTALL_DIR:"
    ls -la "$INSTALL_DIR" 2>/dev/null || echo "  (directory empty or missing)"
    exit 1
fi

# Make sure asprof is executable
chmod +x "$INSTALL_DIR/bin/asprof"

echo ""
echo -e "${GREEN}async-profiler $VERSION installed successfully${NC}"
echo ""
echo "  Location:  $INSTALL_DIR"
echo "  Binary:    $INSTALL_DIR/bin/asprof"
echo ""
echo "  Contents:"
ls "$INSTALL_DIR/" | while read entry; do
    echo "    $entry"
done
echo ""
echo "  Next steps:"
echo "    1. Start services with profiling:"
echo "       cd docker && docker compose -f docker-compose.yml -f docker-compose-profiling.yml up -d"
echo "    2. Run a profiling session:"
echo "       ./scripts/perf/profile-service.sh --service order-service --event cpu --duration 60"
