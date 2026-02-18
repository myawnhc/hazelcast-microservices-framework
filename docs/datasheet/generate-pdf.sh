#!/bin/bash
# Generate PDF from the HTML datasheet
#
# Requirements (pick one):
#   Option A: Google Chrome / Chromium (recommended - best CSS support)
#   Option B: wkhtmltopdf (brew install wkhtmltopdf)
#
# Usage:
#   ./generate-pdf.sh              # Auto-detect available tool
#   ./generate-pdf.sh --chrome     # Force Chrome
#   ./generate-pdf.sh --wkhtmltopdf # Force wkhtmltopdf

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INPUT="$SCRIPT_DIR/datasheet.html"
OUTPUT="$SCRIPT_DIR/Hazelcast-Microservices-Framework-Datasheet.pdf"

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
    CHROME="$(find_chrome)"
    if [ -z "$CHROME" ]; then
        echo "ERROR: Chrome/Chromium not found."
        echo "  Install Google Chrome or use: ./generate-pdf.sh --wkhtmltopdf"
        exit 1
    fi

    echo "Generating PDF with Chrome..."
    "$CHROME" \
        --headless \
        --disable-gpu \
        --no-sandbox \
        --print-to-pdf="$OUTPUT" \
        --print-to-pdf-no-header \
        --no-margins \
        "file://$INPUT"

    echo "Done: $OUTPUT"
}

generate_with_wkhtmltopdf() {
    if ! command -v wkhtmltopdf > /dev/null 2>&1; then
        echo "ERROR: wkhtmltopdf not found."
        echo "  Install with: brew install wkhtmltopdf"
        exit 1
    fi

    echo "Generating PDF with wkhtmltopdf..."
    wkhtmltopdf \
        --page-size Letter \
        --no-outline \
        --margin-top 0 \
        --margin-bottom 0 \
        --margin-left 0 \
        --margin-right 0 \
        --enable-local-file-access \
        --print-media-type \
        "$INPUT" \
        "$OUTPUT"

    echo "Done: $OUTPUT"
}

# Main
case "${1:-}" in
    --chrome)
        generate_with_chrome
        ;;
    --wkhtmltopdf)
        generate_with_wkhtmltopdf
        ;;
    *)
        # Auto-detect: prefer Chrome
        if [ -n "$(find_chrome)" ]; then
            generate_with_chrome
        elif command -v wkhtmltopdf > /dev/null 2>&1; then
            generate_with_wkhtmltopdf
        else
            echo "ERROR: No PDF generator found."
            echo ""
            echo "Install one of:"
            echo "  - Google Chrome (recommended)"
            echo "  - wkhtmltopdf: brew install wkhtmltopdf"
            echo ""
            echo "Or open the HTML directly in Chrome and use Print > Save as PDF:"
            echo "  open '$INPUT'"
            exit 1
        fi
        ;;
esac
