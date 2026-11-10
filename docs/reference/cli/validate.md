# validate

Validate a merge request against configured rules.

## Overview

The `validate` command is the core functionality of Karandash. It loads your configuration file, processes merge request data, and runs all configured validation rules. Results can be output in multiple formats and posted to merge request platforms.

## Usage

```bash
karandash validate [options]
```

## Modes

Karandash supports four modes for providing merge request data:

### File Mode

Provide MR data as a JSON file:

```bash
karandash validate --mr path/to/mr.json
```

### Live Mode

Fetch MR data directly from a platform:

```bash
karandash validate --mr-id 42 --platform gitlab --project group/repo
```

This mode requires platform credentials (see [Authentication](#authentication)).

### PR URL Mode

Parse platform and IDs from a full PR/MR URL:

```bash
karandash validate --pr https://github.com/owner/repo/pull/42
```

### Auto-Detection Mode

If no mode parameters are provided, Karandash attempts to auto-detect your CI environment:

```bash
karandash validate
```

Supported CI systems: GitLab CI, GitHub Actions, Bitbucket Pipelines, and others.

## Options

| Option | Short | Description | Default |
|--------|-------|-------------|---------|
| `--mr <path>` | `-m` | Path to MR data file (.json) for file mode | |
| `--mr-id <id>` | | MR/PR identifier for live mode | |
| `--platform <name>` | | Platform name (gitlab, github, etc.) for live mode | |
| `--project <id>` | | Project identifier (e.g., "group/repo") for live mode | |
| `--pr <url>` | | Full PR/MR URL for URL-based validation | |
| `--config <path>` | `-c` | Path to configuration file. `--rules-file` is an alias | Auto-discovered |
| `--format <type>` | `-f` | Output format ID. Built-ins: `console`, `json`, `sarif`, `rdjsonl`, `rdjson`. When set explicitly, only this reporter runs. | `console` |
| `--output <path>` | `-o` | Write output to file | |
| `--quiet` | `-q` | Suppress all output except errors | |
| `--verbose` | `-v` | Show detailed validation output | |
| `--trace` | | Verbose output plus full AI responses and per-rule AI diff file lists (same as `KARANDASH_AI_TRACE=1`) | |
| `--strict` | | Treat warnings as failures (exit code 5) | |
| `--baseline <file>` | | JSON file of finding fingerprints to ignore; findings already in it are not reported. Overrides the `baseline:` config key | |
| `--update-baseline` | | Rewrite the baseline file from the current findings instead of applying it. Exits 2 when neither `--baseline` nor `baseline:` names a file | |
| `--fix` | | Automatically fix safe validation issues (labels, draft status) | |
| `--yes` | `-y` | Skip confirmation prompts when using `--fix` | |
| `--no-auto-detect` | | Disable platform auto-detection | |
| `--allow-localhost` | | Allow localhost and private IPs for providers/webhooks and for `extends:` / `{ file: }` / plugin content URLs (cloud-metadata hosts stay blocked). Also settable as `allow-localhost: true` in the config (an `extends:` parent's value applies too, also to fetching its own parents). | |
| `--dangerfile <path>` | | Path to Dangerfile.js or Dangerfile.df.kts (auto-detected if not specified) | |
| `--enrich` | | Generate AI title/description before validation (requires AI provider configured) | |
| `--dry-run-enrich` | | Preview AI-generated title/description without applying changes | |
| `--dry-run-comment` | | Print the comment body to console instead of posting it to the MR; thread replies and resolves are logged, not sent | |
| `--show-prompt` | | With `--dry-run`: build every AI prompt without sending anything and write each to `ai.debug.prompt-dir` (see the AI features reference, Prompt Dump). The files hold MR source code; a directory the dump creates gets a `.gitignore` of `*` | |
| `--auto-approve` | | Automatically approve the MR when all findings are below the severity threshold | |
| `--invalidate-rules <ids>` | | Comma-separated rule IDs to invalidate from the cache before validation | |
| `--gate-on-cached` | | Cached findings merged into the report on a partial incremental cache hit also count for the exit code and auto-approve | `false` |
| `--global-timeout <duration>` | | Global watchdog timeout (e.g. `600s`, `10m`). Overrides config. `0` = no timeout. | |

### Run options

`validate` and [`validate-local`](validate-local.md) share these flags.

| Option | Short | Description | Default |
|--------|-------|-------------|---------|
| `--rules <ids>` | | Comma-separated rule IDs to run; other configured rules are skipped. An ID that matches no configured rule is warned about | all configured |
| `--profile <name>` | | Apply a named profile (e.g. `conventional-commits`, `github-flow`). Overrides profile in config file. | |
| `--dry-run` | | Validate config without running validation | |
| `--explain` | | Show detailed explanations for each finding | |
| `--style <layout>` | | Console layout: `concise` (findings grouped by file, summary last) or `full` (every detail per finding) | `concise` |
| `--no-color` | | Never colour console output (`NO_COLOR` and a `TERM=dumb` terminal do the same) | |
| `--watch` | `-w` | Watch config file and re-run validation on changes | |
| `--once` | | Run a single validation and exit; overrides `--watch` (useful when `--watch` comes from a shared alias or script) | |
| `--show-execution-plan` | | Print validation rules grouped by execution priority and exit | |
| `--no-cache` | | Disable staleness gate cache - force full re-run of all rules (state is still saved for the next run) | |
| `--on-missing-plugin <action>` | | How to handle config references to uninstalled modules: `fail`, `warn`, `ignore` | `warn` |
| `--diagnostic <path>` | | Write JSON runtime diagnostics (memory/CPU snapshots, timeline) to the given file path | |
| `--dump-dir <dir>` | | Save every layer input/output of the run as numbered files in this directory. Local debugging only: files contain MR source and diffs (secrets redacted best-effort) | |

### When a plugin fails to load or times out

A plugin that fails to load (a broken JAR, a JS/WASM file that does not evaluate, an unsupported ABI version, a guest
call that times out while loading) is a warning, not an error: the run goes on without its modules and can exit 0.
What happens next depends on whether the config uses those modules, and `--on-missing-plugin` decides it (the flag
wins over the manifest's `on-missing:`; `--strict` without either means `fail`):

| `--on-missing-plugin` | Config references a module of the failed plugin | Config does not |
|-----------------------|--------------------------------------------------|-----------------|
| `warn` (default) | A warning (E0302 when the manifest declares it, otherwise E0002 for a rule and E0303 for another module); that section is dropped and the run goes on | Exit as usual |
| `ignore` | The section is dropped without a warning | Exit as usual |
| `fail` | Config error, exit 2 | Exit as usual |

A failed load leaves the plugin's modules out of the registry, so it is treated exactly like a plugin that is not
installed. To make a CI job fail when a plugin it depends on breaks, pass `--on-missing-plugin fail` (or set
`on-missing: fail` in the manifest).

A plugin that loaded but whose call times out during the run (`KARANDASH_PLUGIN_CALL_TIMEOUT`, default `30s`) also
only warns: `JS plugin call 'validate' exceeded 30s and was interrupted …`. The call yields no findings, the rest of
the run goes on, and `--on-missing-plugin` does not apply, because the plugin is installed.

## Configuration Discovery

If `--config` is not specified, Karandash searches for a config file in this order:

1. `.karandash/config.kts`
2. `.karandash/config.yaml` / `.karandash/config.yml`
3. `.karandash/config.json`

The first file found is used. If none exist, validation runs in zero-config mode with default rules.

## Output Formats

### Console (default)

Human-readable output with colors and formatting:

```bash
karandash validate
```

### JSON

Structured output for programmatic processing:

```bash
karandash validate --format json
```

### SARIF

Static Analysis Results Format for integration with IDE and security tools:

```bash
karandash validate --format sarif
```

### Reviewdog (rdjson)

Reviewdog-compatible format for inline PR annotations:

```bash
karandash validate --format rdjson
```

### Reviewdog (rdjsonl)

Line-delimited variant of the reviewdog format - one JSON object per finding, no envelope:

```bash
karandash validate --format rdjsonl
```

## Examples

### Basic File Mode Validation

```bash
karandash validate --mr mr_data.json
```

### Live Mode with GitLab

```bash
karandash validate \
  --mr-id 42 \
  --platform gitlab \
  --project group/project
```

### PR URL Mode

```bash
karandash validate --pr https://github.com/owner/repo/pull/42
```

### Custom Config and Output

```bash
karandash validate \
  --config ./strict.yaml \
  --format json \
  --output results.json
```

### Strict Mode (Fail on Warnings)

```bash
karandash validate --strict
```

### Dry-Run (Config Validation Only)

```bash
karandash validate --dry-run
```

### Watch Mode

```bash
karandash validate --watch --mr mr_data.json
```

### Auto-Fix

```bash
# With confirmation prompt
karandash validate --fix --mr-id 42 --platform gitlab --project group/repo

# In CI (skip prompt)
karandash validate --fix --yes
```

### Show Execution Plan

```bash
karandash validate --show-execution-plan
```

### Force Cache Bypass

```bash
karandash validate --no-cache
karandash validate --invalidate-rules title-format,size-limits
```

### Diagnostics

```bash
karandash validate --diagnostic diag.json
```

#### Dump every layer of a run

```bash
karandash validate --dump-dir .karandash/dump
```

Writes one file per transition, prefixed with a run-wide sequence number so `ls` (or `find | sort`) shows the
order: `config/{raw,effective,extension}.json`, `mr-fetched.json`, `mr-enriched.json`, `validation-result.json`,
`after-validation/<extension>.json`, `suppressed.json`, `before-reporting/<extension>.json`,
`report-findings.json`, `result.json`, and for AI rules a per-rule `plan.json` (token budget, kept and dropped
context), `call-N.request.json` / `.response.json` (live and `--dry-run`), the dispatch, located and
post-processed findings, all under `ai/` and the rule ID, and one file per AI post-filter under `ai/stages`. The run diagnostics go to
`diagnostic.json` in the dump directory unless `--diagnostic` is given. The directory gets a `.gitignore` of `*`. Content passes
the log redactor (tokens, URL credentials, quoted secret fields), but MR source and diffs stay: keep the
directory local. A write failure warns and the run goes on; without the flag nothing is serialized.

#### Finding feedback

On GitLab a 👎 on a karandash inline comment marks each finding in it as a false positive. When diagnostics are on,
the run reads these reactions once after fetching the MR (at most 100 karandash notes, one request each; the bot's
own reactions and 👎 on the summary note are not counted) and writes them to a `feedback` block:

```json
"feedback": {
  "falsePositives": 2,
  "byRule": { "title-format": 2 },
  "items": [{ "rule": "title-format", "fingerprint": "…", "reaction": "falsePositive", "author": "alice" }]
}
```

Feedback is only counted: it never suppresses a finding. A failed read logs a warning and leaves the block out.
Other platforms report no feedback.

## Authentication

Live mode requires authentication credentials to fetch MR data from platforms.

### GitLab

```bash
GITLAB_TOKEN=token karandash validate --mr-id 42 --platform gitlab --project group/repo
```

### GitHub

```bash
GITHUB_TOKEN=token karandash validate --mr-id 123 --platform github --project owner/repo
```

## Exit Codes

| Code | Name | Description |
|------|------|-------------|
| 0 | SUCCESS | Validation passed, all rules satisfied |
| 1 | VALIDATION_FAILED | One or more validation errors |
| 2 | CONFIG_ERROR | Configuration file missing, invalid, or incorrect CLI options |
| 3 | PROVIDER_ERROR | Failed to fetch MR from platform (auth, network, etc.) |
| 4 | INTERNAL_ERROR | Unexpected error (bug or system issue) |
| 5 | VALIDATION_WARNING | Warnings present with `--strict` flag |

## Mutual Exclusions

- `--watch` and `--fix` cannot be used together
- `--mr` (file mode) cannot be combined with `--mr-id`, `--platform`, or `--project`

## See Also

- [init](init.md) - Generate starter configuration
- [doctor](doctor.md) - Verify your setup
- [plugin](plugin.md) - Manage plugins
