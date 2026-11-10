# test-rule

Test a rule configuration against real MRs.

## Overview

With `--mr-url` and/or `--snapshot`, `test-rule` loads your config the way `validate` does, runs the
configured `rules.<rule-id>` block on each MR, and reports the rule's fire rate and pass/fail ratio with a
calibration recommendation. This lets you evaluate a rule - or a change to its configuration - before
deploying it. It exits with a config error when the config has no block for the rule.

Without either option it only summarizes the rule's recorded fire rate from the statistics history
(`--stats-dir`); that summary does not re-evaluate the rule, and the output says so.

## Synopsis

```
karandash test-rule <rule-id> [--mr-url <url>]... [--snapshot <mr.json>]... [--file <config>]
karandash test-rule <rule-id> [--replay-count <n>] [--stats-dir <dir>]
```

## Arguments

| Argument | Required | Description |
|----------|----------|-------------|
| `rule-id` | Yes | ID of the rule to test, as configured under `rules:` (e.g., `titleFormat`) |

## Options

| Flag | Short | Type | Default | Description |
|------|-------|------|---------|-------------|
| `--mr-url` | -- | `String` | -- | MR/PR URL to replay the rule on (repeatable) |
| `--snapshot` | -- | `String` | -- | `MergeRequest` JSON file to replay the rule on (repeatable) |
| `--file` | `-f` | `String` | auto-discover | Config file path |
| `--replay-count` | -- | `Int` | `50` | History mode: number of recorded runs to summarize |
| `--stats-dir` | -- | `String` | `.karandash/stats` | Statistics directory (project-relative) |

## Usage Examples

```bash
# Replay titleFormat on two real MRs
karandash test-rule titleFormat --mr-url https://gitlab.com/g/r/-/merge_requests/1 --mr-url https://gitlab.com/g/r/-/merge_requests/2

# Replay on saved MergeRequest JSON
karandash test-rule titleFormat --snapshot mr-41.json --snapshot mr-42.json

# History mode: summarize the last 50 recorded runs
karandash test-rule title-format

# Test with a larger replay window
karandash test-rule commit-messages --replay-count 100

# Test with a custom stats directory
karandash test-rule size-limits --stats-dir .karandash/stats
```

## Output

```
Rule Sandbox: title-format
==================================================
Replayed: 50 MR(s)
Fire rate: 24%
Pass: 38 | Fail: 12

Rule fires in roughly 1 in 4 MRs. Configuration looks well-calibrated.
```

## Prerequisites

History mode only: validation history must exist in the statistics store. Run `karandash validate` with state persistence enabled to build history, then use `karandash stats` to confirm records are present.

## See Also

- [stats](stats.md) - view raw run statistics
- [calibrate](calibrate.md) - recommend threshold adjustments across all rules
