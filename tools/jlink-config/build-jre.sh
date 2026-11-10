#!/bin/bash
set -e

# Build custom JRE with jlink for Karandash
# This creates a minimal JRE with only required modules (~60-80MB vs ~180MB full JRE)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$PROJECT_DIR/build/custom-jre"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}Building custom JRE with jlink...${NC}"

# Detect Java home
if [ -z "$JAVA_HOME" ]; then
    # Try to detect JAVA_HOME from java command
    JAVA_CMD=$(which java 2>/dev/null)
    if [ -n "$JAVA_CMD" ]; then
        # Resolve symlinks and get the real path
        JAVA_REAL=$(readlink -f "$JAVA_CMD" 2>/dev/null || realpath "$JAVA_CMD" 2>/dev/null || echo "$JAVA_CMD")
        # Go up two directories from bin/java to get JAVA_HOME
        JAVA_HOME=$(cd "$(dirname "$JAVA_REAL")/.." && pwd)
        export JAVA_HOME
        echo "Detected JAVA_HOME: $JAVA_HOME"
    else
        echo "Error: JAVA_HOME not set and java command not found in PATH"
        exit 1
    fi
fi

echo "Using Java: $JAVA_HOME"
echo "Java version:"
"$JAVA_HOME/bin/java" -version

# Clean output directory (jlink will create it)
rm -rf "$OUTPUT_DIR"

# Read required modules
MODULES=$(cat "$SCRIPT_DIR/required-modules.txt" | tr '\n' ',' | sed 's/,$//')

echo -e "${BLUE}Required modules: ${NC}$MODULES"

# Build custom JRE
"$JAVA_HOME/bin/jlink" \
    --module-path "$JAVA_HOME/jmods" \
    --add-modules "$MODULES" \
    --output "$OUTPUT_DIR" \
    --compress=2 \
    --no-header-files \
    --no-man-pages \
    --strip-debug

# Calculate size
JRE_SIZE=$(du -sh "$OUTPUT_DIR" | cut -f1)

echo -e "${GREEN}Custom JRE built successfully!${NC}"
echo "Location: $OUTPUT_DIR"
echo "Size: $JRE_SIZE"
echo ""
echo "Modules included:"
"$OUTPUT_DIR/bin/java" --list-modules

# Test the custom JRE
echo -e "\n${BLUE}Testing custom JRE...${NC}"
"$OUTPUT_DIR/bin/java" -version

echo -e "${GREEN}Custom JRE is ready!${NC}"
