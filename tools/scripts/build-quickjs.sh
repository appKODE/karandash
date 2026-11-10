#!/bin/bash
set -e

# QuickJS Build Script for Kotlin/Native Integration
# Builds QuickJS static libraries for macOS (arm64/x64), Linux (x64), and Windows (x64)

QUICKJS_VERSION="2024-01-13"
QUICKJS_REPO="https://github.com/bellard/quickjs.git"
BUILD_DIR="build/quickjs"
LIBS_DIR="libs/quickjs"

echo "🔨 Building QuickJS for Kotlin/Native"
echo "Version: ${QUICKJS_VERSION}"
echo ""

# Detect platform
OS_TYPE="$(uname -s)"
ARCH_TYPE="$(uname -m)"

case "$OS_TYPE" in
  Darwin*)  PLATFORM="macos" ;;
  Linux*)   PLATFORM="linux" ;;
  MINGW*|MSYS*|CYGWIN*) PLATFORM="windows" ;;
  *) echo "Unsupported platform: $OS_TYPE"; exit 1 ;;
esac

echo "Detected platform: $PLATFORM ($ARCH_TYPE)"

# Create directories
mkdir -p "$BUILD_DIR"
mkdir -p "$LIBS_DIR/$PLATFORM"

# Clone QuickJS if not already present
if [ ! -d "$BUILD_DIR/quickjs" ]; then
  echo "📥 Cloning QuickJS repository..."
  git clone --depth 1 "$QUICKJS_REPO" "$BUILD_DIR/quickjs"
else
  echo "✓ QuickJS already cloned"
fi

cd "$BUILD_DIR/quickjs"

# Build QuickJS
echo "🔧 Building QuickJS..."

case "$PLATFORM" in
  macos)
    # macOS build (supports both arm64 and x64)
    if [ "$ARCH_TYPE" = "arm64" ]; then
      echo "Building for macOS ARM64..."
      make clean
      make libquickjs.a CFLAGS="-arch arm64 -O2"
      cp libquickjs.a "../../../$LIBS_DIR/$PLATFORM/libquickjs.a"
      cp quickjs.h quickjs-libc.h "../../../$LIBS_DIR/$PLATFORM/"
    else
      echo "Building for macOS x64..."
      make clean
      make libquickjs.a CFLAGS="-arch x86_64 -O2"
      cp libquickjs.a "../../../$LIBS_DIR/$PLATFORM/libquickjs.a"
      cp quickjs.h quickjs-libc.h "../../../$LIBS_DIR/$PLATFORM/"
    fi
    ;;

  linux)
    echo "Building for Linux x64..."
    make clean
    make libquickjs.a CFLAGS="-fPIC -O2"
    cp libquickjs.a "../../../$LIBS_DIR/$PLATFORM/libquickjs.a"
    cp quickjs.h quickjs-libc.h "../../../$LIBS_DIR/$PLATFORM/"
    ;;

  windows)
    echo "Building for Windows x64..."
    # Windows build using MinGW
    make clean
    make libquickjs.a CC=x86_64-w64-mingw32-gcc CFLAGS="-O2"
    cp libquickjs.a "../../../$LIBS_DIR/$PLATFORM/libquickjs.a"
    cp quickjs.h quickjs-libc.h "../../../$LIBS_DIR/$PLATFORM/"
    ;;
esac

cd ../../..

echo ""
echo "✅ QuickJS build complete!"
echo "Library location: $LIBS_DIR/$PLATFORM/libquickjs.a"
echo ""
echo "Headers copied:"
ls -lh "$LIBS_DIR/$PLATFORM/"*.h
echo ""
echo "To use in Kotlin/Native, ensure the cinterop definition points to:"
echo "  - Headers: $LIBS_DIR/$PLATFORM/"
echo "  - Library: $LIBS_DIR/$PLATFORM/libquickjs.a"
