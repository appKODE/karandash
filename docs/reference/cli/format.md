# format

Report template tools.

## Overview

The `format` command is a group for writing [report templates](../../reporters/template.md). `schema` prints the
data a template sees; `render` renders a template against the same example report, so a template can be checked
without running a validation.

## Subcommands

| Subcommand | Description |
|------------|-------------|
| `schema` | Print the data a report template sees, for an example report |
| `render` | Render a report template against an example report |

---

## `karandash format schema`

Print the report view of an example report (three findings, a baseline delta and an MR) as JSON. Every key is a
name a template can use: `{{headline}}`, `{{#findings}}{{rule}}{{/findings}}`, `{{counts.errors}}`.

### Synopsis

```
karandash format schema
```

---

## `karandash format render`

Render a Mustache template file against the example report and print the result.

### Synopsis

```
karandash format render --template <file> [--partial <name>=<file>]... [--escape <mode>]
```

### Options

| Flag | Short | Type | Default | Description |
|------|-------|------|---------|-------------|
| `--template` | -- | `String` | required | Path to a Mustache template file |
| `--partial` | -- | `name=file` | none | A partial included as `{{>name}}`; repeatable |
| `--escape` | -- | `none`, `md`, `html`, `slack`, `json` | `none` | How `{{x}}` is escaped |

### Usage Examples

```bash
# See the keys a template can use
karandash format schema

# Try a Slack template with a row partial
karandash format render --template summary.mustache --partial row=row.mustache --escape slack
```

### Exit Codes

| Code | Meaning |
|------|---------|
| `0` | Output printed |
| `2` | Template or partial file not found, or the template does not parse |

## See Also

- [Templates](../../reporters/template.md) - template keys, escaping and the `template` format
- [report](report.md) - summarize a saved validation result
