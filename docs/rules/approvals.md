# Approvals

## Overview

The Approvals validator enforces approval requirements for merge requests, including minimum approval counts, required approvers, and validation that no changes were made after approval.

**Rule ID:** `approval` (singular - this is the config key and DSL block name)
**Tier:** Extended (`plugins/plugins-extended/rules/approval`)

## Configuration

### Optional Properties

- `minApprovals`: Minimum number of approvals required (integer, default: `1`)
- `requiredApprovers`: List of usernames that must approve (list of strings, default: `[]`)
- `noChangesSinceApproval`: Block if changes were made after approval (boolean, default: `false`). Maps to `freshApprovals` internally; both have the same effect.
- `noVeto`: Require that no reviewer has submitted a "changes requested" review (boolean, default: `false`)
- `noSelfApproval`: Prevent the MR author from counting as an approver (boolean, default: `false`)
- `freshApprovals`: Require that approvals were given after the latest push (boolean, default: `false`)
- `methods`: Which approval signals count (list of `CODE_REVIEW`, `COMMENT_KEYWORD`, `EMOJI_REACTION`, default: `[CODE_REVIEW]`). Listing `COMMENT_KEYWORD` or `EMOJI_REACTION` makes the run fetch MR comments or reactions from the platform.
- `commentKeywords`: Comment texts that count as an approval when `COMMENT_KEYWORD` is listed (list of strings, default: `[LGTM, approved]`, case-insensitive, whole comment)
- `approvalEmojis`: Reactions that count as an approval when `EMOJI_REACTION` is listed (list of strings, default: `[thumbsup, +1]`)
- `policy`: Composable AND/OR/NOT approval policy tree for advanced scenarios. When set, takes precedence over all flat fields above (`ApprovalPolicy`, default: `null`). Supports the following variants:
  - `minApprovals`: `{ count: N }` - require at least N approvals
  - `requiredReviewers`: `{ usernames: [...] }` - every listed user must approve
  - `groupApproval`: `{ group: name, members: [...], minCount: N }` - at least N approvals from the named group
  - `all`: `{ policies: [...] }` - all sub-policies must pass (AND)
  - `any`: `{ policies: [...] }` - at least one sub-policy must pass (OR)
  - `not`: `{ policy: ... }` - negate a policy
  - `noVeto`: `{}` - no CHANGES_REQUESTED reviews
  - `noSelfApproval`: `{}` - author cannot self-approve
  - `freshApprovals`: `{ count: N }` - N approvals must post-date the latest commit

## Examples

### Basic Usage - Minimum Approvals

```kotlin
rules {
    approval {
        minApprovals = 2
    }
}
```

### Required Approvers

```kotlin
rules {
    approval {
        minApprovals = 1
        requiredApprovers = listOf("tech-lead", "architect")
    }
}
```

### No Changes After Approval

```kotlin
rules {
    approval {
        minApprovals = 2
        noChangesSinceApproval = true
    }
}
```

### Strict Approval Policy

```kotlin
rules {
    approval {
        minApprovals = 3
        requiredApprovers = listOf("senior-dev", "tech-lead", "security-officer")
        noChangesSinceApproval = true
    }
}
```

## Error Messages

- **Insufficient approvals**: `Insufficient approvals: {current} of {required} required`
- **Missing required approvers**: `Missing required approvers: {usernames}`
- **Changes after approval**: `Changes may have been made after approval - verification recommended` (WARNING severity)

## Best Practices

### When to Use This Rule

- Enforce code review standards
- Require sign-off from specific team members
- Make sure architectural oversight for significant changes
- Implement four-eyes principle for security
- Prevent rushed merges without proper review

### Approval Requirements by Context

**Small Teams:**
```kotlin
approval {
    minApprovals = 1  // Single reviewer sufficient
}
```

**Medium Teams:**
```kotlin
approval {
    minApprovals = 2  // Two reviewers for redundancy
}
```

**Large/Critical Projects:**
```kotlin
approval {
    minApprovals = 3
    requiredApprovers = listOf("tech-lead", "security")
    noChangesSinceApproval = true
}
```

