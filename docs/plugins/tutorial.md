# Plugin Development Tutorial

Build a complete Karandash plugin from scratch in 30 minutes.

## Introduction

In this tutorial, you'll create a real working plugin called **CommitMessageLengthValidator** that validates commit message lengths in merge requests.

### What You'll Build

A Karandash validation plugin that:
- Checks commit message length against configurable limits
- Returns detailed findings with severity levels
- Supports custom configuration via `.karandash/config.kts`
- Includes tests
- Packages as a distributable JAR

### Prerequisites

- JDK 17 or higher
- Gradle 7.0+
- Basic Kotlin knowledge (or Java - both work)
- Git
- 30 minutes

### Time Estimate

- Setup: 5 minutes
- Implementation: 10 minutes
- Testing: 7 minutes
- Packaging: 3 minutes
- Verification: 5 minutes

---

## Step 1: Setup Project Structure

Create your plugin project directory:

```bash
mkdir commit-length-validator-plugin
cd commit-length-validator-plugin
```

Create the directory structure:

```
commit-length-validator-plugin/
├── build.gradle.kts
├── settings.gradle.kts
├── src/
│   ├── main/
│   │   └── kotlin/
│   │       └── io/karandash/plugins/commitlength/
│   │           ├── CommitLengthValidator.kt
│   │           └── CommitLengthRuleModule.kt
│   └── test/
│       └── kotlin/
│           └── io/karandash/plugins/commitlength/
│               └── CommitLengthValidatorTest.kt
└── README.md
```

Create `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "commit-length-validator-plugin"
```

### Configure build.gradle.kts

Create `build.gradle.kts` in the root directory:

```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

group = "io.karandash.plugins"
version = "1.0.0"
description = "Commit message length validation plugin"

repositories {
    mavenCentral()
}

dependencies {
    // Plugin SDK
    implementation("ru.kode.karandash:sdk:1.0.1")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

tasks {
    test {
        useJUnitPlatform()
    }

    jar {
        archiveFileName.set("${project.name}-${project.version}.jar")
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
                "Main-Class" to "io.karandash.plugins.commitlength.CommitLengthRuleModule"
            )
        }
    }
}
```

---

## Step 2: Create the Validator Class

Create `src/main/kotlin/io/karandash/plugins/commitlength/CommitLengthValidator.kt`:

```kotlin
package io.karandash.plugins.commitlength

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity

data class CommitLengthConfig(
    val maxLength: Int = 72,
    val warnLength: Int = 50,
    val enabled: Boolean = true
)

class CommitLengthValidator(private val config: CommitLengthConfig) {

    fun validate(mr: MergeRequest): List<Finding> {
        if (!config.enabled) {
            return emptyList()
        }

        val findings = mutableListOf<Finding>()

        mr.commits.forEach { commit ->
            val firstLine = commit.message.lines().firstOrNull() ?: return@forEach

            when {
                firstLine.length > config.maxLength -> {
                    findings.add(
                        Finding(
                            severity = Severity.ERROR,
                            message = "Commit message is too long (${firstLine.length} > ${config.maxLength}): ${firstLine.take(50)}...",
                            rule = "commit-length-max",
                            explanation = "Keep the first line of commit messages under ${config.maxLength} characters for better readability"
                        )
                    )
                }
                firstLine.length > config.warnLength -> {
                    findings.add(
                        Finding(
                            severity = Severity.WARNING,
                            message = "Commit message is getting long (${firstLine.length} > ${config.warnLength}): ${firstLine.take(50)}...",
                            rule = "commit-length-warn"
                        )
                    )
                }
            }
        }

        return findings
    }
}
```

---

## Step 3: Create the Rule Module

Create `src/main/kotlin/io/karandash/plugins/commitlength/CommitLengthRuleModule.kt`:

