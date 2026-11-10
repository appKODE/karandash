# standalone-wasm-rust-plugin

A minimal Rust WASM plugin for Karandash that implements a single rule:

**Rule `wasm-rust-standalone-check`** - MR title must start with a conventional commit prefix:
`feat:`, `fix:`, `chore:`, `refactor:`, `docs:`, `test:`, or `ci:`

Uses the Karandash WASM ABI v1: strings are 4-byte LE length-prefixed. No external dependencies.

## Build

```bash
./build.sh
```

Output: `plugin.wasm`

## Requirements

- Rust stable toolchain with `wasm32-unknown-unknown` target:
  ```bash
  rustup target add wasm32-unknown-unknown
  ```

## Usage

```yaml
plugins:
  - path: "./plugin.wasm"
```
