#!/usr/bin/env bash
set -euo pipefail

# publish-plugin.sh - Build and prepare a native shared library for registry publishing.
#
# Builds the release shared library for a plugin module on the current (or all) platforms,
# computes its SHA-256 checksum, and prints a JSON registry entry snippet ready to paste
# into tools/registry/registry.json.
#
# Usage:
#   publish-plugin.sh <gradle-module-path> [options]
#
# Arguments:
#   <gradle-module-path>   Gradle project path, e.g. :plugins:plugins-core:rules:title-format
#
# Options:
#   --all-platforms        Build for all platforms (requires cross-compilation toolchain).
#                          Default: current host platform only.
#   --version <ver>        Override the plugin version (default: reads from project version).
#   --help                 Show this help message.
#
# Examples:
#   # Build for the current host platform:
#   ./tools/scripts/publish-plugin.sh :plugins:plugins-extended:outputs:email-output
#
#   # Build for all platforms (needs Docker cross-compilation setup):
#   ./tools/scripts/publish-plugin.sh :plugins:plugins-core:parsers:json --all-platforms
#
# Prerequisites:
#   - Run from the karandash/ repo root (where gradlew lives).
#   - The module must apply karandash.kmp-distributable-plugin or
#     karandash.kmp-distributable-ktor-plugin in its build.gradle.kts.
#   - Use verify-plugin-standalone.sh first to check the module is ready.
#
# Output binary locations (relative to module directory):
#   macOS ARM64:  build/bin/macosArm64/releaseShared/lib<name>.dylib
#   macOS x64:   build/bin/macosX64/releaseShared/lib<name>.dylib
#   Linux x64:   build/bin/linuxX64/releaseShared/lib<name>.so
#   Windows:     build/bin/mingwX64/releaseShared/<name>.dll
#
# Exit codes:
#   0 - success
#   1 - build or configuration error
#   2 - module not configured for native distribution

# ─── helpers ──────────────────────────────────────────────────────────────────

BOLD='\033[1m'
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RESET='\033[0m'

info()    { echo -e "${CYAN}  ${1}${RESET}"; }
success() { echo -e "${GREEN}  ${1}${RESET}"; }
warn()    { echo -e "${YELLOW}  WARNING: ${1}${RESET}"; }
error()   { echo -e "${RED}  ERROR: ${1}${RESET}" >&2; }
header()  { echo -e "\n${BOLD}${1}${RESET}"; }

usage() {
    sed -n '/^# Usage:/,/^# Exit codes:/p' "$0" | sed 's/^# \{0,2\}//'
    exit 0
}

# ─── argument parsing ─────────────────────────────────────────────────────────

MODULE_PATH=""
ALL_PLATFORMS=false
VERSION_OVERRIDE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --help|-h) usage ;;
        --all-platforms) ALL_PLATFORMS=true; shift ;;
        --version) VERSION_OVERRIDE="${2:?--version requires a value}"; shift 2 ;;
        :*) MODULE_PATH="$1"; shift ;;
        *) error "Unknown argument: $1"; exit 1 ;;
    esac
done

if [[ -z "$MODULE_PATH" ]]; then
    error "Module path is required."
    echo "Usage: publish-plugin.sh <gradle-module-path> [--all-platforms] [--version <ver>]"
    exit 1
fi

# ─── locate repo root ─────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [[ ! -f "$REPO_ROOT/gradlew" ]]; then
    error "gradlew not found at $REPO_ROOT — run this script from inside the karandash repo."
    exit 1
fi

cd "$REPO_ROOT"

# ─── detect host platform ─────────────────────────────────────────────────────

detect_platform() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"

    case "$os" in
        Darwin)
            case "$arch" in
                arm64) echo "macosArm64" ;;
                x86_64) echo "macosX64" ;;
                *) error "Unsupported macOS architecture: $arch"; exit 1 ;;
            esac
            ;;
        Linux)
            case "$arch" in
                x86_64) echo "linuxX64" ;;
                *) error "Unsupported Linux architecture: $arch"; exit 1 ;;
            esac
            ;;
        MINGW*|MSYS*|CYGWIN*)
            echo "mingwX64"
            ;;
        *)
            error "Unsupported OS: $os"
            exit 1
            ;;
    esac
}

platform_to_lib_suffix() {
    case "$1" in
        macosArm64|macosX64) echo "dylib" ;;
        linuxX64)             echo "so" ;;
        mingwX64)             echo "dll" ;;
    esac
}

platform_to_lib_prefix() {
    case "$1" in
        macosArm64|macosX64|linuxX64) echo "lib" ;;
        mingwX64)                      echo "" ;;
    esac
}

