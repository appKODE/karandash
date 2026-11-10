# Rust Plugin Example

A complete, self-contained example of a Karandash rule plugin written in Rust using the native plugin ABI.

## What This Plugin Does

Provides one validation rule:

1. **rust-title-length**: Checks that MR title is at least 10 characters long

**Example validations:**
- OK: Title: "Add new feature" (15 chars, valid)
- No: Title: "Add auth" (8 chars, too short)

## Prerequisites

- Rust 1.70 or later (install via [rustup](https://rustup.rs/))
- Standard Rust toolchain (no additional tools)
- For cross-compilation: additional target toolchains (see Building section)

## Building

### Option 1: Default Target (Recommended)

```bash
cargo build --release
```

This produces:
- **macOS**: `target/release/libkarandash_rust_example.dylib`
- **Linux**: `target/release/libkarandash_rust_example.so`

### Option 2: Explicit Cross-Compilation

**macOS ARM64:**
```bash
cargo build --release --target aarch64-apple-darwin
```

**macOS x64:**
```bash
cargo build --release --target x86_64-apple-darwin
```

**Linux x64:**
```bash
cargo build --release --target x86_64-unknown-linux-gnu
```

First, ensure the target is installed:
```bash
rustup target add aarch64-apple-darwin
rustup target add x86_64-apple-darwin
rustup target add x86_64-unknown-linux-gnu
```

## Installation

### Option 1: Using Karandash CLI

```bash
karandash plugin install target/release/libkarandash_rust_example.dylib
```

### Option 2: Manual Installation

```bash
mkdir -p ~/.karandash/plugins
cp target/release/libkarandash_rust_example.dylib ~/.karandash/plugins/
# Or .so on Linux
```

### Verify Installation

```bash
karandash plugin list
```

You should see "Rust Example Plugin" in the list.

## Configuration

Add the rule to your `karandash.config.kts`:

```kotlin
rules {
    rule("rust-title-length") {
        severity = warning
    }
}
```

This rule doesn't require additional configuration in this example.

## Project Structure

```
native-plugin-rust/
├── Cargo.toml                 # Cargo workspace (one crate per module type)
├── rule-reporter/src/lib.rs   # RULE + REPORTER plugin
├── <module-type>/src/lib.rs   # provider, mutator, ai-*, script, ...
└── README.md                  # This file
../karandash-plugin-common/    # MergeRequest/Finding JSON models, shared with wasm-plugin-rust
```

## How It Works

### 1. Native Plugin ABI

The plugin implements the Karandash native plugin ABI v1 by exporting C functions using `#[no_mangle]` and `extern "C"`:

```rust
#[no_mangle]
pub extern "C" fn karandash_module_name() -> *const c_char {
    to_c_string(MODULE_NAME)
}
```

The `#[no_mangle]` attribute prevents Rust's name mangling, making the function callable from C. `extern "C"` uses the C calling convention.

### 2. Memory Management

All strings returned by the plugin must be heap-allocated. Rust's `CString` type manages C string allocation:

```rust
fn to_c_string(s: &str) -> *const c_char {
    CString::new(s).unwrap_or_default().into_raw()
}
```

The host calls `karandash_free_string()` to free returned strings using `CString::from_raw()`:

```rust
#[no_mangle]
pub extern "C" fn karandash_free_string(ptr: *const c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr as *mut c_char));
        }
    }
}
```

### 3. JSON Communication

Complex data is passed as JSON strings. The plugin:
- Receives **MR data** as JSON (title, description, branches, commits, files)
- Receives **configuration** as JSON (rule config from karandash.config.kts)
- Returns **findings** as JSON array

The example uses `serde` and `serde_json` for type-safe JSON handling:

```rust
let mr: MergeRequest = serde_json::from_str(mr_str)?;
let json = serde_json::to_string(&findings)?;
```

### 4. Unsafe Code Boundaries

Rust requires unsafe blocks for C FFI operations. The example minimizes unsafe code:

```rust
unsafe fn from_c_str<'a>(ptr: *const c_char) -> Option<&'a str> {
    if ptr.is_null() {
        return None;
    }
    CStr::from_ptr(ptr).to_str().ok()
}
```

## JSON Schema

The plugin receives MR data in this JSON format:

```json
{
  "id": "12345",
  "iid": 1,
  "title": "Fix parser bug",
  "description": "Fixes issue with...",
  "source_branch": "bugfix/parser",
  "target_branch": "main",
  "author": {
    "username": "jdoe",
    "name": "John Doe",
    "email": "jdoe@example.com"
  },
  "state": "opened",
  "draft": false,
  "labels": ["bug"],
  "commits": [
    {
      "sha": "abc123",
      "message": "Fix parser",
      "author": {
        "name": "John Doe",
        "email": "jdoe@example.com"
      },
      "timestamp": "2024-01-15T10:30:00Z"
    }
  ],
  "changes": [
    {
      "path": "src/parser.rs",
      "changeType": "MODIFIED",
      "additions": 10,
      "deletions": 5
    }
  ],
  "approvals": [
    {
      "user": {
        "username": "reviewer",
        "name": "Jane Reviewer",
        "email": "jane@example.com"
      },
      "approved_at": "2024-01-15T10:30:00Z"
    }
  ]
}
```

**Critical field names:**
- OK: `source_branch` NOT `sourceBranch`
- OK: `target_branch` NOT `targetBranch`
- OK: `approved_at` NOT `approvedAt`

The plugin returns findings as a JSON array:

```json
[
  {
    "rule": "rust-title-length",
    "message": "Title is too short (8 chars, minimum 10)",
    "severity": "WARNING",
    "location": null,
    "explanation": null
  }
]
```

## Customizing This Plugin

To create your own Rust rule plugin:

### 1. Copy the Directory

```bash
cp -r native-plugin-rust my-rust-plugin
cp -r karandash-plugin-common my-rust-plugin/   # then point workspace.dependencies at "karandash-plugin-common"
cd my-rust-plugin
```

### 2. Update Package Metadata

Edit `Cargo.toml`:

```toml
[package]
name = "my-rust-plugin"
version = "1.0.0"

[lib]
crate-type = ["cdylib"]
```

### 3. Update Module Metadata

Edit `src/lib.rs`:

```rust
const MODULE_ID: &str = "my-custom-plugin";
const MODULE_NAME: &str = "My Custom Plugin";
const MODULE_VERSION: &str = "1.0.0";
```

### 4. Update Data Models

Edit `src/models.rs` if you need to parse additional MR fields.

### 5. Implement Your Rules

Update `karandash_contributed_rules()`:

```rust
#[no_mangle]
pub extern "C" fn karandash_contributed_rules() -> *const c_char {
    to_c_string(r#"["my-rule-1", "my-rule-2"]"#)
}
```

Implement validation in `karandash_validate()`:

```rust
#[no_mangle]
pub extern "C" fn karandash_validate(
    rule_id: *const c_char,
    mr_json: *const c_char,
    _config_json: *const c_char,
) -> *const c_char {
    let rule = match unsafe { from_c_str(rule_id) } {
        Some(s) => s,
        None => return to_c_string("[]"),
    };

    if rule == "my-rule-1" {
        let mr_str = match unsafe { from_c_str(mr_json) } {
            Some(s) => s,
            None => return to_c_string("[]"),
        };

        let mr: MergeRequest = match serde_json::from_str(mr_str) {
            Ok(mr) => mr,
            Err(_) => return to_c_string("[]"),
        };

        let mut findings = Vec::new();

        // Your validation logic here
        if some_violation {
            findings.push(Finding {
                severity: "ERROR".to_string(),
                message: "Validation failed".to_string(),
                rule: "my-rule-1".to_string(),
                location: None,
                explanation: None,
            });
        }

        let json = serde_json::to_string(&findings)
            .unwrap_or_else(|_| "[]".to_string());
        return to_c_string(&json);
    }

    to_c_string("[]")
}
```

### 6. Build and Test

```bash
cargo build --release
```

### 7. Install

```bash
mkdir -p ~/.karandash/plugins
cp target/release/libkarandash_rust_example.dylib ~/.karandash/plugins/
```

## Rust-Specific Notes

### Small Binary Size

Rust binaries are very small (~200KB for this example). This is because:
- No runtime is embedded (Rust is a systems language)
- Dependencies are statically linked
- No garbage collector overhead

### Using Serde for JSON

The example uses `serde` with `#[derive(Serialize, Deserialize)]` for type-safe JSON:

```rust
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize)]
pub struct MergeRequest {
    pub title: String,
    #[serde(rename = "source_branch")]
    pub source_branch: String,
    // ... other fields
}
```

The `#[serde(rename)]` attribute maps snake_case JSON fields to snake_case Rust fields (or use camelCase for Rust conventions).

### Unused Field Warnings

The example may show warnings for unused fields in `MergeRequest` and `Finding` structures. These are expected and harmless:

```
warning: field is never read: `commits`
```

This is fine for example code. In production, either use the fields or add `#[allow(dead_code)]`.

### FFI Type Mappings

Common Rust FFI type mappings:
- `*const c_char` = `char*` (C string, null-terminated)
- `*mut c_char` = `char*` (mutable C string)
- `i32` = `int`
- `c_int` = `int` (from `std::os::raw`)
- `*const u8` = `uint8_t*`

### CString vs CStr

- `CString`: Owned C string (like `std::string` in C++)
  - Allocated with `CString::new()`
  - Use `.into_raw()` to consume and get raw pointer
  - Host must call `CString::from_raw()` to free

- `CStr`: Borrowed C string (like `const char*` in C)
  - Borrowed from a pointer with `CStr::from_ptr()`
  - Convert to `&str` with `.to_str()`
  - No deallocation needed

## Testing

### Manual Testing

Rust doesn't have a built-in test binary for dynamic libraries, but you can write a test with `bindgen`:

```bash
cargo test
```

Or create a simple test program in `examples/test.rs`:

```rust
// examples/test.rs
use std::ffi::{CStr, CString};

extern "C" {
    fn karandash_abi_version() -> i32;
    fn karandash_module_name() -> *const u8;
    fn karandash_validate(rule_id: *const u8, mr_json: *const u8, config_json: *const u8) -> *const u8;
    fn karandash_free_string(ptr: *const u8);
}

fn main() {
    unsafe {
        println!("ABI version: {}", karandash_abi_version());
        // ... more tests
    }
}
```

Run with:
```bash
cargo build --release
cargo run --example test
```

### Integration Testing

Test with actual Karandash:

```bash
# Build plugin
cargo build --release

# Install to test location
mkdir -p ~/.karandash/plugins-test
cp target/release/libkarandash_rust_example.dylib ~/.karandash/plugins-test/

# Test with sample MR
karandash validate --config test.kts --mr sample-mr.json
```

## Troubleshooting

**"no such command: build" or "command not found: cargo"**
- Ensure Rust is installed via rustup: `curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh`
- Add cargo to PATH: `source $HOME/.cargo/env`

**Linker errors on cross-compilation**
- Install the target: `rustup target add x86_64-unknown-linux-gnu`
- On Linux, you may need build tools: `sudo apt-get install build-essential`
- On macOS, ensure Xcode Command Line Tools are installed: `xcode-select --install`

**"unused variable" or "dead code" warnings**
- These are expected for example code with optional fields
- Add `#[allow(dead_code)]` to suppress them

**Plugin won't load at runtime**
- Verify binary format: `file target/release/libkarandash_rust_example.dylib`
- Should show "Mach-O 64-bit dynamically linked shared library" on macOS
- Should show "ELF 64-bit LSB shared object" on Linux

**"Symbol not found" error**
- Ensure function is marked with `#[no_mangle] pub extern "C"`
- Check that the function signature matches the ABI specification

**JSON parsing fails**
- Add `#[serde(default)]` to optional fields in data structures
- Use `unwrap_or_else()` for error handling instead of `unwrap()`

**Memory safety issues**
- Use `valgrind` on Linux: `valgrind ./test_harness`
- Use Instruments or Address Sanitizer on macOS
- Check that all returned strings are freed by the host

## Performance Tips

- Rust compiles to native machine code with excellent optimization
- Use `cargo build --release` for maximum performance
- Avoid unnecessary allocations in validation loop
- Cache parsed configuration between validations
- Use iterators instead of collecting intermediate vectors
- Profile with `cargo flamegraph` if needed

## Advanced: Adding Dependencies

Update `Cargo.toml` to add dependencies:

```toml
[dependencies]
serde = { version = "1", features = ["derive"] }
serde_json = "1"
regex = "1"  # Example additional dependency
```

Then use in code:

```rust
use regex::Regex;

let re = Regex::new(r"^feature/").unwrap();
let matches = re.is_match(&mr.source_branch);
```

## Security Considerations

- Validate all input strings (check for NULL, validate length)
- Avoid unsafe code where possible (unsafe blocks are marked explicitly)
- Don't trust JSON content (could be malicious)
- Limit memory allocations (prevent DoS)
- Don't access filesystem or network without explicit permission
- Sanitize error messages (don't leak sensitive info)
- Use `CStr::to_str()` carefully (UTF-8 validation needed)

## Language-Specific Resources

- [Rust Book](https://doc.rust-lang.org/book/)
- [The Rustonomicon (FFI Guide)](https://doc.rust-lang.org/nomicon/ffi.html)
- [Serde Documentation](https://serde.rs/)
- [CString vs CStr](https://docs.rs/std/latest/std/ffi/#cstring-and-cstr)

## License

This example is public domain. Copy and modify freely for your own plugins.
