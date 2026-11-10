# karandash validate-extends

Validates the `extends:` chain in a config file without running full MR validation. It merges the inheritance chain exactly as `validate` does, so a parent that `validate` cannot fetch or resolve fails here too, and it reports cycle, depth and chain-size violations.

Use this command to verify that your config inheritance is correctly wired before committing or deploying - especially when using remote `extends:` URLs.

## Synopsis

```
karandash validate-extends [OPTIONS]
```

## Options

| Option | Short | Type | Default | Description |
|--------|-------|------|---------|-------------|
| `--config` | `-c` | string | auto-discover | Path to the config file to check |
| `--verbose` | `-v` | flag | false | Show detailed output for each URL |
| `--list-sets` | | flag | false | Print the named sets of `.karandash/extends-manifest.yaml` and exit (no config file needed) |

## How It Works

1. **Discovers the config file** - uses `--config` or auto-discovery.
2. **Merges the extends chain** - the same merge `validate` runs: parents are fetched with their `auth:` through the downloads cache, `allow-localhost` / `allow-private-network` / `limits` from the chain apply, and `{ file: }` references in every layer are resolved.
3. **Reports results** - prints each parent, chain depth, and a final error count.

No separate reachability probe is sent; a parent that cannot be fetched, or a `{ file: }` reference that cannot be resolved, is reported as `[FAIL]` with the error `validate` would show.

## Output

```
Extends Chain Validation
========================
  [OK]   https://configs.example.com/base.yaml (fetched)
  [OK]   shared/company.yaml (local file)

Resolution:
  Total depth: 2 / 5 (max)
  Total extends: 2 / 19 (max)
  Circular references: none

Result: 0 error(s), 0 warning(s)
```

When the merge fails, the error replaces the chain listing:

```
Extends Chain Validation
========================
  [FAIL] [E0009] Failed to fetch config from https://configs.example.com/missing.yaml: HTTP 404. ...

Result: 1 error(s), 0 warning(s)
```

With `--verbose`, the config file path is printed before the chain:

```
Using config file: .karandash/config.yaml

Extends Chain Validation
========================
...
```

## Listing manifest sets

`--list-sets` reads `.karandash/extends-manifest.yaml` in the current directory and prints each set with its entry URLs (`${VAR}` placeholders interpolated):

```
Extends sets (.karandash/extends-manifest.yaml):
  @standard
    - https://company.example/base.yaml
    - https://company.example/security.yaml
```

A missing manifest is a config error (`E0017`, exit code 2).

## Error Detection

The command detects and reports the following configuration errors:

| Error Code | Description |
|------------|-------------|
| `E0010` | Circular reference detected in extends chain |
| `E0011` | Extends chain exceeds maximum depth (10 levels) |
| `E0024` | Extends chain size limit exceeded |

Each is printed under `Resolution:` and exits with code 1.

## Examples

```bash
# Validate the auto-discovered config
karandash validate-extends

# Validate a specific config file with verbose output
karandash validate-extends --config .karandash/config.yaml --verbose

# Use in CI to gate on extends chain health
karandash validate-extends || exit 1
```

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | The chain merges: every parent and `{ file: }` reference resolves, no cycles, no limit violations |
| 1 | A parent or `{ file: }` reference failed, or a cycle/depth/size violation was detected |
| 2 | Config file error (file not found, parse error; missing manifest for `--list-sets`) |
| 4 | Internal error |

## Config File Without Extends

If the config file contains no `extends:` directive, the command prints:

```
No extends directive found in config
```

and exits with code 0.

## Related Commands

- [`validate`](validate.md) - Run full MR validation (resolves extends as part of the pipeline)
- [`explain`](explain.md) - Explain configuration fields and module schemas
- [`init`](../cli/init.md) - Create a new config file
