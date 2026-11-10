# mutate

Run mutation tests against your rule configuration.

## Overview

`mutate` generates a fixed set of synthetic "bad" merge requests, each violating one rule in a known way
(oversized change, non-conventional title, empty description, draft, secret in a diff, missing Jira key,
bad branch name), runs the rules configured in your config file on each one, and reports which mutations
were caught. An escaped mutation means the targeted rule is not configured or is too permissive.

The config is loaded the way `validate` loads it. No platform API is called.

## Synopsis

```
karandash mutate [--file <config>]
```

## Options

| Option | Description |
|--------|-------------|
| `--file`, `-f` | Config file path (default: auto-discover) |

## Output

```
## Rule Mutation Testing
Mutation score: 14% (1/7 caught)

- [ESCAPED] large-mr: ...
  Target rule: size-limits - NOT TRIGGERED
- [CAUGHT] bad-title-format: MR title not matching conventional commits
...
```

A mutation counts as caught when any finding's rule ID matches its target, ignoring case and separators
(`titleFormat` catches `title-format`).

## See Also

- [test-rule](test-rule.md) - replay one rule against real MRs
- [validate](validate.md) - run the standard validation pipeline