platform_to_registry_name() {
    case "$1" in
        macosArm64) echo "macos-arm64" ;;
        macosX64)   echo "macos-x64" ;;
        linuxX64)   echo "linux-x64" ;;
        mingwX64)   echo "windows-x64" ;;
    esac
}

HOST_PLATFORM="$(detect_platform)"

if $ALL_PLATFORMS; then
    PLATFORMS=("macosArm64" "macosX64" "linuxX64" "mingwX64")
    warn "Cross-compilation requested. Ensure the full toolchain image is available."
    warn "Run: ./docker/karandash.sh images build (if not already built)"
else
    PLATFORMS=("$HOST_PLATFORM")
fi

# ─── derive module info ───────────────────────────────────────────────────────

# Convert :plugins:plugins-core:rules:title-format → plugins/plugins-core/rules/title-format
MODULE_DIR="$(echo "$MODULE_PATH" | sed 's|^:||; s|:|/|g')"
MODULE_ABS_DIR="$REPO_ROOT/$MODULE_DIR"
MODULE_NAME="$(basename "$MODULE_DIR")"

# Derive a clean plugin ID from module path (e.g. title-format)
PLUGIN_ID="$MODULE_NAME"

if [[ ! -d "$MODULE_ABS_DIR" ]]; then
    error "Module directory not found: $MODULE_ABS_DIR"
    exit 1
fi

BUILD_FILE="$MODULE_ABS_DIR/build.gradle.kts"
if [[ ! -f "$BUILD_FILE" ]]; then
    error "build.gradle.kts not found in $MODULE_ABS_DIR"
    exit 1
fi

# Verify the module is configured for distribution
if ! grep -qE "kmp-distributable-(plugin|ktor-plugin)" "$BUILD_FILE"; then
    echo ""
    error "Module $MODULE_PATH is not configured for native distribution."
    echo ""
    echo "  The build.gradle.kts must apply one of:"
    echo "    id(\"karandash.kmp-distributable-plugin\")       — for standard modules"
    echo "    id(\"karandash.kmp-distributable-ktor-plugin\")  — for Ktor/HTTP modules"
    echo ""
    echo "  Run verify-plugin-standalone.sh for a full readiness check:"
    echo "    ./tools/scripts/verify-plugin-standalone.sh $MODULE_PATH"
    exit 2
fi

# Read sharedLib baseName from gradle.properties if overridden, else use module name
SHARED_LIB_BASE=""
GRADLE_PROPS="$MODULE_ABS_DIR/gradle.properties"
if [[ -f "$GRADLE_PROPS" ]]; then
    SHARED_LIB_BASE="$(grep "karandash.sharedLib.baseName" "$GRADLE_PROPS" 2>/dev/null | cut -d= -f2 | tr -d '[:space:]')" || true
fi
if [[ -z "$SHARED_LIB_BASE" ]]; then
    SHARED_LIB_BASE="$MODULE_NAME"
fi

# ─── print header ─────────────────────────────────────────────────────────────

echo "═══════════════════════════════════════════════════════════"
echo " Karandash Plugin Native Publisher"
echo " Module:   $MODULE_PATH"
echo " Lib base: $SHARED_LIB_BASE"
echo " Platforms: ${PLATFORMS[*]}"
echo "═══════════════════════════════════════════════════════════"

# ─── build each platform ──────────────────────────────────────────────────────

sha256_of() {
    local file="$1"
    if command -v sha256sum &>/dev/null; then
        sha256sum "$file" | awk '{print $1}'
    elif command -v shasum &>/dev/null; then
        shasum -a 256 "$file" | awk '{print $1}'
    else
        error "No sha256sum or shasum found. Install one to continue."
        exit 1
    fi
}

RESULTS=()  # array of "platform:path:checksum" triples

