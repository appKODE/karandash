# Building Native CLI with Plugin Support

This guide explains how to build Karandash as a native binary that supports plugin loading on macOS, Linux, and Windows.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Quick Start](#quick-start)
3. [Platform Setup](#platform-setup)
4. [Build Instructions](#build-instructions)
5. [Verification](#verification)
6. [Troubleshooting](#troubleshooting)
7. [Performance Tips](#performance-tips)

---

## Prerequisites

### System Requirements

- **Kotlin/Native Compiler:** Installed via Gradle (automatic)
- **Java:** JDK 17+ (for Gradle build system)
- **OpenSSL:** not needed; native crypto links a prebuilt OpenSSL 3 statically (see [Configuration](#configuration))
- **CMake:** 3.15+ (for cinterop compilation)

### Required Environment Variables

```bash
# Optional: Point to Gradle
export GRADLE_HOME=/path/to/gradle

# Optional: Java home (auto-detected if not set)
export JAVA_HOME=/path/to/jdk
```

---

## Quick Start

### macOS (Apple Silicon - ARM64)

```bash
# Build native CLI for Apple Silicon
cd /path/to/karandash
./gradlew :cli:linkReleaseExecutableMacosArm64

# Run native binary
./cli/build/bin/macosArm64/releaseExecutable/cli.kexe --version
```

### macOS (Intel - x64)

```bash
# Build native CLI for Intel
cd /path/to/karandash
./gradlew :cli:linkReleaseExecutableMacosX64

# Run native binary
./cli/build/bin/macosX64/releaseExecutable/cli.kexe --version
```

### Linux (Ubuntu/Debian)

```bash
# Install dependencies
sudo apt-get update
sudo apt-get install build-essential

# Build native CLI
cd /path/to/karandash
./gradlew :cli:linkReleaseExecutableLinuxX64

# Run native binary
./cli/build/bin/linuxX64/releaseExecutable/cli --version
```

### Linux (RHEL/CentOS/Fedora)

```bash
# Install dependencies
sudo dnf groupinstall "Development Tools"

# Build native CLI
cd /path/to/karandash
./gradlew :cli:linkReleaseExecutableLinuxX64

# Run native binary
./cli/build/bin/linuxX64/releaseExecutable/cli --version
```

### Windows (MinGW)

```bash
# Install MSYS2 if not already installed
# From MSYS2 shell, build native CLI
cd /path/to/karandash
./gradlew :cli:linkReleaseExecutableMingwX64

# Run native binary
./cli/build/bin/mingwX64/releaseExecutable/cli.exe --version
```

---

## Platform Setup

### macOS (Intel & Apple Silicon)

#### Installation

Nothing beyond the JDK and the Xcode command line tools (`xcode-select --install`). OpenSSL is linked statically
from a prebuilt copy (see [Configuration](#configuration)); Homebrew's `openssl@3` is not used.

#### Multi-Architecture Builds

```bash
# Build for Apple Silicon (native)
./gradlew :cli:linkReleaseExecutableMacosArm64

# Build for Intel
./gradlew :cli:linkReleaseExecutableMacosX64

# Build both architectures
./gradlew :cli:linkReleaseExecutableMacosArm64 :cli:linkReleaseExecutableMacosX64
```

**Output Locations:**
- Apple Silicon: `./cli/build/bin/macosArm64/releaseExecutable/cli.kexe`
- Intel: `./cli/build/bin/macosX64/releaseExecutable/cli.kexe`

### Linux (x86_64)

#### Installation - Ubuntu/Debian

```bash
# Update package lists
sudo apt-get update

# Install build tools
sudo apt-get install -y \
    build-essential \
    cmake
```

#### Installation - RHEL/CentOS/Fedora

```bash
# Install build tools
sudo dnf install -y \
    gcc \
    gcc-c++ \
    cmake
```

### Windows (x86_64 with MinGW)

#### Installation via MSYS2

```bash
# 1. Download and install MSYS2 from https://www.msys2.org/
# 2. Open MSYS2 terminal and update packages:
pacman -Syuu

# 3. Install MinGW 64-bit toolchain:
pacman -S \
    mingw-w64-x86_64-gcc \
    mingw-w64-x86_64-cmake

# 4. Add MinGW to PATH
export PATH="/mingw64/bin:$PATH"
```

#### Verification

```bash
# In MSYS2 terminal
x86_64-w64-mingw32-gcc --version
x86_64-w64-mingw32-g++ --version
```

#### Build from MSYS2

```bash
# Start MSYS2 shell
# Navigate to karandash repo
cd /c/path/to/karandash

# Build for Windows
./gradlew :cli:linkReleaseExecutableMingwX64

# Output binary
ls cli/build/bin/mingwX64/releaseExecutable/cli.exe
```

---

## Build Instructions

### Configuration

Targets come from the `karandash.kmp-library` convention plugin in `build-logic/`. Crypto lives in `core/crypto` and is built on cryptography-kotlin (`cryptography-provider-optimal`, with the
CryptoKit provider excluded in `core/crypto/build.gradle.kts`). On native targets that provider links a prebuilt
OpenSSL 3 statically, so the build needs no system OpenSSL, no headers and no cinterop `.def` file.

### Build Targets

**Build all JVM and Native targets:**

```bash
./gradlew :cli:build
```

**Build release native executables:**

```bash
# macOS ARM64 (Apple Silicon)
./gradlew :cli:linkReleaseExecutableMacosArm64

# macOS x64 (Intel)
./gradlew :cli:linkReleaseExecutableMacosX64

# Linux x64
./gradlew :cli:linkReleaseExecutableLinuxX64

# Windows x64
./gradlew :cli:linkReleaseExecutableMingwX64

# All native release targets
./gradlew :cli:linkReleaseExecutableMacosArm64 :cli:linkReleaseExecutableMacosX64 \
          :cli:linkReleaseExecutableLinuxX64 :cli:linkReleaseExecutableMingwX64
```

**Build debug binary (faster compilation, for development):**

```bash
# macOS ARM64
./gradlew :cli:linkDebugExecutableMacosArm64

# macOS x64
./gradlew :cli:linkDebugExecutableMacosX64

# Linux x64
./gradlew :cli:linkDebugExecutableLinuxX64

# Windows x64
./gradlew :cli:linkDebugExecutableMingwX64
```

**Build only plugins/loader (skip CLI):**

```bash
./gradlew :plugins:loader:build
```

### Build Output Locations

After successful build, binaries are located at:

**Release Executables:**

```
# macOS ARM64 (Apple Silicon)
cli/build/bin/macosArm64/releaseExecutable/cli.kexe

# macOS x64 (Intel)
cli/build/bin/macosX64/releaseExecutable/cli.kexe

# Linux x64
cli/build/bin/linuxX64/releaseExecutable/cli

# Windows x64
cli/build/bin/mingwX64/releaseExecutable/cli.exe
```

**Debug Executables (for development):**

```
# macOS ARM64
cli/build/bin/macosArm64/debugExecutable/cli.kexe

# macOS x64
cli/build/bin/macosX64/debugExecutable/cli.kexe

# Linux x64
cli/build/bin/linuxX64/debugExecutable/cli

# Windows x64
cli/build/bin/mingwX64/debugExecutable/cli.exe
```

**Binary naming conventions:**
- `.kexe` extension on macOS
- No extension on Linux
- `.exe` extension on Windows

---

## Verification

### Test Native Binary

Example for macOS ARM64:

```bash
# Check binary size
ls -lh cli/build/bin/macosArm64/releaseExecutable/cli.kexe
# Expected: ~9-12MB

# Test version command
./cli/build/bin/macosArm64/releaseExecutable/cli.kexe --version
# Expected: karandash version x.y.z

# Test help command
./cli/build/bin/macosArm64/releaseExecutable/cli.kexe --help
# Expected: usage and options

# Test validate command (needs config)
./cli/build/bin/macosArm64/releaseExecutable/cli.kexe validate --help
# Expected: validate command options
```

**For other platforms, adjust the path:**
- Linux: `./cli/build/bin/linuxX64/releaseExecutable/cli`
- macOS x64: `./cli/build/bin/macosX64/releaseExecutable/cli.kexe`
- Windows: `./cli/build/bin/mingwX64/releaseExecutable/cli.exe`

### Verify No System OpenSSL Is Linked

OpenSSL is linked into the binary statically, so no `libssl`/`libcrypto` appears among its dynamic libraries:

```bash
# macOS (expect no output)
otool -L cli/build/bin/macosArm64/releaseExecutable/cli.kexe | grep -E 'ssl|crypto'

# Linux (expect no output)
ldd cli/build/bin/linuxX64/releaseExecutable/cli | grep -E 'ssl|crypto'
```

### Run Unit Tests

```bash
# Run all JVM tests
./gradlew :plugins:loader:jvmTest

# Run native tests (compile and run on native)
./gradlew :plugins:loader:nativeTest

# Run specific test class
./gradlew :plugins:loader:jvmTest --tests "*CryptoProviderTest"
```

### Smoke Test: Validate a Real PR

```bash
# Set authentication
export GITHUB_TOKEN="your-github-token"

# Validate a real PR (macOS ARM64 example)
./cli/build/bin/macosArm64/releaseExecutable/cli.kexe validate \
    --pr https://github.com/owner/repo/pull/123

# Expected output: validation results with built-in validators
```

---

## Troubleshooting

### OpenSSL Errors

Errors such as `openssl/evp.h: No such file or directory`, `library not found for -lssl` or
`Library not loaded: @rpath/libssl.3.dylib` come from an old build setup that used an `openssl.def` cinterop. The
current build has no such cinterop and links OpenSSL statically, so installing OpenSSL or setting
`DYLD_LIBRARY_PATH`/`LD_LIBRARY_PATH` does not apply. Run `./gradlew clean` and rebuild from a current checkout.

### Error: CMake Not Found

**Problem:**
```
CMake executable not found in PATH
```

**Solution:**

Install CMake:

```bash
# macOS
brew install cmake

# Linux
sudo apt-get install cmake
# or
sudo dnf install cmake

# Windows (MSYS2)
pacman -S mingw-w64-x86_64-cmake
```

### Slow Build Time

**Problem:**
```
Native compilation takes 10-15 minutes
```

**Solution:**

Use debug binary for development (builds in 1-2 minutes):

```bash
./gradlew :cli:linkDebugExecutableLinuxX64
./cli/build/bin/linuxX64/debugExecutable/cli --version
```

Or for macOS:

```bash
./gradlew :cli:linkDebugExecutableMacosArm64
./cli/build/bin/macosArm64/debugExecutable/cli.kexe --version
```

Production releases should use release binary.

### Memory Issues During Build

**Problem:**
```
Java heap space
```

**Solution:**

Increase Gradle heap:

```bash
export GRADLE_OPTS="-Xmx4g"
./gradlew :cli:linkReleaseExecutableMacosArm64
```

Or specify for a specific platform:

```bash
export GRADLE_OPTS="-Xmx4g"
./gradlew :cli:linkReleaseExecutableLinuxX64
```

### Build Succeeds But Binary Won't Run

**Problem:**
```
./cli: command not found (on Linux)
```

**Solution:**

Verify binary format and permissions. Example for Linux:

```bash
# Check file type
file cli/build/bin/linuxX64/releaseExecutable/cli

# Should show: ELF 64-bit LSB executable

# Try running with full path
./cli/build/bin/linuxX64/releaseExecutable/cli --version

# Or verify execute permission
chmod +x cli/build/bin/linuxX64/releaseExecutable/cli
./cli/build/bin/linuxX64/releaseExecutable/cli --version
```

For macOS:

```bash
# Check file type
file cli/build/bin/macosArm64/releaseExecutable/cli.kexe

# Should show: Mach-O 64-bit executable

# Try running with full path
./cli/build/bin/macosArm64/releaseExecutable/cli.kexe --version
```

---

## Performance Tips

### Faster Builds

1. **Use debug target for development:**
   ```bash
   ./gradlew :cli:linkDebugExecutableMacosArm64
   ```

2. **Build only for current platform:**
   ```bash
   # Instead of building all targets, build for your platform only
   ./gradlew :cli:linkReleaseExecutableMacosArm64    # for macOS ARM64
   ./gradlew :cli:linkReleaseExecutableMacosX64      # for macOS Intel
   ./gradlew :cli:linkReleaseExecutableLinuxX64      # for Linux
   ./gradlew :cli:linkReleaseExecutableMingwX64      # for Windows
   ```

3. **Parallel builds:**
   ```bash
   ./gradlew :cli:build --parallel
   ```

4. **Incremental compilation:**
   ```bash
   # Gradle caches by default, just rebuild when needed
   ./gradlew :cli:linkReleaseExecutableMacosArm64
   ```

### Smaller Binaries

1. **Release build (default):**
   ```bash
   # Already optimized with -opt-in=kotlin.native.aggressive.optimizations
   ./gradlew :cli:linkReleaseExecutableMacosArm64
   ```

2. **Strip debug symbols (post-build, macOS):**
   ```bash
   strip cli/build/bin/macosArm64/releaseExecutable/cli.kexe
   ```

   **Or for Linux:**
   ```bash
   strip cli/build/bin/linuxX64/releaseExecutable/cli
   ```

### Better Runtime Performance

1. **Use native binary instead of JVM:**
   - Native: 11-25ms startup
   - JVM: 2-3 seconds startup

2. **Precompile configuration:**
   ```bash
   # Parse config once, reuse multiple times
   karandash init --format kts  # Generate type-safe config
   ```

3. **Cache remote plugins:**
   ```bash
   # Plugin fetcher caches to disk automatically
   # Subsequent runs use cache (< 5ms lookup)
   ```

---

## Summary

Native CLI build includes:

- **Full plugin signature verification** with CryptoProvider and OpenSSL
- **Cross-platform file operations** with kotlinx.io.files.Path
- **All 21 built-in validators** with full Kotlin DSL, YAML, JSON config support
- **11-25ms startup** ideal for CI/CD pipelines
- **9.1MB binary** perfect for containers and edge deployment

For architecture details, see [MULTIPLATFORM_PLUGIN_SYSTEM.md](MULTIPLATFORM_PLUGIN_SYSTEM.md).
