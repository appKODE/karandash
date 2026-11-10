# YAML Configuration Guide

YAML configuration provides a simpler, more portable alternative to the Kotlin DSL for teams that prefer declarative configuration. This guide covers the complete YAML schema, environment variable interpolation, config inheritance, and migration from Kotlin DSL.

## Quick Start

Create `.karandash/config.yaml` in your repository root:

```yaml
rules:
  title-format:
    pattern: "^(feat|fix|chore|docs):.*"
    min-length: 10
    max-length: 120

  size-limits:
    max-lines: 500
    max-files: 30

reporters:
  console: true
```

Run validation:

```bash
karandash validate
```

That's it. Karandash auto-discovers `.karandash/config.yaml` or `.karandash/config.yml`.

## YAML vs Kotlin DSL

| Feature | YAML | Kotlin DSL |
|---------|------|------------|
| **Syntax** | Declarative YAML | Kotlin-like DSL |
| **Ease of Use** | Simple, no programming knowledge | Requires basic Kotlin familiarity |
| **Environment Variables** | `${VAR}` syntax | `env.VAR` syntax |
| **Config Inheritance** | `extends: "url"` | Supported |
| **Type Safety** | Runtime validation | Compile-time validation (via parser) |
| **IDE Support** | YAML schema validation | Limited (no LSP yet) |
| **Portability** | Works anywhere | JVM + Native |
| **Best For** | Simple setups, CI templates | Complex logic, Kotlin teams |

**Recommendation:** Use YAML for most projects. Use Kotlin DSL only if you need programmatic logic or are already a Kotlin shop.

## Complete YAML Schema

> **Important:** There is NO separate top-level `auth:` block in Karandash configuration. Authentication credentials are configured **inline** within the `platforms:` block. Any `auth:` block you create will be **ignored**.

### Provider Configuration (with Authentication)

Configure platform providers with inline authentication:

```yaml
platforms:
  # GitLab
  gitlab:
    url: "https://gitlab.com"
    token: "${GITLAB_TOKEN}"

  # GitHub
  github:
    token: "${GITHUB_TOKEN}"
    base-url: "https://api.github.com"  # Optional, for GitHub Enterprise

  # Bitbucket
  bitbucket:
    token: "${BITBUCKET_TOKEN}"

  # Azure DevOps
  azure-devops:
    token: "${AZURE_DEVOPS_TOKEN}"
    organization: "my-org"

  # Gerrit
  gerrit:
    url: "https://gerrit.example.com"
    username: "${GERRIT_USERNAME}"
    token: "${GERRIT_TOKEN}"

  # Gitea
  gitea:
    url: "https://gitea.example.com"
    token: "${GITEA_TOKEN}"

  # AWS CodeCommit
  # Omit the keys to use AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY or ~/.aws (optional `profile:`)
  aws-codecommit:
    region: "us-west-2"
    access-key-id: "${AWS_ACCESS_KEY_ID}"
    secret-access-key: "${KARANDASH_AWS_SECRET_ACCESS_KEY}"
```

### Validation Rules

#### Title Format

```yaml
rules:
  title-format:
    pattern: "^(feat|fix|chore|docs|test|refactor|style|perf)(\\(.*\\))?:.*"
    min-length: 10
    max-length: 120
    require-ticket: false  # If true, title must contain JIRA ticket
```

#### Commit Messages

```yaml
rules:
  commit-messages:
    format: "conventional"  # or "custom"
    pattern: "^(feat|fix|chore):.*"  # Used when format is "custom"
    min-length: 10
    max-length: 100
```

#### JIRA Ticket Validation

```yaml
rules:
  jira-ticket:
    project-keys:
      - "PROJ"
      - "TASK"
    pattern: "[A-Z]+-[0-9]+"
    validate-status:
      - "In Progress"
      - "In Review"
    validate-assignee: false
```

#### Branch Naming

```yaml
rules:
  branch-naming:
    pattern: "^(feature|bugfix|hotfix|release)/.*"
    allowed-patterns:
      - "^feature/.*"
      - "^bugfix/.*"
      - "^hotfix/.*"
    require-source-match: true  # Source branch must match pattern
    require-target-match: false  # Target branch (e.g., main) exempt
```

#### File Changes

```yaml
rules:
  file-changes:
    require-approval: ["database-team"]  # Global approval requirement
    require-labels: ["needs-review"]     # Global label requirement
    block-on-draft: off                # Block file changes in draft MRs
    patterns:  # Path-based rules
      - pattern: "**/*.sql"
        require-approval: ["database-team"]
        require-labels: ["database-change"]
        block-on-draft: fail
```

#### CODEOWNERS

```yaml
rules:
  codeowners:
    path: ".github/CODEOWNERS"  # or "CODEOWNERS"
    require-owner-approval: true
```

#### Approval Requirements

```yaml
rules:
  approval:
    min-approvals: 2
    required-approvers:
      - "tech-lead"
      - "senior-dev"
    no-changes-since-approval: true  # Resets approvals on new commits
```

#### Reviewer Requirements

```yaml
rules:
  reviewer:
    required-reviewers:
      - "senior-dev-1"
    required-teams:
      - "backend-team"
    excluded-reviewers:
      - "bot-user"
```

