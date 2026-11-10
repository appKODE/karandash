#!/bin/bash
set -e

# Build Windows MSI installer with jpackage
# Creates a professional installer that bundles the custom JRE

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
CUSTOM_JRE="$PROJECT_DIR/build/custom-jre"
JAR_FILE="$PROJECT_DIR/cli/build/libs/karandash.jar"
OUTPUT_DIR="$PROJECT_DIR/build/installers"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Check if running on Windows (Git Bash or similar)
if [[ "$OSTYPE" != "msys" ]] && [[ "$OSTYPE" != "win32" ]]; then
    echo -e "${RED}Error: This script must run on Windows${NC}"
    exit 1
fi

echo -e "${BLUE}Building Windows MSI installer with jpackage...${NC}"

# Check prerequisites
if [ ! -d "$CUSTOM_JRE" ]; then
    echo -e "${RED}Error: Custom JRE not found at $CUSTOM_JRE${NC}"
    echo "Run build-jre.sh first"
    exit 1
fi

if [ ! -f "$JAR_FILE" ]; then
    echo -e "${RED}Error: JAR file not found at $JAR_FILE${NC}"
    echo "Run './gradlew :cli:shadowJar' first"
    exit 1
fi

# Get version from gradle.properties
VERSION=$(grep 'version = ' "$PROJECT_DIR/build.gradle.kts" | head -1 | sed 's/.*version = "\(.*\)".*/\1/' || echo "0.1.0")

# jpackage requires version to start with non-zero, transform 0.x.y to 1.x.y for packaging
if [[ "$VERSION" =~ ^0\. ]]; then
    PACKAGE_VERSION="${VERSION/0./1.}"
    echo "Note: Transforming version $VERSION to $PACKAGE_VERSION for jpackage compatibility"
else
    PACKAGE_VERSION="$VERSION"
fi

# Clean output directory
mkdir -p "$OUTPUT_DIR"

echo "JAR: $JAR_FILE"
echo "Custom JRE: $CUSTOM_JRE"
echo "Version: $VERSION"

# Build MSI installer
"$JAVA_HOME/bin/jpackage" \
    --type msi \
    --input "$PROJECT_DIR/cli/build/libs" \
    --name Karandash \
    --main-jar karandash.jar \
    --main-class io.karandash.cli.MainKt \
    --runtime-image "$CUSTOM_JRE" \
    --app-version "$PACKAGE_VERSION" \
    --vendor "Karandash" \
    --description "Merge Request Validation Tool" \
    --copyright "Copyright 2024 Karandash" \
    --dest "$OUTPUT_DIR" \
    --win-dir-chooser \
    --win-menu \
    --win-shortcut

# Calculate size
if [ -f "$OUTPUT_DIR/Karandash-${PACKAGE_VERSION}.msi" ]; then
    MSI_SIZE=$(du -sh "$OUTPUT_DIR/Karandash-${PACKAGE_VERSION}.msi" | cut -f1)
    echo -e "${GREEN}Windows MSI installer built successfully!${NC}"
    echo "Location: $OUTPUT_DIR/Karandash-${PACKAGE_VERSION}.msi"
    echo "Size: $MSI_SIZE"
    echo ""
    echo "To install: Double-click the MSI file"
else
    echo -e "${RED}Error: MSI file not created${NC}"
    exit 1
fi
