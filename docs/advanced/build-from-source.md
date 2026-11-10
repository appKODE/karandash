# Building Karandash from Source

Complete guide for building Karandash from source code, including setup, development workflows, and platform-specific native builds.

## Prerequisites

### Required

- **JDK 17+** (tested with JDK 22)
  ```bash
  java -version  # Should show 17+
  ```

- **Gradle 8.5+** (included via wrapper)
  ```bash
  ./gradlew --version
  ```

- **Git**
  ```bash
  git --version
  ```

### Optional (for native builds)

- **Kotlin Multiplatform setup** (automatically configured)
- **OpenSSL 3.x** (for native Linux/macOS builds with encryption support)
- **Kotlin/Native compiler** (installed automatically by Gradle)

## Quick Start

Clone and build the entire project:

```bash
# Clone repository
git clone https://github.com/appKODE/karandash.git
cd karandash

# Build all targets (JVM + tests)
./gradlew build

# Run tests
./gradlew test

# Build standalone JAR
./gradlew :cli:shadowJar

# JAR location
ls cli/build/libs/karandash.jar
```

Test the built JAR:

```bash
java -jar cli/build/libs/karandash.jar --version
```

## Project Structure

```
karandash/
├── core/                          # Core validation engine (KMP)
│   ├── src/commonMain/            # Shared code (JVM, iOS, native)
│   ├── src/jvmMain/               # JVM-specific implementations
│   ├── src/commonTest/            # Shared unit tests
│   └── src/jvmTest/               # JVM-specific tests
│
├── parser/                        # DSL parser (JVM only)
│   ├── grammar/                   # ANTLR4 grammar (KarandashDsl.g4)
│   └── src/jvmMain/               # Parser implementation
│
├── providers/                     # Git platform integrations (KMP)
│   ├── src/commonMain/            # Shared provider interface
│   ├── src/jvmMain/               # GitLab, GitHub, Bitbucket clients
│   └── src/commonTest/            # Shared tests
│
├── plugins/sdk/                    # Plugin development SDK (KMP)
├── plugins/helpers/                # Plugin utilities (KMP)
├── plugins/loader/                 # Plugin loading mechanism (JVM)
│
├── plugins/                       # Built-in plugins (JVM)
│   ├── jira-plugin/               # JIRA integration
│   ├── email-plugin/              # Email reporting
│   ├── slack-plugin/              # Slack notifications
│   ├── webhook-plugin/            # Webhook support
│   └── ai-plugin/                 # AI-powered validators
│
├── cli/                           # Command-line interface (KMP)
│   ├── src/commonMain/            # CLI logic (all platforms)
│   ├── src/jvmMain/               # JVM CLI + plugin loading
│   ├── src/macosMain/             # macOS-specific code
│   ├── src/linuxMain/             # Linux-specific code
│   ├── src/mingwX64Main/          # Windows-specific code
│   └── src/jvmMain/resources/     # JVM resources
│
├── native-validators/             # Native binary validators
│   ├── validators-core/
│   ├── validators-approval/
│   ├── validators-checks/
│   ├── validators-content/
│   └── validators-security/
│
├── lsp-server/                    # Language Server Protocol (experimental)
├── examples/                      # Example projects and configs
└── gradle/                        # Gradle wrapper files
```

### Module Dependency Graph

```
                    CLI (jvm/native)
                    /              \
          JVM Plugins            Providers
          /         \            /      \
         /           \          /        \
    Plugin-SDK    Plugin-Loader    Core  (KMP)
                      |
                  Plugin-SDK
```

### Source Set Organization

Each module uses Kotlin Multiplatform source sets:

- **commonMain** - Shared code compiled for all platforms
- **jvmMain** - JVM-only code (Java interop, plugins)
- **nativeMain** - POSIX systems (macOS, Linux)
- **macosMain/macosArm64Main/macosX64Main** - macOS-specific
- **linuxX64Main** - Linux x64-specific
- **mingwX64Main** - Windows x64-specific
- **commonTest/jvmTest/nativeTest** - Platform-specific tests

## Building JVM Artifacts

### Standard JAR

Build a standalone JAR with all dependencies:

```bash
./gradlew :cli:shadowJar
```

Output: `cli/build/libs/karandash.jar` (~45MB)

Run:
```bash
./karandash validate
```

### Installable Distribution

