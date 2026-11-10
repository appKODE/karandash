# Native Plugin Development Guide

Build high-performance plugins using C, C++, or Rust with direct system access.

## Overview

Native plugins are compiled binaries that interface with Karandash through a C ABI contract. Use them when you need:

- **Performance**: CPU-intensive validations (large file analysis, complex pattern matching)
- **Existing code**: Integrate pre-built C/C++/Rust libraries
- **System integration**: Access system resources (file I/O, processes, network)

### When to Use Native Plugins

Choose native plugins when:
- JavaScript or Kotlin plugins are too slow
- You have existing C/C++/Rust code to reuse
- You need unrestricted system access
- You're optimizing for high-throughput validation

### When to Use Other Plugin Types

- **JS plugins**: Rapid development, simple logic, easier deployment
- **Kotlin plugins**: Rich JVM ecosystem, type safety, gradual transition

## C ABI Contract

Native plugins must export specific C functions matching the Karandash ABI version.

### Function Signatures

All strings are UTF-8 encoded, null-terminated C strings. Complex data is passed as JSON strings.

#### ABI Version Check

```c
int32_t karandash_abi_version(void);
```

Return the ABI version your plugin supports (currently `1`). Karandash checks this before loading.

#### Module Identity (Required)

```c
const char* karandash_module_id(void);
const char* karandash_module_name(void);
const char* karandash_module_version(void);
const char* karandash_module_type(void);  // "RULE" | "REPORTER" | "PLATFORM_SERVICE"
```

Return module metadata. Return values must be allocated with `malloc()` or `strdup()` - Karandash will call `karandash_free_string()` to release them.

#### Lifecycle (Required)

```c
int32_t karandash_initialize(const char* context_json);
void karandash_shutdown(void);
```

`karandash_initialize()` is called once when the plugin loads. Return `1` for success, `0` for failure.

`context_json` contains initialization data like the Karandash version and plugin configuration.

`karandash_shutdown()` is called once when the plugin unloads. Use this for cleanup.

#### Rule Module Functions (if type == "RULE")

```c
const char* karandash_contributed_rules(void);
```

Return a JSON array of rule IDs this plugin provides:

```json
["rule-id-1", "rule-id-2"]
```

```c
const char* karandash_validate(
    const char* rule_id,
    const char* mr_json,
    const char* config_json
);
```

Validate a merge request. Return a JSON array of findings:

```json
[
  {
    "severity": "ERROR",
    "message": "Title too short",
    "rule": "title-length",
    "location": null
  },
  {
    "severity": "WARNING",
    "message": "Consider adding description",
    "rule": "description-check",
    "location": {
      "file": "src/main.c",
      "line": 42
    }
  }
]
```

**Parameters:**
- `rule_id`: The rule being validated (one of your contributed rules)
- `mr_json`: Merge request data (see Data Structures below)
- `config_json`: Rule configuration as JSON object

**Return value:** Allocated JSON string. Karandash will call `karandash_free_string()` to release it.

#### Reporter Module Functions (if type == "REPORTER")

```c
const char* karandash_format_id(void);
```

Return the format ID this reporter handles (e.g., "json", "webhook", "slack").

```c
const char* karandash_report(
    const char* findings_json,
    int32_t passed,
    const char* config_json
);
```

Generate a report from validation findings. Return optional output string (for file-based reporters, database plugins can return NULL).

**Parameters:**
- `findings_json`: Array of findings (same format as from `karandash_validate`)
- `passed`: `1` if all validations passed, `0` otherwise
- `config_json`: Reporter configuration as JSON object

#### Memory Management (Required)

```c
void karandash_free_string(const char* ptr);
```

Free strings allocated by your plugin. Implement this to match your allocation strategy:

```c
void karandash_free_string(const char* ptr) {
    if (ptr != NULL) {
        free((void*)ptr);
    }
}
```

### Optional exports

The full symbol list, with every optional export per module type, is the generated header
[`karandash_plugin.h`](../native-sdk/karandash_plugin.h). A CLI command module lists its commands with
`karandash_cli_commands()` and runs them with `karandash_run_cli_command(command, args_json)`:
`command` is the leaf's path (names joined by a space), `args_json` a JSON array of the remaining
arguments, and the result is `{"exitCode":n,"stdout":[...],"stderr":[...]}`. Without that export the
commands are listed but not runnable.

## Data Structures

### Merge Request

Passed as JSON to validation functions:

