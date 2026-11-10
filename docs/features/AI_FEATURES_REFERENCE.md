# AI Features Reference

Complete documentation of all AI-powered features in Karandash, organized by capability.

## Quick Start

```yaml
ai:
  provider: anthropic
  model: claude-sonnet-4-20250514

  # Quality Pipeline
  structuredOutput: true
  filter:
    minConfidence: 0.5
    maxPerRule: 10
    deduplicate: true

  # Capability
  grounding:
    enabled: true
    checkSymbolExistence: true

  constitution:
    principles:
      - "Never comment on code formatting"
      - "Only flag issues with >90% confidence"
    selfCritiqueRounds: 1

  # Intelligence
  gates:
    - ruleId: ai-security-scan
      filePatterns: ["**/*.kt", "**/*.java"]

  feedbackEnabled: true

  # Research Metrics
  reasoning: semi-formal
  metrics:
    changeEntropy: true
    cognitiveLoad: true
    hotspotAnalysis: true

  # Configuration
  executionProfile: ci

  enforcementSchedule:
    - from: "2026-01-01"
      until: "2026-03-31"
      mode: warn
    - from: "2026-04-01"
      mode: enforce

  severityOverrides:
    - rule: size-limits
      reclassify:
        error: warning

  conditionalSections:
    - section: "## Database Changes"
      whenFilesChanged: ["**/*.sql"]

  compositions:
    - name: large-untested
      when: "rule_failed(size-limits) && !rule_failed(security)"
      severity: warning

  costBudget:
    maxTokensPerRun: 50000
    branchBudgets:
      - pattern: "hotfix/"
        maxTokensPerRun: 100000


# Reporters (top-level key, not under ai:)
reporters:
  smartDigest:
    enabled: true
    audiences:
      - role: reviewer
        maxItems: 10
      - role: author
        maxItems: 5

  complianceEvidence:
    enabled: true
    hashChain: true

  riskHeatmap:
    enabled: true

  doraMetrics:
    enabled: true
```

---

## Quality Pipeline

The base layer. It checks that AI findings are accurate and grounded in the diff.

### JSON Mode

**What it does:** Constrains the AI response to the analyzer's result schema, so the reply is JSON in the expected shape instead of free text that has to be salvaged.

**Config:**
```yaml
ai:
  provider: anthropic
  structuredOutput: true   # default: true
  risk-scoring:
    structuredOutput: false   # per-analyzer override
```

**Behavior:**
- Every built-in analyzer and custom AI rules (`customRules:`) get a JSON schema generated from their result type; the dispatcher attaches it, so an analyzer that sets its own `responseSchema` keeps it.
- `ai.<analyzer>.structuredOutput` turns it on or off for that analyzer alone.
- Anthropic forces a `respond` tool whose input schema is that schema and returns the tool input. OpenAI sends `response_format: {type: json_schema}`, with `strict: true` on `api.openai.com` when the schema can be made strict (optional properties become nullable and their null values are dropped from the reply; a schema with an open object, or a root that is not an object, stays non-strict). The host is matched exactly (`api.openai.com`, parsed from `ai.baseUrl`). Another OpenAI-compatible host (a `custom` provider, Ollama, LiteLLM, Bifrost, Gemini or an OpenAI `baseUrl` elsewhere) gets the schema, non-strict, only when `ai.structuredOutput` is written as `true`; left unset it gets the plain request it got before structured output was on by default, and the reply is parsed against the schema as text. `false` sends no schema to any provider. Other providers fall back to JSON mode.
- With the key off, or under `reasoning: semi-formal`, the schema is stripped and the response is parsed as before.
- OpenAI reasoning models: on `api.openai.com` (exact host) a model whose id starts with `o1`, `o3`, `o4` or `gpt-5` is sent `max_completion_tokens` and no `temperature`; a `-chat` variant such as `gpt-5-chat-latest` is a chat model and keeps `max_tokens` and `temperature`. Azure OpenAI reasoning deployments are not covered: their host is not `api.openai.com`, so they are sent the classic parameters.
- A reply that is valid JSON is used as is; the repair of fenced, commented, trailing-comma or truncated JSON runs only when it is not.

### Prompt Caching

**What it does:** Lets the provider reuse the system prompt across the AI rules of a run, and shows the cache use per rule.

Always on. One key, `ai.promptCacheTtl` (`5m` default, or `1h`), sets how long Anthropic keeps the entry.
- Anthropic: the system prompt is sent as a block marked `cache_control: {type: ephemeral}` (plus `ttl: 1h` with `promptCacheTtl: 1h`). Cached input tokens (`cache_read_input_tokens`, `cache_creation_input_tokens`) count toward prompt tokens. Pick `1h` only when runs sharing a system prompt start 5-60 minutes apart: a 1h write costs 2x the input price instead of 1.25x.
- OpenAI caches long prompts on its own; `prompt_tokens_details.cached_tokens` is read back.
- Stable parts of the user turn are breakpoints too. An analyzer passes them as `AiCompletionRequest.contextBlocks` (`review-comments` sends the MR metadata and instructions this way), and Anthropic sends each as its own text block ahead of the diff. The last blocks are marked `cache_control`, up to the API's limit of 4 breakpoints counting the system prompt. The diff is never a breakpoint. The prepared diff is shared by several analyzers, but they run concurrently with different system prompts in front of it, so a breakpoint after the diff would be written once per analyzer, at the write premium, before any call could read it. Providers that do not read context blocks (`AiProviderModule.supportsContextBlocks = false`, the default for third-party and guest providers) get them joined into `prompt` by the host.
- Shared MR context: the context parts of an AI rule's prompt (enricher blocks such as tickets, CI, symbols and design docs, cross-file and dependency context) are built once per rule and sent to every analyzer call as leading context blocks, ahead of the analyzer's own text, which comes last. The prepared diff is built once per MR and diff settings and shared by the rules. Only instructions (language, location rules, extra instructions, best practices, scopes, strictness, feedback, prior findings) stay in the system prompt. What this buys per provider: Anthropic orders its cache prefix as tools, then system, then messages, and the forced `respond` tool's input schema and the system prompt both differ per analyzer, so a cached context block is reused only by later calls of the same analyzer (chunks, retries, re-runs), never across analyzers; reuse across analyzers would need one shared system prompt and schema, or no forced tool. Bifrost speaks the OpenAI wire and sends no `cache_control`; the blocks are joined into the user message, so OpenAI's automatic prefix caching (prompts of 1024 tokens or more) can only reuse a prefix that starts identically, which the per-analyzer system prompt again prevents. A plugin analyzer reads the blocks from `AiAnalyzerContext.contextBlocks` and must not resend them; a guest (JAR, JS, WASM, native) analyzer gets them joined into `extraInstructions`.
- `ai.sharedSystemPrompt: true` (default `false`): every analyzer is sent one short common system prompt, and its own system prompt moves into the user turn as a `## Your task` block right after the shared MR context blocks and before its own blocks and the diff. The system prompt and the shared blocks are then the same bytes for every analyzer of the rule, so OpenAI's automatic prefix caching (and Anthropic's, with `ai.structuredOutput: false`, since the forced `respond` tool schema comes first and differs per analyzer) can reuse them across analyzers. Off by default: the instructions carry user-turn rather than system-prompt weight. Compare the `cache=read:` share in the cost line with it on and off.
- Cache reads and writes are metered per rule and priced in the cost line: Anthropic writes at 1.25x (2x with `1h`) of the input price, reads at 0.1x (Claude Fable 5.1 $0.25/MTok, Claude Opus 5.5 $0.20/MTok); OpenAI `gpt-4o*` cached input at 0.5x. Other providers' cached tokens are priced as plain input.

```
[INFO] [ai] rule=ai-review-comments model=claude-sonnet-4 tokens=900+100 cache=read:600/write:200 cost=$0.0042
```

### JSON Recovery

**What it does:** Automatically recovers from truncated or malformed AI responses by attempting to parse partial JSON, extracting valid findings even when the response was cut off.

**Config:**
```yaml
ai:
  filter:
    attemptJsonRecovery: true  # default: true
```

**Behavior:**
- If response ends mid-JSON, attempts to close brackets intelligently
- Partial findings are included if valid JSON can be extracted
- Logs recovery attempt with recovered vs discarded content ratio

**Output:**
```
[WARN] Response truncated after 1824 tokens
[INFO] JSON recovery: extracted 5/7 valid findings from partial response
```

### Line Validation

