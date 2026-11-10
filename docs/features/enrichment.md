# Enrichment Providers Reference

Enrichment providers fetch external data once per MR and make it available to rules and AI prompts.

## Activation

Providers are configured under the top-level `enrichment:` block, keyed by provider ID:

```yaml
enrichment:
  jira:
    url: "https://acme.atlassian.net"
    token: "${JIRA_TOKEN}"
```

Providers do not read a top-level `auth:` block - each reads its own `token` field (falling back to a
provider-specific env var, e.g. `JIRA_TOKEN`) directly.

Each provider is initialized with its own block as `PluginContext.pluginConfig` (keyed by the provider ID,
kebab-case or camelCase); a provider with no block runs on its defaults and env vars when it is enabled by
default (`executeEnrichmentProviders` in `cli/.../pipeline/ValidationPipeline.kt`).

> **Note:** Two unrelated features are named "enrichment". The top-level `enrichment:` block (this page)
> configures **enrichment providers**. The separate `ai.enrichment:` block configures **AI title/description
> generation** before validation - a different subsystem parsed into `EnrichmentConfig`
> (`EnrichmentConfig`) and run by
> `EnrichmentStage`. The `--enrich` CLI flag belongs to *that* feature, not to providers.

`validate --enrich` runs that AI generation before validation: it needs a configured AI provider and generates
both a title and a description when there is no `ai.enrichment:` block; with a block, its `generate-title` /
`generate-description` / `skip-if-description-exists` keys decide, and the block alone turns the stage on without
the flag. The suggestions are written to the MR through the platform service, and the MR is re-fetched, so the rules
validate the generated text. `--dry-run-enrich` logs the suggestions instead of applying them; it changes nothing
unless the stage runs (`--enrich` or an `ai.enrichment:` block). `ModuleInitializer.runEnrichment`,
`cli/.../pipeline/EnrichmentStage.kt`.

## Sub-block Configuration

Each enrichment ID the provider can emit is configured as a named sub-block:

```yaml
enrichment:
  jira:
    url: "https://acme.atlassian.net"
    token: "${JIRA_TOKEN}"
    tickets:            { enabled: true, max-per-mr: 10 }
    acceptance-criteria: { enabled: true }
    linked-issues:      { enabled: true }
    ticket-keys:        { enabled: true }   # zero-auth regex extraction
```

The `enrich: [...]` list is an equivalent short form. Each entry enables its sub-block with defaults; when both are
present they merge, and an explicit sub-block wins for its key:

```yaml
enrichment:
  jira:
    url: "..."
    enrich: ["jira:tickets", "jira:linked-issues"]
    tickets: { max-per-mr: 5 }   # explicit sub-block wins; linked-issues is enabled with defaults
```

## Providers

### `jira` - Jira Enrichment

**Enrichment IDs:**

| ID | What it contains |
|---|---|
| `jira:ticket-keys` | `List<String>` - ticket keys regex-extracted from MR title/description/branch/commits. Zero-auth; always cheap. |
| `jira:tickets` | `Map<key, TicketDetails>` - summary, description, type, priority, status, labels. Requires API auth. |
| `jira:acceptance-criteria` | `Map<key, List<String>>` - AC bullets from custom field or `## Acceptance Criteria` heading. |
| `jira:linked-issues` | `Map<key, List<LinkedIssue>>` - issue links including remote Confluence URLs. |

**Config:**

```yaml
enrichment:
  jira:
    url: "https://acme.atlassian.net"
    token: "${JIRA_TOKEN}"     # omit to fall back to the JIRA_TOKEN env var
    email: "${JIRA_EMAIL}"     # omit to fall back to JIRA_EMAIL; no email at all sends the token as a Bearer PAT (Server/DC)
    tickets:
      enabled: true
      max-per-mr: 10
    acceptance-criteria:
      enabled: true
      field: "customfield_10042"   # omit to auto-detect from heading
    linked-issues: { enabled: true }
    ticket-keys:   { enabled: true }
    max-requests-per-minute: 60   # rate limit for the JIRA REST API client
```

**Env vars:** `JIRA_EMAIL`, `JIRA_TOKEN`

With an email the token is sent as Basic auth (Atlassian Cloud API token); without one it is sent as
`Authorization: Bearer` (a Jira/Confluence Server or Data Center personal access token). Confluence works the same way.

---

### `confluence` - Confluence Enrichment

Resolves Confluence page URLs from Jira linked issues and MR text. Requires `jira:linked-issues` to run first.

**Enrichment IDs:**

| ID | What it contains |
|---|---|
| `confluence:pages` | `Map<pageId, PageContent>` - title, space, Markdown-converted body, source URL |

**Config:**

