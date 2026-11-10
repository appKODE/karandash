# Native Binary Distribution Guide

Build Karandash as a native binary with instant startup time using Kotlin/Native.

## Overview

Kotlin/Native compiles the entire application ahead-of-time into a standalone executable with no JVM dependency.

**Benefits:**
- ⚡ **11-25ms startup** (vs ~2-3s for JAR)
- 📦 **Single self-contained binary** (no JRE bundled in the download)
- ⚡ **No Java required** for core operation (Java 17+ needed only for JAR plugins)
- 🚀 **Perfect for CI/CD**

**Trade-offs:**
- JAR plugins supported via subprocess bridge (requires Java 17+ on the host)
- JavaScript plugins supported (QuickJS engine, both platforms)
- Native plugins supported (dlopen/dlsym)
- All 21 built-in validators work
- 🔧 Per-platform compilation required

## Prerequisites

### macOS

```bash
# Install Xcode Command Line Tools
xcode-select --install

# Install OpenSSL 3.x (required for plugin signature verification)
brew install openssl@3
```

### Linux (Ubuntu/Debian)

```bash
sudo apt-get install libssl-dev
```

### Linux (RHEL/CentOS/Fedora)

```bash
sudo dnf install openssl-devel
```

### Windows (MinGW)

```bash
# Via MSYS2
pacman -S mingw-w64-x86_64-openssl
```

### All Platforms

- **Kotlin/Native compiler** - installed automatically by Gradle
- **JDK 17+** - required only for running Gradle (not for the output binary)

## Building

### Quick Build (macOS ARM64)

```bash
./gradlew :cli:linkReleaseExecutableMacosArm64
```

Output: `cli/build/bin/macosArm64/releaseExecutable/cli.kexe`

### All Platform Targets

```bash
# macOS ARM64 (Apple Silicon)
./gradlew :cli:linkReleaseExecutableMacosArm64
# Output: cli/build/bin/macosArm64/releaseExecutable/cli.kexe

# macOS x64 (Intel)
./gradlew :cli:linkReleaseExecutableMacosX64
# Output: cli/build/bin/macosX64/releaseExecutable/cli.kexe

# Linux x64
./gradlew :cli:linkReleaseExecutableLinuxX64
# Output: cli/build/bin/linuxX64/releaseExecutable/cli.kexe

# Windows MinGW x64
./gradlew :cli:linkReleaseExecutableMingwX64
# Output: cli/build/bin/mingwX64/releaseExecutable/cli.kexe
```

**Note:** Cross-compilation is not supported. Build on the target platform or use CI/CD.

### Debug Build

```bash
./gradlew :cli:linkDebugExecutableMacosArm64
# Output: cli/build/bin/macosArm64/debugExecutable/cli.kexe
```

### Build Time

- **First build:** 3-8 minutes (Kotlin/Native LLVM compilation)
- **Incremental:** Similar (ahead-of-time compilation does not support incremental builds)

## Testing the Binary

```bash
# Run basic command
./cli/build/bin/macosArm64/releaseExecutable/cli.kexe --version
# Output: karandash version 1.0.0

# Test validation
./cli/build/bin/macosArm64/releaseExecutable/cli.kexe doctor

# Benchmark startup time
time ./cli/build/bin/macosArm64/releaseExecutable/cli.kexe --version
# Output: real 0m0.015s (15ms typical)
```

## Distribution

### Single Binary

The native binary is a self-contained executable - no JVM or runtime installation required:

```bash
# Copy to installation directory
sudo cp cli/build/bin/macosArm64/releaseExecutable/cli.kexe /usr/local/bin/karandash

# Make executable
sudo chmod +x /usr/local/bin/karandash

# Test
karandash --version
```

## Limitations

### JAR Plugin Support Requires Java

JAR plugins are supported on the native binary via a subprocess bridge: when a JAR plugin is loaded, the binary spawns a JVM subprocess (`JarBridgeServer`) that loads the JAR via `ServiceLoader` and communicates findings over JSON IPC. Java 17+ must be available on `PATH` or `JAVA_HOME`.

**Fully supported on native:**
- All 21 built-in validators
- JAR plugins (`.jar`) via subprocess bridge (requires Java 17+)
- JavaScript plugins (`.mjs`) via QuickJS
- Native plugins (`.dylib`/`.so`/`.dll`) via dlopen

## Performance

### Benchmarks (macOS ARM64)

