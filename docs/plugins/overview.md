# Plugins Overview

Extend Karandash with custom validation logic using plugins.

## What are Plugins?

Plugins allow you to:

- Add custom validation rules beyond the 21 built-in rules
- Integrate with external services (JIRA, Asana, etc.)
- Implement organization-specific checks
- Enhance existing rules with custom logic

## Plugin Types

### 1. Validation Plugins (`RuleModule`)
Add custom validation rules that run during MR validation.

### 2. Integration Plugins (`PlatformServiceModule`, `EnrichmentProviderModule`)
Connect to external services for data enrichment or validation.

### 3. Format Plugins (`FormatModule`)
Transform validation findings into a specific output format (console text, JSON, SARIF, Markdown, HTML, JUnit XML, rdjson, Slack Block Kit). Paired with an output module to form a reporter.

### 4. Output Plugins (`OutputModule`)
Deliver formatted output to a destination (stdout, filesystem, platform MR comment, webhook, email, Slack). Paired with a format module to form a reporter.

### 5. Hook Plugins (`PipelineExtension`)
Run custom logic before/after validation.

## Plugin Architecture

Plugins are loaded dynamically at runtime and can be:

- Written in Kotlin or Java
- Distributed as JAR files
- Configured via the `.karandash/config.kts` file

## Example Plugin Usage

```kotlin
karandash {
    plugins {
        plugin("jira-integration") {
            version = "1.0.0"
            config {
                apiUrl = "https://jira.company.com"
                validateIssueStatus = true
            }
        }
    }
}
```

## Finding Model

When a rule plugin produces a violation, it returns `Finding` objects. The `Finding` model supports `suggestion` and `explanation` fields that improve the developer experience:

| Field | Type | Description |
|-------|------|-------------|
| `severity` | `Severity` | `ERROR`, `WARNING`, or `INFO` |
| `message` | `String` | Human-readable finding message |
| `rule` | `String` | Rule ID that produced the finding |
| `location` | `Location?` | Optional file/line location |
| `suggestion` | `String?` | Short actionable hint for the MR author |
| `explanation` | `String?` | Longer explanation shown with `--explain` |
| `metadata` | `Map<String, String>` | Arbitrary key-value metadata |

Reporters display `suggestion` inline (e.g., in MR comments and console output) and `explanation` in explain mode (`--explain` or `explain: true`).

## Getting Started

- **[Step-by-Step Tutorial](tutorial.md)** - Build a complete plugin in 30 minutes with the CommitMessageLengthValidator example
- **[Creating Plugins](creating-plugins.md)** - Reference guide for plugin development
- **[JavaScript Plugin Guide](js-plugin-guide.md)** - Develop plugins in JavaScript
- **[Native Plugin Guide](native-plugin-guide.md)** - High-performance plugins in C/C++/Rust
