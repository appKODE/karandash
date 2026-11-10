# Cross-Entity Consistency

## Overview

The Cross-Entity Consistency validator checks that task IDs (e.g., JIRA ticket references) are consistent across the MR and its commits. It enforces two checks:

1. **Commit task subset**: Task IDs referenced in commit titles must be a subset of the task IDs found in the MR title or description.
2. **Changelog consistency**: Task IDs appearing in `CHANGELOG:` lines in commit bodies must not exceed the number of MR task IDs, and must not reference tasks not present in the MR.

This rule is useful for teams where each MR maps to one or more tickets and each commit is expected to reference only those tickets.

## Configuration

### Optional Properties

- `taskIdPattern`: Regex pattern to extract task IDs from text (string, default: `[A-Z]+-\d+`)
- `commitTasksSubsetOfMr`: Enforce that commit task IDs are a subset of MR task IDs (boolean, default: `true`)
- `changelogConsistency`: Enforce changelog task ID consistency (boolean, default: `true`)
- `changelogDuplicateDetection`: Detect duplicate changelog entries across commits (boolean, default: `true`)
- `changelogDuplicateRequiresMatchingText`: When detecting duplicates, also require that the entry text matches (boolean, default: `false`)
- `changelogPrefix`: Line prefix that identifies changelog entries in commit bodies (string, default: `CHANGELOG:`)
- `changelogCoverage`: Require every task ID of the MR to appear in at least one CHANGELOG entry. Checked only once some commit already carries a CHANGELOG entry (boolean, default: `false`)
- `mrTaskSource`: Where to extract MR task IDs from (string, default: `title`)
  - `title` - extract from MR title only
  - `description` - extract from MR description only
  - `both` - extract from both MR title and description
- `ticketFormat`: Structured ticket format type - alternative to `taskIdPattern`. Ignored when `taskIdPattern` is set (`TicketFormat`, default: `null`). Values: `JIRA`, `LINEAR`, `YOUTRACK`, `GITHUB`, `SHORTCUT`, `CUSTOM`.
- `ticketProjects`: List of project keys used together with `ticketFormat` to build the effective task ID pattern (list of strings, default: `null`)

## Examples

### Default Configuration

```yaml
rules:
  cross-entity-consistency: true
```

With defaults, task IDs matching `[A-Z]+-\d+` (e.g., `PROJ-123`) are extracted from the MR title. Each commit title's task IDs must be a subset of those.

### Extract Task IDs from Both Title and Description

```yaml
rules:
  cross-entity-consistency:
    mr-task-source: "both"
```

### Custom Task ID Pattern

```yaml
rules:
  cross-entity-consistency:
    task-id-pattern: "#\\d+"
    mr-task-source: "title"
```

### Custom Changelog Prefix

```yaml
rules:
  cross-entity-consistency:
    changelog-prefix: "CHANGES:"
    changelog-consistency: true
```

### Subset Check Only (Disable Changelog)

```yaml
rules:
  cross-entity-consistency:
    commit-tasks-subset-of-mr: true
    changelog-consistency: false
```

## How Changelog Consistency Works

When `changelogConsistency` is enabled, the validator scans commit bodies for lines beginning with `changelogPrefix` (default: `CHANGELOG:`). Task IDs found in those lines are collected across all commits. The check fails if:

- The number of unique changelog task IDs exceeds the number of MR task IDs, or
- Any changelog task ID is not present in the MR task IDs.

Example commit body that would be checked:

```
PROJ-123: Implement user authentication

CHANGELOG: PROJ-123 Added OAuth2 login flow
```

## Error Messages

- **Commit references unknown task**: `Commit a1b2c3d mentions ALPHA-7, which is not in the MR title (ALPHA-1).`, or, when
  the MR has no task ID at all, `Commit a1b2c3d mentions ALPHA-1234, but the MR title has no task ID.` The suggestion:
  `Add [ALPHA-1234] to the MR title or remove it from the commit subject.` When three or more commits fail the same way, they fold into one finding - `{N} commits mention tasks not in the MR title ({sha1}, {sha2}, {sha3}, +{rest} more)` - and every per-commit message stays in its collapsible `All {N} commits` attachment.
- **Changelog references unknown task**: one finding for all of them, `CHANGELOG mentions 8 tasks not in the MR title (ALPHA-1, ALPHA-2, ALPHA-3, +5 more)`; the suggestion lists every task
- **Invalid regex**: Reported as a finding if `taskIdPattern` cannot be compiled

## Notes

- Task ID extraction uses `findAll` on the pattern - all non-overlapping matches in the text are collected.
- The MR task ID set is computed once per MR and reused for all commit checks.
- When `mrTaskSource` is `title` (default) and the MR title contains no task IDs, all commit task IDs are treated as "extra" and will fail the subset check.

## Related Rules

- [JIRA Ticket](jira-ticket.md) - Require and validate JIRA ticket references
- [JIRA Validation](jira-validation.md) - Live JIRA ticket status and field validation
- [Commit Messages](commit-messages.md) - Validate commit message format
