# standalone-kotlin-plugin

A minimal Kotlin/Native plugin for Karandash that implements a single rule:

**Rule `kotlin-title-check`** - MR title must start with a conventional commit prefix:
`feat:`, `fix:`, `chore:`, `refactor:`, `docs:`, `test:`, or `ci:`

## Build

```bash
# macOS ARM64
./gradlew linkReleaseSharedMacosArm64

# Linux x64
./gradlew linkReleaseSharedLinuxX64
```

Output: `build/bin/macosArm64/releaseShared/libkotlin-standalone.dylib` or
        `build/bin/linuxX64/releaseShared/libkotlin-standalone.so`

## Requirements

- JDK 17+, Gradle (wrapper included)

## Usage

```yaml
plugins:
  - path: "./libkotlin-standalone.dylib"
```
