# JIRA Ticket

## Overview

The JIRA Ticket validator checks that merge requests reference valid JIRA tickets in their titles. It validates both the presence and format of ticket references.

The rule is **off by default**: without project keys there is nothing to enforce, so it runs only when you configure it (set `projectKeys`/`projects`, or enable it explicitly).

## Configuration

### Optional Properties

- `projectKeys`: List of valid JIRA project keys (list of strings, default: `[]`).
- `pattern`: Custom regex pattern for ticket extraction (string, default: `"([A-Z]+-\\d+)"`)
- `checkIn`: Where to check for the ticket reference - `"title"`, `"description"`, or `"both"` (string, default: `"both"`)
- `jiraUrl`: JIRA instance URL used to generate validation links in findings (string, default: `null`)
- `format`: Structured ticket format type - alternative to `pattern`. Ignored when `pattern` is set (`TicketFormat`, default: `null`). Values: `JIRA`, `LINEAR`, `YOUTRACK`, `GITHUB`, `SHORTCUT`, `CUSTOM`. `CUSTOM` has no built-in shape and needs an explicit `pattern`; without one the rule warns (`format 'custom' requires a pattern`) and falls back to the default JIRA key shape.
- `projects`: Structured list of project keys - alternative to `projectKeys`. Takes precedence over `projectKeys` when both are set (list of strings, default: `null`)
- `validateStatus`: List of required JIRA ticket statuses. Requires the `jira` enrichment provider (list of strings, default: `null`)
- `validateAssignee`: Validate that the ticket is assigned. Requires the `jira` enrichment provider (boolean, default: `false`)

## Examples

### Basic Usage

```kotlin
rules {
    jiraTicket {
        projectKeys = listOf("PROJ", "TEAM", "ENG")
    }
}
```

### Custom Pattern

```kotlin
rules {
    jiraTicket {
        projectKeys = listOf("WEB", "API", "MOBILE")
        pattern = "\\[([A-Z]+-\\d+)\\]"  // Requires brackets: [PROJ-123]
    }
}
```

### Multiple Projects

```kotlin
rules {
    jiraTicket {
        projectKeys = listOf(
            "BACKEND",
            "FRONTEND",
            "DEVOPS",
            "DESIGN"
        )
    }
}
```

## Error Messages

- **No ticket found**: `No JIRA ticket found in MR title.`, with the suggestion ``Add a ticket reference such as `PROJ-123` to the title.``
- **Invalid project key**: `Invalid JIRA project key: {key}.`, with the suggestion ``Use a ticket of an allowed project, e.g. `PROJ-7` instead of `OPS-7`.``

The ticket format, with the allowed project keys, is in the finding's format metadata.

With `format` set, messages name that system instead of JIRA (`No GitHub ticket found…`, `Invalid Linear project key…`); `CUSTOM` says `No ticket found…`.
- **Invalid pattern**: `Invalid regex pattern in JIRA ticket configuration: '{pattern}' - {error}`

## Best Practices

### When to Use This Rule

- Link merge requests to work tracking system
- Make sure all changes are planned and tracked
- Enable traceability from code to requirements
- Generate release notes from ticket metadata
- Enforce project-specific workflows

### MR Title Formats

**Standard Format:**
```
PROJ-123: Add user authentication
```

**Bracketed Format:**
```
[PROJ-123] Implement OAuth2 login
```

**Multiple Tickets:**
```
TEAM-456, TEAM-457: Refactor authentication module
```

**With Conventional Commits:**
```
feat(auth): PROJ-789 add password reset
```

### Pattern Examples

```kotlin
// Standard: PROJ-123 anywhere in title
pattern = "([A-Z]+-\\d+)"

// Bracketed: [PROJ-123]
pattern = "\\[([A-Z]+-\\d+)\\]"

// Start of title: PROJ-123:
pattern = "^([A-Z]+-\\d+):"

// Multiple tickets: PROJ-123, PROJ-124
pattern = "([A-Z]+-\\d+(?:,\\s*[A-Z]+-\\d+)*)"
```

### Tips

1. Keep `projectKeys` list up to date with active JIRA projects
2. Use `pattern` to enforce specific title formats
3. The default pattern `([A-Z]+-\\d+)` works for most cases
4. Test regex patterns to avoid false positives
5. Document required format in team guidelines
6. Status/assignee validation needs live ticket data from the [`jira` enrichment provider](jira-validation.md), which also requires `jiraUrl` to be set on this rule - see the known limitation noted there

## Combining with Other Rules

### With Title Format

```kotlin
rules {
    titleFormat {
        pattern = "^[A-Z]+-\\d+: (feat|fix|docs|chore): .+"
    }

    jiraTicket {
        projectKeys = listOf("PROJ")
        pattern = "^([A-Z]+-\\d+):"
    }
}
```

### With Branch Naming

```kotlin
rules {
    branchNaming {
        pattern = "^(feature|bugfix)/[A-Z]+-\\d+-.+"
    }

    jiraTicket {
        projectKeys = listOf("PROJ")
    }
}
```

This creates end-to-end traceability: branch name → ticket → MR title.

## Common Project Key Patterns

```kotlin
// Single team
projectKeys = listOf("TEAM")

// Multiple teams
projectKeys = listOf("WEB", "API", "MOBILE")

// Hierarchical projects
projectKeys = listOf("PRODUCT", "PRODUCT-WEB", "PRODUCT-API")

// Department-wide
projectKeys = listOf("ENG", "DESIGN", "QA", "DEVOPS")
```

## Troubleshooting

**Ticket not detected:**
- Make sure project key is in `projectKeys` list
- Verify pattern matches your title format
- Check that ticket ID follows `KEY-123` format

**False positives:**
- Tighten pattern with anchors: `^([A-Z]+-\\d+):`
- Use more specific format like `\\[([A-Z]+-\\d+)\\]`

## Related Rules

- [Title Format](title-format.md) - Enforce overall title format
- [Branch Naming](branch-naming.md) - Require tickets in branch names
- [Commit Messages](commit-messages.md) - Validate commit message format
