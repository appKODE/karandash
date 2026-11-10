# Codeowners

## Overview

The Codeowners validator checks that changes to files are approved by their designated owners as defined in a CODEOWNERS file. This implements the CODEOWNERS pattern popularized by GitHub and GitLab.

## Configuration

### Optional Properties

- `path`: Path to the CODEOWNERS file (string, default: `"CODEOWNERS"`)
- `requireOwnerApproval`: Whether owner approval is required (boolean, default: `true`)

### Semantics

- **Last matching rule wins.** For each changed file, only the owners of the last rule in CODEOWNERS that matches it count.
- **Any one owner is enough.** A file is satisfied when at least one owner of that rule has approved; you do not need every listed owner.
- **Target branch.** When the platform can serve repository files, CODEOWNERS is read from the MR's target branch, not from the source branch the author controls. Offline runs read the local file.

## Examples

### Basic Usage

```kotlin
karandash {
    rules {
        codeowners {
            requireOwnerApproval = true
        }
    }
}
```

### Custom CODEOWNERS Path

```kotlin
karandash {
    rules {
        codeowners {
            path = "docs/CODEOWNERS"
            requireOwnerApproval = true
        }
    }
}
```

### Disable Owner Approval

```kotlin
karandash {
    rules {
        codeowners {
            requireOwnerApproval = false  // Just track owners, don't require approval
        }
    }
}
```

## CODEOWNERS File Format

The CODEOWNERS file uses a gitignore-style pattern matching syntax:

```
# Default owner for everything
* @default-owner

# Specific directories
/src/backend/ @backend-team @tech-lead
/src/frontend/ @frontend-team
/src/security/ @security-team @ciso

# Specific files
package.json @tech-lead @devops
*.sql @dba-team

# Multiple patterns
/docs/ @tech-writer
*.md @tech-writer

# Teams and individuals
/api/ @backend-team @alice @bob
```

## Pattern Syntax

CODEOWNERS supports glob-style patterns:

- `*` - Matches files in the directory
- `**` - Matches files recursively
- `/path/` - Matches directory and contents
- `*.ext` - Matches files with extension
- `?` - Matches single character

**Last matching pattern wins** - more specific patterns override general ones.

## Error Messages

- **Missing owner approval**: `Missing approval from code owners: {owners}`
- **No CODEOWNERS file**: Rule is lenient if file doesn't exist
- **Parse errors**: Logged but don't block validation

## Best Practices

### When to Use This Rule

- Distribute code ownership across teams
- Make sure subject matter experts review changes
- Implement scalable review processes
- Document who maintains what
- Automate reviewer assignment

### CODEOWNERS File Organization

**By Component:**
```
# Frontend
/src/components/ @frontend-team
/src/styles/ @frontend-team @designer

# Backend
/src/api/ @backend-team
/src/database/ @backend-team @dba

# Infrastructure
/docker/ @devops
/kubernetes/ @devops @sre
/.github/ @devops
```

**By Team:**
```
# Core Platform Team
/src/core/ @platform-team
/src/shared/ @platform-team

# Product Teams
/src/features/checkout/ @checkout-team
/src/features/catalog/ @catalog-team
/src/features/user/ @user-team
```

**By Expertise:**
```
# Security
/src/auth/ @security-team
/src/crypto/ @security-team @cryptography-expert
**/*security* @security-team

# Performance
**/*cache* @performance-team
**/*optimization* @performance-team

# Database
*.sql @dba
/migrations/ @dba @backend-lead
```

**By Risk Level:**
```
# Critical systems (multiple owners)
/src/payment/ @payment-team @security-team @tech-lead
/src/billing/ @billing-team @finance-team @compliance

# Standard systems (single owner)
/src/reporting/ @analytics-team
/src/notifications/ @messaging-team
```

### Tips

1. Place CODEOWNERS in repository root for visibility
2. Start with broad patterns, add specific overrides
3. Use teams (`@team`) rather than individuals when possible
4. Document ownership in team wiki/docs
5. Review and update CODEOWNERS regularly
6. Last matching pattern wins - order matters!

## Example CODEOWNERS Files

### Simple Project

