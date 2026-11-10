#!/bin/bash
set -e

# Build macOS DMG installer with jpackage
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

echo -e "${BLUE}Building macOS DMG installer with jpackage...${NC}"

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

# Get version from build.gradle.kts
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

# Detect jpackage location (must use JDK, not custom JRE)
if [ -z "$JAVA_HOME" ]; then
    echo -e "${RED}Error: JAVA_HOME not set${NC}"
    exit 1
fi

if [ ! -f "$JAVA_HOME/bin/jpackage" ]; then
    echo -e "${RED}Error: jpackage not found at $JAVA_HOME/bin/jpackage${NC}"
    echo "jpackage requires JDK 14+ (not JRE)"
    exit 1
fi

# Build DMG installer
"$JAVA_HOME/bin/jpackage" \
    --type dmg \
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
    --mac-package-name Karandash \
    --mac-package-identifier io.karandash.cli

# Calculate size
if [ -f "$OUTPUT_DIR/Karandash-${PACKAGE_VERSION}.dmg" ]; then
    DMG_SIZE=$(du -sh "$OUTPUT_DIR/Karandash-${PACKAGE_VERSION}.dmg" | cut -f1)
    echo -e "${GREEN}macOS DMG installer built successfully!${NC}"
    echo "Location: $OUTPUT_DIR/Karandash-${PACKAGE_VERSION}.dmg"
    echo "Size: $DMG_SIZE"
    echo ""
    echo "To install: Open the DMG and drag Karandash to Applications"
else
    echo -e "${RED}Error: DMG file not created${NC}"
    exit 1
fi
