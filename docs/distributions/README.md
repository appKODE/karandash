# Distributions

Karandash ships in two primary formats. The **native binary** is the recommended default for most users and CI/CD pipelines.

## Native Binary (recommended)

A self-contained executable compiled with Kotlin/Native. No Java runtime required.

- **Startup**: ~65ms cold start
- **Size**: ~12–15 MB per platform
- **Plugins**: Native (`.dylib`/`.so`/`.dll`), JavaScript (`.mjs`), WASM (`.wasm`)
- **Platforms**: macOS ARM64, macOS x64, Linux x64, Windows x64

```bash
# macOS ARM64
curl -L https://github.com/appKODE/karandash/releases/latest/download/karandash-macos-arm64 \
  -o karandash && chmod +x karandash
```

See [native-binary.md](native-binary.md) for full download and build instructions. Each release also ships `SHA256SUMS` for verifying downloads, and Docker Hub carries the same binary as `rinekri/karandash:v1.0.1` (`-jvm` for the fat JAR).

## JVM Fat JAR (secondary)

A shadow JAR that runs on any JVM 17+. Required when you need JVM-based (ServiceLoader) plugins.

- **Startup**: ~375ms
- **Size**: ~45 MB
- **Plugins**: All types including JVM JAR plugins
- **Platforms**: Any platform with JRE 17+

```bash
java -jar karandash.jar validate
```

Build from source:

```bash
./gradlew :cli:shadowJar
# Output: cli/build/libs/karandash.jar
```

## JPackage Installer (alternative)

Platform-native installers built with JPackage. Bundles a trimmed JRE - no Java pre-install needed, but startup is JVM speed.

- **Startup**: ~375ms (JVM-based)
- **Size**: ~75–85 MB (includes bundled JRE)
- **Plugins**: All types including JVM JAR plugins
- **Formats**: macOS DMG, Linux DEB/RPM, Windows MSI

See [jpackage.md](jpackage.md) for installation and build instructions.

## Comparison

| Feature | Native binary | JVM fat JAR | JPackage installer |
|---------|:---:|:---:|:---:|
| Java required | No | Yes (17+) | No (bundled) |
| Startup | ~65ms | ~375ms | ~375ms |
| Size | ~12–15 MB | ~45 MB | ~75–85 MB |
| Native plugins | Yes | Yes (JNA) | Yes (JNA) |
| JS plugins | Yes | Yes | Yes |
| WASM plugins | Yes | Yes | Yes |
| JVM JAR plugins | No | Yes | Yes |
| CI/CD friendly | Yes | Yes | - |

## Choosing a Distribution

Use the **native binary** unless:

- You need JVM JAR plugins (ServiceLoader-based) - use the JVM fat JAR
- You want a GUI installer with system integration (Start Menu, Applications folder) - use JPackage

See [choosing.md](choosing.md) for a detailed decision guide.

## Building All Distributions

All commands run from the `karandash/` directory.

```bash
# Native binary - macOS ARM64
./gradlew :cli:linkReleaseExecutableMacosArm64
# Output: cli/build/bin/macosArm64/releaseExecutable/cli.kexe

# Native binary - macOS x64
./gradlew :cli:linkReleaseExecutableMacosX64

# Native binary - Linux x64
./gradlew :cli:linkReleaseExecutableLinuxX64

# Native binary - Windows x64
./gradlew :cli:linkReleaseExecutableMingwX64

# JVM fat JAR
./gradlew :cli:shadowJar
# Output: cli/build/libs/karandash.jar
```
