# Standalone JVM Plugin Example

A complete, self-contained example of a Karandash rule plugin written in Kotlin.

## What This Plugin Does

Validates that merge request titles match a configurable regex pattern. By default, it requires titles to start with a bracketed prefix like `[FEAT]`, `[FIX]`, `[DOCS]`, etc.

**Example validations:**
- OK: `[FEAT] Add new authentication system`
- OK: `[FIX] Resolve memory leak in cache`
- No: `Add new feature` (missing bracket prefix)

## Prerequisites

- JDK 17 or higher
- Internet connection (for first build to download dependencies)

## Building

Build the plugin JAR:

```bash
./gradlew build
```

The compiled plugin will be at: `build/libs/standalone-jvm-plugin.jar`

## Installation

### Option 1: Using Karandash CLI

```bash
karandash plugin install build/libs/standalone-jvm-plugin.jar
```

### Option 2: Manual Installation

```bash
mkdir -p ~/.karandash/plugins
cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
```

### Verify Installation

```bash
karandash plugin list
```

You should see "Example JVM Rule Plugin" in the list.

## Configuration

Add the rule to your `karandash.config.kts`:

```kotlin
rules {
    rule("exampleTitlePattern") {
        severity = error
        config {
            "pattern" to "^\\[\\w+\\].*"
            "message" to "MR title must start with a bracket prefix like [FEAT], [FIX], [DOCS]"
        }
    }
}
```

### Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `pattern` | String | `"^\\[\\w+\\].*"` | Regex pattern for title validation |
| `message` | String | Auto-generated | Custom error message when validation fails |

### Example Configurations

**Require JIRA ticket prefix:**
```kotlin
rules {
    rule("exampleTitlePattern") {
        severity = error
        config {
            "pattern" to "^[A-Z]+-\\d+:.*"
            "message" to "Title must start with JIRA ticket (e.g., PROJ-123: Description)"
        }
    }
}
```

**Semantic commit format:**
```kotlin
rules {
    rule("exampleTitlePattern") {
        severity = warning
        config {
            "pattern" to "^(feat|fix|docs|style|refactor|test|chore)(\\(.+\\))?:.*"
            "message" to "Title should follow semantic commit format"
        }
    }
}
```

## Testing

Run the test suite:

```bash
./gradlew test
```

## Project Structure

```
standalone-jvm-plugin/
├── build.gradle.kts              # Build configuration
├── settings.gradle.kts            # Gradle settings
├── gradle/                        # Gradle wrapper
├── gradlew                        # Gradle wrapper script (Unix)
├── gradlew.bat                    # Gradle wrapper script (Windows)
├── README.md                      # This file
└── src/
    └── main/
        ├── kotlin/
        │   └── com/example/karandash/
        │       └── ExampleRulePlugin.kt   # Plugin implementation
        └── resources/
            └── META-INF/services/
                └── io.karandash.api.Module  # ServiceLoader registration
```

## How It Works

### 1. Plugin Implementation

The plugin extends `io.karandash.sdk.module.AbstractRuleModule` (an `io.karandash.api.module.RuleModule`):

```kotlin
class ExampleRulePlugin : AbstractRuleModule() {
    override val id: String = "example-jvm-plugin"
    override val name: String = "Example JVM Rule Plugin"
    override val version: String = "1.0.0"

    override suspend fun contributedRules(): Set<String> {
        return setOf("exampleTitlePattern")
    }

    override suspend fun validate(ruleId: String, mr: MergeRequest, configElement: JsonElement): List<Finding> {
        // Validation logic here
    }
}
```

### 2. ServiceLoader Registration

The file `src/main/resources/META-INF/services/io.karandash.api.Module` registers the plugin. The host
discovers modules with `ServiceLoader.load(io.karandash.api.Module::class.java)`, so every listed class must
implement `io.karandash.api.Module` (`PluginLoadSmokeTest` checks this). `ConfigFormatModule` is not a
`Module`, so a config format is contributed by wrapping it in `io.karandash.sdk.config.ConfigFormatModuleAdapter`
and listing the adapter - see `ExampleConfigFormatPlugin`, which the host then finds via
`ModuleRegistry.configFormats()`.

