# Draft Detection

## Overview

The Draft Detection validator blocks merge requests that are marked as drafts or have WIP (Work In Progress) indicators. This prevents premature merging of incomplete work.

## Configuration

### Optional Properties

- `blockDraft`: Finding for a draft MR: `fail` (error), `warn` (warning) or `off` (`fail | warn | off`, default: `fail`)
- `wipPatterns`: Regex patterns to detect WIP indicators in titles (list of strings, default: `["\\bWIP\\b", "\\[WIP\\]", "\\bwip:", "\\bWIP:"]`)
- `wipMarkers`: Plain text markers auto-converted to regex patterns (list of strings, default: `null`). Simpler alternative to `wipPatterns`.

## Examples

### Basic Usage

```kotlin
karandash {
    rules {
        draftPrevention {
            blockDraft = "fail"
        }
    }
}
```

### Custom WIP Patterns

```kotlin
karandash {
    rules {
        draftPrevention {
            blockDraft = "fail"
            wipPatterns = listOf(
                "WIP",
                "\\[WIP\\]",
                "wip:",
                "WORK IN PROGRESS",
                "🚧",
                "Draft:"
            )
        }
    }
}
```

### Lenient Draft Policy

```kotlin
karandash {
    rules {
        draftPrevention {
            blockDraft = "warn"  // Allow drafts, just warn
            wipPatterns = listOf("WIP", "\\[WIP\\]")
        }
    }
}
```

### Strict Draft Detection

```kotlin
karandash {
    rules {
        draftPrevention {
            blockDraft = "fail"
            wipPatterns = listOf(
                "(?i)wip",           // Case-insensitive
                "(?i)\\[wip\\]",
                "(?i)work.in.progress",
                "(?i)draft",
                "(?i)do.not.merge",
                "🚧",
                "⚠️"
            )
        }
    }
}
```

## Error Messages

- **Draft MR**: `MR is marked as draft and cannot be merged`
- **WIP in title**: `MR title contains WIP indicator and cannot be merged`

Suggestions: `Mark the MR as ready.` for a draft MR; `Remove '<marker>' from the MR title.` for a WIP title, naming
the longest matched marker (`[WIP]`, `WIP:`, ...).

## Best Practices

### When to Use This Rule

- Prevent premature merging
- Enforce proper review process
- Require explicit ready state
- Protect production branches
- Maintain code quality gates

### Draft/WIP Workflow

**Standard Workflow:**
```
1. Create MR as draft
   - Early feedback
   - CI/CD testing
   - Show progress

2. Complete implementation
   - All features done
   - Tests passing
   - Ready for review

3. Mark as ready for review
   - Remove draft status
   - Remove WIP from title
   - Request reviewers

4. Review and merge
   - Passes validation
   - Gets approvals
   - Merges to target
```

### When to Use Drafts

**Good use cases:**
- Early feedback on approach
- Testing CI/CD pipeline
- Incremental development
- Collaborative development
- Architecture review

**Not recommended:**
- Avoiding review
- Long-term parking
- Incomplete features in production
- Circumventing approval process

### Tips

1. Enable `blockDraft = "fail"` to enforce ready state
2. Customize `wipPatterns` for your team's conventions
3. Document draft workflow in CONTRIBUTING.md
4. Use draft state for early CI/CD testing
5. Remove draft status when ready for serious review

## Draft Indicators

### Platform Draft Status

**GitLab:**
- Draft MR flag (formerly WIP)
- API field: `draft`
- Prefix: "Draft:" automatically added to title

**GitHub:**
- Draft Pull Request state
- API field: `draft`
- UI: "Draft" badge

**Bitbucket:**
- No native draft status
- Relies on title conventions

### Title-Based Indicators

Common patterns detected:

```
WIP: Add user authentication
[WIP] Implement payment flow
wip: Database migration
Work in Progress: API refactor
Draft: New feature
🚧 Under construction
⚠️ DO NOT MERGE
```

## WIP Pattern Examples

### Simple Patterns

```kotlin
wipPatterns = listOf(
    "WIP",          // Matches: "WIP"
    "\\[WIP\\]",    // Matches: "[WIP]"
    "Draft:"        // Matches: "Draft:"
)
```

### Case-Insensitive

```kotlin
wipPatterns = listOf(
    "(?i)wip",              // Matches: WIP, wip, Wip
    "(?i)\\[wip\\]",        // Matches: [WIP], [wip]
    "(?i)draft"             // Matches: Draft, DRAFT, draft
)
```

### Comprehensive

```kotlin
wipPatterns = listOf(
    "(?i)\\bwip\\b",                    // Word boundary
    "(?i)\\[wip\\]",                    // Bracketed
    "(?i)wip:",                         // Colon separator
    "(?i)work.in.progress",             // Full phrase
    "(?i)draft",                        // Draft
    "(?i)do.not.merge",                 // Explicit warning
    "(?i)\\bDNM\\b",                    // Do Not Merge acronym
    "🚧",                                // Construction emoji
    "⚠️",                                // Warning emoji
    "🔴"                                 // Red circle
)
```

## Combining with Other Rules

### With Conflict Detection

```kotlin
rules {
    draftPrevention {
        blockDraft = "off"  // Allow drafts to have conflicts
    }

    conflict {
        blockOnConflicts = "fail"  // Block non-drafts with conflicts
    }
}
```

