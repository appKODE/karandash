# deps

Analyze dependencies between an MR and its sibling MRs.

## Overview

`deps` fetches the MR at `--mr-url` and every `--sibling` MR with the platform services configured under
`platforms:`, then reports:

- **File conflicts** - sibling MRs that modify the same files
- **Dependencies** - ordering constraints, e.g. one MR deletes a file another modifies
- **Circular dependencies** - MRs that mutually depend on each other

Siblings are passed explicitly; `deps` does not list open MRs on the platform.

## Synopsis

```
karandash deps --mr-url <URL> [--sibling <URL>]... [--analyze-cross-mr] [--file <config>]
```

## Options

| Option | Description |
|--------|-------------|
| `--mr-url` | The MR/PR to analyze (required) |
| `--sibling` | A sibling MR/PR URL to compare against (repeatable) |
| `--analyze-cross-mr` | Also compare the siblings with each other (full pairwise matrix); conflicts name both MRs |
| `--file`, `-f` | Config file path (default: auto-discover) |

## Usage Examples

```bash
karandash deps --mr-url https://gitlab.com/group/repo/-/merge_requests/42 \
  --sibling https://gitlab.com/group/repo/-/merge_requests/43 \
  --sibling https://gitlab.com/group/repo/-/merge_requests/44
```

## See Also

- [validate](validate.md) - run the full validation pipeline
- [simulate](simulate.md) - simulate approval policy evaluation
