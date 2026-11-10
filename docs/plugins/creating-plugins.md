# Creating Plugins

Build custom validation plugins for Karandash. Plugins extend Karandash with custom rules, reporters, platform integrations, and more.

## Plugin Types

Karandash supports four plugin formats. Choose the one that fits your language and runtime requirements.

| Plugin type | JVM (fat JAR) | Native binary |
|-------------|---------------|---------------|
| **Kotlin/JVM JAR** | via `ServiceLoader` | via subprocess bridge (requires Java 17+) |
| **JavaScript** (`.mjs`) | via QuickJS | via QuickJS |
| **WASM** (`.wasm`) | via Chicory | via Chasm |
| **Native shared library** (`.dylib`/`.so`/`.dll`) | via JNA | via `dlopen` |

## SDK Interfaces

All plugin types implement interfaces from `io.karandash.sdk`. Add the SDK as a dependency:

```kotlin
// build.gradle.kts
dependencies {
    implementation("ru.kode.karandash:sdk:VERSION")
}
```

### Core interfaces

| Interface | Purpose | Config block |
|-----------|---------|-------------|
| `RuleModule` | Validates merge requests, returns findings | `rules:` |
| `ReporterModule` | Formats and outputs validation results | `reporters:` |
| `PlatformServiceModule` | Fetches MR data, posts comments, mutates state | `platforms:` |

All interfaces extend `Module`, which extends `Plugin`. Every plugin must supply `id`, `name`, and `version` from `Plugin`, and implement `initialize(context: PluginContext): Boolean` and optionally `shutdown()` from `Module`.

### Plugin

```kotlin
interface Plugin {
    val id: String      // Reverse-domain: "com.example.my-rule"
    val name: String    // Human-readable: "My Custom Rule"
    val version: String // Semver: "1.0.0"
}
```

### Module

```kotlin
interface Module : Plugin {
    val type: ModuleType
    val description: String get() = name
    val dslName: String get() = id        // camelCase config key
    val configKey: String get() = dslName // kebab-case YAML key

    suspend fun initialize(context: PluginContext): Boolean
    suspend fun shutdown() {}

    suspend fun defaultConfig(): JsonElement = JsonObject(emptyMap())
    suspend fun configSchema(): List<ConfigProperty> = emptyList()
}
```

### PluginContext

`PluginContext` is passed to `initialize()` and provides runtime services:

```kotlin
interface PluginContext {
    val pluginConfig: JsonElement          // Config as a JSON element from the config file
    val hostVersion: String               // Karandash version string
    val workingDirectory: String          // Repo root path
    val allowLocalhost: Boolean           // True when --allow-localhost is set

    fun log(message: String)              // Info-level log
    fun warn(message: String)             // Warning-level log
    fun env(name: String): String?        // Read an allowlisted env var

    @ExperimentalKarandashApi
    suspend fun resolveContent(source: ContentSource): String  // Inline / { file: } / { url:, auth: }
}
```

`resolveContent` works from `initialize()` onward (it throws `ContentResolveException` during plugin
bootstrap). Local files resolve against the config file's directory and must stay inside it (`../` escapes,
absolute paths elsewhere and symlinks pointing out are refused). URLs are fetched with the strict SSRF policy:
cloud-metadata/link-local hosts are always refused, private/dotless hosts need `allow-private-network: true` or `--allow-localhost`, loopback needs `--allow-localhost`,
redirects are not followed, bodies over 1 MiB are refused, and `auth: <name>` takes its credentials from the
top-level `auth:` block (HTTPS only). Kotlin plugins running in the JAR subprocess or as C-ABI native
libraries only get `Inline` content; any other source fails with an explicit "not supported through the
bridge" error.

Always read environment variables through `context.env()`, never directly. It enforces a host-level allowlist - variables not on that allowlist are inaccessible.

### RuleModule

```kotlin
interface RuleModule : Module {
    override val type: ModuleType get() = ModuleType.RULE

    suspend fun contributedRules(): Set<String>       // Rule IDs this module handles
    suspend fun ruleExplanation(ruleId: String): String? = null

    suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
    ): List<Finding>
}
```

