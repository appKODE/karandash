# Pattern Reference

## Overview

Karandash supports a 5-tier pattern system for text matching. Each tier compiles to standard regex at config-parse time. The validation engine is unchanged - it receives compiled `Regex` objects regardless of which tier produced them.

## Pattern Tiers

### 1. Named Patterns (`@name`)

Zero-regex patterns that expand to well-tested regex. Use `@` prefix.

#### Core Patterns (always available)

**Case patterns:**

| Pattern | Matches | Example |
|---------|---------|---------|
| `@kebab-case` | lowercase with hyphens | `my-feature` |
| `@snake-case` | lowercase with underscores | `my_feature` |
| `@camel-case` | camelCase | `myFeature` |
| `@pascal-case` | PascalCase | `MyFeature` |
| `@screaming-snake` | UPPER_SNAKE | `MY_FEATURE` |
| `@sentence-case` | Starts with uppercase | `My feature` |
| `@lower` | all lowercase | `feature` |
| `@upper` | all uppercase | `FEATURE` |
| `@slug` | URL-safe slug | `my-feature-123` |

**Structural patterns:**

| Pattern | Expands to | Usage |
|---------|-----------|-------|
| `@optional(P)` | `(?:P)?` | `@optional(@jira-ticket)` |
| `@bracketed(P)` | `\[P\]` | `@bracketed(ios\|android)` |
| `@parens(P)` | `\(P\)` | Wrap in parentheses |
| `@one-or-more(P)` | `(?:P)+` | One or more occurrences |
| `@separated-list(P, S)` | `P(?:SP)*` | Separated list |

**Format patterns:**

| Pattern | Matches |
|---------|---------|
| `@semver` | `1.2.3`, `1.0.0-beta.1` |
| `@email` | `user@example.com` |
| `@url` | `https://example.com` |
| `@uuid` | UUID v4 |
| `@date-iso` | `2024-01-15` |
| `@ipv4` | `192.168.1.1` |

#### Plugin-Registered Patterns

These patterns are available when their plugin module is loaded. When a plugin is published to the registry and installed, its patterns ship with it.

| Pattern | Plugin | Matches |
|---------|--------|---------|
| `@jira-ticket` | jira-ticket | `PROJ-123` |
| `@jira-ticket(@project:MAPS)` | jira-ticket | `MAPS-123` |
| `@github-issue` | jira-ticket | `#42` |
| `@linear-ticket` | jira-ticket | `TEAM-123` |
| `@shortcut-ticket` | jira-ticket | `sc-123` |
| `@youtrack-ticket` | jira-ticket | `PROJ-123` |
| `@any-ticket` | jira-ticket | Any ticket format |
| `@conventional-commit` | title-format | `feat: add feature` |
| `@conventional-commit(@types:feat,fix)` | title-format | Restricted types |
| `@branch-prefix(@prefixes:feature,bugfix)` | branch-naming | `feature/...` |
| `@wip-marker` | draft | `WIP`, `[WIP]`, `Draft:` |

### 2. Structured Validators

Domain-specific config fields that eliminate regex entirely. Each rule module provides structured alternatives to `pattern:`.

```yaml
# title-format - structured config
title-format:
  type:
    allowed: [feat, fix, docs, chore]
  scope:
    required: false
  subject:
    min-length: 10
    no-period: true
  separator: ": "

# branch-naming - structured config
branch-naming:
  prefix-one-of: [feature/, bugfix/, hotfix/]
  case: kebab
  max-length: 60

# jira-ticket - structured config
jira-ticket:
  projects: [MAPS, CORE]
  format: jira

# commit-messages - structured config
commit-messages:
  type:
    allowed: [feat, fix, docs]
  subject:
    max-length: 72

# draft - structured config
draft:
  wip-markers: [WIP, DRAFT, "DO NOT MERGE"]
```

When structured fields are present and `pattern:` is not set, the rule builds regex internally from the structured fields. If `pattern:` is also set, it takes precedence (override escape hatch).

### 3. Text Glob (`glob:`)

Simple wildcard matching for text (not file paths):

- `*` - any characters
- `?` - single character
- `{a,b,c}` - alternation
- `[abc]` - character class

Automatically anchored (`^...$`).

```yaml
title-format:
  pattern: "glob:{feat,fix,chore}: *"

branch-naming:
  pattern: "glob:{feature,bugfix,hotfix}/*"
```

### 4. Pomsky (`pomsky:`)

