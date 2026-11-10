# Karandash

[![Platforms](https://img.shields.io/badge/platforms-8-orange)](docs/platforms/)

Merge request checks for GitLab, GitHub, Bitbucket and more.

## What is Karandash?

Karandash checks merge requests and pull requests against your rules before they get merged. You write the rules once in YAML or the Kotlin DSL and run them in CI or locally, so problems show up before review starts.

- Works without a config file: run it on a PR and get the default checks.
- 21 built-in validators: title format, commit messages, JIRA tickets, AI review, secret scanning and more.
- 8 platforms: GitLab, GitHub, Bitbucket, Azure DevOps, Gerrit, Gitea, AWS CodeCommit, Jenkins.
- CI templates for GitHub Actions and GitLab CI, plus Docker images.
- Plugins in Kotlin, JavaScript, WASM or native code.

It is for teams that want the same MR/PR checks across all their repositories.

## Feature Highlights

### Built-in Validators

| Validator | Description |
|-----------|-------------|
| [Title Format](docs/rules/title-format.md) | Enforce title patterns and length limits |
| [Commit Messages](docs/rules/commit-messages.md) | Validate commit message format |
| [JIRA Ticket](docs/rules/jira-ticket.md) | Require JIRA ticket references |
| [Branch Naming](docs/rules/branch-naming.md) | Enforce branch naming conventions |
| [File Changes](docs/rules/file-changes.md) | Rules based on modified files |
| [CODEOWNERS](docs/rules/codeowners.md) | Enforce code owner approvals |
| [Approvals](docs/rules/approvals.md) | Minimum approval requirements |
| [Reviewers](docs/rules/reviewers.md) | Required reviewers or teams |
| [CI/CD Status](docs/rules/cicd-status.md) | Block merge until pipelines pass |
| [Merge Conflicts](docs/rules/conflict-detection.md) | Block MRs with conflicts |
| [Draft Detection](docs/rules/draft-detection.md) | Prevent merging draft PRs |
| [Labels](docs/rules/labels.md) | Label requirements and constraints |
| [Size Limits](docs/rules/size-limits.md) | Prevent overly large MRs |
| [Security](docs/rules/security.md) | Scan for secrets and sensitive files |
| [Conditional Rules](docs/rules/conditional.md) | Apply rules based on conditions |
| [AI Rules](docs/rules/ai-rules.md) | AI review via Anthropic, OpenAI, Ollama |
| [JIRA Enrichment](docs/rules/jira-validation.md) | Fetch JIRA ticket data for rules and AI review |
| [Description Format](docs/rules/description-format.md) | MR description format validation |
| [Cross-Entity Consistency](docs/rules/cross-entity-consistency.md) | Cross-entity consistency checks |
| [Context Tags](docs/rules/context-tags.md) | Context tag validation |
| [Language Policy](docs/rules/language-policy.md) | Language policy enforcement |

See the [full rules reference](docs/rules/index.md) for configuration details.

### Platforms

GitLab | GitHub | Bitbucket | Azure DevOps | Gerrit | Gitea | AWS CodeCommit | Jenkins (provider only - no mutator or comment-poster)

Each platform has a dedicated guide: [Platform documentation](docs/platforms/)

### Output Formats

Console | JSON | SARIF 2.1.0 | Reviewdog rdjsonl/rdjson | Comment poster | Email (SMTP) | Slack (Block Kit) | Webhook (HTTP POST)

## Quick Start

### Install

Releases are published to [GitHub Releases](https://github.com/appKODE/karandash/releases). Download the fat JAR (Java 17+) and check it against `SHA256SUMS`:

```bash
BASE=https://github.com/appKODE/karandash/releases/download/v1.0.0
curl -fsSLO "$BASE/karandash.jar" -fsSLO "$BASE/SHA256SUMS"
grep ' karandash.jar$' SHA256SUMS | sha256sum -c -
java -jar karandash.jar --version
```

Native binaries (`karandash-linux-x64`, `karandash-macos-arm64`, `karandash-macos-x64`, `karandash-windows-x64.exe`) are attached to the same release; swap the file name.

**Docker:** `rinekri/karandash:v1.0.0` (native binary) and `rinekri/karandash:v1.0.0-jvm` on Docker Hub; `latest` and `latest-jvm` track the newest release.

See the [installation guide](docs/getting-started/installation.md) for all options, or the [documentation site](https://appkode.github.io/karandash).

### First Validation

No config file needed. Set your platform token and validate:

```bash
export GITLAB_TOKEN="your-token-here"
karandash validate
```

Or validate any PR/MR by URL:

```bash
karandash validate --pr https://gitlab.com/myorg/myrepo/-/merge_requests/123
```

This uses zero-config mode with sensible defaults. See the [zero-config guide](docs/configuration/ZERO_CONFIG.md) for details.

### Interactive Setup

For guided configuration with auto-detection:

```bash
karandash wizard
```

The wizard detects your platform, helps choose a template (minimal, standard, strict), and optionally generates CI/CD workflow files. See the [wizard documentation](docs/reference/cli/wizard.md).

Other useful commands:

```bash
karandash init       # scaffold a config file in the current repository
karandash doctor     # diagnose configuration, connectivity, and token issues
karandash generate   # generate CI/CD workflow files for your platform
```

## Configuration

Karandash supports YAML and Kotlin DSL configuration. Create `.karandash/config.yaml` in your repository root:

**YAML:**
```yaml
rules:
  title-format:
    pattern: "^(feat|fix|chore|docs):.*"
    max-length: 120
  size-limits:
    max-lines: 500
  approval:
    min-approvals: 2
  draft-prevention:
    block-draft: fail
reporters:
  console: true
  markdown: true
```

**Kotlin DSL** (`.karandash/config.kts`):
```kotlin
rules {
    titleFormat {
        pattern = "^(feat|fix|chore|docs):.*"
        maxLength = 120
    }
    sizeLimits { maxLines = 500 }
    approval { minApprovals = 2 }
    draftPrevention { blockDraft = "fail" }
}
reporters {
    console = true
    markdown = true
}
```

Both formats support config inheritance with `extends` and environment variable interpolation with `${VAR}`.

- [YAML configuration reference](docs/configuration/YAML_CONFIG.md)
- [Config inheritance guide](docs/features/config-inheritance.md)
- [Example configurations](tools/examples/)

## Documentation

Full documentation is available at [appkode.github.io/karandash](https://appkode.github.io/karandash) and in the [docs/](docs/index.md) directory.

| Section | Description |
|---------|-------------|
| [Getting Started](docs/getting-started/quick-start.md) | Installation, first validation, configuration basics |
| [Rules Reference](docs/rules/index.md) | All 21 validators with configuration options |
| [Platform Guides](docs/platforms/) | Setup guides for each supported platform |
| [Plugin Development](docs/plugins/overview.md) | Build custom validators in Kotlin, JS, or native |
| [CI/CD Integration](docs/integrations/ci-cd-integration.md) | GitHub Actions, GitLab CI, Jenkins, and more |
| [Reviewdog Integration](docs/integrations/reviewdog.md) | Inline PR annotations with reviewdog |
| [Architecture](docs/advanced/ARCHITECTURE_MULTIPLATFORM.md) | Multiplatform architecture deep dive |
| [Troubleshooting](docs/guides/troubleshooting.md) | Common issues and solutions |
| [CLI Reference](docs/reference/cli/) | All commands and flags |
| [Contributing](CONTRIBUTING.md) | Contributor guide and roadmap |

## CI/CD Integration

**GitHub Actions**: add to `.github/workflows/karandash.yml`:

```yaml
name: Karandash
on: pull_request
jobs:
  validate:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      pull-requests: write
    steps:
      - uses: actions/checkout@v4
      - uses: appKODE/karandash@v1.0.0
        with:
          version: v1.0.0
```

**GitLab CI**: add to `.gitlab-ci.yml`:

```yaml
karandash:
  stage: test
  image: rinekri/karandash:v1.0.0
  rules:
    - if: $CI_MERGE_REQUEST_IID
  script:
    - karandash validate
```

Both work without config files using zero-config defaults. See [CI/CD templates](tools/templates/) for Bitbucket Pipelines, Jenkins, Azure Pipelines, and CircleCI.

## Building

Karandash supports JVM and native targets for macOS, Linux, and Windows. By default, builds compile for your current platform only.

**Quick start:**
```bash
./gradlew build
```

This builds the JVM target and native target(s) for your host OS automatically. For detailed build instructions, platform requirements, and CI/CD setup, see [docs/advanced/build-from-source.md](docs/advanced/build-from-source.md).

## Contributing

Contributions are welcome. See the [contributor guide](CONTRIBUTING.md) for details.

```bash
# Build from source
./gradlew build

# Run tests
./gradlew jvmTest

# Build distributable JAR
./gradlew :cli:shadowJar
```

Areas for contribution: new validators, platform providers, output reporters, plugins, documentation.

## License

MIT License

---

[Documentation](docs/index.md) | [Changelog](CHANGELOG.md) | [Examples](tools/examples/) | [CI/CD Templates](tools/templates/)
