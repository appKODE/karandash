# calibrate

Analyze rule thresholds and recommend calibration adjustments.

## Overview

The `calibrate` command reads accumulated validation statistics and recommends threshold adjustments for rules that are miscalibrated - either firing too often (suggesting the threshold is too strict) or too rarely (suggesting it is too lenient).

Calibration requires a minimum number of validation runs to produce statistically meaningful recommendations. Statistics are recorded automatically each time `karandash validate` runs (when persistence is enabled).

## Synopsis

```
karandash calibrate [--rule <id>] [--window <days>] [--target-fp-rate <f>] [--min-samples <n>] [--stats-dir <dir>]
karandash calibrate --corpus <dir> [--bot <name>]... [--learn]
```

## Options

| Flag | Short | Type | Default | Description |
|------|-------|------|---------|-------------|
| `--min-samples` | -- | `Int` | `20` | Minimum run count required before analysis |
| `--stats-dir` | -- | `String` | `ai.statistics.dir`, else `.karandash/stats` | Directory where statistics are stored. Relative to the current working directory, not your home directory - each project keeps its own history. |
| `--rule` | -- | `String` | -- | Analyze only this rule ID |
| `--window` | -- | `Int` (>= 1) | all runs | Analyze only runs recorded in the last N days |
| `--target-fp-rate` | -- | `Double` (0-1) | `0.8` | Fire rate above which a rule is recommended LOOSEN; the fire rate stands in for the false-positive rate (no labelled outcomes are recorded) |
| `--corpus` | -- | `String` | -- | Review-history corpus (as for `stats --audit`): print each analyzer's precision from the accepted/rejected labels and propose a severity change. The proposal is printed, never applied |
| `--bot` | -- | `String` (repeatable) | -- | `--corpus`: karandash bot username |
| `--learn` | -- | flag | `false` | `--corpus`: also write the `ai.learnings.file` the corpus supports |

## Usage Examples

```bash
# Run with default settings (requires 20+ runs)
karandash calibrate

# Lower the threshold for smaller teams
karandash calibrate --min-samples 5

# Use a custom stats directory
karandash calibrate --stats-dir /var/karandash/stats
```

## Output

When there is sufficient data, `calibrate` prints a recommendation for each miscalibrated rule: `LOOSEN` above the
`--target-fp-rate` fire rate (default 80%), `TIGHTEN` below 5%, `REVIEW` for a rule that never fires. The statistics
flags (`--rule`, `--window`, `--target-fp-rate`, `--min-samples`, `--stats-dir`) with `--corpus` are a usage error.

```
Calibration Analysis (47 runs)
==================================================

Rule: size-limits
  Fire rate: 82%
  Avg findings/run: 1.4
  Recommendation: LOOSEN
  Reason: Rule fires on 82% of MRs - threshold may be too strict

Rule: commit-messages
  Fire rate: 3%
  Avg findings/run: 0.1
  Recommendation: TIGHTEN
  Reason: Rule fires on only 3% of MRs - threshold may be too lenient
```

When all rules are well-calibrated:

```
All rules are well-calibrated (47 runs analyzed)
```

## Prerequisites

Statistics collection must be enabled. Run `karandash validate` repeatedly to build a history, then use `karandash stats` to review the raw data before calibrating.

## See Also

- [stats](stats.md) - view raw validation run statistics
- [test-rule](test-rule.md) - replay a rule against historical MR data
