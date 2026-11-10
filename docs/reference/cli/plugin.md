# plugin

Manage Karandash plugins (rules, reporters, and providers).

## Overview

The `plugin` command provides tools for discovering, installing, removing, updating, and creating custom validation plugins. Plugins extend Karandash with custom rules, reporters, and data providers.

## Usage

```bash
karandash plugin <subcommand> [options]
```

## Subcommands

- [`list`](#list) - List installed plugins
- [`install`](#install) - Install a plugin from a file or registry
- [`remove`](#remove) / `uninstall` - Remove an installed plugin
- [`info`](#info) - Show an installed plugin's metadata, file and host requirement
- [`rebuild`](#rebuild) - Reinstall an installed plugin from the registry
- [`load`](#load) - Check that a plugin file loads on this platform, without installing it
- [`verify`](#verify) - Verify a plugin file's RSA-SHA256 signature
- [`registry show` / `registry set`](#registry) - Show or set the plugin registry URL
- [`update`](#update) - Update installed plugins to latest versions
- [`create`](#create) - Create a new plugin project scaffold
- [`search`](#search) - Search the plugin registry
- [`sign`](#sign) - Sign a plugin JAR with a private key
- [`cache clear`](#cache-clear) - Clear the plugin download cache

## list

List all installed plugins.

### Usage

```bash
karandash plugin list [options]
```

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `--plugin-dir <path>` | Custom plugin directory | `~/.karandash/plugins/` |
| `--probe` | Execute each plugin's code (WASM engine, or the JAR bridge on native, under the guest timeouts) to show the ID and version it declares | off: listing reads files only |

### Examples

```bash
# List plugins in default directory
karandash plugin list

# List plugins in custom directory
karandash plugin list --plugin-dir ./my-plugins
```

### Output

```
Installed plugins (~/.karandash/plugins/):
  my-custom-rule v1.0.0 - Custom Title Validator
    JAR: my-custom-rule-plugin.jar
  security-scanner v2.1.0 - Security Vulnerability Scanner
    JAR: security-scanner-plugin.jar
```

## install

Install a plugin. Supported plugin types:

- **Native shared library** (`.dylib` / `.so` / `.dll`) - loaded via `dlopen` on native builds or JNA on JVM
- **JAR** (Kotlin/JVM) - loaded via `ServiceLoader` on JVM builds, or via subprocess bridge on native builds (requires Java 17+)
- **JavaScript** (`.mjs`) - evaluated in a sandboxed QuickJS runtime; supported on all build variants
- **WASM** (`.wasm`) - loaded via Chicory (JVM) or Chasm (native); supported on all build variants

### Usage

```bash
karandash plugin install [<plugin-path>] [options]
```

### Arguments

| Argument | Description |
|----------|-------------|
| `plugin-path` | Path to plugin file (`.jar`, `.dylib`, `.so`, `.dll`, `.mjs`, or `.wasm`) |

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `--plugin-dir <path>` | Custom plugin directory | `plugins.directory` from `.karandash/manifest.yaml`, else `~/.karandash/plugins/` |
| `--registry <id>` | Plugin ID to install from the registry (alternative to providing a path) | |
| `--pin` | With `--registry`, upsert `{id, version}` into `plugins.install` of `.karandash/manifest.yaml` (created if missing; the file is rewritten, so comments are dropped) | |

Registry access (`install --registry`, `update`, `search`) uses `plugins.registry-url`, `plugins.token` and `plugins.auth-header` from `.karandash/manifest.yaml` when set. Registry and download URLs on localhost or a private network are refused unless `plugins.allow-localhost: true` is set there (for a local or test registry).

### Examples

```bash
# Install JVM plugin (JAR) from a local file
karandash plugin install ./my-plugin/build/libs/my-plugin-1.0.0.jar

# Install from the plugin registry by ID
karandash plugin install --registry io.karandash.plugins.jira-validator

# Install native shared library (macOS)
karandash plugin install ./my-plugin.dylib

# Install native shared library (Linux)
karandash plugin install ./my-plugin.so

# Install JavaScript plugin
karandash plugin install ./my-plugin.mjs

# Install WASM plugin
karandash plugin install ./my-plugin.wasm

# Install to custom directory
karandash plugin install ./my-plugin.jar --plugin-dir ./corporate-plugins
```

## remove

Remove an installed plugin by its module ID. `uninstall` is the same command under another name.

### Usage

```bash
karandash plugin remove <plugin-id> [options]
```

### Arguments

| Argument | Description |
|----------|-------------|
| `plugin-id` | Plugin ID to remove |

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `--plugin-dir <path>` | Custom plugin directory | `~/.karandash/plugins/` |

### Examples

```bash
# Remove by module ID
karandash plugin remove io.karandash.plugins.my-rule

# Remove from custom directory
karandash plugin remove io.karandash.plugins.security --plugin-dir ./corporate-plugins
```

## update

Update installed plugins to their latest versions.

### Usage

```bash
karandash plugin update [<plugin-id>] [options]
```

### Arguments

| Argument | Description |
|----------|-------------|
| `plugin-id` | Plugin ID to update (omit to check/update all) |

### Options

| Option | Short | Description |
|--------|-------|-------------|
| `--check-only` | `-c` | Only check for updates, don't install |
| `--yes` | `-y` | Skip confirmation prompts |
| `--plugin-dir <path>` | | Plugin directory (default: `~/.karandash/plugins/`) |

### Examples

```bash
# Check and update all plugins
karandash plugin update

# Only show available updates (don't install)
karandash plugin update --check-only

# Update a specific plugin without prompting
karandash plugin update io.karandash.plugins.jira-validator --yes
```

## create

Create a new plugin project scaffold.

### Usage

```bash
karandash plugin create <name> [options]
```

### Arguments

| Argument | Description |
|----------|-------------|
| `name` | Plugin name (lowercase, alphanumeric with hyphens, e.g. `my-custom-rule`) |

### Options

| Option | Description | Default |
|--------|-------------|---------|
| `--type <type>` | Plugin type: `rule`, `reporter`, `platform-service`, `provider`, `mutator`, `comment-poster` (the last three scaffold the platform-service template) | `rule` |
| `--package <pkg>` | Base package name for generated code (e.g. `com.example.myplugin`) | `io.example.<name>` |
| `--no-git` | Skip git repository initialization | |

### Examples

```bash
# Create a rule plugin (default type)
karandash plugin create my-rule

# Create a reporter plugin
karandash plugin create slack-reporter --type reporter

# Create a plugin with custom package, skip git init
karandash plugin create my-rule --package com.example.myrule --no-git
```

### Output

```
Created rule plugin: my-rule-plugin/

Next steps:
  1. cd my-rule-plugin
  2. ./gradlew build
  3. karandash plugin install build/libs/my-rule-plugin.jar
```

## search

Search the plugin registry for available plugins.

### Usage

```bash
karandash plugin search [query] [options]
```

### Arguments

| Argument | Description |
|----------|-------------|
| `query` | Search term (optional; matches name, description, tags) |

### Options

| Option | Description |
|--------|-------------|
| `--tag <tag>` | Filter by tag |
| `--type <type>` | Filter by plugin type (e.g. `rule`, `reporter`, `provider`, `mutator`, `comment-poster`) |
| `--refresh` | Force refresh the registry cache |

The registry index is cached for 24 hours under `~/.karandash/cache/`. When the registry cannot be fetched
(offline, registry down), `search` falls back to the cached index of any age and warns
`using cached registry from <age> ago`; without a cache it fails with the fetch error.

### Examples

```bash
# List all available plugins
karandash plugin search

# Search by keyword
karandash plugin search security

# Filter by tag
karandash plugin search --tag integration

# Filter by type
karandash plugin search --type reporter

# Force refresh registry cache and list all
karandash plugin search --refresh
```

## sign

Sign a plugin JAR with a private key for integrity verification.

### Usage

```bash
karandash plugin sign <jar-file> [options]
```

### Arguments

| Argument | Description |
|----------|-------------|
| `jar-file` | Path to the plugin JAR file to sign |

### Options

| Option | Short | Description |
|--------|-------|-------------|
| `--key <path>` | | Path to private key file (RSA, PKCS#8 or PKCS#1 DER) - **required** |
| `--output <path>` | `-o` | Output path for signature file (default: `<jar-file>.sig`) |

### Examples

```bash
karandash plugin sign my-plugin-1.0.0.jar --key private.der
karandash plugin sign my-plugin.jar --key private.der --output my-plugin.sig
```

## info

Show an installed plugin's ID, name, version and file.

```bash
karandash plugin info <plugin-id> [--plugin-dir <path>] [--source] [--dependencies]
```

| Option | Description |
|--------|-------------|
| `--plugin-dir <path>` | Plugin directory (default: manifest `plugins.directory`, then `~/.karandash/plugins/`) |
| `--source` | Print only the installed plugin file path |
| `--dependencies` | Look the plugin up in the registry and print the karandash version its registry build requires (`minHostVersion`) and whether this karandash satisfies it. Plugins declare no other dependencies |

Exit code `2` when the plugin is not installed or the registry cannot be fetched.

## rebuild

Reinstall an installed plugin from the registry: the registry build is downloaded again, its checksum verified, the
installed file backed up and replaced. Karandash does not compile plugins; use this when an installed file is
corrupted or was built for another platform.

```bash
karandash plugin rebuild <plugin-id> [--plugin-dir <path>]
```

Exit code `2` when the plugin is not installed, not in the registry, or needs a newer karandash; `6` when the
download or install fails.

## load

Check that a plugin file is valid on this platform and read its metadata, without installing it.

```bash
karandash plugin load ./build/libs/my-plugin.jar
karandash plugin load ./libplugin.so
```

Prints `✓ <file> loads: <id> v<version> (<name>)`; exit code `2` when the file is missing, has an unsupported
extension or carries no plugin metadata.

## verify

Verify a plugin file's RSA-SHA256 signature (the format `plugin sign` writes).

```bash
karandash plugin verify <file> [<signature>] [--key <public-key.der>] [--load]
```

| Argument / option | Description |
|-------------------|-------------|
| `file` | Plugin file |
| `signature` | Signature file; default `<file>.sig` |
| `--key <path>` | Public key (X.509 DER) to verify with; default: every trusted key in `~/.karandash/keys/` |
| `--load` | After the signature checks out, execute the plugin's code (under the guest timeouts) to read the ID it declares |

Exit code `0` valid, `1` no key matches, `2` a file is missing or no key is available.

## registry

Show or set the registry the plugin commands (`install --registry`, `update`, `search`, `info --dependencies`,
`rebuild`) use. The setting is `plugins.registry-url` in `.karandash/manifest.yaml`.

```bash
karandash plugin registry show
karandash plugin registry set https://your-domain.com/karandash/plugins.json
```

`set` accepts HTTPS URLs only and keeps the rest of the manifest (comments included) as written.

## cache clear

Clear the plugin download cache.

### Usage

```bash
karandash plugin cache clear
```

Removes cached downloads of remote plugins, freeing disk space. Use when you want to force a re-download or clear corrupted cache files.

## See Also

- [generate](generate.md) - Generate plugin scaffolding with full project structure
- [validate](validate.md) - Run validation with installed plugins
- [init](init.md) - Generate starter configuration
