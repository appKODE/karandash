# AI Rules

## Overview

The AI-powered rules module integrates large language models into the MR validation pipeline. It provides fifteen built-in AI analysis rules that perform tasks such as risk scoring, description quality evaluation, code review comment generation, and more. All rules share a single configured AI provider and a token budget.

**Supported providers:** OpenAI, Anthropic (Claude), Google Gemini, Ollama (local), LiteLLM, Bifrost, and custom endpoints.

## Built-in Rules

| Rule ID | Enabled by default | Description |
|---------|:-----------------:|-------------|
| `ai-risk-scoring` | Yes | Assesses change risk for potential bugs, security issues, and complexity |
| `ai-description-quality` | No | Evaluates MR description clarity and completeness (opt-in: `descriptionQuality: true`) |
| `ai-change-categorization` | No | Auto-categorizes changes and suggests labels (opt-in: `changeCategorization: true`) |
| `ai-review-comments` | Yes | Generates inline code review comments |
| `ai-similar-issues` | No | Detects similar past patterns and recurring problems (opt-in: `similarIssues: true`) |
| `ai-fix-suggestions` | No | Fix suggestions as their own analyzer call (opt-in via `features`) |
| `ai-code-suggestions` | No | Committable `existingCode`/`suggestedCode` suggestions; configure with the `code-suggestions` block (`mode: inline` or `separate`) |
| `ai-ticket-compliance` | No | Ticket compliance is a row set of the pre-merge checklist (`checks-ticket-compliance: true`) |
| `ai-pr-split` | No | Advises whether large MRs should be split (opt-in) |
| `ai-generate-description` | No | Suggests a title and description; the description is a `SetDescription` fix that `--fix` writes to the MR |
| `ai-outdated-docs` | No | Detects documentation files that may be outdated (opt-in) |
| `ai-review-thread-analysis` | No | Analyzes the open review threads, description and commits for unresolved threads and questions and vague statements (opt-in) |
| `ai-mr-summary` | No | Generates an AI-powered MR summary with key changes, risks, review focus, a per-unit walkthrough (`report.walkthrough`) and, for a poor description, a suggested title and description (opt-in) |
| `ai-code-quality` | No | Multi-dimensional code quality assessment (opt-in: `codeQuality: true`) |
| `ai-ownership-analysis` | No | Analyzes code ownership from blame data: ownership concentration, cross-team changes, bug-fix correlations, knowledge risks (opt-in) |

## Configuration

The AI module is configured under the top-level `ai` block in the config file.

### Required

- `provider`: AI provider name (string) - `openai`, `anthropic`, `gemini`, `ollama`, `litellm`, `bifrost`, `custom`, or `stub` (canned replies from `stub.replyFile`, no network, usage 0)

