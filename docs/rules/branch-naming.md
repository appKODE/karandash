# Branch Naming

## Overview

The Branch Naming validator enforces naming conventions for source and target branches in merge requests. It uses regex patterns to validate branch names against your team's standards.

## Configuration

### Required Properties

None - at least one pattern must be configured for the rule to have effect.

### Optional Properties

- `pattern`: Single regex pattern to match branch names (string, default: `null`)
- `allowedPatterns`: List of regex patterns, any of which can match (list of strings, default: `[]`)
- `requireSourceMatch`: Whether to validate the source branch (boolean, default: `true`)
- `requireTargetMatch`: Whether to validate the target branch (boolean, default: `false`)
- `prefixOneOf`: List of allowed branch prefixes, alternative to `pattern` (list of strings, default: `null`)
- `case`: Enforce branch name casing - `CaseType` enum value (string, default: `null`)
- `maxLength`: Max branch name length (integer, default: `null`)

## Examples

### Basic Usage - Single Pattern

```kotlin
karandash {
    rules {
        branchNaming {
            pattern = "^(feature|bugfix|hotfix)/.+"
            requireSourceMatch = true
            requireTargetMatch = false
            suggestion = "Name your branch with a prefix: 'feature/my-feature', 'bugfix/fix-login', or 'hotfix/critical-fix'"
            explanation = "Consistent branch naming makes it easier to identify work in progress and automate CI targeting"
        }
    }
}
```

### Multiple Allowed Patterns

```kotlin
karandash {
    rules {
        branchNaming {
            allowedPatterns = listOf(
                "^feature/[A-Z]+-\\d+-.+",  // feature/PROJ-123-description
                "^bugfix/[A-Z]+-\\d+-.+",   // bugfix/PROJ-456-fix
                "^hotfix/\\d+\\.\\d+\\.\\d+", // hotfix/1.2.3
                "^release/v\\d+\\.\\d+"      // release/v2.0
            )
        }
    }
}
```

### Validate Both Source and Target

```kotlin
karandash {
    rules {
        branchNaming {
            allowedPatterns = listOf(
                "^feature/.+",
                "^main$",
                "^develop$"
            )
            requireSourceMatch = true
            requireTargetMatch = true  // Ensure target is main or develop
        }
    }
}
```

### Jira Ticket Required in Branch Name

```kotlin
karandash {
    rules {
        branchNaming {
            pattern = "^(feature|bugfix)/[A-Z]+-\\d+-.+"
            requireSourceMatch = true
        }
    }
}
```

## Error Messages

- **Source branch validation failure**: `Source branch '{branch}' does not match any allowed pattern.`
- **Target branch validation failure**: `Target branch '{branch}' does not match any allowed pattern.`
- **Missing prefix** (structured config): `Source branch '{branch}' has none of the allowed prefixes.`

The fix goes in the suggestion, built from the closest allowed pattern: when inserting the missing literal
parts makes the branch match, the suggestion names the corrected branch (``Change the branch name to `bugfix/login`.``),
otherwise it gives an example of the format. Branches are never renamed automatically.
- **Invalid regex pattern**: `Invalid regex pattern in configuration: '{pattern}' - {error}`

## Best Practices

### When to Use This Rule

- Enforce consistent branch naming across your team
- Require ticket references in branch names for traceability
- Prevent merges to protected branches with invalid naming
- Distinguish between different types of branches (feature, bugfix, etc.)

### Recommended Patterns

**GitFlow Style:**
```kotlin
allowedPatterns = listOf(
    "^feature/.+",
    "^bugfix/.+",
    "^hotfix/.+",
    "^release/.+",
    "^main$",
    "^develop$"
)
```

**GitHub Flow Style:**
```kotlin
allowedPatterns = listOf(
    "^[a-z0-9-]+$",  // Simple kebab-case
    "^main$"
)
```

**With Ticket Requirements:**
```kotlin
pattern = "^(feature|bugfix|hotfix)/[A-Z]+-\\d+(-[a-z0-9-]+)?$"
// Matches: feature/PROJ-123, bugfix/TEAM-456-fix-login
```

### Tips

1. Use `allowedPatterns` for flexibility with multiple branch types
2. Keep `requireTargetMatch = false` unless you need strict target validation
3. Test regex patterns thoroughly - invalid patterns will generate errors
4. Consider case sensitivity in your patterns (`(?i)` for case-insensitive)
5. Use anchors (`^` and `$`) to match the entire branch name

## Common Regex Patterns

```kotlin
// Lowercase with hyphens
"^[a-z0-9-]+$"

// Prefix with ticket number
"^(feature|bugfix)/[A-Z]+-\\d+-.+$"

// Username prefix (personal branches)
"^[a-z]+/.+$"

// Date-based branches
"^release/\\d{4}-\\d{2}-\\d{2}$"

// Semantic versioning
"^(release|hotfix)/v?\\d+\\.\\d+(\\.\\d+)?$"
```

## Related Rules

- [JIRA Ticket](jira-ticket.md) - Validate ticket references in titles
- [Conditional Rules](conditional.md) - Apply different rules based on branch patterns
