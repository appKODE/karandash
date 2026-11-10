# stats

Show validation run statistics and rule violation trends.

## Overview

The `stats` command displays a history of validation runs stored in the statistics directory, along with a breakdown of the most frequently violated rules. It can also export the full history as JSON or clear all stored data.

Statistics are recorded automatically by `karandash validate` when persistence is enabled in configuration.

## Synopsis

```
karandash stats [--limit <n>] [--rule <id>] [--json] [--clear] [--stats-dir <dir>]
karandash stats --diagnostics <path>... [--format text|markdown]
karandash stats --audit --corpus <dir> [--bot <name>]... [--cutoff <date>] [--holdout-project <p>] [--seed <n>] [--llm-judge [--llm-judge-max-calls <n>]] [--out <file>]
```

## Options

| Flag | Short | Type | Default | Description |
|------|-------|------|---------|-------------|
| `--limit` | `-n` | `Int` | `20` | Number of recent runs to display |
| `--rule` | `-r` | `String` | -- | Filter to a specific rule ID |
| `--json` | -- | flag | `false` | Export full statistics history as JSON |
| `--clear` | -- | flag | `false` | Delete all stored statistics |
| `--diagnostics` | -- | `String` (repeatable) | -- | Weekly digest over `diagnostic.json` files instead of local statistics: a file, a directory (walked for `*.json`) or `dir/pattern*.json` |
| `--format` | -- | `text`, `markdown` | `text` | Output of `--diagnostics` |
| `--stats-dir` | -- | `String` | `ai.statistics.dir`, else `.karandash/stats` | Directory where statistics are stored. Relative to the current working directory, not your home directory - each project keeps its own history. |
| `--audit` | -- | flag | `false` | Audit a frozen review-history corpus (`tools/audit/fetch_gitlab_corpus.py`) instead of local statistics |
| `--corpus` | -- | `String` | -- | `--audit`: corpus directory (`<dir>/<project>/<iid>.json`) |
| `--out` | -- | `String` | stdout | `--audit`: write the JSON report here instead of stdout |
| `--cutoff` | -- | `YYYY-MM-DD` | -- | `--audit`: MRs created before this date tune, the rest test |
| `--holdout-project` | -- | `String` | -- | `--audit`: project kept out of the tune and test splits (full path or one path segment) |
| `--bot` | -- | `String` (repeatable) | -- | `--audit`: bot username |
| `--seed` | -- | `Int` | `0` | `--audit`: bootstrap seed |
| `--llm-judge` | -- | flag | `false` | `--audit`: ask the configured AI provider about replies the keyword classifier leaves unknown |
| `--llm-judge-max-calls` | -- | `Int` (>= 1) | `500` | `--audit --llm-judge`: model call cap |

## Usage Examples

```bash
# Show the last 20 runs as a table
karandash stats

# Show the last 50 runs
karandash stats --limit 50

# Filter by rule
karandash stats --rule title-format

# Export full history as JSON
karandash stats --json > history.json

# Clear all statistics
karandash stats --clear

# Use a non-default stats directory
karandash stats --stats-dir /var/karandash/stats
```

## Output

```
Run History (20 shown):

  #     Timestamp             MR ID               Total  Errors  Warnings   Info
  ----------------------------------------------------------------------
  1     2026-03-15 14:22      mr-42                   3       1         2      0
  2     2026-03-14 09:11      mr-41                   0       0         0      0
  ...

Top violations by rule:

  title-format                              12 finding(s) across 8 run(s)
  commit-messages                            7 finding(s) across 5 run(s)
  size-limits                                4 finding(s) across 3 run(s)
```

## See Also

- [calibrate](calibrate.md) - get threshold adjustment recommendations from statistics
- [test-rule](test-rule.md) - replay a rule against the stored history

## Weekly digest

`--diagnostics` reads the `diagnostic.json` files that `validate --diagnostic <path>` writes and prints one table:

| Metric | Read from |
|--------|-----------|
| Red runs | `summary.finding_summary.passed` |
| AI availability | runs with an AI rule (`rules[].model`, `cost_usd` or an `AI_*` skip category) that have no AI rule in `error` or skipped as `AI_PROVIDER_ERROR` / `AI_RATE_LIMITED` |
| Fix rate / persistence | merge requests (`context.mr_url`, else `mr_iid`) with two or more runs: findings of the first run gone by the last (`findings[]`); files from releases without `findings` are left out |
| Dead rules | rules configured or run (`context.rule_ids`, `rules[]`) that never produced a finding |
| AI cost per MR | sum of `rules[].cost_usd` over merge requests with an AI rule |
| Cache hit | `summary.cache` hits over hits plus misses; rules from cache from `rules[].from_cache` |
| Duration | p50 and p95 of `summary.total_duration_ms` |
| False positives | optional `feedback.falsePositives` |

A file that is not a diagnostic report is skipped with a warning on stderr.

`tools/digest/collect-diagnostics.sh` downloads the `diagnostics/diagnostic.json` artifacts of the `changes-validation` jobs of the last N days through `glab api` (read-only) and runs the digest:

```bash
tools/digest/collect-diagnostics.sh -p group/app-android -p group/app-ios -d 7 -f markdown
```

