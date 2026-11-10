# wizard

Interactive configuration wizard for creating Karandash config files.

## Overview

The `wizard` command guides you through creating a Karandash configuration file interactively. It auto-detects your platform, asks about your workflow, lets you pick a profile, and generates the config in your chosen format.

## Usage

```bash
karandash wizard [options]
```

## Options

| Option | Short | Description | Default |
|--------|-------|-------------|---------|
| `--output <path>` | `-o` | Output path for generated configuration | `.karandash/config.yaml` |
| `--format <fmt>` | `-f` | Output format: `yaml`, `json`, or `kts` (if omitted, wizard will ask) | |
| `--skip-detection` | | Skip automatic platform detection | |

## Examples

```bash
# Interactive wizard with platform auto-detection
karandash wizard

# Generate KTS config
karandash wizard --format kts

# Write to a custom path
karandash wizard --output .karandash/my-config.yaml

# Skip platform auto-detection step
karandash wizard --skip-detection
```

## See Also

- [init](init.md) - Non-interactive config initialization
- [profile](profile.md) - Browse available profiles
- [validate](validate.md) - Run validation with generated config