Create a runnable distribution with bin script:

```bash
./gradlew :cli:installDist
```

Output: `cli/build/install/cli/`

Run:
```bash
./cli/build/install/cli/bin/karandash --version
```

### Module-Specific JAR

Build just the core library:

```bash
./gradlew :core:jvmJar
```

Output: `core/build/libs/core-jvm-0.1.0.jar`

### Testing JVM Builds

Run all JVM tests:

```bash
./gradlew test
```

Run tests for specific module:

```bash
./gradlew :core:test
./gradlew :cli:test
./gradlew :providers:test
```

Run tests matching pattern:

```bash
./gradlew test --tests "*ValidatorTest"
./gradlew test --tests "*TitleFormatValidatorTest"
```

## Building Native Binaries

Karandash uses Kotlin Multiplatform Native (KMP) to compile to native binaries for macOS, Linux, and Windows.

### Build All Native Targets

```bash
# macOS ARM64 (Apple Silicon)
./gradlew :cli:linkReleaseExecutableMacosArm64

# macOS x64 (Intel)
./gradlew :cli:linkReleaseExecutableMacosX64

# Linux x64
./gradlew :cli:linkReleaseExecutableLinuxX64

# Windows x64
./gradlew :cli:linkReleaseExecutableMingwX64
```

### Output Locations

```
cli/build/bin/
├── macosArm64/release/karandash.kexe      (12MB)
├── macosX64/release/karandash.kexe        (12MB)
├── linuxX64/release/karandash.kexe        (14MB)
└── mingwX64/release/karandash.exe         (15MB)
```

### Test Native Builds

```bash
# Test macOS ARM64 binary
./cli/build/bin/macosArm64/release/karandash.kexe --version

# Test Linux binary
./cli/build/bin/linuxX64/release/karandash.kexe --version
```

### Platform-Specific Notes

#### macOS Builds

Requires Xcode Command Line Tools:

```bash
xcode-select --install
```

Build with:
```bash
./gradlew :cli:linkReleaseExecutableMacosArm64
```

#### Linux Builds

On macOS (cross-compilation):
```bash
./gradlew :cli:linkReleaseExecutableLinuxX64
```

On Linux (native build):
```bash
./gradlew :cli:linkReleaseExecutableLinuxX64
```

Requires glibc. To check:
```bash
ldd --version  # Shows glibc version
```

#### Windows Builds

On Windows (native build):
```bash
./gradlew :cli:linkReleaseExecutableMingwX64
```

Cross-compilation from macOS/Linux:
```bash
./gradlew :cli:linkReleaseExecutableMingwX64
```

Note: Windows build may require mingw toolchain. On macOS:
```bash
brew install mingw-w64
```

### Native Build with Debugging Symbols

```bash
./gradlew :cli:linkDebugExecutableMacosArm64

# Output: cli/build/bin/macosArm64/debug/karandash.kexe
```

## Kotlin/Native Binary Building

Build a Kotlin/Native binary for instant startup:

### Prerequisites

1. Install JDK 17+ (for running Gradle only; the output binary requires no JVM)
2. Install OpenSSL development headers (required for plugin signature verification):
   - macOS: `brew install openssl@3`
   - Linux: `sudo apt-get install libssl-dev`
   - Windows: `pacman -S mingw-w64-x86_64-openssl`

### Build Native Binary

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
```

The Kotlin/Native compiler is downloaded automatically by Gradle on first run.

### Test Native Binary

```bash
./cli/build/bin/macosArm64/releaseExecutable/cli.kexe --version  # ~15ms startup
```

### Native Image Performance

Comparison with standard JAR:

| Metric | Standard JAR | Native Image |
|--------|-------------|--------------|
| Size | 45MB | 25MB |
| Startup | ~375ms | ~50-80ms |
| Memory | ~200MB | ~80MB |
| Plugins | All types supported | All types supported (JAR requires Java 17+) |

Note: Native builds support all plugin types: native (.dylib/.so/.dll), JavaScript (.mjs via QuickJS), WASM (.wasm via Chasm), and JAR (via subprocess bridge requiring Java 17+ on PATH).

## Building Hybrid Distribution

Combines JAR + native validators for best performance and compatibility (Recommended):

```bash
./gradlew buildHybrid
```

Output: `build/distributions/karandash-hybrid-0.1.0-<platform>.tar.gz` (~58MB)

### Test Hybrid Distribution

```bash
tar -xzf build/distributions/karandash-hybrid-0.1.0-macos-arm64.tar.gz
cd karandash-hybrid-0.1.0-macos-arm64
./bin/karandash --version
```

### Build for Specific Platforms

```bash
# macOS ARM64
./scripts/package-hybrid.sh 0.1.0 macos-arm64