### Required Approvers by Role

**Technical Leadership:**
```kotlin
approval {
    minApprovals = 2
    requiredApprovers = listOf("tech-lead", "architect")
}
```

**Security-Critical Changes:**
```kotlin
approval {
    requiredApprovers = listOf("security-team", "compliance-officer")
}
```

**Cross-Functional:**
```kotlin
approval {
    requiredApprovers = listOf("backend-lead", "frontend-lead", "qa-lead")
}
```

### Tips

1. Balance rigor with team velocity
2. Use `requiredApprovers` for critical roles only
3. Enable `noChangesSinceApproval` for compliance requirements
4. Combine with [Reviewers](reviewers.md) rule for full review policies
5. Consider using [Conditional Rules](conditional.md) for context-specific requirements

## Combining with Other Rules

### With Reviewers Rule

```kotlin
rules {
    reviewer {
        requiredReviewers = listOf("senior-dev-1", "senior-dev-2")
    }

    approval {
        minApprovals = 2
        requiredApprovers = listOf("tech-lead")
    }
}
```

This means:
- Specific reviewers are assigned
- Minimum approval count is met
- Tech lead must approve

### With File Changes

```kotlin
rules {
    fileChanges {
        patterns = listOf(
            FilePatternRule(
                pattern = "src/security/**",
                requireApproval = listOf("security-team")
            )
        )
    }

    approval {
        minApprovals = 2
    }
}
```

### With Codeowners

```kotlin
rules {
    codeowners {
        requireOwnerApproval = true
    }

    approval {
        minApprovals = 2
        requiredApprovers = listOf("tech-lead")
    }
}
```

## Understanding noChangesSinceApproval

When enabled, this setting validates that no commits were pushed after approvals were given:

```kotlin
approval {
    noChangesSinceApproval = true
}
```

**Use cases:**
- Compliance requirements (SOC2, ISO)
- Security-critical changes
- Release branches
- Production hotfixes

**Workflow impact:**
- If changes are needed after approval, approvals must be re-granted
- Encourages complete review before approval
- Prevents "approve then fix" workflows

## Platform-Specific Behavior

### GitLab

- Uses MR approval API
- Supports approval rules
- Tracks approval timestamps
- Considers "approved by" status

### GitHub

- Uses PR review API
- "Approved" review state counts as approval
- Re-request review after changes resets approval

### Bitbucket

- Uses PR participants API
- Tracks approver role
- Supports approval rules per repository

## Approval Strategies

### Minimum Count Strategy

Best for: Small to medium teams with trusted members

```kotlin
approval {
    minApprovals = 2
}
```

### Required Roles Strategy

Best for: Teams with defined technical leadership

```kotlin
approval {
    minApprovals = 1
    requiredApprovers = listOf("tech-lead", "architect")
}
```

### Strict Compliance Strategy

Best for: Regulated industries, security-critical projects

```kotlin
approval {
    minApprovals = 3
    requiredApprovers = listOf("security", "compliance", "tech-lead")
    noChangesSinceApproval = true
}
```

### Flexible Strategy

Best for: Projects with varying change criticality

Tighten the requirement with a policy when security-sensitive files change:

```yaml
policies:
  - when: 'file_path_matches("src/security/**")'
    modules: [approval]
    override:
      approval:
        minApprovals: 3
        requiredApprovers: ["security-team"]
```

See [Conditional Rules (Policies)](conditional.md).

## Common Patterns

```kotlin
// Standard two-approver policy
approval {
    minApprovals = 2
}

// Leadership sign-off
approval {
    requiredApprovers = listOf("tech-lead")
}

// Department heads approval
approval {
    requiredApprovers = listOf("backend-lead", "frontend-lead")
}

// Security review
approval {
    minApprovals = 1
    requiredApprovers = listOf("security-team")
    noChangesSinceApproval = true
}
```

## Related Rules

- [Reviewers](reviewers.md) - Control who can review
- [Codeowners](codeowners.md) - Require owner approvals
- [File Changes](file-changes.md) - File-specific approval requirements
- [Conditional Rules](conditional.md) - Context-specific approval policies
