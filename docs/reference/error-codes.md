# Error Codes Reference

Karandash uses exit codes to communicate results to CI/CD pipelines and scripts. Every exit code maps to a specific category of outcome.

## Exit Codes

| Code | Constant | Description |
|------|----------|-------------|
| `0` | `SUCCESS` | Validation passed. All rules satisfied. |
| `1` | `VALIDATION_FAILED` | One or more validation rules failed. |
| `2` | `CONFIG_ERROR` | Configuration file is missing, malformed, or contains invalid options. |
| `3` | `PROVIDER_ERROR` | Failed to connect to or authenticate with the platform provider (GitLab, GitHub, etc.). |
| `4` | `INTERNAL_ERROR` | Unexpected internal error (bug or unhandled exception). |
| `5` | `VALIDATION_WARNING` | Validation passed but warnings exist. Only returned when `--strict` flag is used. |
| `6` | `EXECUTION_ERROR` | Plugin signing, compilation, or execution error. |
| `7` | `GLOBAL_TIMEOUT` | The global watchdog timeout was exceeded before the run finished. |

## Exit Code Details

### Exit Code 0 -- Success

All configured validation rules passed. The MR meets all requirements.

```bash
karandash validate --mr-id 123 --platform github --project owner/repo
echo $?  # 0
```

### Exit Code 1 -- Validation Failed

One or more rules produced `ERROR`-severity findings. The MR does not meet the configured requirements.

**Common causes:**
- Title does not match required pattern
- Missing required labels
- Insufficient approvals
- Branch naming convention violated
- Secret detected in diff

**CI/CD usage:**

```yaml
# GitLab CI
validate:mr:
  script:
    - karandash validate
  allow_failure: false  # Pipeline fails on exit code 1
```

### Exit Code 2 -- Configuration Error

The configuration file cannot be parsed or contains invalid values.

**Error catalog code:** `E0005`

**Common causes:**
- Syntax error in `.karandash/config.kts` or YAML file
- Missing required field (e.g., `pattern` in `titleFormat`)
- Invalid option combination (e.g., `--watch` and `--fix` together)
- Missing required CLI flags for live mode
- Config file not found when `--watch` mode is requested

**Troubleshooting:**

```bash
# Verify configuration
karandash doctor

# Validate config without running rules
karandash validate --dry-run
```

### Exit Code 3 -- Provider Error

Failed to communicate with the platform API.

**Error catalog code:** `E0100`

**Common causes:**
- Authentication token expired or invalid
- Network connectivity issues
- API rate limiting
- Wrong platform URL
- MR/PR not found (wrong project ID or MR ID)
- Provider does not support mutation (when using `--fix`)

**Troubleshooting:**

```bash
# Check auth configuration
karandash doctor

# Verify token is set
echo $GITHUB_TOKEN | head -c 10
```

### Exit Code 4 -- Internal Error

An unexpected error occurred inside Karandash. This typically indicates a bug.

**Common causes:**
- Unhandled exception in validation engine
- Plugin crash during execution
- Serialization failure when parsing MR data

If you encounter this exit code consistently, consider filing a bug report.

### Exit Code 5 -- Validation Warning (Strict Mode)

Validation passed but `WARNING`-severity findings exist. This exit code is only returned when the `--strict` flag is active.

Without `--strict`, warnings do not affect the exit code (the process exits with `0`).

```bash
# Warnings cause exit code 5
karandash validate --strict
echo $?  # 5 if warnings exist

# Without --strict, warnings are informational only
karandash validate
echo $?  # 0 even if warnings exist
```

**CI/CD usage for strict mode:**

```yaml
# GitHub Actions
- run: karandash validate --strict
  continue-on-error: false
```

### Exit Code 6 -- Execution Error

A plugin-related execution error occurred outside of validation.

**Common causes:**
- Plugin JAR signing failed
- Plugin compilation error
- Plugin template generation failure
- A plugin command's module failed to initialize (`Error: plugin module '<id>' failed to initialize`)
- `karandash lsp` on a native binary (the language server ships with `karandash.jar` only)

```bash
karandash plugin sign my-plugin.jar --key private.key
echo $?  # 6 if signing fails
```

### Exit Code 7 -- Global Timeout

The run exceeded the global watchdog timeout and was aborted. The timeout is set
with the `--global-timeout` flag (or its config equivalent); `0` disables it.

```bash
karandash validate --mr-id 123 --platform github --project owner/repo --global-timeout 30s
echo $?  # 7 if the run does not finish within 30 seconds
```

## Error Catalog Mapping