for PLATFORM in "${PLATFORMS[@]}"; do
    header "Building $PLATFORM..."

    # Gradle task: linkReleaseSharedMacosArm64, etc.
    # Capitalise first letter of platform name for Gradle task convention
    PLATFORM_CAP="$(echo "${PLATFORM:0:1}" | tr '[:lower:]' '[:upper:]')${PLATFORM:1}"
    GRADLE_TASK="${MODULE_PATH}:linkReleaseShared${PLATFORM_CAP}"

    info "Running: ./gradlew $GRADLE_TASK"
    if ! ./gradlew "$GRADLE_TASK" --quiet 2>&1; then
        error "Gradle build failed for $GRADLE_TASK"
        if $ALL_PLATFORMS && [[ "$PLATFORM" != "$HOST_PLATFORM" ]]; then
            warn "Cross-compilation may require Docker. Try without --all-platforms first."
        fi
        exit 1
    fi

    # Locate the produced binary
    LIB_SUFFIX="$(platform_to_lib_suffix "$PLATFORM")"
    LIB_PREFIX="$(platform_to_lib_prefix "$PLATFORM")"
    BIN_DIR="$MODULE_ABS_DIR/build/bin/${PLATFORM}/releaseShared"
    BIN_PATH="$BIN_DIR/${LIB_PREFIX}${SHARED_LIB_BASE}.${LIB_SUFFIX}"

    if [[ ! -f "$BIN_PATH" ]]; then
        error "Expected binary not found: $BIN_PATH"
        error "Check build output above for the actual output path."
        exit 1
    fi

    CHECKSUM="$(sha256_of "$BIN_PATH")"
    REGISTRY_PLATFORM="$(platform_to_registry_name "$PLATFORM")"
    RESULTS+=("${REGISTRY_PLATFORM}:${BIN_PATH}:${CHECKSUM}")

    success "Built: $BIN_PATH"
    info    "SHA-256: $CHECKSUM"
done

# ─── determine version ────────────────────────────────────────────────────────

if [[ -n "$VERSION_OVERRIDE" ]]; then
    PLUGIN_VERSION="$VERSION_OVERRIDE"
else
    # Attempt to read version from Gradle (outputs "project.version = X.Y.Z")
    PLUGIN_VERSION="$(./gradlew "${MODULE_PATH}:properties" --quiet 2>/dev/null \
        | grep '^version:' | awk '{print $2}' | head -1)" || true
    if [[ -z "$PLUGIN_VERSION" || "$PLUGIN_VERSION" == "unspecified" ]]; then
        PLUGIN_VERSION="0.1.0"
        warn "Could not read project version. Using placeholder: $PLUGIN_VERSION"
        warn "Pass --version <semver> to set it explicitly."
    fi
fi

# ─── emit registry JSON snippet ───────────────────────────────────────────────

TIMESTAMP="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

header "Registry entry snippet"
echo ""
echo "  Add this to tools/registry/registry.json under the \"plugins\" array."
echo "  Fill in the TODO fields and replace OWNER/REPO with the plugin repository before committing."
echo ""

# Build the artifacts array for the JSON snippet
ARTIFACTS_JSON=""
for RESULT in "${RESULTS[@]}"; do
    IFS=':' read -r REG_PLATFORM BIN_PATH CHECKSUM <<< "$RESULT"
    if [[ -n "$ARTIFACTS_JSON" ]]; then
        ARTIFACTS_JSON="${ARTIFACTS_JSON},"$'\n'
    fi
    ARTIFACTS_JSON="${ARTIFACTS_JSON}      {
        \"platform\": \"${REG_PLATFORM}\",
        \"url\": \"https://github.com/OWNER/REPO/releases/download/v${PLUGIN_VERSION}/${REG_PLATFORM}/$(basename "$BIN_PATH")\",
        \"checksum\": \"sha256:${CHECKSUM}\",
        \"localPath\": \"${BIN_PATH}\"
      }"
done

# Extract first result fields for the top-level url/checksum
IFS=':' read -r FIRST_PLATFORM FIRST_BIN_PATH FIRST_CHECKSUM <<< "${RESULTS[0]}"

cat <<JSON
  {
    "id": "${PLUGIN_ID}",
    "name": "TODO: Human-readable name",
    "description": "TODO: Brief description (max 200 chars)",
    "author": "TODO: Author or organization",
    "version": "${PLUGIN_VERSION}",
    "type": "TODO: rule|reporter",
    "tags": ["TODO"],
    "url": "https://github.com/OWNER/REPO/releases/download/v${PLUGIN_VERSION}/${FIRST_PLATFORM}/$(basename "${FIRST_BIN_PATH}")",
    "checksum": "sha256:${FIRST_CHECKSUM}",
    "minHostVersion": "0.1.0",
    "repository": "https://github.com/OWNER/REPO",
    "documentation": "https://github.com/OWNER/REPO#readme",
    "artifacts": [
${ARTIFACTS_JSON}
    ]
  }
JSON

echo ""
echo "═══════════════════════════════════════════════════════════"
echo " Binary locations:"
for RESULT in "${RESULTS[@]}"; do
    IFS=':' read -r REG_PLATFORM BIN_PATH CHECKSUM <<< "$RESULT"
    echo "  [$REG_PLATFORM]  $BIN_PATH"
done
echo "═══════════════════════════════════════════════════════════"
echo ""
