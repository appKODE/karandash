# Labels

## Overview

The Labels validator enforces labeling requirements for merge requests. It can require specific labels, forbid certain labels, and implement conditional label requirements based on other labels.

## Configuration

### Optional Properties

- `requiredLabels`: Labels that must be present (list of strings, default: `[]`)
- `forbiddenLabels`: Labels that must not be present (list of strings, default: `[]`)
- Entries in `requiredLabels`/`forbiddenLabels` that contain `*` are globs: `type::*` matches `type::bug`.
  A required glob is met by any matching label; a forbidden glob reports each matching label.
- `conditionalLabels`: Conditional label requirements (list of `ConditionalLabel`, default: `[]`)

### ConditionalLabel Properties

- `condition`: Condition to evaluate (`LabelCondition`)
  - `hasLabel`: Single label that must be present (string)
  - `hasAnyLabel`: Any of these labels must be present (list of strings)
  - `hasAllLabels`: All of these labels must be present (list of strings)
- `requiredLabels`: Labels required when condition matches (list of strings)
- `forbiddenLabels`: Labels forbidden when condition matches (list of strings)

## Examples

### Basic Usage - Required Labels

```kotlin
karandash {
    rules {
        labels {
            requiredLabels = listOf("type:feature", "reviewed")
        }
    }
}
```

### Forbidden Labels

```kotlin
karandash {
    rules {
        labels {
            forbiddenLabels = listOf("do-not-merge", "blocked")
        }
    }
}
```

### Combined Requirements

```kotlin
karandash {
    rules {
        labels {
            requiredLabels = listOf("type:feature", "type:bug", "type:docs")
            forbiddenLabels = listOf("wip", "blocked")
        }
    }
}
```

### Conditional Labels - Single Label Condition

```kotlin
karandash {
    rules {
        labels {
            conditionalLabels = listOf(
                ConditionalLabel(
                    condition = LabelCondition(hasLabel = "breaking-change"),
                    requiredLabels = listOf("major-version", "migration-guide")
                )
            )
        }
    }
}
```

### Conditional Labels - Any Label Condition

```kotlin
karandash {
    rules {
        labels {
            conditionalLabels = listOf(
                ConditionalLabel(
                    condition = LabelCondition(
                        hasAnyLabel = listOf("type:feature", "type:enhancement")
                    ),
                    requiredLabels = listOf("changelog:added")
                )
            )
        }
    }
}
```

### Conditional Labels - All Labels Condition

```kotlin
karandash {
    rules {
        labels {
            conditionalLabels = listOf(
                ConditionalLabel(
                    condition = LabelCondition(
                        hasAllLabels = listOf("security", "critical")
                    ),
                    requiredLabels = listOf("security-review", "pen-test"),
                    forbiddenLabels = listOf("skip-review")
                )
            )
        }
    }
}
```

### Complex Label Policy

```kotlin
karandash {
    rules {
        labels {
            requiredLabels = listOf("type:*", "priority:*")
            forbiddenLabels = listOf("do-not-merge")

            conditionalLabels = listOf(
                ConditionalLabel(
                    condition = LabelCondition(hasLabel = "database"),
                    requiredLabels = listOf("dba-review", "migration")
                ),
                ConditionalLabel(
                    condition = LabelCondition(hasLabel = "breaking-change"),
                    requiredLabels = listOf("major-version", "docs:updated")
                ),
                ConditionalLabel(
                    condition = LabelCondition(hasAnyLabel = listOf("security", "auth")),
                    requiredLabels = listOf("security-review")
                )
            )
        }
    }
}
```

## Error Messages

- **Missing required label**: `Missing required label: '{label}'`
- **Forbidden label present**: `Forbidden label present: '{label}'`
- **Conditional requirement**: `Conditional requirement not met: label '{label}' is required when {condition}`
- **Conditional violation**: `Conditional requirement not met: label '{label}' is forbidden when {condition}`

## Best Practices

### When to Use This Rule

- Categorize merge requests for tracking
- Enforce workflow states
- Require documentation of changes
- Implement release note automation
- Track breaking changes and migrations

### Label Naming Conventions

**Hierarchical (Recommended):**
```kotlin
requiredLabels = listOf(
    "type:feature",   // or type:bug, type:docs
    "priority:high",  // or priority:medium, priority:low
    "status:ready"    // or status:wip, status:blocked
)
```

**Categorical:**
```kotlin
requiredLabels = listOf(
    "feature",        // One of: feature, bug, docs, chore
    "reviewed",       // Status labels
    "v2.0"           // Version labels
)
```

**Semantic:**
```kotlin
requiredLabels = listOf(
    "breaking-change",  // Impact labels
    "security",         // Domain labels
    "hotfix"           // Urgency labels
)
```

### Common Label Patterns

**Type Classification:**
```kotlin
labels {
    requiredLabels = listOf("type:*")  // Must have one type label
    // Suggested: type:feature, type:bug, type:docs, type:refactor
}
```

