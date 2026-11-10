# Severity Policy

How Karandash assigns and uses severity levels for findings.

## Severity Levels

| Level | Meaning | Default Behavior |
|-------|---------|-----------------|
| `ERROR` | Critical violation that must be fixed | Blocks merge (exit code 1) |
| `WARNING` | Issue that should be addressed | Reported but does not block |
| `INFO` | Informational finding | Reported only, never blocks |

## How Severity is Determined

1. **Rule default** -- Each rule has a built-in default severity (typically `ERROR` for blocking rules like `conflict`, `WARNING` for advisory rules)
2. **Profile override** -- Profiles can set a default severity for all rules: `severity: error` or `severity: warning`
3. **Per-rule override** -- Individual rules can override severity in config:

```yaml
rules:
  title-format:
    pattern: "^(feat|fix):"
    severity: warning  # override to non-blocking
```

## Exit Codes

| Code | Meaning |
|------|---------|
| `0` | All rules passed (no ERROR findings) |
| `1` | One or more ERROR findings |
| `2` | Configuration error |

## Dry Run

The `--dry-run` CLI flag only validates the config; it runs no rules. There is no `--dry-run-scope` flag.

The top-level `dry-run: true` config key sets the `is_dry_run` condition, which policies can use (for example to
lower severities), and suppresses work per `dry-run-scope`:

- `dry-run-scope: ai` (also when unset): AI rules make no LLM calls; other rules are enforced normally.
- `dry-run-scope: all`: no LLM calls, and nothing leaves the process: platform comments, Slack, webhook, email,
  third-party outputs and auto-approve are skipped. Console and file outputs still run.

See [YAML config: Dry run](../configuration/YAML_CONFIG.md#dry-run).
