# Plugin SDK API Reference

Complete reference for the Karandash Plugin SDK interfaces and types. Use these interfaces to create custom rule validators, reporters, and providers.

**Package:** `io.karandash.sdk` (interfaces), `io.karandash.models` (data types)

**Dependency:**

```kotlin
// build.gradle.kts
dependencies {
    implementation("ru.kode.karandash:sdk:<version>")
}
```

---

## Core Interfaces

### `Module`

Base interface for all plugin modules. Every plugin must implement this interface (typically through `RuleModule`, `ReporterModule`, or by implementing `Module` directly for provider modules).

**Package:** `io.karandash.sdk`

```kotlin
interface Module {
    val id: String
    val name: String
    val version: String
    val type: ModuleType
    val description: String get() = name
    val dslName: String get() = id
    val configKey: String get() = dslName
    suspend fun initialize(context: PluginContext): Boolean
    suspend fun shutdown() {}
    fun defaultConfig(): JsonElement = JsonObject(emptyMap())
    fun configSchema(): List<ConfigProperty> = emptyList()
}
```

| Member | Type | Description |
|--------|------|-------------|
| `id` | `String` | Unique module identifier (e.g., `"my-rules"`) |
| `name` | `String` | Human-readable display name |
| `version` | `String` | Semantic version string (e.g., `"1.0.0"`) |
| `type` | `ModuleType` | Module category: `RULE`, `REPORTER`, `PLATFORM_SERVICE`, etc. |
| `description` | `String` | One-line human-readable description. Defaults to `name`. |
| `dslName` | `String` | DSL block name in .karandash.kts (camelCase). Defaults to `id`. |
| `configKey` | `String` | YAML/JSON config key (kebab-case). Defaults to `dslName`. |
| `initialize(context)` | `Boolean` | Called once at startup (suspend). Return `true` if initialization succeeded. |
| `shutdown()` | `Unit` | Called on cleanup (suspend). Override to release resources. Default is no-op. |
| `defaultConfig()` | `JsonElement` | Returns the module's default configuration. Default is empty `JsonObject`. |
| `configSchema()` | `List<ConfigProperty>` | Declares accepted config properties for LSP and docs. |

---

### `RuleModule`

Interface for modules that contribute validation rules.

**Package:** `io.karandash.sdk`

```kotlin
interface RuleModule : Module {
    override val type: ModuleType get() = ModuleType.RULE
    fun contributedRules(): Set<String>
    fun ruleExplanation(ruleId: String): String? = null
    suspend fun validate(ruleId: String, mr: MergeRequest, configElement: JsonElement): List<Finding>
}
```

| Member | Return Type | Description |
|--------|-------------|-------------|
| `type` | `ModuleType` | Always `ModuleType.RULE` |
| `contributedRules()` | `Set<String>` | Returns the set of rule IDs this module provides (e.g., `setOf("titleFormat", "myCustomRule")`) |
| `defaultEnabled` (from `Module`) | `Boolean` | Whether this rule is enabled without explicit config. Default `false` - rules require explicit listing in config or a profile to activate. Only the zero-config profile (`MinimalProfileModule`) selectively enables rules. |
| `ruleExplanation(ruleId)` | `String?` | Human-readable explanation for `--explain` mode. |
| `validate(ruleId, mr, configElement)` | `List<Finding>` | Validate the given MR for the specified rule. Returns a list of findings (empty list means the rule passed). |

**Parameters for `validate`:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `ruleId` | `String` | The rule being evaluated (from `contributedRules()`) |
| `mr` | `MergeRequest` | The merge request data to validate |
| `configElement` | `JsonElement` | Plugin-specific configuration as a JSON element from the config file |

**Example implementation:**

