# Watch Mode

Watch mode allows Karandash to continuously monitor your configuration file and automatically re-run validation whenever the config changes. This is useful during iterative rule development and testing.

## Usage

```bash
karandash validate --watch
```

Or with short form:

```bash
karandash validate -w
```

## How It Works

When watch mode is enabled:
1. Karandash runs the initial validation
2. Monitors the configuration file for changes
3. Automatically re-runs validation when the config file is modified
4. Continues until you press Ctrl+C

## Requirements

- **Config file required**: Watch mode requires a configuration file. It does not work with zero-config mode.
- **File mode only**: The config file path must be specified or auto-discovered.

## Limitations

- **Cannot combine with --fix**: Watch mode and auto-fix are mutually exclusive. You cannot use `--watch` and `--fix` together.
- **Watches config only**: Only monitors the configuration file itself, not the MR data or repository changes.

## Examples

### Basic Watch Mode

```bash
# Auto-discover config and watch for changes
karandash validate --watch --pr https://github.com/owner/repo/pull/123
```

### With Explicit Config

```bash
# Specify config file to watch
karandash validate --config .karandash/config.yaml --watch --pr <url>
```

### During Rule Development

```bash
# Iterate on rules while watching a test PR
karandash validate -c config.kts -w --pr https://gitlab.com/group/project/-/merge_requests/42
```

## Output Example

```
✓ Title format is valid
✗ Missing required label: "ready-for-review"
✗ Requires 2 approvals, has 1

Watching .karandash/config.yaml for changes... (Press Ctrl+C to stop)

[10:23:45] Config changed, re-validating...
✓ Title format is valid
✓ Required label found: "ready-for-review"
✗ Requires 2 approvals, has 1
```

## Best Practices

1. **Use during development**: Great for testing new rules without re-running commands
2. **Combine with verbose output**: Use `-v` to see detailed validation info
3. **Test with real PRs**: Use `--pr` flag to validate against actual merge requests
4. **Stop when done**: Remember to press Ctrl+C when finished

## Error Messages

**No config file:**
```
Error: --watch mode requires a config file
```
Solution: Provide a config file path or make sure auto-discovery can find one.

**Used with --fix:**
```
Error: --watch and --fix cannot be used together
```
Solution: Use watch mode for testing, auto-fix for one-time corrections.

## See Also

- [Auto-Fix Mode](auto-fix.md) - Automatically fix validation issues
- [Configuration](../configuration/YAML_CONFIG.md) - Config file formats
- [Local PR Testing](../getting-started/quick-start.md) - Testing PRs locally
