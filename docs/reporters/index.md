# Reporters

Reporters control how Karandash communicates validation results. The reporting pipeline has two stages:

- **Format** - a `FormatModule` transforms findings into a specific data format (console text, JSON, SARIF, Markdown, etc.)
- **Output** - an `OutputModule` delivers the formatted result to a destination (stdout, file, platform API, webhook, etc.)

For most users, the `reporters:` config block handles this automatically. You pick a reporter name, Karandash selects the right format and output modules for it.

## Format Modules

| Module | Config Key | Output Format |
|--------|-----------|--------------|
| `ConsoleFormatModule` | `console` | Human-readable ANSI terminal text |
| `MarkdownFormatModule` | `markdown` | Markdown tables and sections |
| `JsonReportFormatModule` | `json` | JSON document |
| `SarifFormatModule` | `sarif` | SARIF 2.1.0 |
| `HtmlFormatModule` | `html` | Standalone HTML report |
| `JunitFormatModule` | `junit` | JUnit XML |
| `RdjsonFormatModule` | `rdjson` | Reviewdog rdjson |
| `RdjsonlFormatModule` | `rdjsonl` | Reviewdog rdjsonl |
| `SlackFormatModule` | `slack` | Slack Block Kit JSON |
| `TemplateFormatModule` | `template` | Any text from a user Mustache template ([Templates](template.md)) |
| `CodeQualityFormatModule` | `codequality` | GitLab Code Quality (CodeClimate JSON) ([Code Quality](codequality.md)) |

## Output Modules

| Module | Config Key | Destination |
|--------|-----------|------------|
| `StdoutOutputModule` | (default) | Standard output |
| `FilesystemOutputModule` | (via `--output` flag) | Local file |
| `PlatformOutputModule` | (auto on CI) | MR/PR comment via platform API |
| `WebhookOutputModule` | `webhook` | HTTP POST to any endpoint |
| `EmailOutputModule` | `email` | SMTP email |
| `SlackOutputModule` | `slack` | Slack Incoming Webhook, or the Web API with a bot token |
| `StatusOutputModule` | `status` | Check run, commit status, Code Insights report or PR status ([Status](status.md)) |

## Common Combinations

| Format | Output | Result | Config Key |
|--------|--------|--------|-----------|
| Console | Stdout | Terminal display | `console` |
| JSON | Stdout | Machine-readable JSON on stdout | `json` |
| JSON | Filesystem | JSON file | `json` + `--output` |
| SARIF | Filesystem | SARIF file for CI upload | `sarif` + `--output` |
| Markdown | Platform | MR/PR comment | `markdown` |
| Reviewdog | Stdout | rdjson annotations | `rdjson` |
| Reviewdog | Stdout | rdjsonl annotations (no envelope) | `rdjsonl` |
| Slack | Slack | Slack channel notification | `slack` |
| HTML | Filesystem | Standalone HTML report file | `html` + `--output` |
| JUnit | Filesystem | CI test report | `junit` + `--output` |
| Any | Email | SMTP email with formatted body | `email` |
| Any | Webhook | HTTP POST to endpoint | `webhook` |

## Configuration

### Simple setup (most users)

```yaml
reporters:
  console: true      # Console format -> stdout
  sarif: true        # SARIF format -> stdout (redirect or use --output)
  markdown: true     # Markdown format -> MR comment (auto on CI)
```

### CI setup with SARIF upload

```yaml
reporters:
  console: true
  sarif: true
```

```bash
karandash validate --format sarif --output results.sarif
```

### Full notification setup

```yaml
reporters:
  console: true
  sarif: true
  markdown: true
  slack:
    webhook: "${SLACK_WEBHOOK_URL}"
    channel: "#mr-alerts"
  email:
    host: "smtp.example.com"
    port: 587
    username: "${SMTP_USER}"
    password: "${SMTP_PASSWORD}"
    to: "team@example.com"
```

### Disabling reporters

```yaml
reporters:
  console: false          # disable console output
  markdown: false         # prevent auto-activated MR comments
  slack:
    enabled: false        # disable without removing config
```

## Advanced: explicit format + output config

For scenarios not covered by the built-in reporter shortcuts, you can compose a format module and output module explicitly. See the [reporters section of the configuration schema](../reference/configuration-schema.md#reporters) for details.

## All Reporters

| Reporter | Doc | Module composition |
|----------|-----|--------------------|
| [Console](console.md) | Human-readable terminal output | `ConsoleFormatModule` + `StdoutOutputModule` |
| [Markdown](markdown.md) | MR comment or file | `MarkdownFormatModule` + `PlatformOutputModule` / `FilesystemOutputModule` |
| [JSON](json.md) | Structured JSON | `JsonReportFormatModule` + `StdoutOutputModule` / `FilesystemOutputModule` |
| [SARIF](sarif.md) | SARIF 2.1.0 | `SarifFormatModule` + `StdoutOutputModule` / `FilesystemOutputModule` |
| [HTML](html.md) | Standalone HTML report | `HtmlFormatModule` + `FilesystemOutputModule` |
| [JUnit](junit.md) | JUnit XML for CI | `JunitFormatModule` + `FilesystemOutputModule` |
| [Reviewdog](reviewdog.md) | rdjson/rdjsonl annotations | `RdjsonFormatModule` / `RdjsonlFormatModule` + `StdoutOutputModule` / `FilesystemOutputModule` |
| [Slack](slack.md) | Slack channel notification | `SlackFormatModule` + `SlackOutputModule` |
| [Webhook](webhook.md) | HTTP POST to custom endpoint | any format + `WebhookOutputModule` |
| [Email](email.md) | SMTP email report | any format + `EmailOutputModule` |

## Custom Reporters

Implement a custom `FormatModule` or `OutputModule` from `plugins/sdk` and load it via the `plugins:` block:

```yaml
plugins:
  - id: "com.example.my-format"
    version: "1.0.0"
```

See [Creating Plugins](../plugins/creating-plugins.md) for the full plugin authoring guide.