```kotlin
class MyRuleModule : RuleModule {
    override val id = "my-rules"
    override val name = "My Custom Rules"
    override val version = "1.0.0"

    override suspend fun initialize(context: PluginContext): Boolean {
        context.log("MyRuleModule initialized")
        return true
    }

    override fun contributedRules(): Set<String> = setOf("maxTitleLength")

    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement
    ): List<Finding> {
        val config = configElement as? JsonObject ?: JsonObject(emptyMap())
        return when (ruleId) {
            "maxTitleLength" -> {
                val maxLen = (config["maxLength"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 100
                if (mr.title.length > maxLen) {
                    listOf(Finding(
                        severity = Severity.ERROR,
                        message = "Title exceeds $maxLen characters",
                        rule = "maxTitleLength"
                    ))
                } else emptyList()
            }
            else -> emptyList()
        }
    }
}
```

---

### `ReporterModule`

Interface for modules that output validation results in custom formats.

**Package:** `io.karandash.sdk`

```kotlin
interface ReporterModule : Module {
    override val type: ModuleType get() = ModuleType.REPORTER
    val formatId: String
    val requiresPlatformContext: Boolean get() = false
    suspend fun report(findings: List<Finding>, passed: Boolean, config: JsonElement): String?
}
```

| Member | Type | Description |
|--------|------|-------------|
| `type` | `ModuleType` | Always `ModuleType.REPORTER` |
| `formatId` | `String` | Format identifier (e.g., `"json"`, `"webhook"`, `"slack"`) |
| `defaultEnabled` (from `Module`) | `Boolean` | Whether this reporter is active by default. |
| `requiresPlatformContext` | `Boolean` | Whether this reporter needs platform context (platform, project, MR id). Default `false`. |
| `report(findings, passed, config)` | `String?` | Format and output findings. Return a string for file-based output, or `null` for side-effect reporters (e.g., webhook). |

**Parameters for `report`:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `findings` | `List<Finding>` | All validation findings |
| `passed` | `Boolean` | Whether validation passed overall |
| `config` | `JsonElement` | Reporter-specific configuration as a JSON element |

**Example implementation:**

```kotlin
class WebhookReporter : ReporterModule {
    override val id = "webhook-reporter"
    override val name = "Webhook Reporter"
    override val version = "1.0.0"
    override val formatId = "webhook"

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun report(
        findings: List<Finding>,
        passed: Boolean,
        config: JsonElement
    ): String? {
        val configObj = config as? JsonObject ?: return null
        val url = (configObj["webhookUrl"] as? JsonPrimitive)?.contentOrNull ?: return null
        // Send HTTP POST to webhook URL...
        return null  // Side-effect reporter, no file output
    }
}
```

---

### `PluginContext`

Execution context provided to plugins during initialization.

**Package:** `io.karandash.sdk`

```kotlin
interface PluginContext {
    val pluginConfig: JsonElement
    fun log(message: String)
    fun warn(message: String)
    fun env(name: String): String?
    val hostVersion: String
    val workingDirectory: String
    val allowLocalhost: Boolean get() = false
}
```

| Member | Type | Description |
|--------|------|-------------|
| `pluginConfig` | `JsonElement` | Plugin-specific configuration as a JSON element from the config file |
| `log(message)` | `Unit` | Log an informational message |
| `warn(message)` | `Unit` | Log a warning message |
| `env(name)` | `String?` | Read an environment variable by name |
| `hostVersion` | `String` | Karandash host version (e.g., `"0.1.0"`) |
| `workingDirectory` | `String` | Current working directory path |

---

### `ModuleType`

Enum classifying plugin module types.

**Package:** `io.karandash.sdk`

```kotlin
enum class ModuleType {
    RULE,
    REPORTER,
    PLATFORM_SERVICE,
    ENRICHMENT_PROVIDER,
    PIPELINE_EXTENSION,
    // ... and additional types for AI, CLI, config format, etc.
}
```

