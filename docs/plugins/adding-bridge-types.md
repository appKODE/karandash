# Adding New Bridge Types

This guide documents how to extend Karandash with new plugin bridge types (e.g., gRPC, HTTP, Python subprocess).

## What is a Bridge?

A **bridge** connects external plugin runtimes (JavaScript, WASM, native .so/.dylib, JAR subprocess) to Karandash's module interfaces. Each bridge:

- Implements all module interfaces (`RuleModule`, `ReporterModule`, `PlatformServiceModule`, etc.)
- Translates between Kotlin API calls and the foreign runtime's calling convention
- Manages runtime lifecycle (startup, shutdown, memory/resource cleanup)
- Exchanges data via JSON serialization

**Current bridge types:**
- **JS** - QuickJS runtime (sandboxed ES2021)
- **WASM** - Chicory interpreter on JVM, Chasm on native
- **Native** - JNA (JVM) or dlopen (native) for .so/.dylib files
- **JAR** - ServiceLoader (JVM) or subprocess bridge (native) for Java plugins

## Adding a New Bridge Type

### Step 1: Create the Bridge Class

Implement a `*ModuleBridge` class that extends all required module interfaces:

```kotlin
package io.karandash.loader.mybridge

import io.karandash.sdk.*
import io.karandash.models.Finding
import io.karandash.models.MergeRequest

internal class MyBridgeModuleBridge(
    private val runtimeHandle: MyRuntimeHandle,
    override val id: String,
    override val name: String,
    override val version: String,
    override val type: ModuleType,
    private val context: PluginContext
) : Module, RuleModule, ReporterModule, PlatformServiceModule {

    override suspend fun initialize(context: PluginContext): Boolean {
        // Runtime-specific initialization
        return runtimeHandle.initialize()
    }

    override suspend fun shutdown() {
        runtimeHandle.close()
    }

    override fun configSchema(): JsonElement = TODO()

    override fun defaultConfig(): JsonElement = TODO()

    // Implement all required methods
}
```

### Step 2: Use BridgeUtils for JSON Parsing

`BridgeUtils` provides shared JSON parsing logic - use it instead of duplicating:

```kotlin
import io.karandash.loader.BridgeUtils

// Parse module type from foreign runtime
val moduleType = BridgeUtils.parseModuleType(rawTypeString, "mybridge")

// Parse config properties from JSON
val configProp = BridgeUtils.parseConfigProperty(jsonObject)

// Parse CI environment
val ciEnv = BridgeUtils.parseCIEnvironment(jsonObj)
```

### Step 3: Implement the Loader

Create a `*PluginLoader` implementing the `PluginLoader` interface:

```kotlin
package io.karandash.loader.mybridge

import io.karandash.loader.PluginLoader
import io.karandash.loader.LoadResult
import io.karandash.loader.PluginDiscovery
import io.karandash.sdk.PluginContext
import io.karandash.sdk.Module

class MyBridgePluginLoader(
    private val registry: ModuleRegistry
) : PluginLoader {

    override suspend fun loadFromDirectories(
        directories: List<Path>,
        context: PluginContext
    ): LoadResult {
        val files = PluginDiscovery.discoverMyBridgePlugins(directories)
        val loaded = mutableListOf<String>()
        val failed = mutableListOf<LoadResult.LoadFailure>()

        for (file in files) {
            try {
                val runtime = MyRuntime.load(file)
                val bridge = MyBridgeModuleBridge.fromRuntime(runtime, context)
                val module = bridge.asRegisteredModule()

                registry.register(module)
                loaded.add(module.id)
            } catch (e: Exception) {
                failed.add(LoadResult.LoadFailure(
                    path = file.toString(),
                    reason = e.message ?: "Unknown error"
                ))
            }
        }

        return LoadResult(loaded, failed, emptyList())
    }

    override suspend fun unload(moduleId: String): Boolean = TODO()
    override suspend fun unloadAll() { TODO() }
}
```

### Step 4: Runtime Lifecycle Management

Manage the foreign runtime's lifecycle in the bridge:

```kotlin
internal class MyBridgeModuleBridge(...) : Module {
    private var runtimeHandle: MyRuntimeHandle? = null

    override suspend fun initialize(context: PluginContext): Boolean {
        runtimeHandle = MyRuntime.create()
        return runtimeHandle?.initialize() ?: false
    }

    override suspend fun shutdown() {
        runtimeHandle?.close()
        runtimeHandle = null
    }
}
```

### Step 5: Register in Plugin Discovery

Add file pattern discovery if needed:

```kotlin
// In PluginDiscovery.kt
fun discoverMyBridgePlugins(directories: List<Path>): List<Path> {
    return directories.flatMap { dir ->
        (dir.listDirectoryEntries() ?: emptyList())
            .filter { it.name.endsWith(".mybridge") }
    }
}
```

