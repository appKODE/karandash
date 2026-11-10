# Configuration Profiles

Profiles are pre-configured rule sets for common Git workflows. Instead of configuring every rule from scratch, you pick a profile that matches your team's workflow and Karandash applies a sensible baseline automatically. You can then override individual rule values in your own config.

## Using a Profile

Add a single `profile:` field to your config:

```yaml
profile: conventional-commits
```

That is the minimum required - no `rules:` block needed. The profile sets all rule defaults.

You can override profile defaults by adding a `rules:` block:

```yaml
profile: conventional-commits

rules:
  size-limits:
    maxLines: 1000   # profile default is 800
  title-format:
    minLength: 5     # profile default is 10
```

Rules configured in your file take precedence over profile defaults.

## Applying a Profile via CLI

```bash
karandash validate --profile minimal https://gitlab.example.com/group/repo/-/merge_requests/42
```

The `--profile` flag overrides the `profile:` field in the config file.

## Available Profiles

### minimal

**Profile ID:** `minimal`
**Tags:** `title`, `draft-prevention`, `size`

Sensible non-blocking defaults for teams that want basic guardrails without strict conventions. Checks the title format, flags WIP titles, and sets generous size limits. Every rule runs at `warning` severity, so a fresh install never fails an MR; a rule's own `severity:` or the profile ref's severity override still wins.

| Rule | Config |
|------|--------|
| `title-format` | Pattern: `^(feat\|fix\|perf\|refactor\|style\|test\|docs\|build\|ci\|chore\|revert)(\(.+\))?!?:.*`, minLength: 10 |
| `draft-prevention` | blockDraft: off, WIP patterns: `WIP`, `[WIP]`, `wip:`, `WIP:` |
| `size-limits` | maxLines: 500, maxCommits: 20 |
| `file-changes` | blockedExtensions: (none) |
| `security` | enabled: true |
| `labels` | requiredLabels: (none) |

**When to use:** Teams starting out with MR validation who want a gentle introduction without blocking CI.

```yaml
profile: minimal
```

---

### conventional-commits

**Profile ID:** `conventional-commits`
**Tags:** `title`, `commits`, `semantic-versioning`

