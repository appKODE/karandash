# File Changes

## Overview

The File Changes validator monitors specific file patterns and enforces requirements based on which files are modified. It supports glob patterns, approval requirements, label requirements, and draft blocking per file pattern.

## Configuration

### Optional Properties

- `patterns`: List of file pattern rules (list of `FilePatternRule`, default: `[]`)
- `requireApproval`: Global approval requirement for any changes (list of strings, default: `null`)
- `requireLabels`: Global label requirement for any changes (list of strings, default: `null`)
- `blockOnDraft`: Finding for any change in a draft MR: `fail` (error), `warn` (warning) or `off` (`fail | warn | off`, default: `off`)
- `blockedPaths`: Glob patterns that must not appear in an MR (list of strings, default: `[]`). Each changed file matching one gets an error naming the file.
- `requiredPaths`: Glob patterns the MR must touch (list of strings, default: `[]`). Each pattern that matches no changed file gets an error.
- `requireCoverage`: Warn per changed file that has changed lines without test coverage (boolean, default: `false`). Needs coverage data from the run; without it nothing is reported.

### FilePatternRule Properties

- `pattern`: Glob pattern to match file paths (string, required)
- `requireApproval`: Users who must approve changes to matching files (list of strings, default: `null`)
- `requireLabels`: Labels required when matching files change (list of strings, default: `null`)
- `blockOnDraft`: Finding for changes to matching files in draft MRs, used when the top-level `blockOnDraft` is `off` (`fail | warn | off`, default: `off`)
- `requireNonAuthorApproval`: The MR needs at least one approval from someone other than its author when a matching file changes (boolean, default: `false`)

## Examples

### Basic Usage - Sensitive Files

```kotlin
karandash {
    rules {
        fileChanges {
            patterns = listOf(
                FilePatternRule(
                    pattern = "src/security/**",
                    requireApproval = listOf("security-team")
                )
            )
        }
    }
}
```

### Multiple Pattern Rules

```kotlin
karandash {
    rules {
        fileChanges {
            patterns = listOf(
                FilePatternRule(
                    pattern = "**/*.sql",
                    requireApproval = listOf("dba"),
                    requireLabels = listOf("database-change")
                ),
                FilePatternRule(
                    pattern = "src/api/**",
                    requireApproval = listOf("api-team"),
                    requireLabels = listOf("api-change")
                ),
                FilePatternRule(
                    pattern = "config/**",
                    requireLabels = listOf("configuration")
                )
            )
        }
    }
}
```

### Block Critical Files in Draft

```kotlin
karandash {
    rules {
        fileChanges {
            patterns = listOf(
                FilePatternRule(
                    pattern = "src/payment/**",
                    blockOnDraft = "fail",
                    requireApproval = listOf("payment-team", "security")
                )
            )
        }
    }
}
```

### Global Requirements

```kotlin
karandash {
    rules {
        fileChanges {
            requireApproval = listOf("tech-lead")  // All changes need tech-lead approval
            requireLabels = listOf("reviewed")
        }
    }
}
```

## Glob Pattern Syntax

Karandash supports standard glob patterns:

- `*` - Matches any characters except `/`
- `**` - Matches any characters including `/` (recursive)
- `?` - Matches any single character
- `[abc]` - Matches any character in brackets

**Examples:**

```kotlin
// All JavaScript files
"**/*.js"

// Files in specific directory
"src/components/**"

// Specific file names
"**/config.yml"

// Multiple extensions
"**/*.{ts,tsx}"

// Top-level files only
"*.md"

// Nested directory
"src/security/**/*.java"
```

## Error Messages

- **Approval required**: `File '{path}' requires approval from: {approvers}` (WARNING severity)
- **Label required**: `File '{path}' requires labels: {labels}`
- **Draft blocked**: `Changes to '{path}' are blocked in draft MRs`
- **Global approval**: `MR requires approval from: {approvers}` (WARNING severity)
- **Global labels**: `MR requires labels: {labels}`
- **Blocked path**: `File '{path}' is blocked by fileChanges.blockedPaths`
- **Missing required path**: `MR must change a file matching '{glob}' (fileChanges.requiredPaths)`
- **Uncovered lines**: `File '{path}' has uncovered changed lines: {start}-{end}, ...` (WARNING severity)

## Best Practices

### When to Use This Rule

- Protect sensitive or critical files
- Require subject matter expert review
- Enforce labeling for tracking purposes
- Prevent accidental changes to configuration
- Implement separation of concerns

### Common File Patterns

**Security-Critical:**
```kotlin
FilePatternRule(
    pattern = "src/security/**",
    requireApproval = listOf("security-team", "tech-lead")
)
```

**Infrastructure:**
```kotlin
FilePatternRule(
    pattern = "**/Dockerfile",
    requireApproval = listOf("devops")
),
FilePatternRule(
    pattern = "kubernetes/**",
    requireApproval = listOf("devops", "sre")
)
```

