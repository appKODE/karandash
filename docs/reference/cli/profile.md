# karandash profile

Manages and explores the built-in configuration profiles. Profiles are pre-configured rule sets for common workflows such as Conventional Commits, GitHub Flow, or trunk-based development.

## Synopsis

```
karandash profile <subcommand> [OPTIONS]
```

## Subcommands

| Subcommand | Description |
|------------|-------------|
| `list` | List all available profiles in a table |
| `show <id>` | Show detailed configuration for a profile |
| `export <id>` | Export a profile as a ready-to-use YAML config file |

---

## karandash profile list

Lists all registered profiles sorted alphabetically.

### Synopsis

```
karandash profile list [--tag TAG]
```

### Options

| Option | Type | Description |
|--------|------|-------------|
| `--tag` | string | Filter profiles by tag (e.g. `branching`, `security`, `commits`) |

### Output

```
PROFILE                  SUMMARY                                             TAGS
--------------------------------------------------------------------
conventional-commits     Enforces the Conventional Commits specification...  [commits, semantic-versioning, title]
gitflow                  Traditional GitFlow — feature, release, hotfix...   [branching, title]
github-flow              GitHub Flow branching conventions...                 [branching, size, title]
integration-mr           Integration merge requests (feature or release i... [branching, commits]
minimal                  Sensible non-blocking defaults...                    [draft-prevention, size, title]
security-conscious       Security-first defaults...                           [file-changes, security, size]
semantic-versioning      Semantic versioning commit types...                  [commits, semantic-versioning, title]
trunk-based              Trunk-based development — small, fast PRs...         [branching, draft-prevention, size]
```

### Examples

```bash
# List all profiles
karandash profile list

# Filter by tag
karandash profile list --tag branching
karandash profile list --tag security
```

---

## karandash profile show

Shows the full configuration details for a single profile, including the rules it enables and their configured values.

### Synopsis

```
karandash profile show <profile-id>
```

### Arguments

| Argument | Description |
|----------|-------------|
| `profile-id` | The profile identifier (e.g. `conventional-commits`, `minimal`) |

### Output

```
Profile:  conventional-commits
Summary:  Enforces the Conventional Commits specification for titles and commit messages.
Severity: warning (default)
Tags:     commits, semantic-versioning, title

Rules configured:
  commit-messages  pattern: "^(feat|fix|chore|docs|test|...)...", maxSubjectLength: 72
  draft-prevention blockDraft: fail
  size-limits      maxLines: 800, maxCommits: 25
  title-format     pattern: "^(feat|fix|chore|docs|test|...)...", minLength: 10, ...

To use: add 'profile: conventional-commits' to your .karandash/config.yaml
Or run: karandash validate --profile conventional-commits <url>
```

### Examples

```bash
karandash profile show minimal
karandash profile show conventional-commits
karandash profile show security-conscious
```

### Error Handling

If the profile ID is not found, the command prints an error to stderr and exits with code 2:

```
Error: Unknown profile 'unknown-profile'. Run 'karandash profile list' to see available profiles.
```

---

## karandash profile export

Exports a profile as a YAML config file. The exported file can be used as-is or customised further.

### Synopsis

```
karandash profile export <profile-id> [--output FILE]
```

### Arguments

| Argument | Description |
|----------|-------------|
| `profile-id` | The profile identifier to export |

### Options

| Option | Short | Type | Description |
|--------|-------|------|-------------|
| `--output` | `-o` | string | Output file path. When omitted, the YAML is printed to stdout |

### Output (stdout)

```yaml
# Generated from profile: conventional-commits
# Enforces the Conventional Commits specification for titles and commit messages.

profile: conventional-commits

rules:
  commitMessages:
    maxLength: 72
    pattern: "^(feat|fix|chore|docs|test|refactor|style|perf|build|ci|revert)(\\(.+\\))?!?:.*"
  draftPrevention:
    blockDraft: fail
    wipPatterns: ["\\bWIP\\b","\\[WIP\\]","\\bwip:","\\bWIP:"]
  sizeLimits:
    maxLines: 800
    maxCommits: 25
    excludeRenames: false
    excludeDeleted: false
    severities: {"onViolation":"error"}
  titleFormat:
    pattern: "^(feat|fix|chore|docs|test|refactor|style|perf|build|ci|revert)(\\(.+\\))?!?:.*"
    minLength: 10
    maxLength: 72
    requireTicket: false
```

### Examples

```bash
# Print the conventional-commits profile to stdout
karandash profile export conventional-commits

# Export to a file
karandash profile export minimal --output .karandash/config.yaml

# Export and customise
karandash profile export github-flow --output .karandash/config.yaml
# Edit .karandash/config.yaml to add project-specific overrides
```

---

## Built-in Profiles

| Profile ID | Summary | Key Rules |
|------------|---------|-----------|
| `minimal` | Sensible non-blocking defaults | title-format, draft-prevention, size-limits, security |
| `conventional-commits` | Conventional Commits specification | title-format, commit-messages, size-limits, draft-prevention |
| `github-flow` | GitHub Flow branching conventions | title-format, branch-naming, size-limits, draft-prevention |
| `gitflow` | Traditional GitFlow workflow | title-format, branch-naming, size-limits, draft-prevention |
| `trunk-based` | Trunk-based development - small fast PRs | title-format, size-limits (strict), draft-prevention |
| `semantic-versioning` | Semver-compatible commit type prefixes | title-format, commit-messages, size-limits |
| `security-conscious` | Security-first defaults | security, file-changes, size-limits, title-format |
| `integration-mr` | Integration MRs: commit rules report warnings (opt-in) | none (policy: commit-messages, commit-body, context-tag to warning) |

For full details on each profile see [Profiles](../../features/profiles.md).

## Using a Profile in Config

```yaml
# .karandash/config.yaml
profile: conventional-commits
```

Profile rule values can be overridden in the same config file:

```yaml
profile: conventional-commits

rules:
  size-limits:
    maxLines: 1000   # override the profile default of 800
```

## Using a Profile via CLI Flag

```bash
karandash validate --profile minimal https://gitlab.example.com/group/project/-/merge_requests/42
```

## Related

- [Profiles Feature](../../features/profiles.md) - How profiles work and how to choose one
- [`validate`](validate.md) - Run validation with `--profile` flag
- [`explain`](explain.md) - Explain a config that uses a profile
