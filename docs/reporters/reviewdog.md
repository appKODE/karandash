# Reviewdog Reporter

## Overview

The Reviewdog Reporter outputs validation findings in [reviewdog](https://github.com/reviewdog/reviewdog)'s diagnostic format, in two variants: `rdjson` (single enveloped document) and `rdjsonl` (line-delimited, no envelope). Piping either into `reviewdog` turns Karandash findings into inline PR annotations on GitHub, GitLab, and other reviewdog-supported platforms.

## How it works

`RdjsonFormatModule` (format ID `rdjson`) encodes each finding as an `RdDiagnostic` object with message, file location, severity, and rule code, wrapped in a single JSON document with a `source` field. `RdjsonlFormatModule` (format ID `rdjsonl`) encodes the same `RdDiagnostic` per finding but with no `source`/`diagnostics` envelope - one JSON object per line, newline-joined. Output is delivered by `StdoutOutputModule` by default, or `FilesystemOutputModule` when `--output` is specified.

## Configuration

### YAML

```yaml
reporters:
  rdjson: true
  # rdjsonl: true    # line-delimited variant, configured the same way
```

### JSON

```json
{
  "reporters": {
    "rdjson": true
  }
}
```

### CLI

```bash
karandash validate --format rdjson --output results.rdjson
karandash validate --format rdjsonl --output results.rdjsonl
```

## Output Structure

A single compact JSON object:

```json
{
  "source": {
    "name": "karandash",
    "url": "https://github.com/karandash/karandash"
  },
  "diagnostics": [
    {
      "message": "MR title does not match required pattern",
      "location": {
        "path": ""
      },
      "severity": "ERROR",
      "code": {
        "value": "title-format"
      }
    },
    {
      "message": "Potential secret detected",
      "location": {
        "path": "src/config.py",
        "range": {
          "start": { "line": 42 }
        }
      },
      "severity": "ERROR",
      "code": {
        "value": "security"
      },
      "original_output": "AWS_SECRET_ACCESS_KEY pattern matched"
    }
  ]
}
```

With no findings, `diagnostics` is an empty array - the `source` wrapper is always emitted.

## Diagnostic Object Fields

| Field | Type | Description |
|-------|------|-------------|
| `message` | string | Finding message. Labelled `Where:`, `How to fix:` and `Format:` lines, metadata entries (fix-hint keys excluded), and attachments are appended to the message text |
| `location.path` | string | File path, or empty string if the finding has no file location |
| `location.range.start.line` | integer | Line number (1-based), omitted if unavailable |
| `severity` | string | `ERROR`, `WARNING`, or `INFO` |
| `code.value` | string | Rule ID |
| `code.url` | string | Rule documentation URL (optional) |
| `original_output` | string | Rule explanation text, if available |

## CI Integration

### GitHub Actions with reviewdog

```yaml
- name: Run Karandash
  run: karandash validate --format rdjson --output karandash.rdjson

- name: Post reviewdog annotations
  run: reviewdog -f rdjson -reporter=github-pr-review < karandash.rdjson
  env:
    REVIEWDOG_GITHUB_API_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

### Direct reviewdog Integration

```bash
karandash validate --format rdjson | reviewdog -f rdjson -reporter github-pr-review
```

## Notes

- The Reviewdog reporter has no configuration options beyond `enabled`.
- Findings without a file location use an empty `path` string and omit the `range` field.
- Only start lines are emitted - Karandash findings do not carry end positions or column ranges.

## Related Reporters

- [SARIF](sarif.md) - SARIF 2.1.0 for GitHub Code Scanning / GitLab SAST
- [JSON](json.md) - Karandash-native JSON format
- [Markdown](markdown.md) - Post results as MR comments
