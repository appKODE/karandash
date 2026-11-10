# config

Manage and inspect Karandash configuration.

## Overview

The `config` command is a group with subcommands for validating and describing configuration files. Use it to check a config file for schema errors or look up what any configuration key does.

## Subcommands

| Subcommand | Description |
|------------|-------------|
| `validate` | Parse a config file and report schema errors |
| `describe` | Print the purpose and expected structure of a config key |

---

## `karandash config validate`

Parse a config file for schema errors without running the validation pipeline. Discovers the config file automatically unless `--file` is given.

### Synopsis

```
karandash config validate [--file <path>] [--strict] [--base]
```

### Options

| Flag | Short | Type | Default | Description |
|------|-------|------|---------|-------------|
| `--file` | `-f` | `String` | auto-discovered | Path to config file to validate |
| `--strict` | | `Boolean` | `false` | Fail (exit 2) on unresolved `${VAR}` references and schema mismatches (wrong value types); without it they print a warning |
| `--base` | | `Boolean` | `false` | Validate a shared base config on its own: a missing local `{ file: }` reference prints a warning (consumers provide it) instead of failing |

### Usage Examples

```bash
# Validate auto-discovered config
karandash config validate

# Validate a specific file
karandash config validate --file .karandash/config.yaml

# Validate a KTS config
karandash config validate --file .karandash/config.kts
```

### Exit Codes

| Code | Meaning |
|------|---------|
| `0` | Config is valid |
| `2` | Config file not found, unsupported format, schema errors, or (with `--strict`) unresolved `${VAR}`s and schema mismatches |

---

## `karandash config describe`

Print the purpose and expected structure of a config key or section.

### Synopsis

```
karandash config describe <key>
```

### Arguments

| Argument | Required | Description |
|----------|----------|-------------|
| `key` | Yes | Config key to describe (e.g., `rules`, `policies`, `timeouts`) |

### Available Keys

| Key | Description |
|-----|-------------|
| `rules` | Rule configurations - enabled, severity, thresholds, patterns |
| `policies` | Conditional policy overrides with `when`, `modules`, `severity`, `override` |
| `timeouts` | Timeout settings: `http`, `enrichment`, `pluginShutdown`, `cacheTtl`, `ruleExecution` (unset = no per-rule timeout), `scripts` (unset = no cap beyond each script's own timeout), `dangerfile` (default `5m`; a Dangerfile still running is killed), `global` |
| `http` | HTTP client settings: `retry` (`maxAttempts`, `baseDelay`; idempotent requests on 5xx only), `pagination.maxPages` (`0` = no cap), `responseCache.maxEntries` |
| `reporters` | Reporter configurations (format/output IDs with format-specific options) |
| `platforms` | Platform service configurations (auth, API settings) |
| `profile` | Active profile reference (e.g., `conventional-commits`) |
| `enrichment` | Pre-validation enrichment provider configuration |
| `ai` | AI analysis configuration: provider, model, analyzers, prompts |
| `cache` | Cache configuration: directory, TTL, remote backend |
| `patterns` | Named glob/regex patterns for reuse in rule configs |
| `auto-approve` | Auto-approve settings: `enabled`, `maxSeverity` threshold, `merge` (merge after approving, default `false`) |
| `extends` | Config inheritance - path or URL to a parent config |
| `plugins` | External third-party plugin configurations |

### Usage Examples

```bash
# Describe the rules section
karandash config describe rules

# Describe AI config options
karandash config describe ai

# Describe timeout settings
karandash config describe timeouts
```

## See Also

- [Configuration Schema](../configuration-schema.md) - full schema reference
- [init](init.md) - create a starter config file
- [wizard](wizard.md) - interactive configuration guide
