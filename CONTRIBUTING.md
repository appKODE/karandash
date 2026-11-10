# Contributing to Karandash

Thank you for your interest in contributing to Karandash! This guide will help you get started.

## Getting Started

1. Fork the repository
2. Clone your fork
3. Create a feature branch from `main`
4. Make your changes
5. Submit a merge request

## Development Setup

### Prerequisites

- JDK 17+
- Gradle 9.x (wrapper included)
- macOS (for native ARM64 builds) or Linux x64

### Building

```bash
cd karandash
./gradlew build          # Full build (JVM + native)
./gradlew jvmJar jvmTest # JVM-only build
```

### Running Tests

```bash
./gradlew jvmTest                  # JVM tests
./gradlew macosArm64Test           # Native tests (macOS)
./gradlew ktlintCheck detekt       # Lint + static analysis
```

## Code Guidelines

### Architecture

- All code must target **all supported platforms** (JVM, macOS ARM64/x64, Linux x64, Windows MinGW)
- Use `commonMain` source sets by default; platform-specific code requires `expect/actual`
- Follow the existing module structure under `plugins/modules/`

### Style

- Code must pass **ktlint** and **detekt** with zero violations
- Run `./gradlew ktlintFormat` to auto-fix formatting issues
- Use KDoc for all public API surfaces
- No commented-out code or magic strings

### Testing

- Write tests in `commonTest` (runs on all targets)
- Use `kotlin.test` for assertions, `runTest {}` for coroutines
- No live API calls in tests; use Ktor `MockEngine` for HTTP stubs
- Cover both happy paths and error cases

### Plugin Changes

When modifying plugin interfaces or adding new modules, you must update **all plugin bridges**:
- JavaScript bridge (`JsModuleBridge`)
- WASM bridge (`WasmModuleBridge`)
- Native C-ABI bridge (`NativeModuleBridge` on JVM, `TransportModuleBridge` on the native host)
- JAR bridge (the `JarBridgeDispatcher` op tables `JarModuleOps` / `JarPlatformOps` / `JarAiOps`, served by `JarBridgeServer`)
- Guest op table shared by the JAR bridge and the K/N C ABI exports (`GuestOpTable` in `plugins/helpers`: validate, report, platform ops)

See `docs/development/conventions.md` for the full Plugin Bridge Completeness Rule.

## Submitting Changes

### Commit Messages

Use conventional commit format:

```
feat: add new validation rule for X
fix: correct dispatch for kebab-case rule IDs
docs: update plugin authoring guide
```

### Merge Request Process

1. Make sure all tests pass: `./gradlew jvmTest macosArm64Test`
2. Make sure lint passes: `./gradlew ktlintCheck detekt`
3. Update documentation if your change affects user-facing behavior
4. Update `CHANGELOG.md` with your changes
5. Request review from a maintainer

### What We Look For

- Does the change work on all supported platforms?
- Are there tests covering the new behavior?
- Does the code follow existing patterns and conventions?
- Is the change minimal and focused?

## Reporting Issues

- Use GitLab Issues to report bugs or request features
- Include reproduction steps, expected behavior, and actual behavior
- For security-related issues, see `SECURITY.md`

## License

By contributing, you agree that your contributions will be licensed under the project's MIT License.