Enforces the [Conventional Commits](https://www.conventionalcommits.org/) specification on both the MR title and individual commit messages. Commit type prefixes (`feat`, `fix`, `chore`, etc.) are required.

| Rule | Config |
|------|--------|
| `title-format` | Pattern: `^(feat\|fix\|chore\|docs\|test\|refactor\|style\|perf\|build\|ci\|revert)(\(.+\))?!?:.*`, minLength: 10, maxLength: 72 |
| `commit-messages` | Same pattern as title-format, maxSubjectLength: 72 |
| `size-limits` | maxLines: 800, maxCommits: 25 |
| `draft-prevention` | blockDraft: fail |

**When to use:** Teams that follow Conventional Commits for changelog generation or automated semantic versioning.

```yaml
profile: conventional-commits
```

---

### github-flow

**Profile ID:** `github-flow`
**Tags:** `branching`, `title`, `size`

Enforces [GitHub Flow](https://docs.github.com/en/get-started/using-github/github-flow) branching conventions. Feature branches must be prefixed with `feature/`, hotfixes with `hotfix/`, etc.

| Rule | Config |
|------|--------|
| `title-format` | minLength: 10, maxLength: 100 |
| `branch-naming` | Pattern: `^(main\|master\|feature/.+\|hotfix/.+\|bugfix/.+\|release/.+)$` |
| `size-limits` | maxLines: 500, maxCommits: 20 |
| `draft-prevention` | blockDraft: fail |

Allowed branch prefixes: `main`, `master`, `feature/`, `hotfix/`, `bugfix/`, `release/`.

**When to use:** Teams using GitHub Flow with short-lived feature branches merged directly to main.

```yaml
profile: github-flow
```

---

### gitflow

**Profile ID:** `gitflow`
**Tags:** `branching`, `title`

Enforces [GitFlow](https://nvie.com/posts/a-successful-git-branching-model/) branching conventions with `develop`, `release`, `hotfix`, and `support` branches.

| Rule | Config |
|------|--------|
| `title-format` | minLength: 10, maxLength: 100 |
| `branch-naming` | Pattern: `^(main\|master\|develop\|feature/.+\|release/.+\|hotfix/.+\|support/.+)$` |
| `size-limits` | maxLines: 600, maxCommits: 30 |
| `draft-prevention` | blockDraft: fail |

The branch pattern is checked against the source and the target branch (`requireTargetMatch`).

Allowed branch prefixes: `main`, `master`, `develop`, `feature/`, `release/`, `hotfix/`, `support/`.

**When to use:** Teams following traditional GitFlow with a long-lived `develop` branch and scheduled releases.

```yaml
profile: gitflow
```

---

### trunk-based

**Profile ID:** `trunk-based`
**Tags:** `size`, `branching`, `draft-prevention`

Enforces trunk-based development discipline: small, frequent PRs that merge quickly to `main`. Applies the strictest size limits of any built-in profile and blocks a broad set of WIP markers.

| Rule | Config |
|------|--------|
| `title-format` | minLength: 5, maxLength: 72 |
| `size-limits` | maxLines: **200**, maxCommits: **5** |
| `draft-prevention` | blockDraft: fail, wipPatterns: `WIP`, `[WIP]`, `wip:`, `WIP:`, `DO NOT MERGE` |
| `branch-naming` | Pattern: `^(main\|master\|trunk\|[a-z0-9][a-z0-9._/-]{0,49})$` (short, lowercase, URL-safe names)  |

**When to use:** Teams practicing trunk-based development with continuous integration, where PRs should be small and short-lived.

```yaml
profile: trunk-based
```

---

### semantic-versioning

**Profile ID:** `semantic-versioning`
**Tags:** `commits`, `title`, `semantic-versioning`

Ensures that commit type prefixes are compatible with semantic versioning tooling. The pattern is broader than the `conventional-commits` profile - `!` is optional, but a commit that uses it must carry a `BREAKING CHANGE:` line in its body. Bump level: `feat` is MINOR, `fix` and `perf` are PATCH, `!` is MAJOR.

| Rule | Config |
|------|--------|
| `title-format` | Pattern: `^(feat\|fix\|perf\|refactor\|style\|test\|docs\|build\|ci\|chore\|revert)(\(.+\))?!?:.*`, minLength: 10, maxLength: 72 |
| `commit-messages` | Same pattern, maxSubjectLength: 72 |
| `commit-body` | A commit whose subject has `!` before the colon needs a body line starting `BREAKING CHANGE:` |
| `size-limits` | maxLines: 800, maxCommits: 30 |

**When to use:** Teams that use automated tools (e.g. `semantic-release`, `release-please`) to determine the next version from commit messages.

```yaml
profile: semantic-versioning
```

---

### security-conscious

**Profile ID:** `security-conscious`
**Tags:** `security`, `size`, `file-changes`

Security-first defaults. Enables secret scanning and vulnerable pattern detection, and blocks changes to sensitive file paths (`.env`, `*.pem`, `*.key`, credential files) until the MR has the `security-reviewed` label and an approval from someone other than its author.

| Rule | Config |
|------|--------|
| `security` | enabled: true, scanSecrets: true, scanVulnerablePatterns: true |
| `file-changes` | blockedPatterns: `**/.env`, `**/*.pem`, `**/*.key`, `**/*.p12`, `**/secrets/**`, `**/.aws/credentials` |
| `size-limits` | maxLines: 400, maxCommits: 15 |
| `title-format` | minLength: 10 |

**When to use:** Security-sensitive repositories or regulated environments where credential leaks and sensitive file changes must be caught before merge.

```yaml
profile: security-conscious
```

---

### integration-mr

**Profile ID:** `integration-mr`
**Tags:** `branching`, `commits`

Opt-in preset for integration merge requests, which carry commits written by other people: a `feature/` or `release/` branch merged into `develop`, `main` or `master`, or any merge request with more than 30 commits. On those merge requests one policy lowers `commit-messages`, `commit-body` and `context-tag` to `warning`; every other merge request and every other rule keeps the configured severity.

The profile contributes no rule configs (it only changes the severity of rules your config already enables) and adds its policy before your own `policies:`, so a policy of yours that matches the same merge request merges over it. The commit count comes from the fetched commits, so on platforms that cap the commit list the threshold sees at most that cap.

**When to use:** Repositories where `develop` or `main` receives merges of long-lived branches whose commit history the author of the merge request does not own.

```yaml
profile: integration-mr
```

The branches and the threshold are fixed. To change them, write the policy yourself instead of using the profile:

```yaml
policies:
  - name: integration-mr
    when: '(source_branch_matches("^(feature|release)/") && target_branch_matches("^(develop|main|master)$")) || commit_count > 30'
    modules: [commit-messages, commit-body, context-tag]
    severity: warning
```

---

## Profile Comparison

| Profile | maxLines | maxCommits | Branch rules | Commit pattern | Security scan |
|---------|----------|------------|--------------|----------------|---------------|
| `minimal` | 500 | 20 | No | No | Yes (basic) |
| `conventional-commits` | 800 | 25 | No | Yes (CC) | No |
| `github-flow` | 500 | 20 | Yes | No | No |
| `gitflow` | 600 | 30 | Yes | No | No |
| `trunk-based` | **200** | **5** | Loose | No | No |
| `semantic-versioning` | 800 | 30 | No | Yes (semver) | No |
| `security-conscious` | 400 | 15 | No | No | **Yes (full)** |

## Exploring Profiles from the CLI

```bash
# List all profiles
karandash profile list

# Filter by tag
karandash profile list --tag branching

# Show a profile's full rule configuration
karandash profile show conventional-commits

# Export a profile as a ready-to-use config file
karandash profile export minimal --output .karandash/config.yaml
```

## Combining Profiles with Config Inheritance

Profiles can be combined with `extends:` to build a layered configuration:

```yaml
# .karandash/config.yaml - team-specific config
extends: https://configs.example.com/company-base.yaml

profile: conventional-commits

rules:
  size-limits:
    maxLines: 1000   # override the profile default
```

The precedence order (highest to lowest) is:

1. Your local `rules:` block
2. Profile defaults
3. Inherited config from `extends:`
4. Zero-config defaults

## Related

- [`profile` CLI command](../reference/cli/profile.md) - List, show, and export profiles from the terminal
- [Config Inheritance](config-inheritance.md) - Using `extends:` with profiles
- [Configuration Schema](../reference/configuration-schema.md) - Full config reference