**What it does:** Downgrades or removes findings that reference diff line numbers not actually present in the MR (prevents hallucinations about code that doesn't exist).

**Config:**
```yaml
ai:
  filter:
    validateDiffLines: true  # default: true
    downgradeInvalidLines: true  # downgrade to INFO instead of removing
```

**Behavior:**
- Checks every finding's `location.line` against actual diff
- If line is out-of-bounds, downgrades severity or removes finding
- Logs which findings were downgraded and why

**Output:**
```
[WARN] Finding 'potential-null-ref' references line 42, but diff only has 35 lines
[INFO] Downgraded to INFO (was ERROR)
```

### Evidence Anchoring

**What it does:** Removes findings that reference files not actually changed in the MR (prevents false positives about code outside the diff).

**Config:**
```yaml
ai:
  filter:
    anchored: true  # default: true
    requireFileInDiff: true
```

**Behavior:**
- Cross-checks `finding.location.file` against MR changed files
- Removes findings for files not in the diff
- Logs excluded findings with reason

**Output:**
```
[DEBUG] Evidence anchoring: 12 findings anchored to diff, 2 excluded (files not in MR)
```

### Confidence Filtering

**What it does:** Drops findings with AI confidence scores below configured thresholds.

**Config:**
```yaml
ai:
  filter:
    minConfidence: 0.5  # drop findings below 50% confidence
    minConfidenceByModel:  # optional per-model override, first matching glob wins
      "anthropic/*": 0.4  # Claude models report 0.4-0.55 for real findings
    confidenceMetadata: true  # include confidence in Finding.metadata
```

**Behavior:**
- Uses AI metadata `confidence: 0.0-1.0` embedded in response
- `filter.dedupOpenThreads` (default on) also drops a finding that an unresolved thread by someone else on the same file, within 3 lines, already says (token overlap, no LLM call); same-line repeats of one rule keep only the most severe
- Removes findings below threshold
- Preserves findings at or above threshold

**Output:**
```
[DEBUG] Confidence filter: 3 findings dropped (confidence < 0.5)
```

### Deduplication

**What it does:** Keeps the highest-severity finding when multiple AI findings refer to the same rule/file/line combination.

**Config:**
```yaml
ai:
  filter:
    deduplicate: true  # default: true
    deduplicateKey: "rule:file:line"  # or "rule:file"
```

**Behavior:**
- Groups findings by `(ruleId, file, line)`
- Keeps highest severity finding in each group
- Logs deduplicated count

**Output:**
```
[INFO] Deduplication: 15 findings → 12 findings (3 duplicates removed)
```

### AI Grounding

**What it does:** Verifies that AI claims about code are actually true by checking symbol existence and code snippet matches against the actual diff.

**Config:**
```yaml
ai:
  grounding:
    enabled: true
    checkSymbolExistence: true  # verify function/class names exist
    checkCodeSnippets: true      # verify quoted code is in diff
    failureMode: downgrade       # or: remove
```

**Behavior:**
- For each finding with a symbol reference, checks it exists in changed code
- For each code quote, verifies it appears in the diff
- Downgrades or removes findings that don't ground

**Example:**
```
Finding: "Potential null dereference of 'userService'"
Grounding check: ✓ 'userService' found in diff
Status: PASSED
```

```
Finding: "Missing error handling for 'database.query()'"
Grounding check: ✗ 'database.query()' not found in diff
Status: FAILED → Downgraded to INFO
```

#### Verifier pass (opt-in)

After the grounding checks, `ai.grounding.verifier` can send every WARNING-or-worse AI finding on a file, with
the diff of that file, to a second model in one call. The model answers `valid` or `invalid` per finding, verdict
first and reason second; only `invalid` drops a finding. Any error, timeout, unreadable reply or a prompt over
60,000 characters keeps every finding (fails open).

Order after validation: constitutional review, then the grounding checks, contradiction detection and
actionability, then the verifier. A finding the constitutional review removed or grounding dropped never reaches
the verifier, and one either of them downgraded to INFO is below its WARNING threshold, so the verifier only
judges findings both earlier passes kept at WARNING or above. The diff it sends is secret-redacted, and the call
counts against the run's token budget and holds one of the run's AI concurrency permits; with the budget spent
it is skipped and every finding kept.

```yaml
ai:
  grounding:
    verifier:
      enabled: false   # default; precision on a live model is not measured yet
      model: gpt-4o-mini   # default: ai.model, else the provider's low-cost default model
```

---

### Timeouts

`ai.timeout` (default 60s for OpenAI-compatible providers, 120s for Anthropic) bounds the whole request. A model that
stalls without answering therefore holds the run for the full `ai.timeout` per attempt, and `ai.max-retries` repeats it
(`timeout: "5m"` with one retry can cost 10 minutes). Set `ai.stream: true` to bound only the silence between streamed
chunks: a stalled call fails after `ai.timeout` of silence and is retried, while a long generation that keeps sending is
not cut off. OpenRouter sends keep-alive comments while it queues a request, so a short `ai.timeout` (60-90s) is safe with
streaming.

Without `ai.stream`, a non-streaming call also fails after 180s without a single response byte, even when
`ai.timeout` is longer, so a stalled connection is retried by `ai.max-retries` instead of holding the run for the full
timeout. A slow answer that sends keep-alive bytes (OpenRouter) is not cut by this; `ai.stream: true` removes the
cap and bounds only the silence between chunks by `ai.timeout`.

## Capability

Enhanced AI analysis with multi-pass review, context awareness, and self-critique.

### Custom Rules

**What it does:** Runs a rule you define with a prompt instead of Kotlin code. Each rule makes its own model call with its prompt as the system prompt and the MR title, description, branches, labels and changed-file list (paths with +/- counts, not the diff) as the user turn.

**Config:**
```yaml
ai:
  provider: openai
  model: gpt-4o
  custom-rules:
    - id: changelog            # registered as rule `ai-changelog`
      name: Changelog check
      prompt: "Check if the MR updates CHANGELOG.md for user-visible changes."
      severity: warning        # default warning; error | warning | info
      maxTokens: 1000          # optional, default 1000
      temperature: 0.2         # optional, default 0.2
      enabled: true            # optional, default true
```

**Behavior:**
- `id` and `prompt` are required; entries without them are skipped. The rule ID gets an `ai-` prefix unless it already has one, so findings, `rules:` overrides and policies use `ai-changelog` (`aiChangelog` in camelCase).
- The model answers with JSON `{"message": "...", "severity": "...", "explanation": "...", "suggestion": "..."}`. A non-empty `message` is one finding; an empty one means the rule passed. `message` holds the problem only; the optional `suggestion` (how to fix it) becomes the finding suggestion. `severity` in the answer overrides the configured one. A non-JSON answer becomes the finding message as-is.
- `priority`, `mode` and `concernArea` on an entry work as on any rule; otherwise the `ai:` block defaults apply.

### Pre-merge Checklist

**What it does:** Answers a list of yes/no checks about the MR (pass, fail or n/a, each with a one-line reason) in one model call, and renders them as ticks in a "Pre-merge checklist" summary section.

**Config:**
```yaml
ai:
  checks:
    - name: No secrets logged
      instruction: The change does not log tokens, passwords or API keys
      paths: ["src/**"]     # optional; n/a without asking when no changed file matches
      mode: error           # warn (default) | error
  checks-ticket-compliance: false   # default; one row per acceptance criterion of the linked ticket
```

**Behavior:**
- All asked checks go in one call over the shared MR context and the prepared diff; the reply is a JSON list of `{index, status, reason}`. The checklist is never split into diff chunks.
- A failing `mode: error` check is an ERROR finding and fails the run; a failing `warn` check is a WARNING. Passing and n/a rows are INFO, except a `mode: error` check that was not evaluated (no AI service, the call failed, or no verdict in the reply): that row is a WARNING, so an outage does not pass silently. It never fails the run.
- `mode` is `warn` or `error`, in any case. Any other value logs `ai.checks '<name>': unknown mode '<value>', using warn` at config load and the check runs as `warn`.
- A check that gets no verdict (no AI service, a failed call, a row missing from the reply) is n/a with the reason.
- `checks-ticket-compliance` reads the Jira enrichment (one row per acceptance criterion, or the ticket summary when it has none) and linked GitHub/GitLab issues (one row per issue title). With no linked ticket it adds a single n/a row, never a finding. Ticket text (title, criteria, up to 1000 characters of the body) is secret-redacted before it goes into the prompt; the checklist row label shows the ticket key and title as written, so a title is visible in the report. At most 20 ticket rows are asked; any beyond that become one n/a row saying how many were left out. It is off by default: its precision on a live model is not measured yet.
- The checklist call asks for at most 300 reply tokens plus 80 per asked row, capped at 4000.
- The rule id is `pre-merge-checks`; it runs when `checks` is non-empty or `checks-ticket-compliance` is on.

### Diff Chunking

**What it does:** Splits large MRs into overlapping chunks, enabling multiple AI passes that collectively provide better coverage than a single truncated review.

**Config:**
```yaml
ai:
  chunking:                 # or `chunking: false`
    enabled: true
    chunkSize: 10000        # characters per chunk
    overlapSize: 2000       # hunks shared between consecutive windows of one large file
    maxChunks: 10           # max passes (prevents runaway token usage)
```

**Behavior:**
- A diff larger than `chunkSize` is split into at most `maxChunks` chunks on file boundaries.
- A single file larger than `chunkSize` is split on hunk boundaries; consecutive windows share up to `overlapSize` characters of hunks.
- Each chunk is reviewed independently; the findings are merged with deduplication.
- Logs the chunk count, per-chunk findings and merge statistics.
- `enabled: false` reviews the diff in one pass, truncated to the diff limits.
- Without the key, chunking runs on the diff cap and `ai.limits.maxChunks`, and a file larger than the cap is split on
  hunk boundaries without overlap. The diff cap derives from `ai.budget.max-input-tokens` less
  `ai.budget.reserve-output-tokens` (4 characters a token) when it is set, else 256,000 characters, a ceiling
  `ai.context.budget` binds first; the
  `ai.limits.maxDiffCharsTotal` / `maxDiffCharsPerFile` override it.
- Files that fit in no chunk past `maxChunks` are recorded in the coverage ledger as `dropped`, not reviewed.
  `ai.limits.maxChunks` defaults to 12.
- With `ai.large-mr.chunk-context: true` (default) a diff bigger than the prompt room gets at most half of it, so
  the context (cross-file, dependency, enricher blocks) still fits in every chunk call; `false` lets the diff take
  the whole room and drops the context from every chunk.
- With `ai.large-mr.scale-budget: true` (default) each MR gets a budget plan before any rule calls the model: a
  chunked rule gets its token share once per chunk, a whole-MR rule (`pre-merge-checks`, MR-level analyzers such as
  `mr-summary`) its share plus the MR's diff, and the run budget grows by the same tokens. Rules are funded highest
  priority first: `ai.<analyzer>.priority` or a custom rule's `priority` (integers) when set, otherwise
  `review-comments` 2, whole-MR rules 1, the rest 0 (ties by rule ID). When `cost-budget.max-tokens-per-run` (or the
  branch's budget) or the input tokens `max-cost-per-run-usd` buys runs out, the lowest-ranked rules keep only their
  own share, and a rule whose share holds only some chunks sends the chunks with the highest-scored files first
  (`truncation.priority` weights: change volume, file type, commit-frequency hotspot, path). `false` keeps every rule
  on its one share.
- Each chunk's call lists the files other chunks carry (`ai.large-mr.mr-map-max-files`, default 50; `0` = none), so
  the model does not report them missing.

**Output:**
```
[INFO] [ai] rule=ai-review: diff chunking: 45000 chars split into 5 chunks
[DEBUG] [ai] rule=ai-review: chunk 1 (9800 chars): 3 findings
[INFO] [ai] rule=ai-review: merged 7 unique findings (1 duplicates across chunks)
```

### Constitutional AI

**What it does:** Uses a secondary LLM critique pass to self-evaluate findings against user-defined principles, removing findings that violate the principles.

**Config:**
```yaml
ai:
  constitution:
    enabled: true
    principles:
      - "Never comment on code formatting issues"
      - "Only flag issues with >90% confidence"
      - "Always provide actionable remediation"
      - "No style opinions (use linters for formatting)"
    model: gpt-4o-mini       # critique model (often faster/cheaper)
    selfCritiqueRounds: 1    # how many times to critique
    failureMode: remove      # or: downgrade
```

**Behavior:**
- After all rules generate findings, passes findings to critique model
- Critique model scores each finding against principles (pass/fail)
- Removes (or downgrades) findings that fail critique
- Logs critique result per finding

**Example:**
```
Finding: "Consider using snake_case for this variable name"
Principles:
  ✓ Not a formatting issue
  ✗ Violates: "Never comment on code formatting issues"
Decision: REMOVE
```

```
Finding: "Potential memory leak in destructor: missing delete"
Principles:
  ✓ Not a formatting issue
  ✓ High confidence (95%)
  ✓ Actionable (add delete call)
Decision: KEEP
```

### Incremental Review

**What it does:** Only analyzes changes since the last review, reducing token usage for MRs with multiple review cycles.

**Config:**
```yaml
ai:
  incremental:
    enabled: true

cache:
  state:
    enabled: true  # persists incremental snapshots across runs
```

**Behavior:**
- On by default when `cache.state` is configured; `incremental: false` (or `enabled: false`) reviews the whole diff on every run.
- First run: reviews the full diff and saves a snapshot plus the head commit SHA.
- Later runs: reviews only the files whose diff changed since the last reviewed commit and reuses the cached findings of the rest.
- A force push or rebase that drops the last reviewed commit from the MR's commits, or a stored snapshot with no
  last reviewed commit, reviews the whole diff again; the `ai-incremental` finding then says why
  (`Incremental AI review: all 12 file(s) reviewed, last reviewed commit 1a2b3c4d is not in the MR history`).
- Adds an `ai-incremental` INFO finding listing the reviewed and skipped files (metadata `reviewedFiles`, `skippedFiles`).
  When at least one rule did not send some files to the model, the message ends with their counts
  (`; not sent to AI by at least one rule: 3 too large, 2 filtered, 1 binary`) and the finding carries
  `tooLargeFiles` (dropped by `partialReview` past `maxFiles`), `filteredFiles` (file filters and the exclude
  policy) and `binaryFiles`.

**Output:**
```
[INFO] Incremental: 2 file(s) changed since last reviewed commit 1a2b3c4d (head 5e6f7a8b)
INFO  ai-incremental  Incremental AI review: 2 file(s) reviewed, 14 unchanged since 1a2b3c4d skipped (cached findings reused)
```

### Cross-File Context

**What it does:** Includes imported/referenced file signatures in AI prompts, providing context about dependencies without sending full file contents.

**Config:**
```yaml
ai:
  crossFileContext:
    enabled: true
    includeImports: true
    includeTests: true       # add the test file matching a changed source file
    maxContextFiles: 5       # max related files to include
    captureSignatures: true  # include function/class signatures only (false sends the full text)
  crossFileContextMaxChars: 8000   # size cap of the section
```

**Behavior:**
- Resolves the imports of changed files to repository files: Kotlin/Java fully qualified names under `src/<set>/kotlin|java/`, relative JS/TS imports, Python modules, C/C++ `#include "..."`. Matching test files follow.
- Imports are read from the whole changed file when the CLI fetched it at the MR head, so an include outside the changed hunks counts too; otherwise from the diff.
- Related files are read from the working copy; a file missing there is fetched at the MR head through the platform (GitLab/GitHub file API), so the section works without a checkout.
- Changed files and sensitive files (keys, `.env`, ...) are skipped; content is redacted before it is sent.
- For each related file, includes declarations only (or the full text with `captureSignatures: false`).
- Adds them to the prompt under "Context from Dependencies", within `ai.crossFileContextMaxChars`.
  A section cut to that cap is named in the coverage ledger as `dependencyContext (cut to N of M chars)`.
- `enabled: false` also turns off the older cross-file section.

**Example Prompt Section:**
```
## Context from Dependencies

### Imported: io.example.auth.UserService
- authenticate(username: String, password: String): User?
- validateToken(token: String): Boolean
- logout(user: User): Unit

### Imported: io.example.db.Database
- query(sql: String): List<Map<String, Any>>
- execute(sql: String): Int
```

---

## Intelligence

Advanced analysis with pattern detection, contradiction detection, and risk assessment.

### Finding Correlation

**What it does:** Merges AI findings that several analyzers raised on the same line, and detects compound risk patterns across rules.

**Config:**
```yaml
ai:
  correlation:            # or `correlation: true`
    enabled: true
    patterns:
      - name: untested-security-change
        findings: ["ai-security", "ai-test-coverage"]   # rule ids, matched exactly or as a substring
        severity: error   # severity of the meta-finding, also applied to the matched findings
        message: "Security change without tests - high risk"
        when: 'total_changes > 500 || label_exists("security")'   # optional condition on the MR
```

**Behavior:**
- Without the block, only the built-in compound-risk patterns run.
- With the block:
  - AI findings (rule `ai-*`) from different analyzers on the same file and line merge into one: the most severe (then most confident) finding. It lists every source rule in its `correlatedSources` metadata. Deterministic rule findings and file-level findings are never merged.
  - Each pattern whose `findings` rules all reported something (and whose `when` holds) adds a `correlation:<name>` meta-finding. `when` uses the condition expression predicates (`is_draft`, `total_changes > N`, `label_exists(...)`, `branch *= "hotfix-*"`, ...); a malformed expression logs a warning and the pattern does not match. The matched findings are raised to the pattern `severity` when it is higher, and tagged `correlationUpgradedBy`. `message` defaults to `Correlated findings: a + b`.
- `enabled: false` turns correlation off, built-in patterns included.

**Output:**
```
[INFO] Correlation merged 2 same-location AI finding(s)
[INFO] Correlation engine generated 1 meta-finding(s)
```

### Contradiction Detection

**What it does:** Removes one of two contradictory AI findings, for example "add a null check" next to "remove the redundant null check".

**Config:**
```yaml
ai:
  contradictions:
    enabled: true                         # false keeps both findings
    resolution: keep-higher-confidence    # or: keep-higher-severity
```

**Behavior:**
- Two AI findings from different rules contradict when they sit on the same file within 5 lines and their messages use opposing words (add/remove, increase/decrease, split/merge, extract/inline, missing/unnecessary, ...).
- `keep-higher-confidence` (the default and the behavior without the block) keeps the finding with the higher `confidence` metadata. `keep-higher-severity` keeps the more severe one, and confidence breaks ties. When both are equal, the first finding is kept.
- Each removal is logged with the kept and the dropped finding.

**Output:**
```
[INFO] Contradiction detector removed 1 contradictory AI finding(s)
[INFO]   contradiction: kept [ai-code-quality] WARNING confidence=0.9 'Remove the redundant error handling' over [ai-review-comments] ERROR confidence=0.4 'Add error handling' (keep-higher-confidence)
```

### Feedback Learning

**What it does:** Tracks dismissed findings and uses that history to avoid repeat findings in future reviews.

**Config:**
```yaml
ai:
  feedback:
    enabled: true                  # same as `feedbackEnabled: true` (an explicit `feedbackEnabled` wins)
    skipRepeatFindingTypes: true   # skip an AI rule whose findings were dismissed at least twice
    replyJudge: off                # `llm`: the model judges replies the keyword classifier leaves unknown
    dismissed: []                  # dismissed findings; the list form `feedback: [...]` is still accepted

cache:
  state:
    enabled: true  # persists feedback history across runs
```

**Behavior:**
- Tracks dismissed findings by (rule, file, message pattern)
- Adds dismissal history to prompt for subsequent reviews
- With `skipRepeatFindingTypes: true`, skips rules dismissed at least twice
- Logs skip reason
- Learns from the platform: every inline finding comment ends with a hidden `<!-- karandash-finding {...} -->` marker. In live mode the run reads the marked comments a reviewer dismissed and records them like `ai.feedback.dismissed` entries (once per finding):
  - GitHub: a thumbs-down reaction on the review comment
  - GitLab: the thread resolved by someone other than the comment author
  - Azure DevOps: the thread set to "Won't fix" or "By design"
  - Gitea, Bitbucket and Gerrit expose no dismissal signal; use `ai.feedback.dismissed` there
- Learns from project members' replies in karandash inline threads. A keyword classifier reads each reply: a rebuttal
  ("by design", "already handled", "false positive", "так задумано") dismisses the finding even next to "thanks"; an
  acknowledgement ("fixed", "исправил") accepts it, and one that cites a fix commit sha is accepted even when long; a
  question, a quote or a hedged reply ("верно", "valid") stays unknown and teaches nothing
- With `replyJudge: llm`, a reply the classifier leaves unknown goes to the run's model (`ai.model`, else the provider's
  cheap model) with the finding text, both redacted and fenced as data. At most 50 calls per run; a provider error, an
  unreadable answer or a spent cap leaves the reply unknown. Off in a dry run. Replies are re-read from the threads
  every run, so the judge sees an unknown reply again on the next run

**Output:**
```
[DEBUG] Feedback learning: 3 previous dismissals found
        - ai-code-suggestions: "unused import warning" (dismissed 2x)
        - ai-description-quality: "description too short" (dismissed 1x)
[INFO] Skipping ai-code-suggestions (dismissed 2 times previously)
```

### Review Learnings

**What it does:** Applies what reviewers rejected before. `calibrate --corpus DIR --learn` writes a learnings file from a `stats --audit` corpus; the AI review reads it from the target branch and suppresses or downgrades matching findings.

**Config:**
```yaml
ai:
  learnings:
    enabled: true                    # default true
    file: .karandash/learnings.yaml  # default; read from the MR's target branch, never the MR head
    min-rejections: 5                # default 5; rejections a rule needs before calibrate --learn writes an entry
```

**Behavior:**
- Only AI findings are affected; a learning naming a deterministic rule id does nothing
- An entry `{rule, path?, action: suppress|downgrade, evidence}` drops the finding (`suppress`) or lowers it one severity level (`downgrade`); the most specific matching entry wins (longest `path` glob, a rule-only entry last)
- The entries are sent to the model as one never-cut instruction
- `calibrate --learn` keeps hand-written entries (no `evidence` block) of an existing file, regenerates the rest and prints the kept, added and removed counts

### Rule Gating

**What it does:** Skips expensive AI rules based on MR characteristics (e.g., don't run security scan on docs-only changes).

**Config:**
```yaml
ai:
  gates:
    - ruleId: ai-security-scan
      filePatterns: ["**/*.kt", "**/*.java", "**/*.go"]
      minChangedLines: 5
      skipLabels: ["documentation", "chore"]
      skipIfTitleMatches: "^(docs|chore):"

    - ruleId: ai-code-suggestions
      filePatterns: ["src/**"]
      requireLabel: "ready-for-review"
```

**Behavior:**
- Before executing rule, checks gate conditions
- Skips rule if conditions not met
- Logs skip reason with condition details

**Output:**
```
[DEBUG] Gate check: ai-security-scan
        ✗ File patterns don't match (only changes: *.md, *.yaml)
        → SKIPPED
```

```
[DEBUG] Gate check: ai-code-suggestions
        ✓ File patterns match (src/main.kt, src/util.kt)
        ✓ Changed lines: 42 > 5
        ✓ Label "ready-for-review" present
        → EXECUTE
```

### Semi-Formal Reasoning

**What it does:** Uses structured certificate-based reasoning inspired by Meta's "Agentic Code Reasoning" paper to force explicit premises, execution traces, and evidence citations before producing findings. Significantly reduces hallucinated results.

**Config:**
```yaml
ai:
  reasoning: semi-formal    # direct (default) | semi-formal
  # or per-rule:
  review-comments:
    reasoning: semi-formal
  risk-scoring:
    reasoning: semi-formal
```

**Behavior:**
- Wraps analyzer prompts with a structured certificate template
- Requires the LLM to state definitions, premises, and trace execution paths
- Every finding must cite specific code evidence (file:line + exact snippet)
- Citations are deterministically verified against the actual diff
- Findings without verified evidence are dropped
- Self-critique: each response's certificates are checked again after parsing; a finding located at a citation whose snippet is not in the diff is dropped, and the verified/unverified counts are logged
- Supported analyzers: review-comments, code-suggestions, risk-scoring
- Token cost: ~1.7-2x compared to direct mode

**Example Output:**
```json
{
  "ruleId": "security-scan",
  "message": "Unvalidated SQL parameter in query",
  "location": {"file": "db/UserDao.kt", "line": 42},
  "severity": "error",
  "metadata": {
    "reasoning": [
      "1. Found SQL query: 'SELECT * FROM users WHERE id = ' + userId",
      "2. Parameter 'userId' comes from request: userId = request.getParameter('id')",
      "3. No validation call on userId before SQL usage",
      "4. Risk: SQL injection if userId contains ' OR '1'='1"
    ],
    "evidence": "String query = \"SELECT * FROM users WHERE id = \" + userId;"
  }
}
```

### Process Risk

**What it does:** Scores MR risk based on process metrics like file churn, file diversity, and change density.

**Config:**
```yaml
ai:
  processRisk:                # or `processRisk: true`
    enabled: true
    factors:
      changeChurn: true        # gross lines changed relative to the net change
      fileDiversity: true      # many directories touched = higher risk
      changeDensity: true      # few commits spread over many files = higher risk
      authorFamiliarity: true  # files the author has not changed before (0.5 without history)
```

**Behavior:**
- Calculates a risk score from 0.0-1.0; disabled factors are left out and the remaining weights rescaled
- Adds an `ai-process-risk` INFO finding with the score, level and per-factor metadata
- Logs the breakdown of risk factors
- The score drives [Model Routing](#model-routing)

**Output:**
```
[INFO] Process risk assessment: 0.72 (HIGH)
- Change churn: 0.85
- File diversity: 0.60
- Change density: 0.75
- Author familiarity: 0.50
```

### Model Routing

**What it does:** Cheap-model triage: rules run on an inexpensive model unless the MR's process risk is high.

**Config:**
```yaml
ai:
  routing:
    enabled: true
    strategy: cost-optimized   # or quality-first: always the rule's tier
    riskThreshold: 0.5         # process risk at or above this escalates
    tiers:                     # strongest first, cheapest last
      - name: strong
        models: [claude-sonnet-4-6]
      - name: cheap
        models: [claude-haiku-4-5]
    rules:
      - match: security        # substring of the rule id
        tier: strong
```

**Behavior:**
- Below `riskThreshold`, a rule runs on the first model of the last (cheapest) tier.
- At or above it, the rule runs on the first model of the tier its `rules` entry names (default: the first tier).
- An explicit `ai.models.<rule>` entry wins over routing.
- Logs the routed model at debug level.

---

## Research Metrics

Advanced metrics for understanding change characteristics and risk.

### Change Entropy

**What it does:** Calculates Shannon entropy of change distribution across files, detecting concentrated vs scattered changes.

**Config:**
```yaml
ai:
  metrics:
    changeEntropy:
      enabled: true
      includeInMetadata: true
```

**Behavior:**
- Calculates the normalized Shannon entropy (0.0–1.0) of the change across files and directories
- Above `ai.pipeline.thresholds.highEntropy` it adds an INFO `metrics:change-entropy` finding ("Shotgun surgery detected")
- `includeInMetadata: true` adds the entropy (two decimals) to every finding's metadata as `changeEntropy`

**Output:**
```
[INFO] metrics:change-entropy: Shotgun surgery detected: changes spread across 12 files in 7 directories (entropy: 0.92)
```

### Cognitive Load

**What it does:** Estimates review complexity based on cyclomatic complexity delta, number of files, and change concentration.

**Config:**
```yaml
ai:
  metrics:
    cognitiveLoad:
      enabled: true
      estimatedReviewTime: true  # include time estimate
```

**Behavior:**
- Estimates review time (minutes) from changed lines and files, and scores the load LOW/MEDIUM/HIGH
- A HIGH load adds a WARNING `metrics:cognitive-load` finding with `estimatedReviewMinutes`, `totalLoc` and `riskLevel` metadata
- `estimatedReviewTime: true` reports the estimate for every MR: an INFO finding when the load is not HIGH, and the estimate appended to the HIGH warning

**Output:**
```
[INFO] metrics:cognitive-load: Cognitive load MEDIUM: estimated review time ~18 min
```

### Hotspot Analysis

**What it does:** Identifies high-churn + high-complexity areas (hotspots) that need extra scrutiny.

**Config:**
```yaml
ai:
  metrics:
    hotspotAnalysis:
      enabled: true
      churnThreshold: 50       # changed lines at which the churn score saturates (default 200)
      complexityThreshold: 10  # changed lines at which the complexity score saturates (default 100)
```

`hotspotAnalysis` and `hotspot` are the same metric; `ai.metrics.hotspot: true` keeps working.

**Behavior:**
- Scores each changed file by churn and complexity, both estimated from its changed lines in this MR (lines ÷ threshold, capped at 1.0)
- Files whose average score is at least 0.7 are hotspots; they are listed in one INFO `metrics:hotspot` finding
- Lower thresholds flag smaller changes

**Output:**
```
[INFO] metrics:hotspot: Hotspot files detected: auth/login.kt — high churn + complexity
```

### Adaptive Budget

**What it does:** Splits the per-rule token budget (`ai.maxTokenBudget`) by how often each rule found something in past runs, instead of evenly.

**Config:**
```yaml
ai:
  costBudget:
    adaptiveAllocation: true
```

**Behavior:**
- Reads the last 100 run records from the `ai.statistics` store (`.karandash/stats` by default)
- With fewer than 10 recorded runs, every rule gets an even share
- Otherwise rules are ranked by finding rate; each gets its estimated token cost in that order until the budget runs out, rules left over get 500 tokens, and any remainder goes to the top rule
- A registered `AiTokenAllocatorModule` still takes precedence
- Logs the allocation once at init

**Output:**
```
AI plugin: Adaptive budget allocation (50000 tokens): ai-security-scan=3000, ai-risk-scoring=45000, ai-code-quality=2000
```

### Prompt Dump

**What it does:** Writes every AI request to files, after redaction and byte for byte as the provider gets it, so you can see what a rule sent.

**Config:**
```yaml
ai:
  debug:
    prompt: artifact                     # off (default) | artifact
    prompt-dir: .karandash/debug/prompts # default
```

**Behavior:**
- One set of files per request: `<rule>-<n>.prompt.txt`, plus `<rule>-<n>.system.txt` and `<rule>-<n>.context-<i>.txt` when the request has them
- Taken after the secret redactors and interceptors, at the provider call (in an AI dry run, at the point the request would be sent)
- There is no mode that posts the prompt to the MR: the prompt carries project source
- The dumps hold project source code: when the dump creates the directory it writes a `.gitignore` of `*` into it, so they are not committed; a directory that already exists (the repo root, `.`) gets none, so pick a fresh one. Do not upload the directory as a CI artifact of a public pipeline
- A file that cannot be written is logged as a warning; the rule still runs
- `karandash validate --dry-run --show-prompt` runs the whole pipeline with `dry-run-scope: all` and an AI dry run and dumps every prompt: no provider call, no MR comment

### Temporal Escalation

**What it does:** Tracks cross-MR author violation history and escalates severity for repeat offenders.

**Config:**
```yaml
ai:
  temporal:
    enabled: true
    trackViolations: true   # false: escalate from stored history without recording this MR
    violationMemory: 90     # days a violation counts; omit to count every stored one
    escalationFactor: 1.5   # divides the repeat (2) and habitual (5) thresholds
```

`temporal: true` is shorthand for `{ enabled: true }`; `ai.pipeline.temporalEscalation: true` still turns escalation on with the defaults.
History is kept in the AI state store, so escalation needs one (it is skipped without it).

**Behavior:**
- Counts each author's prior non-INFO findings per rule across MRs (the last 10 MRs per rule, within `violationMemory` days)
- At the repeat threshold (`ceil(2 / escalationFactor)`) an INFO finding becomes WARNING
- At the habitual threshold (`ceil(5 / escalationFactor)`) the finding becomes ERROR
- Escalated findings carry `temporalEscalation` and `priorViolations` metadata
- After escalation the MR's own violations are recorded, unless `trackViolations: false`

**Output:**
```
[INFO] Temporal escalation: 1 finding(s) escalated for repeat author violations
```

### Calibration

**What it does:** At the end of every run, analyzes the recorded run history (`ai.statistics`) and logs which rules fire too often or too rarely. `karandash calibrate` prints the same analysis on demand.

**Config:**
```yaml
ai:
  calibration:
    enabled: true
    historyWindow: 180  # only runs recorded in the last 180 days
    rules:
      - ruleId: ai-risk-scoring
        targetFalsePositiveRate: 0.5   # report LOOSEN when it fires on more than 50% of MRs
      - ruleId: ai-security-scan
        targetFalsePositiveRate: 0.1
```

**Behavior:**
- No labelled outcomes are recorded, so a rule's fire rate (share of runs with at least one finding) stands in for its false-positive rate
- Above the target (default 80%): LOOSEN; below 5%: TIGHTEN; never fires: REVIEW
- Without `rules`, every rule seen in the history is analyzed against the default target
- Needs 20 runs in the window; with fewer, nothing is logged
- `calibration: true` is shorthand for `{ enabled: true }`

**Output:**
```
[INFO] Rule calibration report (64 runs)
  ai-risk-scoring: fire rate 72% (target 50%) -> LOOSEN: Rule fires on 72% of MRs - threshold may be too strict
  ai-security-scan: fire rate 0% (target 10%) -> REVIEW: Rule never fires - may be misconfigured or unnecessary
```

### Clone Detection

**What it does:** Uses normalized compression distance (NCD) to detect similar code patterns and identify clones/near-duplicates.

**Config:**
```yaml
ai:
  cloneDetection:
    enabled: true
    threshold: 0.85  # minimum similarity reported (default 0.75)
    onDetection: warn  # WARNING findings; anything else (default `info`) reports INFO
```

`cloneDetection: true` is shorthand for `{ enabled: true }`. The block enables the metric on its own; `ai.metrics.cloneDetection: true` also enables it with the defaults.

**Behavior:**
- Compares the added lines of every pair of changed files by normalized compression distance (NCD)
- Pairs with similarity (1 − NCD) of at least `threshold` are reported, up to `ai.pipeline.cloneDetection.maxFindings` (default 3)
- Only files in the MR are compared, not the rest of the repository

**Output:**
```
[INFO] metrics:clone-detection: Potential code clone detected: src/UserService.kt ↔ src/AdminService.kt (similarity: 91%)
```

### Naturalness

**What it does:** Scores code "naturalness" using token surprise/entropy, detecting unusual or suspicious code patterns.

**Config:**
```yaml
ai:
  naturalness:
    enabled: true
    flagUnusualPatterns: true
    surpriseThreshold: 0.7  # 0.0-1.0, higher = more unusual
```

`naturalness: true` is shorthand for `{ enabled: true }`. The block enables the metric on its own; `ai.metrics.naturalness: true` also enables it with the defaults.

**Behavior:**
- Trains a token trigram model on the diff and scores each file's added tokens by surprise (negative log-probability)
- With `surpriseThreshold`, a file is unusual when `1 − e^(−average surprise)` exceeds it; without it, when more tokens than `ai.pipeline.thresholds.highSurpriseTokens` are outliers
- Unusual files are listed in one INFO `metrics:naturalness` finding; `flagUnusualPatterns: false` logs them instead

**Output:**
```
[INFO] metrics:naturalness: Unusual code patterns in: src/main.kt — may warrant closer review
```

### Rule Sandbox

**What it does:** Sets defaults for `karandash test-rule`, which replays a rule on given MRs (`--mr-url`, `--snapshot`) or summarizes its recorded history.

**Config:**
```yaml
ai:
  sandbox:
    historyWindow: 30   # history mode replays the last 30 recorded runs (default 50)
    reportMetrics: true # also print affected MRs and severity distribution
```

**Behavior:**
- `--replay-count` on the command line wins over `historyWindow`
- `reportMetrics` applies to both replay and history mode

**Output:**
```
Rule Sandbox: ai-risk-scoring
==================================================
Replayed: 30 MR(s)
Fire rate: 40%
Pass: 18 | Fail: 12
Affected MRs: 12
Severity: WARNING=12
```

---

## Configuration

Advanced enforcement and compliance features.

### Execution Profiles
**What it does:** Picks a run mode by name. A profile decides which AI analyzers run, whether AI runs at all, how long analyzers may keep starting, and which configured rules keep their findings.

**Config:**
```yaml
ai:
  executionProfile: ci  # or: pre-push, local, security-audit, or a name from executionProfiles
  executionProfiles:
    quick:
      includeRules: [title-format, branch-naming]  # substring match, case, - and _ ignored
      excludeRules: []
      aiEnabled: true
      totalTimeout: 30s  # analyzers that would start later are skipped
```

**Built-in profiles:**

| Profile | AI | Rules kept | totalTimeout |
|---------|----|-----------|--------------|
| `ci` | off | all except IDs containing `ai-` | none |
| `pre-push` | off | `title-format`, `commit-messages`, `branch-naming`, `draft` | 3s |
| `local` | on | all | none |
| `security-audit` | on | IDs containing `security`, `codeowners`, `approval`, `ai-security` | none |

`includeRules`/`excludeRules` apply to the AI analyzers and to the findings of the rules configured under `rules:`; the deterministic rules still run, their findings are dropped. A finding from any other source (engine, AI) is kept. `exitOnError` and `verbose` are accepted but have no effect; a profile that sets them logs a warning.

### Progressive Enforcement
**What it does:** Time-based rollout from warn → enforce, allowing teams to adopt strict rules gradually.

**Config:**
```yaml
ai:
  enforcementSchedule:
    - from: "2026-01-01"
      until: "2026-03-31"
      mode: warn           # phase 1: warnings only
    - from: "2026-04-01"
      until: "2026-06-30"
      mode: warn-errors    # phase 2: warnings + critical errors
    - from: "2026-07-01"
      mode: enforce        # phase 3: full enforcement
```

**Behavior:**
- Checks enforcement schedule at validation time
- Downgrades/upgrades severities based on current phase
- Logs current enforcement phase

**Output:**
```
[INFO] Progressive enforcement phase: WARN (until 2026-03-31)
       All findings capped at WARNING severity
       Critical findings demoted: ERROR → WARNING
```

### Environment Tracks

**What it does:** Applies different rules to different target branches (e.g., stricter rules for main vs permissive for feature branches).

**Config:**
```yaml
ai:
  environmentTracks:
    - targetBranch: "main|master"
      rules:
        ai-security-scan:
          enabled: true
          enforceSeverity: error
        size-limits:
          maxFiles: 20

    - targetBranch: "develop"
      rules:
        ai-security-scan:
          enabled: true
        size-limits:
          maxFiles: 50

    - targetBranch: "feature/.*"
      rules:
        ai-security-scan:
          enabled: false  # skip for feature branches
        size-limits:
          maxFiles: 100
```

**Behavior:**
- `environmentTracks` is a short form of `ai.tracks`: each entry becomes a track named after `targetBranch`, whose pattern must match the whole target branch name (`main|master` matches `main` but not `maintenance`). The first matching track wins; `ai.tracks` entries come first.
- A rule set to `enabled: false` is added to the track's `disableRules`, so that AI rule is skipped before any provider call (`enableRules`/`disableRules` of `ai.tracks` now apply to AI rules too).
- For every finding, the matching rule setting adjusts the result: `enforceSeverity` (or `severity`, or a bare severity string) sets the severity; `enabled: false` drops the finding. Other settings in the block (such as `maxFiles`) are not applied - configure those on the rule itself.
- Logs the applied track at info level.

**Output:**
```
[INFO] Environment track 'main|master': rule settings applied for target main
```

### Team Scoping

**What it does:** Allows different rules for different parts of monorepo (team ownership).

**Config:**
```yaml
ai:
  teams:
    - name: platform
      owns: ["infra/**", "k8s/**"]
      rules:
        ai-security-scan:
          enabled: true
        size-limits:
          maxFiles: 30

    - name: api
      owns: ["services/api/**", "lib/api/**"]
      rules:
        ai-security-scan:
          enabled: true
        ai-performance-scan:
          enabled: true
        size-limits:
          maxFiles: 40
```

**Behavior:**
- Determines affected teams from the changed files matching `owns`.
- `rules` is another name for `ruleOverrides`. For a finding on a file a team owns, that team's setting for the finding's rule applies: `enforceSeverity` (or `severity`, or a bare severity string) sets the severity; `enabled: false` drops the finding. Other settings (such as `maxFiles`) are not applied. Findings without a file location are left alone.
- Logs affected teams at info level.

**Output:**
```
[INFO] Team scoping: affected teams: platform, api
```

### Severity Reclassification

**What it does:** Allows per-rule severity overrides (e.g., downgrade size-limits from ERROR to WARNING).

**Config:**
```yaml
ai:
  severityOverrides:
    - rule: size-limits
      reclassify:
        error: warning
        warning: info

    - rule: ai-security-scan
      reclassify:
        warning: error  # upgrade security warnings

    - rule: "*"  # wildcard: all rules
      minimumSeverity: warning  # no INFOs from any rule
```

**Behavior:**
- `rule` matches as a substring of the finding's rule id; `*` matches every rule
- The first matching override with `reclassify` or `suppress` remaps or drops the finding; the old severity and `reason` go into the finding metadata
- `minimumSeverity` (`error`, `warning`, `info`) then drops matching findings that are less severe after reclassification. An override with only `minimumSeverity` does not stop a later override from reclassifying

**Output:**
```
[DEBUG] Severity reclassification
        size-limits: ERROR → WARNING (overridden)
        ai-code-suggestions: INFO → (filtered, below minimumSeverity)
```

### Conditional Sections

**What it does:** Dynamically includes/requires MR description sections based on files changed.

**Config:**
```yaml
ai:
  conditionalSections:
    - section: "## Database Changes"
      whenFilesChanged: ["**/*.sql", "**/migrations/**"]
      required: true
      description: "Describe schema changes, data migration, and rollback plan"

    - section: "## Security Review"
      whenFilesChanged: ["**/auth/**", "**/crypto/**"]
      required: true

    - section: "## Performance Impact"
      whenFilesChanged: ["**/db/**", "**/cache/**"]
      required: false  # nice to have
```

**Behavior:**
- Scans changed files against `whenFilesChanged` globs
- If one matches and the description lacks the section heading, adds a `conditional-sections` finding
- `required: true` (default) uses `severity` (default `warning`); `required: false` adds an INFO "recommended section" finding instead
- `description` is appended to the finding message to say what the section should contain

**Output:**
```
[INFO] Conditional description sections
       Database changes detected → requires "## Database Changes"
       Status: ✓ PRESENT (valid)
```

```
[WARN] Required section missing
       Security changes detected → requires "## Security Review"
       Status: ✗ MISSING
```

### Canary Promotion

**What it does:** Gradually rolls out new rules through shadow → canary → enforce pipeline.

**Config:**
```yaml
ai:
  canary:
    ai-new-ml-rule:
      stage: shadow
      # No-op: collects data but doesn't affect findings

    ai-experimental-security:
      stage: canary
      percentOfMrs: 10  # apply to 10% of MRs
      # Findings visible but don't block MRs

    ai-refactor-detector:
      stage: enforce
      # Full enforcement
```

**Stages:**
- `shadow`: the rule is skipped silently (metric collection only)
- `canary`: the rule runs on `percentOfMrs` percent of MRs (default 100)
- `enforce`: full enforcement; `percentOfMrs` is ignored

**Behavior:**
- Checks the rule stage before any provider call.
- In the `canary` stage, an MR is in the rollout when a stable hash of the rule id and MR id falls below `percentOfMrs`. The same MR always gets the same answer, so re-runs do not flip. Outside the rollout the rule is skipped.
- Logs the skip at info level.

**Output:**
```
[INFO] AI plugin [ai-new-ml-rule]: canary shadow mode — running silently
[INFO] AI plugin [ai-experimental-security]: canary — MR outside the 10% rollout
```

### Inline Overrides

**What it does:** Allows authors to override findings using special tags in MR description.

**Config:**
```yaml
ai:
  inlineOverrides:
    enabled: true
    overrideTag: "override"  # or: waive, exempt, etc.
    requireApproval: true    # need reviewer approval for override
    auditLog: true
```

**Tags in MR Description:**
```
<!-- @karandash override ai-size-limits: Large refactor necessary -->
<!-- @karandash waive ai-security-scan: False positive on library code -->
```

**Behavior:**
- Reads `<!-- @karandash <tag> <rule>: <reason> -->` tags from the MR description. The tag is `overrideTag` (default `override`), `waive` or `exempt`.
- Findings of the named rule become INFO, with the message prefixed `[OVERRIDDEN: <reason>]`. They are kept, not removed.
- `requireApproval: true` honors the tags only when the MR has at least one approval; otherwise they are ignored with a warning.
- `auditLog: true` logs each overridden finding at info level and records `overrideReason` in its metadata.
- The `<!-- karandash-override rule: <rule> reason: "..." -->` form works without this block; `enabled: false` turns it off too. `inlineOverrides: true` is shorthand for `{ enabled: true }`.

**Output:**
```
[INFO] Inline override: [ai-size-limits] ERROR → INFO (reason: Large refactor necessary) — MR exceeds 500 lines
```

### Notification Routing
**What it does:** Routes findings to different notification channels based on rule and severity.

**Config:**
```yaml
ai:
  notifications:
    - match:
        rule: security
        severities: ["error"]
      targets:
        - channel: slack
          target: "#security-alerts"

    - match:
        rule: ai-performance
        severities: ["warning", "error"]
      targets:
        - channel: email
          target: "performance-team@example.com"
        - channel: slack
          target: "#performance-reviews"

    - match:
        severities: ["error"]
      targets:
        - channel: slack
          target: "#mr-blockers"
```

**Behavior:**
- After validation, matches findings to routes
- Sends notifications to configured channels
- Logs routing decisions

### Violation Budget

**What it does:** Allows legacy code to accumulate violations (debt ratchet), enforcing no regression.

**Config:**
```yaml
ai:
  violationBudget:
    enabled: true
    baselineFile: ".karandash/violation-baseline.json"
    incrementPerMonth: 0  # no new violations allowed
    baselineDate: "2026-01-01"  # start of the monthly increment
```

`baselineFile` holds a JSON object of file path to tolerated violation count:

```json
{ "auth/login.kt": 3, "legacy/Parser.kt": 12 }
```

**Behavior:**
- Counts ERROR and WARNING findings per file.
- A file whose count is within its baseline plus `incrementPerMonth` for each month since `baselineDate` is known debt: its findings become INFO (metadata `violationBudgetFrom`).
- A file over its allowance keeps its findings and is logged as OVER BUDGET. A file missing from the baseline has an allowance of 0.
- Findings without a file location are left alone. A missing baseline file counts as empty; an unreadable one logs a warning and leaves findings unchanged.
- The rule-level `ai.violationBudgets` list (`rule`, `baseline`, decay) still applies on its own, the same way per rule: a rule whose ERROR/WARNING findings are within its decayed allowance has them demoted to INFO (`violationBudgetFrom`); a rule over it keeps them and is logged as OVER BUDGET.

**Output:**
```
[INFO] Violation budget: utils.kt OVER BUDGET (1 violations, allowance 0)
[INFO] Violation budget: 1 file(s) within baseline, 1 over
```

### Grace Periods

**What it does:** Enables temporary override windows for emergency situations.

**Config:**
```yaml
ai:
  gracePeriod:
    enabled: true
    durationMinutes: 60
    approvalRequired: 2  # need 2 approvals to enter grace period
    auditLog: true
```

**Activation:**
```
<!-- @karandash grace-period-start: Emergency hotfix, temporarily disabling checks -->
<!-- @karandash grace-period-end -->
```

**Behavior:**
- A window is open when the description has a `grace-period-start` tag not followed by a `grace-period-end` tag.
- The window is in force once the MR has `approvalRequired` approvals. While in force, every finding becomes INFO (metadata `gracePeriodFrom`).
- The window starts at the approval that met `approvalRequired` and ends `durationMinutes` later. With `approvalRequired: 0`, or when that approval has no timestamp, the window has no known start and does not expire.
- An open window that lacks approvals or has expired changes nothing and logs a warning. `auditLog: true` logs the active window at warn level.
- The rule-level `ai.gracePeriods` list (label/branch conditions) still applies on its own.

**Output:**
```
[WARN] Grace period active: 'Emergency hotfix' (approvals 2/2, expires 2026-03-16T11:30:00Z); findings downgraded to INFO
```

### Rule Composition

**What it does:** Combines multiple rule results using Boolean logic to detect compound risks.

**Config:**
```yaml
ai:
  compositions:
    - name: large-untested
      when: "rule_failed(size-limits) && !rule_failed(test-coverage)"
      severity: error
      message: "Large MR without test coverage increase - high risk"

    - name: security-in-unreliable-code
      when: "rule_failed(ai-security-scan) && high(ai-risk-scoring)"
      severity: error
      message: "Security issue in high-risk code path"

    - name: refactor-with-config-changes
      when: "rule_failed(file-changes) && keyword_match(title, 'refactor')"
      severity: warning
      message: "Refactoring + config changes - ensure no unintended side effects"
```

The condition goes in `when` (`expression` is the same key under its older name).

**Operators:**
- `rule_failed(id)`: a rule whose id contains `id` produced any finding
- `rule_passed(id)`: no such rule produced a finding
- `high(id)`, `medium(id)`, `low(id)`: such a rule produced an ERROR, WARNING or INFO finding
- `keyword_match(field, pattern)`: regex match on `title`, `description`, `source_branch` or `target_branch` (an invalid regex never matches)
- `file_match(pattern)`: glob match on changed file paths
- `&&`, `||`, `!` and `not(...)`: Boolean logic (`&&` binds before `||`; no parentheses for grouping)

**Behavior:**
- Evaluates each composition's condition
- Adds meta-finding if condition true
- Logs composition matches

**Output:**
```
[INFO] Composition matched: large-untested
       Condition: size-limits:ERROR && !test-coverage
       New finding: ERROR "Large MR without test coverage increase"
```

### Rule Macros

**What it does:** Defines reusable policy bundles that can be referenced across configs.

**Config:**
```yaml
ai:
  macros:
    strict-security:
      - rule: ai-security-scan
        enabled: true
        enforceSeverity: error
      - rule: ai-risk-scoring
        enabled: true
        minConfidence: 0.8
      - rule: file-changes
        blockPatterns: ["**/*.env", "**/secrets/**"]

    fast-review:
      - rule: ai-risk-scoring
        enabled: true
      - rule: ai-description-quality
        enabled: true
      # other rules disabled

  # Apply macros
  applyMacros:
    - strict-security
    - fast-review
```

**Behavior:**
- `macros` is a short form of `ai.macroDefinitions` (each `- rule:` entry becomes that rule's settings) and `applyMacros` of `ai.macroReferences` applied to every file (`whenFilesMatch: ["**"]`). On a name clash `macroDefinitions` wins.
- For every finding, the applied macros' setting for its rule adjusts the result: `enforceSeverity` (or `severity`, or a bare severity string) sets the severity; `enabled: false` drops the finding. When several macros set the same rule, the later one wins. Other settings (`minConfidence`, `blockPatterns`) are not applied - configure those on the rule itself.
- Logs the number of expanded macros at info level.

**Output:**
```
[INFO] Rule macros: 2 macro(s) expanded
```

### Author Seniority

**What it does:** Adjusts severity based on author experience level (junior/senior).

**Config:**
```yaml
ai:
  seniorityTiers:
    - level: junior
      matchTeams: ["interns", "new-hires"]
      matchAuthors: ["alice", "bob"]
      severityAdjustment:
        error: error         # no change for errors
        warning: error       # elevate warnings for juniors
        info: warning        # elevate infos to warnings

    - level: senior
      matchAuthors: ["charlie", "diana"]
      severityAdjustment:
        warning: info        # demote non-critical for seniors
```

**Behavior:**
- A tier matches when the author's username is in `matchAuthors` (case-insensitive, leading `@` ignored), or when `matchTeams` names the author's username or one of the MR labels; the first matching tier wins
- `severityAdjustment` maps each severity to its replacement; severities not listed stay. Without it the level decides: `junior` turns ERROR into WARNING, `standard` and `senior` change nothing

**Output:**
```
[DEBUG] Author seniority: junior (alice, new-hire)
        Finding: "missing-error-handling" (would be WARNING)
        Adjustment: WARNING → ERROR (junior developer)
```

### AI Cost Budget

**What it does:** Caps tokens per run, with per-branch token overrides, and reports the run's estimated cost.

**Config:**
```yaml
ai:
  costBudget:
    maxTokensPerRun: 50000      # global default
    onBudgetExceeded: skip-remaining   # or warn
    branchBudgets:
      - pattern: "hotfix/"
        maxTokensPerRun: 100000  # more for urgent fixes
      - pattern: "docs/"
        maxTokensPerRun: 2000    # minimal for docs
    costEstimate:
      enabled: true              # false omits the end-of-run cost summary
      sendAlert: true
      alertThreshold: 1.00       # warn if the run's estimated cost exceeds $1.00
```

**Behavior:**
- The first `branchBudgets` entry whose pattern matches the source branch sets the token limit
- Once the limit is reached, remaining AI rules are skipped (`skip-remaining`) or only logged (`warn`)
- `maxCostPerRunUsd` skips the remaining AI rules once the estimated spend in USD reaches it (0 turns the cap off)
- At the end of the run the cost summary prints the estimated cost and tokens per rule
- `costEstimate.alertThreshold` above 0 with `sendAlert: true` logs a warning when the estimate exceeds it

**Output:**
```
AI plugin cost estimate: $0.2500 (48210 tokens total, 41000 in / 7210 out)
  ai-security-scan: ok, 12000 tokens, 4s 100ms
  mr-summary: skipped (provider error: timed out)
  code-suggestions: from cache
```

Each rule line reads `ok, <tokens>`, `ok, no tokens reported (<time>)`, `from cache` (served by the incremental cache),
`not run` or `skipped (<reason>)`. The MR note prints the run's AI status line under its heading (`AI: ok - …`,
`AI: unavailable - 12/12 calls failed`, `AI: off by policy '<label>'`, `AI: N findings from cache`, `AI: not run`).

```
[WARN] AI plugin: Cost alert threshold exceeded: estimated cost $2.5000 > $1.0000 threshold
```

---

## Reporters

AI-powered analysis and reporting features.

The AI reports below can be turned on from the top-level `reporters:` block as well as from `ai.reports`.
A `reporters.<name>` entry is on when it is `true` or an object without `enabled: false`; its `output:` file path
gets a copy of the report. These entries are accepted only while the AI plugin is loaded.

### JUnit XML

**What it does:** Exports findings in JUnit XML format for CI integration (GitHub Actions, Jenkins, etc.).
Each rule becomes a `<testsuite>`; ERROR and WARNING findings become `<failure>` elements, INFO findings `<system-out>`.

**Config:**
```yaml
reporters:
  junit:
    output: karandash-results.xml   # file path; without it the XML goes to the interactive output
    includeAiMetadata: true         # add confidence and metadata lines to each failure (default false)
```

`output:` is first looked up as an output id (`stdout`, `file`, ...); any other value is a file path written by the file output.

**Output (with `includeAiMetadata: true`):**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<testsuites name="karandash" tests="1" failures="1" errors="0">
  <testsuite name="security-scan" tests="1" failures="1" errors="0">
    <testcase name="security-scan" classname="karandash.rules">
      <failure message="Unvalidated SQL parameter" type="ERROR">
Rule: security-scan
Severity: ERROR
Message: Unvalidated SQL parameter
Location: FileRef(path=db/UserDao.kt, line=42, endLine=null)
Confidence: 0.95
model: claude-sonnet
      </failure>
    </testcase>
  </testsuite>
</testsuites>
```

### Smart Digest

**What it does:** Summarizes the run's findings as a short natural-language digest (developer audience, no AI call).

**Config:**
```yaml
reporters:
  smartDigest: true                      # or an object:
  # smartDigest:
  #   enabled: true
  #   output: .karandash/smart-digest.json   # also write the report to this file
```

Same as `ai.reports.smart-digest: true`. Input: findings. The report is added as an INFO finding `report:smart-digest` with the `smart-digest.json` attachment.

### Check Run Annotations

**What it does:** Turns every finding that has a file location into a check-run annotation and adds the result as
an INFO finding `report:check-run-annotations` with a `check-run-annotations.json` attachment. Nothing is posted by
this report; point `output:` at a file and hand it to your CI (GitHub check-run API, GitLab Code Quality artifact).

**Config:**
```yaml
reporters:
  checkRunAnnotation:
    format: github       # github (default) or gitlab
    consolidate: true    # merge findings on the same file and line into one annotation (default false)
    output: .karandash/annotations.json
```

`format: github` writes a check-run `output` object; at most 50 annotations are kept (GitHub's per-request limit):
```json
{"title": "Karandash Validation", "conclusion": "failure", "summary": "1 errors, 0 warnings, 1 annotations",
 "annotations": [{"path": "db/UserDao.kt", "start_line": 42, "end_line": 42, "annotation_level": "failure",
                  "title": "ai-security-scan", "message": "Potential SQL injection"}]}
```

`format: gitlab` writes a [Code Quality](https://docs.gitlab.com/ci/testing/code_quality/) report (ERROR is `major`,
WARNING `minor`, INFO `info`):
```json
[{"description": "Potential SQL injection", "check_name": "ai-security-scan", "fingerprint": "<sha256>",
  "severity": "major", "location": {"path": "db/UserDao.kt", "lines": {"begin": 42}}}]
```

### Compliance Evidence

**What it does:** Builds a SOC2/ISO27001-style evidence pack for the MR: findings, MR metadata and content hashes.

**Config:**
```yaml
reporters:
  complianceEvidence: true                      # or an object:
  # complianceEvidence:
  #   enabled: true
  #   output: .karandash/compliance-evidence.json   # also write the report to this file
```

Same as `ai.reports.compliance-evidence: true`. Input: findings and the MR. The report is added as an INFO finding `report:compliance-evidence` with the `compliance-evidence.json` attachment.

### Annotated Diff HTML

**What it does:** Renders a self-contained HTML page with the MR diff and each finding inline next to its line (HTML-escaped). Skipped when the run has no findings.

**Config:**
```yaml
reporters:
  annotatedDiffHtml: true                      # or an object:
  # annotatedDiffHtml:
  #   enabled: true
  #   output: .karandash/annotated-diff-html.html   # also write the report to this file
```

Same as `ai.reports.annotated-diff-html: true`. Input: findings and the MR. The report is added as an INFO finding `report:annotated-diff-html` with the `annotated-diff-html.html` attachment.

### Team Scorecard

**What it does:** Scores rule quality from this run's findings and the recorded run history.

**Config:**
```yaml
reporters:
  teamScorecard: true                      # or an object:
  # teamScorecard:
  #   enabled: true
  #   output: .karandash/team-scorecard.json   # also write the report to this file
```

Same as `ai.reports.team-scorecard: true`. Input: findings and run history. Run history comes from `ai.statistics`; without recorded runs the report is skipped. The report is added as an INFO finding `report:team-scorecard` with the `team-scorecard.json` attachment.

### Developer Growth

**What it does:** Tracks the MR author's finding trend over the runs recorded for that author.

**Config:**
```yaml
reporters:
  developerGrowth: true                      # or an object:
  # developerGrowth:
  #   enabled: true
  #   output: .karandash/developer-growth.json   # also write the report to this file
```

Same as `ai.reports.developer-growth: true`. Input: the MR and run history. Run history comes from `ai.statistics`; without recorded runs the report is skipped. The report is added as an INFO finding `report:developer-growth` with the `developer-growth.json` attachment.

### Risk Heatmap

**What it does:** Computes finding density per file and directory.

**Config:**
```yaml
reporters:
  riskHeatmap: true                      # or an object:
  # riskHeatmap:
  #   enabled: true
  #   output: .karandash/risk-heatmap.json   # also write the report to this file
```

Same as `ai.reports.risk-heatmap: true`. Input: findings. The report is added as an INFO finding `report:risk-heatmap` with the `risk-heatmap.json` attachment.

### DORA Metrics

**What it does:** Approximates DORA metrics (lead time, change failure rate, deploy frequency) from the recorded run history.

> The recorded validation runs are the deployment proxy: validation-run frequency stands in for deploy
> frequency, and the share of runs with an error finding stands in for the change failure rate. The values are
> not read from deployment data.

**Config:**
```yaml
reporters:
  doraMetrics: true                      # or an object:
  # doraMetrics:
  #   enabled: true
  #   output: .karandash/dora-metrics.json   # also write the report to this file
```

Same as `ai.reports.dora-metrics: true`. Input: run history. Run history comes from `ai.statistics`; without recorded runs the report is skipped. The report is added as an INFO finding `report:dora-metrics` with the `dora-metrics.json` attachment.

### ROI Calculator

**What it does:** Estimates review time and cost saved by the findings in the recorded run history.

**Config:**
```yaml
reporters:
  roiCalculator: true                      # or an object:
  # roiCalculator:
  #   enabled: true
  #   output: .karandash/roi.json   # also write the report to this file
```

Same as `ai.reports.roi: true`. Input: run history. Run history comes from `ai.statistics`; without recorded runs the report is skipped. The report is added as an INFO finding `report:roi` with the `roi.json` attachment.

### Finding Transforms

**What it does:** Renders the run's findings through named templates, one line per finding. Each transform is added
as an INFO finding `report:transform-<name>` whose attachment holds the rendered text; its `output:` writes the text to a file.
A transform with no findings this run produces nothing.

**Config:**
```yaml
reporters:
  findingTransform:
    transforms:
      - name: slack-message
        template: "*{{severity}}* {{rule}}: {{message}} ({{file}}:{{line}})"
        output: .karandash/slack.txt
      - name: jira
        template: "{{rule}}|{{severity}}|{{meta.model}}"
```

Placeholders: `{{rule}}`, `{{severity}}`, `{{message}}`, `{{explanation}}`, `{{suggestion}}`, `{{file}}`, `{{line}}`,
`{{commit}}` and `{{meta.KEY}}` for any finding metadata key; a missing value becomes an empty string. Entries
without a `name` or `template` are skipped. The single-template `ai.findingTemplate` (`ai.reports.custom-findings`) still works.

### Cross-Platform Consistency

**What it does:** Compares finding rates per rule across the platforms (GitLab, GitHub, ...) recorded in the run history.

**Config:**
```yaml
reporters:
  crossPlatformConsistency: true                      # or an object:
  # crossPlatformConsistency:
  #   enabled: true
  #   output: .karandash/cross-platform-consistency.json   # also write the report to this file
```

Same as `ai.reports.cross-platform-consistency: true`. Input: run history. Run history comes from `ai.statistics`; without recorded runs the report is skipped. The report is added as an INFO finding `report:cross-platform-consistency` with the `cross-platform-consistency.json` attachment.

### Flaky Rules

**What it does:** Flags rules with inconsistent behavior across recorded runs (flip-floppers, never-fire, always-fire).

**Config:**
```yaml
reporters:
  flakyRuleDetector: true                      # or an object:
  # flakyRuleDetector:
  #   enabled: true
  #   output: .karandash/flaky-rules.json   # also write the report to this file
```

Same as `ai.reports.flaky-rules: true`. Input: run history. Run history comes from `ai.statistics`; without recorded runs the report is skipped. The report is added as an INFO finding `report:flaky-rules` with the `flaky-rules.json` attachment.

### Policy Drift

**What it does:** Compares recent per-rule finding counts against an older baseline from the run history to show regressions and improvements.

**Config:**
```yaml
reporters:
  policyDriftDetector: true                      # or an object:
  # policyDriftDetector:
  #   enabled: true
  #   output: .karandash/policy-drift.json   # also write the report to this file
```

Same as `ai.reports.policy-drift: true`. Input: run history. Run history comes from `ai.statistics`; without recorded runs the report is skipped. The report is added as an INFO finding `report:policy-drift` with the `policy-drift.json` attachment.

### AI Predictions

**What it does:** Predicts which rules are likely to fire on this MR from historical fire rates and the MR's characteristics (size, touched paths).

**Config:**
```yaml
reporters:
  aiPredictions: true                      # or an object:
  # aiPredictions:
  #   enabled: true
  #   output: .karandash/predictions.json   # also write the report to this file
```

Same as `ai.reports.predictions: true`. Input: the MR and run history. Run history comes from `ai.statistics`; without recorded runs the report is skipped. The report is added as an INFO finding `report:predictions` with the `predictions.json` attachment.

---

## Architecture

Advanced system-level features.

### Semantic Diff

**What it does:** Flags public API removed by the MR. The diff of each changed file is scanned line by line for removed
(`-`) `fun`, `class`/`interface`/`object`/`enum class` and `val`/`var` declarations that are not `private`, `internal`
or `protected`. A removed function that the same diff adds back under the same name (a signature change) is not
reported. This is a text heuristic over Kotlin-style declarations, not a compiler ABI check.

**Config:**
```yaml
ai:
  architecture:
    semanticDiff: true      # or { enabled: true }
```

**Output:** one WARNING finding per removed declaration, rule `semantic-diff:api-breakage`, located on the file:
```
[WARNING] semantic-diff:api-breakage  Public function 'authenticate' removed — potential API breakage
```

### ADR Validation

**What it does:** Validates changes against Architecture Decision Records (ADRs). For each ADR whose `affectedPaths`
globs match a changed file, every `forbiddenPatterns` entry found in that file's diff produces a finding, and every
`requiredPatterns` entry found in none of the affected diffs produces one. Patterns are plain substrings, not regexes.

**Config:**
```yaml
ai:
  adrs:
    - id: ADR-001
      name: No direct DB access from controllers
      affectedPaths: ["**/controllers/**"]
      forbiddenPatterns: ["DataSource", "jdbcTemplate", "orm.Session"]
      rationale: "Use repository pattern for data access"
      severity: error            # error | warning (default) | info

    - id: ADR-003
      name: All async code must use coroutines
      affectedPaths: ["**/*.kt"]
      forbiddenPatterns: ["Thread(", "ExecutorService", "runBlocking"]
      allowedPatterns: ["bridge-boundary:"]   # a forbidden match on a line that also holds one of these is exempt
```

With `allowedPatterns`, a forbidden pattern counts only on diff lines that contain none of the allowed patterns.

**Output:** one finding per violation, rule `adr:<id>`, the ADR `rationale` as its explanation:
```
[ERROR] adr:ADR-001  ADR 'No direct DB access from controllers' violated: forbidden pattern 'DataSource' found in controller/UserController.kt
```

### Provenance Chain

**What it does:** Builds a tamper-evident hash chain over the run's findings. Each finding is hashed together with the
previous finding's hash (the first with the config hash), so changing any finding changes every later hash. Turning
it on is the same as `ai.reports.provenance-chain: true`: when the run has findings, an INFO finding
`report:provenance-chain` carries the chain as `provenance-chain.md`.

With `persistChain`, every run (findings or not) also appends one line to that JSON Lines file. Each line's `chainHash`
is the SHA-256 of the previous line's `chainHash` and this run's `findingsHash`, so editing or deleting a line breaks
every later line. If the file's last line is not JSON, nothing is appended and a warning is logged.

**Config:**
```yaml
ai:
  architecture:
    provenanceChain:
      enabled: true                               # `provenanceChain: true` is shorthand
      persistChain: .karandash/provenance.jsonl   # optional
```

**Output:** one JSONL entry per run (hashes are SHA-256 hex):
```json
{"timestamp":"2026-03-16T10:30:00Z","mrId":"123","findingCount":3,"configHash":"9f2c…","mrSnapshotHash":"41ab…","findingsHash":"d07e…","previousHash":"77c1…","chainHash":"e5a0…"}
```

### Cross-MR Dependencies

**What it does:** Compares the MR with other open MRs into the same target branch and warns when both change the
same file, or when this MR deletes or renames a file another MR still changes. In live mode the platform service
lists the open MRs into the target branch (GitLab, GitHub, Bitbucket Cloud, Gitea, Azure DevOps and Gerrit; at most
20, most recently updated first) and each one is fetched for its file list. When the service cannot list them, the
listing fails, or the run reads a local MR file, the check falls back to snapshots: each run records the MR it
validated in `{statistics dir}/mr-snapshots.json` (paths and metadata, no diffs; at most 200 entries) and compares
against the entries recorded within `detectionWindow`. For an ad-hoc comparison of explicit MRs use `karandash deps`.

**Config:**
```yaml
ai:
  dependencies:
    enabled: true          # `dependencies: true` is shorthand
    checkConflicts: true   # false: skip the shared-file findings, keep delete/rename ones
    detectionWindow: 7     # days a validated MR counts as a sibling
```

**Output:** WARNING findings with rule `cross-mr-dependency`:
```
[WARNING] cross-mr-dependency  MR 'Refactor auth' (456) also changes services/auth.ts: coordinate the merge order
```

### Policy Verification

**What it does:** Checks the top-level `policies:` block on every run, the same check as `karandash verify-policy`.
Each policy effect (`enabled`, `severity`, `autoApprove`, `autoFix`, `scripts`, `dangerfile`) is checked on its own:
two policies with the same `when:` condition that set the same effect to different values on overlapping `modules`
contradict each other.

**Config:**
```yaml
ai:
  architecture:
    policyVerification:
      enabled: true          # `policyVerification: true` is shorthand
      reportIssues: true     # false: log the issues as warnings instead of adding findings
```

**Output:** one finding per issue, rule `policy-verification`; a contradiction is a WARNING, other issues are INFO:
```
[WARNING] policy-verification  Contradiction: 'strict (severity)' (severity=error) conflicts with 'lax (severity)' (severity=info) on same scope
```

### Rule Mutation Testing

**What it does:** On each run, validates the built-in synthetic bad MRs (oversized MR, bad title, empty description,
draft, leaked secret, missing JIRA ticket, bad branch name) with this config's rules and reports how many each target
rule caught. The AI analyzers are left out, so it makes no LLM calls; the rule cache and platform are not used either.
For one rule, or more synthetic MRs, use `karandash mutate`.

**Config:**
```yaml
ai:
  mutationTesting:
    enabled: true   # `mutationTesting: true` is shorthand
```

**Output:** INFO findings with rule `mutation-testing`: the score, then one per mutation that escaped:
```
[INFO] mutation-testing  Rule mutation score 71% (5/7 caught)
[INFO] mutation-testing  Mutation 'no-jira-ticket' escaped: MR without JIRA ticket reference; jira-ticket did not fire
```

---

## CLI Commands

### validate
```bash
karandash validate --mr-id 123 --config .karandash/config.yaml
```

Standard MR validation with all configured rules.

### calibrate
```bash
karandash calibrate --rule ai-risk-scoring --window 90 --target-fp-rate 0.05
```

Analyzes the recorded validation history (`--stats-dir`, default `.karandash/stats`) and recommends a
threshold change per rule. `--rule` limits the analysis to one rule, `--window` to runs recorded in the
last N days. No labelled false-positive outcomes are recorded, so the **fire rate** (share of runs in which
the rule produced a finding) is the proxy: `--target-fp-rate` sets the fire rate above which a rule is
recommended `LOOSEN` (default 0.8). Rules firing on under 5% of runs get `TIGHTEN`, rules that never fire `REVIEW`. At least
`--min-samples` runs (default 20) are needed.

**Output:**
```
Window: last 90 days
Calibration Analysis (150 runs)
==================================================

Rule: ai-risk-scoring
  Fire rate: 12%
  Avg findings/run: 0.2
  Recommendation: LOOSEN
  Reason: Rule fires on 12% of MRs - threshold may be too strict
```

### test-rule
```bash
karandash test-rule ai-security-scan --against-history --window 30
```

Summarizes the rule's recorded fire rate over the validation history (`--window` limits it to the last N
days, `--replay-count` caps the number of runs). History mode is also the default when no `--mr-url` or
`--snapshot` is given; it reads the recorded finding counts and does not re-evaluate the rule. To re-run
the rule as configured on real MRs, pass `--mr-url <url>` or `--snapshot mr.json` instead.

### verify-policy
```bash
karandash verify-policy --config .karandash/config.yaml
```

Checks the `policies:` block for contradictions (same `when:` condition, same effect, different values on
overlapping modules). `--config` is an alias of `--file`/`-f`; without it the config is auto-discovered.

### mutate
```bash
karandash mutate --rule ai-code-quality --synthetic-mrs 20
```

Runs the built-in synthetic bad MRs (oversized MR, bad title, missing description, ...) through the
configured rules. With `--rule`, only that rule is loaded and a mutation counts as caught when it fires.
`--synthetic-mrs N` runs N MRs, cycling through the templates.

### deps
```bash
karandash deps --mr-url <url> --sibling <url> --sibling <url> --analyze-cross-mr
```

Analyzes cross-MR dependencies and detects conflicts. Without `--analyze-cross-mr` only the `--mr-url` MR is
compared with each sibling; with it the siblings are compared with each other as well (full pairwise matrix).

---

## Environment Variables

| Variable | Purpose | Example |
|----------|---------|---------|
| `OPENAI_API_KEY` | OpenAI API key | `sk-...` |
| `ANTHROPIC_API_KEY` | Anthropic (Claude) API key | `sk-ant-...` |
| `GEMINI_API_KEY` | Google Gemini API key | `AIzaSy...` |
| `LITELLM_API_KEY` | LiteLLM proxy API key | `...` |
| `BIFROST_API_KEY` | Bifrost gateway API key | `...` |
| `BIFROST_BASE_URL` | Bifrost gateway base URL | `https://...` |
| `CUSTOM_AI_API_KEY` | Custom endpoint API key | `...` |
| `KARANDASH_AI_DRY_RUN` | Enable dry-run mode | `true` |
| `KARANDASH_AI_MAX_TOKEN_BUDGET` | Override token budget | `100000` |
| `KARANDASH_REVIEW_MODE` | Override review mode | `added_only` |

---

## Performance

Typical token usage per rule (average, varies by code size):

| Rule | Token Cost | Notes |
|------|-----------|-------|
| ai-risk-scoring | 800-1200 | Core rule, always included |
| ai-description-quality | 400-600 | Lightweight |
| ai-change-categorization | 600-900 | Medium |
| ai-review-comments | 1500-2500 | Expensive (detailed analysis) |
| ai-similar-issues | 700-1000 | Medium |
| ai-fix-suggestions | 800-1200 | Medium |
| ai-code-suggestions | 1200-1800 | Heavyweight (opt-in) |
| ai-code-quality | 2500-3500 | Most expensive; default off (`ai.features.codeQuality: true`) |
| ai-ownership-analysis | 2000-3000 | Default off (`ai.features.ownershipAnalysis: true`); needs platform blame data |

---

## Examples

### GitHub Actions CI Integration

```yaml
name: Karandash AI Review

on: [pull_request]

jobs:
  ai-review:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
        with:
          fetch-depth: 0  # full history for cross-MR features

      - name: Install Karandash
        run: |
          wget https://releases.karandash.io/cli/linux-x64
          chmod +x karandash

      - name: Run AI Review
        env:
          ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}
        run: |
          ./karandash validate \
            --mr-id ${{ github.event.pull_request.number }} \
            --target-branch ${{ github.base_ref }} \
            --config .karandash/config.yaml

      - name: Upload SARIF
        uses: github/codeql-action/upload-sarif@v2
        if: always()
        with:
          sarif_file: karandash.sarif
```

### GitLab CI Integration

```yaml
karandash-ai-review:
  stage: review
  image: rinekri/karandash:latest
  script:
    - karandash validate
      --mr-id $CI_MERGE_REQUEST_IID
      --target-branch $CI_MERGE_REQUEST_TARGET_BRANCH_NAME
  artifacts:
    reports:
      sarif: karandash.sarif
  only:
    - merge_requests
```

### Complete Config Example

```yaml
extends:
  - .karandash/base-config.yaml

ai:
  provider: anthropic
  model: claude-sonnet-4-20250514
  maxTokenBudget: 50000
  dryRunAi: false
  failOnError: false

  # Quality pipeline
  structuredOutput: true
  filter:
    minConfidence: 0.5
    maxPerRule: 10
    deduplicate: true
  grounding:
    enabled: true

  # Capability
  constitution:
    enabled: true
    principles:
      - "Focus on logic and correctness, not style"
      - "Only flag high-confidence issues (>80%)"
      - "Always provide actionable fixes"
  incremental:
    enabled: true

  # Intelligence
  gates:
    - ruleId: ai-security-scan
      filePatterns: ["src/**"]
  feedbackEnabled: true

  # Metrics
  reasoning: semi-formal
  metrics:
    changeEntropy: true
    cognitiveLoad: true

  # Configuration
  executionProfile: ci
  enforcementSchedule:
    - from: "2026-01-01"
      until: "2026-03-31"
      mode: warn
    - from: "2026-04-01"
      mode: enforce

  severityOverrides:
    - rule: size-limits
      reclassify:
        error: warning

reporters:
  console: true
  sarif:
    output: karandash.sarif
  smartDigest:
    enabled: true
    audiences:
      - role: reviewer
        maxItems: 10
  complianceEvidence:
    enabled: true

rules:
  size-limits:
    maxFiles: 30
    maxLines: 500
```

---

## Troubleshooting

### AI findings seem wrong or hallucinated
- Enable `grounding.enabled: true` to verify code claims
- Increase `filter.minConfidence` threshold (0.6-0.8)
- Check `constitution.principles` to reject unwanted patterns

### Token budget exceeded
- Use `size-policy.partial-review` to limit files sent
- Enable `size-policy.diff-preprocessor` (default) to reduce tokens
- Use `costBudget.branchBudgets` to allocate smartly

### Too many false positives
- Review `constitution.principles`
- Increase `feedbackEnabled` to learn from dismissals
- Use `gates` to skip rules for certain files/labels

### Slow validation
- Reduce `concurrency` to lower resource usage
- Enable `incremental.enabled` for subsequent reviews
- Use `size-policy.exclude` to skip generated files

### Configuration not applying
- Run `karandash verify-policy` to check consistency
- Use `karandash --verbose` to see rule loading
- Check `policyDriftDetector` for unintended changes
