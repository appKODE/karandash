# Dependency Validation

## Overview

The Dependency Validation rule checks changed dependency files in a merge request for unpinned versions, outdated packages, and known-vulnerable dependency patterns.

**Supported files:** `package.json`, `requirements.txt`, `Cargo.toml`, `go.mod`, `build.gradle`, `build.gradle.kts`, `gradle/libs.versions.toml`

**Rule ID:** `dependency-validation`
**Tier:** Extended (plugins-extended)

## Configuration

### Optional Properties

- `warnOnUnpinnedVersions`: Warn when dependencies use unpinned version specifiers like `^`, `~`, `*`, or ranges (boolean, default: `true`)
- `minVersions`: Map of package names to minimum required versions (map, default: `{}`)
- `blockedPatterns`: List of dependency patterns to block entirely (list, default: `[]`). Forms: `name`, `name:version-prefix`, `group:artifact` (any version) and `group:artifact:version-prefix`

## Examples

### Basic Usage

```yaml
rules:
  dependency-validation:
    warnOnUnpinnedVersions: true
```

Warns on version specifiers like `"lodash": "^4.0.0"` or `requests>=2.0`.

### Enforce Minimum Versions

```yaml
rules:
  dependency-validation:
    warnOnUnpinnedVersions: true
    minVersions:
      lodash: "4.17.21"
      express: "4.18.0"
      django: "4.2.0"
```

Produces an error if any changed dependency file pins a version below the specified minimum.

### Block Vulnerable Dependencies

```yaml
rules:
  dependency-validation:
    blockedPatterns:
      - "log4j:2.14"
      - "event-stream"
      - "ua-parser-js:0.7"
```

Blocks MRs that add or modify dependencies matching these patterns.

### Kotlin DSL - not supported

This rule cannot be configured from `.karandash/config.kts`. Its rule ID is the only hyphenated one
in the built-in set, and DSL block names must be valid identifiers
(`IDENTIFIER : [a-zA-Z_][a-zA-Z0-9_]*` in `dsl/kts-parser/src/commonAntlr/antlr/KarandashDsl.g4`), so
`dependency-validation { }` does not parse. There is no camelCase alias - `dependencyValidation { }`
is reported as an unrecognized rule and ignored.

Use YAML or JSON config for this rule. (In YAML both `dependency-validation:` and
`dependencyValidation:` work; keys are normalized during parsing.) `minVersions` is a map, which the
flat KTS DSL cannot express in any case.

## How It Works

The rule scans only dependency files that appear in the MR's changed file list. It parses each file format natively to extract dependency declarations and version specifiers, then checks against the configured constraints.

| Finding | Severity | Condition |
|---------|----------|-----------|
| Unpinned version | WARNING | `warnOnUnpinnedVersions: true` and version uses range specifier |
| Below minimum | ERROR | Version is lower than `minVersions` entry |
| Blocked pattern | ERROR | Dependency matches a `blockedPatterns` entry |

## See Also

- [Security](security.md) -- detects secrets and sensitive file changes
- [File Changes](file-changes.md) -- validates which files were modified