#### CI/CD Status

```yaml
rules:
  cicd-status:
    require-passed: true
    required-jobs:
      - "build"
      - "test"
      - "lint"
    block-on-failing: fail
```

#### Merge Conflicts

```yaml
rules:
  conflict:
    block-on-conflicts: fail
```

#### Draft/WIP Detection

```yaml
rules:
  draft:
    block-draft: fail
    wip-patterns:
      - "WIP"
      - "\\[WIP\\]"
      - "wip:"
      - "WIP:"
```

#### Labels

```yaml
rules:
  labels:
    required-labels:
      - "reviewed"
    forbidden-labels:
      - "do-not-merge"
      - "wip"
    conditional-labels:  # Advanced: conditional label rules
      - condition:
          has-label: "breaking-change"
        required-labels:
          - "changelog-updated"
      - condition:
          has-any-label: ["feature", "enhancement"]
        forbidden-labels:
          - "needs-design"
```

#### Size Limits

```yaml
rules:
  size-limits:
    max-files: 50
    max-lines: 500
    max-commits: 20
```

#### Security Scanning

```yaml
rules:
  security:
    scan-for-secrets: true
    secret-patterns:
      - "(?i)(password|passwd|pwd)\\s*=\\s*['\"][^'\"]+['\"]"
      - "(?i)(api[_-]?key|apikey)\\s*=\\s*['\"][^'\"]+['\"]"
    sensitive-files:
      - "**/.env"
      - "**/.env.*"
      - "**/*.pem"
      - "**/*.key"
```

#### Boolean shorthand

`rules: { commit-messages: true }` runs the rule with its default config; `false` disables it.

### Policies

Policies are conditional overrides that apply when a `when:` expression evaluates to `true`
for the current MR. They can enable/disable modules, override rule configs, change finding
severity, and gate pipeline stages - all without duplicating configuration.

#### Basic structure

<!-- template: `<condition expression>` is a placeholder, not a predicate -->
<!-- docs-test: skip -->
```yaml
policies:
  - when: '<condition expression>'
    modules: [module-id, ...]  # Optional: scope to specific modules; omit for all modules
    enabled: false             # Optional: enable or disable matched modules
    severity: warning          # Optional: override finding severity (info/warning/error)
    override:                  # Optional: deep-merged into matched module configs
      rule-name:
        key: value
    autoApprove: false         # Optional: gate the auto-approve pipeline stage
    autoFix: false             # Optional: gate the auto-fix pipeline stage
    scripts: false             # Optional: gate the scripts pipeline stage
    dangerfile: false          # Optional: gate the Dangerfile execution stage
```

**`modules:`**

The `modules:` field scopes a policy to specific module IDs. It accepts any module type:
rules, reporters, enrichment providers, AI analyzers, etc.

```yaml
- when: 'is_draft'
  modules: [ai-risk-scoring]
  enabled: false
```

#### Pipeline stage control

Four boolean fields gate whether specific pipeline stages execute when the policy matches:

| Field | Stage controlled |
|-------|-----------------|
| `autoApprove` | Automatic MR approval by Karandash |
| `autoFix` | Automatic fix application (e.g. label mutations) |
| `scripts` | External script execution (`.karandash/scripts/`) |
| `dangerfile` | Dangerfile execution |

```yaml
policies:
  # Never auto-approve MRs targeting main
  - when: 'target_branch_matches("^(main|master)$")'
    autoApprove: false

  # Do not mutate draft MRs
  - when: 'is_draft'
    autoFix: false
    scripts: false
    dangerfile: false
```

#### Condition expression predicates

Condition expressions are boolean expressions using the predicates below. Operators:
`&&` (and), `||` (or), `!` (not), parentheses for grouping, `>`, `<`, `>=`, `<=`, `==`
for numeric comparisons.

**MR state - bare predicates (no arguments)**

| Predicate | True when |
|-----------|-----------|
| `is_draft` | MR is marked as draft/WIP |
| `has_description` | MR description is non-empty |
| `has_approvals` | At least one approval exists |
| `has_reviewers` | At least one reviewer is assigned |
| `has_conflicts` | MR has merge conflicts |
| `is_merged` | MR state is merged |
| `is_closed` | MR state is closed |
| `is_opened` | MR state is open |
| `has_ci` | MR has at least one CI pipeline |

**MR numeric comparisons**

Syntax: `predicate > N`, `predicate < N`, `predicate >= N`, `predicate <= N`, `predicate == N`

| Predicate | Measures |
|-----------|---------|
| `change_count` | Number of changed files |
| `additions` | Total added lines |
| `deletions` | Total deleted lines |
| `total_changes` | Total added + deleted lines |
| `commit_count` | Number of commits |
| `approval_count` | Number of approvals |
| `reviewer_count` | Number of assigned reviewers |
| `label_count` | Number of labels |
| `description_length` | Character length of the description |
| `mr_age_hours` | MR age in hours (since earliest commit) |
| `mr_age_days` | MR age in days (since earliest commit) |
| `last_commit_age_hours` | Hours since the most recent commit |

