#!/bin/bash
set -e

# Native Distribution Packaging Script
VERSION="${1:-0.1.0}"
PLATFORM="${2:-macos-arm64}"

echo "Packaging Karandash Distribution v$VERSION for $PLATFORM"

# Determine native target
case "$PLATFORM" in
  macos-arm64)
    TARGET="MacosArm64"
    ;;
  macos-x64)
    TARGET="MacosX64"
    ;;
  linux-x64)
    TARGET="LinuxX64"
    ;;
  windows-x64)
    TARGET="MingwX64"
    ;;
  *)
    echo "Unknown platform: $PLATFORM"
    echo "Supported: macos-arm64, macos-x64, linux-x64, windows-x64"
    exit 1
    ;;
esac

# Convert TARGET to lowercase for build directory
TARGET_LOWER=$(echo "$TARGET" | tr '[:upper:]' '[:lower:]')

DIST_NAME="karandash-${VERSION}-${PLATFORM}"
DIST_DIR="build/distributions/${DIST_NAME}"

echo "Building for target: $TARGET (directory: $TARGET_LOWER)"

# Clean and create distribution directory
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"/bin

# Build native binary
echo "Building native binary..."
./gradlew ":cli:linkReleaseExecutable${TARGET}" --console=plain -q

# Copy native binary
BINARY_PATH="cli/build/bin/${TARGET_LOWER}/releaseExecutable/cli.kexe"
if [ -f "$BINARY_PATH" ]; then
  cp "$BINARY_PATH" "$DIST_DIR/bin/karandash"
  chmod +x "$DIST_DIR/bin/karandash"
  echo "  Copied native binary"
else
  echo "  Binary not found: $BINARY_PATH"
  exit 1
fi

# Create tarball
echo "Creating tarball..."
cd build/distributions
tar -czf "${DIST_NAME}.tar.gz" "${DIST_NAME}"
cd ../..

# Summary
echo ""
echo "Distribution created successfully!"
echo ""
echo "Distribution: build/distributions/${DIST_NAME}.tar.gz"
echo "Size: $(du -h "build/distributions/${DIST_NAME}.tar.gz" | cut -f1)"
echo ""
echo "Test with:"
echo "  tar -xzf build/distributions/${DIST_NAME}.tar.gz"
echo "  build/distributions/${DIST_NAME}/bin/karandash --version"
