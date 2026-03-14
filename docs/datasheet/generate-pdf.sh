#!/bin/bash
# Generate PDFs from the HTML datasheets (SA and PM versions)
#
# Requirements (pick one):
#   Option A: Google Chrome / Chromium (recommended - best CSS support)
#   Option B: wkhtmltopdf (brew install wkhtmltopdf)
#
# Usage:
#   ./generate-pdf.sh              # Generate both SA and PM PDFs
#   ./generate-pdf.sh sa           # Generate SA version only
#   ./generate-pdf.sh pm           # Generate PM version only
#   ./generate-pdf.sh --chrome     # Force Chrome (both versions)
#   ./generate-pdf.sh --wkhtmltopdf # Force wkhtmltopdf (both versions)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOL=""
TARGETS=""

# Detect Chrome on macOS
find_chrome() {
    if [ -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ]; then
        echo "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    elif command -v google-chrome > /dev/null 2>&1; then
        echo "google-chrome"
    elif command -v chromium-browser > /dev/null 2>&1; then
        echo "chromium-browser"
    elif command -v chromium > /dev/null 2>&1; then
        echo "chromium"
    else
        echo ""
    fi
}

generate_with_chrome() {
    local input="$1"
    local output="$2"
    CHROME="$(find_chrome)"
    if [ -z "$CHROME" ]; then
        echo "ERROR: Chrome/Chromium not found."
        echo "  Install Google Chrome or use: ./generate-pdf.sh --wkhtmltopdf"
        exit 1
    fi

    echo "Generating PDF with Chrome: $(basename "$output")"
    "$CHROME" \
        --headless \
        --disable-gpu \
        --no-sandbox \
        --print-to-pdf="$output" \
        --print-to-pdf-no-header \
        --no-margins \
        "file://$input"

    echo "  Done: $output"
}

generate_with_wkhtmltopdf() {
    local input="$1"
    local output="$2"
    if ! command -v wkhtmltopdf > /dev/null 2>&1; then
        echo "ERROR: wkhtmltopdf not found."
        echo "  Install with: brew install wkhtmltopdf"
        exit 1
    fi

    echo "Generating PDF with wkhtmltopdf: $(basename "$output")"
    wkhtmltopdf \
        --page-size Letter \
        --no-outline \
        --margin-top 0 \
        --margin-bottom 0 \
        --margin-left 0 \
        --margin-right 0 \
        --enable-local-file-access \
        --print-media-type \
        "$input" \
        "$output"

    echo "  Done: $output"
}

generate_one() {
    local input="$1"
    local output="$2"

    if [ ! -f "$input" ]; then
        echo "WARNING: $input not found, skipping."
        return
    fi

    case "$TOOL" in
        chrome)
            generate_with_chrome "$input" "$output"
            ;;
        wkhtmltopdf)
            generate_with_wkhtmltopdf "$input" "$output"
            ;;
        *)
            if [ -n "$(find_chrome)" ]; then
                generate_with_chrome "$input" "$output"
            elif command -v wkhtmltopdf > /dev/null 2>&1; then
                generate_with_wkhtmltopdf "$input" "$output"
            else
                echo "ERROR: No PDF generator found."
                echo ""
                echo "Install one of:"
                echo "  - Google Chrome (recommended)"
                echo "  - wkhtmltopdf: brew install wkhtmltopdf"
                exit 1
            fi
            ;;
    esac
}

# Parse arguments
for arg in "$@"; do
    case "$arg" in
        --chrome)
            TOOL="chrome"
            ;;
        --wkhtmltopdf)
            TOOL="wkhtmltopdf"
            ;;
        sa|SA)
            TARGETS="${TARGETS} sa"
            ;;
        pm|PM)
            TARGETS="${TARGETS} pm"
            ;;
        -h|--help)
            echo "Usage: $0 [sa|pm] [--chrome|--wkhtmltopdf]"
            echo ""
            echo "  sa             Generate SA (Solution Architect) version only"
            echo "  pm             Generate PM (Product Marketing) version only"
            echo "  --chrome       Force Chrome for PDF generation"
            echo "  --wkhtmltopdf  Force wkhtmltopdf for PDF generation"
            echo ""
            echo "  No arguments generates both versions."
            exit 0
            ;;
    esac
done

# Default: both versions
if [ -z "$TARGETS" ]; then
    TARGETS="sa pm"
fi

# Generate requested versions
for target in $TARGETS; do
    case "$target" in
        sa)
            generate_one \
                "$SCRIPT_DIR/datasheet-sa.html" \
                "$SCRIPT_DIR/Hazelcast-Microservices-Framework-SA.pdf"
            ;;
        pm)
            generate_one \
                "$SCRIPT_DIR/datasheet-pm.html" \
                "$SCRIPT_DIR/Hazelcast-Microservices-Framework-PM.pdf"
            ;;
    esac
done

echo ""
echo "All done."
