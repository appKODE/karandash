# CLI Reference

Complete reference for all Karandash CLI commands and flags.

## Synopsis

```
karandash <command> [options]
```

**Global description:** MR validation tool with configurable rules.

### Global options

`-v`/`--debug` and `-q` are read anywhere on the command line before `--` (`karandash -v validate` and
`karandash validate -v` are the same); without them `KARANDASH_LOG_LEVEL` sets the level.

| Flag | Short | Description |
|------|-------|-------------|
| `--version` | `-V` | Print the version and exit |
| `--verbose`, `--debug` | `-v` | Debug logging for every command, including plugin loading |
| `--quiet` | `-q` | Suppress all output except errors |

---

## Commands

### Core Validation

| Command | Description |
|---------|-------------|
| [`validate`](#karandash-validate) | Validate a merge request against configured rules |
| [`validate-local`](cli/validate-local.md) | Validate local commits and diff against a target branch (no platform API) |
| [`simulate`](cli/simulate.md) | Simulate approval policy evaluation against MR state |
| [`test-rule`](cli/test-rule.md) | Re-run a configured rule on real MRs (or summarize its recorded history) |

### Configuration

| Command | Description |
|---------|-------------|
| [`init`](#karandash-init) | Initialize a new Karandash configuration file |
| [`wizard`](#karandash-wizard) | Interactive setup wizard for Karandash configuration |
| [`config`](cli/config.md) | Validate config files and describe config keys |
| [`explain`](cli/explain.md) | Explain the effective config: what each rule and key does |
| [`verify-policy`](cli/verify-policy.md) | Check the `policies:` block for contradictions |
| [`profile`](cli/profile.md) | List and apply named configuration profiles |

### Analysis

| Command | Description |
|---------|-------------|
| [`doctor`](#karandash-doctor) | Check configuration, authentication, and plugin health |
| [`deps`](cli/deps.md) | Analyze file conflicts and ordering dependencies between an MR and its siblings |
| [`stats`](cli/stats.md) | Show validation run statistics and rule violation trends |
| [`calibrate`](cli/calibrate.md) | Analyze rule thresholds and recommend calibration adjustments |
| [`ai`](cli/ai.md) | AI helpers: explain a rule, suggest a fix, draft a review reply or MR description (registered only when an AI provider env var is set) |

### Reporting

| Command | Description |
|---------|-------------|
| [`report`](cli/report.md) | Summarize a saved validation result JSON file |
| [`format`](cli/format.md) | Print the data a report template sees and render a template against an example report |
| [`mutate`](cli/mutate.md) | Run mutation tests against rule configuration |

### Merge requests

| Command | Description |
|---------|-------------|
| [`mr`](cli/mr.md) | Merge, close or reopen a merge/pull request by URL |

### Cache

| Command | Description |
|---------|-------------|
| [`cache`](cli/cache.md) | Manage the local and remote CAS cache |

### Plugins

| Command | Description |
|---------|-------------|
| [`plugin`](#karandash-plugin) | Manage karandash plugins |

### Scaffolding

| Command | Description |
|---------|-------------|
| [`generate`](#karandash-generate) | Generate plugin scaffolding or JSON Schema |
| [`validate-extends`](cli/validate-extends.md) | Validate a config inheritance chain |

### Editor Support

| Command | Description |
|---------|-------------|
| `lsp` | Run the config language server over stdio for IDE extensions. The server answers `textDocument/completion` (rule ids inside `rules {}`, a rule's properties inside its block), `textDocument/hover` (a rule's description and properties) and publishes diagnostics from `config validate` on a `.karandash/config.kts`. JVM distribution (`karandash.jar`) only; native binaries exit 6 (`EXECUTION_ERROR`). Standalone server: `./gradlew :dsl:lsp-server:shadowJar` builds `dsl/lsp-server/build/libs/karandash-lsp.jar`. |

---

## `karandash validate`

Validate a merge request against configured rules.

### Mode Selection

Karandash supports four modes of operation. Only one can be active at a time.

| Mode | Flags | Description |
|------|-------|-------------|
| File mode | `--mr <path>` | Read MR data from a local JSON file |
| Live mode | `--mr-id`, `--platform`, `--project` | Fetch MR data from a platform API |
| PR URL mode | `--pr <url>` | Parse platform and IDs from a PR/MR URL |
| Auto-detect | *(none)* | Detect platform from CI environment or git remote |

### Options

| Flag | Short | Type | Default | Description |
|------|-------|------|---------|-------------|
| `--config` | `-c` | `String` | auto-discovered | Path to configuration file |
| `--mr` | `-m` | `String` | -- | Path to MR data file (JSON) for file mode |
| `--mr-id` | -- | `String` | -- | MR/PR identifier for live mode |
| `--platform` | -- | `String` | -- | Platform name: `gitlab`, `github`, `bitbucket`, etc. |
| `--project` | -- | `String` | -- | Project identifier (e.g., `owner/repo`) |
| `--pr` | -- | `String` | -- | Full PR/MR URL for URL-based validation |
| `--format` | `-f` | `String` | `console` | Output format: `console`, `json`, `sarif`, `rdjsonl`, `rdjson`. When set explicitly, only this reporter runs. |
| `--output` | `-o` | `String` | -- | Write output to file |
| `--quiet` | `-q` | flag | `false` | Suppress all output except errors |
| `--verbose` | `-v` | flag | `false` | Show detailed validation output |
| `--trace` | -- | flag | `false` | Verbose output plus full AI responses and per-rule AI diff file lists (same as `KARANDASH_AI_TRACE=1`) |
| `--strict` | -- | flag | `false` | Treat warnings as failures (exit code 5) |
| `--baseline` | -- | `String` | -- | JSON file of finding fingerprints to ignore; findings already in it are not reported |
| `--update-baseline` | -- | flag | `false` | Rewrite the `--baseline` file from the current findings instead of applying it |
| `--dry-run` | -- | flag | `false` | Validate config without running validation |
| `--explain` | -- | flag | `false` | Show detailed explanations for each finding |
| `--fix` | -- | flag | `false` | Automatically fix safe validation issues (labels, draft status) |
| `--yes` | `-y` | flag | `false` | Skip confirmation prompts when using `--fix` |
| `--enrich` | -- | flag | `false` | Run AI enrichment before validation (generate title/description) |
| `--dry-run-enrich` | -- | flag | `false` | Preview AI enrichment without applying changes |
| `--dry-run-comment` | -- | flag | `false` | Print comment body to console instead of posting to MR |
| `--show-prompt` | -- | flag | `false` | With `--dry-run`: build every AI prompt without sending anything and write each to `ai.debug.prompt-dir` (the files hold MR source code; a directory the dump creates gets a `.gitignore` of `*`) |
| `--auto-approve` | -- | flag | `false` | Automatically approve MR when all findings are below severity threshold |
| `--watch` | `-w` | flag | `false` | Watch config file and re-run validation on changes |
| `--no-auto-detect` | -- | flag | `false` | Disable platform auto-detection |
| `--on-missing-plugin` | -- | `String` | `warn` | How to handle uninstalled modules: `fail`, `warn`, `ignore` |
| `--profile` | -- | `String` | -- | Apply a named profile. Overrides profile in config file. |
| `--show-execution-plan` | -- | flag | `false` | Print validation rules grouped by execution priority and exit |
| `--allow-localhost` | -- | flag | `false` | Allow localhost and private IPs (disables SSRF protection) |
| `--dangerfile` | -- | `String` | auto-detected | Path to Dangerfile.js or Dangerfile.df.kts |
| `--no-cache` | -- | flag | `false` | Disable staleness gate cache - force full re-run of all rules and AI calls; nothing is read from the cache dir (state and cache entries are still saved for the next run) |
| `--invalidate-rules` | -- | `String` | -- | Comma-separated rule IDs to invalidate from the cache |
| `--gate-on-cached` | -- | `Boolean` | `false` | Cached findings merged into the report on a partial cache hit also gate the exit code and auto-approve |
| `--global-timeout` | -- | `String` | -- | Global watchdog timeout (e.g. `600s`, `10m`). Overrides config. `0` = no timeout. |
| `--diagnostic` | -- | `String` | -- | Write JSON runtime diagnostics to the given file path |
| `--dump-dir` | -- | `String` | -- | Save every layer input/output of the run (config, MR, findings per stage, AI plans and calls) as numbered files; local debugging only, secrets redacted best-effort |

### Usage Examples

```bash
# Auto-detect in CI (no flags needed)
karandash validate

# Live mode with explicit platform
karandash validate --mr-id 123 --platform github --project owner/repo

# File mode
karandash validate --mr test-data.json

# PR URL mode
karandash validate --pr https://github.com/owner/repo/pull/42

# JSON output to file
karandash validate --format json --output results.json

# SARIF output for code scanning
karandash validate --format sarif --output results.sarif

# Strict mode (warnings become failures)
karandash validate --strict

# Dry-run to verify config
karandash validate --dry-run

# Auto-fix with confirmation prompt
karandash validate --fix --mr-id 123 --platform github --project owner/repo

# Auto-fix in CI (no prompt)
karandash validate --fix --yes

# Watch mode (re-validate on config changes)
karandash validate --watch --mr test-data.json

# Custom config file
karandash validate -c custom-config.yaml --mr test-data.json

# Force cache bypass
karandash validate --no-cache

# Invalidate specific cached rules
karandash validate --invalidate-rules title-format,size-limits
```

### Mutual Exclusions

- `--watch` and `--fix` cannot be combined.
- `--mr` (file mode) cannot be combined with `--mr-id`, `--platform`, or `--project`.

---

## `karandash init`

Initialize a new Karandash configuration file.

### Options

| Flag | Short | Type | Default | Description |
|------|-------|------|---------|-------------|
| `--output` | `-o` | `String` | `.karandash/config.yaml` | Output path for configuration file |
| `--format` | `-f` | `String` | `yaml` | Config format: `yaml`, `json`, `kts` |
| `--profile` | -- | `String` | -- | Initialize config with a named profile (e.g. `conventional-commits`) |

### Usage Examples

```bash
# Create default YAML config
karandash init

# Create Kotlin DSL config
karandash init --format kts

# Create config at custom path
karandash init --output .karandash/config.yaml

# Create JSON config
karandash init --format json --output .karandash/config.json

# Initialize from a named profile
karandash init --profile conventional-commits
```

If the configuration file already exists, `init` prints a message and exits without overwriting.

---

## `karandash doctor`

Check configuration health, authentication, and plugin status.

### Options

| Flag | Description |
|------|-------------|
| `--plugins` | Show detailed plugin status table (built-in and community modules) |
| `--offline` | Skip the credential check against each platform API |

### Exit Codes

| Code | Meaning |
|------|---------|
| `0` | All checks passed |
| `1` | Some checks failed (non-fatal) |
| `2` | Config parse error (fatal) |

### Usage

```bash
karandash doctor
karandash doctor --plugins
```

---

## `karandash wizard`

Interactive setup wizard for Karandash configuration. Guides through platform selection, profile choice, and config format.

### Options

| Flag | Short | Type | Default | Description |
|------|-------|------|---------|-------------|
| `--output` | `-o` | `String` | `.karandash/config.yaml` | Output path for generated configuration |
| `--format` | `-f` | `String` | *(wizard will ask)* | Output format: `yaml`, `json`, or `kts` |
| `--skip-detection` | -- | flag | `false` | Skip automatic platform detection |

### Usage

```bash
karandash wizard
karandash wizard --format kts
karandash wizard --skip-detection
```

---

## `karandash generate`

Generate plugin scaffolding or a JSON Schema for IDE autocompletion.

### Options

| Flag | Short | Type | Required | Description |
|------|-------|------|----------|-------------|
| `--schema` | -- | flag | No | Generate JSON Schema and write to `.karandash.schema.json` |
| `--type` | `-t` | `String` | Yes (without `--schema`) | Plugin type: `rule`, `reporter`, `platform-service`, `provider`, `mutator`, `comment-poster` (the last three scaffold the platform-service template) |
| `--name` | `-n` | `String` | Yes (without `--schema`) | Plugin name (lowercase with hyphens) |
| `--package` | `-p` | `String` | No | Base package name (default: `io.karandash.plugin.<name>`) |
| `--output` | `-o` | `String` | No | Output directory (default: `.`) |
| `--init-git` | -- | flag | No | Initialize a git repository in the generated project |

### Usage Examples

```bash
# Generate a rule plugin scaffold
karandash generate --type rule --name my-rules

# Generate a reporter plugin scaffold
karandash generate --type reporter --name slack-reporter

# Generate JSON Schema for IDE autocompletion
karandash generate --schema

# Generate with custom package and output directory
karandash generate --type rule --name my-rule --package com.example.myrule --output plugins/
```

The plugin name must be lowercase with hyphens only (e.g., `my-plugin`).

---

## `karandash plugin`

Manage karandash plugins. This is a group command with subcommands.

### Subcommands

| Subcommand | Description |
|------------|-------------|
| `list` | List installed plugins |
| `install` | Install a plugin from a file or registry |
| `remove` | Remove an installed plugin by ID |
| `update` | Update installed plugins to latest versions |
| `create` | Create a new plugin project scaffold |
| `search` | Search the karandash plugin registry |
| `sign` | Sign a plugin JAR with a private key |
| `cache` | Manage the plugin download cache |

---

### `karandash plugin list`

List installed plugins.

| Flag | Type | Default | Description |
|------|------|---------|-------------|
| `--plugin-dir` | `String` | `~/.karandash/plugins/` | Plugin directory |

```bash
karandash plugin list
karandash plugin list --plugin-dir /opt/plugins
```

---

### `karandash plugin install`

Install a plugin from a local file or the plugin registry.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `path` | `String` | No* | Path to plugin file |

| Flag | Type | Default | Description |
|------|------|---------|-------------|
| `--plugin-dir` | `String` | `~/.karandash/plugins/` | Plugin directory |
| `--registry` | `String` | -- | Plugin ID to install from the registry |
| `--pin` | flag | `false` | With `--registry`, upsert `{id, version}` into `plugins.install` of `.karandash/manifest.yaml` |

*Specify exactly one of `path` or `--registry`.

```bash
karandash plugin install build/libs/my-plugin-1.0.0.jar
karandash plugin install --registry io.karandash.plugins.jira-validator
```

---

### `karandash plugin remove`

Remove an installed plugin by its module ID.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `id` | `String` | Yes | Plugin module ID to remove |

| Flag | Type | Default | Description |
|------|------|---------|-------------|
| `--plugin-dir` | `String` | `~/.karandash/plugins/` | Plugin directory |

```bash
karandash plugin remove my-custom-rules
```

---

### `karandash plugin update`

Update installed plugins to their latest versions.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `plugin-id` | `String` | No | Plugin ID to update (omit to update all) |

| Flag | Short | Type | Default | Description |
|------|-------|------|---------|-------------|
| `--check-only` | `-c` | flag | `false` | Only check for updates, don't install |
| `--yes` | `-y` | flag | `false` | Skip confirmation prompts |
| `--plugin-dir` | -- | `String` | `~/.karandash/plugins/` | Plugin directory |

```bash
karandash plugin update
karandash plugin update --check-only
karandash plugin update io.karandash.rules --yes
```

---

### `karandash plugin create`

Create a new plugin project from a template.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `name` | `String` | Yes | Plugin name (lowercase, alphanumeric with hyphens) |

| Flag | Type | Default | Description |
|------|------|---------|-------------|
| `--type` | `String` | `rule` | Plugin type: `rule`, `reporter`, `platform-service`, `provider`, `mutator`, `comment-poster` (the last three scaffold the platform-service template) |
| `--package` | `String` | `io.example.<name>` | Base package name |
| `--no-git` | flag | `false` | Skip git repository initialization |

```bash
# Create a rule plugin
karandash plugin create my-rules

# Create a reporter plugin
karandash plugin create slack-reporter --type reporter

# Create with custom package, no git init
karandash plugin create my-rule --package com.example.myrule --no-git
```

---

### `karandash plugin search`

Search the karandash plugin registry.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `query` | `String` | No | Search query (matches name, description, tags) |

| Flag | Type | Default | Description |
|------|------|---------|-------------|
| `--tag` | `String` | -- | Filter by tag |
| `--type` | `String` | -- | Filter by plugin type |
| `--refresh` | flag | `false` | Force refresh the registry cache |

```bash
# Search by keyword
karandash plugin search jira

# Filter by tag
karandash plugin search --tag integration

# Filter by type
karandash plugin search --type reporter

# Refresh cache and list all
karandash plugin search --refresh
```

---

### `karandash plugin sign`

Sign a plugin JAR with a private key for integrity verification.

| Argument | Type | Required | Description |
|----------|------|----------|-------------|
| `jar-file` | `String` | Yes | Path to the plugin JAR file |

| Flag | Short | Required | Description |
|------|-------|----------|-------------|
| `--key` | -- | Yes | Path to private key file (RSA, PKCS#8 or PKCS#1 DER) |
| `--output` | `-o` | No | Output path for signature file (default: `<jar-file>.sig`) |

```bash
karandash plugin sign my-plugin-1.0.0.jar --key private.der
karandash plugin sign my-plugin.jar --key private.der -o custom.sig
```

---

### `karandash plugin cache`

Manage the plugin download cache.

#### `karandash plugin cache clear`

Clear all cached remote plugin downloads.

```bash
karandash plugin cache clear
```

---

## Output Formats

| Format | Flag | Description |
|--------|------|-------------|
| `console` | `--format console` | Human-readable terminal output (default) |
| `json` | `--format json` | Machine-readable JSON output |
| `sarif` | `--format sarif` | SARIF format for code scanning integrations |
| `rdjson` | `--format rdjson` | Reviewdog JSON format |
| `rdjsonl` | `--format rdjsonl` | Reviewdog JSON Lines format (no envelope) |

## See Also

- [Configuration Schema](configuration-schema.md) -- all configuration options
- [Error Codes](error-codes.md) -- exit code meanings
- [Environment Variables](environment-variables.md) -- env var reference
