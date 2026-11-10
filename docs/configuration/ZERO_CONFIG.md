# Zero-Config Mode

Zero-config mode enables you to start validating merge requests in under 5 minutes without creating any configuration files. Karandash will use sensible default rules that catch common issues while you decide on your team's standards.

## What is Zero-Config Mode?

Zero-config mode activates automatically when you run `karandash validate` in a repository without a `.karandash/config.kts` or `.karandash/config.yaml` file. Instead of failing with "config not found", Karandash applies a curated set of default validation rules.

## Quick Start

```bash
# 1. Set authentication (one-time setup)
export GITLAB_TOKEN="your-token-here"

# 2. Run validation - that's it!
karandash validate
```

No config file needed. No initialization step. Just validate.

## Default Rules

Zero-config mode applies these rules with the severities indicated:

| Rule | Default Setting | Severity | Description |
|------|----------------|----------|-------------|
| **Title Format** | Conventional commits pattern | ERROR | Requires `feat:`, `fix:`, `chore:`, etc. prefix |
| **Title Length** | Min: 10 chars (no max) | ERROR | Minimum length enforced; maximum not enforced |
| **Size Limits** | Max 500 lines changed | ERROR | Flags large PRs (blocks merge) |
| **Draft Block** | Blocks draft MRs | ERROR | Prevents accidental merge of WIP changes |
| **Max Commits** | 20 commits per MR | ERROR | Enforces commit count limit (blocks merge) |

**Note on description validation:** zero-config does not check the MR description. Description checks come from the opt-in [`descriptionFormat`](../rules/description-format.md) rule (required sections), which needs a config file.

### Default Title Pattern

```regex
^(feat|fix|chore|docs|test|refactor|style|perf):.*
```

Accepts conventional commit formats:
- `feat: add user authentication`
- `fix: handle null response`
- `chore: update dependencies`

## Environment Variable Authentication

Zero-config mode reads authentication tokens from environment variables. No config file required.

### GitLab

```bash
export GITLAB_TOKEN="glpat-xxxxxxxxxxxxxxxxxxxx"
export GITLAB_URL="https://gitlab.com"  # Optional, defaults to gitlab.com
```

**Token Permissions:** `api` or `read_api` scope

**CI/CD Auto-Detection:** In GitLab CI, uses `CI_JOB_TOKEN` automatically if `GITLAB_TOKEN` is not set.

### GitHub

```bash
export GITHUB_TOKEN="ghp_xxxxxxxxxxxxxxxxxxxx"
export GITHUB_API_URL="https://api.github.com"  # Optional, for GHE
```

**Token Permissions:** `repo` scope (or `public_repo` for public repos only)

**GitHub Actions:** Uses `GITHUB_TOKEN` from workflow context automatically.

### Bitbucket

```bash
export BITBUCKET_TOKEN="your-app-password"
export BITBUCKET_USERNAME="your-username"
```

**Token Type:** App password with `pullrequest:read` permission

### Azure DevOps

```bash
export AZURE_DEVOPS_TOKEN="your-pat-token"
export AZURE_DEVOPS_ORG="your-organization"
```

**Token Permissions:** Code (Read) scope

### Gerrit

```bash
export GERRIT_USERNAME="your-gerrit-username"
export GERRIT_TOKEN="your-http-password"
export GERRIT_URL="https://gerrit.example.com"
```

**Token Type:** HTTP password from user settings

### Gitea

```bash
export GITEA_TOKEN="your-access-token"
export GITEA_URL="https://gitea.example.com"
```

**Token Permissions:** `read:repository` scope

### AWS CodeCommit

```bash
export AWS_ACCESS_KEY_ID="AKIAIOSFODNN7EXAMPLE"
export AWS_SECRET_ACCESS_KEY="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
export AWS_REGION="us-west-2"
```

**IAM Permissions:** `codecommit:GetPullRequest` and related read permissions

## Example Output

```
[INFO] No configuration file found
[INFO] Using zero-config mode with default rules
[INFO] Set GITLAB_TOKEN to authenticate with GitLab

Validating MR #123: feat: add user authentication

✓ Title format matches conventional commits
✓ Title length: 32 characters (min 10)
✗ Size: 650 lines changed (max: 500)
✓ Not a draft MR
✓ Commits: 5 (max: 20)

✗ 1 error found

Validation FAILED (errors block merge)
```

**Note:** The size limit and draft blocking rules enforce strict limits with ERROR severity. This differs from the previous behavior which used WARNING severity. Adjust defaults via custom configuration if needed.

## When to Use Zero-Config vs Custom Config

### Use Zero-Config When:

- **Testing Karandash** - Trying it out before committing to configuration
- **Starting a new project** - Don't have team standards defined yet
- **Small teams** - Default rules are good enough
- **Quick validation** - Need to validate a PR URL locally without setup
- **CI experimentation** - Testing Karandash in CI without config overhead

### Use Custom Config When:

- **Established standards** - Your team has specific conventions
- **Stricter enforcement** - Need ERROR severity on size limits
- **Custom patterns** - Non-conventional commit formats
- **Advanced rules** - Need JIRA ticket validation, CODEOWNERS, etc.
- **Multiple repos** - Want to share config via `extends` inheritance
- **Platform-specific** - Different rules for GitHub vs GitLab

