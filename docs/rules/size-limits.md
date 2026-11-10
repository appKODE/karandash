# Size Limits

## Overview

The Size Limits validator enforces constraints on merge request size to keep changes manageable and reviewable. It can limit the number of files changed, lines changed, and commits.

## Configuration

### Optional Properties

- `maxFiles`: Maximum number of files that can be changed (integer, default: `null`)
- `maxLines`: Maximum total lines changed (additions + deletions) (integer, default: `null`)
- `maxCommits`: Maximum number of commits in the MR (integer, default: `null`)
- `excludeRenames`: Exclude renamed files from file and line counts (boolean, default: `false`)
- `excludeDeleted`: Exclude deleted files from file and line counts (boolean, default: `false`)
- `severities`: Controls violation severity and early-warning threshold (`SizeLimitsSeverityConfig`, default: see sub-properties below); a scalar `severity:` sets the severity of every finding of the rule
  - `onViolation`: Severity when a limit is reached: `"error"` or `"warning"` (string, default: `"error"`)
  - `warnThreshold`: Optional early-warning threshold as a percentage of the limit. When set, a warning finding is emitted if usage exceeds `limit * warnThreshold` but is below the hard limit. Must be between 50% and 99% if provided (percentage, default: `null`)

## Examples

### Basic Usage - File Limit

```kotlin
karandash {
    rules {
        sizeLimits {
            maxFiles = 20
        }
    }
}
```

### Line Limit

```kotlin
karandash {
    rules {
        sizeLimits {
            maxLines = 500
        }
    }
}
```

### Commit Limit

```kotlin
karandash {
    rules {
        sizeLimits {
            maxCommits = 10
        }
    }
}
```

### Combined Limits

```kotlin
karandash {
    rules {
        sizeLimits {
            maxFiles = 30
            maxLines = 1000
            maxCommits = 15
        }
    }
}
```

### Strict Limits for Small Changes

```kotlin
karandash {
    rules {
        sizeLimits {
            maxFiles = 10
            maxLines = 300
            maxCommits = 5
        }
    }
}
```

## Error Messages

- **Too many files**: `Too many files changed: {count} (max: {max})`
- **Too many lines**: `Too many lines changed: {count} (max: {max})`
- **Too many commits**: `Too many commits: {count} (max: {max})`

The suggestion carries the numbers: `Split the MR: {count} {metric} is {count - max} over the limit of {max}.`; an
"Approaching" warning suggests `Keep the MR at or under {max} {metric}: {max - count} more allowed, or split it.`

## Best Practices

### When to Use This Rule

- Encourage focused, reviewable changes
- Prevent massive "catch-all" merge requests
- Improve code review quality
- Reduce review fatigue
- Enforce incremental development

### Recommended Limits

**Small Team / High Velocity:**
```kotlin
sizeLimits {
    maxFiles = 15
    maxLines = 400
    maxCommits = 8
}
```

**Medium Team / Standard Projects:**
```kotlin
sizeLimits {
    maxFiles = 25
    maxLines = 800
    maxCommits = 12
}
```

**Large Team / Complex Projects:**
```kotlin
sizeLimits {
    maxFiles = 40
    maxLines = 1500
    maxCommits = 20
}
```

**Strict Policy (Recommended):**
```kotlin
sizeLimits {
    maxFiles = 20
    maxLines = 500
    maxCommits = 10
}
```

### Guidelines by Change Type

**Bug Fixes:**
```kotlin
sizeLimits {
    maxFiles = 5
    maxLines = 200
    maxCommits = 3
}
```

**Features:**
```kotlin
sizeLimits {
    maxFiles = 30
    maxLines = 1000
    maxCommits = 15
}
```

**Refactoring:**
```kotlin
sizeLimits {
    maxFiles = 50  // May touch many files
    maxLines = 2000  // But mostly moves, not new logic
    maxCommits = 10
}
```

### Tips

1. Start with generous limits, tighten based on team feedback
2. Document why limits exist (review quality, maintainability)
3. Provide guidance on splitting large changes
4. Use exceptions for special cases (generated code, migrations)
5. Monitor actual MR sizes to calibrate limits

## Understanding Line Count

`maxLines` counts **additions + deletions**:

