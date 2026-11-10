# Rules Reference

Karandash provides 21 built-in validation rules for merge requests. Each rule is highly configurable and can be enabled/disabled independently.

## Available Rules

### Title & Description
- [**Title Format**](title-format.md) - Validate MR title format, length, and patterns
- [**Commit Messages**](commit-messages.md) - Validate commit message format (conventional commits)
- [**JIRA Ticket**](jira-ticket.md) - Require and validate JIRA ticket references
- [**Description Format**](description-format.md) - Validate MR description structure and content
- [**Context Tags**](context-tags.md) - Validate context tag conventions

### Branch & Labels
- [**Branch Naming**](branch-naming.md) - Enforce branch naming conventions
- [**Labels**](labels.md) - Require or forbid specific labels on MRs

### Files & Changes
- [**File Changes**](file-changes.md) - Monitor specific file patterns and enforce requirements
- [**Size Limits**](size-limits.md) - Limit the size of changes (files, lines, commits)
- [**Security**](security.md) - Scan for secrets and sensitive file changes

### Approvals & Reviewers
- [**Approvals**](approvals.md) - Require minimum approvals and specific approvers
- [**Reviewers**](reviewers.md) - Enforce reviewer requirements
- [**Codeowners**](codeowners.md) - Validate CODEOWNERS compliance

### CI/CD & Status
- [**CI/CD Status**](cicd-status.md) - Require passing CI/CD pipelines
- [**Conflict Detection**](conflict-detection.md) - Block MRs with merge conflicts
- [**Draft Detection**](draft-detection.md) - Block or warn on draft/WIP MRs

### Integration & Intelligence
- [**AI-powered Rules**](ai-rules.md) - AI review via Anthropic, OpenAI, Ollama
- [**JIRA Validation**](jira-validation.md) - JIRA ticket validation and enrichment (beyond basic reference checks)
- [**Cross-entity Consistency**](cross-entity-consistency.md) - Validate consistency across related entities
- [**Language Policy**](language-policy.md) - Enforce language policy rules

### Advanced
- [**Conditional Rules**](conditional.md) - Apply rules conditionally based on MR properties

## Opt-in rules

`cicdStatus`, `contextTag`, `crossEntity` and `languagePolicy` do nothing until you configure them: they are not part of the default rule set, so a config that never mentions them never runs them. Add the rule's block (see its page above) to turn it on. The AI rules are opt-in too; see [AI-powered Rules](ai-rules.md).

## Suppressing findings

- **In the MR description:** a line `karandash-disable <rule>` drops every finding of that rule for this MR, for example `karandash-disable sizeLimits` (`size-limits` and `size_limits` work too) to override the size limits for one MR. Several rules can be listed with commas (`karandash-disable sizeLimits, labels`). Text after the first rule id on a line is ignored. Anyone who can edit the description can use it, so do not rely on it for rules that must hold.
- **Baseline:** `karandash validate --baseline findings-baseline.json` ignores findings whose fingerprint is in the file. `--baseline findings-baseline.json --update-baseline` rewrites the file from the current findings. The file can also be set once with `baseline: findings-baseline.json` in the config (the flag wins); `--update-baseline` without either exits 2. A baseline file that is not valid JSON of the expected shape is a config error (exit 2). The JSON report's `baselineComparison` then counts new, resolved and unchanged findings.

All rules are configured in the `karandash.kts` configuration file using Kotlin DSL:

```kotlin
karandash {
    rules {
        titleFormat {
            pattern = "^(feat|fix|docs|chore): .+"
            minLength = 10
            maxLength = 72
        }

        approval {
            minApprovals = 2
            requiredApprovers = listOf("tech-lead", "architect")
        }

        // ... more rules
    }
}
```

## Common Properties

Most rules share these common configuration patterns:

- **Pattern Matching**: Rules that validate text use regex patterns
- **List-based Requirements**: Rules can require or forbid specific values
- **Conditional Logic**: Many rules support conditional requirements

### Universal Rule Options

Every rule supports two optional fields that improve the developer experience when violations are found:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `suggestion` | `String` | `null` | A short, actionable hint shown to the MR author when this rule fails. Displayed in console output, MR comments, and IDE integrations. |
| `explanation` | `String` | `null` | A longer explanation of *why* the rule exists. Shown with `--explain` or in verbose reporters. |

**Example (Kotlin DSL):**

```kotlin
rules {
    titleFormat {
        pattern = "^(feat|fix|docs):"
        suggestion = "Use 'feat: ...', 'fix: ...', or 'docs: ...' as your title prefix"
        explanation = "Conventional commit prefixes enable automated changelogs and semantic versioning"
    }
}
```

**Example (YAML):**

```yaml
rules:
  title-format:
    pattern: "^(feat|fix|docs):"
    suggestion: "Use 'feat: ...', 'fix: ...', or 'docs: ...' as your title prefix"
    explanation: "Conventional commit prefixes enable automated changelogs and semantic versioning"
```

## Rule Execution

Rules are executed in the following order:

1. Basic validation (title, description, branch naming)
2. Content validation (commits, file changes)
3. People validation (approvals, reviewers, codeowners)
4. Status validation (CI/CD, conflicts, draft)
5. Security validation (secrets, sensitive files)
6. Conditional rules (based on MR properties)

## Severity Levels

Findings from rules can have different severity levels:

- **ERROR**: Blocks merge, must be resolved
- **WARNING**: Advisory, doesn't block merge
- **INFO**: Informational, for awareness only

## Next Steps

- Browse individual rule documentation for detailed configuration options
- See [Quick Start](../getting-started/quick-start.md) for configuration examples
- Learn about [Creating Plugins](../plugins/creating-plugins.md) for custom validation logic