# macOS x64
./scripts/package-hybrid.sh 0.1.0 macos-x64

# Linux x64
./scripts/package-hybrid.sh 0.1.0 linux-x64

# Windows x64
./scripts/package-hybrid.sh 0.1.0 windows-x64
```

## Running Tests

### All Tests

```bash
./gradlew test
```

### JVM Tests Only

```bash
./gradlew :core:jvmTest
./gradlew :cli:jvmTest
./gradlew :providers:jvmTest
```

### Native Tests

```bash
# macOS ARM64
./gradlew :cli:macosArm64Test

# Linux
./gradlew :cli:linuxX64Test
```

### Tests with Coverage

```bash
# Enable coverage reporting
./gradlew test --info

# View coverage report
open build/reports/jacoco/test/html/index.html
```

### Run Specific Test Class

```bash
./gradlew test --tests "*TitleFormatValidatorTest"
./gradlew test --tests "*YamlConfigParserTest"
```

### Test Output

```bash
# Verbose output
./gradlew test --info

# Failed tests only
./gradlew test --fail-fast
```

## Development Workflow

### IDE Setup

#### IntelliJ IDEA (Recommended)

1. Open project: `File > Open` > select `karandash` directory
2. Wait for Gradle sync to complete
3. Configure JDK 17+:
   - Preferences > Build, Execution, Deployment > Gradle
   - Set Gradle JVM to JDK 17+

4. Enable Kotlin Multiplatform support:
   - Plugins > search "Kotlin Multiplatform" > Install

#### Visual Studio Code

1. Install extensions:
   - Kotlin Language (fwcd.kotlin)
   - Gradle for Java (vscjava.vscode-gradle)

2. Open settings: `settings.json`
   ```json
   {
     "java.import.gradle.enabled": true,
     "kotlin.compiler.jvm.target": "17"
   }
   ```

### Running from Source

#### Run CLI Directly

```bash
./gradlew :cli:jvmRun --args="validate"
./gradlew :cli:jvmRun --args="--version"
```

With custom args:
```bash
./gradlew :cli:jvmRun --args="validate --config myconfig.yaml"
```

#### Build & Run

```bash
./gradlew :cli:installDist
./cli/build/install/cli/bin/karandash --version
```

### Debugging

#### Remote Debug

In IDE, set breakpoint and run:

```bash
./gradlew :cli:jvmRun --debug-jvm
```

Then attach debugger: `Run > Attach to Process` in IntelliJ.

#### Debug Gradle Build

```bash
./gradlew build --debug
```

## Common Build Tasks

### Clean and Rebuild

```bash
# Clean all build artifacts
./gradlew clean

# Full rebuild
./gradlew clean build

# Rebuild just CLI
./gradlew clean :cli:build
```

### View Build Dependencies

```bash
# Show all dependencies
./gradlew dependencies

# Show dependencies for specific module
./gradlew :cli:dependencies

# Tree view
./gradlew dependencyTree
```

### Update Dependencies

Check for updates:
```bash
./gradlew dependencyUpdates
```

Update Gradle:
```bash
./gradlew wrapper --gradle-version=latest
```

### Build Reports

Generate reports:

```bash
# Test report
./gradlew test
open build/reports/tests/test/index.html

# Coverage report
./gradlew test jacocoTestReport
open build/reports/jacoco/test/html/index.html

# Dependency report
./gradlew dependencyReport
open build/reports/project/dependencies/index.html
```

### Publish to Local Maven

```bash
./gradlew publishToMavenLocal

