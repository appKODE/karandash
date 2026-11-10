# Platform Guides

Karandash supports 8 Git platforms for merge request and pull request validation.
This section contains detailed setup and usage guides for each platform.

## Platform Comparison

| Feature | GitHub | GitLab | Bitbucket | Azure DevOps | Gerrit | Gitea | AWS CodeCommit |
|---------|--------|--------|-----------|--------------|--------|-------|----------------|
| **Support Level** | Full | Full | Full | Full | Full | Full | Full |
| **PR/MR Fetching** | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| **Comment Posting** | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| **Auto-Fix (--fix)** | Yes | Yes | Partial* | Yes | Yes | Yes | Partial* |
| **CI Auto-Detection** | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| **SARIF Upload** | Yes | Yes | No | No | No | No | No |
| **Self-Hosted** | GHE | Yes | Server | Yes | Yes | Yes | N/A |
| **Zero-Config** | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| **URL Validation** | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| **Merge / Close** ([`karandash mr`](../reference/cli/mr.md), `autoApprove.merge`) | Yes | Yes | Yes | Yes | Yes | Yes | Yes |
| **Reopen** | Yes | Yes | No | Yes | Yes | Yes | Yes |
| **Credential check** (`doctor`) | Yes | Yes | Yes | Yes | Yes | Yes | Yes** |

\* Partial: comment posting and draft/title/description mutations work; label management not supported by platform API.

\** AWS CodeCommit checks credentials only when it finds a key pair (config, `AWS_*` env or `~/.aws` profile); Azure DevOps needs `organization`.

### Support Levels

- **Full**: PR/MR fetching, comment posting, auto-fix mutations (set draft, update title/description, approve/unapprove), CI auto-detection, zero-config mode, URL-based validation. Label management varies by platform.

## Quick Selection Guide

**Choose your platform:**

| Scenario | Recommended Platform Guide |
|----------|---------------------------|
| GitHub.com or GitHub Enterprise | [GitHub](github.md) |
| GitLab.com or self-hosted GitLab | [GitLab](gitlab.md) |
| Bitbucket Cloud or Bitbucket Server | [Bitbucket](bitbucket.md) |
| Azure DevOps Services or Server | [Azure DevOps](azure-devops.md) |
| Gerrit Code Review | [Gerrit](gerrit.md) |
| Gitea or Forgejo | [Gitea](gitea.md) |
| AWS CodeCommit | [AWS CodeCommit](aws-codecommit.md) |
| Jenkins | [Jenkins](jenkins.md) |
| No platform - validate the local git working copy offline | [Local Git](local-git.md) |

## Authentication at a Glance

Every platform supports two authentication methods:

1. **Environment variables** (recommended for CI/CD and zero-config mode)
2. **Configuration file** (`.karandash/config.yaml` auth section)

| Platform | Environment Variables | Config Key |
|----------|----------------------|------------|
| GitHub | `GITHUB_TOKEN`, `GITHUB_API_URL` | `auth.github` |
| GitLab | `GITLAB_TOKEN`, `CI_JOB_TOKEN`, `CI_SERVER_URL` | `auth.gitlab` |
| Bitbucket | `BITBUCKET_TOKEN` | `auth.bitbucket` |
| Azure DevOps | `AZURE_DEVOPS_TOKEN`, `SYSTEM_ACCESSTOKEN` | `auth.azure-devops` |
| Gerrit | `GERRIT_URL`, `GERRIT_TOKEN` | `auth.gerrit` |
| Gitea | `GITEA_URL`, `GITEA_TOKEN` | `auth.gitea` |
| AWS CodeCommit | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` | `auth.aws-codecommit` |

## PR/MR URL Formats

Karandash parses PR/MR URLs automatically with `--pr`:

```bash
# GitHub
karandash validate --pr https://github.com/owner/repo/pull/123

# GitLab
karandash validate --pr https://gitlab.com/group/project/-/merge_requests/123

# Bitbucket
karandash validate --pr https://bitbucket.org/workspace/repo/pull-requests/123

# Azure DevOps
karandash validate --pr https://dev.azure.com/org/project/_git/repo/pullrequest/123

# Gerrit
karandash validate --pr https://gerrit.example.com/c/project/+/123

# Gitea
karandash validate --pr https://gitea.example.com/owner/repo/pulls/123

# AWS CodeCommit
karandash validate --pr https://console.aws.amazon.com/codesuite/codecommit/repositories/my-repo/pull-requests/123
```

## Output Formats

All platforms support the same output formats:

- **console** (default) -- human-readable with color and status icons
- **json** -- machine-readable for programmatic processing
- **sarif** -- SARIF 2.1.0 for code scanning integration (GitHub and GitLab)

```bash
karandash validate --format json
karandash validate --format sarif --output results.sarif
```

## Explicit Platform Override

Karandash auto-detects your platform from CI environment variables or git remote URLs.
To override detection, use `KARANDASH_PLATFORM`:

```bash
export KARANDASH_PLATFORM=github
export KARANDASH_PROJECT_ID=owner/repo
export KARANDASH_MR_ID=123
export KARANDASH_TOKEN=your-token
karandash validate
```

## Next Steps

Choose your platform guide from the list above to get started with detailed
authentication setup, configuration examples, and CI/CD integration templates.
