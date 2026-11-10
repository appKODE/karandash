# verify-policy

Verify the `policies:` block for logical consistency.

## Overview

`verify-policy` loads the config the way `validate` does (extends chain, `{ file: }` references,
`${VAR}` interpolation) and checks the top-level `policies:` list for contradictions: two policies with
the same `when:` condition that set the same effect (`enabled`, `severity`, `autoApprove`, `autoFix`,
`scripts`, `dangerfile`) to different values on an overlapping set of modules. A policy without `modules:`
applies to every module.

It is static analysis only: no MR is fetched and no rule runs.

## Synopsis

```
karandash verify-policy [--config <config>]
```

## Options

| Option | Description |
|--------|-------------|
| `--config`, `-f` | Config file path (default: auto-discover) |

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | No error-level issue (warnings are still printed) |
| 1 | At least one contradiction was found |
| config error | The config could not be loaded |

## Example

```yaml
policies:
  - when: 'is_draft'
    modules: [titleFormat]
    severity: warning
  - when: 'is_draft'
    modules: [titleFormat]
    severity: error     # contradicts the policy above: verify-policy exits 1
```

```bash
karandash verify-policy --config .karandash/config.yaml
```

## See Also

- [simulate](simulate.md) - simulate approval policy evaluation against MR state
- [YAML config: policies](../../configuration/YAML_CONFIG.md) - the `policies:` reference