```json
{
  "id": "123",
  "iid": 456,
  "title": "Fix: handle null values in parser",
  "description": "Prevent crashes when input is null",
  "source_branch": "feature/null-handling",
  "target_branch": "main",
  "author": {
    "username": "alice",
    "name": "Alice Developer",
    "email": "alice@example.com"
  },
  "state": "opened",
  "draft": false,
  "labels": ["bug", "critical"],
  "commits": [
    {
      "sha": "abc123",
      "message": "Fix null handling in parser",
      "author": { "name": "Alice Developer", "email": "alice@example.com" },
      "timestamp": "2024-02-10T10:30:00Z"
    }
  ],
  "changes": [
    {
      "path": "src/parser.c",
      "changeType": "MODIFIED",
      "additions": 5,
      "deletions": 2
    }
  ]
}
```

### Finding

Each validation result is a finding:

```json
{
  "severity": "ERROR",
  "message": "Descriptive error message",
  "rule": "rule-id",
  "location": {
    "file": "path/to/file.c",
    "line": 42
  },
  "suggestion": "Short actionable hint for the MR author",
  "explanation": "Longer explanation of why this rule exists"
}
```

Severity values: `ERROR`, `WARNING`, `INFO`

Location is optional (use `null` for title/description validations).

`suggestion` and `explanation` are optional string fields. When present, they are displayed in MR comments and console output to help developers fix violations quickly.

### Configuration

Configuration is passed as a JSON object:

```json
{
  "minTitleLength": 10,
  "requireIssueKey": true,
  "customPattern": "^[A-Z]"
}
```

Use a JSON parsing library (cJSON, rapidjson, nlohmann/json) to parse and access config values.

## Building Native Plugins

### C Example: Title Length Validator

Here's a complete, minimal example:

```c
#include "karandash_plugin.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#define MODULE_ID "c-title-length"
#define MODULE_NAME "C Title Length Validator"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE "RULE"

int32_t karandash_abi_version(void) {
    return 1;
}

const char* karandash_module_id(void) {
    return strdup(MODULE_ID);
}

const char* karandash_module_name(void) {
    return strdup(MODULE_NAME);
}

const char* karandash_module_version(void) {
    return strdup(MODULE_VERSION);
}

const char* karandash_module_type(void) {
    return strdup(MODULE_TYPE);
}

int32_t karandash_initialize(const char* context_json) {
    // Parse context_json if needed for initialization
    // For this simple example, just return success
    return 1;
}

void karandash_shutdown(void) {
    // Cleanup if needed
}

const char* karandash_contributed_rules(void) {
    return strdup("[\"title-length-check\"]");
}

const char* karandash_validate(
    const char* rule_id,
    const char* mr_json,
    const char* config_json
) {
    // In production, parse JSON with cJSON or rapidjson
    // For this example, demonstrate the pattern

    // 1. Parse config_json to get minLength (default: 10)
    // 2. Parse mr_json to get title
    // 3. Check title length
    // 4. Return findings array

    // Simplified: return empty findings (no violations)
    return strdup("[]");
}

void karandash_free_string(const char* ptr) {
    if (ptr != NULL) {
        free((void*)ptr);
    }
}
```

### C with cJSON

For real-world plugins, use cJSON for JSON parsing:

```c
#include "karandash_plugin.h"
#include "cJSON.h"
#include <stdlib.h>
#include <string.h>

// ... module identity functions ...

const char* karandash_validate(
    const char* rule_id,
    const char* mr_json,
    const char* config_json
) {
    // Parse input JSON
    cJSON* mr = cJSON_Parse(mr_json);
    cJSON* config = cJSON_Parse(config_json);

    if (!mr || !config) {
        cJSON_Delete(mr);
        cJSON_Delete(config);
        return strdup("[]");
    }

    // Extract values
    cJSON* title_obj = cJSON_GetObjectItem(mr, "title");
    const char* title = title_obj ? title_obj->valuestring : "";

    cJSON* min_len_obj = cJSON_GetObjectItem(config, "minLength");
    int min_length = min_len_obj ? min_len_obj->valueint : 10;

    // Build result
    cJSON* findings = cJSON_CreateArray();

    if (strlen(title) < min_length) {
        cJSON* finding = cJSON_CreateObject();
        cJSON_AddStringToObject(finding, "severity", "ERROR");
        cJSON_AddStringToObject(finding, "message",
            "Title too short");
        cJSON_AddStringToObject(finding, "rule", rule_id);
        cJSON_AddNullToObject(finding, "location");
        cJSON_AddItemToArray(findings, finding);
    }

    // Convert to string
    char* result = cJSON_Print(findings);

    // Cleanup
    cJSON_Delete(mr);
    cJSON_Delete(config);
    cJSON_Delete(findings);

    return result;
}
```

### Rust Example

