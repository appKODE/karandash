# init

Initialize a new Karandash configuration file.

## Overview

The `init` command creates a minimal configuration file in YAML, JSON, or KTS format. If the output file already exists, the command prints a message and exits without overwriting.

## Usage

```bash
karandash init [options]
```

## Options

| Option | Short | Description | Default |
|--------|-------|-------------|---------|
| `--output <path>` | `-o` | Output path for configuration file | `.karandash/config.yaml` |
| `--format <name>` | `-f` | Config format: `yaml`, `json`, or `kts` | `yaml` |
| `--profile <id>` | | Initialize config with a named profile (e.g. `conventional-commits`, `github-flow`) | |

## Examples

### Create default YAML config

```bash
karandash init
```

Creates `.karandash/config.yaml` with minimal rules.

### Create Kotlin DSL config

```bash
karandash init --format kts
```

### Create config at a custom path

```bash
karandash init --output ./config/.karandash/config.yaml
```

The directory is created automatically if it doesn't exist.

### Initialize from a named profile

```bash
karandash init --profile conventional-commits
karandash init --profile github-flow --format yaml
```

Creates a config pre-populated with the profile's rule set.

### JSON config at custom path

```bash
karandash init --format json --output .karandash/config.json
```

## File Conflicts

If the output file already exists, `init` will not overwrite it:

```bash
karandash init
# Output: "Config file already exists at: .karandash/config.yaml"
# Tip: Run 'karandash doctor' to verify your setup
```

To use a different file or update the config:

1. Delete the existing file and run `init` again
2. Specify a new path with `--output`
3. Edit the existing file manually

## Next Steps

After running `init`:

1. **Review** the generated config file
2. **Verify** setup: `karandash doctor`
3. **Test** with a recent MR: `karandash validate --mr mr_data.json`
4. **Customize** rules as needed

## See Also

- [wizard](wizard.md) - Interactive setup wizard
- [validate](validate.md) - Run validation with generated config
- [doctor](doctor.md) - Verify your setup
- [profile](profile.md) - Browse available profiles