### With CI/CD Status

```kotlin
rules {
    draftPrevention {
        blockDraft = "fail"
    }

    cicdStatus {
        requirePassed = true  // Still run CI on drafts for feedback
    }
}
```

### With File Changes

```kotlin
rules {
    draftPrevention {
        blockDraft = "fail"
    }

    fileChanges {
        patterns = listOf(
            FilePatternRule(
                pattern = "src/critical/**",
                blockOnDraft = "fail"  // Extra protection for critical files
            )
        )
    }
}
```

### With Labels

```kotlin
rules {
    draftPrevention {
        blockDraft = "fail"
    }

    labels {
        conditionalLabels = listOf(
            ConditionalLabel(
                condition = LabelCondition(hasLabel = "experimental"),
                requiredLabels = listOf("draft-ok")
            )
        )
    }
}
```

## Draft Best Practices

### Creating Drafts

**Do:**
```
Add clear WIP/Draft indicator
Describe what's incomplete
Set as draft status on platform
Request early feedback
Keep CI/CD running
```

**Don't:**
```
Leave as draft indefinitely
Use draft to avoid review
Merge without removing draft status
Forget to update when ready
```

### Draft Descriptions

Good draft description:
```markdown
## Draft MR - Early Feedback Requested

### Completed
- [x] Basic authentication flow
- [x] Unit tests

### In Progress
- [ ] OAuth2 integration
- [ ] Error handling

Ready for feedback on architecture and approach.
Will remove draft status once all items completed.
```

### Converting to Ready

**Checklist before removing draft:**
```
□ All features implemented
□ Tests written and passing
□ Documentation updated
□ Code formatted and linted
□ Self-reviewed changes
□ Ready for thorough review
□ No known issues
```

## Platform-Specific Features

### GitLab

**Create Draft:**
```bash
# CLI
glab mr create --draft

# Web: Check "Mark as draft"
```

**Remove Draft:**
```bash
# CLI
glab mr update <id> --ready

# Web: Click "Mark as ready"
```

### GitHub

**Create Draft:**
```bash
# CLI
gh pr create --draft

# Web: Select "Create draft pull request"
```

**Ready for Review:**
```bash
# CLI
gh pr ready <number>

# Web: Click "Ready for review"
```

### Bitbucket

**No Native Support:**
- Use title conventions
- Add "[WIP]" prefix manually
- Remove prefix when ready

## Advanced Patterns

### Context-Aware WIP Detection

```kotlin
draftPrevention {
    blockDraft = "fail"
    wipPatterns = listOf(
        "(?i)^\\[?WIP\\]?:?\\s",           // Start of title
        "(?i)\\bwip\\b",                    // Word boundary
        "(?i)^Draft:?\\s",                  // Start with Draft
        "(?i)\\(wip\\)",                    // Parentheses
        "(?i)- wip$"                        // End of title
    )
}
```

### Emoji-Based Detection

```kotlin
draftPrevention {
    blockDraft = "fail"
    wipPatterns = listOf(
        "🚧",     // Construction
        "⚠️",     // Warning
        "🔴",     // Red circle
        "⏸",     // Pause
        "🛑",     // Stop sign
        "📝",     // Memo (some teams use for draft)
        "🔧"      // Wrench (work in progress)
    )
}
```

## Automated Draft Management

### Auto-Draft on Branch Name

Some teams auto-mark as draft based on branch:

```bash
# Branch: wip/new-feature
# Auto-set to draft

# Branch: feature/new-feature
# Normal MR
```

### Auto-Ready on Label

```
# Add "ready-for-review" label
# Automatically removes draft status
```

## Draft Analytics

Track metrics:
- Draft to ready conversion time
- Number of drafts per developer
- Average review comments on drafts
- Draft abandonment rate

## Common Issues

**Draft stuck in pipeline:**
- Make sure draft can still run CI/CD
- Don't block drafts from testing

**Accidental draft status:**
- Clear documentation on creating MRs
- Team training on draft workflow

**Abandoned drafts:**
- Periodic cleanup process
- Auto-close after X days (e.g., 30)

## Team Guidelines Example

```markdown
## Draft Merge Requests

### When to Use Draft
- Seeking early architectural feedback
- Testing CI/CD pipeline
- Incremental development with checkpoints
- Collaborative feature development

### How to Create Draft
1. Create MR with "[WIP]" in title OR use platform draft feature
2. Describe what's complete and what's remaining
3. Tag relevant reviewers for early feedback
4. Keep CI/CD running

### When to Mark Ready
1. All TODOs completed
2. Tests passing
3. Self-review done
4. Ready for thorough review
5. Remove "[WIP]" from title
6. Change platform status to "Ready"

### Rules
- Draft MRs cannot be merged
- Draft MRs can receive feedback
- CI/CD runs on drafts
- Drafts older than 30 days will be closed
```

## Related Rules

- [Conflict Detection](conflict-detection.md) - Allow conflicts in drafts
- [CI/CD Status](cicd-status.md) - Run tests on drafts
- [File Changes](file-changes.md) - Block critical files in drafts
- [Approvals](approvals.md) - No approvals needed for drafts
- [Labels](labels.md) - Label-based draft policies
