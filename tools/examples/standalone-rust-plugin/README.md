# standalone-rust-plugin

A minimal Rust native plugin for Karandash that implements a single rule:

**Rule `rust-title-check`** - MR title must start with a conventional commit prefix:
`feat:`, `fix:`, `chore:`, `refactor:`, `docs:`, `test:`, or `ci:`

## Build

```bash
make
```

Output: `librust-standalone.dylib` (macOS) or `librust-standalone.so` (Linux)

## Requirements

- Rust stable toolchain (`cargo`)

## Usage

```yaml
plugins:
  - path: "./librust-standalone.dylib"
```
