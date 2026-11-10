# Jenkins Platform Guide

## Overview

Karandash provides **CI detection support** for Jenkins pipelines.

**Support level:** CI detection only

**Capabilities:**
- Jenkins CI environment auto-detection
- `CHANGE_URL` parsing to identify the underlying platform (GitLab, GitHub, Bitbucket, Gerrit, Azure DevOps, Gitea, AWS CodeCommit)
- Automatic delegation to the appropriate platform provider for MR data fetching

**What Jenkins does NOT do:**
- Jenkins does not fetch merge request data directly
- Jenkins does not post comments or apply mutations - those are handled by the underlying platform provider

**API used:** None (environment variable detection only)

---

## How It Works

Jenkins pipelines expose a `CHANGE_URL` environment variable for multibranch pipeline builds triggered by a pull/merge request. Karandash reads this URL, parses it to determine the underlying platform, and delegates all MR fetching to the matching provider (e.g., the GitLab or GitHub provider).

```
JENKINS_URL + CHANGE_URL
       ↓
JenkinsProvider.detectCiEnvironment()
       ↓
Parse CHANGE_URL → platform + projectId + mrId
       ↓
Delegate to GitLab / GitHub / Bitbucket / ... provider
```

---

## Authentication Setup

Jenkins itself requires no token. You still need to configure credentials for the underlying platform:

**For GitLab MRs in Jenkins:**

```bash
export GITLAB_TOKEN="glpat-xxxxxxxxxxxxxxxxxxxx"
```

**For GitHub PRs in Jenkins:**

```bash
export GITHUB_TOKEN="ghp_xxxxxxxxxxxxxxxxxxxx"
```

Store credentials as Jenkins credentials and inject them as environment variables in your pipeline.

---

## Configuration

### Zero-Config (Recommended)

No configuration file changes are needed for Jenkins. Karandash auto-detects the Jenkins environment from `JENKINS_URL` and `CHANGE_URL`:

```groovy
// Jenkinsfile
pipeline {
    agent any
    stages {
        stage('Validate MR') {
            when {
                changeRequest()
            }
            steps {
                withCredentials([string(credentialsId: 'gitlab-token', variable: 'GITLAB_TOKEN')]) {
                    sh 'karandash validate'
                }
            }
        }
    }
}
```

### Configuration File

If you need explicit configuration, add the Jenkins provider to your `.karandash/config.yaml`:

```yaml
platforms:
  jenkins:
    change-url: "${CHANGE_URL}"
```

The `changeUrl` field is optional - Karandash reads `CHANGE_URL` from the environment automatically. When `CHANGE_URL` is unset, `changeUrl` is used as the merge request URL; when both are set, `CHANGE_URL` wins.

---

## Supported CHANGE_URL Formats

Jenkins parses the following `CHANGE_URL` patterns to determine the underlying platform:

| Platform | CHANGE_URL pattern | Example |
|----------|-------------------|---------|
| GitLab | `https://{host}/{path}/-/merge_requests/{id}` | `https://gitlab.com/org/repo/-/merge_requests/42` |
| GitHub | `https://{host}/{owner}/{repo}/pull/{id}` | `https://github.com/org/repo/pull/10` |
| Bitbucket | `https://{host}/{workspace}/{repo}/pull-requests/{id}` | `https://bitbucket.org/team/repo/pull-requests/5` |
| Gerrit | `https://{host}/c/{project}/+/{id}` | `https://gerrit.example.com/c/myproject/+/123` |
| Azure DevOps | `https://{host}/{org}/{project}/_git/{repo}/pullrequest/{id}` | `https://dev.azure.com/org/proj/_git/repo/pullrequest/7` |
| Gitea | `https://{host}/{owner}/{repo}/pulls/{id}` | `https://gitea.example.com/owner/repo/pulls/3` |
| AWS CodeCommit | `https://{region}.console.aws.amazon.com/codesuite/codecommit/repositories/{repo}/pull-requests/{id}/...` | - |

---

## Environment Variables

Jenkins sets these variables automatically in multibranch pipeline builds:

| Variable | Description |
|----------|-------------|
| `JENKINS_URL` | Jenkins instance URL (required for detection) |
| `CHANGE_URL` | Full URL of the MR/PR (required for detection) |
| `CHANGE_ID` | The change/PR number |
| `BRANCH_NAME` | The source branch name |
| `BUILD_URL` | URL of the current build |

---

## Features and Limitations

### What Works

| Feature | Status | Notes |
|---------|--------|-------|
| CI auto-detection | Yes | Detects `JENKINS_URL` + `CHANGE_URL` |
| Platform identification | Yes | Parses `CHANGE_URL` to find GitLab/GitHub/etc. |
| MR fetching | Delegated | Handled by the underlying platform provider |
| Comment posting | Delegated | Requires platform comment-poster configuration |
| Auto-fix | Delegated | Requires platform mutator configuration |

### What Does Not Work

| Feature | Reason |
|---------|--------|
| Direct MR fetch | Jenkins has no MR API; it delegates to the platform |
| Jenkins-native comments | Jenkins build results are not MR comments |

---

## Common Use Cases

### GitLab MR Validation in Jenkins

```groovy
// Jenkinsfile
pipeline {
    agent { docker { image 'rinekri/karandash:latest' } }
    stages {
        stage('Karandash') {
            when { changeRequest() }
            steps {
                withCredentials([string(credentialsId: 'gitlab-token', variable: 'GITLAB_TOKEN')]) {
                    sh 'karandash validate'
                }
            }
        }
    }
}
```

### GitHub PR Validation in Jenkins

```groovy
// Jenkinsfile
pipeline {
    agent { docker { image 'rinekri/karandash:latest' } }
    stages {
        stage('Karandash') {
            when { changeRequest() }
            steps {
                withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
                    sh 'karandash validate'
                }
            }
        }
    }
}
```

### With Comment Posting

Comment posting is handled by the underlying platform provider. Configure it in your `.karandash/config.yaml` as you would for a direct GitLab or GitHub setup:

```yaml
# .karandash/config.yaml
platforms:
  gitlab:
    token: "${GITLAB_TOKEN}"

reporters:
  console: true
  markdown: true   # Posts results as an MR comment via the GitLab platform
```

---

## Troubleshooting

### Jenkins CI Not Detected

```
Error: No CI environment detected
```

**Causes:**
- `JENKINS_URL` is not set (required for detection)
- `CHANGE_URL` is not set (required for MR identification)
- Build was not triggered by a pull/merge request (only multibranch pipeline builds on change requests have `CHANGE_URL`)

**Fix:**
- Verify the build is a multibranch pipeline build triggered by a pull/merge request
- Check that `JENKINS_URL` is set: `echo $JENKINS_URL`
- Check that `CHANGE_URL` is set: `echo $CHANGE_URL`
- Add a `when { changeRequest() }` guard to skip non-PR builds

### Unsupported CHANGE_URL Format

**Cause:** The `CHANGE_URL` format does not match any known platform pattern.

**Fix:**
- Verify the URL matches one of the supported patterns listed above
- For custom SCM systems, consider using the platform provider directly with `--pr {url}`

### Platform Provider Authentication Fails

Since Jenkins delegates to the underlying platform provider, authentication errors come from that provider. See the relevant platform guide:
- [GitLab](gitlab.md)
- [GitHub](github.md)
- [Bitbucket](bitbucket.md)