Return an empty list when there are no findings. Never throw from `validate()`; log warnings via `context.warn()` instead.

### ReporterModule

```kotlin
interface ReporterModule : Module {
    override val type: ModuleType get() = ModuleType.REPORTER
    val formatId: String
    val requiresPlatformContext: Boolean get() = false

    suspend fun runtimeReporterConfig(
        reportersConfig: JsonElement,
        cliParams: JsonElement
    ): JsonElement = JsonObject(emptyMap())

    suspend fun report(
        findings: List<Finding>,
        passed: Boolean,
        config: JsonElement
    ): String?
}
```

---

## Kotlin/JVM Plugin

JVM plugins are JAR files discovered via Java `ServiceLoader`.

### 1. Implement the interface

```kotlin
package com.example

import io.karandash.sdk.PluginContext
import io.karandash.sdk.config.ConfigProperty
import io.karandash.sdk.config.ConfigPropertyType
import io.karandash.sdk.module.RuleModule
import io.karandash.models.finding.Finding
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Severity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class TicketReferenceRuleModule : RuleModule {
    override val id = "com.example.rule.ticket-reference"
    override val name = "Ticket Reference Rule"
    override val version = "1.0.0"

    private lateinit var context: PluginContext

    override suspend fun contributedRules() = setOf("ticketReference")

    override suspend fun initialize(context: PluginContext): Boolean {
        this.context = context
        return true
    }

    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
    ): List<Finding> {
        val config = configElement as? JsonObject ?: JsonObject(emptyMap())
        val pattern = (config["pattern"] as? JsonPrimitive)?.content ?: "[A-Z]+-\\d+"
        val regex = Regex(pattern)

        if (!regex.containsMatchIn(mr.title)) {
            return listOf(
                Finding(
                    rule = ruleId,
                    message = "Title must reference a ticket matching $pattern",
                    severity = Severity.ERROR
                )
            )
        }
        return emptyList()
    }

    override suspend fun defaultConfig(): JsonElement = JsonObject(
        mapOf("pattern" to JsonPrimitive("[A-Z]+-\\d+"))
    )

    override suspend fun configSchema() = listOf(
        ConfigProperty(
            name = "pattern",
            type = ConfigPropertyType.STRING,
            description = "Regex pattern for ticket reference",
            required = false
        )
    )
}
```

### 2. Register via ServiceLoader

Create the file `src/main/resources/META-INF/services/io.karandash.api.Module` containing the fully-qualified class name:

```
com.example.TicketReferenceRuleModule
```

### 3. Build a fat JAR

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    compileOnly("ru.kode.karandash:sdk:VERSION")
}

tasks.shadowJar {
    archiveClassifier.set("")
}
```

```bash
./gradlew shadowJar
# Output: build/libs/my-plugin-1.0.0.jar
```

### 4. Declare in config

```yaml
plugins:
  - path: "./plugins/my-plugin-1.0.0.jar"

rules:
  ticketReference:
    pattern: "PROJ-\\d+"
```

On the native binary the JAR runs in a JVM subprocess that serves every module type, and a class
implementing several interfaces registers once per type. Properties are read once at discovery and each
call runs in a fresh JVM, so keep state out of the module; the remaining gaps are listed in
[JVM vs Native](../advanced/JVM_VS_NATIVE_FEATURES.md). The JVM CLI loads the class in
process and registers it under every type it implements as well. Example:
`tools/examples/standalone-jvm-plugin/src/main/kotlin/com/example/karandash/ExampleCompositePlugin.kt`.

---

## JavaScript Plugin

JavaScript plugins run in a sandboxed QuickJS runtime. They work on both the JVM fat JAR and the native binary.

### Rule plugin example

```javascript
// ticket-reference.mjs

export const id = "com.example.rule.ticket-reference-js";
export const name = "Ticket Reference Rule (JS)";
export const version = "1.0.0";
export const type = "rule";
export const contributedRules = ["ticketReference"];

