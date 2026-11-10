# Kotlin/Native Plugin Example

A complete, self-contained example of a Karandash rule plugin written in Kotlin/Native using the native plugin ABI.

## What This Plugin Does

Provides one validation rule:

1. **kotlin-title-length**: Checks that MR title is at least 10 characters long

**Example validations:**
- OK: Title: "Add new feature" (15 chars, valid)
- No: Title: "Add auth" (8 chars, too short)

## Prerequisites

- JDK 17 or later (for Kotlin compiler)
- Kotlin 2.0+ (included via Gradle wrapper)
- macOS ARM64, Linux x64, or similar platform with C interop support
- No additional tools needed (Gradle wrapper included)

## Building

### Option 1: Using Gradle Wrapper (Recommended)

**macOS ARM64:**
```bash
./gradlew linkReleaseSharedMacosArm64
```

**Linux x64:**
```bash
./gradlew linkReleaseSharedLinuxX64
```

This produces:
- **macOS**: `build/bin/macosArm64/releaseShared/libkotlin-example.dylib`
- **Linux**: `build/bin/linuxX64/releaseShared/libkotlin-example.so`

### Troubleshooting Build Issues

- **"No such command"**: Ensure you're in the plugin directory and have execute permissions: `chmod +x gradlew`
- **Out of memory**: Increase heap: `export GRADLE_OPTS="-Xmx2g"` before running gradle
- **Compilation is slow**: This is normal for Kotlin/Native. First build can take 30s-2 minutes.

## Installation

### Option 1: Using Karandash CLI

```bash
karandash plugin install build/bin/macosArm64/releaseShared/libkotlin-example.dylib
```

### Option 2: Manual Installation

```bash
mkdir -p ~/.karandash/plugins
cp build/bin/macosArm64/releaseShared/libkotlin-example.dylib ~/.karandash/plugins/
# Or .so on Linux
```

### Verify Installation

```bash
karandash plugin list
```

You should see "Kotlin/Native Example Plugin" in the list.

## Configuration

Add the rule to your `karandash.config.kts`:

```kotlin
rules {
    rule("kotlin-title-length") {
        severity = warning
    }
}
```

This rule doesn't require additional configuration in this example.

## Project Structure

```
native-plugin-kotlin/
├── build.gradle.kts           # Gradle Kotlin DSL build config
├── gradle/wrapper/            # Gradle wrapper files
├── gradlew                     # Gradle wrapper script
├── README.md                   # This file
└── src/nativeMain/kotlin/
    ├── Plugin.kt              # Main plugin implementation
    └── Models.kt              # Data classes for JSON serialization
```

## How It Works

### 1. Native Plugin ABI

The plugin implements the Karandash native plugin ABI v1, which defines a set of C functions exported from the shared library. Kotlin/Native uses the `@CName` annotation to map Kotlin functions to C function names:

```kotlin
@CName("karandash_module_name")
fun moduleName(): CPointer<ByteVar> = "Kotlin/Native Example Plugin".toCString()
```

### 2. Memory Management

All strings returned by the plugin must be heap-allocated using C functions. Kotlin/Native provides `platform.posix.strdup()` and `platform.posix.free()`:

```kotlin
private fun String.toCString(): CPointer<ByteVar> {
    return strdup(this)!!.reinterpret()
}

@CName("karandash_free_string")
fun freeString(ptr: CPointer<ByteVar>?) {
    if (ptr != null) {
        free(ptr)
    }
}
```

The host calls `karandash_free_string()` to free all returned strings.

### 3. JSON Communication

Complex data is passed as JSON strings. The plugin:
- Receives **MR data** as JSON (title, description, branches, commits, files)
- Receives **configuration** as JSON (rule config from karandash.config.kts)
- Returns **findings** as JSON array

The example uses `kotlinx.serialization` for JSON parsing:

```kotlin
private val json = Json { ignoreUnknownKeys = true }

val mr = json.decodeFromString<MergeRequest>(mrStr)
```

