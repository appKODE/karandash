# report

Report management commands.

## Overview

The `report` command is a group for working with previously saved validation results. Its `summary` subcommand reads a JSON result file and prints a human-readable breakdown of findings by severity and rule.

## Subcommands

| Subcommand | Description |
|------------|-------------|
| `summary` | Print a summary of a validation result JSON file |

---

## `karandash report summary`

Parse a JSON file produced by `karandash validate --format json` and print finding counts grouped by severity and by rule.

### Synopsis

```
karandash report summary --from <file>
```

### Options

| Flag | Short | Type | Default | Description |
|------|-------|------|---------|-------------|
| `--from` | -- | `String` | required | Path to validation result JSON file |

### Usage Examples

```bash
# Summarize a saved JSON result
karandash report summary --from results.json

# Summarize CI artifact after collecting it
karandash validate --format json --output results.json
karandash report summary --from results.json
```

### Output

```
Validation Summary
==================
Total findings: 5
Result: FAILED

By severity:
  ERROR       : 2
  WARNING     : 3

By rule:
  title-format                  : 1
  commit-messages               : 2
  size-limits                   : 1
  labels                        : 1
```

### Exit Codes

| Code | Meaning |
|------|---------|
| `0` | Summary printed successfully |
| `2` | File not found or not valid JSON |

## See Also

- [validate](validate.md) - produce a JSON result file with `--format json --output`
- [stats](stats.md) - view historical run statistics