| Metric | Native Binary | Standard JAR | Improvement |
|--------|---------------|--------------|-------------|
| Startup Time | ~15ms | ~2-3s | **100-200x faster** |
| Binary Size | single binary, no JRE | fat JAR + JRE | -- |
| Memory (RSS) | ~40MB | ~120MB | **3x less** |
| Java Required | No | Yes (JDK 17+) | - |
| Build Time | 3-8min | ~10s | - |

### When Native Binary Wins

- **CI/CD pipelines** - Startup speed matters, runs frequently
- **Serverless functions** - Cold start performance critical
- **Docker containers** - Smaller images, no Java base image needed
- **CLI tools** - Users expect instant response

### When JAR is Better

- **Development** - Faster iteration (no native compile step)
- **JAR plugin ecosystem without Java on target** - JAR plugins on native require Java 17+ on the host; JVM JAR distribution bundles its own runtime
- **Windows without MinGW** - JVM jar is simpler to set up

## Troubleshooting

### Build Fails: OpenSSL Not Found

```bash
# macOS: install via Homebrew
brew install openssl@3

# Linux: install dev headers
sudo apt-get install libssl-dev   # Debian/Ubuntu
sudo dnf install openssl-devel    # RHEL/Fedora
```

### Build Fails: Kotlin/Native Toolchain Error

```bash
# Clean and retry
./gradlew clean :cli:linkReleaseExecutableMacosArm64

# Check Kotlin version compatibility
./gradlew --version
```

### Binary Crashes at Runtime

Check that OpenSSL libraries are present on the target machine (required at runtime for HTTPS):

```bash
# macOS
otool -L cli/build/bin/macosArm64/releaseExecutable/cli.kexe

# Linux
ldd cli/build/bin/linuxX64/releaseExecutable/cli.kexe
```

## CI/CD Integration

### GitHub Actions (macOS ARM64)

```yaml
build-native:
  runs-on: macos-latest
  steps:
    - uses: actions/checkout@v4

    - name: Set up JDK 17
      uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'

    - name: Build Native Binary
      run: ./gradlew :cli:linkReleaseExecutableMacosArm64

    - name: Test Binary
      run: ./cli/build/bin/macosArm64/releaseExecutable/cli.kexe --version

    - name: Upload Artifact
      uses: actions/upload-artifact@v4
      with:
        name: karandash-native-macos-arm64
        path: cli/build/bin/macosArm64/releaseExecutable/cli.kexe
```

### GitHub Actions (Linux x64)

```yaml
build-native-linux:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4

    - name: Install OpenSSL
      run: sudo apt-get install -y libssl-dev

    - name: Set up JDK 17
      uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'

    - name: Build Native Binary
      run: ./gradlew :cli:linkReleaseExecutableLinuxX64

    - name: Test Binary
      run: ./cli/build/bin/linuxX64/releaseExecutable/cli.kexe --version

    - name: Upload Artifact
      uses: actions/upload-artifact@v4
      with:
        name: karandash-native-linux-x64
        path: cli/build/bin/linuxX64/releaseExecutable/cli.kexe
```

### Docker Multi-Stage Build (Linux)

```dockerfile
# Stage 1: Build with Gradle + Kotlin/Native
FROM gradle:8-jdk17 AS builder

RUN apt-get update && apt-get install -y libssl-dev

WORKDIR /build
COPY . .

RUN ./gradlew :cli:linkReleaseExecutableLinuxX64

# Stage 2: Minimal runtime image (no JVM needed)
FROM gcr.io/distroless/base-debian12

COPY --from=builder /build/cli/build/bin/linuxX64/releaseExecutable/cli.kexe /usr/local/bin/karandash

ENTRYPOINT ["/usr/local/bin/karandash"]
```

**Result:** ~20MB final image (vs 200MB+ with JRE base image)

## Comparison with JVM JAR

| Feature | Native Binary | JVM JAR |
|---------|---------------|---------|
| Startup | **~15ms** | ~2-3s |
| Size | single binary, no JRE | fat JAR + JRE |
| JAR Plugins | subprocess bridge (Java 17+) | yes |
| JS Plugins | QuickJS | QuickJS |
| Native Plugins | dlopen | JNA |
| Java Required | No (Yes for JAR plugins) | JDK 17+ |
| Build Time | 3-8min | ~10s |

**Recommendation:** Use native binary for production CI/CD. All plugin types work on both distributions; JAR plugins on native require Java 17+ available on the host.

## Next Steps

- [Choosing a Distribution](choosing.md)
- [JVM Distribution Guide](jpackage.md)
- [Plugin Development](../plugins/overview.md)