export function initialize(context) {
    return true;
}

export function validate(ruleId, mr, config) {
    const pattern = config.pattern ?? "[A-Z]+-\\d+";
    const regex = new RegExp(pattern);

    if (!regex.test(mr.title)) {
        return [{
            ruleId: ruleId,
            message: `Title must reference a ticket matching ${pattern}`,
            severity: "ERROR"
        }];
    }
    return [];
}

export function defaultConfig() {
    return { pattern: "[A-Z]+-\\d+" };
}
```

### Declare in config

```yaml
plugins:
  - path: "./plugins/ticket-reference.mjs"

rules:
  ticketReference:
    pattern: "PROJ-\\d+"
```

### JavaScript API contract

The `mr` object passed to `validate()` mirrors `MergeRequest`:

| Field | Type | Description |
|-------|------|-------------|
| `mr.title` | `string` | MR/PR title |
| `mr.description` | `string \| null` | MR/PR description |
| `mr.author` | `string` | Author username |
| `mr.sourceBranch` | `string` | Source branch name |
| `mr.targetBranch` | `string` | Target branch name |
| `mr.commits` | `Commit[]` | List of commits |
| `mr.changedFiles` | `ChangedFile[]` | Changed files |
| `mr.labels` | `string[]` | Applied labels |
| `mr.isDraft` | `boolean` | Draft status |

A finding object must have `ruleId` (string), `message` (string), and `severity` (`"ERROR"`, `"WARNING"`, or `"INFO"`). The `filePath` and `line` fields are optional for inline annotations.

---

## WASM Plugin

WASM plugins are loaded via Chicory on the JVM fat JAR and Chasm (pure Kotlin, compiled in) on the native binary (Chasm 1.7.0 on the macOS x64 binary). Each `.wasm` file runs in its own isolated runtime instance.

### ABI contract

Your WASM module must export these functions:

| Export | Signature | Description |
|--------|-----------|-------------|
| `karandash_abi_version` | `() -> i32` | Must return `1` |
| `karandash_module_id` | `(ptr: i32, len: i32) -> i32` | Write plugin ID to memory, return bytes written |
| `karandash_validate` | `(rule_ptr, rule_len, mr_ptr, mr_len, cfg_ptr, cfg_len, out_ptr, out_len: i32) -> i32` | Run validation, write JSON findings to out buffer |

Memory exchange uses length-prefixed strings via linear memory. Input and output are JSON-encoded.

### Rust example

```rust
// src/lib.rs

#[no_mangle]
pub extern "C" fn karandash_abi_version() -> i32 { 1 }

#[no_mangle]
pub extern "C" fn karandash_module_id(ptr: i32, len: i32) -> i32 {
    let id = b"com.example.rule.ticket-reference-wasm";
    let out = unsafe { std::slice::from_raw_parts_mut(ptr as *mut u8, len as usize) };
    let copy_len = id.len().min(out.len());
    out[..copy_len].copy_from_slice(&id[..copy_len]);
    copy_len as i32
}

#[no_mangle]
pub extern "C" fn karandash_validate(
    _rule_ptr: i32, _rule_len: i32,
    mr_ptr: i32, mr_len: i32,
    _cfg_ptr: i32, _cfg_len: i32,
    out_ptr: i32, out_len: i32,
) -> i32 {
    // Decode MR from linear memory (JSON), run validation,
    // write JSON findings array to out_ptr/out_len.
    // Return bytes written, or negative value for error.
    let result = b"[]";
    let out = unsafe { std::slice::from_raw_parts_mut(out_ptr as *mut u8, out_len as usize) };
    let copy_len = result.len().min(out.len());
    out[..copy_len].copy_from_slice(&result[..copy_len]);
    copy_len as i32
}
```

```bash
cargo build --target wasm32-unknown-unknown --release
# Output: target/wasm32-unknown-unknown/release/my_plugin.wasm
```

### Declare in config

```yaml
plugins:
  - path: "./plugins/my_plugin.wasm"