```kotlin
package io.karandash.plugins.commitlength

import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.sdk.PluginContext
import io.karandash.sdk.module.RuleModule
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

class CommitLengthRuleModule : RuleModule {

    override val id: String = "commit-length-validator"
    override val name: String = "Commit Message Length Validator"
    override val version: String = "1.0.0"

    private var validator: CommitLengthValidator? = null
    private var context: PluginContext? = null

    override suspend fun initialize(context: PluginContext): Boolean {
        this.context = context

        // Extract configuration from plugin config (config flows as JsonElement)
        val pluginConfig = context.pluginConfig as? JsonObject
        val maxLength = (pluginConfig?.get("maxLength") as? JsonPrimitive)?.intOrNull ?: 72
        val warnLength = (pluginConfig?.get("warnLength") as? JsonPrimitive)?.intOrNull ?: 50
        val enabled = (pluginConfig?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: true

        val config = CommitLengthConfig(
            maxLength = maxLength,
            warnLength = warnLength,
            enabled = enabled
        )

        validator = CommitLengthValidator(config)
        context.log("Commit length validator initialized with maxLength=$maxLength, warnLength=$warnLength")

        return true
    }

    override suspend fun shutdown() {
        validator = null
        context = null
    }

    override suspend fun contributedRules(): Set<String> {
        return setOf(
            "commit-length-max",
            "commit-length-warn"
        )
    }

    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement
    ): List<Finding> {
        val validator = this.validator ?: return listOf(
            Finding(
                severity = Severity.ERROR,
                message = "Validator not initialized",
                rule = ruleId
            )
        )

        return when (ruleId) {
            "commit-length-max", "commit-length-warn" -> {
                validator.validate(mr)
            }
            else -> listOf(
                Finding(
                    severity = Severity.ERROR,
                    message = "Unknown rule: $ruleId",
                    rule = ruleId
                )
            )
        }
    }
}
```

> **Key Points:**
> - `RuleModule` interface defines the plugin contract
> - `initialize()` is called once on startup; return `false` to disable the plugin
> - `contributedRules()` declares which validation rules this plugin provides
> - `validate()` runs the actual validation logic
> - Return `List<Finding>` with results (empty list = all passed)

---

## Step 4: Write Unit Tests

Create `src/test/kotlin/io/karandash/plugins/commitlength/CommitLengthValidatorTest.kt`:

```kotlin
package io.karandash.plugins.commitlength

import io.karandash.models.*
import io.karandash.models.finding.Severity
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommitLengthValidatorTest {

    private lateinit var validator: CommitLengthValidator

    @Before
    fun setup() {
        validator = CommitLengthValidator(
            CommitLengthConfig(maxLength = 72, warnLength = 50)
        )
    }

    private fun createTestMR(commits: List<String>): MergeRequest {
        return MergeRequest(
            id = "1",
            iid = 1,
            title = "Test MR",
            description = null,
            sourceBranch = "feature/test",
            targetBranch = "main",
            author = User(username = "user1", name = "User One", email = "user1@example.com"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = commits.mapIndexed { idx, msg ->
                Commit(
                    sha = "abc123$idx",
                    message = msg,
                    author = CommitAuthor(name = "User One", email = "user1@example.com"),
                    timestamp = "2024-01-01T00:00:00Z"
                )
            },
            changes = emptyList()
        )
    }

    @Test
    fun testShortCommitMessage() {
        val mr = createTestMR(listOf("Fix bug"))
        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun testCommitMessageWarning() {
        val msg = "Fix bug that was causing errors in production".padEnd(55, '!')
        val mr = createTestMR(listOf(msg))
        val findings = validator.validate(mr)

        assertEquals(1, findings.size)
        assertEquals(Severity.WARNING, findings[0].severity)
        assertEquals("commit-length-warn", findings[0].rule)
    }

    @Test
    fun testCommitMessageError() {
        val msg = "x".repeat(100) // 100 character message
        val mr = createTestMR(listOf(msg))
        val findings = validator.validate(mr)

        assertEquals(1, findings.size)
        assertEquals(Severity.ERROR, findings[0].severity)
        assertEquals("commit-length-max", findings[0].rule)
        assertTrue(findings[0].message.contains("100"))
    }

    @Test
    fun testMultipleCommits() {
        val mr = createTestMR(
            listOf(
                "Short",
                "This commit message is a bit longer".padEnd(55, '!'),
                "x".repeat(100)
            )
        )
        val findings = validator.validate(mr)

        // Should have 2 findings (1 warning + 1 error)
        assertEquals(2, findings.size)
    }

    @Test
    fun testDisabledValidator() {
        val disabledValidator = CommitLengthValidator(
            CommitLengthConfig(maxLength = 72, enabled = false)
        )
        val mr = createTestMR(listOf("x".repeat(100)))
        val findings = disabledValidator.validate(mr)

        assertTrue(findings.isEmpty())
    }

    @Test
    fun testEmptyCommitMessage() {
        val mr = createTestMR(listOf(""))
        val findings = validator.validate(mr)
        assertTrue(findings.isEmpty())
    }
}
```

