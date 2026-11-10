# JSON Reporter

## Overview

The JSON Reporter outputs validation results as a structured JSON document to stdout or a file. It is suitable for machine consumption, custom dashboards, CI artifact storage, and integration with other tooling.

## How it works

The JSON reporter uses **`JsonReportFormatModule`** to serialize findings into a structured JSON document. Output is delivered by `StdoutOutputModule` by default, or `FilesystemOutputModule` when `--output` is specified.

## Configuration

### YAML

```yaml
reporters:
  json: true
```

### JSON

```json
{
  "reporters": {
    "json": true
  }
}
```

### Write to File

Use the `--output` CLI flag to write JSON output to a file:

```bash
karandash validate --format json --output results.json
```

## Output Format

The reporter produces a single JSON object with three top-level fields:

```json
{
  "passed": false,
  "findings": [
    {
      "severity": "error",
      "message": "MR title does not match required pattern: ^(feat|fix): .+",
      "rule": "title-format",
      "location": null
    },
    {
      "severity": "warning",
      "message": "MR title too long (max: 72, actual: 85)",
      "rule": "size-limits",
      "location": {
        "file": null,
        "line": null
      }
    },
    {
      "severity": "error",
      "message": "Potential secret detected",
      "rule": "security",
      "location": {
        "file": "src/config.py",
        "line": 42
      }
    }
  ],
  "summary": {
    "errors": 2,
    "warnings": 1,
    "info": 0,
    "total": 3
  }
}
```

### Fields

**Top level:**

| Field | Type | Description |
|-------|------|-------------|
| `passed` | boolean | `true` if validation passed (no errors), `false` otherwise |
| `findings` | array | List of all findings |
| `summary` | object | Count of findings by severity |

**Finding object:**

| Field | Type | Description |
|-------|------|-------------|
| `severity` | string | `"error"`, `"warning"`, or `"info"` (lowercase) |
| `message` | string | Human-readable finding message |
| `rule` | string | Rule ID that produced this finding |
| `location` | object or null | File and line location, if available |
| `suggestion` | string or null | How to fix the finding |
| `expectedFormat` | string | The required format (Pomsky source); omitted when absent |
| `mismatch` | object | Where the value diverges: `value`, `position`, `expected` and optional `group`; omitted when absent |

**Location object:**

| Field | Type | Description |
|-------|------|-------------|
| `file` | string or null | File path relative to repository root |
| `line` | integer or null | Line number (1-based) |

**Summary object:**

| Field | Type | Description |
|-------|------|-------------|
| `errors` | integer | Number of error-severity findings |
| `warnings` | integer | Number of warning-severity findings |
| `info` | integer | Number of info-severity findings |
| `total` | integer | Total number of findings |

## Notes

- The JSON reporter has no configuration options.
- Output is pretty-printed with consistent indentation.
- When there are no findings, `findings` is an empty array `[]` and `passed` is `true`.
- Severity values are lowercase strings in the JSON output.

## Related Reporters

- [Console](console.md) - Human-readable terminal output
- [SARIF](sarif.md) - SARIF 2.1.0 output for code scanning tools
- [Reviewdog](reviewdog.md) - Reviewdog-compatible JSON output
- [Webhook](webhook.md) - HTTP POST with a similar JSON payload
