# Glossary

Karandash uses specific terminology for its plugin architecture and configuration system.
This glossary clarifies key terms and resolves ambiguities between code and configuration terminology.

---

## Core Concepts

### Plugin

**In code:** The base interface for all Karandash extensions (`io.karandash.sdk.Plugin`).

**Umbrella term:** Any component that extends Karandash functionality, including:
- Structured modules (rules, reporters, providers)
- Script-based plugins (JavaScript `.mjs` via QuickJS, available on JVM and Native)
- WebAssembly plugins (`.wasm` via Chicory on JVM and Chasm on Native)
- Native plugins (`.dylib`/`.so`/`.dll` via dlopen/JNA)
- Other extension types

**In config:** The `plugins:` block is reserved for **external third-party plugins only**.
Built-in plugins use domain-specific blocks (`rules:`, `platforms:`, `reporters:`).

**Example:**
```yaml
plugins:
  - id: "com.example.custom-validator"
    version: "1.0.0"
```

**See also:** [Module](#module), `plugins/sdk/src/commonMain/kotlin/io/karandash/sdk/Plugin.kt`

---

### Module

**In code:** A structured plugin with lifecycle management (`io.karandash.sdk.Module`).

**Definition:** A specialized [Plugin](#plugin) that implements:
- **Type system**: Categorized as rule, reporter, platform-service, enrichment-provider, etc.
- **Lifecycle**: `initialize()` and `shutdown()` callbacks
- **Configuration**: Declares schema via `configSchema()`, provides defaults via `defaultConfig()`
- **Self-description**: Metadata for tooling (description, DSL name, config key)

**Types of modules:**
- [RuleModule](#rulemodule) - Validators
- [FormatModule](#formatmodule) - Output formatters
- [OutputModule](#outputmodule) - Output delivery
- [PlatformServiceModule](#platformservicemodule) - Platform integrations (fetch MR, post comments, apply mutations)

**Why "Module" not "Plugin"?** The name distinguishes structured plugins (with lifecycle and type system) from other plugin types like JavaScript scripts (QuickJS), WebAssembly modules (Chicory/Chasm), and native shared libraries.

**NOT related to Gradle modules:** This term refers to runtime plugin components, not build system subprojects (`:plugins:modules:rules:title-format`).

**See also:** [Plugin](#plugin), `plugins/sdk/src/commonMain/kotlin/io/karandash/sdk/Module.kt`

---

### RuleModule

**Interface:** `io.karandash.sdk.RuleModule extends Module`

**Purpose:** Validates merge requests and returns validation findings.

**Config block:** `rules:`

**Example:**
```yaml
rules:
  title-format:
    pattern: "^(feat|fix|chore):"
    max-length: 120
```

**Built-in rule modules:**
- `title-format` - Title format validation
- `commit-messages` - Commit message validation
- `jira-ticket` - JIRA ticket reference validation
- `branch-naming` - Branch naming conventions
- `file-changes` - File change restrictions
- `codeowners` - CODEOWNERS coverage
- `approval` - Approval requirements
- `reviewer` - Reviewer assignment
- `cicd-status` - CI/CD pipeline status
- `conflict` - Merge conflict detection
- `draft` - Draft/WIP detection
- `labels` - Label requirements
- `size-limits` - MR size limits
- `security` - Security pattern detection
- `ai` - AI-powered validation rules
- `jira` - Jira ticket validation and enrichment
- `description-format` - MR description format validation
- `cross-entity-consistency` - Cross-entity consistency checks
- `context-tag` - Context tag validation
- `language-policy` - Language policy enforcement

---

### Policy

**Config block:** `policies:` (top-level array, parsed by `PolicyConfigParser` into `PolicyConfig`)

**Purpose:** Applies conditional changes to already-configured modules. Each policy carries a `when:`
condition expression evaluated against the merge request; when it matches, the policy can toggle the
modules listed in `modules:` (`enabled:`), deep-merge per-module config from `override:`, override
finding `severity:`, and gate the `autoApprove` / `autoFix` / `scripts` / `dangerfile` stages.
Policies never add rules - they modify rules that already exist in `rules:`.

There is no conditional *module*: policy evaluation happens in the config pipeline, not in a plugin.
A single rule can also carry an inline `when:` block for a condition that concerns only that rule.

**Example:**
```yaml
policies:
  - when: 'target_branch_matches("^main$")'
    modules: [approval]
    override:
      approval:
        minApprovals: 2
```

See [Conditional Rules (Policies)](../rules/conditional.md).

---

### FormatModule

**Interface:** `io.karandash.sdk.FormatModule extends Module`

**Purpose:** Transforms validation findings into a specific output format.

**Built-in format modules:**
- `ConsoleFormatModule` (`console`) - Human-readable ANSI terminal text
- `MarkdownFormatModule` (`markdown`) - Markdown tables and sections
- `JsonReportFormatModule` (`json`) - JSON document
- `SarifFormatModule` (`sarif`) - SARIF 2.1.0
- `HtmlFormatModule` (`html`) - Standalone HTML report
- `JunitFormatModule` (`junit`) - JUnit XML
- `RdjsonFormatModule` (`rdjson`) - Reviewdog rdjson
- `RdjsonlFormatModule` (`rdjsonl`) - Reviewdog rdjsonl (line-delimited, no envelope)
- `SlackFormatModule` (`slack`) - Slack Block Kit JSON

---

### OutputModule

**Interface:** `io.karandash.sdk.OutputModule extends Module`

**Purpose:** Delivers formatted output to a destination.

**Built-in output modules:**
- `StdoutOutputModule` - Standard output (default)
- `FilesystemOutputModule` - Local file (via `--output` flag)
- `PlatformOutputModule` - MR/PR comment via platform API
- `WebhookOutputModule` - HTTP POST to any endpoint
- `EmailOutputModule` - SMTP email delivery
- `SlackOutputModule` - Slack Incoming Webhook

---

### Reporter

**User-facing concept:** The combination of a `FormatModule` and an `OutputModule`. Configured via the `reporters:` block.

**Config block:** `reporters:`

**Example:**
```yaml
reporters:
  console: true       # ConsoleFormatModule + StdoutOutputModule
  markdown: true      # MarkdownFormatModule + PlatformOutputModule (auto on CI)
  sarif: true         # SarifFormatModule + StdoutOutputModule
```

**Built-in reporters and their composition:**

| Config key | FormatModule | OutputModule |
|-----------|-------------|-------------|
| `console` | `ConsoleFormatModule` | `StdoutOutputModule` |
| `markdown` | `MarkdownFormatModule` | `PlatformOutputModule` / `FilesystemOutputModule` |
| `json` | `JsonReportFormatModule` | `StdoutOutputModule` / `FilesystemOutputModule` |
| `sarif` | `SarifFormatModule` | `StdoutOutputModule` / `FilesystemOutputModule` |
| `html` | `HtmlFormatModule` | `FilesystemOutputModule` |
| `junit` | `JunitFormatModule` | `FilesystemOutputModule` |
| `reviewdog` | `RdjsonFormatModule` | `StdoutOutputModule` / `FilesystemOutputModule` |
| `slack` | `SlackFormatModule` | `SlackOutputModule` |
| `webhook` | `JsonReportFormatModule` | `WebhookOutputModule` |
| `email` | `HtmlFormatModule` | `EmailOutputModule` |

**See also:** [Reporters documentation](../reporters/index.md)

---

### PlatformServiceModule

**Interface:** `io.karandash.sdk.PlatformServiceModule extends Module`

**Purpose:** Unified platform integration: fetches merge request data, posts comments, and mutates MR state (labels, approvals, merge status).

**Config block:** `platforms:`

**Example:**
```yaml
platforms:
  gitlab:
    url: "https://gitlab.com"
    token: "${GITLAB_TOKEN}"
  github:
    token: "${GITHUB_TOKEN}"
```

**Built-in platform service modules:**
- `gitlab` - GitLab
- `github` - GitHub
- `bitbucket` - Bitbucket Cloud
- `azure-devops` - Azure DevOps
- `gerrit` - Gerrit Code Review
- `gitea` - Gitea
- `aws-codecommit` - AWS CodeCommit
- `jenkins` - Jenkins (CI environment only)

---

## Configuration Terminology

### Domain-Specific Blocks

**Definition:** Top-level YAML/JSON config blocks for built-in modules.

**Blocks:**
- `rules:` - Built-in [RuleModules](#rulemodule)
- `platforms:` - Built-in [PlatformServiceModules](#platformservicemodule)
- `reporters:` - Built-in [FormatModules](#formatmodule) and [OutputModules](#outputmodule)

**Why domain-specific?** This syntax is more intuitive than a generic `plugins:` block for common use cases.

**Example:**
```yaml
rules:
  title-format: {}

platforms:
  gitlab: {}

reporters:
  console: true
```

---

### External Plugin Block

**Block:** `plugins:`

**Purpose:** Configure **external third-party plugins only**.

**Example:**
```yaml
plugins:
  - id: "com.example.jira-integration"
    version: "1.0.0"
    config:
      jira-url: "https://jira.example.com"
  - id: "custom-validator"
```

**Note:** Built-in modules do NOT go in the `plugins:` block - they use domain-specific blocks.

---

## Comparison: Module vs Plugin

| Aspect | Module | Plugin |
|--------|--------|--------|
| **In code** | Specific interface | Umbrella interface |
| **Purpose** | Structured plugin with lifecycle | Any extension type |
| **Examples** | RuleModule, FormatModule, OutputModule | Module |
| **Config** | Domain blocks (`rules:`, `platforms:`) | `plugins:` block (external only) |
| **When to use** | Most plugin authors | New plugin types only |

**Key insight:** All Modules are Plugins, but not all Plugins are Modules.

---

## Related Documentation

- [Plugin SDK Reference](./api-reference.md#plugin-registration)
- [Module Interface](./api-reference.md#module)
- [Configuration Schema](./configuration-schema.md)
- [YAML Configuration Guide](../configuration/YAML_CONFIG.md)
- [Plugin Development Guide](../plugins/creating-plugins.md)
