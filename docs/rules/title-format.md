# Title Format

## Overview

The Title Format validator enforces standards for merge request titles, including pattern matching and length constraints. This keeps MR titles consistent and readable across your project.

## Configuration

### Required Properties

None.

### Optional Properties

- `pattern`: Regex pattern that the title must match (string, default: `null`)
- `minLength`: Minimum title length in characters (integer, default: `null`)
- `maxLength`: Maximum title length in characters (integer, default: `null`)
  - Returns WARNING severity when exceeded
- `requireTicket`: Require a JIRA-style ticket reference in the title (boolean, default: `false`)
- `type`: Structured constraint on the type token - `TitleTypeConfig` with `allowed` list (default: `null`)
- `scope`: Structured constraint on the scope token - `TitleScopeConfig` with `required` boolean (default: `null`)
- `subject`: Structured constraint on the subject token - `TitleSubjectConfig` with `minLength` and `noPeriod` (default: `null`)

## Examples

### Basic Usage - Conventional Commits Style

```kotlin
karandash {
    rules {
        titleFormat {
            pattern = "^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\\(.+\\))?: .+"
            minLength = 10
            maxLength = 72
            suggestion = "Use a conventional commit prefix, e.g. 'feat: add login page' or 'fix: handle null response'"
            explanation = "Conventional commit prefixes enable automated changelogs and semantic versioning"
        }
    }
}
```


### Custom Format

```kotlin
karandash {
    rules {
        titleFormat {
            pattern = "^(Feature|Bugfix|Hotfix): [A-Z].+"
            minLength = 20
            maxLength = 100
        }
    }
}
```

### Lenient Pattern with Length Limits

```kotlin
karandash {
    rules {
        titleFormat {
            pattern = "^[A-Z].+"  // Must start with capital letter
            minLength = 10
            maxLength = 80
        }
    }
}
```

## Error Messages

- **Pattern mismatch**: `MR title does not match required pattern: {pattern}`
- **Too short**: `MR title too short (min: {min}, actual: {length})`
- **Too long**: `MR title too long (max: {max}, actual: {length})` (WARNING severity)
- **Invalid regex**: `Invalid regex pattern in title format configuration: '{pattern}' - {error}`

## Best Practices

### When to Use This Rule

- Enforce consistent title formatting across all MRs
- Require commit message conventions (conventional commits)
- Make sure titles are descriptive enough (min length)
- Prevent overly long titles that don't render well (max length)

### Recommended Patterns

**Conventional Commits (Strict):**
```kotlin
pattern = "^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\\([a-z0-9-]+\\))?: [a-z].+"
```

**Conventional Commits (Lenient):**
```kotlin
pattern = "^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert): .+"
```

**Ticket-First Format:**
```kotlin
pattern = "^[A-Z]+-\\d+: .+"
// Matches: PROJ-123: Add user authentication
```

**Category-Based:**
```kotlin
pattern = "^(Feature|Bug|Docs|Chore): [A-Z].+"
// Matches: Feature: User profile page
```

### Length Guidelines

```kotlin
minLength = 10   // Prevent vague titles like "fix bug"
maxLength = 72   // Git recommendation for commit subjects
```

### Tips

1. Test your regex pattern thoroughly before deploying
2. Use `maxLength` as WARNING to guide without blocking
3. Combine with [JIRA Ticket](jira-ticket.md) rule for fuller validation
4. Document your pattern in team guidelines
5. Consider using `(?i)` flag for case-insensitive matching if needed

## Common Patterns Reference

```kotlin
// Conventional commits with scope
"^(feat|fix|docs|style|refactor|perf|test|build|ci|chore)(\\([a-z0-9-]+\\))?!?: .+"

// Emoji prefix support
"^(:\\w+: )?(feat|fix|docs): .+"

// Ticket reference required
"^[A-Z]+-\\d+[: ].+"

// Multiple formats allowed
"^((feat|fix|docs): .+|\\[WIP\\] .+|Merge .+)"

// Capital letter start, no special chars
"^[A-Z][a-zA-Z0-9 ]+$"
```

## Integration with Conventional Commits

For full conventional commits compliance:

```kotlin
karandash {
    rules {
        titleFormat {
            pattern = "^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\\(.+\\))?!?: .+"
            minLength = 15
            maxLength = 72
        }

        commitMessages {
            format = "conventional"
            maxLength = 72
        }
    }
}
```

## Related Rules

- [Commit Messages](commit-messages.md) - Validate individual commit messages
- [JIRA Ticket](jira-ticket.md) - Validate ticket references in titles
- [Draft Detection](draft-detection.md) - Detect WIP/Draft indicators