An exit code does not imply a catalog code: a message carries `[EXXXX]` only where the CLI raises that specific
problem (for example `E0005` when the `--config` path does not exist, `E0100` when a platform token is missing).
Other errors print without a code or docs link.

| Exit Code | Meaning |
|-----------|---------|
| `2` | Config errors (a code is printed when one applies) |
| `3` | Provider errors (a code is printed when one applies) |
| `1` | Rule failures (not system errors) |
| `4` | Internal errors |
| `5` | Warnings in strict mode |
| `6` | Execution errors |
| `7` | Global watchdog timeout |

## Error Code Catalog

Every `[EXXXX]` code the CLI prints. The `See:` link in an error message points at its row here.

| Code | Title | Category | Severity |
|------|-------|----------|----------|
| <a id="e0001"></a>`E0001` | Unclosed string literal | config | error |
| <a id="e0002"></a>`E0002` | Unknown rule name | config | error |
| <a id="e0003"></a>`E0003` | Invalid regex pattern | config | error |
| <a id="e0004"></a>`E0004` | Missing required field | config | error |
| <a id="e0005"></a>`E0005` | Config file not found | config | error |
| <a id="e0006"></a>`E0006` | Invalid YAML syntax | config | error |
| <a id="e0007"></a>`E0007` | Unknown YAML config key | config | error |
| <a id="e0008"></a>`E0008` | Invalid config value type | config | error |
| <a id="e0009"></a>`E0009` | Remote config fetch failed | config | error |
| <a id="e0010"></a>`E0010` | Circular config inheritance | config | error |
| <a id="e0011"></a>`E0011` | Config inheritance depth exceeded | config | error |
| <a id="e0012"></a>`E0012` | PR URL parse failed | provider | error |
| <a id="e0013"></a>`E0013` | Config fetch authentication failed | config | error |
| <a id="e0014"></a>`E0014` | Too many extends entries | config | error |
| <a id="e0015"></a>`E0015` | Glob expansion failed | config | error |
| <a id="e0016"></a>`E0016` | Condition evaluation failed | config | error |
| <a id="e0017"></a>`E0017` | Extends manifest not found | config | error |
| <a id="e0018"></a>`E0018` | Manifest set not found | config | error |
| <a id="e0019"></a>`E0019` | Cache operation failed | config | warning |
| <a id="e0020"></a>`E0020` | Glob security violation | config | error |
| <a id="e0021"></a>`E0021` | Restricted env var in config | config | error |
| <a id="e0022"></a>`E0022` | Environment variable not set | config | warning |
| <a id="e0023"></a>`E0023` | External value resolution failed | config | error |
| <a id="e0024"></a>`E0024` | Config inheritance chain too large | config | error |
| <a id="e0025"></a>`E0025` | Unsupported config format | config | error |
| <a id="e0100"></a>`E0100` | Authentication missing | provider | error |
| <a id="e0101"></a>`E0101` | Authentication failed | provider | error |
| <a id="e0102"></a>`E0102` | Unknown platform | provider | error |
| <a id="e0103"></a>`E0103` | MR not found | provider | error |
| <a id="e0104"></a>`E0104` | Rate limited | provider | warning |
| <a id="e0200"></a>`E0200` | Title format mismatch | validation | error |
| <a id="e0201"></a>`E0201` | Title length violation | validation | error |
| <a id="e0202"></a>`E0202` | Missing required label | validation | error |
| <a id="e0203"></a>`E0203` | Forbidden label present | validation | error |
| <a id="e0204"></a>`E0204` | MR too large | validation | warning |
| <a id="e0300"></a>`E0300` | Plugin load failed | plugin | error |
| <a id="e0301"></a>`E0301` | Plugin init failed | plugin | error |
| <a id="e0302"></a>`E0302` | Plugin declared but not installed | plugin | error |
| <a id="e0303"></a>`E0303` | Unknown module reference in config | plugin | warning |

## Severity Levels

Validation findings have three severity levels:

| Severity | Effect on Exit Code | Description |
|----------|---------------------|-------------|
| `ERROR` | Exit code `1` | Rule violation. Blocks merge. |
| `WARNING` | Exit code `5` (strict) or `0` | Advisory issue. Blocks only with `--strict`. |
| `INFO` | No effect | Informational message. Never affects exit code. |

## Scripting with Exit Codes

