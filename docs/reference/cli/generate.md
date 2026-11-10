# generate

Generate plugin scaffolding or a JSON Schema for IDE autocompletion.

## Overview

The `generate` command creates a complete plugin project scaffold for rule, reporter, provider, mutator, or comment-poster plugins. It can also generate a `.karandash.schema.json` JSON Schema file for IDE autocompletion.

## Usage

```bash
karandash generate [options]
```

## Options

| Option | Short | Description | Default |
|--------|-------|-------------|---------|
| `--schema` | | Generate JSON Schema for IDE autocompletion and write to `.karandash.schema.json` | |
| `--type <type>` | `-t` | Plugin type: `rule`, `reporter`, `platform-service`, `provider`, `mutator`, `comment-poster` (the last three scaffold the platform-service template) | |
| `--name <name>` | `-n` | Plugin name (lowercase with hyphens, e.g. `my-plugin`) | |
| `--package <pkg>` | `-p` | Base package name (default: `io.karandash.plugin.<name>`) | |
| `--output <dir>` | `-o` | Output directory | `.` |
| `--init-git` | | Initialize a git repository in the generated project | |

A missing `--type`/`--name`, an invalid name, or a generation failure exits with status 1.

## Usage Examples

### Generate a rule plugin scaffold

```bash
karandash generate --type rule --name my-rules
```

### Generate a reporter plugin scaffold

```bash
karandash generate --type reporter --name slack-reporter
```

### Generate with custom package and output directory

```bash
karandash generate --type rule --name my-rule --package com.example.myrule --output plugins/
```

### Generate JSON Schema for IDE autocompletion

```bash
karandash generate --schema
```

Writes `.karandash.schema.json` to the current directory. Point your IDE's YAML/JSON schema association at this file for autocompletion in config files.

### Generate with git initialization

```bash
karandash generate --type provider --name my-provider --init-git
```

## Plugin Name Rules

The plugin name must be lowercase letters, digits, and hyphens only, starting with a letter (e.g. `my-plugin`, `custom-rule`).

## Output

On success:

```
✓ Plugin 'my-rules' generated at /path/to/my-rules-plugin

Next steps:
  1. cd my-rules-plugin
  2. ./gradlew build
  3. Edit src/commonMain/kotlin/... to implement your rule logic
```

## See Also

- [plugin](plugin.md) - Install and manage plugins
- [validate](validate.md) - Run validation with installed plugins
