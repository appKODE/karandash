# Commit Body

## Overview

The Commit Body validator enforces commit body presence and format.
It can require commit bodies and validate them against a regex pattern.

## Configuration

### Optional Properties

- `pattern`: Regex pattern that commit body must match (string, default: `null`)
- `required`: Whether every commit must have a body (boolean, default: `false`)
- `requiredWhenSubjectMatches`: Regex pattern - when a commit subject matches, a body is required (string, default: `null`)
- `requiredWhenTitleMatches`: Regex pattern - when the MR title matches, every commit needs a body; this wins over `requiredWhenSubjectMatches` and `required` (string, default: `null`)
- `matchMode`: How to apply `pattern` (string, default: `fullMatch`)
  - `fullMatch` - the entire first body line must match the pattern
  - `contains` - the pattern must appear anywhere in the first body line
  - `anyLine` - the pattern must match at least one line of the body
- `scope`: `perCommit` (default) checks every commit on its own; `anyCommit` passes when at least one commit in the MR has a body (matching `pattern`, if set) and reports a single finding otherwise (string, default: `perCommit`)

## Examples

### Require Commit Body

```yaml
rules:
  commit-body:
    required: true
```

### Pattern Match (Contains)

```yaml
rules:
  commit-body:
    pattern: "Closes #\\d+"
    matchMode: "contains"
```

### Full Example

```yaml
rules:
  commit-body:
    required: true
    pattern: "^(Closes|Fixes|Refs) #\\d+"
    matchMode: "contains"
```

## Error Messages

- **Commit body missing**: `Commit body is required but missing in commit {sha}`
- **Commit body pattern mismatch**: names the line the pattern wants (taken from the pattern's leading word: `^CHANGELOG: .+` gives `no CHANGELOG line`; otherwise ``no line matching `{pattern}` ``), quotes the line that was checked and names the match mode:
  - `fullMatch` / `contains`: `Commit e379ea0: no CHANGELOG line in the body; first line "{line}" does not match (checked: first line only, match-mode: fullMatch; set match-mode: anyLine to check every line)`
  - `anyLine`: `Commit e379ea0: no CHANGELOG line in the body; none of 3 lines match (match-mode: anyLine); closest: "{line}"` (the closest line is the first one mentioning the leading word, else the first line)
  - The suggestion gives a line that would match, e.g. `Add a line like "CHANGELOG: [BRAVO-3158] <user-visible change>." as the first body line.`
- **Invalid regex**: Reported as a finding when the pattern cannot be compiled

When three or more commits fail the same way, they fold into one finding - `{N} commits have no CHANGELOG line in the body ({sha1}, {sha2}, {sha3}, +{rest} more)` - and every per-commit message stays in its collapsible `All {N} commits` attachment.

## Notes

- `pattern` is checked only when a commit has a body (lines after the first line).
- `pattern` is checked only on commits that need a body. When `requiredWhenSubjectMatches` or `requiredWhenTitleMatches`
  is set, a commit those filters leave out may carry any body. With neither filter set, a body that is present must
  match, whether or not `required` is set.
- Which commits need a body, highest precedence first: an MR title matching `requiredWhenTitleMatches` requires a body
  on every commit; otherwise a configured `requiredWhenSubjectMatches` decides per commit (and `required` is not
  consulted); otherwise `required`.
- If a commit has no body and `required` is `false`, pattern matching is skipped.
- Pattern compilation errors are reported as findings and do not throw.

## Related Rules

- [Description Format](description-format.md) - Validate MR description sections
- [Commit Messages](commit-messages.md) - Validate commit title format
