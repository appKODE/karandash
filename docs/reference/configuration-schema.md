# Configuration Schema Reference

Complete reference for all Karandash configuration options.

Karandash supports three configuration formats: **Kotlin DSL** (`.karandash/config.kts`), **YAML** (`.karandash/config.yaml`), and **JSON** (`.karandash/config.json`). The Kotlin DSL is the primary format. YAML and JSON equivalents are shown where applicable.

## Top-Level Structure

| Block | Type | Required | Description |
|-------|------|----------|-------------|
| `extends` | string, object, or array | No | Config inheritance (see [Config Inheritance](../features/config-inheritance.md)) |
| `rules` | object | No | Validation rules configuration |
| `policies` | array | No | Conditional overrides applied when a `when:` expression matches (see [Policies](#policies)) |
| `platforms` | object | No | Platform configuration with inline authentication |
| `reporters` | object | No | Output and reporting configuration |
| `plugins` | object | No | Plugin management settings |
| `scripts` | array | No | Custom JavaScript validation scripts: a list of entries with `path`, optional `mode`, `timeout` and `env` (allowed env var names) (see [Scripts](../configuration/scripts.md)) |
| `ai` | object | No | AI-powered analysis and enrichment settings |
| `auth` | object | No | Named credentials for remote `extends:` parents and `{ file: }` references. Merged per entry name through `extends:` (a child entry replaces only the fields it sets) |
| `env` | object | No | Default values for `${VAR}` interpolation, used when the variable is unset. Denylisted variables stay unset |
| `profile` | string or object | No | Profile to apply (see [Profiles](../features/profiles.md)) |
| `profile-overrides` | object | No | Threshold overrides merged into rule configs after profile resolution |
| `severity-cap` | string | No | Shadow mode: findings more severe than this severity (`error`, `warning` or `info`) are lowered to it; `rules.<id>.severity-cap` caps one rule (see [YAML config](../configuration/YAML_CONFIG.md)) |
| `patterns` | object | No | User-defined named patterns, used in rule patterns as `@name` (see [Patterns](patterns.md)) |
| `baseline` | string | No | Fingerprint file of findings that are not reported; the `--baseline` flag wins. `--update-baseline` rewrites it and needs a path from the flag or this key |
| `dangerfile` | string | No | Path to a `Dangerfile.js` or `Dangerfile.df.kts` run as part of validation (see [Dangerfile](../configuration/dangerfile.md)) |
| `auto-approve` | object | No | Approve the MR when findings stay below a severity threshold |
| `enrichment` | object | No | Pre-validation enrichment stage and provider configs (see [Enrichment](../features/enrichment.md)) |
| `rule-enrichment` | object | No | Per-rule enrichment bindings: rule ID to `enrich:` list of enrichment IDs or globs |
| `cache` | object | No | Cache settings (see [Cache](#cache)) |
| `timeouts` | object | No | Timeout settings |
| `http` | object | No | HTTP client settings (retry, pagination, response cache) |
| `limits` | object | No | Safety limits for expressions, `extends:`, globs, regexes and patterns (see [Limits](../configuration/YAML_CONFIG.md#limits)) |
| `display` | object | No | Display preferences (git SHA length, watcher debounce, console marks) |
| `report` | object | No | Report preferences: `language` (BCP-47 tag, default `en`) for AI messages, the markdown summary and inline-review lines (strings in English, other languages from a translation pack, Russian and Polish built in; `ai.response-language` is its alias, see [Report language](../configuration/YAML_CONFIG.md#report-language)); `transparency` (`summary: off\|collapsed\|expanded`, `inline-why`, `flow-diagram`) `walkthrough` (`off\|collapsed`) and `style` (`quiet\|standard\|verbose`, default `standard`; see [Report style](../configuration/YAML_CONFIG.md#report-style)) (see [Report transparency](../configuration/YAML_CONFIG.md#report-transparency)) |
| `security` | object | No | Network security: `blocked-hosts` (extra hostnames every outbound URL check refuses, see [Security](#security)) |
| `diagnostics` | object | No | Runtime diagnostics (memory and CPU monitoring) |
| `dry-run` | boolean | No | Default `false`. Sets the `is_dry_run` condition; with `dry-run-scope` it also suppresses work (see [Dry run](../configuration/YAML_CONFIG.md#dry-run)) |
| `dry-run-scope` | string | No | `ai` or `all`: what `dry-run: true` suppresses. Unset suppresses nothing |
| `options` | block | No | Kotlin DSL only: the keys inside `options { }` are read as top-level keys. YAML and JSON write them at the top level |
| `$schema` | string | No | JSON Schema URL for editors. Not read by Karandash |
| `version` | string or number | No | Config schema version (`major.minor`, currently `1.0`; `init` writes it). A version newer than the running karandash supports fails the load naming the upgrade; an equal or lower one is accepted. A non-numeric value fails the load |
| `allow-private-network` (`allow_private_network`, `allowPrivateNetwork`) | boolean | No | Default `false`. Allows private and dotless hosts for outbound URLs (self-hosted services on an internal network); cloud-metadata, link-local and loopback hosts stay blocked and scheme rules are unchanged (configured URLs must be HTTPS; redirects to `http://` on a permitted host are followed). An `extends:` parent's value applies to the run like the root's own (a root `false` overrides it); while the `extends:` chain is fetched, a parent's value applies to fetching its own parents unless a layer closer to the root sets it. Applies to the whole run. See [Network access](../configuration/YAML_CONFIG.md#network-access-ssrf-protection) |
| `allow-localhost` (`allow_localhost`, `allowLocalhost`) | boolean | No | Default `false`. Disables all SSRF checks (loopback, private, HTTP); local development only. Inherits through `extends:`: a parent's value applies to the run and to fetching its own parents |

Keys may be written in kebab-case, snake_case or camelCase. Any other top-level key gives an unknown-key warning (E0007), unless a loaded plugin reads it.

Platform credentials are configured **inline** within the `platforms:` block. The top-level `auth:` block holds credentials for fetching config files (remote `extends:` parents, `{ file: }` references), not for the platform API.

```kotlin
karandash {
    rules { /* ... */ }
    platforms { /* ... */ }  // Authentication configured here
    reporters { /* ... */ }
    plugins { /* ... */ }
    scripts { /* ... */ }
    ai { /* ... */ }      // AI rules and enrichment
}
```

---

## AI Keys

Keys of the `ai:` block, in any spelling (kebab-case, snake_case, camelCase). An object-valued key not listed here is
read as a per-analyzer block (`<analyzer-id>` row).

<!-- keys: ai -->
| Key | Type | Description |
|-----|------|-------------|
| `enabled` | bool | Default `true`. `false` turns AI off: the block is ignored, so no AI rules, provider checks, AI state or AI findings |
| `provider` | string | Default LLM provider (default `openai`) |
| `api-key` | string | Provider API key; prefer `${ENV_VAR}` interpolation |
| `base-url` | string | Provider endpoint override (self-hosted or proxy) |
| `model` | string | Default model; unset uses the provider's default |
| `model-fallback` | list | Models tried in order when the primary model fails |
| `models` | object | Per-analyzer model, analyzer ID to model |
| `temperatures` | object | Per-analyzer temperature (0.0-2.0) |
| `providers` | object | Per-provider settings, provider ID to its block |
| `rule-providers` | object | Per-analyzer provider, analyzer ID to provider ID |
| `features` | object | Analyzer on/off switches, analyzer ID to boolean (same as `ai.<analyzer-id>.enabled`) |
| `timeout` | duration | Provider request timeout; a bare number is seconds |
| `max-retries` | int | Retries of a failed provider call after the first attempt (default 2; 0 = one attempt) |
| `ollama` | object | `allow-hosts`: hosts the `ollama` provider may reach besides loopback, private-network and single-label ones, matched by name (default `[host.docker.internal]`; a set list replaces it) |
| `execution-timeout` | duration | Wall-clock cap for the whole AI stage |
| `concurrency` | int | Concurrent LLM calls (default 20) |
| `fail-on-error` | bool | Default `false`. Fail the run when an AI call fails instead of logging and continuing |
| `dry-run` | bool | Default `false`. Build prompts without calling the provider |
| `strictness` | string | Review strictness preset (default `standard`) |
| `review-mode` | string | Which diff lines are reviewed (default `changed`) |
| `context-lines` | int | Diff context lines fetched around each hunk |
| `best-practices` | string or `{ file: }` | Project best practices added to every prompt |
| `extra-instructions` | string or `{ file: }` | Extra instructions added to every prompt |
| `prompts` | object | Prompt overrides per analyzer |
| `prompt-overrides` | object | Prompt text overrides, prompt ID to text |
| `system-prompts` | object | System prompt overrides per analyzer |
| `context` | object | Named context strings added to prompts |
| `finding-template` | string | Template for AI finding messages |
| `reasoning` | string | Reasoning style (default `direct`); per analyzer via `ai.<analyzer-id>.reasoning` |
| `reasonings` | object | Per-analyzer reasoning, analyzer ID to style (same as `ai.<analyzer-id>.reasoning`) |
| `analyzerMaxTokens` | object | Per-analyzer token budget, analyzer ID to tokens (same as `ai.<analyzer-id>.max-tokens`; default: the analyzer's estimate) |
| `structured-output` | bool | Default `true`. Constrain analyzer replies to their result schema where the provider supports it; per analyzer via `ai.<analyzer-id>.structuredOutput` |
| `structuredOutputs` | object | Per-analyzer structured output, analyzer ID to bool (same as `ai.<analyzer-id>.structuredOutput`) |
| `prompt-cache-ttl` | string | Anthropic prompt-cache lifetime, `5m` (default) or `1h` |
| `reasoning-effort` | string | Unset by default. OpenAI-compatible providers send it as OpenRouter `reasoning: {effort}` (`none`, `minimal`, `low`, `medium`, `high`); lower it when a reasoning model spends the whole output cap on hidden reasoning and returns no content |
| `shared-system-prompt` | bool | Default `false`. One common system prompt for every analyzer; each analyzer's instructions move after the shared MR context so the cached prefix is shared across analyzers |
| `stream` | bool | Default `false`. Stream completions (SSE / NDJSON) so long generations are guarded by an idle timeout; text and usage match the non-stream result |
| `stub` | object | Options of `provider: stub`: `replyFile`, one reply or a JSON map by model / system-prompt key / `default`; no network, usage 0 |
| `max-token-budget` | int | Token budget per run (default 50000). With `large-mr.scale-budget` (default) it only seeds each rule's starting share: how much an MR gets reviewed is set by `cost-budget`, and the plan's `[ai] budget plan` line names the value that covers the whole MR |
| `context` | object | `budget`: input tokens one AI request may use, `auto` (default: a quarter of the model window, at most 64000, within the cost cap) or a count; see [Context budget](../rules/ai-rules.md#context-budget); also `stripImports` and `importGraph` (booleans, default `true`, see [AI review precision](#ai-review-precision)) |
| `budget` | object | `max-input-tokens`: optional ceiling over `context.budget`; `reserve-output-tokens`: output tokens every call keeps free (default `2000` when unset or `0`); context tier weights |
| `cost-budget` | object | Per-run token cap (`max-tokens-per-run`, default 50000 even in a block that sets only `max-cost-per-run-usd`) and USD cap; with `scale-budget` it bounds the per-MR growth (a run budget above it is cut to it, with a warning); without it the run grows to every rule's need |
| `pricing` | object | Model ID (or prefix) to USD per million tokens, overriding the built-in prices |
| `limits` | object | Diff size, chunking and per-analyzer limits. `reviewThreadAnalysisMaxTokens`, `designDocDriftMaxTokens`, `generateDescriptionMaxTokens`, `mrSummaryMaxTokens`, `prSplitMaxTokens` and `outdatedDocsMaxTokens` cap those analyzers' replies; unset, they fall back to `descriptionMaxTokens` (review-thread analysis) or `reviewMaxTokens` (the others), which warns once |
| `thresholds` | object | Per-analyzer score thresholds |
| `max-comments` | int | Soft cap on review comments per MR (default 10): repeats collapse first, then the rest rank by severity, category and confidence; BUG/SEC/DATA/LEAK comments are never cut by it |
| `max-comments-hard` | int | Optional hard cap on review comments per MR, every category included (the worst and surest kept); unset by default, so there is no hard cap |
| `split-threshold` | int | Change count above which an MR split is suggested |
| `code-quality-include-maturity-level` | bool | Add the maturity level to the code-quality report |
| `size-policy` | object | Skip, exclude and partial-review policies for large MRs |
| `chunking` | object | Chunk size, overlap and pass count (`max-chunks`, default 10, same as `limits.maxChunks`) for large diffs; unset uses `ai.limits`. `chunk-size` is a ceiling: the chunk is what one call holds (`large-mr.max-call-tokens` with `large-mr.scale-budget`, else its share at most that; at most the model context; less the prompt and output reserve, half of it with `large-mr.chunk-context`) at the diff's own characters per token; a larger `chunk-size` is capped there with a warning, a chunk still over it (one huge hunk) is skipped and reported, never cut |
| `incremental` | object | `enabled: false` reviews the whole diff on every run instead of only the delta |
| `large-mr` | object | Oversized-MR threshold, chunk parallelism and incremental override (see [`ai.large-mr`](#ailarge-mr)) |
| `filters` | list | File globs the AI review is limited to |
| `rule-filters` | object | Per-analyzer file globs |
| `filter` | object | Post-processing filter for AI findings |
| `mode` | string | Default analyzer mode |
| `priority` | string | Accepted, no effect; rank analyzers with `ai.<analyzer-id>.priority` (integer) |
| `priorities` | object | Analyzer or rule ID to integer rank for the token budget; higher is funded first, `ai.<analyzer-id>.priority` wins on conflict |
| `concern-area` | string | Default analyzer concern area |
| `rules` | object | Per-rule AI strategy, rule ID to strategy block |
| `hooks` | object | Hook name to `{ action }` (`log`, ...) |
| `scopes` | object or list | Path-scoped prompt overlays (see [AI Scopes](#ai-scopes)) |
| `review` | object | Path-scoped project review instructions, sent when their paths match |
| `learnings` | object | The `calibrate --learn` file read from the MR base (see [`ai.learnings`](#ailearnings)) |
| `thread-commands` | object | Which `@karandash` thread commands are answered (see [`ai.thread-commands`](#aithread-commands)) |
| `checks` | list | Pre-merge checks answered pass, fail or n/a and shown as a checklist (see [`ai.checks`](#aichecks)) |
| `checks-ticket-compliance` | bool | Default `false`. Add a checklist row per acceptance criterion of the linked ticket |
| `enrichment` | object | Pre-validation title and description generation (see [AI Enrichment](#ai-enrichment)) |
| `enricher` | object | Knowledge-context enricher settings |
| `grounding` | object | Grounding of findings in the diff; `windowLines`, `negatedClaims`, `evidenceCheck` and `miss` are in [AI review precision](#ai-review-precision) |
| `reviewSampling` | object | `ai.review-comments.samples` (default `2`) and `ai.review-comments.sample-temperature` (default `0.5`); see [AI review precision](#ai-review-precision) |
| `agreement` | object | How two review-comments samples are matched (see [AI review precision](#ai-review-precision)) |
| `ranking` | object | Score weights and protect conditions of the finding caps (see [AI review precision](#ai-review-precision)) |
| `dedup` | object | Cross-rule in-run dedup of AI findings (see [AI review precision](#ai-review-precision)) |
| `hints` | object | Deterministic hint block of review-comments (see [AI review precision](#ai-review-precision)) |
| `testGap` | object | `level`: `mr` (default) or `file` (see [AI review precision](#ai-review-precision)) |
| `streamDeadline` | duration | Default `5m`. Longest a streamed call may run in total before it is retried |
| `streamStall` | duration | Default `60s`, at most `ai.timeout`. Longest silence between two chunks of a streamed call before it is retried. Not enforced by the macOS and Linux native builds: their curl HTTP engine has no idle timeout, so there `streamDeadline` bounds a silent stream |
| `constitution` | object | Project constitution checked by the AI review |
| `context-quality` | object | Smart truncation, related files, full-file context, convention detection, finding validator |
| `convention-context` | object | Convention detection context |
| `cross-file-context` | object | Add related repository files (imports, tests) to the prompt |
| `cross-file-context-max-chars` | int | Character cap for the cross-file context |
| `correlation` | object | Merge same-location findings across analyzers; compound-risk patterns |
| `contradictions` | object | Contradiction removal and how a contradictory pair is resolved |
| `custom-rules` | list | User-defined AI rules: `id`, `name`, `prompt`, `severity`, `max-tokens`, `temperature`, `enabled`, `priority` (integer rank in the `scale-budget` plan) |
| `gates` | list | Quality gates over AI findings |
| `enforcement-schedule` | list | Phased enforcement (warn, then error) by date |
| `severity-overrides` | list | Severity overrides for AI findings |
| `grace-periods` | list | Grace periods for rules |
| `grace-period` | object | Approval-gated grace window opened by a description tag |
| `conditional-sections` | list | Prompt sections added when a condition holds |
| `compositions` | list | Rules composed from other rules' findings |
| `seniority-tiers` | list | Author seniority tiers that adjust the review |
| `teams` | list | Team definitions for routing and notifications |
| `notifications` | list | Notification routes for AI findings |
| `tracks` | list | Branch-specific rule tracks |
| `environment-tracks` | list | Short form of `tracks`: `{ target-branch, rules }` |
| `macros` | object | Short form of `macro-definitions`: macro name to a list of `{ rule, ...settings }` |
| `macro-definitions` | object | Named rule-setting macros |
| `apply-macros` | list | Short form of `macro-references`: macro names applied to all files |
| `macro-references` | list | Macros applied by file glob |
| `adrs` | list | Architecture decision records checked as constraints |
| `canary` | object | Canary rollout per rule |
| `violation-budgets` | list | Tolerated finding counts per rule |
| `violation-budget` | object | Per-file baseline of tolerated findings |
| `execution-profile` | string | Name of the execution profile to apply |
| `execution-profiles` | object | Named execution profiles |
| `consensus-models` | list | Two or more: each custom AI rule runs once per model and keeps the findings enough models report |
| `consensus-strategy` | string | Default `intersection` (every model must report the finding); `majority` (more than half), `first_agreement` (any two) |
| `analyzer-consensus` | bool | Default `false`. Also vote on built-in analyzers that review the diff; MR-level ones (summary, risk score) ask one model |
| `consensus` | object | `models` and `strategy`, same as `consensus-models` and `consensus-strategy` (the long form wins when both are set) |
| `feedback` | object | Feedback store settings (`enabled`, `skip-repeat-finding-types`, `reply-judge`) |
| `feedback-enabled` | bool | Same as `feedback.enabled` |
| `feedback-skip-repeat-finding-types` | bool | Same as `feedback.skip-repeat-finding-types`: skip a rule once its findings were dismissed repeatedly |
| `feedback-reply-judge` | string | Same as `feedback.reply-judge`: `off` (default) or `llm` asks the model about replies the keyword classifier leaves unknown (at most 50 calls per run, not in a dry run) |
| `pgo-enabled` | bool | Default `false`. Profile-guided ordering of analyzers |
| `analyzer` | object | Analyzer settings |
| `defect-predictor` | object | Defect predictor settings |
| `pipeline` | object | AI pipeline thresholds, clone detection and reports |
| `statistics` | object | Statistics history settings |
| `research` | object | Research mode settings |
| `debug` | object | `prompt: artifact` dumps each sent request to files |
| `process-risk` | object | Score process risk and report it as an INFO finding |
| `routing` | object | Cheap-model triage, escalating to the strong tier by process risk |
| `inline-overrides` | object | `@karandash override <rule>: <reason>` description tags |
| `temporal` | object | Cross-MR repeat-violation escalation |
| `calibration` | object | End-of-run rule calibration report from the statistics history |
| `sandbox` | object | `karandash test-rule` defaults |
| `clone-detection` | object | Clone detection settings for the metrics enricher |
| `naturalness` | object | Naturalness metrics for the metrics enricher |
| `architecture` | object | Architecture checks of the pipeline extension |
| `dependencies` | object | Dependency checks of the pipeline extension |
| `mutation-testing` | object | Mutation testing settings |
| `response-language` | string | Alias of [`report.language`](#report) |
| `<analyzer-id>` | object | Per-analyzer block: `enabled`, `model`, `temperature`, `reasoning`, `mode`, `priority` (integer rank in the `scale-budget` plan, higher funded first), `concern-area`, `min-severity`; other keys warn as unsupported |

### AI review precision

<!-- keys: ai.reviewSampling, ai.agreement, ai.ranking, ai.dedup, ai.hints, ai.testGap, ai.grounding -->
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `review-comments.samples` | int | `2` | Calls per review-comments chunk, 1 to 3. Extra samples run after every first call on a budget of their own (the rule's grant per sample), so they never cut coverage; measured cost about +45% per MR, most of the sample-2 prompt read from the provider prefix cache; `1` restores a single call |
| `review-comments.sample-temperature` | number | `0.5` | Temperature of samples 2 and later, 0 to 2 |
| `agreement.lineWindow` | int | `3` | Largest line distance of a pair of findings from two samples |
| `agreement.minSimilarity` | number | `0.3` | Overlap of the messages' words of 4+ letters a pair needs, 0 to 1 |
| `agreement.singleton` | string | `demote` | A finding only one sample reported: `demote` (INFO), `drop` or `keep`; BUG/SEC are never dropped unless `drop` |
| `grounding.windowLines` | int | `40` | A backticked name must appear within this many lines of the anchor (or in the file's diff) or the finding is demoted |
| `grounding.negatedClaims` | bool | `true` | Refute "undefined / not found" claims about a name present in the MR |
| `grounding.evidenceCheck` | bool | `true` | Refute a finding whose quoted `evidence` line is absent from the file; demote one not within 2 lines of the anchor |
| `grounding.miss` | string | `demote` | `demote` or `drop` a finding that misses the local check; never a BUG/SEC/DATA/LEAK finding |
| `ranking.weights.agreed` / `grounded` / `security` | int | `2` / `1` / `1` | Score weights the caps rank by |
| `ranking.protect` | list | `[agreed, grounded]` | Conditions (`agreed`, `grounded`) a BUG/SEC/DATA/LEAK finding must all meet to be kept past `maxComments`; a finding without the metadata is neutral |
| `dedup.crossRule` | bool | `true` | Merge similar findings of different AI rules in one file |
| `dedup.lineWindow` | int | `3` | Line distance for that merge |
| `hints.enabled` | bool | `true` | Send the deterministic moved/defined identifier hints to review-comments |
| `hints.maxTokens` | int | `400` | Upper bound of the hint block |
| `testGap.level` | string | `mr` | `mr`: a test anywhere in the MR means no test-gap line; `file`: per review unit |

A bad value is a `ConfigParseException` naming the key. `ai.context.stripImports` (default `true`) leaves import/package lines out of windowed file context and `ai.context.importGraph` (default `true`) adds the import graph to cross-file context.

### `ai.learnings`

<!-- keys: ai.learnings -->
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | bool | `true` | Read the learnings file from the MR base (target branch, never the head), send it as guidance and apply it to the findings |
| `file` | string | `.karandash/learnings.yaml` | Path of the file `calibrate --learn` writes |
| `min-rejections` | int | `5` | Rejected-finding floor `calibrate --learn` needs before it writes an entry |

### `ai.thread-commands`

<!-- keys: ai.thread-commands -->
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `ask` | bool | `false` | Answer `@karandash ask <question>` in a reply to a bot thread (one model call from the run's AI budget); off, it gets a short "disabled" reply. `@karandash why` needs no model and is always on with `ai.feedback` |

### `ai.large-mr`

<!-- keys: ai.large-mr -->
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `max-units` | int | `10` | An MR with more review units (modules or directories) is oversized: the summary suggests splitting it and `incremental` applies |
| `max-parallel` | int | `4` | Chunks of one rule sent at once; each call still takes a permit of `ai.concurrency` |
| `incremental` | bool | unset | For an oversized MR, overrides `ai.incremental.enabled`; unset follows it |
| `test-gaps` | bool | `true` | The summary gets a test-gap line per unit that changed production code and no test |
| `chunk-context` | bool | `true` | A chunked call gives half its diff room to context (cross-file, enricher blocks) instead of filling it with diff |
| `mr-map-max-files` | int | `50` | Each chunk's call lists up to this many files other chunks carry (path, +/- lines), so the model does not report them missing; `0` sends no list |
| `max-call-tokens` | int | `16000` | Most tokens one chunked AI call is sized to (prompt, output reserve and diff). With `scale-budget` every chunked call is this size, at most the model context; off, a rule's share at most this. Lower it to spend less per call, raise it for fewer, bigger calls; under 1 = default with a warning |
| `scale-budget` | bool | `true` | Per-MR budget plan: a chunked rule gets one call per chunk, each `max-call-tokens` at most the model context, never the rule's smaller share (at least its prompt, output reserve and 500 diff tokens; skipped with a reason when the context cannot hold that), so a big diff goes in more calls, not a bigger one; a whole-MR rule gets its prompt, output reserve and the MR's diff, at most the model context; funded by priority, highest first (`ai.<analyzer>.priority` or a custom rule's `priority`; default review-comments 2, whole-MR rules 1, the rest 0), and when chunks still do not all fit, the chunks with the highest-scored files (`truncation.priority` weights, hotspot included) first (the run budget grows by the same tokens), capped by `cost-budget.max-tokens-per-run` and `max-cost-per-run-usd`; without `cost-budget` every rule is funded in full up to `max-run-tokens`; one line before any call (`[ai] budget plan: N calls, T tokens, ...`, INFO when every rule is funded, WARN with each short rule's funded % and the `cost-budget` or `max-run-tokens` value that covers the MR); `false` = every rule keeps one share |
| `max-run-tokens` | int | `500000` | Without `cost-budget`, the most tokens `scale-budget` grows one MR's run to (never below the run budget already set); past it the lowest-priority rules are short and one warning says so; `0` = no cap |

Values under 1 for `max-units`, `max-parallel` or `max-call-tokens` warn and fall back to the default.

### `ai.checks`

A list of pre-merge checks the model answers pass, fail or n/a for in one call, shown as a checklist.

<!-- keys: ai.checks[] -->
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `name` | string | required | Row label in the checklist |
| `instruction` | string | required | What must hold for the check to pass |
| `paths` | list of globs | `[]` | When set and no changed file matches, the check is n/a without asking the model |
| `mode` | `warn` or `error` | `warn` | `error` makes a failing check an ERROR finding (non-zero exit); `warn` a WARNING |

---

## AI Enrichment

Pre-validation AI enrichment generates MR titles and descriptions before rules run. This is opt-in and configured under the `ai:` block.

### YAML

```yaml
ai:
  provider: anthropic
  model: claude-sonnet
  enrichment:
    generate-description: true
    generate-title: true
    skip-if-description-exists: true
```

### JSON

```json
{
  "ai": {
    "provider": "anthropic",
    "model": "claude-sonnet",
    "enrichment": {
      "generate-description": true,
      "generate-title": true,
      "skip-if-description-exists": true
    }
  }
}
```

<!-- keys: ai.enrichment -->
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `generate-description` | bool | `false` | Generate MR description using AI |
| `generate-title` | bool | `false` | Generate MR title using AI |
| `skip-if-description-exists` | bool | `true` | Skip generation when description is already present |
| `model` | string | `null` | Model for the description and title generation calls; unset uses the routed `ai.model` |
| `max-tokens` | int | `2000` | Response token limit for the description and title generation calls |

Enrichment can also be triggered via CLI flags `--enrich` and `--dry-run-enrich` without config file changes.

### AI Scopes

Path-based prompt overlays for monorepo and multi-stack projects. When an MR touches files matching a scope's patterns, that scope's instructions are added to the AI prompt. Multiple scopes can match simultaneously.

```yaml
ai:
  provider: anthropic
  scopes:
    android:
      paths: ["android/**", "app/src/**/*.kt"]
      instructions: |
        Check Activity/Fragment lifecycle issues.
        Flag missing null checks in Java interop.
      best-practices: "Use ViewModel for UI state."
    core:
      paths: ["core/cpp/**", "**/*.cpp", "**/*.h"]
      instructions: "Check memory safety and ABI compatibility."
      best-practices: "Use RAII. Prefer std::unique_ptr."
```

| Field | Type | Required | Description |
|-------|------|:--------:|-------------|
| `paths` | list of strings | Yes | Glob patterns that trigger this scope |
| `instructions` | string | No | Scope-specific review instructions |
| `best-practices` | string | No | Scope-specific best practices |

When no scopes match, built-in language detection provides language-specific overlays as fallback. See [AI Rules](../rules/ai-rules.md#scope-specific-prompt-customization) for details.

---

## Extends

Config inheritance allows you to build on shared base configurations. See [Config Inheritance](../features/config-inheritance.md) for detailed documentation.

### String Form

```yaml
extends: "https://example.com/base-config.yaml"
```

### Object Form (with Auth)

```yaml
extends:
  url: "https://private.gitlab.com/config.yaml"
  auth:
    type: bearer-token
    token: "${GITLAB_TOKEN}"
```

**Auth types**: `bearer-token`, `basic-auth`, `custom-header`

Keys of an object entry (any spelling):

<!-- keys: extends[] -->
| Key | Type | Description |
|-----|------|-------------|
| `url` | string | Remote URL, local path or `@manifest` reference. One of `url` or `pattern` is required |
| `pattern` | glob | Local glob expanding to several config files |
| `if` | expression | Condition; the entry is skipped when it is false. `${VAR}` is resolved by the condition evaluator (unset compares as `""`) |
| `cache` | object | Cache settings of a remote parent (below) |
| `merge-strategy` | object | Field path to merge mode, overriding the default merge for that field |
| `file-resolution` | string | How `{ file: }` references in a remote parent resolve: `local` (default), `fallback-remote`, `fallback-local`, `strict` |
| `auth` | object or string | Credentials for fetching this `url` (see below), or the name of an `auth.<name>` entry in the same file |
| `trust` | boolean | Lets `{ file: }` values of this remote parent read local files outside the project root, absolute paths included (default `false`) |

<!-- keys: extends[].cache -->
| Key | Type | Description |
|-----|------|-------------|
| `ttl` | string | Cache lifetime of a remote parent (seconds or duration, default `1h`; `0s` disables, `-1`/`never` caches forever) |

<!-- keys: extends[].auth -->
| Key | Type | Description |
|-----|------|-------------|
| `type` | string | `bearer-token`, `basic-auth` or `custom-header` |
| `token` | string | `bearer-token`: the token |
| `username` | string | `basic-auth`: user name |
| `password` | string | `basic-auth`: password |
| `name` | string | `custom-header`: header name |
| `value` | string | `custom-header`: header value |

### Array Form (Multiple Extends) - NEW

```yaml
extends:
  - "https://company.com/base.yaml"
  - "https://company.com/security.yaml"
  - url: "https://private.com/team.yaml"
    auth:
      type: bearer-token
      token: "${TOKEN}"
```

**Merge order**: Left-to-right (element 0 is base, later elements override)
**Maximum**: 10 entries (error E0014 if exceeded)

### Merge Rules

- Objects merge deeply: the child's value wins key by key, and keys only the parent sets are inherited.
- Arrays are replaced: a child's `scripts`, `ai.gates`, `ai.customRules`, `ai.notifications`, `ai.tracks` or any rule's list (for example `jiraTicket.projectKeys`) replaces the parent's list as a whole; nothing is appended or merged by index. To extend a parent's list, repeat its items in the child.
- `policies` is the exception: policies merge by `name` (or by `when` when unnamed). A child policy with the same name replaces the parent's; other policies from both are kept.

---

## Auth

Named credentials (`auth.<name>`) for remote `extends:` parents (an entry written `auth: <name>`, which also takes the
auth's `file-resolution` as its default) and `{ file: }` references, merged per entry name through `extends:`.

<!-- keys: auth.<name> -->
| Key | Type | Description |
|-----|------|-------------|
| `type` | string | `bearer-token`, `basic-auth` or `custom-header` |
| `token` | string | `bearer-token`: the token |
| `username` | string | `basic-auth`: user name |
| `password` | string | `basic-auth`: password |
| `name` | string | `custom-header`: header name |
| `value` | string | `custom-header`: header value |
| `base-url` | string | Host (and port) this credential is keyed for; a content source on another host is refused. Unset: any host |
| `file-resolution` | string | Default `file-resolution` (`local`, `fallback-remote`, `fallback-local`, `strict`) of every `extends:` entry that names this auth and sets none of its own; an invalid value fails the load. Unset: `local` |

---

## Report

<!-- keys: report -->
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `language` | string | `en` | Output language (BCP-47 tag, base subtag used) of AI messages, the markdown summary and inline-review lines; strings are English, other languages come from a translation pack (Russian and Polish built in), others render in English. `ai.response-language` is its alias |
| `transparency` | object | | How the summary shows how the review was made (see below) |
| `walkthrough` | `off` or `collapsed` | `off` | Whether the AI MR summary carries a changes walkthrough |
| `style` | `quiet`, `standard` or `verbose` | `standard` | Report layout preset for every format; `verbose` restores the previous layout, `reporters.<format>.style` overrides it per format (see [Report style](../configuration/YAML_CONFIG.md#report-style)) |

<!-- keys: report.transparency -->
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `summary` | `off`, `collapsed` or `expanded` | `collapsed` | The provenance block of the markdown summary |
| `inline-why` | bool | `false` | Each inline note ends with a one-line "why" footer |
| `flow-diagram` | bool | `false` | The block carries a Mermaid flow diagram of the run; the diagram is written to `.karandash/debug/review-flow.md` either way |

---

## Security

<!-- keys: security -->
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `blocked-hosts` | list of strings | `[]` | Extra hostnames every outbound URL (webhooks, provider base URLs, redirects) is refused for, on top of the built-in metadata and link-local hosts; it only adds blocks, and `allow-localhost` never lifts them |

---

## Cache

The `cache:` block. Durations take a unit (`ms`, `s`, `m`, `h`, `d`, `w`, `mo`, `y`; compounds like `1h 30m`); sizes take `b`, `kb`,
`mb`, `gb` or `tb`. Commands: [`karandash cache`](cli/cache.md).

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | boolean | `true` | `false` keeps the caches memory-only, with no per-MR state store and no remote cache |
| `dir` | string | `~/.karandash/cache` | Cache base directory |
| `responses` | object | | Provider API response cache (`cache.responses` below) |
| `downloads` | object | | Remote `extends:` config cache (`cache.downloads` below) |
| `state` | object | | Per-MR validation state: rule results, AI state (`cache.state` below) |
| `memory` | object | | In-memory cache sizes (`cache.memory` below) |
| `remote` | object | none (local only) | Shared remote cache backend (`cache.remote` below) |
| `default-ttl` | duration | | Provider response TTL when `cache.responses.ttl` is unset and there is no `timeouts.cache-ttl` |
| `max-cache-size` | integer (bytes) | unbounded | The end-of-run GC evicts the oldest entries, after expiry cleanup, until the namespaces the run used fit |
| `glob` | object | | `max-entries` (default `1000`): compiled glob cache size |
| `regex` | object | | `max-entries` (default `1000`): compiled regex cache size |
| `patterns` | object | | `max-entries` (default `512`): resolved named-pattern cache size |

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | boolean | `true` | Cache provider responses, revalidating with ETags |
| `ttl` | duration | `timeouts.cache-ttl`, else `cache.default-ttl`, else `60s` | Response lifetime |
| `persist` | boolean | `false` | Also keep responses on disk so a later run within `ttl` reuses them; off, they live in memory for one run |

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | boolean | `true` | Cache remote `extends:` configs |
| `ttl` | duration | `1h` | Lifetime of a fetched config; an `extends:` entry's own `cache.ttl` wins. Merged through `extends:` (the closer layer wins) |

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enabled` | boolean | `true` | Keep per-MR state between runs |
| `ttl` | duration | `7d` | State lifetime |

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `max-entries` | integer | `512` | Default size of every in-memory cache |
| `overrides` | object | `{}` | Per-cache size by cache name, e.g. `responses: { max-entries: 256 }`, `glob-patterns: { max-entries: 1024 }` |

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `backend` | string | required | `http`, `s3` or `gcs`. `gcs` is the HTTP backend: point `url` at the bucket's HTTP endpoint and put the credential in `auth` |
| `url` | string | required | Backend base URL |
| `auth` | string | | `Authorization` header for `http`/`gcs`: sent as is when it names a scheme (`Basic …`, `Bearer …`), otherwise as a bearer token. Never logged |
| `push` | boolean | `true` | Upload new local entries |
| `pull` | boolean | `true` | Read entries missing locally |
| `prefix` | string | `""` | Key prefix under `url` |
| `max-blob-size` | size | `10mb` | Blobs over this are neither pushed nor read |
| `push-timeout` | duration | `5s` | Per-upload timeout |
| `pull-timeout` | duration | `3s` | Per-download timeout |
| `include` | list of strings | `[]` | Namespaces to share; empty shares every namespace except `http-responses` |
| `exclude` | list of strings | `[]` | Namespaces never shared, applied after `include` |
| `region` | string | `us-east-1` | `s3`: signing region |
| `access-key-id` | string | | `s3`: with `secret-access-key`, signs requests (SigV4) |
| `secret-access-key` | string | | `s3`: see `access-key-id` |
| `session-token` | string | | `s3`: temporary-credential token |
| `profile` | string | `AWS_PROFILE`, else `default` | `s3`: shared-file profile when no keys are set; with no credentials found requests are anonymous |
| `ttl` | duration | | `cache gc --remote` also expires local entries older than this, deleting their blobs locally and on the remote |

---

## Rules

### Universal Rule Options

Every rule block supports two optional fields that improve the developer experience when violations are found:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `suggestion` | `String` | `null` | Short actionable hint shown to the MR author when this rule fails |
| `explanation` | `String` | `null` | Longer explanation of *why* the rule exists, shown with `--explain` |

These fields are available on all rules described below.

---

### `titleFormat`

Validates MR/PR title format.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `pattern` | `String` | `null` | Regex pattern the title must match (optional - falls back to structured `type`/`scope`/`subject` config, or skips pattern validation if neither is set) |
| `minLength` | `Int` | `10` | Minimum title length |
| `maxLength` | `Int` | `null` | Maximum title length |
| `requireTicket` | `Boolean` | `false` | Require a ticket reference in the title |
| `suggestion` | `String` | `null` | Actionable hint shown when the rule fails |
| `explanation` | `String` | `null` | Explanation of why the rule exists (shown with `--explain`) |

**Kotlin DSL:**

```kotlin
rules {
    titleFormat {
        pattern = "^(feat|fix|docs|refactor|test|chore):"
        minLength = 10
        maxLength = 72
        requireTicket = true
    }
}
```

**YAML:**

```yaml
rules:
  titleFormat:
    pattern: "^(feat|fix|docs|refactor|test|chore):"
    minLength: 10
    maxLength: 72
    requireTicket: true
```

**JSON:**

```json
{
  "rules": {
    "titleFormat": {
      "pattern": "^(feat|fix|docs|refactor|test|chore):",
      "minLength": 10,
      "maxLength": 72,
      "requireTicket": true
    }
  }
}
```

---

### `commitMessages`

Validates commit message format.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `format` | `String` | `null` | Preset format: `"conventional"` or custom |
| `pattern` | `String` | `null` | Custom regex pattern for commit messages |
| `maxLength` | `Int` | `null` | Maximum commit message length |
| `minLength` | `Int` | `null` | Minimum commit message length |
| `conventionalTypes` | `List<String>` | `null` | Custom list of conventional commit types (overrides the default list) |

```kotlin
rules {
    commitMessages {
        format = "conventional"
        maxLength = 100
    }
}
```

---

### `jiraTicket`

Requires JIRA ticket references in the MR.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `projectKeys` | `List<String>` | `[]` | JIRA project key prefixes (e.g., `["PROJ", "ENG"]`). Effectively required for JIRA-family formats - validation emits an error finding if left empty |
| `pattern` | `String` | `null` | Custom regex pattern for ticket matching |
| `validateStatus` | `List<String>` | `null` | Allowed JIRA ticket statuses |
| `validateAssignee` | `Boolean` | `false` | Require the ticket to have an assignee |

```kotlin
rules {
    jiraTicket {
        projectKeys = listOf("PROJ", "ENG")
        pattern = "[A-Z]+-\\d+"
    }
}
```

---

### `branchNaming`

Validates source and target branch naming conventions.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `pattern` | `String` | `null` | Single regex pattern for branch names |
| `allowedPatterns` | `List<String>` | `[]` | List of allowed branch name patterns |
| `requireSourceMatch` | `Boolean` | `true` | Validate the source branch name |
| `requireTargetMatch` | `Boolean` | `false` | Validate the target branch name |

```kotlin
rules {
    branchNaming {
        allowedPatterns = listOf(
            "feature/.*",
            "bugfix/.*",
            "hotfix/.*",
            "release/.*"
        )
        requireSourceMatch = true
        requireTargetMatch = false
    }
}
```

---

### `fileChanges`

Enforces rules based on which files are modified.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `patterns` | `List<FilePatternRule>` | `[]` | File pattern rules (each with a glob `pattern` plus optional overrides) |
| `requireApproval` | `List<String>` | `null` | Usernames required to approve when matched files change |
| `requireLabels` | `List<String>` | `null` | Labels required when matched files change |
| `blockOnDraft` | `fail \| warn \| off` | `off` | Finding when the MR is a draft and matched files change |

Each pattern entry creates a `FilePatternRule`:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `pattern` | `String` | **required** | Glob pattern (e.g., `"**/migrations/**"`) |
| `requireApproval` | `List<String>` | `null` | Override: users who must approve |
| `requireLabels` | `List<String>` | `null` | Override: labels required |
| `blockOnDraft` | `fail \| warn \| off` | `off` | Per-pattern finding on draft (used when the top-level key is `off`) |

```kotlin
rules {
    fileChanges {
        patterns = listOf(
            FilePatternRule(pattern = "**/migrations/**", requireApproval = listOf("dba-team")),
            FilePatternRule(pattern = "**/config/**", requireLabels = listOf("database")),
        )
    }
}
```

---

### `codeowners`

Enforces CODEOWNERS file approval requirements.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `path` | `String` | `"CODEOWNERS"` | Path to the CODEOWNERS file |
| `requireOwnerApproval` | `Boolean` | `true` | Require approval from code owners |

```kotlin
rules {
    codeowners {
        path = ".github/CODEOWNERS"
        requireOwnerApproval = true
    }
}
```

---

### `approval`

Controls approval requirements.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `minApprovals` | `Int` | `1` | Minimum number of approvals required |
| `requiredApprovers` | `List<String>` | `[]` | Specific users who must approve |
| `noChangesSinceApproval` | `Boolean` | `false` | Same as `freshApprovals`: invalidate approvals if new commits are pushed. |

```kotlin
rules {
    approval {
        minApprovals = 2
        requiredApprovers = listOf("tech-lead", "security-team")
        noChangesSinceApproval = true
    }
}
```

---

### `reviewer`

Controls reviewer assignment requirements.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `requiredReviewers` | `List<String>` | `[]` | Users who must be assigned as reviewers |
| `requiredTeams` | `List<String>` | `[]` | Teams that must have a reviewer assigned |
| `excludedReviewers` | `List<String>` | `[]` | Users who cannot be reviewers (e.g., the MR author) |

```kotlin
rules {
    reviewer {
        requiredTeams = listOf("backend-team")
        excludedReviewers = listOf("bot-user")
    }
}
```

---

### `cicdStatus`

Validates CI/CD pipeline status.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `requirePassed` | `Boolean` | `true` | Require the pipeline to have passed |
| `requiredJobs` | `List<String>` | `[]` | Specific job names that must pass |
| `blockOnFailing` | `fail \| warn \| off` | `fail` | Severity of pipeline/required-job failures (`warn` skips the failing-only check, `off` reports none) |

```kotlin
rules {
    cicdStatus {
        requirePassed = true
        requiredJobs = listOf("build", "test", "lint")
        blockOnFailing = "fail"
    }
}
```

---

### `conflict`

Detects merge conflicts.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `blockOnConflicts` | `fail \| warn \| off` | `fail` | Finding when conflicts are detected |

```kotlin
rules {
    conflict {
        blockOnConflicts = "fail"
    }
}
```

---

### `draftPrevention`

Detects draft/WIP merge requests. `draft` is accepted as an alias for the rule ID.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `blockDraft` | `fail \| warn \| off` | `fail` | Finding when the MR is a draft |
| `wipPatterns` | `List<String>` | `["\\bWIP\\b", "\\[WIP\\]", "\\bwip:", "\\bWIP:"]` | Regex patterns that indicate WIP in the title |

```kotlin
rules {
    draftPrevention {
        blockDraft = "fail"
        wipPatterns = listOf("WIP", "\\[WIP\\]", "DO NOT MERGE")
    }
}
```

---

### `labels`

Validates MR labels.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `requiredLabels` | `List<String>` | `[]` | Labels that must be present |
| `forbiddenLabels` | `List<String>` | `[]` | Labels that must not be present |
| `conditionalLabels` | `List<ConditionalLabel>` | `[]` | Conditional label rules |

**ConditionalLabel:**

| Field | Type | Description |
|-------|------|-------------|
| `condition` | `LabelCondition` | Condition to evaluate |
| `requiredLabels` | `List<String>` | Labels required if condition is true |
| `forbiddenLabels` | `List<String>` | Labels forbidden if condition is true |

**LabelCondition:**

| Field | Type | Description |
|-------|------|-------------|
| `hasLabel` | `String` | True if MR has this label |
| `hasAnyLabel` | `List<String>` | True if MR has any of these labels |
| `hasAllLabels` | `List<String>` | True if MR has all of these labels |

```kotlin
rules {
    labels {
        requiredLabels = listOf("type/bug", "priority/high")
        forbiddenLabels = listOf("do-not-merge")
    }
}
```

---

### `sizeLimits`

Limits MR size to encourage smaller, reviewable changes.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `maxFiles` | `Int` | `null` | Maximum number of changed files |
| `maxLines` | `Int` | `500` | Maximum number of changed lines |
| `maxCommits` | `Int` | `20` | Maximum number of commits |

```kotlin
rules {
    sizeLimits {
        maxFiles = 20
        maxLines = 500
        maxCommits = 10
    }
}
```

---

### `security`

Scans for secrets and sensitive file changes.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `scanForSecrets` | `Boolean` | `true` | Enable secret scanning in diffs |
| `secretPatterns` | `List<String>` | *(see below)* | Regex patterns for secret detection |
| `sensitiveFiles` | `List<String>` | *(see below)* | Glob patterns for sensitive files |

**Default secret patterns** detect: API keys, secret keys, passwords, access tokens, bearer tokens, AWS credentials, private keys, GitHub tokens, and GitLab tokens.

**Default sensitive file patterns:** `.env`, `.env.*`, `config/secrets.yml`, `config/credentials.yml`, `*secret*`, `*credential*`, `*.pem`, `*.key`, `*.p12`, `*.pfx`, `id_rsa`, `id_dsa`.

```kotlin
rules {
    security {
        scanForSecrets = true
        sensitiveFiles = listOf("**/.env", "**/*.pem")
    }
}
```

---

## Policies

Top-level `policies:` array. Each entry has a `when:` condition expression; when it evaluates to
`true` for the current MR, the policy toggles modules, deep-merges config overrides into them,
overrides severity, and gates pipeline stages. Policies never *add* rules - they modify rules that
are already configured.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `when` | `String` | **required** | Condition expression, e.g. `target_branch_matches("^main$")` |
| `name` | `String` | `null` | Human-readable label used in logs |
| `modules` | `List<String>` | *(all)* | Module IDs the policy applies to |
| `enabled` | `Boolean` | `null` | Enable/disable the scoped modules |
| `severity` | `String` | `null` | Override finding severity |
| `override` | object | `{}` | Per-module config deep-merged into the scoped modules |
| `autoApprove` / `autoFix` / `scripts` / `dangerfile` | `Boolean` | `null` | Gate the corresponding pipeline stage |

Conditions are boolean expressions over predicates such as `is_draft`, `label_exists("name")`,
`file_path_matches("glob")`, `target_branch_matches("regex")`, and `change_count > N`, combined with
`&&`, `||`, `!`, and parentheses. Full predicate reference:
[Condition expression predicates](../configuration/YAML_CONFIG.md#condition-expression-predicates).

```yaml
policies:
  - when: 'file_path_matches("**/migrations/**")'
    modules: [approval, labels]
    override:
      approval:
        minApprovals: 2
```

```kotlin
policies {
    migrations {
        when = "file_path_matches(\"**/migrations/**\")"
        modules(listOf("approval", "labels"))
        override {
            approval { minApprovals = 2 }
        }
    }
}
```

See [Conditional Rules (Policies)](../rules/conditional.md) for evaluation semantics and per-rule
`when:` blocks.

---

## Reporters

Each reporter is a combination of a `FormatModule` (renders output) and an `OutputModule` (delivers it). See [Reporters](../reporters/index.md) for full documentation on each reporter.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `console` | `Boolean` or object | `true` | Console output (`ConsoleFormatModule` + `StdoutOutputModule`) |
| `markdown` | `Boolean` or object | auto | MR/PR comment (`MarkdownFormatModule` + `PlatformOutputModule`). |
| `json` | `Boolean` | `false` | JSON output (`JsonReportFormatModule` + `StdoutOutputModule` / `FilesystemOutputModule`) |
| `sarif` | `Boolean` or object | `false` | SARIF 2.1.0 (`SarifFormatModule` + `StdoutOutputModule` / `FilesystemOutputModule`) |
| `junit` | `Boolean` | `false` | JUnit XML (`JunitFormatModule` + `FilesystemOutputModule`) |
| `reviewdog` | `Boolean` | `false` | Reviewdog rdjson/rdjsonl (`RdjsonFormatModule` / `RdjsonlFormatModule` + `StdoutOutputModule` / `FilesystemOutputModule`) |
| `slack` | object | `null` | Slack Block Kit (`SlackFormatModule` + `SlackOutputModule`) |
| `webhook` | object | `null` | HTTP POST (`JsonReportFormatModule` + `WebhookOutputModule`) |
| `email` | object | `null` | SMTP email (`HtmlFormatModule` + `EmailOutputModule`) |
| `html` | `Boolean` | `false` | HTML report (`HtmlFormatModule` + `FilesystemOutputModule`) |
| `codequality` | `Boolean` or object | `false` | GitLab Code Quality JSON (`CodeQualityFormatModule`); write it to a file with `output: gl-code-quality-report.json` |
| `status` | object | `null` | The verdict as the platform's result status (`RdjsonlFormatModule` + `StatusOutputModule`): GitHub check run, GitLab commit status, Bitbucket Code Insights report, Azure DevOps PR status |
| `platform` | object | auto | Options of the MR comment output; `comments: all\|summary\|inline\|none` (default `all`) picks which comments are posted |

### `reporters.sarif`

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `version` | `String` | Karandash version | Override the tool version in the SARIF `driver.version` field |

### `reporters.slack`

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `webhook` | `String` | required without `token` | Slack incoming webhook URL (must start with `https://hooks.slack.com/`) |
| `channel` | `String` | `null` | Slack channel override; required with `token` (a channel ID such as `C0123456789`) |
| `token` | `String` | `null` | Bot token (`xoxb-…`, scopes `chat:write` and `channels:history`). Set, it posts with `chat.postMessage` and a later run for the same MR updates that message with `chat.update`; the webhook is not used |
| `notificationTarget` | `String` | set by the CLI | The MR this run is for (`<project>!<mr>`), used to find the earlier message; leave it unset |

### `reporters.status`

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `name` | `String` | `karandash` | Name of the check run, commit status context, Code Insights report or PR status context |
| `detailsUrl` | `String` | `null` | Link shown with the status, e.g. the CI job URL |
| `onError` | `warn` or `fail` | `warn` | `fail` fails the run when the status cannot be published |
| `notificationState` | object | set by the CLI | This run's verdict; leave it unset |

### `reporters.webhook`

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `url` | `String` | **required** | HTTPS URL to POST results to |
| `headers` | map | `{}` | Additional HTTP headers |
| `authToken` | `String` | `null` | Bearer token added to the `Authorization` header |
| `contentType` | `String` | `application/json` | `Content-Type` of the POSTed body |

### `reporters.email`

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `host` | `String` | **required** | SMTP server hostname |
| `port` | `Int` | `587` | SMTP server port |
| `username` | `String` | `null` | SMTP authentication username |
| `password` | `String` | `null` | SMTP authentication password |
| `from` | `String` | `null` | Sender address (defaults to `username`) |
| `to` | `String` | **required** | Recipient email address |
| `subject` | `String` | `"Karandash Validation Report"` | Email subject line |
| `starttls` | `Boolean` | `true` | Require STARTTLS. `false` sends plaintext and is accepted only for a loopback SMTP host (`localhost`, `127.0.0.1`, `::1`) |

```kotlin
reporters {
    console = true
    markdown = true
    slack {
        webhook = env.SLACK_WEBHOOK_URL
        channel = "#code-review"
    }
}
```

---

## Plugins

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `install` | list | `[]` | Declared plugins: an ID string, or an object with `id` (required), `version` and `source` (a URL to the plugin file) |
| `on-missing` | `String` | `warn` | `warn`, `fail` or `ignore`: what happens when a config entry names a module no loaded plugin provides |

When read from the config file, only `install` and `on-missing` take effect. Registry settings (`registry-url`,
`token`, `auth-header`) and `directory` are read by the `karandash plugin` commands from `.karandash/manifest.yaml`
only. Per-plugin config goes under the plugin's own section (for example `rules: { custom-validator: { ... } }`).

```yaml
plugins:
  on-missing: warn
  install:
    - com.example.jira-integration
    - id: com.example.custom-validator
      version: "1.0.0"
      source: https://example.com/custom-validator.jar
```

---

## Environment Variable References

The Kotlin DSL reads environment variables with `env.VAR_NAME` or `env("VAR_NAME")`; each becomes a `${VAR_NAME}` placeholder, interpolated like YAML. Denylisted variables (secrets such as `AWS_SECRET_ACCESS_KEY`, and system variables such as `PATH`) read as unset; the top-level `env:` block supplies defaults for unset variables, also in a KTS `if` condition (see [Environment Variables](environment-variables.md)).

```kotlin
platforms {
    gitlab {
        url = env.CI_SERVER_URL
        token = env.CI_JOB_TOKEN
    }
}
```

### AWS credentials

`platforms.aws-codecommit` and `cache.remote` (backend `s3`) take an optional `profile` key naming a shared-file
profile. Without `access-key-id` + `secret-access-key` both read credentials from the standard AWS chain - `profile`'s
shared files, else `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` (+ `AWS_SESSION_TOKEN`), else `AWS_PROFILE`/`default`
in `~/.aws/credentials` / `~/.aws/config`. The built-in chain reads the denylisted secret directly; `${AWS_SECRET_ACCESS_KEY}`
interpolation stays blocked. The chain is only used for `https` AWS hosts (`*.amazonaws.com`, `*.amazonaws.com.cn`):
not for a custom CodeCommit `endpoint`/`sts-endpoint` or an S3-compatible `cache.remote.url`, whichever config layer (root or `extends:`
parent) sets `cache.remote`. See [AWS CodeCommit](../platforms/aws-codecommit.md#credential-resolution).

## Config File Discovery

Karandash searches for configuration files in this order:

1. `.karandash/config.kts`
2. `.karandash/config.yaml` / `.karandash/config.yml`
3. `.karandash/config.json`

If no file is found, zero-config mode activates with default rules.

## See Also

- [CLI Reference](cli-reference.md) -- command-line flags that override config
- [Environment Variables](environment-variables.md) -- env vars for auth and overrides
- [Error Codes](error-codes.md) -- exit codes for config errors