```yaml
- when: 'change_count > 50 || mr_age_days > 14'
  severity: error
```

**MR field matching**

| Predicate | Arguments | Matches against |
|-----------|-----------|----------------|
| `title_matches("pattern")` | regex | MR title |
| `branch_matches("pattern")` | regex | Source branch |
| `branch_matches("pattern", "target")` | regex, `"target"` | Target branch |
| `source_branch_matches("pattern")` | regex | Source branch |
| `target_branch_matches("pattern")` | regex | Target branch |
| `author_matches("pattern")` | regex | MR author username |
| `description_matches("pattern")` | regex | MR description |
| `commit_message_matches("pattern")` | regex | Any commit message |
| `commit_author_matches("pattern")` | regex | Any commit author email |
| `label_exists("name")` | string | Label list (case-insensitive) |
| `label_matches("pattern")` | regex | Label list |
| `approved_by("username")` | string | Approval list (case-insensitive) |
| `reviewer_is("username")` | string | Reviewer list (case-insensitive) |
| `ci_status("status")` | string | CI pipeline status (case-insensitive) |
| `ci_job_status("job", "status")` | strings | Specific CI job status |
| `merge_status("status")` | string | Platform merge status string |

```yaml
- when: 'target_branch_matches("^main$") && !approved_by("tech-lead")'
  modules: [approval]
  override:
    approval:
      minApprovals: 2
```

**File predicates**

| Predicate | Arguments | True when |
|-----------|-----------|-----------|
| `file_path_matches("glob")` | glob | Any changed file path matches the glob |
| `has_changes_in("prefix")` | string | Any changed file path starts with prefix |
| `file_extension_matches(".ext")` | string | Any changed file has the extension |
| `file_modified("path")` | string | A specific file path is in the diff |
| `file_added("pattern")` | string | A newly-added file path contains the pattern |
| `has_file_type(".ext")` | string | Same as `file_extension_matches` |
| `change_type_exists("ADDED\|MODIFIED\|DELETED")` | string | Any change has that type |
| `file_count > N` | comparison | Changed file count comparison |
| `file_count_added > N` | comparison | Added-file count comparison |
| `file_count_deleted > N` | comparison | Deleted-file count comparison |
| `file_count_modified > N` | comparison | Modified-file count comparison |

```yaml
- when: 'file_extension_matches(".sql") || file_modified("db/schema.rb")'
  override:
    labels:
      requiredLabels: ["database-approved"]
```

**Environment predicates**

| Predicate | Arguments | True when |
|-----------|-----------|-----------|
| `is_ci` | - | The CI environment variable is set |
| `is_gitlab_ci` | - | Running inside GitLab CI |
| `is_github_actions` | - | Running inside GitHub Actions |
| `ci_platform("name")` | string | CI platform matches name (case-insensitive) |
| `env_set("VAR")` | string | Environment variable is set (non-null) |
| `env_equals("VAR", "value")` | strings | Variable equals the given value |
| `env_matches("VAR", "pattern")` | regex | Variable matches the regex |

```yaml
- when: '!is_ci'
  modules: [slack-webhook]
  enabled: false

- when: 'env_set("STRICT_REVIEW")'
  override:
    size-limits:
      maxFiles: 30
      maxLines: 800
```

**Config predicates**

