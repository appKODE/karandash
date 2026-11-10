# Console Reporter

## Overview

The Console Reporter prints validation results to standard output in a human-readable format. It is the default reporter and is enabled automatically unless explicitly disabled.

Results are grouped by severity (errors, warnings, info) and a final `Validation PASSED` or `Validation FAILED` line is printed.

## How it works

The Console reporter composes two modules:

- **`ConsoleFormatModule`** - renders findings as ANSI-formatted terminal text grouped by severity
- **`StdoutOutputModule`** - writes the formatted output directly to stdout

## Configuration

### YAML

```yaml
reporters:
  console: true
```

### With Options

```yaml
reporters:
  console:
    quiet: false
    verbose: false
    explain: false
```

### Disabling

The console reporter can be explicitly disabled:

```yaml
reporters:
  console: false
```

Or equivalently:

```yaml
reporters:
  console:
    enabled: false
```

When disabled, no console output is produced. This is useful when you want only machine-readable output (e.g., SARIF or JSON) without any terminal noise.

### JSON

```json
{
  "reporters": {
    "console": {
      "quiet": false,
      "verbose": true
    }
  }
}
```

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `quiet` | bool | `false` | Show only errors; suppress warnings and info |
| `verbose` | bool | `false` | Show extra detail including rule names and file locations |
| `explain` | bool | `false` | Print a human-readable explanation for each rule that produced a finding |
| `successMark` | string | `"✓"` | Character or string used to indicate success in policy summary output |
| `failureMark` | string | `"✗"` | Character or string used to indicate failure in policy summary output |
| `style` | string | `report.style` | `concise`/`standard` (default): findings grouped by file, one status line last; `full`/`verbose`: the layout below with every detail |
| `color` | bool | `false` | ANSI colours in the concise layout; `validate` sets it on a terminal unless `NO_COLOR`, `TERM=dumb` or `--no-color` |

## Output Format

### Concise Output (default)

```
src/App.kt
  error     12  Debug log left in  no-debug-log
                  How to fix: remove the println

commit:abc1234
  warn          Commit body missing  commit-messages

Validation FAILED · 1 error · 1 warning
```

`--style full`, `report.style: verbose`, `--verbose` and `--explain` print the full layout below.

### Full Output (`--style full`)

```
Found 3 issue(s):

Errors (1):
  - [title-format] MR title does not match required pattern: ^(feat|fix): .+

Warnings (2):
  - [size-limits] MR title too long (max: 72, actual: 85)
  - [commit-messages] Commit body missing in abc1234

Validation FAILED
```

### Quiet Mode (`quiet: true`)

Only errors are shown. Warnings and info are suppressed. No output is produced when there are no errors.

```
Errors (1):
  - [title-format] MR title does not match required pattern: ^(feat|fix): .+
```

### Verbose Mode (`verbose: true`)

Rule names and file locations are printed under each finding.

```
Errors (1):
  - [security] Potential secret detected
    Rule: security
    Location: src/config.py:42
```

### Explain Mode (`explain: true`)

A human-readable rule explanation is printed under each finding (when available). This includes both the `suggestion` (what to do) and `explanation` (why the rule exists) fields from the finding.

```
Errors (1):
  - [title-format] MR title does not match required pattern
    How to fix: Use a conventional commit prefix, e.g. 'feat: add login page'
    Explanation: Conventional commit prefixes enable automated changelogs and semantic versioning
```

Every finding prints its fix hint as labelled lines in this order: `Where:` (the checked value, the matched prefix and
the expected token, from the `mismatch` metadata), `How to fix:` (the `suggestion`) and `Format:` (the required format,
from `expectedFormat`). A line is left out when its part is absent. The format diagram (`formatDiagram` metadata) is
printed under `Format diagram:` only in verbose mode, and the verbose metadata dump leaves the fix-hint keys out.

Configure `suggestion` and `explanation` on any rule to populate this output:

```yaml
rules:
  title-format:
    pattern: "^(feat|fix|docs):"
    suggestion: "Use 'feat: ...', 'fix: ...', or 'docs: ...' as your title prefix"
    explanation: "Conventional commit prefixes enable automated changelogs and semantic versioning"
```

### All Clear

```
No issues found
```

## CLI Flags

The quiet, verbose, and explain options can also be set via CLI flags:

```bash
karandash validate --quiet
karandash validate --verbose
karandash validate --explain
karandash validate --style full   # or concise (default)
karandash validate --no-color
```

## Notes

- The console reporter is the only reporter enabled by default (`defaultEnabled = true`). All other reporters must be explicitly enabled.
- The console reporter can be disabled with `console: false` or `console: { enabled: false }` in the `reporters:` block.
- The reporter does not write to a file; output always goes to stdout.
- `quiet` and `verbose` can be combined: quiet suppresses warnings/info but verbose adds location details to the errors that are shown.

## Related Reporters

- [JSON](json.md) - Machine-readable JSON output
- [SARIF](sarif.md) - SARIF 2.1.0 output for code scanning tools
- [Markdown](markdown.md) - Post results as an MR comment
