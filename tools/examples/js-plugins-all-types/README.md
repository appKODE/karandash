# JS Plugins - All Module Types

This directory contains standalone JavaScript ES module examples for every Karandash plugin module type. Each file is self-contained and requires no external dependencies.

## Files

| File | Type | Description |
|------|------|-------------|
| `rule-plugin.mjs` | `RULE` | Validates MR title format against a configurable regex and checks description length |
| `reporter-plugin.mjs` | `REPORTER` | Generates a styled HTML report (`format_id: "html"`) with a findings table |
| `provider-plugin.mjs` | `MERGE_REQUEST_PROVIDER` | Returns a hardcoded mock MR - useful for offline testing and CI dry-runs |
| `mutator-plugin.mjs` | `MERGE_REQUEST_MUTATOR` | In-memory mock mutator; tracks `addLabels`, `removeLabels`, `setDraft`, `updateTitle`, `updateDescription` calls in a log |
| `comment-poster-plugin.mjs` | `COMMENT_POSTER` | In-memory mock comment poster; supports `postComment`, `updateComment`, `findExistingComment`, `postInlineComment` |
| `composite-plugin.mjs` | `RULE` + `REPORTER` + `PROFILE` + `CLI_COMMAND` + `CONFIG_FORMAT` | One guest serving five types, listed in `describe().types` |

## Usage

Copy any plugin file to your Karandash plugins directory:

```bash
cp rule-plugin.mjs ~/.karandash/plugins/
```

Then reference it in your config:

```yaml
plugins:
  - path: "~/.karandash/plugins/rule-plugin.mjs"
```

## Plugin Contract

All plugins export a default object with:

- `id` - unique string identifier
- `name` - human-readable name
- `version` - semver string
- `type` - one of `RULE`, `REPORTER`, `MERGE_REQUEST_PROVIDER`, `MERGE_REQUEST_MUTATOR`, `COMMENT_POSTER`
- `initialize(context)` - called once on load; `context.log()` and `context.hostVersion` available
- `shutdown()` - called on teardown

Type-specific methods are described in each file.