# JAR location: ~/.m2/repository/io/karandash/
```

### Build Custom JRE

For smaller distributions:

```bash
./gradlew buildCustomJre
```

Creates ~60-80MB custom JRE vs ~180MB full JRE.

## Troubleshooting Build Issues

### "Unable to find Kotlin compiler"

**Solution:** Clean and rebuild:
```bash
./gradlew clean
./gradlew build
```

### "Gradle daemon killed"

**Cause:** Out of memory

**Solution:** Increase heap in `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g
```

### Native build fails with "OpenSSL not found"

**On macOS:**
```bash
brew install openssl
export LDFLAGS="-L/opt/homebrew/opt/openssl@3/lib"
export CPPFLAGS="-I/opt/homebrew/opt/openssl@3/include"
./gradlew :cli:linkReleaseExecutableMacosArm64
```

**On Linux:**
```bash
sudo apt-get install libssl-dev
./gradlew :cli:linkReleaseExecutableLinuxX64
```

### "Native toolchain not found"

**Solution:** The Kotlin/Native toolchain is downloaded automatically by Gradle. Make sure you have a working internet connection and JDK 17+:
```bash
./gradlew :cli:linkReleaseExecutableMacosArm64
```

### Tests hang or timeout

**Solution:** Increase timeout in `build.gradle.kts`:
```kotlin
tasks.test {
    timeout.set(Duration.ofMinutes(10))
}
```

### Plugin not loading in compiled JAR

**Check:** Plugin must be on classpath
```bash
jar tf cli/build/libs/karandash.jar | grep "plugin"
```

Add plugin to `:cli:jvmMain` dependencies in `cli/build.gradle.kts`.

### Cross-compilation fails

**Example: Building Linux binary on macOS**

This is supported but requires:
1. Kotlin/Native toolchain (auto-downloaded)
2. Linux headers (auto-configured)

If it fails:
```bash
# Clear Kotlin/Native cache
rm -rf ~/.konan

# Retry
./gradlew :cli:linkReleaseExecutableLinuxX64
```

## Development Conventions

### Code Style

- **Language:** Kotlin
- **Style:** Official Kotlin style guide
- **Imports:** Alphabetical order, no wildcards

Check with:
```bash
./gradlew ktlintCheck

# Auto-format
./gradlew ktlintFormat
```

### Testing Requirements

1. All new features require tests
2. Run `./gradlew test` before committing
3. Maintain >80% code coverage for core module

### Documentation Requirements

1. Public APIs must have KDoc comments
2. Complex logic needs explanation
3. Update docs/ for user-facing changes

### Git Workflow

```bash
# Create feature branch
git checkout -b feature/my-feature

# Make changes and commit
git add .
git commit -m "feat: add new validator"

# Push and create PR
git push origin feature/my-feature
```

## Contributing

### Before Submitting a PR

1. Fork the repository
2. Create feature branch: `git checkout -b feature/name`
3. Make changes and add tests
4. Verify all tests pass:
   ```bash
   ./gradlew test
   ./gradlew ktlintCheck
   ```
5. Build distribution:
   ```bash
   ./gradlew :cli:shadowJar
   java -jar cli/build/libs/karandash.jar --version
   ```
6. Push and submit PR

### Testing Your Changes

Run before every commit:

```bash
# Quick test (JVM only)
./gradlew test

# Full test (all platforms)
./gradlew build

# Test specific module
./gradlew :core:test
```

### Build on CI

When you push, GitHub Actions runs:
- All tests (JVM)
- Build checks (ktlint, detekt)
- Test coverage report
- Binary builds (on release tags)

## Performance Tips

### Faster Builds

1. Use daemon (default):
   ```bash
   ./gradlew build  # Daemon stays alive between runs
   ```

2. Parallel execution:
   ```bash
   ./gradlew build --parallel
   ```

3. Build specific modules:
   ```bash
   ./gradlew :cli:shadowJar  # Only build CLI JAR, skip tests
   ```

4. Disable tests:
   ```bash
   ./gradlew :cli:shadowJar -x test
   ```

### Faster Incremental Builds

Gradle caches build outputs. Avoid:
- Changing build.gradle.kts frequently
- Modifying gradle.properties
- Cleaning often

### Development Mode

Skip tests during development:

```bash
./gradlew :cli:shadowJar -x test
```

Then test manually:
```bash
./karandash validate
```

## Additional Resources

- [Gradle Documentation](https://docs.gradle.org)
- [Kotlin Multiplatform Documentation](https://kotlinlang.org/docs/multiplatform.html)
- [Kotlin Native Documentation](https://kotlinlang.org/docs/native-overview.html)
- [Kotlin/Native Documentation](https://kotlinlang.org/docs/native-overview.html)
- [Project Repository](https://github.com/appKODE/karandash)
