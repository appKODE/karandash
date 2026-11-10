# WASM C Plugin Example

A Karandash WASM plugin written in C, targeting `wasm32` without WASI.

## Rule

| Rule ID | Description |
|---------|-------------|
| `wasm-c-title-check` | Title must be non-empty and at least 5 characters |

## Build

```bash
chmod +x build.sh
./build.sh
```

Requires either Emscripten (`emcc`) or LLVM/Clang with `wasm32` target support.

## Install

```bash
cp plugin.wasm ~/.karandash/plugins/
```

## Notes

- Uses a 64KB bump allocator (no libc/WASI required)
- Implements WASM ABI v1 with length-prefixed string protocol
- JSON parsing uses minimal byte scanning (no external dependencies)