### Optional

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `model` | string | Provider default (`claude-sonnet-5-5` for anthropic, `gpt-4o-mini` for openai) | Model name to use (e.g., `gpt-4o`, `claude-sonnet-5-5`, `claude-haiku-5-5`) |
| `apiKey` | string | The provider's env var (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, ...) | API key; the config value wins over the env var (prefer the env var over a key in the config file) |
| `baseUrl` | string | `<PROVIDER>_BASE_URL` env var, then the provider default | Provider endpoint; the config value wins over the env var |
| `stream` | bool | `false` | Stream completions (SSE) so a long generation is bounded by an idle timeout instead of one request timeout; text and usage match the non-stream call |
| `stub.replyFile` | string | - | `provider: stub` only: file with the reply to every call, or a JSON object mapping a model name, a key the system prompt contains, or `default` to its reply. Relative to the working directory |
| `maxTokenBudget` | int | `50000` | Total token budget across all AI rules per MR |
| `failOnError` | bool | `false` | Treat AI provider errors as validation failures |
| `dryRunAi` | bool | `false` | Skip actual AI calls; emit INFO findings instead |
| `strictness` | string | `standard` | Review strictness: `lenient`, `standard`, or `strict` |
| `concurrency` | int | `1` | Number of AI rules that may run concurrently |
| `features` | map | - | Enable or disable individual rules by name |
| `models` | map | - | Per-rule model overrides |
| `temperatures` | map | - | Per-rule temperature overrides |
| `promptOverrides` | map | - | Per-rule system prompt overrides (template string) |
| `systemPrompts` | map | - | Per-analyzer system prompt as a content source: `"text"`, `{ inline: }`, `{ file: }` (inside the config directory) or `{ url:, auth: }`. For the same rule `prompts` wins over `systemPrompts`, which wins over `promptOverrides`; a conflict logs a warn naming the winner |
| `extraInstructions` | string | - | Additional instructions appended to every prompt |
| `extraInstructionsFile` | string | - | Path to a file containing extra instructions (alternative to inline `extraInstructions`) |
| `bestPractices` | string | - | Best practices appended to every prompt |
| `scopes` | map | - | Path-based prompt overlays for monorepo/multi-stack projects |
| `review.instructions` | list | `[]` | Project review instructions sent whole when their paths match a changed file ([details](#review-instructions)) |
| `review.bugChecklist` | boolean | `true` | Append a short bug-class checklist (generic items plus one line for the dominant language of the changed files) to the `ai-review-comments` prompt; ignored when the prompt is overridden |
| `review.duo-instructions-file` | boolean | `false` | Also read `.gitlab/duo/mr-review-instructions.yaml` from the MR target branch ([details](#review-instructions)) |
| `modelFallback` | list | `[]` | Ordered list of fallback models tried when the primary fails with a retryable error or is not available (404) |
| `size-policy` | map | - | Size-based skip, partial review, and diff preprocessing controls ([details](#size-policy)) |

## Environment Variables

| Variable | Provider | Description |
|----------|----------|-------------|
| `OPENAI_API_KEY` | openai | OpenAI API key |
| `OPENAI_BASE_URL` | openai | OpenAI base URL (`ai.baseUrl` wins) |
| `ANTHROPIC_BASE_URL` | anthropic | Anthropic base URL (`ai.baseUrl` wins) |
| `GEMINI_BASE_URL` | gemini | Gemini base URL (`ai.baseUrl` wins) |
| `ANTHROPIC_API_KEY` | anthropic | Anthropic API key |
| `GEMINI_API_KEY` | gemini | Google Gemini API key |
| `LITELLM_API_KEY` | litellm | LiteLLM proxy API key |
| `BIFROST_API_KEY` | bifrost | Bifrost gateway API key |
| `BIFROST_BASE_URL` | bifrost | Bifrost gateway base URL (`ai.baseUrl` wins) |
| `CUSTOM_AI_API_KEY` | custom | Custom endpoint API key |
| `KARANDASH_AI_DRY_RUN` | all | Set to `true` to enable dry-run mode |
| `KARANDASH_AI_MAX_TOKEN_BUDGET` | all | Override `maxTokenBudget` |
| `KARANDASH_REVIEW_MODE` | all | Override `reviewMode` |

## Examples

### OpenAI (GPT-4o)

```yaml
ai:
  provider: "openai"
  model: "gpt-4o"
  max-token-budget: 30000
```

Set the API key via environment variable:

```bash
export OPENAI_API_KEY="sk-..."
```

### Anthropic Claude

```yaml
ai:
  provider: "anthropic"
  model: "claude-sonnet-5-5"
  strictness: "strict"
  fail-on-error: false
```

```bash
export ANTHROPIC_API_KEY="sk-ant-..."
```

### Ollama (Local Model)

```yaml
ai:
  provider: "ollama"
  model: "llama3.2"
  max-token-budget: 10000
```

No API key required for Ollama.

### Enable Opt-in Rules

```yaml
ai:
  provider: "openai"
  model: "gpt-4o-mini"
  code-suggestions:
    enabled: true
  checks-ticket-compliance: true
  features:
    prSplit: true
    mrSummary: true
```

### Code Suggestions

The `code-suggestions` block is the single place to configure all suggestion behavior. The `mode` setting controls whether suggestions are included in the `review-comments` prompt or sent as a separate API request.

```yaml
ai:
  code-suggestions:
    enabled: true
    mode: "inline"          # "inline" (default) or "separate"
    min-severity: "warning" # minimum severity for including suggestedCode
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | bool | `false` | Enable code suggestions (implied `true` when block is present) |
| `mode` | string | `inline` | `inline` merges suggestions into the review-comments prompt (1 API request); `separate` runs the code-suggestions analyzer as its own API request |
| `min-severity` | string | `warning` | Minimum finding severity for generating `suggestedCode` (`error`, `warning`, or `info`) |

**Modes:**

- **`inline`** (default) - The review-comments analyzer uses a suggestion-enhanced prompt that includes `existingCode`/`suggestedCode` in its JSON output. When both fields are present, the analyzer splits the response into two independent findings: one `ai-review-comments` finding (the review comment without code metadata) and one `ai-code-suggestions` finding (with `existingCode` + `suggestedCode`). This makes sure suggestions render correctly through platform comment posters (e.g., GitLab/GitHub "Apply suggestion" UI). No separate code-suggestions API call is made. This is the most token-efficient option.
- **`separate`** - The code-suggestions analyzer runs its own API request and review-comments uses its plain prompt without `existingCode`/`suggestedCode`. The code-quality and fix-suggestions analyzers likewise run as their own analyzers when enabled.

**Committable suggestions:** on GitHub, GitLab and Gitea the inline comment carries a native suggestion block. When `existingCode` spans several lines, the comment covers all of them, so applying the suggestion replaces the whole snippet: GitHub gets `start_line`/`line`, and GitLab gets a `suggestion:-N+0` fence anchored on the last line. A single-line snippet uses the plain fence.

### Disable Specific Rules

```yaml
ai:
  provider: "openai"
  features:
    riskScoring: false
    reviewComments: false
```

### Per-Rule Model Overrides

```yaml
ai:
  provider: "openai"
  model: "gpt-4o-mini"
  models:
    ai-risk-scoring: "gpt-4o"
    ai-review-comments: "gpt-4o"
```

### Model Fallback Chain

```yaml
ai:
  provider: "openai"
  model: "gpt-4o"
  model-fallback:
    - "gpt-4o-mini"
    - "gpt-3.5-turbo"
```

The next model is tried when a call fails with a retryable error (429, 5xx, timeout) or the provider does not
have the model (404). A failed authentication or another non-retryable error fails every model alike and stops
the chain.

### LiteLLM Proxy

```yaml
ai:
  provider: "litellm"
  model: "gpt-4o"
```

```bash
export LITELLM_API_KEY="..."
```

### Bifrost Gateway

Bifrost requires an API key and a base URL (no default - must be configured explicitly).

```yaml
ai:
  provider: "bifrost"
  model: "gpt-4o-mini"
  baseUrl: "${BIFROST_BASE_URL}"
```

```bash
export BIFROST_API_KEY="..."
export BIFROST_BASE_URL="https://your-bifrost-proxy.example.com/openai"
```

## Scope-Specific Prompt Customization

In monorepos or projects with multiple technology stacks, different parts of the codebase benefit from different review instructions. The `scopes` block lets you define path-based prompt overlays that are automatically applied when an MR touches matching files.

### Configuration

```yaml
ai:
  provider: anthropic
  model: claude-sonnet-5-5
  scopes:
    android:
      paths: ["android/**", "app/src/**/*.kt", "app/src/**/*.java"]
      instructions: |
        Android-specific review:
        - Check Activity/Fragment lifecycle issues
        - Flag missing null checks in Java interop
        - Look for memory leaks (static Context references)
    ios:
      paths: ["ios/**", "**/*.swift", "**/*.m"]
      instructions: |
        iOS-specific review:
        - Check for retain cycles in closures
        - Flag force-unwrapping (!) unless justified
    core:
      paths: ["core/cpp/**", "**/*.cpp", "**/*.h", "**/*.hpp"]
      instructions: |
        C++ core review:
        - Check memory safety (use-after-free, buffer overflows)
        - Flag raw pointer usage where smart pointers fit
        - Verify ABI compatibility for mobile consumers
      best-practices: "Use RAII. Prefer std::unique_ptr."
```

### How It Works

- When an MR touches files matching one or more scopes, the matching scope instructions are appended to the AI prompt under `## Scope: {name}` headers.
- **Multiple scopes can match simultaneously.** An MR touching both `android/` and `core/cpp/` will include both sets of instructions.
- When **no scopes match**, the built-in language detection (`LanguageDetector`) provides language-specific overlays as a zero-config fallback.
- Scopes are fully backward compatible - when no `scopes` block is configured, behavior is unchanged.

### Scope Fields

| Field | Type | Required | Description |
|-------|------|:--------:|-------------|
| `paths` | list of strings | Yes | Glob patterns that trigger this scope |
| `instructions` | string | No | Review instructions appended to AI prompts |
| `instructions-file` | string | No | Path to a file containing scope review instructions (alternative to inline `instructions`) |
| `best-practices` | string | No | Best practices section appended to prompts |
| `best-practices-file` | string | No | Path to a file containing scope best practices (alternative to inline `best-practices`) |

### Template Variable

Matched scope names are available as `{{mr.scopes}}` (comma-separated) in prompt templates:

```yaml
ai:
  prompt-overrides:
    ai-review-comments: |
      {% if mr.scopes contains "android" %}
      Focus on Android lifecycle and Kotlin idioms.
      {% endif %}
      {% if mr.scopes contains "core" %}
      Focus on C++ memory safety and ABI stability.
      {% endif %}
```

## Review Instructions

`ai.review.instructions` lists project review rules, each tied to the files it covers. An instruction is sent to the
model only when one of its `paths` matches a file the MR changes, and it is sent whole: it is never cut to fit the
prompt. If the matched instructions alone do not fit `ai.budget.max-input-tokens`, the rule is skipped with a reason
that names them instead.

```yaml
ai:
  review:
    instructions:
      - name: kotlin-nullability
        paths: ["src/**/*.kt", "!src/generated/**"]
        text: |
          Prefer requireNotNull or checkNotNull over the double-bang operator.
      - name: sql-migrations
        paths: ["db/migrations/**"]
        text: Every migration must be reversible.
```

| Field | Type | Required | Description |
|-------|------|:--------:|-------------|
| `name` | string | Yes | Shown in the prompt and in `metadata.instruction` of the findings |
| `paths` | list of strings | Yes | Globs; one starting with `!` excludes the files it matches. Only `!` globs means every file but those |
| `text` | string | Yes | The instruction, sent under `## Review instruction: {name}` |

- When exactly one instruction matched the files a rule reviewed, every finding of that rule carries
  `metadata.instruction: <name>`. With several matches no finding names one.
- Like any `ai` key, the list is inherited through `extends:`; a child that sets its own list replaces the parent's.
- **Trust.** Instructions from config follow the config trust model: they come from the run's config chain, the
  same files that already choose the provider, the rules and the prompts. Where the MR author can edit that config,
  they can edit the instructions too.
- **GitLab Duo file.** With `ai.review.duo-instructions-file: true` (default `false`), the entries of
  `.gitlab/duo/mr-review-instructions.yaml` (`instructions: [{name, fileFilters, instructions}]`) are appended to the
  list. The file is read through the platform from the MR's target (the base commit, else the target branch), never
  from the MR head or the working directory, so an MR cannot change the instructions it is reviewed with. When the
  file is absent there, or the platform cannot read files (`--mr-data`, local git without a repository), it is
  skipped with a warning. A file over 16 KB is ignored, and entries past the first 20 are dropped, each with a
  warning. An entry without a name, file filters or text, and a file that is not valid YAML, are warned about and
  ignored.

## Review Learnings

`karandash calibrate --corpus DIR --learn` writes `ai.learnings.file` from reviewer feedback; the AI review then reads
it and applies it to its findings.

```yaml
ai:
  learnings:
    enabled: true                         # default true
    file: .karandash/learnings.yaml       # default; read from the MR's target branch
    min-rejections: 5                     # default 5; used by calibrate --learn
```

| Key | Default | Description |
|-----|---------|-------------|
| `ai.learnings.enabled` | `true` | Read the file and apply it. `false` skips the base-branch file read |
| `ai.learnings.file` | `.karandash/learnings.yaml` | Learnings file, read from the MR's target branch (the base commit), never the MR head, so an MR cannot quiet its own review |
| `ai.learnings.min-rejections` | `5` | Rejected findings a rule needs before `calibrate --learn` writes an entry for it (it also needs at least 80% of its labelled findings rejected) |

- **Only AI findings are affected.** Deterministic rules are never suppressed or downgraded by a learning, even when
  an entry names their rule id.
- An entry is `{rule, path?, action, evidence: {rejected, accepted}}`. `suppress` drops the finding, `downgrade`
  lowers it one severity level. A `path` is a glob relative to the repo root (a leading `./` or `/` is dropped; an
  entry containing `..` is ignored with a warning).
- When several entries match a finding the most specific wins: the longest `path` glob, then the entry without a path.
- The entries are also sent to the model as one never-cut instruction, with `rule` and `path` cleaned of backticks,
  newlines and control characters and cut at 200 characters.
- `calibrate --learn` merges into an existing file: entries without an `evidence` block are hand-written and kept,
  the rest are regenerated. It prints how many entries were kept, added and removed.

## Size Policy

When an MR is very large, sending it to an LLM can be wasteful - diffs get heavily truncated, context is poor, and API costs are high. The `size-policy` block provides two controls:

1. **Skip AI entirely** when the MR exceeds size thresholds
2. **Partial review** - review only the most important subset of files

### Skip Thresholds

If **any** threshold is exceeded, all AI rules are skipped and an INFO finding explains why.

```yaml
ai:
  provider: "openai"
  size-policy:
    skip:
      maxFiles: 500
      maxTotalLines: 50000
      maxCommits: 200
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `maxFiles` | int | - | Skip AI when the MR has more than this many changed files |
| `maxTotalLines` | int | - | Skip AI when total added + deleted lines exceed this value |
| `maxCommits` | int | - | Skip AI when the MR contains more than this many commits |

All fields are optional. Unconfigured thresholds are not checked.

### Exclude

Noise and generated files are excluded from AI analysis **unconditionally** (regardless of whether partial review is enabled). This prevents wasting AI tokens on files that provide no review value. Exclusion is active whenever `patterns` is non-empty.

```yaml
ai:
  provider: "openai"
  size-policy:
    exclude:
      patterns:
        - "**/generated/**"
        - "**/package-lock.json"
        - "**/Pods/**"
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `patterns` | list | See below | Glob patterns for files to exclude |

**Default patterns** (used when `patterns` is not specified):

- `**/generated/**`
- `**/*.generated.*`
- `**/package-lock.json`
- `**/*.lock`, `**/*.lockfile`
- `**/pnpm-lock.yaml`
- `**/go.sum`
- `vendor/**`, `third_party/**` (repository root only: a `vendor` package inside sources is reviewed), `**/node_modules/**`
- `**/*.min.js`, `**/*.min.css`, `**/*.js.map`
- `**/*.svg`

Binary assets (images, fonts) are dropped by the diff preprocessor's binary check. Every file left out this way
(and by the diff preprocessor or partial review) is named with its reason in the summary's AI coverage line.

### Partial Review

When the MR is large but below skip thresholds, partial review selects a subset of files for AI analysis. Exclusion is applied first, then incremental review drops files unchanged since the last review, then partial review limits the count among the files left.

```yaml
ai:
  provider: "openai"
  size-policy:
    exclude:
      patterns:
        - "**/generated/**"
    partial-review:
      maxFiles: 30
      strategy: "most-changed"
```

Presence of the `partial-review:` block enables partial review. Omit the block to disable it.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `maxFiles` | int | `30` | Maximum files to send to AI |
| `strategy` | string | `most-changed` | File selection strategy (see below) |
| `prioritize-types` | list | `[]` | Glob patterns for types to prioritize (used with `by-type`) |

**Strategies:**

| Strategy | Description |
|----------|-------------|
| `most-changed` | Select files with the most additions + deletions |
| `first` | Take the first N files in the order they appear |
| `by-type` | Prioritize files matching `prioritize-types` patterns, fill remaining slots with most-changed |

### Large MRs

A diff over the chunk size is split into chunks by **review unit**: the module a file belongs to (the path before
`src/` or `lib/`) or, outside a module, its directory without `test/`, `tests/`, `__tests__/` and `spec/`, so
tests travel with their sources (`src/test` with `src/main`, `test/` with `lib/`, `tests/x` with `x`). A test whose
own unit changed no source joins the unit of the one changed source it is named for (`tests/test_mod.py` with
`pkg/mod.py`, `foo_test.cc` with `foo.cc`). Units are ranked by risk (the riskiest file, then lines changed) and
the riskiest unit is chunked first. The chunks of one rule are sent in parallel, at most `max-parallel` at a time, and every
call still takes one of the `ai.concurrency` permits shared by all rules.

```yaml
ai:
  large-mr:
    max-units: 10     # more units than this makes the MR oversized
    max-parallel: 4   # chunks of one rule sent at once
    incremental: true # for an oversized MR, overrides ai.incremental.enabled; unset follows it
    test-gaps: true   # false drops the ai-test-gap summary lines
    chunk-context: true # a chunked diff wants one chunk, so context fits in every chunk call; false = old sizing
    max-call-tokens: 16000 # one chunked call: prompt, reserve and diff, at most the model context
    mr-map-max-files: 50 # each chunk lists up to 50 files other chunks carry, so it does not report them missing; 0 = no list
    scale-budget: true  # per-MR plan funds each rule by priority up to cost-budget (the one knob for review volume); false = one share for all chunks
    max-run-tokens: 500000 # without cost-budget, the most one MR's run grows to; 0 = no cap
```

All five keys are optional and inherit through `extends:`.

Once an AI rule ran, the summary comment also gets two kinds of INFO lines, worked out from the diff without a model
call and never posted inline:

- `ai-large-mr`: `Large MR: N review units, R reviewed, K not reviewed`, with both lists, when a budget or chunk
  skip left files of the MR unreviewed or the MR is over `max-units`; past `max-units` it also suggests splitting
  the MR. A unit whose files were all excluded, or none of them in the incremental delta, counts as neither reviewed
  nor not reviewed. The opt-in `ai-pr-split` rule still gives the model's split advice.
- `ai-test-gap`: one line per unit that changed production code and no test (`src/test`, `src/androidTest`,
  `tests/`, `*_test.cc`, `*Test.kt`, ...). Files matching `ai.size-policy.exclude.patterns` (generated, lock, vendor
  and asset files by default), deleted files, build scripts (`.kts`), TypeScript declarations (`.d.ts`) and non-code
  files never count; at most 10 lines. `test-gaps: false` turns them off.

### Diff Preprocessor

The `diff-preprocessor` block controls automatic diff optimization to reduce token consumption before diffs are sent to the LLM. Every filter is on by default except the two that hide removed code from the reviewer.

```yaml
ai:
  provider: "anthropic"
  size-policy:
    diff-preprocessor:
      skipDeletedFiles: false     # Remove entirely-deleted files
      deletedFileStubLines: 40    # A kept deleted file is cut to its first 40 deleted lines (0 = whole diff)
      skipPureRenames: true       # Remove renamed files with no change besides package/import lines
      stripDeletedLines: false    # Remove deleted (-) lines entirely
      stripDiffMetadata: true     # Remove --- a/, +++ b/, \ No newline lines
      skipBinaryDiffs: true       # Remove binary file diffs
```

| Option | Default | Savings | Description |
|--------|---------|---------|-------------|
| `skipDeletedFiles` | `false` | 5-15% on deletion-heavy MRs | Drops entirely-deleted files; when kept, each is a `deletedFileStubLines` stub |
| `deletedFileStubLines` | `40` | most of a deleted file | A kept deleted file's diff is cut to this many `-` lines plus a count of the rest; `0` keeps it whole |
| `skipPureRenames` | `true` | 1-5% on refactoring MRs | Renames and moves that change only `package`/`import` lines are noise; listed as `path (renamed from old)` in AI coverage |
| `stripDeletedLines` | `false` | 20-40% on modify-heavy MRs | Removes deleted (`-`) lines entirely; kept `-` lines do not shift the new-file line numbers inline comments use |
| `stripDiffMetadata` | `true` | 1-3% per file | `--- a/file`, `+++ b/file`, `\ No newline` lines |
| `skipBinaryDiffs` | `true` | Variable | AI cannot meaningfully analyze binary content |

File-level filters (`skipDeletedFiles`, `skipPureRenames`, `skipBinaryDiffs`) run before partial review, so filtered files don't count toward the partial review limit. Content-level transforms (`stripDeletedLines`, `stripDiffMetadata`) run inside each analyzer's prompt builder.

To disable all preprocessing (send raw diffs):

```yaml
ai:
  size-policy:
    diff-preprocessor:
      skipDeletedFiles: false
      skipPureRenames: false
      stripDeletedLines: false
      stripDiffMetadata: false
      skipBinaryDiffs: false
```

### DiffMode: ADDED_ONLY

In addition to the existing `changed`, `patch`, and `full` modes, Karandash supports `added_only` mode for maximum token reduction. This mode keeps only added (`+`) lines and hunk headers, replacing all context and deleted lines with empty lines.

```yaml
ai:
  reviewMode: "added_only"
```

This is an aggressive optimization - use it when you want AI to focus exclusively on new code and don't need context for the review.

### Example: Full Size Policy

```yaml
ai:
  provider: "anthropic"
  size-policy:
    skip:
      maxFiles: 500
      maxTotalLines: 50000
    exclude:
      patterns:
        - "**/generated/**"
        - "**/build/**"
        - "**/Pods/**"
    partial-review:
      maxFiles: 25
      strategy: "by-type"
      prioritize-types:
        - "*.kt"
        - "*.java"
    diff-preprocessor:
      skipDeletedFiles: true
      stripDeletedLines: true
```

### Interaction with Other Settings

- **Exclude runs unconditionally**, even when partial review is disabled. Auto-generated files never consume AI tokens.
- **Processing order:** per-rule file filters (`filters`/`ruleFilters`) → exclude → diff preprocessor (file-level) → partial review → snapshot delta check. Content-level preprocessing (strip deleted lines, metadata) runs inside each analyzer's prompt builder.
- **`context.budget`** sizes each AI request; see [Context budget](#context-budget). An explicit
  **`budget.max-input-tokens`** (less `budget.reserve-output-tokens`) is a ceiling over it and also sets the diff caps
  (that many tokens at 4 characters a token); without it the diff caps are 256,000 characters, a safety ceiling the
  context budget binds before. A file over its cap is cut on whole hunks.
  `budget.weights` overrides the priority of a context part by name (`crossFileContext`, `dependencyContext`,
  `enricherContext` or an enricher id). Prior findings (the do-not-repeat list) are part of the fixed prompt and never cut. The `limits.maxDiffCharsPerFile` and
  `limits.maxDiffCharsTotal` override the derived caps. Size policy controls whether to *run AI at all* and *how many files* to include.
- **Skip produces an INFO finding** (not an empty result) so users always see why AI was skipped.

## Context budget

`ai.context.budget` is the number of input tokens one AI request may use. It is the single knob the prompt is fit
into:

```yaml
ai:
  context:
    budget: auto      # default; or a token count such as 48000
```

- **`auto`** takes a quarter of the model's context window (Claude 200k, GPT-5 400k, GPT-4.1 and Gemini 1M,
  unknown models 128k), at most 64,000 tokens, and no more than `ai.costBudget.maxCostPerRunUsd` buys at the
  model's input price (`ai.pricing` or the built-in table).
- **A number** is used as given. `ai.budget.max-input-tokens`, when set, stays a ceiling over both.
- **Calibration.** Within one run the budget is converted by the ratio of provider-reported prompt tokens to the
  4-characters-a-token estimate of earlier requests to the same model (clamped to 0.5–2.0), so a denser tokenizer
  does not overshoot it.

The prompt is filled in priority order. The fixed part (system prompt, output contract, instructions, MR and ticket text,
prior findings) is never cut. The diff comes next and is never cut for context. The context then fills what is
left, most valuable tier first: deleted code, enclosing function (full-file snippets), symbol definitions
(symbols, cross-file and dependency context), callers and other related context (any other enricher block),
related tests (related files), conventions and design docs. From the first part that does not fit on, the
remaining parts share the room left equally: a part under its share stays whole and hands the rest on, a larger
one is cut to its share at a line boundary (marked `[cut to fit ai.context.budget]`) when that is at least 100
tokens, else dropped. So full-file snippets no longer crowd symbol definitions out of a chunked call.
`budget.weights` still orders parts inside one tier.

Each request logs one coverage line at INFO:

```
AI plugin [ai-review]: context budget 50000 tokens, used 41210 (82%), diff 30100 of 30100 tokens, 0 hunks cut; symbol_definitions kept 6200, cut 0, dropped 0; conventions_docs kept 900, cut 1400, dropped 0
```

The same line goes to the diagnostic JSON (`summary.ai.health.context`, keyed by rule) and to the
`--dump-dir` dispatch plan (`coverage`, with per-tier token counts).

When a reply uses its whole output cap (the provider stopped at `max_tokens`), the rule's diff is split in two on
file and hunk boundaries and each half is retried once; the findings of all three calls are merged and
deduplicated. A diff that cannot be split keeps the first reply.

## Token Budget

The `maxTokenBudget` (default: 50,000) is a shared budget across all AI rules for a single MR validation run. When the budget is exhausted, remaining AI rules are skipped. `KARANDASH_AI_MAX_TOKEN_BUDGET` overrides it; a call whose estimated tokens do not fit the remaining budget is not sent. At shutdown, the plugin logs the total tokens consumed and a cost estimate broken down by rule.

## Dry Run Mode

When `dryRunAi: true` (or `KARANDASH_AI_DRY_RUN=true`), no AI API calls are made.
Each rule still builds its prompts (preprocessing, enrichment, prompt processors and redaction run as usual), then
emits an INFO finding saying what would have been sent:

```
[DRY RUN] AI analysis skipped. Provider: openai, Model: gpt-4o-mini. Would send 1 request(s), 5230 chars (~1307 tokens)
```

This is useful for testing configuration and sizing prompts without incurring API costs.

## Secret Redaction

Before sending MR diff content to the AI provider, Karandash automatically redacts secrets and sensitive patterns via `SecretRedactor`. This prevents accidentally exposing credentials or tokens in AI prompts.

## Code Quality Rule (`ai-code-quality`)

The code quality rule performs a multi-dimensional assessment of the code changes in an MR. Enable it with `ai.features.codeQuality: true`. Findings carry rule ID `code-quality`; the diff in the prompt is the pipeline's prepared (redacted, size-capped) diff.

### Scoring Dimensions

| Dimension | What it measures |
|-----------|-----------------|
| `correctness` | Logic completeness, edge case handling, null safety, off-by-one errors |
| `errorHandling` | Exception handling quality, graceful degradation, error propagation |
| `readability` | Naming clarity, structure, formatting consistency, cognitive complexity |
| `maintainability` | Modularity, coupling/cohesion, DRY adherence, extensibility |
| `performance` | Algorithm efficiency, unnecessary allocations, N+1 patterns |
| `security` | Input validation, injection risks, secrets handling |
| `design` | Design patterns, abstraction quality, SOLID principles, architecture fit |

Each dimension is scored 0.0-1.0. The overall score is a weighted average (correctness and security weighted higher).

### Severity Mapping

Lower scores indicate worse quality (inverted from risk scorer):

- `<= 0.3` (configurable via `thresholds.codeQualityError`) -> **ERROR** (poor quality)
- `<= 0.6` (configurable via `thresholds.codeQualityWarning`) -> **WARNING** (needs improvement)
- `> 0.6` -> **INFO** (good up to 0.8, excellent above)

The response token cap is `limits.codeQualityMaxTokens` (default 2000).

### Output

- **Summary finding**: Overall score, all dimension scores in metadata, strengths list
- **Per-issue findings**: Up to 8 specific code quality issues with file/line locations and suggestions

### Code Maturity Level (Optional)

When `codeQualityIncludeMaturityLevel: true`, the rule additionally assesses the maturity level of the code patterns (junior/mid/senior/staff). This describes the **code**, not the developer. Requires both the rule enabled AND the maturity flag set.

### Configuration Example

```yaml
ai:
  provider: anthropic
  model: claude-sonnet-5-5
  features:
    codeQuality: true
  thresholds:
    codeQualityWarning: 0.6
    codeQualityError: 0.3
  # Optional: enable code maturity level assessment
  codeQualityIncludeMaturityLevel: true
```

## Ownership Analysis Rule (`ai-ownership-analysis`)

The ownership rule assesses code-ownership health for the changed files. It is **opt-in** (default off; enable with `ai.features.ownershipAnalysis: true`, ~3000 tokens). Findings carry rule ID `ownership-analysis`.

### Input

- Per-file blame summaries (lines per author) from `FileChange.blameData`. When this rule is enabled the pipeline fetches blame at the MR base commit for up to 50 modified or deleted files, on platforms that implement `fetchFileBlame` (GitLab, GitHub, Bitbucket, Gitea, Azure DevOps, Gerrit, AWS CodeCommit). Without blame data the prompt says so and the model gives a conservative estimate.
- Bug-fix signals: commits whose message contains `fix`, `bug`, `revert`, `hotfix`, `patch`, `resolve` or `regression`.
- Reviewers, approvers, the first 15 commits and the prepared diff.

### Severity Mapping

- score below `thresholds.ownershipWarning` (default `0.7`) or `thresholds.ownershipError` (default `0.4`), whichever is
  higher, or cross-team changes -> **WARNING**
- otherwise -> **INFO**

The summary never goes above WARNING: the score describes who owns the code, not a defect in the change.
`thresholds.riskError` applies only to risk scoring.

The response token cap is `limits.ownershipMaxTokens` (default 2000).

### Output

- **Summary finding**: ownership score, contributor count, cross-team flag, bug-fix statistics and knowledge risks in metadata (no names or emails)
- **Bug-fix findings**: up to 5 WARNING findings naming the file (`file` metadata), never who introduced or fixed the issue

No email address is sent to the model; blame is summarised as lines per author name.

### Configuration Example

```yaml
ai:
  features:
    ownershipAnalysis: true
  thresholds:
    ownershipWarning: 0.7
    ownershipError: 0.4
```

## Notes

- The AI module requires a valid API key for providers other than Ollama and LiteLLM (when no key is configured). If no key is found and none is provided in config, the module logs a warning and does not initialize.
- Diff content is filtered by file include/exclude patterns before being sent to the AI provider, allowing you to focus AI analysis on specific parts of the codebase.
- Custom AI rules can be defined in the plugin config alongside the built-in rules.
