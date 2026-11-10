# Conflict Detection

## Overview

The Conflict Detection validator blocks merge requests that have merge conflicts with the target branch. So MRs are ready to merge and fail less often at merge time.

## Configuration

### Optional Properties

- `blockOnConflicts`: Finding for an MR with conflicts: `fail` (error), `warn` (warning) or `off` (`fail | warn | off`, default: `fail`)

## Examples

### Basic Usage

```kotlin
karandash {
    rules {
        conflict {
            blockOnConflicts = "fail"
        }
    }
}
```

### Disable Conflict Blocking

```kotlin
karandash {
    rules {
        conflict {
            blockOnConflicts = "warn"  // Warn but don't block
        }
    }
}
```

## Error Messages

- **Conflicts detected**: `MR has merge conflicts that must be resolved before merging`

## Best Practices

### When to Use This Rule

- Prevent merge failures
- Make sure MR is up-to-date with target branch
- Enforce conflict resolution before review
- Maintain clean merge history
- Reduce reviewer burden

### Conflict Resolution Workflow

**Standard Workflow:**
```
1. MR created
2. Conflicts detected by Karandash
3. Developer resolves conflicts:
   - Merge target branch into feature branch
   - OR rebase feature branch onto target
4. Push resolved changes
5. Validation passes
6. Ready for review and merge
```

**Continuous Integration:**
```
1. Feature branch developed
2. Target branch advances
3. Conflicts arise
4. CI/CD alerts developer
5. Developer updates branch regularly
6. Conflicts minimized
```

### Tips

1. Enable `blockOnConflicts = "fail"` for clean merges
2. Encourage frequent rebasing/merging from target
3. Use CI/CD to alert on conflicts early
4. Smaller, faster MRs reduce conflict likelihood
5. Document conflict resolution process

## Conflict Types

### Content Conflicts

Same lines modified in both branches:

```
<<<<<<< HEAD (target branch)
const value = "target version";
=======
const value = "feature version";
>>>>>>> feature-branch
```

**Resolution**: Choose appropriate version or merge both changes.

### Rename Conflicts

File renamed in one branch, modified in another.

**Resolution**: Apply modifications to renamed file.

### Delete Conflicts

File deleted in one branch, modified in another.

**Resolution**: Decide whether to keep modifications or deletion.

### Binary Conflicts

Binary files (images, PDFs) changed in both branches.

**Resolution**: Choose one version, conflicts can't be merged.

## Merge Strategies

### Merge (Recommended for Most Teams)

```bash
# On feature branch
git merge origin/main
# Resolve conflicts
git add .
git commit
git push
```

**Pros:**
- Preserves full history
- Clear conflict resolution commits
- Safe and reversible

**Cons:**
- Creates merge commits
- History can be complex

### Rebase (Clean History)

```bash
# On feature branch
git rebase origin/main
# Resolve conflicts per commit
git add .
git rebase --continue
git push --force-with-lease
```

**Pros:**
- Linear history
- Clean commit sequence
- No merge commits

**Cons:**
- Rewrites history
- Requires force push
- Can be complex for beginners

### Squash and Merge

```bash
# Platform handles squashing at merge time
# Just ensure branch is conflict-free
git merge origin/main
```

**Pros:**
- Single commit in target branch
- Clean history
- Simple for reviewers

**Cons:**
- Loses intermediate commits
- Less granular history

## Preventing Conflicts

### Keep Branches Short-Lived

```
Good: Feature branch: 1-3 days
Bad: Long-running branch: 2+ weeks
```

### Regular Updates

```bash
# Daily or before each review
git fetch origin
git merge origin/main
# OR
git rebase origin/main
```

### Small, Focused Changes

```
Good: Small MR: 10 files, 300 lines
Bad: Large MR: 50 files, 2000 lines
```

### Communication

```
- Coordinate on shared files
- Review team's active MRs
- Merge frequently
- Use feature flags for long changes
```

## Combining with Other Rules

### With Draft Detection

```kotlin
rules {
    conflict {
        blockOnConflicts = "fail"
    }

    draft {
        blockDraft = "off"  // Allow drafts to resolve conflicts
    }
}
```

### With Labels

```kotlin
rules {
    conflict {
        blockOnConflicts = "fail"
    }

    labels {
        forbiddenLabels = listOf("has-conflicts")  // Auto-added by platform
    }
}
```