| Predicate | Arguments | True when |
|-----------|-----------|-----------|
| `is_dry_run` | - | Top-level `dry-run: true` is set (see [Dry run](#dry-run)) |
| `has_profile` | - | A profile is active (any profile) |
| `profile_is("id")` | string | Active profile ID equals the value |
| `module_configured("id")` | string | Module is present in config |

```yaml
- when: 'profile_is("strict")'
  override:
    approval:
      minApprovals: 2

- when: 'is_dry_run'
  severity: info
```

#### ConditionContext

The evaluation context passed to every predicate is `ConditionContext`. Plugin authors implementing custom predicates via
`Module.registerPredicates()` should import `io.karandash.api.context.ConditionContext`.

Key fields available in `ConditionContext`:

| Field | Type | Description |
|-------|------|-------------|
| `mr` | `MergeRequest` | The fetched MR (changes, commits, labels, approvals, etc.) |
| `isCI` | `Boolean` | Whether running in a CI environment |
| `ciPlatform` | `String?` | Detected CI platform name (`"gitlab"`, `"github"`, …) |
| `dryRun` | `Boolean` | Whether top-level `dry-run: true` is set |
| `profileId` | `String?` | Active profile ID, or null if none |
| `configuredModuleIds` | `Set<String>` | IDs of all modules present in config |
| `nowEpochSeconds` | `Long` | Current time as Unix epoch seconds (for age predicates) |
| `env(name)` | `String?` | Reads from the allowlisted environment variable set |

### Shadow mode (`severity-cap`)

Roll out a new preset without blocking merges: `severity-cap:` lowers every finding that is more severe than the
cap to the cap, so the run reports what would fail but passes. A per-rule `severity-cap:` caps one rule and wins
over the top-level value; `error` there keeps that rule blocking while the rest is capped. No cap (the default)
changes nothing.

```yaml
severity-cap: warning        # every rule reports at most a warning
rules:
  title-format:
    severity-cap: error      # still blocks
  commit-messages:
    severity-cap: info       # reported as info only
```

The cap runs after every other post-validation step (AI findings included), and the exit code, auto-approve and
the reported severities follow the capped values. It is the `severity-cap` pipeline extension
(`io.karandash.extensions.SeverityCapExtension`), built in and active only when a cap is set.

### Dry run

```yaml
dry-run: true          # sets is_dry_run and makes an AI dry run
dry-run-scope: ai      # optional: ai | all
```

| `dry-run-scope` | Effect (only with `dry-run: true`) |
|-----------------|------------------------------------|
| unset (default) | Same as `ai`, with a one-time warning (before, `dry-run` alone only fed `is_dry_run`). |
| `ai` | AI rules make no LLM calls and `@karandash why`/`ask` post no thread replies (same as `ai.dryRun: true`); every other rule and output runs. |
| `all` | `ai` plus: outputs with external side effects (platform comments, Slack, webhook, email, third-party outputs) and auto-approve are skipped with a `dry-run: skipped <id>` info line. Console and file outputs still run. |

Not the same as the `--dry-run` CLI flag, which only validates the config.

### Network access (SSRF protection)

Every outbound URL (platforms, webhooks, AI providers, remote `extends:`) must be HTTPS and point to a public
host; host names are also resolved before each request and rejected if they resolve to a non-public address.

```yaml
allow-private-network: true   # self-hosted services on an internal network
# allow-localhost: true       # local development only: lifts every check below
```

| Host | default | `allow-private-network: true` | `allow-localhost: true` |
|------|---------|-------------------------------|-------------------------|
| Public | allowed | allowed | allowed |
| Private (`10/8`, `172.16/12`, `192.168/16`, `100.64/10`, `198.18/15`, `fc00::/7`) or dotless (`gitlab`), literal or after DNS | blocked | allowed | allowed |
| Loopback (`localhost`, `127/8`, `::1`) | blocked | blocked | allowed |
| Cloud metadata, link-local (`169.254/16`, `fe80::/10`), obfuscated numeric hosts | blocked | blocked | allowed |
| `http://` as the configured URL | blocked | blocked | allowed |

IPv6 forms that carry an IPv4 address (`::ffff:a00:1`, `::10.0.0.1`, NAT64 `64:ff9b::/96`, 6to4 `2002::/16`) are
classified as that IPv4 address. On the native binaries a host name whose lookup fails for any reason other than "no
such name" (resolver down, timeout) is refused rather than passed unchecked.

`allow-private-network` is the narrow escape hatch for `git.example.corp → 10.x.x.x`: it keeps the metadata, link-local
and loopback checks. It applies to the whole run and to every outbound request (platforms, webhooks, AI providers,
remote cache, `extends:`, plugin content), not per service. It does not change scheme rules: a configured webhook,
platform or provider URL must still be `https://`, but the per-request check (which also runs on redirect targets)
accepts `http://` for any host it permits - public hosts always, private hosts under this flag - so a redirect to
`http://10.x.x.x` is followed. Key spellings `allow_private_network` and `allowPrivateNetwork` work too.

An `extends:` parent's `allow-private-network` counts like the root's own (the root can still set `false`). Fetching
the `extends:` chain inherits it step by step: a parent's value applies to fetching its own parents unless a layer
closer to the root sets it. `allow-localhost` inherits the same way. Each run (and each
`--watch` re-run, `doctor`, `validate-extends`) sets it afresh, so a run without it has it off.

### Limits

DoS guards. Every value is clamped to safe bounds; an out-of-bounds value is replaced by the nearest bound with a warning.
Unset keeps the built-in default.

```yaml
limits:
  max-findings: 10000        # findings kept per run (100..100000)
  fetch-timeout: "10s"       # remote extends / { file: } fetches (1s..120s)
  expressions:
    max-length: 1000         # when:/policy expressions (100..10000)
    max-nesting-depth: 50    # (5..200)
  extends:
    max-entries: 10          # extends entries per file (1..50)
    max-depth: 5             # inheritance depth (1..20)
    max-chain-size: 20       # configs in the whole chain (2..100)
  regex:
    max-pattern-length: 500  # user regex length (50..2000)
    max-quantifier-nesting: 1 # nested quantifiers allowed (1..3)
  patterns:
    max-expansion-depth: 20  # nested named-pattern expansion (5..50)
```

`limits.fetch-timeout`, `limits.extends` and `limits.glob` (glob `extends:` entries) bound the resolution of the config
chain itself. They inherit like any other key, step by step: each layer's parents are fetched under the value merged from the root down to that layer (the closer layer wins, the root over everything). A parent can raise
or lower them for the rest of the chain, always within the bounds above. `cache.downloads`, `allow-localhost` and
`allow-private-network` inherit the same way while the chain is fetched. `${VAR}` values count after interpolation,
as in the run. A config reachable by several paths (a diamond) is fetched once, under the values inherited on the
path closest to the root; between paths of equal depth, the one through the later-declared `extends:` entry wins
(the same entry whose values win the merge).

Remote `extends:` configs are cached across runs in the `downloads` cache (`~/.karandash/cache`, keyed by the
normalized URL, revalidated with `ETag` where the server sends one, bounded by the cache size limit and GC).
`cache.downloads.ttl` sets the freshness window (default 1d). `cache.downloads.enabled: false`, `cache.enabled: false`
and `--no-cache` fetch every remote config directly and write nothing to the cache dir.

### Display

```yaml
display:
  watcher:
    debounce: "500ms"        # --watch debounce (0..60s)
  console:
    success-mark: "OK"       # console format marks; reporters.console.success-mark wins
    failure-mark: "FAIL"
```

`display.git.short-sha-length` (clamped to 4..40, default 7) sets the abbreviated commit SHA in finding text. The SHA is part of the message, so changing it changes message-based dedup and baseline keys.

### Report language

```yaml
report:
  language: ru   # BCP-47 tag, default en; ru-RU reads as ru
```

`report.language` sets the output language of the AI messages and summaries. It inherits through `extends:`.
A region tag maps to its base tag (`ru-RU` reads as `ru`).

The markdown MR summary (headings, status, tables, collapsed lists, checklist, footer) and the inline-review lines of
the platform summary are written in English; every other language comes from a translation pack (Russian, `ru`, and
Polish, `pl`, ship built in, see [Language packs](#language-packs)); a language without a pack renders them in English. A `reporters:`
entry may set its own `language` to override it for that format or output. Finding messages come from the rules (or the
AI, see below), rule ids stay as they are, and inline comment bodies keep their English severity words so later runs
still match their threads. Other formats (console, html, slack, ...) are not localized.

For any language but English, every AI prompt carries one instruction that is never trimmed under a tight budget:
reason in English, write the finding messages and the summary in the report language, keep code, identifiers, paths and
suggestion blocks verbatim, and never report the natural language of the MR content as a finding. The deterministic
[language-policy](../rules/language-policy.md) rule is unaffected: it checks only the MR title, description, commits and
branch name.

`ai.response-language` (also `response_language`, `responseLanguage`) is an alias of `report.language`: set on its own, it
works without a warning, also when only a parent sets it. A blank value counts as unset. Across
`extends:` the nearest config that sets either key wins (the root before its parents), so a child's alias overrides a
parent's `report.language` and the other way round; within one file `report.language` wins, and a warning names both
values only when one file sets both.

#### Language packs

A language beyond `en` is a plugin module of type `TRANSLATION` (`TranslationModule` in `plugins/sdk`). It is
data only: `language` (a base tag such as `pl`), `pluralRule` (`ONE_OTHER`, `EAST_SLAVIC` or `WEST_SLAVIC`) and
`translations()`, a map from each English template to its text, gettext style. Placeholders (`{0}`, `{1}`, `%d`) stay as
they are, and a counted text keeps its `one|few|many` forms separated by `|`. Any English template missing from the map
renders in English. Packs load at startup with the other plugins (JAR, JS, WASM or native; the bridges carry the map in
the module descriptor), and a pack for `en` is ignored. Two packs ship built in and load by default: Russian
(`io.karandash.translation.ru`, `EAST_SLAVIC`) and Polish (`io.karandash.translation.pl`). Russian used to be compiled
into every text table; it moved into its pack unchanged, and stays bundled because dropping it would break every config
with `report.language: ru`. Code that renders texts without the plugin bootstrap (an embedder, a unit test) gets English
for `ru` until it calls `RussianTranslationModule.register()`.

### Reporters

```yaml
reporters:
  console: true
  markdown: true  # Post compliance summary, inline comments, and AI suggestions on MR/PR

  slack:
    webhook: "${SLACK_WEBHOOK_URL}"
    channel: "#code-reviews"

  sarif:
    output-file: "karandash-results.sarif"
```

### Report transparency

```yaml
report:
  transparency:
    summary: collapsed   # off | collapsed (default) | expanded
    inline-why: false    # one-line "why" footer under each inline note
    flow-diagram: false  # Mermaid flow diagram of the run inside the block
  walkthrough: off       # off (default) | collapsed: changes table in the AI MR summary
```

When an AI rule ran, the markdown summary ends with a `<details>` block "How this review was made": coverage
with what was left out of the diff and of the context, each enricher (ok, skipped with why, e.g. a missing
`JIRA_TOKEN` or no `ast-grep`, or failed), each analyzer (model, tokens in/out, cost, findings) and how many findings
each filter removed, feedback suppressions included. `summary: expanded` opens it; `summary: off` gives the summary
exactly as before. Runs without an AI rule show no block.

`inline-why: true` ends each AI inline note with `Why: ...`: the rule, the model and the context it was given.
`flow-diagram: true` adds a Mermaid `flowchart LR` (inputs, context, analyzers, filters, output, with counts on the
edges) to the block; past 24 nodes it falls back to a stage table. The diagram is written to
`.karandash/debug/review-flow.md` on every AI run whatever the key says, so a CI job can keep it as an artifact.
Whether your GitLab or GitHub instance renders Mermaid inside a collapsed `<details>` block has not been checked for
every version, which is why `flow-diagram` defaults to off; try it on one MR first.

`walkthrough: collapsed` adds a collapsed changes table (one row per review unit: its files and lines changed) under
the AI changes summary, plus a Mermaid `sequenceDiagram` when two or more units interact. It needs the `mr-summary`
analyzer enabled, which makes one call on the shared context prefix and, when the MR description is missing or poor,
also suggests a title and description (the "Suggested Description" block, shown once even if the
`generate-description` analyzer runs too). All keys inherit through `extends:`.

### Report style

```yaml
report:
  style: standard   # quiet | standard (default) | verbose
```

`report.style` picks how much every format shows. `standard` is the compact layout: the markdown summary opens with a
`> [!caution]` or `> [!tip]` alert (verdict, counts, new/fixed since the baseline), then one table of findings
(icon | Where | Rule | Finding | How to fix) and the rest in `<details>`; inline notes lead with `rule · message`;
the console groups findings by file and ends with one status line; Slack shows at most 5 findings; the html format and
email are a short inline-styled page with a plain-text alternative. `quiet` also drops explanations, attachments and the metadata list. `verbose` shows
every detail per finding. A `reporters.<format>.style` entry overrides it for one format;
`validate --style concise|full` sets the console (`full` is `verbose`). The key inherits through `extends:`.

### Report templates

<!-- docs-test: skip -->
```yaml
reporters:
  markdown:
    template: { file: templates/summary.mustache }
    partials:
      row: { file: templates/row.mustache }
  template:                       # a custom text format
    template: { file: templates/teams.json.mustache }
    escape: json                  # none (default) | md | html | slack | json
    maxFindings: 10
```

`markdown`, `html` and `slack` take a logic-less Mustache `template` and `partials` that replace their bundled
`standard`/`quiet` layout; `{{x}}` is escaped for the medium. A broken template logs a warning and the bundled one is
used. `karandash format schema` prints the data a template sees and `karandash format render --template <file>` tries
one. See [Templates](../reporters/template.md).

### Plugins

The `plugins:` block has the same schema as `manifest.yaml`. A `manifest.yaml` next to the config file
or at `.karandash/manifest.yaml` takes precedence; the config block is used only when there is none.

```yaml
plugins:
  on-missing: fail          # warn (default) | fail | ignore: a config entry no loaded module handles
  install:
    - com.example.jira-integration            # id only
    - id: com.example.custom-validator
      version: "1.0.0"
      source: https://example.com/custom-validator.jar
```

When read from the config file, only `install` (the declared plugins) and `on-missing` take effect: a
module referenced under `rules:`, `reporters:` or `platforms:` that is declared but not installed is
reported as such. Registry settings (`registry-url`, `token`, `auth-header`) and `directory` are read by
the `karandash plugin` commands from `.karandash/manifest.yaml` only. Per-plugin config goes under the
plugin's own section (for example `rules: { custom-validator: { severity: warning } }`).

## Environment Variable Interpolation

Karandash supports `${VAR}` syntax for environment variable substitution:

```yaml
platforms:
  gitlab:
    token: "${GITLAB_TOKEN}"
    url: "${GITLAB_URL:-https://gitlab.com}"  # With default fallback
```

**Syntax:**
- `${VAR}` - Reads from environment; when not set it becomes an empty string with warning E0022 and the run
  continues (`--strict` turns the warning into a failure)
- `${VAR:-default}` - Reads from environment, uses default if not set

**Example:**

```bash
export GITLAB_TOKEN="glpat-xxxxxxxxxxxxxxxxxxxx"
export SLACK_WEBHOOK="https://hooks.slack.com/services/XXX/YYY/ZZZ"

karandash validate
```

All `${...}` placeholders are replaced at runtime.

## Config Inheritance with `extends`

Share configuration across multiple repositories or environments using remote config inheritance.

### Basic Inheritance

**Base config** (https://example.com/karandash-base.yaml):

```yaml
rules:
  title-format:
    pattern: "^(feat|fix|chore):.*"
    max-length: 120

  size-limits:
    max-lines: 500

reporters:
  console: true
```

**Child config** (.karandash/config.yaml):

```yaml
extends: "https://example.com/karandash-base.yaml"

# Override only what you need
rules:
  size-limits:
    max-lines: 1000  # Override parent's 500
```

**Result:** Child inherits all base rules and overrides `max-lines` to 1000.

### Multi-Level Inheritance

```yaml
# Level 1: Company-wide standards
extends: "https://company.com/karandash-company.yaml"

rules:
  # Level 2: Team-specific overrides
  approval:
    min-approvals: 3  # Team requires more approvals
```

**Maximum depth:** 5 levels (prevents infinite recursion)

### Local File Inheritance (Monorepos)

```yaml
# In packages/frontend/.karandash/config.yaml
extends: "../../../karandash-base.yaml"

rules:
  title-format:
    pattern: "^(feat|fix)\\(ui\\):.*"  # Frontend-specific pattern
```

**File path resolution:**
- Relative paths resolve from the directory of the file that declares them (`.karandash/`), so a package config
  reaches the repository root with one more `../` than the package depth: `../../../karandash-base.yaml`
- Absolute paths: `/repo/karandash-base.yaml`
- HTTP(S) URLs: `https://example.com/base.yaml` (`file://` URLs are not supported)

Local files must sit under the project root: the git top-level (found by walking up from the working directory
and from the root config for a `.git` entry) or the directory holding the discovered `.karandash/`, whichever is
higher. A config discovered from a subdirectory and a monorepo package extending a base at the repository root are
inside it; a path outside it fails with E0009 naming the root. A config fetched over HTTP(S) can never read a
local file.

### Inheritance Merge Behavior

**Merge strategy:** Deep merge with child priority

**Example:**

Base config:
```yaml
rules:
  approval:
    min-approvals: 2
    required-approvers: ["tech-lead"]
```

Child config:
```yaml
extends: "base.yaml"
rules:
  approval:
    min-approvals: 3  # Overrides base
    # required-approvers inherited from base
```

**Result:**
```yaml
rules:
  approval:
    min-approvals: 3  # From child
    required-approvers: ["tech-lead"]  # From base
```

**Lists are replaced, not merged:**

Base:
```yaml
rules:
  jira-ticket:
    project-keys: ["PROJ", "TASK"]
```

Child:
```yaml
extends: "base.yaml"
rules:
  jira-ticket:
    project-keys: ["NEWPROJ"]  # Replaces, doesn't append
```

Result: `project-keys: ["NEWPROJ"]` (not `["PROJ", "TASK", "NEWPROJ"]`)

### Error Handling

**Circular dependency:**

```yaml
# config-a.yaml
extends: "config-b.yaml"
```

```yaml
# config-b.yaml
extends: "config-a.yaml"  # ERROR: Circular reference
```

**Error:** `E0010: Circular config inheritance detected`

**Max depth exceeded:**

```yaml
# config-level-6.yaml
extends: "config-level-5.yaml"  # ERROR: Max depth is 5
```

**Error:** `E0011: Config inheritance depth exceeded (max: 5)`

**Fetch failure:**

```yaml
extends: "https://example.com/missing.yaml"  # ERROR: 404 Not Found
```

**Error:** `E0009: Remote config fetch failed: 404`

## Migration from Kotlin DSL

### Field Name Mapping

YAML uses kebab-case, Kotlin DSL uses camelCase:

| YAML | Kotlin DSL |
|------|------------|
| `title-format` | `titleFormat` |
| `commit-messages` | `commitMessages` |
| `jira-ticket` | `jiraTicket` |
| `branch-naming` | `branchNaming` |
| `file-changes` | `fileChanges` |
| `cicd-status` | `cicdStatus` |
| `size-limits` | `sizeLimits` |
| `min-length` | `minLength` |
| `max-length` | `maxLength` |
| `require-ticket` | `requireTicket` |
| `project-keys` | `projectKeys` |
| `block-draft` | `blockDraft` |
| `wip-patterns` | `wipPatterns` |
| `azure-devops` | `azureDevOps` |
| `aws-codecommit` | `awsCodeCommit` |
| `base-url` | `baseUrl` |
| `allowed-patterns` | `allowedPatterns` |
| `require-source-match` | `requireSourceMatch` |
| `require-target-match` | `requireTargetMatch` |
| `required-labels` | `requiredLabels` |
| `forbidden-labels` | `forbiddenLabels` |
| `conditional-labels` | `conditionalLabels` |
| `required-reviewers` | `requiredReviewers` |
| `required-teams` | `requiredTeams` |
| `excluded-reviewers` | `excludedReviewers` |
| `require-passed` | `requirePassed` |
| `required-jobs` | `requiredJobs` |
| `block-on-conflicts` | `blockOnConflicts` |
| `scan-for-secrets` | `scanForSecrets` |
| `block-on-draft` | `blockOnDraft` |
| `no-changes-since-approval` | `noChangesSinceApproval` |
| `require-owner-approval` | `requireOwnerApproval` |

### Conversion Example

**Kotlin DSL (.karandash/config.kts):**

```kotlin
rules {
    titleFormat {
        pattern = "^feat:.*"
        maxLength = 120
    }

    sizeLimits {
        maxLines = 500
    }
}

reporters {
    console = true
}
```

**Equivalent YAML (.karandash/config.yaml):**

```yaml
rules:
  title-format:
    pattern: "^feat:.*"
    max-length: 120

  size-limits:
    max-lines: 500

reporters:
  console: true
```

### Environment Variables

**Kotlin DSL:**
```kotlin
platforms {
    gitlab {
        token = env.GITLAB_TOKEN
    }
}
```

**YAML:**
```yaml
platforms:
  gitlab:
    token: "${GITLAB_TOKEN}"
```

### Lists

**Kotlin DSL:**
```kotlin
jiraTicket {
    projectKeys = listOf("PROJ", "TASK")
}
```

**YAML:**
```yaml
jira-ticket:
  project-keys:
    - "PROJ"
    - "TASK"
```

## Common Configurations

### Minimal (Quick Start)

```yaml
rules:
  title-format:
    pattern: "^(feat|fix|docs):.*"

  draft:
    block-draft: fail

reporters:
  console: true
```

### Standard (Most Teams)

```yaml
rules:
  title-format:
    pattern: "^(feat|fix|chore|docs):.*"
    min-length: 10
    max-length: 120

  size-limits:
    max-lines: 500
    max-files: 30

  draft:
    block-draft: fail

  approval:
    min-approvals: 2

  conflict:
    block-on-conflicts: fail

reporters:
  console: true
```

### Strict (Enterprise)

```yaml
extends: "https://company.com/base.yaml"

rules:
  title-format:
    pattern: "^\\[JIRA-\\d+\\] (feat|fix|chore):.*"
    require-ticket: true

  jira-ticket:
    project-keys: ["PROJ", "TASK"]
    validate-status: ["In Progress", "In Review"]

  approval:
    min-approvals: 3
    required-approvers: ["tech-lead", "architect"]
    no-changes-since-approval: true

  security:
    scan-for-secrets: true
    secret-patterns:
      - "(?i)(api[_-]?key|apikey)\\s*=\\s*['\"][^'\"]+['\"]"

reporters:
  console: true
  markdown: true
  slack:
    webhook: "${SLACK_WEBHOOK}"
    channel: "#code-reviews"
```

### Multi-Repo (Monorepo with Inheritance)

**Root config (karandash-base.yaml):**

```yaml
rules:
  title-format:
    pattern: "^(feat|fix|chore):.*"
    max-length: 120

  draft:
    block-draft: fail

reporters:
  console: true
```

**Frontend config (packages/frontend/.karandash/config.yaml):**

```yaml
extends: "../../../karandash-base.yaml"

rules:
  title-format:
    pattern: "^(feat|fix)\\(ui\\):.*"  # UI-specific
```

**Backend config (packages/backend/.karandash/config.yaml):**

```yaml
extends: "../../../karandash-base.yaml"

rules:
  approval:
    min-approvals: 3  # Backend needs more approvals
```

## Validation and Error Handling

### Invalid YAML Syntax

**Error:** `E0006: Invalid YAML syntax`

<!-- docs-test: skip -->
```yaml
rules:
  title-format:
    pattern: "unclosed string
```

**Fix:** Close the string properly:

```yaml
rules:
  title-format:
    pattern: "closed string"
```

### Unknown Configuration Key

**Error:** `E0007: Unknown YAML config key: 'titel-format'`

**Suggestion:** `Did you mean 'title-format'?`

```yaml
rules:
  titel-format:  # Typo
    pattern: ".*"
```

**Fix:**

```yaml
rules:
  title-format:
    pattern: ".*"
```

### Invalid Type

**Error:** `E0008: Invalid config value type for 'max-length': expected number, got string`

```yaml
rules:
  title-format:
    max-length: "120"  # Should be number, not string
```

**Fix:**

```yaml
rules:
  title-format:
    max-length: 120
```

## Best Practices

1. **Use environment variables for secrets** - Never commit tokens:
   ```yaml
   platforms:
     gitlab:
       token: "${GITLAB_TOKEN}"  # Good
       # token: "glpat-hardcoded"  # BAD
   ```

2. **Start with `extends` for consistency** - Share base config across repos:
   ```yaml
   extends: "https://company.com/karandash-base.yaml"
   ```

3. **Comment your overrides** - Explain why you deviate from defaults:
   ```yaml
   rules:
     size-limits:
       max-lines: 1000  # Legacy codebase, can't refactor yet
   ```

4. **Use relative paths in monorepos** - Enables moving directories:
   ```yaml
   extends: "../../base.yaml"  # Good
   # extends: "/absolute/path/base.yaml"  # Bad
   ```

5. **Pin remote configs with versioning** - Avoid breaking changes:
   ```yaml
   extends: "https://company.com/base-v1.2.yaml"  # Good
   # extends: "https://company.com/base-latest.yaml"  # Bad
   ```

6. **Generate YAML from init command** - Don't write from scratch:
   ```bash
   karandash init --format yaml
   ```
   (`yaml` is the default, so plain `karandash init` also produces YAML.)

## FAQ

**Q: Can I mix YAML and Kotlin DSL?**

A: No. Karandash uses whichever config file it finds first (`.karandash/config.kts` takes priority over `.karandash/config.yaml`).

**Q: Does YAML support all features of Kotlin DSL?**

A: Yes. Both formats map to the same `Config` model and have identical capabilities.

**Q: How do I validate my YAML config?**

A: Run `karandash validate --config .karandash/config.yaml`. Parsing errors will be reported immediately.

**Q: Can I use YAML in `extends` and Kotlin DSL for local config?**

A: No. The parent config format must match the child config format (both YAML or both Kotlin DSL).

**Q: Does YAML config work in Docker?**

A: Yes. Mount your config file or use environment variables:
   ```bash
   docker run -v .karandash/config.yaml:/app/.karandash/config.yaml rinekri/karandash:latest karandash validate
   ```

## Related Documentation

- [Zero-Config Mode](ZERO_CONFIG.md) - Validation without config files
- [Config Inheritance Deep Dive](../features/config-inheritance.md) - Advanced `extends` usage
- [Kotlin DSL Reference](../getting-started/quick-start.md) - Alternative config format
- [CLI Commands](../reference/cli/validate.md) - Command-line options
