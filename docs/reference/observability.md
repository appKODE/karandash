# Observability

What a run tells you about itself: the log, the diagnostic report, the run dump, the AI trace and dry run, the crash
report, and the cost and usage lines at the end of a run.

## Log levels

Every log line goes to **stderr**; stdout carries only the report (or the file `-o` names). Each line starts with a
local timestamp.

| Level | Prefix | Default | `-v` / `--verbose` / `--debug` | `-q` / `--quiet` |
|-------|--------|---------|--------------------------------|------------------|
| debug | `[DEBUG]` | hidden | shown | hidden |
| info | none | shown | shown | hidden |
| warn | `[WARN]` | shown | shown | hidden |
| error | `[ERROR]` | shown | shown, with the stack trace | shown |

`--quiet` wins over `--verbose`. An error with an exception prints one line (`Type: message`) unless debug is on.

`--trace` (on `validate` and `validate-local`) is `--verbose` plus the bulky AI output; see
[AI trace](#ai-trace-and-dry-run).

### What is redacted

Every log line, at every level, goes through the log redactor before it is written:

- credentials in URLs: userinfo (`https://user:pass@host`), sensitive query parameters (`private_token`, `key`,
  `access_token`, `X-Amz-Signature`, `jwt`, …) and webhook paths (`hooks.slack.com/services/…`);
- `password`, `secret`, `token`, `api_key`/`apiKey`, `private_key` assignments (`token=…`, `apiKey: …`) and quoted
  JSON/YAML fields (`"apiKey": "…"`), except a boolean/null literal or an obvious code name (`self.token`,
  `deviceToken`);
- `Bearer …` values;
- credential formats recognised on sight: private key headers, `ghp_…`, `glpat-…`, `sk-…`, `sk-ant-…`, `AKIA…`, a JDBC
  `password=`.

The same redactor covers the HTTP debug lines (URL plus the error message), the JVM crash report, the config chain in
the diagnostic report and every file of the run dump. Redaction is pattern-based, so it is best-effort: a secret in a
shape it does not know, such as a bare random string in a diff, is written as is. Finding messages and MR content
(titles, diffs, file contents) are not redacted in reports; the AI path runs diffs through its own secret redactor
before anything is sent to a provider.

## Diagnostic report (`--diagnostic`)

```bash
karandash validate --diagnostic diag.json
```

or in config:

<!-- docs-test: skip -->
```yaml
diagnostics:
  output: diagnostics/diagnostic.json
  interval: 500ms        # resource sampling, default 500ms
  flush-interval: 5s     # how often the file is rewritten while the run goes, default 5s
```

The flag wins over `diagnostics.output`. The file is rewritten every `flush-interval`, so a run killed by a CI timeout
still leaves one, and written a last time at the end of the run (`Diagnostics written to <path>` at info).

| Key | What |
|-----|------|
| `snapshots[]` | Periodic resource samples: peak and current RSS, heap total/max, CPU user/system ms, GC count/time, threads, open file descriptors. JVM reads `Runtime` and the GC MXBeans; native reads `getrusage`. |
| `events[]` | The timeline: stage start/end, HTTP requests (method and URL without query or fragment, credentials stripped; status), with `timestampMs` since the start. |
| `summary` | Totals at the end: duration, peak memory, CPU, GC, `stageTimings`, `ruleTimings`, cache hits, HTTP requests (count, total ms, errors, slowest URL), enrichment and reporter timings, per-stage resource deltas, the AI provider health and review-comments funnel. `null` when the run was interrupted before it finished. |
| `context` | The run: platform, build variant, OS, karandash version, config source, rule count, rule and reporter ids. |
| `rules[]` | One outcome per rule: `status` (`ok`, `skipped` with `reason`/`category`, `error`, `not_run`), `fromCache`, `findingCount`, and for AI rules `model`, `inputTokens`, `outputTokens`, `costUsd`. |
| `findings[]` | Every finding: `rule`, `severity`, `message`, `file`, `line`, `commit`. |

## Run dump (`--dump-dir`)

```bash
karandash validate --dump-dir dump/
```

Saves every layer's input and output as files under the directory, for local debugging. Each file's name gets a
run-wide sequence number (`0007-plan.json`), so `ls` shows the order things happened in. `--dump-dir` also writes the
diagnostic report as `diagnostic.json` in the directory, unless `--diagnostic` names another path.

| File | What |
|------|------|
| `config/raw.json`, `config/effective.json`, `config/extension.json` | The parsed config, the effective config with defaults, the config pipeline extensions get |
| `mr-fetched.json`, `mr-enriched.json` | The merge request as fetched and after enrichment |
| `validation-result.json` | The rule engine's result |
| `after-validation/<extension>.json`, `before-reporting/<extension>.json` | Findings after each extension hook |
| `suppressed.json`, `report-findings.json`, `result.json` | The findings left after the baseline and MR-description suppression, the findings reported, the run result |
| `ai/budget-plan.json` | The token budget split across AI rules |
| In `ai/<rule>/`: `plan.json`, `dispatch-findings.json`, `located-findings.json`, `post-processed-findings.json` | One AI rule's plan and its findings at each step |
| `ai/<rule>/call-<n>.request.json`, `call-<n>.response.json` | Every AI request as sent and its response (`ai/service/…` for calls outside a rule) |
| `ai/stages/<stage>.json` | AI post-processing stages: `grounding`, `agreement`, `dedup`, `ranking`, `hints`, `enricher`, `architecture`, `config-features`, `metrics`, … |

Every file is redacted (a JSON file value by value, so it stays valid JSON: a string under a secret-named key is
replaced). The files still hold MR source and diffs: keep the directory local, do not upload it as a CI artifact.
A file that cannot be written logs a warning; the run goes on.

For the AI prompts alone, byte for byte as the provider gets them, see
[Prompt dump](../features/AI_FEATURES_REFERENCE.md#prompt-dump) (`ai.debug.prompt: artifact`).

## AI trace and dry run

| Switch | Effect |
|--------|--------|
| `--trace`, `KARANDASH_AI_TRACE=1` (`true`, `yes`) | Debug log gets the full AI responses and the per-file diff list of every AI rule; `--verbose` alone keeps one summary line per call. In GitLab CI (`GITLAB_CI=true`) each dump folds into a collapsed log section. `--trace` turns on `--verbose`; the variable does not, so set `-v` with it. |
| `ai.dryRun: true`, `KARANDASH_AI_DRY_RUN=1` | AI rules build every request but send none: each rule reports `Would send <n> request(s), <chars> chars (~<tokens> tokens)`. Thread answers are skipped. |

`karandash validate --dry-run --show-prompt` combines a dry run with the prompt dump.

## Crash report

Installed at startup by every binary; the report goes to stderr.

| Platform | Trigger | Output |
|----------|---------|--------|
| JVM (fat JAR) | An uncaught exception on any thread | `[CRASH] karandash JVM uncaught exception in thread '<name>'`, the exception type and message, the stack trace; redacted like log lines |
| Native (macOS, Linux, Windows) | `SIGSEGV`, `SIGABRT`, `SIGILL` | `[CRASH] karandash native binary received SIGSEGV (segmentation fault)` (or the abort/illegal-instruction text), then the signal is raised again with its default action, so the exit status and any core dump are the system's |

The native handler only calls `write(2)` on a buffer prepared at install time, so it is safe inside a signal handler;
it carries no stack trace and no message. A Kotlin exception on native is not a signal: it ends the run through the
normal error path (`[ERROR]`, exit code per [CLI reference](cli-reference.md)).

## Cost and usage lines

At the end of a run with AI rules, at info (hidden by `-q`):

```
AI plugin cost estimate: $0.0123 (45210 tokens total, 41000 in / 4210 out, total 38s)
  review-comments: ok, 30120 tokens, 28000 in / 2120 out, 21s
  risk-scoring: skipped (<reason>)
  summary (auxiliary): 1200 tokens
```

- one line per enabled AI rule: `ok` with tokens and time, `skipped (<reason>)`, `from cache`, `not run`; plus what
  the rule left out (`left out: …`, `context left out: …`, `excluded: …`) and skipped chunks;
- `(auxiliary)` lines for AI calls outside a rule;
- `ai.costBudget.costEstimate.enabled: false` turns the summary off;
- over the cost alert threshold, a warning: `AI plugin: Cost alert threshold exceeded: estimated cost … > … threshold`.

A rule module that reports its usage (tokens, requests, cost) and used any logs
`Module <id> usage: <tokens> tokens, <requests> requests, cost ~$<usd>`.

On the MR note, the AI status line under the heading says whether the provider answered: `AI: unavailable - 2/2 calls
failed`, `AI: partly unavailable - 1/3 calls failed`, `AI: off by <policy>`, `AI: <n> findings from cache`,
`AI: not run - <reason>`. A failed provider call also adds one info finding and one info log line; it never blocks the
merge.

Rule timings, enrichment and report times and cache hits/misses travel with the report as `ValidationReport.metrics`
for plugin formats and reporters; the built-in formats do not print them. Use the diagnostic report for timings.