**Database:**
```kotlin
FilePatternRule(
    pattern = "**/*.sql",
    requireApproval = listOf("dba"),
    requireLabels = listOf("database-migration")
),
FilePatternRule(
    pattern = "migrations/**",
    requireApproval = listOf("dba", "backend-lead")
)
```

**Configuration:**
```kotlin
FilePatternRule(
    pattern = "config/**",
    requireLabels = listOf("config-change")
),
FilePatternRule(
    pattern = "**/.env*",
    requireApproval = listOf("devops", "security")
)
```

**API Contracts:**
```kotlin
FilePatternRule(
    pattern = "src/api/schema/**",
    requireApproval = listOf("api-team", "frontend-lead"),
    requireLabels = listOf("api-change", "breaking-change")
)
```

### Tips

1. Start with critical paths first (security, infrastructure)
2. Use specific patterns to avoid over-matching
3. Combine `requireApproval` with `requireLabels` for tracking
4. Use `blockOnDraft` for high-risk files
5. Test patterns with example file paths before deploying

## Pattern Matching Examples

```kotlin
// Match all TypeScript files
pattern = "**/*.ts"
// Matches: src/app.ts, src/components/Button.ts

// Match specific directory
pattern = "src/security/**"
// Matches: src/security/auth.ts, src/security/crypto/hash.ts

// Match file name
pattern = "**/package.json"
// Matches: package.json, frontend/package.json

// Match multiple extensions
pattern = "**/*.{yml,yaml}"
// Matches: config.yml, docker-compose.yaml

// Match top-level only
pattern = "*.md"
// Matches: README.md, CONTRIBUTING.md
// Does NOT match: docs/api.md
```

## Combining with Other Rules

### Keys that share a name with other rules

Some `fileChanges` keys look like keys of other rules but check something else:

| Key | What it checks | Not the same as |
|-----|----------------|-----------------|
| `fileChanges.requireApproval` | At least one of the listed users approved the MR (per pattern: only when a matching file changed) | `approval.requiredApprovers` (every listed user must approve, on every MR) and `ai.inlineOverrides.requireApproval` (a boolean: inline override tags count only once the MR has any approval) |
| `fileChanges.blockOnDraft` | A draft MR fails (per pattern: only when a matching file changed) | `draft.blockDraft` (every draft or WIP MR fails, WIP title markers included) |

Each key is read only by its own rule; setting one never changes another.

### With Labels Rule

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
        requiredLabels = listOf("type:feature", "type:bug", "type:docs")
    }
}
```

### With Approvals Rule

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

### With Draft Detection

```kotlin
rules {
    fileChanges {
        patterns = listOf(
            FilePatternRule(
                pattern = "src/payment/**",
                blockOnDraft = "fail"
            )
        )
    }

    draft {
        blockDraft = "off"  // Allow drafts, but not for payment files
    }
}
```

## Use Cases

### Database Migration Protection

```kotlin
fileChanges {
    patterns = listOf(
        FilePatternRule(
            pattern = "migrations/**",
            requireApproval = listOf("dba", "tech-lead"),
            requireLabels = listOf("database-migration"),
            blockOnDraft = "fail"
        )
    )
}
```

### Security Review

```kotlin
fileChanges {
    patterns = listOf(
        FilePatternRule(
            pattern = "src/auth/**",
            requireApproval = listOf("security-team")
        ),
        FilePatternRule(
            pattern = "src/crypto/**",
            requireApproval = listOf("security-team", "cryptography-expert")
        )
    )
}
```

### API Contract Changes

```kotlin
fileChanges {
    patterns = listOf(
        FilePatternRule(
            pattern = "api/v*/schema/**",
            requireApproval = listOf("api-team", "product-manager"),
            requireLabels = listOf("api-change")
        )
    )
}
```

### Infrastructure as Code

```kotlin
fileChanges {
    patterns = listOf(
        FilePatternRule(
            pattern = "terraform/**",
            requireApproval = listOf("devops", "sre")
        ),
        FilePatternRule(
            pattern = "**/docker-compose.yml",
            requireApproval = listOf("devops")
        ),
        FilePatternRule(
            pattern = "kubernetes/**",
            requireApproval = listOf("sre"),
            blockOnDraft = "fail"
        )
    )
}
```

### Documentation Requirements

```kotlin
fileChanges {
    patterns = listOf(
        FilePatternRule(
            pattern = "**/*.md",
            requireLabels = listOf("documentation")
        )
    )
}
```

## Performance Considerations

- Patterns are checked for each changed file
- More specific patterns perform better
- Avoid overly broad patterns like `**/*`
- Use directory prefixes when possible

## Related Rules

- [Codeowners](codeowners.md) - File-based ownership and approval
- [Labels](labels.md) - Label requirements and validation
- [Approvals](approvals.md) - General approval requirements
- [Security](security.md) - Scan for secrets in changed files
- [Draft Detection](draft-detection.md) - Draft MR handling
