# Quick Start

Get started with Karandash in minutes.

## 1. Initialize Configuration

Create a default configuration file:

```bash
karandash init
```

This creates `.karandash/config.yaml` in your project root. YAML is the default
format; pass `--format kts` or `--format json` for the other two.

## 2. Configure Rules

Edit `.karandash/config.yaml` to define validation rules:

```yaml
rules:
  titleFormat:
    minLength: 10
    pattern: "^(feat|fix|docs|refactor)(\\(.+\\))?!?: .+"

  commitMessages:
    format: conventional

  branchNaming:
    pattern: "^(feature|fix|chore)/.+"
```

The equivalent Kotlin DSL (`--format kts`):

```kotlin
karandash {
    rules {
        titleFormat {
            minLength = 10
            pattern = "^(feat|fix|docs|refactor)(\\(.+\\))?!?: .+"
        }

        commitMessages {
            format = "conventional"
        }

        branchNaming {
            pattern = "^(feature|fix|chore)/.+"
        }
    }
}
```

`titleFormat` also accepts `maxLength`, `requireTicket`, and structured
`type` / `scope` / `subject` blocks instead of a raw `pattern`. See
[Rules](../rules/index.md) for the full set of built-in rule IDs.

## 3. Run Validation

Validate your merge request:

```bash
karandash validate
```

## 4. CI/CD Integration

### GitLab CI

```yaml
validate:mr:
  stage: test
  script:
    - karandash validate
  only:
    - merge_requests
```

### GitHub Actions

```yaml
name: MR Validation
on: [pull_request]
jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - run: karandash validate
```

## Next Steps

- Explore all [Rules](../rules/index.md)
- Create custom [Plugins](../plugins/creating-plugins.md)
