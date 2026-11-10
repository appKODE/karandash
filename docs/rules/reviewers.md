# Reviewers

## Overview

The Reviewers validator enforces reviewer requirements for merge requests. It can require specific reviewers, team members, or exclude certain users from reviewing.

**Rule ID:** `reviewer` (singular - this is the config key and DSL block name)
**Tier:** Extended (`plugins/plugins-extended/rules/reviewer`)

## Configuration

### Optional Properties

- `requiredReviewers`: List of usernames that must be assigned as reviewers (list of strings, default: `[]`)
- `requiredTeams`: Names matched against reviewer usernames; passes when any reviewer matches any listed name. This is not a team membership lookup (list of strings, default: `[]`)
- `excludedReviewers`: List of usernames that should not be reviewers (list of strings, default: `[]`)
- `teamMatchMode`: How to match team names - `"exact"` (default) or `"contains"` for substring matching (string, default: `"exact"`)

## Examples

### Basic Usage - Required Reviewers

```kotlin
rules {
    reviewer {
        requiredReviewers = listOf("senior-dev", "tech-lead")
    }
}
```

### Required Teams

```kotlin
rules {
    reviewer {
        requiredTeams = listOf("backend-team", "security-team")
    }
}
```

### Excluded Reviewers

```kotlin
rules {
    reviewer {
        excludedReviewers = listOf("junior-dev", "intern")
    }
}
```

### Combined Requirements

```kotlin
rules {
    reviewer {
        requiredReviewers = listOf("tech-lead")
        requiredTeams = listOf("security")
        excludedReviewers = listOf("external-contractor")
    }
}
```

## Error Messages

- **Missing required reviewers**: `Missing required reviewers: {usernames}`
- **Missing team representation**: `Missing reviewer from required teams: {teams}`
- **Excluded reviewers present**: `Excluded reviewers present: {usernames}`

## Best Practices

### When to Use This Rule

- Make sure proper technical oversight
- Require security team review for sensitive changes
- Enforce cross-team collaboration
- Prevent conflicts of interest
- Implement review policies based on expertise

### Required Reviewers by Context

**Technical Leadership Review:**
```kotlin
reviewer {
    requiredReviewers = listOf("tech-lead", "architect")
}
```

**Peer Review Only:**
```kotlin
reviewer {
    requiredTeams = listOf("backend-team")
    excludedReviewers = listOf("author")  // Author can't self-review
}
```

**Security Review:**
```kotlin
reviewer {
    requiredTeams = listOf("security-team")
}
```

**Cross-Functional Review:**
```kotlin
reviewer {
    requiredTeams = listOf("backend", "frontend", "qa")
}
```

### Team-Based Requirements

```kotlin
reviewer {
    requiredTeams = listOf("senior-developers", "architects")
}
```

This means at least one member from each team reviews the MR.

### Tips

1. Use `requiredReviewers` for critical roles only
2. `requiredTeams` passes when any reviewer username matches any listed name (not team membership)
3. Use `excludedReviewers` to prevent conflicts of interest
4. Combine with [Approvals](approvals.md) for complete review policies
5. Document team membership clearly

## Difference from Approvals Rule

**Reviewers Rule** - Controls WHO reviews:
- Assignment of reviewers
- Team representation requirements
- Exclusion of certain users

**Approvals Rule** - Controls approval requirements:
- Number of approvals
- Who must approve
- Changes after approval

Use both together for stricter policies:

```kotlin
rules {
    reviewer {
        requiredReviewers = listOf("senior-dev-1", "senior-dev-2")
        requiredTeams = listOf("security")
    }

    approval {
        minApprovals = 2
        requiredApprovers = listOf("tech-lead")
    }
}
```

## Team Matching

The validator performs exact matching for teams by default:

```kotlin
requiredTeams = listOf("backend")
```

This matches reviewers whose team name is exactly `"backend"` (case-insensitive). Set `teamMatchMode` to `"contains"` for substring matching instead.

**Note**: For production use, integrate with your platform's team API for accurate team membership checking.

## Combining with Other Rules

### With Codeowners

```kotlin
rules {
    codeowners {
        requireOwnerApproval = true
    }

    reviewer {
        requiredTeams = listOf("senior-developers")
    }
}
```

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

    reviewer {
        requiredTeams = listOf("security")
    }
}
```

### With Policies

Require a database-team reviewer only when SQL files change:

```yaml
policies:
  - when: 'file_path_matches("**/*.sql")'
    modules: [reviewer]
    override:
      reviewer:
        requiredTeams: ["database-team"]
```

See [Conditional Rules (Policies)](conditional.md).

## Exclusion Use Cases

### Prevent Self-Review

```kotlin
reviewer {
    excludedReviewers = listOf(env("MR_AUTHOR"))
}
```

### Junior Developer Restrictions

```kotlin
reviewer {
    excludedReviewers = listOf("junior-dev-1", "intern-1")
}
```

### External Contributors

```kotlin
reviewer {
    excludedReviewers = listOf("external-contractor", "vendor-employee")
}
```

### Conflict of Interest

```kotlin
reviewer {
    excludedReviewers = listOf("product-manager")  // No PM approval on technical changes
}
```

## Platform-Specific Features

### GitLab

- Supports team/group mentions (@team)
- Approval rules per merge request
- Required reviewers at project level

### GitHub

- Team reviews (@org/team)
- CODEOWNERS integration
- Review requests API

### Bitbucket

- Default reviewers per repository
- Required reviewers by branch
- Pull request participants

## Common Patterns

```kotlin
// Single senior reviewer required
reviewer {
    requiredReviewers = listOf("senior-dev")
}

// Team representation
reviewer {
    requiredTeams = listOf("backend", "frontend")
}

// Technical leadership
reviewer {
    requiredReviewers = listOf("tech-lead", "architect")
}

// Security review for sensitive changes
reviewer {
    requiredTeams = listOf("security", "compliance")
}

// Prevent conflicts
reviewer {
    requiredTeams = listOf("core-team")
    excludedReviewers = listOf("external-1", "external-2")
}
```

## Workflow Integration

### Standard Review Flow

1. Developer creates MR
2. Karandash validates reviewer assignments
3. Required reviewers/teams must be assigned
4. Excluded reviewers are blocked
5. Review proceeds with approved reviewers

### Automatic Assignment

Many platforms support automatic reviewer assignment. Combine with Karandash validation:

```kotlin
// Platform auto-assigns, Karandash validates
reviewer {
    requiredTeams = listOf("backend-team")
}
```

## Troubleshooting

**Team not detected:**
- Verify team name spelling
- Check platform team API integration
- Use explicit usernames instead

**Reviewer exclusion not working:**
- Make sure exact username match
- Check if reviewer was added before validation

## Related Rules

- [Approvals](approvals.md) - Approval count and requirements
- [Codeowners](codeowners.md) - File-based review requirements
- [File Changes](file-changes.md) - File-specific reviewer needs
- [Conditional Rules](conditional.md) - Context-based reviewer policies