### Step 6: Register in BuiltInPluginLoader

Add your loader to `plugins/builtin/builtin-modules.txt` for auto-registration:

```
io.karandash.loader.mybridge.MyBridgePluginLoader
```

The build system generates `BuiltInPluginLoader.kt` from this manifest. Do not edit the generated file directly.

## Key Abstraction Points

### BridgeUtils - Shared Type Parsing

All bridges use `BridgeUtils` for:
- Module type normalization (`parseModuleType()`)
- Config property parsing (`parseConfigProperty()`)
- CI environment parsing (`parseCIEnvironment()`)
- URL parsing (`parseParsedMergeRequestUrl()`)

This prevents duplication and keeps the bridge types consistent.

### asRegisteredModule() Extension

Use `asRegisteredModule()` to wrap your bridge with marker interfaces:

```kotlin
val bridge = MyBridgeModuleBridge(...)
val module = bridge.asRegisteredModule()
// Returns appropriately wrapped Module (CliCommandModule, ConfigFormatModule, etc.)
```

## Bridge-Specific Implementation Requirements

### String/Memory Exchange

Define how to pass data between Kotlin and the foreign runtime:

**JSON-based** (simplest, used by WASM/native):
```kotlin
val json = Json { serializersModule = ... }
val jsonString = json.encodeToString(data)
val result = runtimeHandle.callFunction("validate", jsonString)
```

**Length-prefixed binary** (used by WASM):
```kotlin
val bytes = data.toByteArray(Charsets.UTF_8)
val lengthBytes = bytes.size.toLong().toByteArray()
memory.write(ptr, lengthBytes + bytes)
```

### Capability Detection

Discover which functions the plugin exports:

```kotlin
val caps = MyBridgeCapabilities(
    hasInitialize = runtimeHandle.hasFunction("initialize"),
    hasValidate = runtimeHandle.hasFunction("validate"),
    hasReport = runtimeHandle.hasFunction("report"),
)
```

Use capabilities to skip optional functions gracefully.

### Error Handling

Catch runtime-specific exceptions and wrap as `LoadResult.LoadFailure`:

```kotlin
try {
    val result = runtimeHandle.call(...)
} catch (e: MyRuntimeException) {
    throw IllegalArgumentException(
        "Plugin runtime error: ${e.message}",
        e
    )
}
```

## Testing Your Bridge

Add tests in `plugins/loader/src/*/test/`:

```kotlin
// commonTest/kotlin/io/karandash/loader/mybridge/MyBridgeModuleBridgeTest.kt
@Test
fun testValidateIntegration() {
    val bridge = createTestBridge()
    val mr = testMergeRequest()

    val findings = runBlocking {
        bridge.validate("rule-id", mr, Json.encodeToJsonElement(config))
    }

    assertEquals(1, findings.size)
    assertEquals("RULE_ID", findings[0].rule)
}
```

## No Magic - Everything is Explicit

- **No magic module ID inference** - require explicit `id` field in exported modules
- **No default module types** - require explicit `type` field (RULE, REPORTER, PROVIDER, etc.)
- **No implicit config schema** - all modules must return schema from `configSchema()`
- **No silent failures** - wrap foreign runtime exceptions clearly with context

## Example: Simple HTTP Bridge

```kotlin
class HttpBridgePluginLoader(registry: ModuleRegistry) : PluginLoader {
    override suspend fun loadFromDirectories(dirs: List<Path>, context: PluginContext) =
        dirs.flatMap { it.listDirectoryEntries() ?: emptyList() }
            .filter { it.name.endsWith(".http.json") }
            .map { path ->
                try {
                    val config = Json.decodeFromString<HttpPluginConfig>(path.readText())
                    val bridge = HttpModuleBridge(config.baseUrl, config.moduleId, context)
                    LoadResult(loaded = listOf(config.moduleId), failed = emptyList(), skipped = emptyList())
                } catch (e: Exception) {
                    LoadResult(loaded = emptyList(), failed = listOf(
                        LoadResult.LoadFailure(path.toString(), e.message ?: "HTTP bridge error")
                    ), skipped = emptyList())
                }
            }.fold(LoadResult(emptyList(), emptyList(), emptyList())) { acc, result ->
                LoadResult(
                    loaded = acc.loaded + result.loaded,
                    failed = acc.failed + result.failed,
                    skipped = acc.skipped + result.skipped
                )
            }

    override suspend fun unload(moduleId: String) = false
    override suspend fun unloadAll() {}
}
```

For complete examples, see `plugins/loader/src/*/kotlin/io/karandash/loader/{js,wasm,native}/`.