rules:
  ticketReference:
    pattern: "PROJ-\\d+"
```

---

## Native Shared Library Plugin

Native plugins are shared libraries (`.dylib` on macOS, `.so` on Linux, `.dll` on Windows). They are loaded via `dlopen` on native binary and via JNA on the JVM fat JAR.

### C ABI contract

Your library must export these symbols:

```c
// Returns ABI version; must return 1
int32_t karandash_abi_version(void);

// Returns the module ID as a null-terminated UTF-8 string (static lifetime)
const char* karandash_module_id(void);

// Runs validation; mr_json and config_json are null-terminated JSON strings.
// Returns a null-terminated JSON array of findings.
// Caller must free the returned string with karandash_free_string().
const char* karandash_validate(
    const char* rule_id,
    const char* mr_json,
    const char* config_json
);

// Frees memory allocated by karandash_validate
void karandash_free_string(const char* ptr);
```

### C example

```c
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

int32_t karandash_abi_version(void) { return 1; }

const char* karandash_module_id(void) {
    return "com.example.rule.ticket-reference-native";
}

const char* karandash_validate(
    const char* rule_id,
    const char* mr_json,
    const char* config_json
) {
    // Parse mr_json, run validation logic, return JSON findings array.
    // Return strdup("[]") for no findings.
    return strdup("[]");
}

void karandash_free_string(const char* ptr) {
    free((void*)ptr);
}
```

### Build

```bash
# macOS
clang -dynamiclib -o my-plugin.dylib my-plugin.c

# Linux
gcc -shared -fPIC -o my-plugin.so my-plugin.c

# Windows (MinGW)
gcc -shared -o my-plugin.dll my-plugin.c
```

### Declare in config

```yaml
plugins:
  - path: "./plugins/my-plugin.dylib"  # or .so / .dll

rules:
  ticketReference:
    pattern: "PROJ-\\d+"
```

---

## Configuration Reference

### External plugin declaration

```yaml
plugins:
  # Local file path
  - path: "./plugins/my-plugin-1.0.0.jar"

  # Registry reference
  - id: "com.example.ticket-validator"
    version: "1.2.0"
```

### Using your rule in config

Once loaded, your rule module's contributed rule IDs appear under `rules:` using the rule ID returned by `contributedRules()`:

```yaml
rules:
  ticketReference:          # Matches contributedRules() = setOf("ticketReference")
    pattern: "PROJ-\\d+"
```

---

## Best Practices

- **Return empty list on error**: when validation cannot proceed, log via `context.warn()` and return `emptyList()`. Never throw from `validate()`.
- **Read env vars via `context.env()`**: it enforces a host-level allowlist. Variables not on that allowlist are inaccessible.
- **Provide a config schema**: implement `configSchema()` so the LSP server can offer completions and validate your plugin's config block.
- **Use `defaultConfig()`**: return sensible defaults so your plugin works without explicit configuration.
- **Single responsibility**: each plugin should validate one concern. Compose multiple plugins instead of building one that does everything.
- **Test all paths**: cover the happy path, missing config, invalid input, and empty MR data.

## Plugin Security

JAR plugins are signature-verified before loading, on the JVM and native binaries alike, once any trusted key is present in `~/.karandash/keys`: a JAR without a matching `<name>.jar.sig` is rejected. JS, WASM and native plugins are not signature-checked. The `env()` API enforces an allowlist - plugins cannot read arbitrary environment variables. JavaScript plugins run in a sandboxed QuickJS context with no access to the filesystem or network beyond the APIs explicitly provided by Karandash.

## Example Projects

Complete, buildable example plugin projects for each language are in `tools/examples/`:

```
tools/examples/
├── standalone-jvm-plugin/       - Kotlin/JVM JAR plugin with Gradle build
├── standalone-js-plugin/        - JavaScript .mjs plugin
├── standalone-wasm-rust-plugin/ - WASM plugin written in Rust
├── standalone-cpp-plugin/       - Native shared library written in C++
└── standalone-go-plugin/        - Native shared library written in Go
```