| Value | Description |
|-------|-------------|
| `RULE` | Validation rule module |
| `REPORTER` | Output reporter module |
| `PLATFORM_SERVICE` | Unified platform integration (fetch MR, post comments, mutate state) |
| `ENRICHMENT_PROVIDER` | Data enrichment provider |
| `PIPELINE_EXTENSION` | Pre/post validation hooks |

---

## Data Models

### `MergeRequest`

Core data model representing a merge/pull request.

**Package:** `io.karandash.models`

```kotlin
data class MergeRequest(
    val id: String,
    val iid: Int,
    val title: String,
    val description: String?,
    val sourceBranch: String,
    val targetBranch: String,
    val author: User,
    val state: String,
    val draft: Boolean,
    val labels: List<String>,
    val commits: List<Commit>,
    val changes: List<FileChange>,
    val approvals: List<Approval> = emptyList(),
    val reviewers: List<User> = emptyList()
)
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | `String` | Unique global identifier |
| `iid` | `Int` | Internal (project-scoped) ID |
| `title` | `String` | MR/PR title |
| `description` | `String?` | MR/PR description body (nullable) |
| `sourceBranch` | `String` | Source branch name |
| `targetBranch` | `String` | Target branch name |
| `author` | `User` | MR/PR author |
| `state` | `String` | Current state (e.g., `"opened"`, `"merged"`) |
| `draft` | `Boolean` | Whether the MR is a draft/WIP |
| `labels` | `List<String>` | Applied labels |
| `commits` | `List<Commit>` | Commits in the MR |
| `changes` | `List<FileChange>` | Files changed in the MR |
| `approvals` | `List<Approval>` | Approvals received |
| `reviewers` | `List<User>` | Assigned reviewers |

---

### `Finding`

A single validation result produced by a rule.

**Package:** `io.karandash.models`

```kotlin
data class Finding(
    val severity: Severity,
    val message: String,
    val rule: String,
    val location: Location? = null,
    val suggestion: String? = null,
    val explanation: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val fixSuggestions: List<FixSuggestion> = emptyList(),
)
```

| Field | Type | Description |
|-------|------|-------------|
| `severity` | `Severity` | Finding severity: `ERROR`, `WARNING`, or `INFO` |
| `message` | `String` | Human-readable finding message |
| `rule` | `String` | Rule ID that produced the finding |
| `location` | `Location?` | Optional file/line location |
| `suggestion` | `String?` | Short actionable hint shown to the MR author inline (in comments and console output) |
| `explanation` | `String?` | Longer explanation of why the rule exists (shown with `--explain`) |
| `metadata` | `Map<String, String>` | Arbitrary key-value metadata |
| `fixSuggestions` | `List<FixSuggestion>` | Structured fix suggestions for auto-fixer (see `FixSuggestion`) |

Built-in rules populate `suggestion` and `explanation` from the `suggestion` and `explanation` fields in the rule's config block. Custom plugins can set these fields directly when constructing `Finding` objects.

`message` holds the problem only; `suggestion` holds the imperative fix. Pattern-based rules can add a structured fix
hint through well-known metadata keys, which every reporter renders under the labels **Where**, **How to fix** and
**Format**:

| Key (`WellKnownMetadataKeys`) | Value | Accessor |
|-------------------------------|-------|----------|
| `EXPECTED_FORMAT` (`expectedFormat`) | Required format as Pomsky source | `Finding.expectedFormat(): String?` |
| `MISMATCH` (`mismatch`) | JSON `{"value","position","expected","group"?}` | `Finding.mismatch(): Mismatch?`, write with `Mismatch.toMetadataValue()` |
| `FORMAT_EXPLAIN` (`formatExplain`) | Plain-text explanation of the format | - |
| `FORMAT_DIAGRAM` (`formatDiagram`) | ASCII diagram of the format | - |

`Finding.fixHintLines(language)` returns the labelled plain-text lines; `FIX_HINT_METADATA_KEYS` lists the four keys so
text reporters can leave them out of a generic metadata dump.

Rules that produce auto-fixable findings should include structured `fixSuggestions` so the auto-fixer can apply fixes generically without parsing message text.

**Example:**

```kotlin
Finding(
    severity = Severity.ERROR,
    message = "Missing required label: 'bug'",
    rule = "labels",
    suggestion = "Add the required label via the MR sidebar.",
    explanation = "Labels help categorize and route MRs through review workflows.",
    fixSuggestions = listOf(FixSuggestion.AddLabel("bug")),
)
```

---

### `FixSuggestion`

Structured fix suggestion emitted by rules alongside findings. The auto-fixer processes these generically without inspecting rule IDs or message text.

**Package:** `io.karandash.models`

```kotlin
sealed class FixSuggestion {
    data class AddLabel(val label: String) : FixSuggestion()
    data class RemoveLabel(val label: String) : FixSuggestion()
    data class SetDraft(val draft: Boolean) : FixSuggestion()
    data class SetTitle(val title: String) : FixSuggestion()
    data class SetDescription(val description: String) : FixSuggestion()
}
```

| Subtype | Fields | Description |
|---------|--------|-------------|
| `AddLabel` | `label: String` | Add a label to the merge request |
| `RemoveLabel` | `label: String` | Remove a label from the merge request |
| `SetDraft` | `draft: Boolean` | Set or unset draft/WIP status |
| `SetTitle` | `title: String` | Update the merge request title |
| `SetDescription` | `description: String` | Update the merge request description |

---

### `Severity`

Finding severity levels.

**Package:** `io.karandash.models`

```kotlin
enum class Severity {
    ERROR,
    WARNING,
    INFO
}
```

| Value | Effect | Description |
|-------|--------|-------------|
| `ERROR` | Exit code `1` | Rule violation. Blocks merge. |
| `WARNING` | Exit code `5` (strict only) | Advisory. Blocks only with `--strict`. |
| `INFO` | No effect | Informational. Never affects exit code. |

---

### `Location`

Optional source location for a finding. Implemented as a sealed class with two variants.

**Package:** `io.karandash.models`

```kotlin
sealed class Location {
    data class FileRef(
        val path: String,
        val line: Int? = null
    ) : Location()