```
+ 100 lines added
- 50 lines deleted
= 150 total lines changed
```

This measures the **scope of review** rather than net change.

## When Limits Are Exceeded

### Strategies for Developers

**Split by Component:**
```
MR 1: Backend API changes
MR 2: Frontend UI changes
MR 3: Tests and documentation
```

**Split by Feature:**
```
MR 1: Core feature implementation
MR 2: Edge case handling
MR 3: Performance optimizations
```

**Split by Dependency:**
```
MR 1: Database schema changes
MR 2: Backend service updates
MR 3: Frontend integration
```

### Guidance for Teams

Add this to your CONTRIBUTING.md:

```markdown
## Merge Request Size Limits

- Maximum 20 files changed
- Maximum 500 lines changed
- Maximum 10 commits

If your change exceeds these limits:
1. Split into logical, independent MRs
2. Create a tracking issue linking all related MRs
3. Submit MRs in dependency order
4. Request limit exception from tech lead (rare cases)
```

## Combining with Other Rules

### With Conditional Rules

```kotlin
policies {
    largeRefactor {
        when = "label_exists(\"large-refactor\")"
        modules = listOf("sizeLimits")
        override {
            sizeLimits {
                maxFiles = 100 // Relaxed for refactoring
                maxLines = 3000
            }
        }
    }
}
```

### With Labels

```kotlin
rules {
    sizeLimits {
        maxFiles = 20
        maxLines = 500
    }

    labels {
        conditionalLabels = listOf(
            ConditionalLabel(
                condition = LabelCondition(hasLabel = "large-change"),
                requiredLabels = listOf("split-approved", "tech-lead-review")
            )
        )
    }
}
```

## Exceptions and Special Cases

### Generated Code

```kotlin
// Document in guidelines:
// "Generated code (e.g., proto files, migrations) may exceed limits.
//  Add 'generated-code' label and get approval from tech lead."
```

### Database Migrations

```kotlin
// Large data migrations may need exceptions
// Use conditional rules or manual approval process
```

### Dependency Updates

```kotlin
// Package-lock.json changes can be large
// Consider excluding from line count or using separate validation
```

### Initial Implementation

```kotlin
// First implementation of major feature may be larger
// Use 'initial-implementation' label and require extra review
```

## Benefits of Size Limits

**For Reviewers:**
- Manageable review scope
- Better focus and attention
- Faster review turnaround
- Lower cognitive load

**For Authors:**
- Clearer change boundaries
- Easier to describe changes
- Faster feedback cycles
- Simpler conflict resolution

**For Teams:**
- Higher review quality
- More frequent integration
- Reduced merge conflicts
- Better commit history

## Monitoring and Metrics

Track these metrics to calibrate limits:

- Average MR size (files, lines, commits)
- Review time vs. MR size correlation
- Defect rate vs. MR size correlation
- MRs exceeding limits (frequency)

## What Counts as a File Change?

- Added files: Count toward limit
- Modified files: Count toward limit
- Deleted files: Count toward limit
- Renamed files: Typically count as 2 (delete + add)

## Commit Count Considerations

**High commit count may indicate:**
- Work-in-progress commits not squashed
- Multiple logical changes in one MR
- Long-running feature branch
- Incremental development (good!)

**Encourage:**
```
- Squash WIP commits before review
- Keep commits atomic and meaningful
- Follow conventional commit format
```

## Common Threshold Values

**maxFiles:**
```
5   - Very strict (bug fixes only)
10  - Strict (focused changes)
20  - Moderate (recommended)
30  - Lenient (features)
50+ - Very lenient (refactors, migrations)
```

**maxLines:**
```
200   - Very strict
500   - Strict (recommended)
1000  - Moderate
2000  - Lenient
5000+ - Very lenient (special cases)
```

**maxCommits:**
```
3    - Very strict (encourage squashing)
5    - Strict
10   - Moderate (recommended)
20   - Lenient
50+  - Very lenient (feature branches)
```

## Related Rules

- [Commit Messages](commit-messages.md) - Validate commit quality
- [Labels](labels.md) - Label large changes appropriately
- [Conditional Rules](conditional.md) - Context-specific limits
- [File Changes](file-changes.md) - Monitor specific file patterns
