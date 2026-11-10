# Webhook Reporter

## Overview

The Webhook Reporter POSTs validation results as a JSON payload to an HTTP endpoint. It supports custom request headers, bearer token authentication, and SSRF protection (local/private addresses are blocked by default).

This reporter is useful for integrating Karandash with custom dashboards, notification systems, ticketing tools, or any service that can receive HTTP POST requests.

## How it works

The Webhook reporter composes two modules:

- **`WebhookOutputModule`** - handles HTTP POST delivery, auth headers, and SSRF protection; requests go through the shared HTTP client, which supplies the default retry policy (`ProviderHttpClientFactory`)
- A format module (defaults to JSON) - serializes findings into the request body

By default the payload uses the JSON format. To POST a different format (e.g., Markdown), configure explicit format+output composition via the advanced reporter config.

## Configuration

### YAML

```yaml
reporters:
  webhook:
    url: "https://hooks.example.com/karandash"
```

### With Authentication

```yaml
reporters:
  webhook:
    url: "https://hooks.example.com/karandash"
    auth-token: "${WEBHOOK_SECRET}"
    headers:
      X-Source: "karandash"
      X-Environment: "production"
```

### JSON

```json
{
  "reporters": {
    "webhook": {
      "url": "https://hooks.example.com/karandash",
      "authToken": "${WEBHOOK_SECRET}"
    }
  }
}
```

## Options

| Option | Type | Required | Default | Description |
|--------|------|----------|---------|-------------|
| `url` | string | Yes | - | HTTPS URL to POST results to |
| `headers` | map | No | `{}` | Additional HTTP headers to include; names are sent exactly as written (`X-Source` stays `X-Source`) |
| `authToken` | string | No | - | Bearer token added to the `Authorization` header |
| `contentType` | string | No | `application/json` | `Content-Type` of the POSTed body (set it when posting a non-JSON format) |
| `onError` | string | No | `fail` | `fail`: a failed POST (bad URL, connection error, non-2xx response) fails the output and the run exits 6 (`EXECUTION_ERROR`) when it would otherwise pass; `warn`: the failure is only logged |
| `when` | string | No | `always` | Which runs deliver: `always`, `changed` (the verdict or the set of findings differs from the last platform summary comment; a run without a platform counts as changed), `failed`, `never` |

The auth token and the custom headers are sent only to the webhook URL's host: a request that leaves it (a
redirect to another host) carries neither.

## Payload Format

The reporter POSTs a compact JSON body with `Content-Type: application/json`:

```json
{
  "status": "FAIL",
  "findings": [
    {
      "severity": "ERROR",
      "rule": "title-format",
      "message": "MR title does not match required pattern: ^(feat|fix): .+",
      "location": null
    },
    {
      "severity": "ERROR",
      "rule": "security",
      "message": "Potential secret detected",
      "location": {
        "file": "src/config.py",
        "line": 42
      }
    }
  ],
  "summary": {
    "errors": 2,
    "warnings": 0,
    "info": 0,
    "total": 2
  }
}
```

### Payload Fields

**Top level:**

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | `"PASS"` or `"FAIL"` |
| `findings` | array | List of all findings |
| `summary` | object | Counts by severity |

**Finding object:**

| Field | Type | Description |
|-------|------|-------------|
| `severity` | string | `"ERROR"`, `"WARNING"`, or `"INFO"` (uppercase) |
| `rule` | string | Rule ID |
| `message` | string | Finding message |
| `location` | object or null | File and line, if available |

**Location object:**

| Field | Type | Description |
|-------|------|-------------|
| `file` | string or null | File path |
| `line` | integer or null | Line number (1-based) |

**Summary object:**

| Field | Type | Description |
|-------|------|-------------|
| `errors` | integer | Error count |
| `warnings` | integer | Warning count |
| `info` | integer | Info count |
| `total` | integer | Total findings |

## Authentication

When `authToken` is set, the reporter adds an `Authorization: Bearer {token}` header to the request.

```yaml
reporters:
  webhook:
    url: "https://api.example.com/karandash/results"
    auth-token: "${WEBHOOK_TOKEN}"
```

## Error Handling

A failed POST (bad or blocked URL, connection error, non-2xx response) is always logged at warn. What happens next is set by `onError`:

- `fail` (default): the output fails, and a run that would otherwise pass exits 6 (`EXECUTION_ERROR`). A run that already fails on its findings keeps its own exit code.
- `warn`: the failure is only logged and the exit code is unchanged.

The request goes through the run's `http.retry` policy. Because a POST is not idempotent, it is retried only when the server cannot have acted on it: a `429` answer or a connect timeout. Other failures are not retried.

## SSRF Protection

The webhook URL is validated by `SsrfUrlValidator` before each request. Requests to private IP ranges, loopback addresses (`127.x.x.x`, `::1`), and link-local addresses are blocked by default. This protection can be disabled for local endpoints with `allow-localhost: true` in the config (or `--allow-localhost`); the URL check and the HTTP client both honour it. For a webhook on an internal network, `allow-private-network: true` permits private and dotless hosts only, keeping the loopback, metadata and HTTPS checks.

## Notes

- The `url` field is required and must be non-empty, or the reporter throws `IllegalArgumentException` at report time.
- Severity values in the payload are uppercase strings (`"ERROR"`, `"WARNING"`, `"INFO"`), unlike the JSON reporter which uses lowercase.
- A failed POST affects the exit code only under the default `onError: fail`; set `onError: warn` to keep it advisory.

## Related Reporters

- [Slack](slack.md) - Structured Slack Block Kit messages
- [Email](email.md) - SMTP email delivery
- [JSON](json.md) - Write JSON to stdout or file (similar payload structure)
