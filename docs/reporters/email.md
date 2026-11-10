# Email Reporter

## Overview

The Email Reporter sends validation results via SMTP email as an HTML-formatted message. It is useful for notifying teams about MR validation failures without requiring direct access to the CI system.

The email subject line includes the overall pass/fail status. The body is an HTML report with findings grouped by severity.

**Platform support:** JVM (JavaMail) and native (platform SMTP). The reporter checks platform support at initialization and logs a warning if email sending is not available on the current platform.

## How it works

The Email reporter composes two modules:

- **`EmailOutputModule`** - handles SMTP connection, authentication, and delivery
- A format module (defaults to HTML) - renders findings into the email body

The format used for the email body is the HTML format by default, producing a styled report. To use a different format, configure explicit format+output composition via the advanced reporter config.

## Configuration

### YAML

```yaml
reporters:
  email:
    host: "smtp.example.com"
    port: 587
    username: "${SMTP_USERNAME}"
    password: "${SMTP_PASSWORD}"
    to: "team@example.com"
```

### JSON

```json
{
  "reporters": {
    "email": {
      "host": "smtp.example.com",
      "port": 587,
      "username": "${SMTP_USERNAME}",
      "password": "${SMTP_PASSWORD}",
      "to": "team@example.com"
    }
  }
}
```

## Options

| Option | Type | Required | Default | Description |
|--------|------|----------|---------|-------------|
| `host` | string | Yes | - | SMTP server hostname |
| `port` | int | No | `587` | SMTP server port |
| `username` | string | No | - | SMTP authentication username |
| `password` | string | No | - | SMTP authentication password |
| `from` | string | No | `username` | Sender email address (defaults to `username` when set) |
| `to` | string | Yes | - | Recipient email address |
| `subject` | string | No | the format's subject | Email subject line; overrides the one the format sets (see [Email Content](#email-content)) |
| `starttls` | bool | No | `true` | Require STARTTLS. `false` sends plaintext and is accepted only for a loopback `host` (`localhost`, `127.0.0.1`, `::1`) |
| `when` | string | No | `changed` | Which runs deliver: `always`, `changed` (the verdict or the set of findings differs from the last platform summary comment; a run without a platform counts as changed), `failed`, `never` |

> **Note:** TLS is on by default. The email output module requires STARTTLS on the configured port (default 587),
> with server certificate and host name verification. The only way to send plaintext is `starttls: false` to a
> relay on the same machine (see [Local relay](#local-relay)); plaintext to any other host is refused with
> `Email 'starttls: false' is only allowed for a loopback SMTP host`.

## Email Content

The subject is the first of:

1. `subject:` when set;
2. the subject the format sets: `html` sends `[karandash] FAILED · <MR title>` (`PASSED` on a passing run, the
   verdict word in the report's language; the finding counts, such as `3 errors · 1 warning`, in place of the title
   when there is no merge request);
3. `Karandash Validation Report`, for a format that sets none.

```
[karandash] FAILED · Add login screen
[karandash] PASSED · Add login screen
```

The HTML body contains a styled report listing all findings grouped by severity (errors, warnings, info), with the overall validation status at the top.

## Examples

### Gmail SMTP

```yaml
reporters:
  email:
    host: "smtp.gmail.com"
    port: 587
    username: "${GMAIL_USER}"
    password: "${GMAIL_APP_PASSWORD}"
    to: "dev-team@example.com"
```

**Note:** Use a Gmail App Password, not your account password. Enable 2FA and create an App Password in Google Account settings.

### Custom From Address

```yaml
reporters:
  email:
    host: "mail.example.com"
    port: 587
    username: "karandash@example.com"
    password: "${SMTP_PASSWORD}"
    from: "karandash-ci@example.com"
    to: "alerts@example.com"
```

### Office 365

```yaml
reporters:
  email:
    host: "smtp.office365.com"
    port: 587
    username: "${O365_USER}"
    password: "${O365_PASSWORD}"
    to: "team@company.com"
```

### Custom Subject

```yaml
reporters:
  email:
    host: "mail.example.com"
    port: 587
    username: "${SMTP_USER}"
    password: "${SMTP_PASSWORD}"
    to: "ops@corp.example.com"
    subject: "CI Validation Report"
```

### Local relay

A relay on the same host (postfix, a sidecar MTA) that accepts unauthenticated plaintext on loopback:

```yaml
reporters:
  email:
    host: "localhost"
    port: 25
    from: "karandash@ci.example.com"
    to: "team@company.com"
    starttls: false
```

`username` may be omitted: without it the module does not attempt SMTP `AUTH`.

## Notes

- If the reporter fails to send (network error, authentication failure, etc.), a warning is logged but the validation exit code is not affected.
- The `host` and `to` fields must be non-empty or the reporter throws an `IllegalArgumentException` at send time. Either `from` or `username` must also be set so the sender address can be derived.
- Use environment variable interpolation (`${VAR}`) for credentials rather than hardcoding them in config files.
- The reporter checks `EmailPlatformSupport.isEmailSendingSupported()` at initialization. On unsupported platforms it logs a warning and does not initialize.

## Related Reporters

- [Slack](slack.md) - Post results to a Slack channel
- [Webhook](webhook.md) - HTTP POST to any endpoint
- [Markdown](markdown.md) - Post results as an MR comment
