# Code Quality Format

The `codequality` format writes findings as a GitLab Code Quality report (the CodeClimate JSON issue array). GitLab
shows the issues in the MR widget and in the diff, without a bot comment.

## Configuration

<!-- docs-test: skip -->
```yaml
reporters:
  codequality:
    output: gl-code-quality-report.json
```

and in `.gitlab-ci.yml`:

<!-- docs-test: skip -->
```yaml
karandash:
  script: karandash validate
  artifacts:
    reports:
      codequality: gl-code-quality-report.json
```

## Mapping

| Finding | Code Quality issue |
|---------|--------------------|
| `message` | `description` |
| `rule` | `check_name` |
| `severity` | `severity`: error `major`, warning `minor`, info `info` |
| `location` (file and lines) | `location.path`, `location.lines.begin` / `end` |
| `explanation`, `suggestion` | `content.body` (markdown) |
| `fingerprint` | `fingerprint` (the finding's own, else a stable hash of rule, location and message) |

A finding without a file (an MR title or description rule) has no place in the report and is left out; it still
reaches the other reporters. Pair it with `reporters.status` for a commit status that carries the verdict.
