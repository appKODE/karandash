# Choosing the Right Distribution

Karandash offers multiple distribution options to fit different use cases. This guide helps you choose the right one.

## Quick Recommendation

- **Most users** → [Hybrid Distribution](#hybrid-distribution)
- **CI/CD pipelines** → [Native Image](#native-image-distribution) (when available)
- **Maximum compatibility** → [Standard JAR](#standard-jar-distribution)

## Distribution Comparison

| Feature | Standard JAR | Hybrid | Native Image |
|---------|-------------|--------|--------------|
| **Size** | 45MB | 58MB | 18-25MB |
| **Startup Time** | ~720ms | ~720ms | ~50ms |
| **Java Required** | Yes (17+) | Yes (17+) | No |
| **JAR Plugins** | yes | yes | yes (Java 17+ required) |
| **Native Plugins** | yes | yes | yes |
| **JS Plugins** | yes | yes | yes |
| **Cross-Platform** | yes | yes | Per-platform binary |
| **Status** | Production | Production | ⏳ Config Ready |

## Standard JAR Distribution

**Best for:** Maximum compatibility and flexibility

### Pros
- Works on any platform with Java 17+
- Full plugin ecosystem support
- Single file download (45MB)
- Cross-platform compatible

### Cons
- Requires Java installation
- Slower startup (~720ms)

### Installation

```bash
# Download karandash.jar
# Run with:
./karandash validate --mr 123
```

### Use Cases
- Development environments where Java is already installed
- Need for full plugin compatibility
- Multi-platform deployment with single artifact

## Hybrid Distribution

**Best for:** Production use with optimal balance

### Pros
- Full plugin support (JAR, Native, JS)
- Native validators for core performance
- Graceful fallback to JVM validators
- Tested and stable

### Cons
- Slightly larger than standard JAR (58MB vs 45MB)
- Still requires Java installation

### Installation

See [Distributions Overview](README.md) for detailed instructions.

```bash
# Download and extract
tar -xzf karandash-hybrid-0.1.0-macos-arm64.tar.gz

# Add to PATH
export PATH="$PATH:/path/to/karandash-hybrid-0.1.0-macos-arm64/bin"

# Use it
karandash validate --mr 123
```

### Use Cases
- Production deployments
- Teams wanting performance benefits without sacrificing compatibility
- Environments where Java is available

## Native Image Distribution

**Best for:** CI/CD and performance-critical use cases

### Pros
- Instant startup (~50ms, ~14x faster)
- No Java installation required
- Smallest binary size (18-25MB)
- Perfect for automation workflows

### Cons
- JAR plugins require Java 17+ available on the host (other plugin types have no extra dependency)
- Requires per-platform compilation
- More complex build process

### Installation

See [Native Image Guide](native-image.md) for build instructions.

```bash
# After building with Kotlin/Native:
./cli/build/bin/macosArm64/releaseExecutable/cli.kexe validate --mr 123
```

### Use Cases
- CI/CD pipelines where startup speed matters
- Docker containers with minimal base images
- Environments without Java
- Serverless functions

## Decision Tree

```
Do you need fastest startup?
├─ Yes → Native Image
│
└─ No → Is Java already installed?
    ├─ Yes → Hybrid Distribution (recommended) or Standard JAR
    └─ No → Native Image (all plugin types supported; JAR plugins need Java 17+)
```

## Platform-Specific Notes

### macOS

All distributions work well on macOS. Hybrid distribution is recommended for most users.

**ARM64 (Apple Silicon):**
- Use `macos-arm64` distributions
- Native validators compiled specifically for M1/M2/M3 chips

**x64 (Intel):**
- Use `macos-x64` distributions
- Full compatibility with Intel Macs

### Linux

**Recommended:** Hybrid distribution

- Use `linux-x64` distributions
- Native validators compiled for Linux x86_64
- Tested on Ubuntu, Debian, and CentOS

### Windows

**Recommended:** Hybrid distribution

- Use `windows-x64` distributions
- Native validators compiled for Windows x64
- Use Git Bash or WSL for shell scripts

## Benchmarks

Measured 2026-10-19 on macOS ARM64 (`tools/benchmarks/benchmark-cli.sh`, 30 runs, `validate --dry-run` on the
`tools/benchmarks/fixtures/configs` fixtures), mean ms:

| Command | Native (`cli.kexe`) | JAR (`java -jar`) |
|---------|--------------------|-------------------|
| `--version` (cold start) | 52 | 717 |
| `validate`, 3 rules | 87 | 810 |
| `validate`, 21 rules | 71 | 843 |

Most of a native `validate` is loading the 116 built-in modules (~35ms). `tools/ci/gate.sh` runs
`tools/benchmarks/benchmark-regression-check.sh` on both against the thresholds in
`tools/benchmarks/baseline-macos-arm64.json`.

## Migration Guide

### From Standard JAR to Hybrid

No changes needed! The hybrid distribution is fully compatible:

```bash
# Before
./karandash validate

# After
karandash validate  # Same commands work
```

### From Hybrid to Native Image

No plugin changes required. All plugin types work on Native Image. If you use JAR plugins, make sure Java 17+ is available on the host machine.

## Support Matrix

| Distribution | macOS ARM64 | macOS x64 | Linux x64 | Windows x64 |
|-------------|-------------|-----------|-----------|-------------|
| Standard JAR | yes | yes | yes | yes |
| Hybrid | yes | yes | yes | yes |
| Native Image | ⏳ | ⏳ | ⏳ | ⏳ |

yes = production ready  
⏳ = configuration ready (needs testing)

## FAQ

### Q: Which distribution is fastest?

**A:** Native Image: ~50ms startup and 70-90ms per `validate`. Hybrid and Standard JAR run on the same JVM: ~720ms startup, ~810-840ms per `validate`.

### Q: Can I switch distributions later?

**A:** Yes! All distributions use the same configuration files and commands. Migration needs no changes.

### Q: Do I lose functionality with Native Image?

**A:** No. All plugin types (JAR, Native, JavaScript, WASM) and all 21 built-in validators work on Native Image. JAR plugins require Java 17+ available on the host; all other plugin types have no extra dependency.

### Q: Which distribution uses less memory?

**A:** Native Image typically uses less memory than JVM-based distributions, but the difference is minimal for typical use cases.

### Q: Can I use custom plugins with Hybrid?

**A:** Yes! Hybrid distribution supports all plugin types (JAR, Native, JavaScript).

## Getting Help

- Documentation: [docs home](../index.md)
- Issues: [GitHub Issues](https://github.com/appKODE/karandash/issues)
- Discussions: [GitHub Discussions](https://github.com/appKODE/karandash/discussions)
