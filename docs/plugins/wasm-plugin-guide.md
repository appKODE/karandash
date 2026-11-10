# WASM Plugin Guide

Write Karandash plugins in any language that compiles to WebAssembly.

## Overview

WASM plugins run in a sandboxed runtime (Chicory on JVM, Chasm on native) and communicate with Karandash through a defined ABI. This guide covers the ABI contract, memory protocol, and development workflow.

## Supported Languages

Any language that compiles to `.wasm` (WebAssembly MVP):

- **Rust** (recommended) -- `wasm32-unknown-unknown` target
- **C/C++** -- via Emscripten or clang `--target=wasm32`
- **Go** -- via TinyGo
- **AssemblyScript** -- TypeScript-like syntax

## ABI Contract

Your WASM module must export the following functions:

| Export | Signature | Required |
|--------|-----------|----------|
| `karandash_abi_version` | `() -> i32` | Yes |
| `karandash_module_id` | `() -> i32` (ptr) | Yes |
| `karandash_module_type` | `() -> i32` (ptr) | Yes |
| `karandash_validate` | `(ptr, len) -> i32` (ptr) | Yes (rules) |
| `karandash_config_schema` | `() -> i32` (ptr) | Optional |
| `karandash_rule_explanation` | `(ptr, len) -> i32` (ptr) | Optional |
| `karandash_alloc` | `(i32) -> i32` | Yes |
| `karandash_dealloc` | `(i32, i32)` | Yes |

### ABI Version

Return `1` for the current ABI version:

```rust
#[no_mangle]
pub extern "C" fn karandash_abi_version() -> i32 { 1 }
```

## Memory Protocol

Strings are exchanged as length-prefixed data in linear memory:

1. Karandash calls `karandash_alloc(len)` to allocate input buffer
2. Writes JSON data to the allocated buffer
3. Calls `karandash_validate(ptr, len)`
4. Reads the result from the returned pointer (first 4 bytes = length)

## Example (Rust)

```rust
use std::alloc::{alloc, dealloc, Layout};

#[no_mangle]
pub extern "C" fn karandash_abi_version() -> i32 { 1 }

#[no_mangle]
pub extern "C" fn karandash_module_id() -> *const u8 {
    let id = b"my-wasm-rule";
    write_length_prefixed(id)
}

#[no_mangle]
pub extern "C" fn karandash_module_type() -> *const u8 {
    let module_type = b"rule"; // e.g. "rule", "reporter", "platform-service"
    write_length_prefixed(module_type)
}

#[no_mangle]
pub extern "C" fn karandash_validate(ptr: *const u8, len: i32) -> *const u8 {
    let input = unsafe { std::slice::from_raw_parts(ptr, len as usize) };
    let json_str = std::str::from_utf8(input).unwrap_or("{}");
    // Parse MR data and validate...
    let result = b"[]"; // empty findings = pass
    write_length_prefixed(result)
}

#[no_mangle]
pub extern "C" fn karandash_alloc(size: i32) -> *mut u8 {
    let layout = Layout::from_size_align(size as usize, 1).unwrap();
    unsafe { alloc(layout) }
}

#[no_mangle]
pub extern "C" fn karandash_dealloc(ptr: *mut u8, size: i32) {
    let layout = Layout::from_size_align(size as usize, 1).unwrap();
    unsafe { dealloc(ptr, layout) }
}
```

## Building

```bash
# Rust
cargo build --target wasm32-unknown-unknown --release
cp target/wasm32-unknown-unknown/release/my_plugin.wasm .

# C (with clang)
clang --target=wasm32 -nostdlib -Wl,--no-entry -Wl,--export-all -o my_plugin.wasm my_plugin.c
```

## Serving Several Module Types

`karandash_describe` returns `{"id": ..., "types": [...], "properties": {...}}`. Every type in
`types` besides the primary one (`karandash_module_type`, listed first) is registered as its own
part under the same id, so one binary can be a rule, a reporter, a profile and a CLI command at once;
export the symbols each type needs (see [WASM exports](../native-sdk/wasm-exports.md)).
`AI_PIPELINE_STAGE` has no SDK interface and is skipped with a warning. The host reads the binary's
export section, so an optional symbol you leave out (for example `karandash_report_enriched`) falls
back to its plain counterpart instead of trapping. Example:
`tools/examples/standalone-wasm-c-plugin/plugin.c`.

CLI commands listed by `karandash_cli_commands` run through the optional
`karandash_run_cli_command(command, args_json)` export: `command` is the leaf's path (names joined by
a space), `args_json` a JSON array of the remaining arguments, and the result is
`{"exitCode":n,"stdout":[...],"stderr":[...]}`. Without the export the commands are listed but not runnable.

## Loading

Place the `.wasm` file in your plugins directory and reference it in config:

```yaml
plugins:
  - path: ./plugins/my-wasm-rule.wasm
```

## Signing

Sign the plugin for production use:

```bash
karandash plugin sign my-wasm-rule.wasm --key private.pem
```