> **Testing Strategy:**
> - Test valid cases (no findings)
> - Test warning threshold
> - Test error threshold
> - Test edge cases (empty, disabled, multiple commits)
> - Create helper to build test MR objects

---

## Step 5: Build the Plugin JAR

Build your plugin:

```bash
./gradlew build
```

The compiled JAR will be at:

```
build/libs/commit-length-validator-plugin-1.0.0.jar
```

Verify the build succeeded:

```bash
./gradlew test
```

---

## Step 6: Test Locally

### 6a. Create a Test Configuration

Create a test directory and `.karandash/config.kts` file to load your plugin:

```kotlin
karandash {
    rules {
        rule("commit-length-max") {
            enabled = true
        }
        rule("commit-length-warn") {
            enabled = true
        }
    }

    plugins {
        // For development, use the JAR path directly
        plugin("/path/to/commit-length-validator-plugin-1.0.0.jar") {
            config {
                maxLength = 72
                warnLength = 50
                enabled = true
            }
        }
    }
}
```

### 6b. Create Test MR Data

Create a sample MR JSON file to test against:

```json
{
  "id": "123",
  "iid": 1,
  "title": "Fix authentication bug",
  "description": "Fixes issue with JWT validation in the authentication service",
  "source_branch": "fix/jwt-validation",
  "target_branch": "main",
  "author": {
    "id": "1",
    "username": "developer",
    "email": "dev@example.com",
    "name": "Developer One"
  },
  "state": "opened",
  "draft": false,
  "labels": ["bug", "security"],
  "commits": [
    {
      "sha": "abc123",
      "message": "Fix JWT validation\n\nThis commit fixes an issue where expired tokens were not properly validated.",
      "author": {
        "name": "Developer One",
        "email": "dev@example.com"
      },
      "timestamp": "2024-01-01T10:00:00Z"
    },
    {
      "sha": "def456",
      "message": "This is an extremely long commit message that exceeds the recommended length limit and should trigger a warning during validation process",
      "author": {
        "name": "Developer One",
        "email": "dev@example.com"
      },
      "timestamp": "2024-01-01T11:00:00Z"
    }
  ],
  "changes": []
}
```

### 6c. Manual Testing

In a Karandash project directory:

```bash
# Copy your JAR to the plugins directory (or reference it in config)
cp /path/to/commit-length-validator-plugin-1.0.0.jar ./plugins/

# Run validation
karandash validate --config .karandash/config.kts
```

Expected output:

```
ValidationResult {
  rule: "commit-length-warn"
  severity: WARNING
  message: "Commit message is getting long (87 > 50): This is an extremely long commit m..."
}

ValidationResult {
  rule: "commit-length-max"
  severity: ERROR
  message: "Commit message is too long (87 > 72): This is an extremely long commit m..."
}
```

---

## Step 7: Documentation

Create a `README.md` in your plugin root:

