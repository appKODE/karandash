# Conditional Rules (Policies)

## Overview

Context-aware validation - applying stricter or looser rules depending on target branch, changed
files, labels, or other MR characteristics - is handled by the top-level **`policies:`** block.

A policy has a `when:` condition expression. When it evaluates to `true` for the current MR, the
policy can enable or disable modules, deep-merge config overrides into them, change finding
severity, and gate pipeline stages.

## Configuration

<!-- template: `<condition expression>` is a placeholder, not a predicate -->
<!-- docs-test: skip -->
```yaml
policies:
  - when: '<condition expression>'    # required
    name: "human-readable label"      # optional, used in logs
    modules: [approval, reviewer]     # optional, scope to module IDs; omit for all modules
    enabled: false                    # optional, enable/disable the scoped modules
    severity: warning                 # optional, override finding severity
    override:                         # optional, deep-merged into the scoped module configs
      approval:
        minApprovals: 2
    autoApprove: false                # optional, gate the auto-approve stage
    autoFix: false                    # optional, gate the auto-fix stage
    scripts: false                    # optional, gate script execution
    dangerfile: false                 # optional, gate Dangerfile execution
```

Keys are `when` / `modules` / `override` - there is no `condition`, `thenRules`, or `elseRules`.
Policies do not *add* rules; they modify the configuration of rules that already exist. To express
"only run this rule in some situations", configure the rule and disable it via a policy, or use the
per-rule `when:` block (see [Per-rule `when:`](#per-rule-when)).

`modules:` and the keys inside `override:` accept kebab-case or camelCase - both are normalized to
the canonical module IDs before use.

## Condition Expressions

Conditions are boolean expressions over predicates, combined with `&&`, `||`, `!`, parentheses, and
the numeric comparators `>`, `<`, `>=`, `<=`, `==`.

Common predicates:

| Predicate | True when |
|-----------|-----------|
| `is_draft` | MR is a draft/WIP |
| `has_conflicts` | MR has merge conflicts |
| `target_branch_matches("regex")` | Target branch matches the regex |
| `branch_matches("regex")` | Source branch matches the regex |
| `author_matches("regex")` | Author username matches the regex |
| `title_matches("regex")` | MR title matches the regex |
| `label_exists("name")` | MR carries the label (case-insensitive) |
| `file_path_matches("glob")` | Any changed file matches the glob |
| `file_extension_matches(".ext")` | Any changed file has the extension |
| `change_count > N` | Changed-file count comparison |
| `mr_age_days > N` | MR age comparison |
| `is_ci`, `is_gitlab_ci`, `is_github_actions` | CI environment detection |
| `env_set("VAR")`, `env_equals("VAR", "value")` | Environment variable checks |
| `profile_is("id")`, `is_dry_run` | Active profile / dry-run mode |

The full predicate reference (MR state, numeric metrics, field matching, file, environment, and
config predicates) lives in
[YAML Configuration → Condition expression predicates](../configuration/YAML_CONFIG.md#condition-expression-predicates).

Note that file predicates take **globs** while branch/title/author predicates take **regexes**.

## Examples

### Stricter approvals on main

```yaml
policies:
  - when: 'target_branch_matches("^(main|master)$")'
    modules: [approval]
    override:
      approval:
        minApprovals: 2
```

### Relax validation for drafts

```yaml
policies:
  - when: 'is_draft'
    severity: warning
    autoFix: false
    scripts: false
```

### Database changes need the DBA label

```yaml
policies:
  - when: 'file_extension_matches(".sql") || file_path_matches("migrations/**")'
    override:
      labels:
        requiredLabels: ["database-approved"]
```

### Turn a module off outside CI

```yaml
policies:
  - when: '!is_ci'
    modules: [slack-webhook]
    enabled: false
```

### Kotlin DSL

The same policy in `config.kts`. Each named block inside `policies { }` is one list entry (the name is
only a label):

```kotlin
policies {
    offCi {
        when = "!is_ci"
        modules(listOf("slack-webhook"))
        enabled = false
    }
}
```

A KTS policy takes the same keys as YAML: `when`, `enabled`, `severity`, `modules(...)`, `override { }` and
the pipeline-stage gates (`autoApprove`, `autoFix`, `scripts`, `dangerfile`).

## Per-rule `when:`

A single rule can also carry its own inline condition, evaluated during validation. The rule is
skipped when the expression is false; when it is true, an optional `override:` is merged into that
rule's config:

```yaml
rules:
  size-limits:
    maxLines: 500
    when:
      expression: 'target_branch_matches("^release/")'
      override:
        maxLines: 200
```

When no override is needed, the shorthand string form works too: `when: 'is_draft'`.

### Skip a rule for some authors or paths

`when:` decides whether a rule runs for the whole MR. To skip bots, or to run a rule only when certain
files changed, use `author_matches` (regex) and `file_path_matches` (glob):

```yaml
rules:
  title-format:
    pattern: "^(feat|fix|chore): .+"
    when: '!author_matches("^(renovate|dependabot)(\\[bot\\])?$")'
  security:
    when: 'file_path_matches("src/**")'
```

To keep a rule running but drop its findings in some files, list globs under `excludePaths:` instead
(every rule accepts it; findings without a file location are kept):

```yaml
rules:
  security:
    excludePaths: ["generated/**", "**/*.snap"]
```

Use `policies:` for cross-cutting changes affecting several modules, and per-rule `when:` for a
condition that concerns exactly one rule.

## Evaluation Rules

- Every policy is evaluated independently; multiple policies can match the same MR.
- Matching policies are applied in configuration order - later overrides deep-merge over earlier ones.
- Pipeline-stage gates use AND semantics: any matching policy setting a stage to `false` disables it.
- `enabled` with `modules:` toggles those modules; `enabled` without `modules:` applies to all
  configured rules.
- An expression that fails to evaluate is treated as `false` and logged at debug level - it does not
  abort the run.
- **Scope.** Policies are evaluated after the MR is fetched and enriched and after modules are
  initialized. `enabled: false` therefore stops a rule from running, an enrichment provider from
  fetching and a reporter/output from publishing, but it does not skip module initialization, and it
  cannot switch off pipeline extensions (such as the AI pipeline extension), platform services or MR
  enrichment. Disable those in the top-level config instead.

Run `karandash validate --log-level debug` to see which policies matched and which overrides were
applied, or use the [`verify-policy`](../reference/cli/verify-policy.md) command.

## Related Rules

Any module can be targeted by a policy:

- [Approvals](approvals.md)
- [Reviewers](reviewers.md)
- [File Changes](file-changes.md)
- [Labels](labels.md)
- [Size Limits](size-limits.md)
- [CI/CD Status](cicd-status.md)
- [Security](security.md)
- [Draft Rules](draft-detection.md)