You can also write native plugins in Rust:

```rust
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use serde_json::{json, Value};

#[no_mangle]
pub extern "C" fn karandash_abi_version() -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn karandash_module_id() -> *const c_char {
    CString::new("rust-title-validator")
        .unwrap()
        .into_raw()
}

#[no_mangle]
pub extern "C" fn karandash_module_name() -> *const c_char {
    CString::new("Rust Title Validator")
        .unwrap()
        .into_raw()
}

#[no_mangle]
pub extern "C" fn karandash_module_version() -> *const c_char {
    CString::new("1.0.0")
        .unwrap()
        .into_raw()
}

#[no_mangle]
pub extern "C" fn karandash_module_type() -> *const c_char {
    CString::new("RULE")
        .unwrap()
        .into_raw()
}

#[no_mangle]
pub extern "C" fn karandash_initialize(_context: *const c_char) -> i32 {
    1
}

#[no_mangle]
pub extern "C" fn karandash_shutdown() {}

#[no_mangle]
pub extern "C" fn karandash_contributed_rules() -> *const c_char {
    CString::new("[\"title-validator\"]")
        .unwrap()
        .into_raw()
}

#[no_mangle]
pub extern "C" fn karandash_validate(
    rule_id: *const c_char,
    mr_json: *const c_char,
    config_json: *const c_char,
) -> *const c_char {
    let mr: Value = serde_json::from_str(
        unsafe { CStr::from_ptr(mr_json).to_str().unwrap_or("{}") }
    ).unwrap_or(json!({}));

    let title = mr["title"].as_str().unwrap_or("");

    let findings = if title.is_empty() {
        json!([{
            "severity": "ERROR",
            "message": "Title is required",
            "rule": "title-validator",
            "location": null
        }])
    } else {
        json!([])
    };

    CString::new(findings.to_string())
        .unwrap()
        .into_raw()
}

#[no_mangle]
pub extern "C" fn karandash_free_string(ptr: *const c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr as *mut c_char));
        }
    }
}
```

Build with Cargo:

```toml
[package]
name = "karandash-rust-plugin"
version = "1.0.0"

[lib]
crate-type = ["cdylib"]

[dependencies]
serde_json = "1.0"
```

### CMake Build

Use CMake for cross-platform C/C++ builds:

```cmake
cmake_minimum_required(VERSION 3.15)
project(karandash_title_validator)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Option 1: Use cJSON subdirectory
add_subdirectory(cJSON)

# Main plugin library
add_library(karandash_plugin SHARED
    src/title_validator.c
)

target_link_libraries(karandash_plugin PRIVATE cjson)
target_include_directories(karandash_plugin PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# Installation
install(TARGETS karandash_plugin DESTINATION lib)
```

Build:

```bash
mkdir build
cd build
cmake ..
make
# Library at: build/libkarandash_plugin.so (Linux)
#            build/libkarandash_plugin.dylib (macOS)
#            build/karandash_plugin.dll (Windows)
```

## Example Walkthrough

Let's examine the example rule plugin at `tools/examples/native-plugin-cpp/src/rule-plugin.cpp`.

### Module Identity

```c
#define MODULE_ID "native-title-length"
#define MODULE_NAME "Native Title Length Validator"
#define MODULE_VERSION "1.0.0"
#define MODULE_TYPE "RULE"
```

These constants define the plugin's identity, returned by the metadata functions.

### ABI Version

```c
int32_t karandash_abi_version(void) {
    return 1;
}
```

Declare ABI version 1 compatibility.

### Lifecycle

```c
int32_t karandash_initialize(const char* context_json) {
    return 1;  // Success
}

void karandash_shutdown(void) {
    // No cleanup needed
}
```

Simple lifecycle: initialize succeeds, no cleanup required.

### Contributed Rules

```c
const char* karandash_contributed_rules(void) {
    return strdup("[\"title-length-check\"]");
}
```

Advertise one rule: `title-length-check`.

### Validation Logic

```c
const char* karandash_validate(
    const char* rule_id,
    const char* mr_json,
    const char* config_json
) {
    // In real code, parse JSON with cJSON
    // For demo, return empty findings (no violations)
    return strdup("[]");
}
```

The example shows the pattern. Real implementations parse JSON and return violations.

### Memory Management

```c
void karandash_free_string(const char* ptr) {
    if (ptr != NULL) {
        free((void*)ptr);
    }
}
```

Release strings allocated by your plugin.

## Building the Example

The example includes a build setup. On macOS/Linux:

```bash
cd tools/examples/native-plugin-cpp
g++ -std=c++17 -shared -fPIC -o libcpp-rule-example.so src/rule-plugin.cpp
# Result: libcpp-rule-example.so
```