### 4. C Interop and Foreign Function Interface

Kotlin/Native uses `cinterop` to call C functions and work with C types:

```kotlin
import kotlinx.cinterop.*
import platform.posix.free
import platform.posix.strdup

// CPointer<ByteVar> is the equivalent of C's char*
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
      "path": "src/parser.kt",
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
    "rule": "kotlin-title-length",
    "message": "Title is too short (8 chars, minimum 10)",
    "severity": "WARNING",
    "location": null,
    "explanation": null
  }
]
```

## Customizing This Plugin

To create your own Kotlin/Native rule plugin:

### 1. Copy the Directory

```bash
cp -r native-plugin-kotlin my-kotlin-plugin
cd my-kotlin-plugin
```

### 2. Update Module Metadata

Edit `src/nativeMain/kotlin/Plugin.kt`:

```kotlin
const val MODULE_ID = "my-custom-plugin"
const val MODULE_NAME = "My Custom Plugin"
const val MODULE_VERSION = "1.0.0"
```

### 3. Update Data Models

Edit `src/nativeMain/kotlin/Models.kt` if you need to parse additional MR fields.

### 4. Implement Your Rules

Update `contributedRules()`:

```kotlin
@CName("karandash_contributed_rules")
fun contributedRules(): CPointer<ByteVar> {
    return json.encodeToString(
        ListSerializer(String.serializer()),
        listOf("my-rule-1", "my-rule-2")
    ).toCString()
}
```

Implement validation in the `validate()` function:

```kotlin
@CName("karandash_validate")
fun validate(
    ruleId: CPointer<ByteVar>?,
    mrJson: CPointer<ByteVar>?,
    configJson: CPointer<ByteVar>?
): CPointer<ByteVar> {
    val rule = ruleId?.toKStringFromUtf8() ?: return "[]".toCString()
    val mrStr = mrJson?.toKStringFromUtf8() ?: return "[]".toCString()

    if (rule == "my-rule-1") {
        val mr = json.decodeFromString<MergeRequest>(mrStr)
        val findings = mutableListOf<Finding>()

        // Your validation logic here
        if (violationFound) {
            findings.add(Finding(
                severity = "ERROR",
                message = "Validation failed",
                rule = "my-rule-1",
                location = null,
                explanation = null
            ))
        }

        return json.encodeToString(
            ListSerializer(Finding.serializer()),
            findings
        ).toCString()
    }

    return "[]".toCString()
}
```

### 5. Build and Test

```bash
./gradlew linkReleaseSharedMacosArm64
```

### 6. Install

```bash
mkdir -p ~/.karandash/plugins
cp build/bin/macosArm64/releaseShared/libkotlin-example.dylib ~/.karandash/plugins/
```

## Kotlin/Native Specific Notes

### OptIn Experimental APIs

Kotlin/Native's foreign function interface uses experimental APIs. The plugin uses `@OptIn` to suppress warnings:

```kotlin
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class
)
```

### Binary Size

Kotlin/Native binaries are relatively large (~4-5MB). This is expected because:
- Kotlin standard library is statically linked
- Runtime is included in the binary

### Serialization with Kotlinx

The example uses `kotlinx.serialization` for type-safe JSON handling. It requires explicit serialization configuration:

```kotlin
@Serializable
data class MergeRequest(
    val title: String,
    @SerialName("source_branch")
    val sourceBranch: String,
    // ... other fields
)
```

The `@SerialName` annotation maps Kotlin camelCase property names to snake_case JSON fields.

### CPointer and C Types

Common Kotlin/Native C type mappings:
- `CPointer<ByteVar>` = `char*` (C string)
- `CInt` = `int`
- `CLong` = `long`
- `CPointer<IntVar>` = `int*`

Convert C strings to Kotlin strings:
```kotlin
val kotlinStr = cPointer?.toKStringFromUtf8()  // safe, returns Option-like
```

