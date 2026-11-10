# karandash validate-local

Validates the local working copy (the commits and diff of `HEAD` against a target branch) through the
[Local Git](../../platforms/local-git.md) platform service. No network, no token, no MR needs to exist.

It is shorthand for `karandash validate --platform local-git --project <target> --mr-id local`.

## Synopsis

```
karandash validate-local [OPTIONS]
```

## Options

| Option | Short | Type | Default | Description |
|--------|-------|------|---------|-------------|
| `--config` | `-c` | string | auto-discover | Path to the config file |
| `--target` | | string | `main` | Target branch to diff against (`<target>..HEAD` commits, `<target>...HEAD` diff) |
| `--format` | `-f` | string | `console` | Output format ID (`console`, `json`, `sarif`, `rdjsonl`, `rdjson`) |
| `--output` | `-o` | string | | Write output to file |
| `--strict` | | flag | false | Treat warnings as failures (exit code 5) |
| `--quiet` | `-q` | flag | false | Suppress all output except errors |
| `--verbose` | `-v` | flag | false | Show detailed validation output |
| `--trace` | | flag | false | Verbose output plus full AI responses and per-rule AI diff file lists (same as `KARANDASH_AI_TRACE=1`) |
| `--baseline` | | string | `baseline:` config key | JSON file of finding fingerprints to ignore; findings already in it are not reported |
| `--update-baseline` | | flag | false | Rewrite the `--baseline` file from the current findings instead of applying it |
| `--fix` | | flag | false | Not supported on local git (no platform API): warns and validates without fixing |
| `--yes` | `-y` | flag | false | Not supported on local git: ignored |

`validate-local` also takes every [run option of `validate`](validate.md#run-options): `--rules`, `--profile`,
`--dry-run`, `--explain`, `--style`, `--no-color`, `--watch`, `--once`, `--show-execution-plan`, `--no-cache`,
`--on-missing-plugin`, `--diagnostic` and `--dump-dir`.

Rule IDs match in any case style: `size-limits`, `sizeLimits` and `size_limits` are the same rule.

## Examples

```bash
karandash validate-local
karandash validate-local --target develop
karandash validate-local --rules security
karandash validate-local --rules title-format,commit-messages --format json --output findings.json
```

## Exit Codes

Same as [`validate`](validate.md#exit-codes): `0` passed, `1` validation failed, `2` config error, `5` warnings
under `--strict`.

## Limitations

Labels, approvals, reviewers and the description are always empty under Local Git, and `--fix` is not offered.
See [Local Git](../../platforms/local-git.md#features-and-limitations).