```
# Default owner
* @tech-lead

# Backend
/backend/ @backend-dev

# Frontend
/frontend/ @frontend-dev

# DevOps
/docker/ @devops
/kubernetes/ @devops
```

### Large Project

```
# Global fallback
* @engineering-team

# Frontend
/src/web/ @frontend-team
/src/mobile/ @mobile-team
/src/shared-ui/ @frontend-team @mobile-team

# Backend services
/services/api/ @api-team
/services/auth/ @auth-team @security-team
/services/payments/ @payments-team @security-team @compliance

# Infrastructure
/infrastructure/ @devops @sre
/docker/ @devops
/kubernetes/ @sre
/.github/ @devops

# Database
*.sql @dba
/migrations/ @dba @backend-lead
/database/ @dba

# Security
/security/ @security-team
**/*security*.ts @security-team
**/*auth*.ts @security-team

# Configuration
config/ @devops @tech-lead
.env.example @devops

# Documentation
*.md @tech-writer
/docs/ @tech-writer @product-manager

# Build & CI
package.json @tech-lead @devops
Dockerfile @devops
.gitlab-ci.yml @devops
```

### Microservices Project

```
# Global
* @platform-team

# Service-specific
/services/user-service/ @user-team
/services/order-service/ @order-team
/services/inventory-service/ @inventory-team
/services/notification-service/ @messaging-team

# Shared libraries
/libs/common/ @platform-team
/libs/auth/ @security-team
/libs/database/ @platform-team @dba

# API Gateway
/gateway/ @api-team @platform-team

# Infrastructure
/terraform/ @devops
/helm/ @sre
```

## Combining with Other Rules

### With Approvals

```kotlin
rules {
    codeowners {
        requireOwnerApproval = true
    }

    approval {
        minApprovals = 2  // Need owner + one other
    }
}
```

### With File Changes

```kotlin
rules {
    codeowners {
        requireOwnerApproval = true
    }

    fileChanges {
        patterns = listOf(
            FilePatternRule(
                pattern = "src/security/**",
                requireLabels = listOf("security-review")
            )
        )
    }
}
```

### With Reviewers

```kotlin
rules {
    codeowners {
        requireOwnerApproval = true
    }

    reviewer {
        requiredReviewers = listOf("tech-lead")  // Tech lead always reviews
    }
}
```

## Platform Integration

### GitHub

- Native CODEOWNERS support
- Automatic reviewer assignment
- Protected branch integration
- Located at: `CODEOWNERS`, `.github/CODEOWNERS`, `docs/CODEOWNERS`

### GitLab

- Code owners feature (Premium/Ultimate)
- Approval rules integration
- Located at: `CODEOWNERS`, `.gitlab/CODEOWNERS`, `docs/CODEOWNERS`

### Bitbucket

- Limited native support
- Can use pull request default reviewers
- Karandash provides consistent implementation

## Ownership Patterns

**Team-Based:**
```
/frontend/ @frontend-team
/backend/ @backend-team
```

**Individual + Team:**
```
/core/ @tech-lead @core-team
```

**Multiple Teams:**
```
/api/ @backend-team @api-team @security-team
```

**Hierarchical:**
```
* @tech-lead                    # Fallback
/src/ @engineering-team         # Broad
/src/security/ @security-team   # Specific (wins)
```

## Migration Strategy

1. **Start Simple**: Begin with directory-level ownership
2. **Add Specificity**: Add critical file patterns
3. **Refine Teams**: Create focused ownership groups
4. **Document**: Keep CODEOWNERS and team docs in sync
5. **Monitor**: Track approval patterns and adjust

## Troubleshooting

**Owners not detected:**
- Verify CODEOWNERS file path
- Check pattern syntax
- Make sure owner usernames match platform

**Too many required approvers:**
- Use more specific patterns
- Consolidate ownership to teams
- Review pattern ordering

**Approvals not counting:**
- Verify owner username matches approver
- Check that approval API is accessible
- Make sure proper authentication

## Related Rules

- [Approvals](approvals.md) - General approval requirements
- [Reviewers](reviewers.md) - Reviewer assignment rules
- [File Changes](file-changes.md) - File-specific requirements
