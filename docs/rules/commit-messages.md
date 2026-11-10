# Commit Messages

## Overview

The Commit Messages validator enforces standards for commit messages in the merge request. It can validate message format (like conventional commits) and enforce length constraints.

## Configuration

### Optional Properties

- `format`: Message format to enforce (string, default: `null`)
  - `"conventional"` - Enforces conventional commits format
  - `"any"` - No format check (length and `pattern` checks still apply)
  - `"custom"` - Format given by `pattern` only
  - Any other value reports `Unknown commit format`
- `maxLength`: Maximum length for first line of commit message (integer, default: `null`)
  - Returns WARNING severity when exceeded
- `minLength`: Minimum length for first line of commit message (integer, default: `null`)
- `pattern`: Custom regex pattern each commit message must match (string, default: `null`)
- `conventionalTypes`: Custom list of conventional commit types that overrides the default list (list of strings, default: `null`)
  - Only applies when `format = "conventional"`
- `type`: Structured constraint on the commit type token - `CommitTypeConfig` with `allowed` list (default: `null`)
- `subject`: Structured constraint on the commit subject - `CommitSubjectConfig` with `maxLength` (default: `null`)

## Examples

### Basic Usage - Conventional Commits

```kotlin
karandash {
    rules {
        commitMessages {
            format = "conventional"
            maxLength = 72
            suggestion = "Use conventional commit format: '<type>[(scope)]: <description>', e.g. 'feat: add OAuth2 support'"
            explanation = "Conventional commits enable automated changelog generation with tools like release-please"
        }
    }
}
```

### Maximum Length Constraint

```kotlin
karandash {
    rules {
        commitMessages {
            maxLength = 72  // Git best practice
        }
    }
}
```

## Conventional Commits Format

When `format = "conventional"`, commits must follow this pattern:

```
<type>[(scope)][!]: <description>
```

**Supported types:**
- `feat` - New feature
- `fix` - Bug fix
- `docs` - Documentation changes
- `style` - Code style changes (formatting, etc.)
- `refactor` - Code refactoring
- `perf` - Performance improvements
- `test` - Adding or updating tests
- `build` - Build system changes
- `ci` - CI/CD changes
- `chore` - Maintenance tasks
- `revert` - Reverting previous changes

**Examples:**
```
feat: add user authentication
fix(api): resolve null pointer exception
docs: update installation guide
feat!: breaking change to API
refactor(database): optimize query performance
```

## Error Messages

- **Format violation**: `Commit message does not follow conventional format: a1b2c3d, e4f5a6b`, one finding for all failing commits, naming up to three short SHAs (`a1b2c3d, e4f5a6b, c7d8e9f, +4 more`); past three, an `All N commits` attachment lists every SHA and subject. The fingerprint ignores the SHAs, so the thread stays stable as the set changes, and a configured `explanation:` no longer hides which commits failed.
- **Pattern mismatch**: `Commit message does not match required pattern: a1b2c3d`, likewise one finding naming the failing commits. The suggestion gives the fix for the first failing commit (e.g. ``Change the commit message to `feat: add login`.``); the format itself is in the `expectedFormat` metadata.
- **Too long**: `Commit a1b2c3d subject is 85 chars (limit 72).` with the suggestion `Shorten it; move details to the body.` (WARNING severity)
- **Too short**: `Commit a1b2c3d subject is 2 chars (minimum 5).` with the suggestion `Expand it to at least 5 characters.`
- When three or more commits fail the same way, they fold into one finding - `{N} commits have a subject over 72 chars ({sha1}, {sha2}, {sha3}, +{rest} more)` - and every per-commit message stays in its collapsible `All {N} commits` attachment.

## Best Practices

### When to Use This Rule

- Enforce team commit message standards
- Enable automated changelog generation
- Improve git history readability
- Support semantic versioning workflows
- Track breaking changes with `!` notation

### Recommended Configuration

**For Conventional Commits:**
```kotlin
commitMessages {
    format = "conventional"
    maxLength = 72  // Git standard
}
```

### Tips

1. Combine with [Title Format](title-format.md) for consistency
2. Use `maxLength = 72` to follow Git best practices
3. Document your commit message format in CONTRIBUTING.md
4. Consider using git hooks for pre-commit validation
5. The rule validates the **first line** of each commit message

## Conventional Commits Benefits

Using conventional commits enables:

- **Automated Changelogs**: Tools can generate CHANGELOG.md automatically
- **Semantic Versioning**: Determine next version based on commit types
- **Better History**: Clear, scannable git log
- **Breaking Changes**: Track with `!` notation (e.g., `feat!: change API`)

## Integration with Changelogs

When using conventional commits, you can generate changelogs with tools like:

- [conventional-changelog](https://github.com/conventional-changelog/conventional-changelog)
- [semantic-release](https://github.com/semantic-release/semantic-release)
- [release-please](https://github.com/googleapis/release-please)

Example generated changelog:
```markdown
## [2.0.0] - 2024-01-15

### Features
- add user authentication (abc123)
- support OAuth2 providers (def456)

### Bug Fixes
- resolve login timeout issue (ghi789)

### BREAKING CHANGES
- remove deprecated API v1 endpoints
```

## Validation Examples

**Valid Conventional Commits:**
```
feat: add user profile page
fix(auth): resolve token expiration
docs: update API documentation
refactor: simplify error handling
feat!: migrate to new database schema
```

**Invalid Commits:**
```
Added feature         // Missing type prefix
FIX: bug             // Type should be lowercase
feat add feature     // Missing colon
fix:                 // Missing description
```


## Related Rules

- [Title Format](title-format.md) - Validate MR title format
- [JIRA Ticket](jira-ticket.md) - Validate ticket references
- [Size Limits](size-limits.md) - Limit number of commits
