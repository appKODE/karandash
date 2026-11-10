# Karandash WASM Plugin Example (Rust)

A Karandash plugin compiled to `wasm32-unknown-unknown` implementing RuleModule and ReporterModule via the WASM ABI v1.

## Rules

| Rule ID | Severity | Description |
|---|---|---|
| `wasm-title-length` | WARNING | Title must be at least 10 characters |
| `wasm-description-required` | ERROR | Description must be present and non-empty |

## Reporter

Format ID: `wasm-text` - plain text summary of findings.

## Build

```bash
# Install target (once)
rustup target add wasm32-unknown-unknown

# Build
./build.sh
```

Output: `karandash_wasm_example.wasm`

## WASM ABI

This plugin uses the Karandash WASM ABI v1:

- All strings returned to the host are **length-prefixed**: 4-byte little-endian `u32` length followed by UTF-8 bytes.
- All strings received from the host arrive as a raw pointer + separate length argument.
- The host calls `karandash_alloc` / `karandash_dealloc` to manage memory for strings it writes into the plugin.

## Usage

Register in `.karandash/config.yaml`:

```yaml
plugins:
  - path: ./karandash_wasm_example.wasm

rules:
  wasm-title-length: true
  wasm-description-required: true

reporters:
  wasm-text: true
```
