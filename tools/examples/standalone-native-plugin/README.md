# Standalone Native Plugin Example

A complete, self-contained example of a Karandash rule plugin written in C using the native plugin ABI.

## What This Plugin Does

Provides two validation rules:

1. **native-title-uppercase**: Checks that MR title contains at least one uppercase letter
2. **native-branch-prefix**: Validates that source branch starts with `feature/`, `bugfix/`, or `hotfix/`

**Example validations:**
- OK: Title: "Add Feature" (has uppercase)
- No: Title: "add feature" (no uppercase)
- OK: Branch: "feature/add-auth" (valid prefix)
- No: Branch: "add-auth" (invalid prefix)

## Prerequisites

- C compiler (GCC, Clang, or MSVC)
- CMake 3.10+ (optional, can use Makefile instead)
- Standard C library

## Building

### Option 1: Using CMake (Recommended)

```bash
mkdir build
cd build
cmake ..
make
```

This produces:
- **macOS**: `libnative-example.dylib`
- **Linux**: `libnative-example.so`
- **Windows**: `native-example.dll`

### Option 2: Using Makefile

```bash
make
```

The Makefile automatically detects your platform and builds the appropriate shared library.

### Option 3: Manual Compilation

**macOS:**
```bash
gcc -std=c11 -Wall -fPIC -dynamiclib -o libnative-example.dylib src/example-rule.c
```

**Linux:**
```bash
gcc -std=c11 -Wall -fPIC -shared -o libnative-example.so src/example-rule.c
```

**Windows (MinGW):**
```bash
gcc -std=c11 -Wall -shared -o native-example.dll src/example-rule.c
```

## Installation

### Option 1: Using Karandash CLI

```bash
karandash plugin install libnative-example.dylib
```

### Option 2: Using Makefile

```bash
make install
```

### Option 3: Manual Installation

```bash
mkdir -p ~/.karandash/plugins
cp libnative-example.dylib ~/.karandash/plugins/
# Or .so on Linux, .dll on Windows
```

### Verify Installation

```bash
karandash plugin list
```

You should see "Example Native Rule Plugin" in the list.

## Configuration

Add the rules to your `karandash.config.kts`:

```kotlin
rules {
    // Title uppercase check
    rule("native-title-uppercase") {
        severity = warning
    }

    // Branch prefix validation
    rule("native-branch-prefix") {
        severity = error
    }
}
```

These rules don't require additional configuration in this example, but you can extend them to support config options.

## Project Structure

```
standalone-native-plugin/
├── CMakeLists.txt         # CMake build configuration
├── Makefile              # Simple make-based build
│                         # header: docs/native-sdk/karandash_plugin.h (KARANDASH_SDK)
├── README.md            # This file
└── src/
    └── example-rule.c   # Plugin implementation
```

## How It Works

### 1. Native Plugin ABI

The plugin implements the Karandash native plugin ABI v1, which defines a set of C functions that must be exported:

```c
/* Identity */
int32_t karandash_abi_version(void);
const char* karandash_module_id(void);
const char* karandash_module_name(void);
const char* karandash_module_version(void);
const char* karandash_module_type(void);

/* Lifecycle */
int32_t karandash_initialize(const char* context_json);
void karandash_shutdown(void);

/* Rule module functions */
const char* karandash_contributed_rules(void);
const char* karandash_validate(const char* rule_id, const char* mr_json, const char* config_json);

/* Memory management */
void karandash_free_string(const char* ptr);
```

### 2. Memory Management

All strings returned by the plugin must be heap-allocated using `malloc()` or `strdup()`. The host calls `karandash_free_string()` to free them:

```c
const char* karandash_module_name(void) {
    return strdup("My Plugin");  // Host will call karandash_free_string on this
}

void karandash_free_string(const char* ptr) {
    if (ptr != NULL) {
        free((void*)ptr);
    }
}
```

### 3. JSON Communication

Complex data is passed as JSON strings. The plugin receives:
- **MR data** as JSON (title, description, branches, commits, files)
- **Configuration** as JSON (rule config from karandash.config.kts)

And returns:
- **Findings** as JSON array

Example MR JSON:
```json
{
  "title": "Fix parser bug",
  "description": "Fixes issue with...",
  "source_branch": "bugfix/parser",
  "target_branch": "main",
  "author": {"username": "user", "name": "User Name"},
  "labels": ["bug"],
  "commits": [...],
  "changes": [...]
}
```

Example findings JSON:
```json
[
  {
    "rule": "my-rule",
    "message": "Validation failed",
    "severity": "ERROR",
    "location": null
  }
]
```

### 4. Simple JSON Parsing