### With CI/CD Status

```kotlin
rules {
    conflict {
        blockOnConflicts = "fail"
    }

    cicdStatus {
        requirePassed = true  // Tests must pass after resolving conflicts
    }
}
```

## Platform-Specific Behavior

### GitLab

- **Merge Status**: `cannot_be_merged`, `can_be_merged`
- **API Field**: `merge_status`
- **UI Indicator**: Red "Merge conflicts" badge
- **Resolution**: Web IDE or local resolution

### GitHub

- **Mergeable State**: `dirty`, `clean`, `unknown`
- **API Field**: `mergeable_state`
- **UI Indicator**: "This branch has conflicts"
- **Resolution**: Web editor or local resolution

### Bitbucket

- **Merge Status**: `conflicted`, `clean`
- **API Field**: `state`
- **UI Indicator**: Conflict warning
- **Resolution**: Local resolution only

## Conflict Resolution Tools

**Command Line:**
```bash
git mergetool
```

**IDE Integration:**
- VS Code: Built-in merge conflict resolver
- IntelliJ IDEA: Merge conflicts tool
- Sublime Merge: Visual conflict resolution

**Web-Based:**
- GitLab Web IDE
- GitHub web editor (simple conflicts)

## Automated Conflict Detection

### CI/CD Pipeline Check

```yaml
# .gitlab-ci.yml
conflict-check:
  stage: validate
  script:
    - git fetch origin $CI_MERGE_REQUEST_TARGET_BRANCH_NAME
    - git merge-base --is-ancestor origin/$CI_MERGE_REQUEST_TARGET_BRANCH_NAME HEAD || exit 1
```

### Pre-merge Hook

```bash
#!/bin/bash
# .git/hooks/pre-push

# Check for conflicts before pushing
git fetch origin
git merge-tree $(git merge-base HEAD origin/main) HEAD origin/main | grep -q "<<<<<<<" && {
    echo "ERROR: Conflicts detected with origin/main"
    exit 1
}
```

## Handling Persistent Conflicts

### Large Refactoring

```
1. Create tracking issue
2. Split into smaller MRs
3. Merge incrementally
4. Coordinate with team
```

### Multiple Feature Branches

```
1. Establish merge order
2. First MR merges
3. Subsequent MRs rebase on main
4. Resolve conflicts sequentially
```

### Shared Files

```
1. Identify conflict-prone files
2. Coordinate changes
3. Consider file splitting
4. Use feature flags
```

## Conflict Prevention Strategies

### Team Practices

```
Do: Merge frequently (multiple times per day)
Do: Keep MRs small and focused
Do: Communicate about shared code
Do: Use CODEOWNERS for coordination
Do: Rebase regularly

Don't: Long-running feature branches
Don't: Large, monolithic changes
Don't: Ignoring conflicts until ready to merge
Don't: Working on same files without coordination
```

### Technical Practices

```
Do: Modular code architecture
Do: Clear module boundaries
Do: Feature flags for large changes
Do: Automated testing
Do: Continuous integration

Don't: Tightly coupled code
Don't: God objects/files
Don't: Manual deployment
Don't: Infrequent integration
```

## Monitoring Conflicts

Track metrics:

- **Conflict Rate**: % of MRs with conflicts
- **Resolution Time**: Time from conflict detection to resolution
- **Conflict Frequency**: Conflicts per week/month
- **Hot Spots**: Files with frequent conflicts

Use data to:
- Identify problematic code areas
- Improve team practices
- Refactor conflict-prone modules

## When to Allow Conflicts

Rare cases where `blockOnConflicts = "off"`:

- **Draft MRs**: Allow drafts with conflicts for early feedback
- **Long-running branches**: Coordinate resolution timing
- **Experimental changes**: Conflicts expected during exploration

**Recommendation**: Keep `blockOnConflicts = "fail"` for 99% of cases.

## Related Rules

- [Draft Detection](draft-detection.md) - Allow drafts with conflicts
- [CI/CD Status](cicd-status.md) - Rerun tests after conflict resolution
- [Size Limits](size-limits.md) - Smaller MRs = fewer conflicts
- [Branch Naming](branch-naming.md) - Track feature branches