## Upgrading from Zero-Config

When you're ready to customize, generate a config file with `karandash init`:

```bash
# Generate YAML config (the default format)
karandash init

# Or pick a different format: yaml, json, kts
karandash init --format kts
karandash init --format json

# Or start from a named profile
karandash init --profile conventional-commits
karandash init --profile github-flow

# Write to a specific path
karandash init --output .karandash/config.yaml
```

| Option | Alias | Values | Default |
|--------|-------|--------|---------|
| `--format` | `-f` | `yaml`, `json`, `kts` | `yaml` |
| `--profile` | -- | `minimal`, `conventional-commits`, `github-flow`, `gitflow`, `trunk-based`, `semantic-versioning`, `security-conscious` | none (a minimal config is generated) |
| `--output` | `-o` | any path | the default path for the chosen format |

Without `--profile`, `init` writes the same minimal ruleset that zero-config
applies, so the generated file is a direct starting point for customization.
`init` never overwrites an existing config file - it reports the existing path
and exits.

## Zero-Config with Local PR Testing

Combine zero-config with the `--pr` flag for instant validation:

```bash
export GITLAB_TOKEN="glpat-xxxxxxxxxxxxxxxxxxxx"

# No config file needed
karandash validate --pr https://gitlab.com/myorg/myrepo/-/merge_requests/123
```

Perfect for:
- Testing PRs from external repositories
- Validating before creating your own PR
- Quick checks without cloning the repository

## Zero-Config in CI/CD

### GitLab CI

```yaml
karandash-validate:
  stage: test
  image: rinekri/karandash:latest
  rules:
    - if: $CI_MERGE_REQUEST_IID
  script:
    - karandash validate
  variables:
    GITLAB_TOKEN: $CI_JOB_TOKEN  # Auto-provided by GitLab
```

### GitHub Actions

```yaml
name: Karandash Validation
on: pull_request

jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Validate PR
        uses: docker://rinekri/karandash:latest
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        with:
          args: validate
```

Both examples work without a config file in your repository.

## Overriding Defaults

Create a config file to override any default rule:

**Kotlin DSL (.karandash/config.kts):**

```kotlin
rules {
    titleFormat {
        // Override only the pattern, keep other defaults
        pattern = "^\\[JIRA-\\d+\\] .+"
    }
}
```

**YAML (.karandash/config.yaml):**

```yaml
rules:
  title-format:
    # Override only the pattern, keep other defaults
    pattern: "^\\[JIRA-\\d+\\] .+"
```

Even with one rule override, you still benefit from other zero-config defaults.

## Limitations

Zero-config mode does not support:

- **Customizing severity levels** - Rules use fixed severities (most are ERROR, blocking merges)
- **Description validation** - Not part of the zero-config defaults; configure [`descriptionFormat`](../rules/description-format.md) in a config file
- **Advanced validators** - JIRA ticket validation, CODEOWNERS, file-specific rules
- **Custom reporters** - Only console output (no Slack, SARIF, etc.)
- **Plugin loading** - Custom plugins require explicit configuration
- **Config inheritance** - Cannot extend remote configs without a base config file

For these features, you need a configuration file (Kotlin DSL or YAML).

### Severity Behavior in Zero-Config

Most zero-config rules use **ERROR severity**, which blocks merges:
- Title format violations
- Title minimum length violations
- Size limit violations (maxLines, maxCommits)
- Draft detection

To use non-blocking (WARNING) rules, create a custom configuration with `.karandash/config.kts` or `.karandash/config.yaml`.

## FAQ

**Q: How do I know if zero-config mode is active?**

A: Karandash prints `[INFO] Using zero-config mode with default rules` when no config file is found.

**Q: Can I customize zero-config defaults?**

A: No. Zero-config provides fixed defaults. To customize, create a config file with `karandash init`.

**Q: Do zero-config rules change between versions?**

A: Defaults may improve in major versions. Pin your Docker image tag (`rinekri/karandash:v1.0.0`) for stability.

**Q: Can I use zero-config with custom plugins?**

A: No. Plugins require a config file to specify which plugins to load and their settings.

**Q: Does zero-config work in CI without environment variables?**

A: In GitLab CI, `CI_JOB_TOKEN` is auto-detected. In GitHub Actions, `GITHUB_TOKEN` must be passed explicitly.

**Q: Why do zero-config rules block my PRs with ERROR severity?**

A: Zero-config enforces strict rules (ERROR severity) to keep quality up. If you need more lenient rules (WARNING severity that don't block merges), create a custom configuration file with `karandash init`.

**Q: Does zero-config check the MR description?**

A: No. Add a config file with the [`descriptionFormat`](../rules/description-format.md) rule to require description sections.

## Related Documentation

- [YAML Configuration Guide](YAML_CONFIG.md) - Custom config in YAML format
- [Kotlin DSL Reference](../getting-started/quick-start.md) - Custom config in Kotlin DSL
- [Local PR Testing](../reference/cli/validate.md) - Using `--pr` flag for ad-hoc validation
- [CI/CD Integration](../integrations/ci-cd-integration.md) - Setting up in GitHub Actions, GitLab CI, etc.