```
com.example.karandash.ExampleRulePlugin
```

This enables automatic discovery when the JAR is placed in the plugins directory.

### 3. Dependencies

The plugin compiles against the real plugin SDK (`ru.kode.karandash:sdk`). Inside this repository,
`settings.gradle.kts` substitutes it with the `:plugins:sdk` project via a composite build.
The host provides the SDK at runtime, so it is `compileOnly` and not bundled into the JAR:

```kotlin
dependencies {
    compileOnly("ru.kode.karandash:sdk")
}
```

## Customizing This Plugin

To create your own rule plugin based on this example:

### 1. Copy the Directory

```bash
cp -r standalone-jvm-plugin my-custom-plugin
cd my-custom-plugin
```

### 2. Update Package and Class Names

Edit `src/main/kotlin/com/example/karandash/ExampleRulePlugin.kt`:
- Change package from `com.example.karandash` to your own
- Rename `ExampleRulePlugin` class
- Update `id`, `name`, and `version` fields

### 3. Update ServiceLoader Registration

Edit `src/main/resources/META-INF/services/io.karandash.api.Module`:
- Update to your new fully-qualified class name

### 4. Update Build Configuration

Edit `build.gradle.kts`:
- Change `group` to your organization
- Update `rootProject.name` in `settings.gradle.kts`

### 5. Implement Your Validation Logic

Modify the `validate()` method to implement your custom rules:

```kotlin
private fun myCustomValidation(mr: MergeRequest, configElement: JsonElement): List<Finding> {
    val config = configElement as? JsonObject ?: JsonObject(emptyMap())
    val findings = mutableListOf<Finding>()

    // Your validation logic here
    if (someConditionFails) {
        findings.add(Finding(
            rule = "my-rule-id",
            message = "Validation failed: ...",
            severity = Severity.ERROR,
            location = null
        ))
    }

    return findings
}
```

### 6. Build and Test

```bash
./gradlew clean build test
```

## Advanced Topics

### Accessing MR Data

The `MergeRequest` object provides:
- `title: String` - MR title
- `description: String?` - MR description
- `sourceBranch: String` - Source branch name
- `targetBranch: String` - Target branch name
- `author: String` - Author username
- `labels: List<String>` - Applied labels
- `commits: List<Commit>` - Commit history
- `changedFiles: List<ChangedFile>` - Modified files

### Multiple Rules in One Plugin

```kotlin
override fun contributedRules(): Set<String> {
    return setOf("rule-one", "rule-two", "rule-three")
}

override suspend fun validate(ruleId: String, mr: MergeRequest, configElement: JsonElement): List<Finding> {
    return when (ruleId) {
        "rule-one" -> validateRuleOne(mr, configElement)
        "rule-two" -> validateRuleTwo(mr, configElement)
        "rule-three" -> validateRuleThree(mr, configElement)
        else -> emptyList()
    }
}
```

### Severity Levels

```kotlin
Finding(
    rule = "my-rule",
    message = "...",
    severity = Severity.ERROR,    // Fails the validation
    // or
    severity = Severity.WARNING,  // Warning only
    // or
    severity = Severity.INFO,     // Informational
    location = null
)
```

### File-Level Findings

```kotlin
Finding(
    rule = "my-rule",
    message = "Issue in file",
    severity = Severity.ERROR,
    location = Location.FileRef(
        path = "src/main/kotlin/MyFile.kt",
        line = 42
    )
)
```

## Troubleshooting

**Build fails with "Could not resolve ru.kode.karandash:sdk":**
- Inside the repository, keep the `includeBuild("../../..")` block in `settings.gradle.kts`
- Outside it, remove that block and depend on a published SDK version instead

**Plugin not discovered:**
- Verify `META-INF/services/io.karandash.api.Module` contains the correct class name
- Ensure the JAR is in `~/.karandash/plugins/`
- Check that the class name matches exactly (including package)

**Validation not running:**
- Verify the rule ID in `karandash.config.kts` matches `contributedRules()`
- Check logs for initialization errors
- Ensure `initialize()` returns `true`

## License

This example is public domain. Copy and modify freely for your own plugins.
