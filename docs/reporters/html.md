# HTML Reporter

## Overview

The HTML Reporter renders validation findings as a self-contained HTML report. It is suitable for archiving, attaching to emails, hosting on a static file server, or sharing with stakeholders who do not have direct CI access.

## How it works

The HTML reporter uses `HtmlFormatModule` to render findings into a styled, standalone HTML document (no external CSS or JS dependencies). The output is delivered by `FilesystemOutputModule` when `--output` is specified, or to stdout otherwise.

## Configuration

### YAML

```yaml
reporters:
  html: true
```

### Write to file (recommended)

```bash
karandash validate --format html --output report.html
```

Or set the file in the config, so a plain `karandash validate` writes it - `output` is a file path (relative to
the working directory) unless it names an output module such as `webhook`:

```yaml
reporters:
  html:
    output: report.html
```

### JSON

```json
{
  "reporters": {
    "html": true
  }
}
```

## Output Structure

The HTML report includes:

- A status banner (`PASSED` / `FAILED`) with a pass/fail color indicator
- A summary section: total findings broken down by severity
- A findings table with columns: severity, rule, message, location; the message cell adds labelled `Where:`, `How to fix:`
  and `Format:` lines when the finding carries them
- A footer with the Karandash version and run timestamp

The document is self-contained - all styles are inlined. It can be opened directly in any browser without a server.

## CI Integration

### GitLab - HTML artifact

```yaml
karandash:
  script:
    - karandash validate --format html --output karandash-report.html
  artifacts:
    paths:
      - karandash-report.html
    expire_in: 7 days
```

### GitHub Actions - Upload as artifact

```yaml
- name: Run Karandash
  run: karandash validate --format html --output karandash-report.html

- name: Upload report
  uses: actions/upload-artifact@v4
  with:
    name: karandash-report
    path: karandash-report.html
```

## Notes

- The HTML reporter has no configuration options beyond `enabled`.
- When stdout is the output destination, the HTML is streamed directly - suitable for piping to a file.

## Related Reporters

- [Markdown](markdown.md) - Markdown format for MR comments or files
- [JSON](json.md) - Machine-readable JSON output
- [Email](email.md) - Send the HTML report via SMTP
