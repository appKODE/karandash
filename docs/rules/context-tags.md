# Context Tags

## Overview

The Context Tags validator enforces the presence and validity of context tags in MR titles and commit messages. Context tags are bracketed labels (e.g., `[ios]`, `[android]`, `[core]`) that categorize which area of a codebase a change affects. This rule makes sure every MR and its commits are tagged with exactly one (or at least one) recognized context tag from a configured allowlist.

## Configuration

### Required Properties

- `allowed`: List of allowed tag values (string list). Validation is skipped when this list is empty.

### Optional Properties

- `exactlyOne`: Require exactly one matching tag per title (boolean, default: `true`). When `false`, one or more tags are accepted.
- `checkMrTitle`: Validate context tags in the MR title (boolean, default: `true`)
- `checkCommitTitles`: Validate context tags in each commit's first line (boolean, default: `true`)
- `tagPattern`: Regex with a capture group that extracts the tag value (string, default: `\[([a-zA-Z][a-zA-Z0-9_]*)\]`)
- `caseSensitive`: Use case-sensitive tag matching against the allowlist (boolean, default: `false`)
- `delimiter`: Auto-generates `tagPattern` from a delimiter type. Simpler alternative to setting `tagPattern` directly. Ignored when `tagPattern` is set (`DelimiterType`, default: `null`). Values:
  - `BRACKETS` - matches `[tag]`
  - `PARENS` - matches `(tag)`
  - `BRACES` - matches `{tag}`
  - `NONE` - matches bare `tag` (no surrounding delimiters)

## Examples

### Basic Usage

```yaml
rules:
  context-tag:
    allowed:
      - ios
      - android
      - core
      - backend
```

With this config, a title like `[ios] Fix crash on launch` passes, while `Fix crash on launch` fails.

### Allow Multiple Tags

```yaml
rules:
  context-tag:
    allowed:
      - ios
      - android
      - core
    exactly-one: false
```

### Custom Tag Pattern

```yaml
rules:
  context-tag:
    allowed:
      - FEAT
      - FIX
      - DOCS
    tag-pattern: "\\(([A-Z]+)\\)"
    case-sensitive: true
```

This matches tags in parentheses like `(FEAT)` instead of brackets.

### Commits Only

```yaml
rules:
  context-tag:
    allowed:
      - api
      - ui
      - db
    check-mr-title: false
    check-commit-titles: true
```

## Error Messages

- **No tag found**: `No context tag found in {MR title|commit {sha}}.`, with the suggestion ``Add one of the allowed context tags to the MR title: `[tag1]`, `[tag2]`.``
- **Multiple tags found**: `Multiple context tags found in {entity}: [tag1], [tag2].`, with the suggestion ``Keep only one context tag in the MR title, e.g. `[tag1]`.``
- **Tag in the wrong case** (only with `caseSensitive: true`, when a tag differs from an allowed one only in case): `Commit a1b2c3d: tag [iOS] is not accepted - tags are case-sensitive here.`, with the suggestion `Use [ios] exactly, or set caseSensitive: false.`
- Commit findings of one kind (no tag, several tags, wrong case) fold into one when three or more commits fail: `4 commits have no context tag (a1b2c3d, ..., +1 more)`; each commit's message stays in the `All {N} commits` attachment.
- **Invalid regex**: Reported as a finding if `tagPattern` cannot be compiled

Tags in these messages use the configured `delimiter` (`(tag1)`, `{tag1}`, bare `tag1` for `none`); brackets are the default, also when only `tagPattern` is set.

## Default Tag Format

The default pattern `\[([a-zA-Z][a-zA-Z0-9_]*)\]` matches tags of the form `[word]` where the tag starts with a letter and may contain letters, digits, and underscores. Matching against the allowlist is case-insensitive by default.

## Notes

- When `allowed` is empty, the validator returns no findings (validation is disabled).
- The tag value captured by group 1 of `tagPattern` is compared against the `allowed` list.
- Case-insensitive comparison (default) means `[IOS]`, `[iOS]`, and `[ios]` all match an allowlist entry of `ios`.

## Related Rules

- [Title Format](title-format.md) - Validate MR title format and length
- [Branch Naming](branch-naming.md) - Enforce branch naming conventions
