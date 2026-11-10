# Native Binary

Karandash ships as a self-contained native binary compiled with Kotlin/Native. No JVM required.

## Overview

The native binary is the **primary distribution target**. It is compiled ahead-of-time for each platform using Kotlin/Native (LLVM backend) and requires no Java runtime.

**Characteristics:**

- Single executable file, no runtime dependencies
- Sub-100ms cold startup (benchmark: ~50ms on macOS ARM64)
- Supports all plugin types: native (`.dylib`/`.so`/`.dll`), JavaScript (`.mjs` via QuickJS), WASM (`.wasm` via Chasm, compiled in), and JAR (via subprocess bridge, requires Java 17+)

## Supported Platforms

| Platform | Target | File |
|----------|--------|------|
| macOS Apple Silicon | `macosArm64` | `cli.kexe` |
| macOS Intel | `macosX64` | `cli.kexe` |
| Linux x64 | `linuxX64` | `cli.kexe` |
| Windows x64 | `mingwX64` | `cli.exe` |

## Downloading

Download the pre-built binary for your platform from the [releases page](https://github.com/appKODE/karandash/releases).

Each release also ships `SHA256SUMS`; verify a download with `grep ' karandash-linux-x64$' SHA256SUMS | sha256sum -c -` (use `shasum -a 256 -c` on macOS).

### macOS ARM64 (Apple Silicon)

```bash
curl -L https://github.com/appKODE/karandash/releases/latest/download/karandash-macos-arm64 \
  -o karandash
chmod +x karandash
sudo mv karandash /usr/local/bin/
karandash --version
```

### macOS x64 (Intel)

```bash
curl -L https://github.com/appKODE/karandash/releases/latest/download/karandash-macos-x64 \
  -o karandash
chmod +x karandash
sudo mv karandash /usr/local/bin/
karandash --version
```

### Linux x64

```bash
curl -L https://github.com/appKODE/karandash/releases/latest/download/karandash-linux-x64 \
  -o karandash
chmod +x karandash
sudo mv karandash /usr/local/bin/
karandash --version
```

### Windows x64

Download `karandash-windows-x64.exe` from the releases page and add it to your `PATH`.

## Building from Source

### Prerequisites

- JDK 17+ (required to run Gradle; not needed at runtime)
- Gradle wrapper (`./gradlew`) included in the repository
- macOS: Xcode Command Line Tools (`xcode-select --install`)
- Linux: OpenSSL headers (`sudo apt-get install libssl-dev`)

All commands run from the `karandash/` directory.

### Build for macOS ARM64

```bash
./gradlew :cli:linkReleaseExecutableMacosArm64
```

Output: `cli/build/bin/macosArm64/releaseExecutable/cli.kexe`

### Build for macOS x64

```bash
./gradlew :cli:linkReleaseExecutableMacosX64
```

Output: `cli/build/bin/macosX64/releaseExecutable/cli.kexe`

### Build for Linux x64

```bash
./gradlew :cli:linkReleaseExecutableLinuxX64
```

Output: `cli/build/bin/linuxX64/releaseExecutable/cli.kexe`

### Build for Windows x64

```bash
./gradlew :cli:linkReleaseExecutableMingwX64
```

Output: `cli/build/bin/mingwX64/releaseExecutable/cli.exe`

### Debug builds

Add `Debug` instead of `Release` to get an unoptimized binary with debug symbols:

```bash
./gradlew :cli:linkDebugExecutableMacosArm64
# Output: cli/build/bin/macosArm64/debugExecutable/cli.kexe
```

## Testing the Binary

```bash
# Check version
./cli/build/bin/macosArm64/releaseExecutable/cli.kexe --version

# Validate a merge request
./cli/build/bin/macosArm64/releaseExecutable/cli.kexe validate \
  --platform gitlab \
  --project my-group/my-repo \
  --mr 42
```

## Native Tests

```bash
# Run native tests on macOS ARM64
./gradlew macosArm64Test

# macOS x64 tests (disabled by default on Apple Silicon)
./gradlew macosX64Test -Pkarandash.tests.includeMacosX64=true
```

## Plugin Support

The native binary supports all four plugin types. JAR plugins are loaded via a subprocess bridge that spawns a JVM process; Java 17+ must be available on `PATH` or `JAVA_HOME`.

| Plugin type | Supported | Loaded via |
|-------------|-----------|------------|
| Native (`.dylib`/`.so`/`.dll`) | Yes | `dlopen` |
| JavaScript (`.mjs`) | Yes | QuickJS (embedded) |
| WASM (`.wasm`) | Yes | Chasm (pure Kotlin, compiled in) |
| JVM JAR | Yes (requires Java 17+) | subprocess bridge (`JarBridgeServer`) |

The macOS x64 binary runs WASM on Chasm 1.7.0 (Chasm 2.x publishes no macosX64 build); the other native binaries
run Chasm 2.2.0. A 1.7.0 store cannot be interrupted: a WASM call or start function that overruns its time limit
still fails at the limit and the plugin is abandoned, but its guest thread keeps running until the process exits.

## CI/CD Integration

### GitHub Actions

```yaml
jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Download Karandash
        run: |
          curl -L https://github.com/appKODE/karandash/releases/latest/download/karandash-linux-x64 \
            -o karandash
          chmod +x karandash

      - name: Validate MR
        run: ./karandash validate
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

### GitLab CI

```yaml
validate:mr:
  stage: test
  script:
    - curl -L https://github.com/appKODE/karandash/releases/latest/download/karandash-linux-x64
        -o karandash
    - chmod +x karandash
    - ./karandash validate
  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
```

## Cross-Compilation

Kotlin/Native supports limited cross-compilation. Building a Linux binary from macOS works for most cases but requires the Kotlin/Native toolchain to download sysroots automatically.

Building Windows binaries from macOS or Linux requires the MinGW toolchain:

```bash
brew install mingw-w64  # macOS
```

Clear the Kotlin/Native cache if cross-compilation fails:

```bash
rm -rf ~/.konan
./gradlew :cli:linkReleaseExecutableLinuxX64
```

## Troubleshooting

### "OpenSSL not found" on Linux

```bash
sudo apt-get install libssl-dev
# or on RPM-based distros:
sudo dnf install openssl-devel
```

### "OpenSSL not found" on macOS

```bash
brew install openssl@3
export LDFLAGS="-L$(brew --prefix openssl@3)/lib"
export CPPFLAGS="-I$(brew --prefix openssl@3)/include"
./gradlew :cli:linkReleaseExecutableMacosArm64
```

### "TLS verification failed" for a private CA or self-signed server

The macOS and Linux binaries trust their built-in CA bundle. To trust a corporate CA or a self-signed
certificate (a self-hosted GitLab, a remote `extends:` server, a private plugin registry), point
`SSL_CERT_FILE` (or `CURL_CA_BUNDLE`) at a PEM bundle that contains it. The file replaces the default bundle, so
include the public CAs too if you also reach public hosts:

```bash
SSL_CERT_FILE=/etc/ssl/corp-ca-bundle.pem karandash validate --pr <url>
```

The JVM distribution uses the Java truststore instead (`-Djavax.net.ssl.trustStore=...`).

### Reaching a server by IP while its DNS is down

A certificate names the host, not its IP, so `https://<ip>/...` fails TLS verification. For a manual run,
`KARANDASH_TLS_INSECURE_HOSTS` lists hosts whose certificates are not checked:

```bash
KARANDASH_TLS_INSECURE_HOSTS=203.0.113.10 karandash validate --pr https://203.0.113.10/group/project/-/merge_requests/1
```

Only the platform client keyed for those hosts skips verification; AI providers and every other host are still
verified, and that client refuses requests and redirects to any unlisted host. Anyone on the network path can read
the unverified traffic, token included, so prefer an `/etc/hosts` entry (`<ip> <host name>`), which keeps
verification on, and never set the variable in CI. Windows ignores it.

### macOS: "cannot be opened because the developer cannot be verified"

The binary downloaded from releases is not notarized. Remove the quarantine attribute:

```bash
xattr -d com.apple.quarantine /usr/local/bin/karandash
```

Builds from source are not affected.