```yaml
enrichment:
  confluence:
    url: "https://acme.atlassian.net/wiki"
    token: "${CONFLUENCE_TOKEN}"   # omit to fall back to CONFLUENCE_TOKEN, then JIRA_TOKEN
    email: "${CONFLUENCE_EMAIL}"  # omit to fall back to CONFLUENCE_EMAIL, then JIRA_EMAIL
    pages:
      enabled: true
      max-pages-per-mr: 5
      max-chars-per-page: 4000
```

Without a token the provider is skipped and says why in the report. Requests are limited to 60 per minute and, with
the run cache on, the result is reused while the MR text and linked issues are unchanged.

---

### `github-issue` - GitHub Issue Enrichment

Extracts GitHub issue refs (`Closes #N`, `org/repo#N`) from MR text and fetches issue details.

**Enrichment IDs:**

| ID | What it contains |
|---|---|
| `github:issues` | `Map<number, IssueDetails>` - title, body, state, labels, linked PR numbers |

**Config:**

```yaml
enrichment:
  github-issue:
    # url defaults to https://api.github.com; set for GitHub Enterprise:
    # url: "https://github.acme.com/api/v3"   (unset: GITHUB_API_URL in GitHub Actions)
    owner: "acme"              # owner and repo: omit to use GITHUB_REPOSITORY (GitHub Actions)
    repo: "backend"
    token: "${GITHUB_TOKEN}"   # omit to fall back to the GITHUB_TOKEN env var
    issues: { enabled: true, max-issues-per-mr: 10 }
```

Without a token or a repository the provider is skipped with a reason. Requests are limited to 60 per minute and cached
per run like `confluence`.

---

### `gitlab-issue` - GitLab Issue Enrichment

Extracts GitLab issue refs (`#N`, `group/project#N`) and fetches issue details.

**Enrichment IDs:**

| ID | What it contains |
|---|---|
| `gitlab:issues` | `Map<iid, IssueDetails>` - title, body, state, labels |

**Config:**

```yaml
enrichment:
  gitlab-issue:
    # url defaults to https://gitlab.com; set for self-hosted:
    # url: "https://gitlab.acme.com"     (unset: CI_SERVER_URL in GitLab CI)
    project-id: 12345678       # omit to use CI_PROJECT_ID (GitLab CI)
    token: "${GITLAB_TOKEN}"   # omit to fall back to GITLAB_TOKEN (PRIVATE-TOKEN), then CI_JOB_TOKEN (JOB-TOKEN)
    issues: { enabled: true, max-issues-per-mr: 10 }
```

A `CI_JOB_TOKEN` only reaches the issues API where the GitLab instance lets job tokens read it; set `GITLAB_TOKEN`
otherwise. Without a token or a project the provider is skipped with a reason. Requests are limited to 60 per minute and
cached per run like `confluence`.

---

### `ci` - CI + Coverage Enrichment

Fetches failed job summaries and coverage gaps from the MR's most recent CI pipeline: GitLab CI pipelines, or
GitHub Actions workflow runs for the head commit.

**Enrichment IDs:**

| ID | What it contains |
|---|---|
| `ci:failed-jobs` | `List<FailedJob>` - name, stage, URL, error snippet (last ~200 log chars) |
| `ci:coverage-gaps` | `Map<filePath, List<UncoveredRange>>` - uncovered line ranges in changed files only |

**Config:**

```yaml
enrichment:
  ci:
    platform: "gitlab"        # gitlab or github; unset: github when GITHUB_ACTIONS=true, else gitlab
    url: "https://gitlab.acme.com"   # unset: CI_SERVER_URL (gitlab), GITHUB_API_URL or https://api.github.com (github)
    project-id: "12345678"    # unset: CI_PROJECT_ID (gitlab), GITHUB_REPOSITORY `owner/repo` (github)
    token: "${GITLAB_TOKEN}"  # gitlab: GITLAB_TOKEN, then CI_JOB_TOKEN; github: GITHUB_TOKEN
    failed-jobs:
      enabled: true
      max-jobs: 10
    coverage-gaps:
      enabled: true
      artifact-patterns: ["**/lcov.info", "**/*jacoco.xml", "**/jacoco-aggregated.xml"]  # globs relative to the working directory; .xml = JaCoCo, else LCOV
```

---

### `symbol-context` - Symbol Context Enrichment

Extracts function/class signatures from changed files using `ast-grep`. Requires the `ast-grep` binary on PATH.

**Enrichment IDs:**

| ID | What it contains |
|---|---|
| `symbols:changed` | `Map<filePath, List<SymbolInfo>>` - kind, name, signature, line number |

**Config:**

```yaml
enrichment:
  symbol-context:
    ast-grep-path: "ast-grep"   # optional override
    changed: { enabled: true }
```

If `ast-grep` is absent, the provider logs an `info` message and skips silently.

---

### `local-git` - Local Git Enrichment