This example uses simple string operations for JSON parsing (for simplicity). Production plugins should use a proper JSON library like:
- **cJSON**: Lightweight, single-file library
- **jansson**: Full-featured JSON library
- **json-c**: Another popular option

## Customizing This Plugin

To create your own native rule plugin:

### 1. Copy the Directory

```bash
cp -r standalone-native-plugin my-native-plugin
cd my-native-plugin
```

### 2. Update Module Metadata

Edit `src/example-rule.c` and change:

```c
#define MODULE_ID "my-custom-plugin"
#define MODULE_NAME "My Custom Plugin"
#define MODULE_VERSION "1.0.0"
```

### 3. Implement Your Rules

Update `karandash_contributed_rules()`:

```c
const char* karandash_contributed_rules(void) {
    return strdup("[\"my-rule-1\", \"my-rule-2\"]");
}
```

Implement validation in `karandash_validate()`:

```c
const char* karandash_validate(
    const char* rule_id,
    const char* mr_json,
    const char* config_json
) {
    if (strcmp(rule_id, "my-rule-1") == 0) {
        // Your validation logic here
        // Parse mr_json, check conditions, build findings JSON

        if (violation_found) {
            return strdup(
                "[{\"rule\":\"my-rule-1\","
                "\"message\":\"Validation failed\","
                "\"severity\":\"ERROR\","
                "\"location\":null}]"
            );
        }
        return strdup("[]");  // No violations
    }

    return strdup("[]");  // Unknown rule
}
```

### 4. Build and Test

```bash
mkdir build && cd build
cmake .. && make
```

### 5. Install

```bash
make install
# or
cp libnative-example.* ~/.karandash/plugins/
```

## Advanced Topics

### Using a JSON Library

For production plugins, use a proper JSON parser. Example with cJSON:

```c
#include "cJSON.h"

const char* karandash_validate(
    const char* rule_id,
    const char* mr_json,
    const char* config_json
) {
    /* Parse MR JSON */
    cJSON* mr = cJSON_Parse(mr_json);
    if (mr == NULL) {
        return strdup("[]");
    }

    /* Extract fields */
    cJSON* title_item = cJSON_GetObjectItem(mr, "title");
    if (title_item == NULL || !cJSON_IsString(title_item)) {
        cJSON_Delete(mr);
        return strdup("[]");
    }

    const char* title = title_item->valuestring;

    /* Validate */
    if (strlen(title) < 10) {
        cJSON_Delete(mr);

        /* Build finding JSON */
        cJSON* findings = cJSON_CreateArray();
        cJSON* finding = cJSON_CreateObject();
        cJSON_AddStringToObject(finding, "rule", rule_id);
        cJSON_AddStringToObject(finding, "message", "Title too short");
        cJSON_AddStringToObject(finding, "severity", "ERROR");
        cJSON_AddNullToObject(finding, "location");
        cJSON_AddItemToArray(findings, finding);

        char* result = cJSON_PrintUnformatted(findings);
        char* heap_result = strdup(result);
        free(result);
        cJSON_Delete(findings);
        return heap_result;
    }

    cJSON_Delete(mr);
    return strdup("[]");
}
```

To use cJSON, add it to your CMakeLists.txt:

```cmake
# Download cJSON
include(FetchContent)
FetchContent_Declare(
    cjson
    GIT_REPOSITORY https://github.com/DaveGamble/cJSON.git
    GIT_TAG v1.7.16
)
FetchContent_MakeAvailable(cjson)

# Link to your plugin
target_link_libraries(native-example PRIVATE cjson)
```

### Configuration Handling

Parse config JSON to support configurable rules:

```c
const char* karandash_validate(
    const char* rule_id,
    const char* mr_json,
    const char* config_json
) {
    /* Parse config */
    cJSON* config = cJSON_Parse(config_json);

    /* Get config values with defaults */
    int min_length = 10;  // default
    cJSON* min_item = cJSON_GetObjectItem(config, "minLength");
    if (min_item && cJSON_IsNumber(min_item)) {
        min_length = min_item->valueint;
    }

    /* Use config in validation... */

    cJSON_Delete(config);
    return strdup("[]");
}
```

### File-Level Findings

Include location information in findings:

```c
/* Finding with file location */
cJSON* finding = cJSON_CreateObject();
cJSON_AddStringToObject(finding, "rule", "my-rule");
cJSON_AddStringToObject(finding, "message", "Issue found");
cJSON_AddStringToObject(finding, "severity", "ERROR");

cJSON* location = cJSON_CreateObject();
cJSON_AddStringToObject(location, "path", "src/main.c");
cJSON_AddNumberToObject(location, "line", 42);
cJSON_AddNumberToObject(location, "column", 10);
cJSON_AddItemToObject(finding, "location", location);
```

