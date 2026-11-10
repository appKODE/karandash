#!/bin/bash
set -e

# Build Linux DEB/RPM installers with jpackage
# Creates professional installers that bundle the custom JRE

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

# Check if running on Linux
if [[ "$OSTYPE" != "linux-gnu"* ]]; then
    echo -e "${RED}Error: This script must run on Linux${NC}"
    exit 1
fi

echo -e "${BLUE}Building Linux installers with jpackage...${NC}"

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

# Build DEB installer
echo -e "\n${BLUE}Building DEB package...${NC}"
"$JAVA_HOME/bin/jpackage" \
    --type deb \
    --input "$PROJECT_DIR/cli/build/libs" \
    --name karandash \
    --main-jar karandash.jar \
    --main-class io.karandash.cli.MainKt \
    --runtime-image "$CUSTOM_JRE" \
    --app-version "$PACKAGE_VERSION" \
    --vendor "Karandash" \
    --description "Merge Request Validation Tool" \
    --copyright "Copyright 2024 Karandash" \
    --dest "$OUTPUT_DIR" \
    --linux-package-name karandash \
    --linux-app-category utils \
    --linux-shortcut

# Build RPM installer
echo -e "\n${BLUE}Building RPM package...${NC}"
"$JAVA_HOME/bin/jpackage" \
    --type rpm \
    --input "$PROJECT_DIR/cli/build/libs" \
    --name karandash \
    --main-jar karandash.jar \
    --main-class io.karandash.cli.MainKt \
    --runtime-image "$CUSTOM_JRE" \
    --app-version "$PACKAGE_VERSION" \
    --vendor "Karandash" \
    --description "Merge Request Validation Tool" \
    --copyright "Copyright 2024 Karandash" \
    --dest "$OUTPUT_DIR" \
    --linux-package-name karandash \
    --linux-app-category utils \
    --linux-shortcut

# Report results
echo -e "\n${GREEN}Linux installers built successfully!${NC}"
echo "Output directory: $OUTPUT_DIR"
echo ""

for file in "$OUTPUT_DIR"/*.{deb,rpm}; do
    if [ -f "$file" ]; then
        SIZE=$(du -sh "$file" | cut -f1)
        echo "  $(basename "$file"): $SIZE"
    fi
done

echo ""
echo "To install DEB: sudo dpkg -i $OUTPUT_DIR/karandash_${PACKAGE_VERSION}_amd64.deb"
echo "To install RPM: sudo rpm -i $OUTPUT_DIR/karandash-${PACKAGE_VERSION}.rpm"