Runs `git log` to surface hotspot files by change frequency and the most active contributors to them. Requires a full checkout.

**Enrichment IDs:**

| ID | What it contains |
|---|---|
| `local-git:file-frequency` | `Map<filePath, Int>` - commit count per file over the configured period |
| `local-git:contributor-stats` | `{"contributors": [{name, email, commits}], "period"}` - commit count per author (each commit counted once) over the changed files and the configured period, most active first. The markdown summary shows it as one anonymised line (contributor count, top contributor share, commits per contributor); names and emails never reach it |

**Config:**

```yaml
enrichment:
  local-git:
    file-frequency:
      enabled: true
      period: "6m"   # git log --since=6.months.ago
```

---

## Rule-to-Enrichment Binding

Any rule can receive enrichment data via the `rule-enrichment:` top-level block. The binding is additive - it broadens what a rule sees beyond what the module author hardcoded in `supportedEnrichment`:

```yaml
rule-enrichment:
  review-comments:
    enrich: ["jira:*", "confluence:*", "ci:*", "symbols:*"]

  risk-scoring:
    enrich: ["jira:tickets", "ci:failed-jobs", "ci:coverage-gaps"]

  branch-naming:
    enrich: ["jira:ticket-keys"]   # non-AI rule consuming enrichment
```

Glob patterns (`jira:*`) match all IDs whose prefix matches. At pipeline startup both sides of the binding
are validated: an unknown rule ID and a pattern matching no registered enrichment ID each emit a `warn` log
(`warnUnmatchedRuleEnrichment` in `cli/.../pipeline/PipelinePreparationStage.kt`).

Bindings take effect through `ModuleInitializer.deliverEnrichmentToRuleModules`,
which re-initializes every enrichment-aware rule module with the
data its `supportedEnrichment` plus its bindings resolve to. Rules declaring `requiredEnrichment` for IDs
that are unavailable are skipped by `ValidationEngine` with `SkipCategory.MISSING_REQUIRED_ENRICHMENT`.

AI rules (`review-comments`, `risk-scoring`, `pre-merge-checks`, …) are dispatched by a single
`AiRuleModule` rather than registered individually, so they receive their bindings through the per-rule
`initialize(context, ruleEnrichmentBindingPrefixes)` overload (`core/api/.../RuleModule.kt`), which gives
each dispatched rule its own enrichment visibility.

---

## Environment Variables

All tokens are read via the `PlatformEnvironment` allowlist (never `System.getenv()` directly):

| Variable | Provider |
|---|---|
| `JIRA_EMAIL` | jira |
| `JIRA_TOKEN` | jira |
| `CONFLUENCE_EMAIL` | confluence (falls back to `JIRA_EMAIL`) |
| `CONFLUENCE_TOKEN` | confluence (falls back to `JIRA_TOKEN`) |
| `GITHUB_TOKEN` | github-issue, ci on GitHub Actions (sent as `Bearer`) |
| `GITLAB_TOKEN` | gitlab-issue, ci (when `token` is not set; sent as `PRIVATE-TOKEN`) |
| `CI_JOB_TOKEN` | gitlab-issue, ci (when neither `token` nor `GITLAB_TOKEN` is set; sent as `JOB-TOKEN`) |
| `CI_SERVER_URL`, `CI_PROJECT_ID` | gitlab-issue, ci (GitLab CI: default `url` and `project-id`) |
| `GITHUB_ACTIONS`, `GITHUB_REPOSITORY`, `GITHUB_API_URL` | github-issue, ci (GitHub Actions: platform, repository, API url) |

---

## Redaction

All content fetched from ticket systems passes through `TicketContentRedactor` before reaching AI prompts. Redacted patterns include:

- Email addresses → `[PII:email]`
- Phone numbers (E.164, US, EU) → `[PII:phone]`
- SSN-like patterns → `[PII:ssn]`
- Credit-card-like sequences → `[PII:cc]`
- Jira `@mentions` → `@user`
- Secrets (API keys, bearer tokens, connection strings) → `[REDACTED]`

AI prompt blocks are also wrapped in `<untrusted-content>` boundary markers to prevent prompt injection from fetched external data.

---

## Degradation

Provider failures are **non-fatal**. When a provider times out or returns an error:

- Its IDs are added to `EnrichmentContext.missingIds`
- Rules declaring `requiredEnrichment` for those IDs are skipped with a `warn` log
- All other rules continue with the data that is available

The `timeouts.enrichment` config key (default 10s - `TimeoutConfig.DEFAULT_ENRICHMENT`) caps each
individual provider's `enrich()` call.
It is not an aggregate cap on the combined enrichment phase. Providers in different dependency layers
run sequentially, so total wall time scales with the number of layers.

```yaml
timeouts:
  enrichment: "20s"
```