```bash
#!/bin/bash
karandash validate -c .karandash/config.yaml --mr-id "$MR_ID" --platform gitlab --project "$PROJECT"
EXIT_CODE=$?

case $EXIT_CODE in
    0) echo "All checks passed" ;;
    1) echo "Validation failed - fix the issues and retry" ;;
    2) echo "Configuration error - check .karandash/config.yaml" ;;
    3) echo "Provider error - check authentication" ;;
    4) echo "Internal error - please report this bug" ;;
    5) echo "Warnings detected (strict mode)" ;;
    6) echo "Execution error - check plugin configuration" ;;
    *) echo "Unknown exit code: $EXIT_CODE" ;;
esac
```

## Exception Classes

Karandash maps exit codes to exception classes internally:

| Exception | Exit Code | Usage |
|-----------|-----------|-------|
| `ConfigException` | `2` | Invalid config, missing files, bad CLI usage |
| `ProviderException` | `3` | API errors, auth failures |
| `KarandashException` | *(varies)* | Base class for all typed errors |

## Config Inheritance Error Codes (E0015-E0020, E0024)

These error codes relate to [config inheritance](../features/config-inheritance.md) features: glob expansion, conditional extends, manifests, and caching.

### E0015: Glob Expansion Failed

**Exit Code:** 2

```
[E0015] Glob expansion failed
Pattern: "{pattern}"
Reason: {reason}
```

**Common causes:**
- Pattern syntax error (`configs/[invalid].yaml`)
- Maximum expansion exceeded (>10 files matched)
- Remote URL glob (not supported - use manifests)
- Base directory doesn't exist

**Fix:** Use simple relative patterns (`configs/*.yaml`), keep depth under 5 levels, use manifests for remote globs.

### E0016: Condition Evaluation Failed

**Exit Code:** 2

```
[E0016] Condition evaluation failed
Condition: "{condition}"
Error: {error_details}
```

**Common causes:**
- Missing `${}` wrapper: `ENV == 'prod'` should be `${ENV} == 'prod'`
- Invalid operator: only `==` and `!=` are supported
- Unclosed quotes or parentheses
- Empty condition string

**Valid syntax:**
```yaml
if: "${ENV} == 'production'"          # Variable comparison
if: "platform == 'gitlab'"            # Platform detection
if: "${A} == 'x' && ${B} == 'y'"     # AND
if: "${A} == 'x' || ${B} == 'y'"     # OR
if: "!${CI}"                          # NOT
if: "(${A} == 'x') || ${C} == 'z'"   # Parentheses
```

### E0017: Extends Manifest Not Found

**Exit Code:** 2

```
[E0017] Extends manifest not found
File: {manifest_path}
```

Triggered when using `@set-name` syntax but `.karandash/extends-manifest.yaml` doesn't exist.

**Fix:** Create the manifest file or use direct extends instead.

### E0018: Manifest Set Not Found

**Exit Code:** 2

```
[E0018] Manifest set not found
Reference: {set_name}
Available sets: {list}
```

**Fix:** Run `karandash validate-extends --list-sets` to see available sets. Check for typos (set names are case-sensitive).

### E0019: Cache Operation Failed

**Severity:** WARNING (does not fail validation)

```
[E0019] Cache operation failed (warning)
Fallback: Using direct fetch
```

**Common causes:** Cache directory not writable, disk full, corrupted cache file.

**Fix:** Run `karandash cache clear` to reset cache. Check permissions on `~/.karandash/cache/extends/`.

### E0020: Glob Security Violation

**Exit Code:** 2

```
[E0020] Glob security violation
Pattern: {pattern}
```

**Common causes:**
- Path traversal attempt (`../../etc/passwd`)
- Absolute path (`/etc/passwd`)
- Symlink escaping project boundary
- Remote URL glob (`https://example.com/*.yaml`)

**Fix:** Use relative patterns within the project directory. Use manifests for remote URL lists.

### E0024: Config Inheritance Chain Too Large

**Exit Code:** 2

```
[E0024] Config inheritance chain too large (max: {max} nodes)
```

**Fix:** Pull fewer configs into the `extends` chain, or raise `limits.extends.max-chain-size` in the root config.

### E0025: Unsupported Config Format

**Exit Code:** 2

```
[E0025] Unsupported config format 'toml' for https://example.com/a.toml. Supported formats: .kts, .yaml, .yml, .json
```

**Fix:** Name the config file with a supported extension. Before this code existed the same condition was reported as `E0012`.

---

## See Also

- [CLI Reference](cli-reference.md) -- `--strict` flag and output options
- [Configuration Schema](configuration-schema.md) -- valid configuration options
- [Environment Variables](environment-variables.md) -- authentication setup
- [Config Inheritance](../features/config-inheritance.md) -- extends, manifests, caching
