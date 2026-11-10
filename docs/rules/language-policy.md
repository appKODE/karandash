# Language Policy

## Overview

The Language Policy validator enforces that all human-readable text in an MR uses only characters from allowed writing scripts. This is useful for teams that require all MR metadata, commit messages, and branch names to be written in a specific language (e.g., English only, or English plus a second language).

The validator operates at the Unicode script level: it checks every letter character against a set of allowed Unicode ranges and reports any disallowed characters found.

It never reads file contents: a changelog, doc or code comment written in another language is not a
language-policy finding. The output language of the review itself is `report.language`
([YAML config](../configuration/YAML_CONFIG.md#report-language)); with it set, the AI prompt also states that the
language of the MR content is never a finding.

## Configuration

### Optional Properties

- `checkMrTitle`: Validate the MR title (boolean, default: `true`)
- `checkMrDescription`: Validate the MR description (boolean, default: `true`)
- `checkCommitTitle`: Validate the first line of each commit message (boolean, default: `true`)
- `checkCommitDescription`: Validate the body of each commit message (boolean, default: `true`)
- `checkBranchName`: Validate the source branch name (boolean, default: `true`)
- `allowedScripts`: Predefined script names to allow (string list, default: `["latin"]`)
  - Supported values: `latin`, `cyrillic`, `greek`, `arabic`, `cjk`, `hebrew`, `devanagari`
- `additionalAllowedRanges`: Custom Unicode code point ranges to allow in addition to `allowedScripts` (string list, default: `[]`)
  - Format: `"0400-04FF"` or `"0x0400-0x04FF"` (hex, start-end inclusive)
- `languageName`: Language name used in error messages (string, default: `"English"`)

## Unicode Script Ranges

| Script name | Unicode range | Example languages |
|-------------|--------------|------------------|
| `latin` | U+0041–U+024F | English, French, German, Spanish, etc. |
| `cyrillic` | U+0400–U+04FF | Russian, Bulgarian, Serbian, etc. |
| `greek` | U+0370–U+03FF | Greek |
| `arabic` | U+0600–U+06FF | Arabic, Persian, Urdu |
| `cjk` | U+4E00–U+9FFF | Chinese, Japanese (CJK), Korean |
| `hebrew` | U+0590–U+05FF | Hebrew |
| `devanagari` | U+0900–U+097F | Hindi, Sanskrit, Marathi |

## Examples

### English Only (Default)

```yaml
rules:
  language-policy: true
```

### English and Russian

```yaml
rules:
  language-policy:
    allowed-scripts:
      - latin
      - cyrillic
    language-name: "English or Russian"
```

### Skip Branch Name Check

```yaml
rules:
  language-policy:
    allowed-scripts:
      - latin
    check-branch-name: false
```

### Custom Unicode Range

```yaml
rules:
  language-policy:
    allowed-scripts:
      - latin
    additional-allowed-ranges:
      - "00C0-00FF"   # Latin Extended-A
      - "0100-017F"   # Latin Extended-B (already in latin range, shown for illustration)
    language-name: "English"
```

### Commits Only

```yaml
rules:
  language-policy:
    allowed-scripts:
      - latin
    check-mr-title: false
    check-mr-description: false
    check-branch-name: false
    check-commit-title: true
    check-commit-description: true
```

## Error Messages

- **Text not in the language**: `{Entity} is not in {languageName} ({Script} found)`, e.g. `Commit 7d40815 subject is not in English (Cyrillic found)`
  - The script comes from the table above (`Cyrillic`, `Greek and Cyrillic`, ...); a letter in none of them reads `other letters`.
  - Suggestion: `Rewrite the {entity} in {languageName}, replacing '{char}', ...` (up to 5 characters).
  - Entity is one of: `MR title`, `MR description`, `branch name`, `commit {sha} subject`, `commit {sha} body`.
  - When three or more commits fail the same way, they fold into one finding - `{N} commits have a subject not in English ({sha1}, {sha2}, {sha3}, +{rest} more)` - and every per-commit message stays in its collapsible `All {N} commits` attachment.

## Notes

- Only letter characters (`Char.isLetter()`) are checked. Numbers, punctuation, whitespace, and symbols are always allowed.
- The `latin` script range (U+0041–U+024F) covers Basic Latin, Latin-1 Supplement, Latin Extended-A, Latin Extended-B, and IPA Extensions - sufficient for virtually all Latin-alphabet languages.
- When multiple disallowed characters are found in one text field, a single finding is reported listing up to 5 of them.
- `additionalAllowedRanges` values without a `0x` prefix are interpreted as plain hex strings.

## Related Rules

- [Title Format](title-format.md) - Validate MR title format and length
- [Commit Messages](commit-messages.md) - Validate commit message format
- [Branch Naming](branch-naming.md) - Enforce branch naming conventions