Convert Kotlin strings to C strings:
```kotlin
val cStr = kotlinStr.toCString()  // returns CPointer<ByteVar>
```

## Testing

### Manual Testing

Create a test harness using Kotlin:

```kotlin
fun main() {
    // Initialize
    val initialized = karandash_initialize("{\"hostVersion\":\"0.1.0\"}".toCString())
    println("Initialized: ${initialized == 1}")

    // Get module info
    val name = karandash_module_name()?.toKStringFromUtf8()
    println("Plugin: $name")
    karandash_free_string(name?.toCString())

    // Test validation
    val testMr = "{\"title\":\"test\",\"source_branch\":\"feature/test\"}"
    val findings = karandash_validate(
        "kotlin-title-length".toCString(),
        testMr.toCString(),
        "{}".toCString()
    )
    println("Findings: ${findings?.toKStringFromUtf8()}")
    karandash_free_string(findings)

    // Shutdown
    karandash_shutdown()
}
```

### Integration Testing

Test with actual Karandash:

```bash
# Build plugin
./gradlew linkReleaseSharedMacosArm64

# Install to test location
mkdir -p ~/.karandash/plugins-test
cp build/bin/macosArm64/releaseShared/libkotlin-example.dylib ~/.karandash/plugins-test/

# Test with sample MR
karandash validate --config test.kts --mr sample-mr.json
```

## Troubleshooting

**Compilation is slow**
- This is expected for Kotlin/Native. First compilation can take 30s-2 minutes.
- Subsequent builds are faster due to caching.
- Increase heap if you get OOM: `export GRADLE_OPTS="-Xmx2g"`

**"Experimental API" warnings**
- Add `@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)` at the top of your file
- The plugin already includes these annotations

**Plugin won't load at runtime**
- Verify binary architecture matches your system: `file build/bin/macosArm64/releaseShared/libkotlin-example.dylib`
- Should show "Mach-O 64-bit dynamically linked shared library arm64"
- On Linux, should show "ELF 64-bit LSB shared object, x86-64"

**"Symbol not found" or "undefined reference"**
- Ensure all `@CName` annotations are present on exported functions
- Check that function signatures match the ABI specification

**JSON parsing fails**
- Add `ignoreUnknownKeys = true` to Json constructor (already done in example)
- Use `@SerialName` to map snake_case JSON fields to camelCase Kotlin properties

**Memory issues**
- Verify `karandash_free_string()` is called by the host for every returned string
- Use valgrind or similar to detect leaks: `valgrind ./test_harness`

## Performance Tips

- Kotlin/Native compiles to native machine code, so performance is comparable to C
- Serialize JSON with `Json` builder configured appropriately
- Avoid allocating large objects repeatedly in validation loop
- Consider caching parsed configuration between validations
- Use `ignoreUnknownKeys = true` to skip unnecessary JSON fields

## Advanced: Using External C Libraries

To use external C libraries (like cJSON), update `build.gradle.kts`:

```kotlin
kotlin {
    macosArm64 {
        binaries {
            sharedLib {
                baseName = "kotlin-example"
                // Link against system libraries
                linkerOpts("-lc")  // libc
            }
        }
    }
}
```

For custom libraries, you'll need to provide Kotlin bindings or use `cinterop` to generate them.

## Security Considerations

- Validate all input strings (check for NULL, validate length)
- Avoid buffer overflows (Kotlin handles this automatically)
- Don't trust JSON content (could be malicious)
- Limit memory allocations (prevent DoS)
- Don't access filesystem or network without explicit permission
- Sanitize error messages (don't leak sensitive info)

## Language-Specific Resources

- [Kotlin/Native Documentation](https://kotlinlang.org/docs/native-overview.html)
- [Kotlin/Native C Interop](https://kotlinlang.org/docs/native-c-interop.html)
- [Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization)
- [Gradle Kotlin DSL](https://docs.gradle.org/current/userguide/kotlin_dsl.html)

## License

This example is public domain. Copy and modify freely for your own plugins.