Human-readable regex DSL ([language guide](https://pomsky-lang.org/docs/language-tour/basics/)). The compiler is built in
(the `pomsky-kt` library) on every target; no `pomsky` binary is needed.

```yaml
title-format:
  pattern: "pomsky: let type = 'feat' | 'fix' | 'docs'; ^ type ': ' .+"
```

`karandash config validate` and `karandash doctor` compile every `pomsky:` value of the config with the pomsky
linter on:

- a compile error fails, naming the config key and marking the bad part with carets;
- a lint diagnostic (unreachable alternative, nested repetition, ReDoS risk) is a warning;
- a pattern whose complexity score is `HIGH` is a warning.

The pomsky help text, when there is one, follows on a `→ How to fix:` line:

```text
Config validation failed: rules.titleFormat.pattern: pomsky pattern does not compile: Variable `unknown_ref` doesn't exist
    'a' unknown_ref
        ^^^^^^^^^^^
  → How to fix: Fix the pomsky expression at the marked position.
Warning: rules.branchNaming.patterns[1]: This expression may cause catastrophic backtracking (ReDoS) due to nested quantifiers
  → How to fix: Rewrite to avoid nesting unbounded quantifiers. Use atomic groups or ensure alternatives don't overlap.
```

`doctor` lists these under `[Patterns]` as `FAIL:` (the run exits 1) and `WARN:` (does not fail the run).

## Fix hints in findings

When a value does not match its format (`titleFormat`, `commitMessages`, `branchNaming`, `descriptionFormat`,
`jiraTicket`, `contextTag`), the finding's `message` states only the problem. How to fix it is in `suggestion`:

- ``Change the title to `feat: add login`.`` - the value with the missing literal parts inserted. Only this kind of
  fix is applied by `--fix` (titles only).
- ``Change the title to match the format, e.g. `fix: crash on start`.`` - an example: the pattern's own
  `test { match ... }` case first, else a generated one.

The finding's metadata carries the format for reporters: `expectedFormat` (readable pomsky, regex patterns
decompiled), `mismatch` (where the value diverges), `formatExplain` (the format in words)
and `formatDiagram` (a text railroad diagram). `formatExplain` is always English: the built-in rules do not pass
the output language yet, and pomsky-kt explains only in English and Russian, so a `pl` report would get English
even then.

### 5. Regex (`regex:` or bare string)

Standard regex. Bare strings (without prefix) are treated as regex for backward compatibility.

```yaml
# Explicit regex prefix:
title-format:
  pattern: "regex:^(feat|fix): .+"

# Bare string (backward compatible):
title-format:
  pattern: "^(feat|fix): .+"
```

## User-Defined Patterns

Define reusable patterns in the `patterns:` config block. They can reference built-in patterns, plugin patterns, and other user patterns.

```yaml
patterns:
  my-ticket: "@jira-ticket(@project:MAPS)"
  my-tag: "@bracketed(ios|android|core)"
  my-title: "@optional(@my-tag) @conventional-commit(@types:feat,fix)"
```

Use in rules:

```yaml
title-format:
  pattern: "@my-title"

jira-ticket:
  pattern: "@my-ticket"
```

Inside a pattern body, an `@name` that is not a registered pattern is kept as literal text, so an email-like
`renovate@bot\.example` works. A pattern used directly in a rule (`pattern: "@my-titel"`) must exist.

## Condition Operators

Use patterns in conditional expressions alongside existing function syntax:

```yaml
conditional:
  # Glob match (new)
  - condition: 'title *= "feat:*"'
  - condition: 'branch *= "feature/*"'

  # Regex match (new)
  - condition: 'title ~= "^(feat|fix):"'

  # Exact match (new)
  - condition: 'author = "dependabot"'

  # Structured predicates (new)
  - condition: 'has_ticket("MAPS")'
  - condition: 'has_tag("ios")'

  # Function syntax (existing, kept)
  - condition: 'title_matches("^feat:")'
  - condition: 'branch_matches("feature/")'
```

## Multi-Glob Expansion

File-path glob patterns support brace expansion:

```yaml
ai:
  scopes:
    backend:
      paths: "{src,lib}/**/*.{kt,java}"
      # Expands to: src/**/*.kt, src/**/*.java, lib/**/*.kt, lib/**/*.java
```

## Custom Plugin Patterns

Plugin authors can register named patterns via the `registerPatterns()` lifecycle method:

```kotlin
class MyRuleModule : AbstractTypedSingleRuleModule<MyConfig>() {
    override fun registerPatterns(registry: PatternRegistry) {
        registry.register("my-pattern", PatternDefinition(
            name = "my-pattern",
            description = "Matches my custom format",
            parameters = listOf(PatternParam(name = "prefix", default = "X")),
            expand = { params ->
                val prefix = params["prefix"] ?: "X"
                "$prefix-\\d+"
            },
        ))
    }
}
```

Users can then use `@my-pattern` or `@my-pattern(@prefix:Y)` in their config.

External plugins (JS, WASM, native) register patterns via their respective bridge APIs:

- **JS:** `context.registerPattern("name", "regex", "description")`
- **WASM:** `karandash_register_pattern(name_ptr, name_len, regex_ptr, regex_len)`
- **Native:** `karandash_register_pattern(name, regex_string)`