### Platform-Specific Code

Use preprocessor directives for platform differences:

```c
#ifdef _WIN32
    /* Windows-specific code */
    #include <windows.h>
#elif __APPLE__
    /* macOS-specific code */
    #include <mach/mach.h>
#elif __linux__
    /* Linux-specific code */
    #include <unistd.h>
#endif
```

### Error Handling

Always validate inputs and handle errors gracefully:

```c
int32_t karandash_initialize(const char* context_json) {
    if (context_json == NULL) {
        fprintf(stderr, "Error: NULL context\n");
        return 0;  // Failure
    }

    /* Initialize your plugin... */

    return 1;  // Success
}

const char* karandash_validate(/*...*/) {
    if (mr_json == NULL || config_json == NULL) {
        return strdup("[]");  // Return empty findings on error
    }

    /* Validation logic... */
}
```

## Testing

### Manual Testing

Create a test harness:

```c
/* test.c */
#include "karandash_plugin.h"
#include <stdio.h>

int main() {
    /* Check ABI version */
    printf("ABI version: %d\n", karandash_abi_version());

    /* Get metadata */
    const char* id = karandash_module_id();
    const char* name = karandash_module_name();
    printf("Plugin: %s (%s)\n", name, id);
    karandash_free_string(id);
    karandash_free_string(name);

    /* Initialize */
    int success = karandash_initialize("{\"hostVersion\":\"0.1.0\"}");
    printf("Initialized: %s\n", success ? "yes" : "no");

    /* Test validation */
    const char* test_mr = "{\"title\":\"test\",\"source_branch\":\"feature/test\"}";
    const char* rules = karandash_contributed_rules();
    printf("Rules: %s\n", rules);
    karandash_free_string(rules);

    const char* findings = karandash_validate("native-title-uppercase", test_mr, "{}");
    printf("Findings: %s\n", findings);
    karandash_free_string(findings);

    /* Shutdown */
    karandash_shutdown();

    return 0;
}
```

Compile and run:

```bash
gcc -o test test.c -L. -lnative-example
LD_LIBRARY_PATH=. ./test
```

### Integration Testing

Test with actual Karandash:

```bash
# Build plugin
make

# Install to test location
mkdir -p ~/.karandash/plugins-test
cp libnative-example.dylib ~/.karandash/plugins-test/

# Test with sample MR
karandash validate --config test.kts --mr sample-mr.json
```

## Troubleshooting

**Plugin not loading:**
- Ensure shared library has correct extension (.so/.dylib/.dll)
- Check that all required functions are exported
- Verify ABI version matches Karandash's expected version
- Check logs for dynamic library errors

**Build fails:**
- Ensure C compiler is installed and in PATH
- For CMake, check minimum version is met
- On Windows, use MinGW or MSVC
- Check that `karandash_plugin.h` is in include path (`make KARANDASH_SDK=/path/to/docs/native-sdk` outside the repo)

**Runtime crashes:**
- Verify all returned strings are heap-allocated
- Check for memory leaks (use valgrind on Linux)
- Ensure `karandash_free_string()` properly frees memory
- Validate all JSON strings are properly null-terminated

**Validation not working:**
- Test JSON parsing separately
- Add debug logging to stderr
- Verify rule IDs match exactly
- Check that findings JSON is valid

## Performance Tips

- Use efficient JSON parser (cJSON is very fast)
- Avoid unnecessary string allocations
- Cache parsed JSON if validating multiple rules
- Return early when validation passes
- Use string views/spans to avoid copies
- Profile with tools like `perf` or Instruments

## Security Considerations

- Validate all input strings (check for NULL, validate length)
- Avoid buffer overflows (use strncpy, snprintf)
- Don't trust JSON content (could be malicious)
- Limit memory allocations (prevent DoS)
- Don't access filesystem or network
- Sanitize error messages (don't leak sensitive info)

## Extracting a Built-in Module as a Standalone Plugin

If you are moving an existing built-in module out of the monorepo and publishing it as a standalone native binary, use the tooling scripts:

```bash
# Verify the module is standalone-ready before removal
./tools/scripts/verify-plugin-standalone.sh :plugins:plugins-core:rules:title-format

# Build and publish the native binary; outputs a registry entry JSON snippet
./tools/scripts/publish-plugin.sh :plugins:plugins-core:rules:title-format
```

See `tools/registry/MODULE_REMOVAL_PROTOCOL.md` for the full step-by-step procedure.

## License

This example is public domain. Copy and modify freely for your own plugins.