```markdown
# Commit Message Length Validator Plugin

A Karandash validation plugin that enforces commit message length standards.

## Features

- Validates commit message length against configurable thresholds
- Separate warning and error levels
- Detailed feedback for each violation
- Easy configuration via `.karandash/config.kts`

## Installation

### Option 1: Copy JAR

```bash
cp commit-length-validator-plugin-1.0.0.jar ~/.karandash/plugins/
```

### Option 2: Maven Repository

Add to your `.karandash/config.kts`:

```kotlin
plugins {
    plugin("io.karandash.plugins:commit-length-validator:1.0.0")
}
```

## Configuration

In your `.karandash/config.kts`:

```kotlin
karandash {
    plugins {
        plugin("commit-length-validator") {
            config {
                maxLength = 72      // Error if > 72 chars
                warnLength = 50     // Warning if > 50 chars
                enabled = true
            }
        }
    }

    rules {
        rule("commit-length-max") {
            enabled = true
        }
        rule("commit-length-warn") {
            enabled = true
        }
    }
}
```

## Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `maxLength` | Int | 72 | Maximum commit message length (error) |
| `warnLength` | Int | 50 | Recommended message length (warning) |
| `enabled` | Boolean | true | Enable/disable validation |

## Rules

### commit-length-max

Error severity when first line of commit exceeds `maxLength`.

### commit-length-warn

Warning severity when first line of commit exceeds `warnLength`.

## Examples

### Configuration: Strict Length Limit

```kotlin
plugins {
    plugin("commit-length-validator") {
        config {
            maxLength = 50
            warnLength = 40
        }
    }
}
```

### Configuration: Relaxed Limit

```kotlin
plugins {
    plugin("commit-length-validator") {
        config {
            maxLength = 100
            warnLength = 80
        }
    }
}
```

## Building from Source

```bash
./gradlew build
./gradlew test
```

JAR location: `build/libs/commit-length-validator-plugin-1.0.0.jar`

## Development

### Running Tests

```bash
./gradlew test
```

### Building with Custom Version

```bash
./gradlew build -Pversion=1.0.1
```

## License

MIT

## Support

For issues or questions, open an issue in the Karandash repository.
```

---

## Step 8: Package for Distribution

### Option 1: Publish to Maven

Create `build.gradle.kts` publishing block:

```kotlin
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "io.karandash.plugins"
            artifactId = "commit-length-validator"
            version = "1.0.0"
            from(components["java"])

            pom {
                name.set("Commit Message Length Validator")
                description.set("Karandash plugin to validate commit message length")
                url.set("https://github.com/karandash/plugins")

                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("yourname")
                        name.set("Your Name")
                        email.set("you@example.com")
                    }
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/karandash/plugins")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

Publish:

```bash
./gradlew publish
```

### Option 2: Create GitHub Release

```bash
# Tag the release
git tag v1.0.0

# Push the tag
git push origin v1.0.0

# Create GitHub release with JAR attached
gh release create v1.0.0 build/libs/commit-length-validator-plugin-1.0.0.jar
```

---

## Step 9: Verify Installation

Test that your plugin installs correctly in a Karandash project:

```bash
# Create a test project
mkdir test-project
cd test-project
git init

# Create .karandash/config.kts
cat > .karandash/config.kts << 'EOF'
karandash {
    plugins {
        plugin("commit-length-validator") {
            config {
                maxLength = 72
                warnLength = 50
            }
        }
    }

    rules {
        rule("commit-length-max") { enabled = true }
        rule("commit-length-warn") { enabled = true }
    }
}
EOF

# Create a sample MR file
cat > mr.json << 'EOF'
{
  "id": "1", "iid": 1, "title": "Test",
  "description": null,
  "source_branch": "feature/test",
  "target_branch": "main",
  "author": {"id": "1", "username": "test", "email": "test@example.com", "name": "Tester"},
  "state": "opened", "draft": false, "labels": [],
  "commits": [
    {"sha": "abc", "message": "This commit message is way too long and exceeds the maximum character limit for the first line of a commit message", "author": {"name": "Tester", "email": "test@example.com"}, "timestamp": "2024-01-01T00:00:00Z"}
  ],
  "changes": []
}
EOF

# Run validation
karandash validate
```

---

## Advanced Topics

### Multiple Validators in One Plugin

You can provide multiple validation rules from a single plugin:

```kotlin
override suspend fun contributedRules(): Set<String> {
    return setOf(
        "commit-length-max",
        "commit-length-warn",
        "commit-line-count",
        "commit-format"
    )
}

