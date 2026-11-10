# JIRA Enrichment

## Overview

There is no JIRA validation *rule* module. Live JIRA data reaches Karandash through the **JIRA
enrichment provider** (module ID `jira`), which fetches ticket details from the JIRA REST API and
publishes them as enrichment data for rules, AI analyzers, and reports to consume.

**Module ID:** `jira`
**Type:** `EnrichmentProviderModule`
**Tier:** Extended (`plugins/plugins-extended/enrichment/jira-enrichment`)

The provider publishes four enrichment IDs:

| Enrichment ID | Contents | Needs API access |
|---------------|----------|------------------|
| `jira:ticket-keys` | Ticket keys scraped from title, description, branch, and commits | No |
| `jira:tickets` | Full ticket details fetched from the REST API | Yes |
| `jira:acceptance-criteria` | Acceptance-criteria bullets parsed from the ticket description | Yes |
| `jira:linked-issues` | Linked-issue relationships | Yes |

It degrades gracefully: without a URL and token only `jira:ticket-keys` is emitted, and a `warn` is
logged.

The provider reads its settings from its own `enrichment:` block (below); `JIRA_TOKEN` and `JIRA_EMAIL`
are the fallbacks for the credentials.

## Configuration

Configured under the top-level `enrichment:` block, keyed by the provider ID. Field names come from
`JiraConfig` (`plugins/plugins-extended/enrichment/jira-enrichment/.../JiraConfig.kt`).

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `url` | string | `""` | JIRA base URL, e.g. `https://acme.atlassian.net`. API enrichment is off when blank. |
| `token` | string | - | API token. Falls back to the `JIRA_TOKEN` env var. |
| `email` | string | - | Atlassian account email for basic auth. Falls back to the `JIRA_EMAIL` env var. |
| `ticket-keys.enabled` | boolean | `true` | Emit `jira:ticket-keys` |
| `tickets.enabled` | boolean | `true` | Emit `jira:tickets` |
| `tickets.max-per-mr` | int | `10` | Maximum tickets fetched per MR |
| `acceptance-criteria.enabled` | boolean | `true` | Emit `jira:acceptance-criteria` |
| `acceptance-criteria.field` | string | - | Custom field to read acceptance criteria from |
| `linked-issues.enabled` | boolean | `true` | Emit `jira:linked-issues` |
| `max-requests-per-minute` | int | `60` | Client-side rate limit |

There is no `baseUrl`, `authType`, `username`, or `password` field, and the provider does not read a
top-level `auth:` block.

### Example

```yaml
enrichment:
  jira:
    url: "https://acme.atlassian.net"
    email: "${JIRA_EMAIL}"
    token: "${JIRA_TOKEN}"
    ticket-keys:
      enabled: true
    tickets:
      enabled: true
      max-per-mr: 10
    acceptance-criteria:
      enabled: true
    linked-issues:
      enabled: true
```

### Environment Variables

| Variable | Used for |
|----------|----------|
| `JIRA_TOKEN` | API token when `token` is not set |
| `JIRA_EMAIL` | Account email when `email` is not set |

Both are on the `PlatformEnvironment` allowlist, so `${JIRA_TOKEN}` interpolation in config works.

## Ticket Key Extraction

Keys are matched with `(?<![A-Z0-9])([A-Z]{2,10}-[1-9][0-9]{0,5})(?![0-9A-Z])` across the MR title,
description, source branch, and every commit message, de-duplicated in that order. Known technical
abbreviations (`SHA-256`, `UTF-8`, `RFC-2119`, `CVE-2021`, …) are filtered out as false positives.

## Consuming the Data

Enrichment data is bound to rules via the top-level `rule-enrichment:` block, and is available to AI
analyzers and reports. Ticket keys are also cached, keyed on the MR title, description, source
branch, and commit messages.

```yaml
rule-enrichment:
  jiraTicket:
    enrich: ["jira:*"]
```

## Related

- [Enrichment Providers Reference](../features/enrichment.md) - full provider catalogue and the
  shared configuration model
- [JIRA Ticket](jira-ticket.md) - the rule that requires a ticket reference in the MR (no network)
- [Cross-Entity Consistency](cross-entity-consistency.md) - task ID consistency across commits