    data class CommitRef(
        val sha: String
    ) : Location()
}
```

| Subtype | Fields | Description |
|---------|--------|-------------|
| `FileRef` | `path: String`, `line: Int?` | A location within a source file, optionally at a specific line |
| `CommitRef` | `sha: String` | A reference to a specific commit by its SHA hash |

---

### `User`

Represents a user (author, reviewer, approver).

**Package:** `io.karandash.models`

```kotlin
data class User(
    val username: String,
    val name: String,
    val email: String? = null
)
```

| Field | Type | Description |
|-------|------|-------------|
| `username` | `String` | Platform username/handle |
| `name` | `String` | Display name |
| `email` | `String?` | Email address (nullable) |

---

### `Commit`

A single commit in the MR.

**Package:** `io.karandash.models`

```kotlin
data class Commit(
    val sha: String,
    val message: String,
    val author: CommitAuthor,
    val timestamp: String
)
```

| Field | Type | Description |
|-------|------|-------------|
| `sha` | `String` | Commit SHA hash |
| `message` | `String` | Full commit message |
| `author` | `CommitAuthor` | Commit author info |
| `timestamp` | `String` | Commit timestamp (ISO 8601) |

### `CommitAuthor`

```kotlin
data class CommitAuthor(
    val name: String,
    val email: String
)
```

---

### `FileChange`

A single file change in the MR.

**Package:** `io.karandash.models`

```kotlin
data class FileChange(
    val path: String,
    val changeType: ChangeType,
    val additions: Int = 0,
    val deletions: Int = 0,
    val diffContent: String? = null
)
```

| Field | Type | Description |
|-------|------|-------------|
| `path` | `String` | File path relative to repository root |
| `changeType` | `ChangeType` | Type of change |
| `additions` | `Int` | Number of added lines |
| `deletions` | `Int` | Number of deleted lines |
| `diffContent` | `String?` | Raw diff content (nullable) |

### `ChangeType`

```kotlin
enum class ChangeType {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED
}
```

---

### `Approval`

A single approval on the MR.

**Package:** `io.karandash.models`

```kotlin
data class Approval(
    val user: User,
    val approvedAt: String? = null
)
```

| Field | Type | Description |
|-------|------|-------------|
| `user` | `User` | The approving user |
| `approvedAt` | `String?` | ISO 8601 timestamp of approval |

---

## Provider Interfaces

### `MergeRequestProvider`

Interface for fetching MR data from platform APIs.

**Package:** `io.karandash.providers`

```kotlin
interface MergeRequestProvider {
    fun fetchMergeRequest(projectId: String, mrId: String): MergeRequest
    fun fetchMergeRequestByUrl(url: String): MergeRequest
    fun close() {}
}
```

| Method | Description |
|--------|-------------|
| `fetchMergeRequest(projectId, mrId)` | Fetch MR by project ID and MR ID |
| `fetchMergeRequestByUrl(url)` | Fetch MR by its full URL |
| `close()` | Release resources (HTTP clients, connections). Default is no-op. |

---

### `MergeRequestMutator`

Interface for providers that support modifying MR properties. Used by the `--fix` flag.

**Package:** `io.karandash.providers`

```kotlin
interface MergeRequestMutator {
    fun addLabels(projectId: String, mrId: String, labels: List<String>)
    fun removeLabels(projectId: String, mrId: String, labels: List<String>)
    fun setDraft(projectId: String, mrId: String, draft: Boolean)
    fun updateTitle(projectId: String, mrId: String, title: String)
    fun updateDescription(projectId: String, mrId: String, description: String)
}
```

| Method | Description |
|--------|-------------|
| `addLabels(projectId, mrId, labels)` | Add labels to a MR |
| `removeLabels(projectId, mrId, labels)` | Remove labels from a MR |
| `setDraft(projectId, mrId, draft)` | Set draft status of a MR |
| `updateTitle(projectId, mrId, title)` | Update the title of a MR |
| `updateDescription(projectId, mrId, description)` | Update the description body of a MR |

---

### `CommentPoster`

Interface for posting and managing MR comments.

**Package:** `io.karandash.providers`

```kotlin
interface CommentPoster {
    fun postComment(projectId: String, mrId: String, body: String): String
    fun updateComment(projectId: String, mrId: String, commentId: String, body: String)
    fun findExistingComment(projectId: String, mrId: String, marker: String): String?
}
```

| Method | Return | Description |
|--------|--------|-------------|
| `postComment(projectId, mrId, body)` | `String` | Post a comment. Returns the comment ID. |
| `updateComment(projectId, mrId, commentId, body)` | `Unit` | Update an existing comment. |
| `findExistingComment(projectId, mrId, marker)` | `String?` | Find a comment containing a marker string. Returns comment ID or `null`. |

---

### `Validator`

Internal interface for built-in validators.

**Package:** `io.karandash.validators`

```kotlin
interface Validator {
    fun validate(mr: MergeRequest): List<Finding>
}
```

Plugin authors typically implement `RuleModule` rather than `Validator` directly.

---

## Plugin Registration

Plugins are discovered via Java `ServiceLoader`. Create the service declaration file:

**File:** `src/main/resources/META-INF/services/io.karandash.sdk.Module`

```
com.example.MyRuleModule
```

Each line is a fully-qualified class name implementing `Module` (or a sub-interface like `RuleModule`).

## See Also

- [Configuration Schema](configuration-schema.md) -- plugin configuration options
- [CLI Reference](cli-reference.md) -- `plugin create` and `generate` commands
- [Error Codes](error-codes.md) -- exit codes for plugin errors