**Priority Levels:**
```kotlin
labels {
    requiredLabels = listOf("priority:*")
    // Suggested: priority:critical, priority:high, priority:medium, priority:low
}
```

**Release Tracking:**
```kotlin
labels {
    requiredLabels = listOf("target:*")
    // Suggested: target:v2.0, target:v2.1, target:next
}
```

**Status Indicators:**
```kotlin
labels {
    forbiddenLabels = listOf("wip", "blocked", "on-hold")
}
```

### Tips

1. Use hierarchical naming (`type:feature`) for clarity
2. Document required labels in team guidelines
3. Use conditional labels for advanced workflows
4. Combine with [File Changes](file-changes.md) for file-based labeling
5. Automate label assignment where possible

## Conditional Label Examples

### Breaking Changes

```kotlin
conditionalLabels = listOf(
    ConditionalLabel(
        condition = LabelCondition(hasLabel = "breaking-change"),
        requiredLabels = listOf(
            "major-version",
            "migration-guide",
            "changelog:breaking"
        )
    )
)
```

### Security Changes

```kotlin
conditionalLabels = listOf(
    ConditionalLabel(
        condition = LabelCondition(
            hasAnyLabel = listOf("security", "auth", "crypto")
        ),
        requiredLabels = listOf("security-review", "penetration-test")
    )
)
```

### Database Changes

```kotlin
conditionalLabels = listOf(
    ConditionalLabel(
        condition = LabelCondition(hasLabel = "database"),
        requiredLabels = listOf("dba-review", "migration-tested"),
        forbiddenLabels = listOf("skip-review")
    )
)
```

### Documentation Requirements

```kotlin
conditionalLabels = listOf(
    ConditionalLabel(
        condition = LabelCondition(
            hasAnyLabel = listOf("type:feature", "breaking-change")
        ),
        requiredLabels = listOf("docs:updated")
    )
)
```

## Combining with Other Rules

### With File Changes

```kotlin
rules {
    fileChanges {
        patterns = listOf(
            FilePatternRule(
                pattern = "**/*.sql",
                requireLabels = listOf("database")
            )
        )
    }

    labels {
        conditionalLabels = listOf(
            ConditionalLabel(
                condition = LabelCondition(hasLabel = "database"),
                requiredLabels = listOf("dba-review")
            )
        )
    }
}
```

### With Approvals

```kotlin
rules {
    labels {
        requiredLabels = listOf("priority:*")

        conditionalLabels = listOf(
            ConditionalLabel(
                condition = LabelCondition(hasLabel = "priority:critical"),
                requiredLabels = listOf("expedited-review")
            )
        )
    }

    approval {
        minApprovals = 3  // More approvals for critical changes
    }
}
```

## Automation Integration

Labels drive automation workflows:

**Release Notes Generation:**
```kotlin
labels {
    requiredLabels = listOf("changelog:*")
    // Suggested: changelog:added, changelog:changed, changelog:fixed
}
```

**Issue Tracking:**
```kotlin
labels {
    requiredLabels = listOf("jira:*")
    // Auto-link to JIRA: jira:PROJ-123
}
```

**Deployment Tracking:**
```kotlin
labels {
    conditionalLabels = listOf(
        ConditionalLabel(
            condition = LabelCondition(hasLabel = "hotfix"),
            requiredLabels = listOf("deploy:production", "rollback-plan")
        )
    )
}
```

## Label Lifecycle

**Draft Stage:**
```
- wip
```

**Review Stage:**
```
- wip (removed)
+ needs-review
+ type:feature
```

**Approved Stage:**
```
- needs-review (removed)
+ approved
+ ready-to-merge
```

## Platform-Specific Features

### GitLab

- Scoped labels (`type::feature`)
- Label priority
- Label descriptions
- Label templates

### GitHub

- Multiple labels per category
- Label colors and descriptions
- Team-based label permissions

### Bitbucket

- Custom labels per repository
- Label searching and filtering

## Common Label Sets

**Minimal:**
```kotlin
requiredLabels = listOf("type:*", "reviewed")
```

**Standard:**
```kotlin
requiredLabels = listOf(
    "type:*",        // type:feature, type:bug, type:docs
    "priority:*",    // priority:high, priority:medium, priority:low
    "status:ready"
)
```

**Comprehensive:**
```kotlin
requiredLabels = listOf(
    "type:*",
    "priority:*",
    "target:*",      // Release version
    "changelog:*"    // Changelog category
)

forbiddenLabels = listOf(
    "do-not-merge",
    "blocked",
    "wip"
)
```

## Related Rules

- [File Changes](file-changes.md) - File-based label requirements
- [JIRA Ticket](jira-ticket.md) - Issue tracking integration
- [Title Format](title-format.md) - Complement with title validation
- [Conditional Rules](conditional.md) - Advanced conditional logic