override suspend fun validate(ruleId: String, mr: MergeRequest, configElement: JsonElement): List<Finding> {
    return when (ruleId) {
        "commit-length-max" -> validateLength(mr)
        "commit-length-warn" -> validateLengthWarning(mr)
        "commit-line-count" -> validateLineCount(mr)
        "commit-format" -> validateFormat(mr)
        else -> emptyList()
    }
}
```

### Using Async Operations

Since `validate()` is a `suspend fun`, you can call other suspend functions directly:

```kotlin
override suspend fun validate(ruleId: String, mr: MergeRequest, configElement: JsonElement): List<Finding> {
    return when (ruleId) {
        "jira-check" -> validateWithJira(mr)
        else -> emptyList()
    }
}

private suspend fun validateWithJira(mr: MergeRequest): List<Finding> {
    val ticketId = extractTicketId(mr.title)
    val status = httpClient.getTicketStatus(ticketId)
    // Return findings based on status
}
```

### Performance Considerations

- Cache results when possible (e.g., ticket lookups)
- Use connection pooling for HTTP clients
- Avoid blocking operations on critical paths
- Test plugin performance with large MRs (1000+ commits)

### Type-Safe Configuration

```kotlin
data class ValidatorConfig(
    val maxLength: Int = 72,
    val warnLength: Int = 50,
    val ignoreScopes: Set<String> = emptySet()
)

private fun parseConfig(configElement: JsonElement): ValidatorConfig {
    val config = configElement as? JsonObject ?: JsonObject(emptyMap())
    return ValidatorConfig(
        maxLength = (config["maxLength"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 72,
        warnLength = (config["warnLength"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 50,
        ignoreScopes = (config["ignoreScopes"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()
    )
}
```

---

## Troubleshooting

### Plugin Not Loading

**Error:** `Plugin not found: commit-length-validator`

**Solution:**
- Verify the JAR is in the correct location
- Check plugin config is enabled in `.karandash/config.kts`
- Make sure class name matches exactly

```bash
# List loaded plugins
karandash plugin list
```

### Validation Not Running

**Error:** Plugin loads but validation doesn't execute

**Solution:**
- Verify rules are enabled in config:

```kotlin
rules {
    rule("commit-length-max") { enabled = true }
}
```

- Check that rule ID exactly matches `contributedRules()`

### Configuration Not Applied

**Error:** Plugin runs with default values, not custom config

**Solution:**
- Verify config is in plugin block:

```kotlin
plugins {
    plugin("commit-length-validator") {
        config {
            maxLength = 100  // Must be inside config block
        }
    }
}
```

### ClassNotFound Exception

**Error:** `java.lang.ClassNotFoundException`

**Solution:**
- Make sure all dependencies are included in JAR (use shadowJar or fat JAR)
- Check Kotlin/Java version compatibility
- Verify plugin entry point is correct

### Testing Issues

**Error:** Tests fail with `NullPointerException` in PluginContext

**Solution:**
- Mock PluginContext properly:

```kotlin
class TestPluginContext : PluginContext {
    override val pluginConfig: JsonElement = JsonObject(emptyMap())
    override fun env(name: String): String? = null
    override val hostVersion = "1.0.0"
    override val workingDirectory = "/tmp"
}
```

---

## Next Steps

1. **Explore More Examples**: Check out the official plugins in the Karandash repository
2. **Read the API Reference**: Full documentation of RuleModule, Finding, MergeRequest interfaces
3. **Join the Community**: Discuss plugin development in Karandash issues/discussions
4. **Share Your Plugin**: Contribute to the plugin registry

## Real-World Examples

Looking for inspiration? The Karandash repository includes example plugins:

- **JIRA Plugin**: Integrates with JIRA to validate tickets
- **AI Plugin**: Uses OpenAI/Anthropic for intelligent validation
- **Slack Plugin**: Posts validation results to Slack
- **Email Plugin**: Sends validation reports via email

Each demonstrates different patterns:
- External API integration
- Async operations
- Complex configuration
- Error handling

---

## Summary

You now have a working plugin. This tutorial covered:

- Project structure and build configuration
- Implementing RuleModule interface
- Validator logic with Finding results
- Configuration and initialization
- Unit testing
- Building and packaging
- Local testing and verification
- Documentation and distribution

Publish it with the [publishing guide](../guides/publishing-plugins.md).
