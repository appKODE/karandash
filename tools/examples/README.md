# Karandash Examples

This directory contains configuration examples and plugin implementation samples for Karandash.

## Overview

The examples are organized into three main categories:

1. **Configuration Files** - Ready-to-use `.kts` config files demonstrating various validation scenarios
2. **Plugin Examples** - Complete plugin implementations in multiple languages (JVM, JavaScript, Native)
3. **Sample Data** - Test MR/PR JSON files for validation testing

## Configuration Examples

All configuration files use Karandash's Kotlin-like DSL and are located in this directory:

| File | Description | Use Case |
|------|-------------|----------|
| `minimal-config.kts` | Bare minimum configuration | Quick start, basic title and size validation |
| `simple-config.kts` | Basic rules with common validators | Small teams, conventional commits |
| `standard-config.kts` | Balanced configuration for most teams | Production use, approval workflows |
| `github-specific.kts` | GitHub-optimized with Actions integration | GitHub repos, SARIF reporting, CODEOWNERS |
| `aws-codecommit.kts` | AWS CodeCommit with IAM authentication | AWS environments, CodeCommit repos |
| `full-config.kts` | Comprehensive showcase of all 21 validators | Reference, advanced customization |

### Quick Start with Configs

```bash
# Use minimal config for testing
karandash validate --config examples/minimal-config.kts --mr examples/sample-mr.json

# Try the standard config
karandash validate --config examples/standard-config.kts --mr examples/valid-mr.json

# GitHub-specific with SARIF output
karandash validate --config examples/github-specific.kts --mr examples/valid-mr.json
```

## Plugin Examples by Language

### JVM Plugins (Kotlin/Java)

**Directory:** `standalone-jvm-plugin/`

Full-featured Kotlin plugin example demonstrating the JVM plugin API.

- Language: Kotlin
- Build: Gradle
- Prerequisites: JDK 17+
- [View README](standalone-jvm-plugin/README.md)

```bash
cd examples/standalone-jvm-plugin
./gradlew build
```

### JavaScript/Node.js Plugins

**Directory:** `standalone-js-plugin/`

Lightweight JavaScript plugin using ES modules and modern Node.js features.

- Language: JavaScript (ES2022+)
- Runtime: Node.js 18+
- Prerequisites: Node.js 18+, npm
- [View README](standalone-js-plugin/README.md)

```bash
cd examples/standalone-js-plugin
npm install
node example-rule.mjs
```

### Native Plugins (C/C++)

**Directory:** `standalone-native-plugin/`

High-performance C native plugin with FFI integration.

- Language: C
- Build: CMake or Make
- Prerequisites: GCC/Clang, CMake 3.10+
- [View README](standalone-native-plugin/README.md)

```bash
cd examples/standalone-native-plugin
make
```

### Native Plugins (Go)

**Directory:** `native-plugin-go/`

Go-based native plugin compiled to shared library with CGo.

- Language: Go
- Build: Go build with CGo
- Prerequisites: Go 1.21+, GCC/Clang
- [View README](native-plugin-go/README.md)

```bash
cd examples/native-plugin-go
make
```

### Native Plugins (Kotlin Native)

**Directory:** `native-plugin-kotlin/`

Kotlin/Native plugin demonstrating multiplatform capabilities.

- Language: Kotlin/Native
- Build: Gradle with Kotlin/Native plugin
- Prerequisites: JDK 17+
- [View README](native-plugin-kotlin/README.md)

```bash
cd examples/native-plugin-kotlin
./gradlew build
```

### Native Plugins (Rust)

**Directory:** `native-plugin-rust/`

Rust native plugin with memory safety and performance.

- Language: Rust
- Build: Cargo
- Prerequisites: Rust 1.70+
- [View README](native-plugin-rust/README.md)

```bash
cd examples/native-plugin-rust
cargo build --release
```

## Sample Data Files

### `sample-mr.json`

Basic merge request JSON for testing validators without JIRA ticket reference.

```json
{
  "title": "feat: Add new validation rule for commit messages",
  "source_branch": "feature/commit-validation",
  "target_branch": "main",
  "draft": false,
  "labels": ["enhancement", "validation"]
}
```

### `valid-mr.json`

Complete merge request JSON with JIRA ticket reference (PROJ-789).

```json
{
  "title": "feat: Add new validation rule for commit messages PROJ-789",
  "source_branch": "feature/commit-validation",
  "target_branch": "main",
  "draft": false,
  "labels": ["enhancement", "validation"]
}
```

## Prerequisites by Plugin Type

### JVM Plugins
- JDK 17 or higher
- Gradle 8.0+ (wrapper included)

### JavaScript Plugins
- Node.js 18 or higher
- npm 9+

### Native Plugins (C/C++)
- GCC 9+ or Clang 10+
- CMake 3.10+ (for CMake builds)
- Make (for Makefile builds)

### Native Plugins (Go)
- Go 1.21 or higher
- GCC/Clang for CGo compilation

### Native Plugins (Kotlin Native)
- JDK 17 or higher
- Gradle 8.0+ (wrapper included)

### Native Plugins (Rust)
- Rust 1.70 or higher
- Cargo (included with Rust)

## Testing Your Plugin

Each plugin example includes:

1. Complete source code
2. Build configuration
3. README with setup instructions
4. Example validation rules
5. Testing commands

### Basic Plugin Test Flow

```bash
# 1. Navigate to plugin directory
cd examples/standalone-jvm-plugin

# 2. Build the plugin
./gradlew build

# 3. Test with sample data
cp build/libs/*.jar ~/.karandash/plugins/
karandash validate --mr ../sample-mr.json

# 4. Verify output
echo $?  # Should be 0 for success
```

## Plugin Selection Guide

| Choose... | When you need... |
|-----------|------------------|
| **JVM** | Full Kotlin/Java ecosystem, Spring integration, enterprise features |
| **JavaScript** | Quick prototyping, npm ecosystem, serverless deployment |
| **C Native** | Maximum performance, minimal dependencies, embedded systems |
| **Go Native** | Fast compilation, simple deployment, Go ecosystem |
| **Kotlin Native** | Multiplatform support, Kotlin coroutines, type safety |
| **Rust Native** | Memory safety, performance, systems programming |

## Next Steps

1. **Start with configs**: Copy and modify a configuration file to match your team's workflow
2. **Test with sample data**: Use `sample-mr.json` or `valid-mr.json` to test your config
3. **Explore plugins**: Choose a plugin example in your preferred language
4. **Build and customize**: Follow the plugin README to build and extend functionality

## Additional Resources

- [Main Documentation](../../README.md)
- [Plugin API Reference](../docs/plugin-api.md)
- [Configuration Guide](../docs/configuration.md)
- [Validator Reference](../docs/validators.md)

## Questions?

Each plugin directory contains a detailed README with:
- Architecture overview
- Build instructions
- API documentation
- Troubleshooting tips
- Extension examples

Check the individual plugin READMEs for language-specific guidance.