## Best Practices

### Memory Management

**Rule 1: Plugin allocates, host frees.**

Every function returning a string must allocate with `malloc()` or `strdup()`. Karandash calls `karandash_free_string()` to release memory.

```c
// CORRECT
const char* karandash_module_name(void) {
    return strdup("My Plugin");  // Allocated
}

// WRONG
const char* karandash_module_name(void) {
    static const char* name = "My Plugin";
    return name;  // Not allocated - will crash when freed
}
```

### JSON Parsing

Use a real JSON library:

- **C**: cJSON, jansson, or Parson
- **C++**: rapidjson or nlohmann/json
- **Rust**: serde_json

Don't parse JSON with string manipulation.

### Error Handling

Return empty findings array if validation fails:

```c
const char* karandash_validate(...) {
    cJSON* mr = cJSON_Parse(mr_json);
    if (!mr) {
        return strdup("[]");  // Graceful degradation
    }
    // ... process ...
}
```

### String Encoding

All strings are UTF-8. Use UTF-8-aware functions if handling non-ASCII characters:

```c
#include <locale.h>
setlocale(LC_ALL, "en_US.UTF-8");
```

### Thread Safety

Plugins may be called from multiple threads. Use thread-local storage or locks:

```c
#include <pthread.h>

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

const char* karandash_validate(...) {
    pthread_mutex_lock(&lock);
    // Critical section
    pthread_mutex_unlock(&lock);
}
```

## Security Considerations

### Native plugins have full system access

- No sandboxing
- Can read/write files
- Can execute processes
- Can access network
- Can access system resources

**Only load native plugins from trusted sources.**

### Signature Verification

Karandash verifies optional RSA-SHA256 signatures (raw signature bytes, the format `plugin sign` writes for
JARs). Provide a signature and the public key when distributing plugins:

```bash
openssl dgst -sha256 -sign private.pem -out plugin.so.sig plugin.so
openssl rsa -in private.pem -pubout -outform DER -out public.der
```

Verify plugin sources (without `--key`, the trusted keys in `~/.karandash/keys/` are tried):

```bash
karandash plugin verify plugin.so plugin.so.sig --key public.der
```

### Input Validation

Always validate input JSON:

```c
cJSON* mr = cJSON_Parse(mr_json);
if (!mr) {
    return strdup("[]");  // Reject malformed input
}
```

### Avoid Command Injection

If executing external commands, use safe APIs:

```c
// WRONG
char cmd[1024];
snprintf(cmd, sizeof(cmd), "grep '%s' file.txt", pattern);
system(cmd);  // Command injection risk

// BETTER
const char* argv[] = {"grep", pattern, "file.txt", NULL};
execv("/bin/grep", (char* const*)argv);
```

## Cross-Platform Considerations

### Shared Library Extensions

- Linux: `.so` (libplugin.so)
- macOS: `.dylib` (libplugin.dylib)
- Windows: `.dll` (plugin.dll)

Use CMake or autotools for portable builds.

### ABI Stability

Keep your C ABI stable:
- Don't change function signatures
- Don't reorder parameters
- Use versioning if you need new functions

Example: `karandash_validate_v2()` for breaking changes.

### Testing

Test on all target platforms:

```bash
# Build
mkdir build && cd build
cmake ..
cmake --build . --config Release

# Test with Karandash
karandash plugin load ./libplugin.so
karandash validate --rules-file .karandash/config.yaml
```

## Troubleshooting

### Plugin fails to load

Check ABI version:
```bash
nm -D libplugin.so | grep karandash_abi
```

Verify all required functions are exported:
```bash
nm -D libplugin.so | grep karandash_module
```

### Segmentation fault

- Memory corruption: Check for buffer overflows
- Null pointer: Verify JSON parsing succeeded
- Stack overflow: Check for infinite recursion

Use valgrind or AddressSanitizer to debug:

```bash
gcc -fsanitize=address -g -shared -fPIC src/plugin.c -o libplugin.so
./libplugin.so
```

### JSON parsing errors

Log parsed values:

```c
cJSON* title = cJSON_GetObjectItem(mr, "title");
fprintf(stderr, "title: %s\n", title ? title->valuestring : "NULL");
```

## Next Steps

- Review the example native plugin in `tools/examples/native-plugin-cpp/`
- Explore [cJSON documentation](https://github.com/DaveGamble/cJSON)
- Check [rapidjson docs](https://rapidjson.org/) for C++
- See [Rust FFI guide](https://doc.rust-lang.org/nomicon/ffi.html)
