# karandash explain

Explains the current configuration by analysing it against all registered module schemas. For each configured module it shows which fields are valid, which have type mismatches, which are unknown (with typo suggestions), and which required fields are missing. It also lists available-but-unconfigured modules.

## Synopsis

```
karandash explain [OPTIONS]
```

## Options

| Option | Short | Type | Default | Description |
|--------|-------|------|---------|-------------|
| `--config` | `-c` | string | auto-discover | Path to the config file to explain |
| `--format` | `-f` | choice | `text` | Output format: `text`, `markdown`, `json`, or `doc` |
| `--ai` | | flag | false | Enhance `doc` output with AI-generated examples (requires an API key) |
| `--output` | `-o` | string | stdout | Write output to a file instead of stdout |

## Output Formats

### text (default)

Human-readable plain-text output, suitable for terminal inspection.

```
karandash explain
```

```
Config file: .karandash/config.yaml

=== title-format ===
  [OK]     pattern: "^(feat|fix):"
  [OK]     minLength: 10
  [WARN]   maxLenght: unknown field (did you mean 'maxLength'?)

=== size-limits ===
  [OK]     maxLines: 500
  [MISSING] maxCommits - required field, default: 20

Available (unconfigured):
  - commit-messages
  - branch-naming
  - security
  ...
```

### markdown

Markdown-formatted explanation, suitable for pasting into a wiki or issue.

```
karandash explain --format markdown
```

### json

Machine-readable JSON, suitable for editor integrations or custom tooling.

```
karandash explain --format json
```

```json
{
  "configPath": ".karandash/config.yaml",
  "modules": [
    {
      "id": "title-format",
      "status": "configured",
      "fields": [
        { "key": "pattern", "status": "ok", "value": "^(feat|fix):" },
        { "key": "maxLenght", "status": "unknown", "suggestion": "maxLength" }
      ],
      "missing": []
    }
  ],
  "unconfigured": ["commit-messages", "branch-naming"]
}
```

### doc

User-friendly documentation for the current config, structured as a reference document. Use `--ai` to add AI-generated examples for each module.

```
karandash explain --format doc
karandash explain --format doc --ai
```

## Config Discovery

When `--config` is not specified, the command auto-discovers the config file using the same logic as `validate`:

1. `.karandash/config.kts`
2. `.karandash/config.yaml` / `.karandash/config.yml`
3. `.karandash/config.json`

If no config file is found, the explanation is based on the zero-config defaults.

## AI Enhancement

The `--ai` flag is only valid with `--format doc`. When set, Karandash queries an AI provider to add generated examples and explanations to the documentation output.

The AI provider is resolved automatically from environment variables (first match wins):

| Environment Variable | Provider |
|---------------------|----------|
| `OPENAI_API_KEY` | OpenAI |
| `ANTHROPIC_API_KEY` | Anthropic (Claude) |
| `GEMINI_API_KEY` | Google Gemini |
| `OLLAMA_HOST` | Ollama (local) |

If `--ai` is set but no API key is found, a warning is printed to stderr and the non-enhanced `doc` output is returned.

## Examples

```bash
# Explain the auto-discovered config in plain text
karandash explain

# Explain a specific config file
karandash explain --config path/to/config.yaml

# Generate Markdown documentation for the current config
karandash explain --format markdown --output docs/karandash-config.md

# Generate AI-enhanced documentation
karandash explain --format doc --ai --output docs/config-reference.md

# Machine-readable JSON for editor integration
karandash explain --format json
```

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success, including a config file that cannot be read or parsed |
| 4 | Internal error |

The `explain` command does not validate or run rules - it only analyses the config schema. A config file that cannot be read or parsed prints `Warning: Failed to parse config: ...` to stderr and is explained as the zero-config defaults, still exiting 0. Use [`config validate`](config.md) to fail on a broken config.

## Related Commands

- [`validate`](validate.md) - Run full MR validation
- [`validate-extends`](validate-extends.md) - Validate the extends chain
- [`profile show`](profile.md) - Show details of a built-in profile
