# Description Format

## Overview

The Description Format validator enforces structure and content standards for MR descriptions.
It requires specific named sections in the MR description, where each section is matched by a regex pattern.

## Configuration

### Optional Properties

- `mrSections`: List of section rules to enforce in the MR description (object list, default: `[]`)
  - Each section rule has:
    - `name`: Human-readable section name (string, required)
    - `pattern`: Regex pattern that must match at least one line of the description (string, required)
    - `required`: Whether the section is mandatory (boolean, default: `false`)

## Examples

### Require Standard MR Sections

```yaml
rules:
  description-format:
    mr-sections:
      - name: "Summary"
        pattern: "^## Summary"
        required: true
      - name: "Testing"
        pattern: "^## Test(ing)?"
        required: true
      - name: "Checklist"
        pattern: "^## Checklist"
        required: false
```

### Full Example

```yaml
rules:
  description-format:
    mr-sections:
      - name: "What Changed"
        pattern: "^## What"
        required: true
      - name: "Why"
        pattern: "^## Why|^## Motivation"
        required: true
```

## Error Messages

- **Missing required section**: `MR description is missing the required section {name}.`
- **Empty description with required sections**: `MR description is empty; required sections are missing: {name1}, {name2}.`

The suggestion gives an example of the section (``Add the Summary section, e.g. `## Summary`.``), taken from the
pattern's own `test { match ... }` case when it has one; the pattern itself is in the finding's format metadata.
- **Invalid regex**: Reported as a finding when the pattern cannot be compiled

## Notes

- Section matching is line-based: a section is found if any single line of the description matches the pattern using `containsMatchIn` (substring search within the line).
- Pattern compilation errors are reported as findings and do not cause validation to throw.

## Related Rules

- [Commit Body](commit-body.md) - Validate commit body presence and pattern
- [Title Format](title-format.md) - Validate MR title format and length
- [Commit Messages](commit-messages.md) - Validate commit message format
- [JIRA Ticket](jira-ticket.md) - Require ticket references in titles
