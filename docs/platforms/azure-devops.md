# Azure DevOps Platform Guide

## Overview

Karandash provides **standard support** for Azure DevOps Services (dev.azure.com) and Azure DevOps Server.

**Support level:** Standard

**Capabilities:**
- Pull request fetching (title, commits, changes)
- Azure Pipelines auto-detection
- Zero-config mode via environment variables
- URL-based PR validation

**Not available:**
- SARIF upload to Azure DevOps

**API used:** Azure DevOps REST API

---

## Authentication Setup

### Option 1: Personal Access Token (PAT)

Use a PAT for local development or external CI systems.

**Create a token:**

1. Go to **User Settings (gear icon) > Personal access tokens** in Azure DevOps
2. Click **New Token**
3. Set a name (e.g., "karandash-validation")
4. Set the organization scope
5. Set an expiration date (maximum 1 year)
6. Under **Scopes**, select:
   - **Code**: Read (required)
   - **Pull Request Threads**: Read (required)
   - For broader access, select **Full access**
7. Click **Create**
8. Copy the token immediately

### Option 2: Azure Pipelines System Token (SYSTEM_ACCESSTOKEN)

In Azure Pipelines, the built-in `System.AccessToken` provides scoped access.

**Enable the token in your pipeline:**

```yaml
# azure-pipelines.yml
steps:
  - script: echo "Token available"
    env:
      SYSTEM_ACCESSTOKEN: $(System.AccessToken)
```

**Important:** `System.AccessToken` must be explicitly mapped as an environment variable. It is not automatically available as an environment variable.

**Default permissions:** Scoped to the current project with read access to code and pull requests.

### Environment Variable Setup

```bash
# Required (one of these)
export AZURE_DEVOPS_TOKEN="your-pat-here"
# Or in Azure Pipelines:
export SYSTEM_ACCESSTOKEN="$(System.AccessToken)"
```

Karandash checks `AZURE_DEVOPS_TOKEN` first, then falls back to `SYSTEM_ACCESSTOKEN`.

### Testing Authentication

```bash
export AZURE_DEVOPS_TOKEN="your-pat-here"
karandash validate --pr https://dev.azure.com/myorg/myproject/_git/myrepo/pullrequest/1
```

A successful run prints validation results. An authentication failure produces:

```
Authentication failed for azure-devops provider (HTTP 401).
Your token is invalid, expired, or revoked.
Hint: configure token via AZURE_DEVOPS_TOKEN.
```

---

## Configuration

### Environment Variables (Zero-Config)

```bash
export AZURE_DEVOPS_TOKEN="your-pat-here"
karandash validate --pr https://dev.azure.com/myorg/myproject/_git/myrepo/pullrequest/42
```

### Configuration File (.karandash/config.yaml)

```yaml
platforms:
  azure-devops:
    token: "${AZURE_DEVOPS_TOKEN}"
    organization: "myorg"   # completes a project/repository project ID
    # url: "https://tfs.example.com/tfs"   # Azure DevOps Server; default https://dev.azure.com

rules:
  title-format:
    pattern: "^(feat|fix|chore|docs): .+"
    max-length: 120
  commit-messages:
    format: "conventional"
  draft:
    block-draft: fail
  size-limits:
    max-lines: 500
    max-files: 30
  approval:
    min-approvals: 2

reporters:
  console: true
  markdown: true   # Post results as PR comment
```

### URL Formats

```
# Azure DevOps Services
https://dev.azure.com/{organization}/{project}/_git/{repository}/pullrequest/{id}
```

**Project identification:** `organization/project/repository` format (e.g., `myorg/myproject/myrepo`)

This three-part identifier is parsed automatically from the PR URL.

---

## Features and Limitations

### What Works

| Feature | Status | Notes |
|---------|--------|-------|
| PR fetching | Yes | Title, description, source/target branches |
| Commit validation | Yes | All commits in the PR |
| Change detection | Yes | File changes via changes API |
| Comment posting | Yes | Posts threads via Azure DevOps Threads API |
| Comment updating | Yes | Updates existing comment threads |
| Inline comments | Yes | Posts inline thread comments on PR diffs |
| Label management (--fix) | Yes | Add and remove PR labels |
| Draft status (--fix) | Yes | Toggle draft via `isDraft` field |
| Title update (--fix) | Yes | Updates PR title |
| Description update (--fix) | Yes | Updates PR description |
| Approve / unapprove | Yes | Sets or clears vote on the PR |
| CI auto-detection | Yes | Detects TF_BUILD, AZURE_HTTP_USER_AGENT, BUILD_REPOSITORY_NAME |
| Zero-config mode | Yes | Via AZURE_DEVOPS_TOKEN or SYSTEM_ACCESSTOKEN |
| URL parsing | Yes | Parses dev.azure.com PR URLs |
| PR ID detection | Yes | Reads SYSTEM_PULLREQUEST_PULLREQUESTID in Azure Pipelines |

### What Does Not Work

| Feature | Reason |
|---------|--------|
| SARIF upload | No direct integration; use Azure Pipelines publish task |
| Azure DevOps Server URLs | URL parser expects dev.azure.com format |
| Work item linking | Not validated by Karandash |

### Platform-Specific Considerations

- **Three-part project ID:** Azure DevOps uses `organization/project/repository`, which differs from the two-part `owner/repo` used by GitHub and GitLab.
- **System.AccessToken:** Must be explicitly mapped to an environment variable in pipeline YAML. It is not available by default.
- **Rate limiting:** Azure DevOps allows 200 requests per 5 minutes for PATs. Karandash makes 3 API calls per PR validation (PR, commits, changes).
- **On-premises:** Azure DevOps Server uses different URL patterns. Use explicit `--platform` and `--project` flags.

### CI Auto-Detection Variables

Karandash detects Azure Pipelines using these environment variables:

| Variable | Purpose |
|----------|---------|
| `TF_BUILD` or `AZURE_HTTP_USER_AGENT` | Identifies Azure Pipelines |
| `BUILD_REPOSITORY_NAME` | Repository name |
| `SYSTEM_PULLREQUEST_PULLREQUESTID` | Pull request ID |
| `SYSTEM_TEAMFOUNDATIONCOLLECTIONURI` | Organization URL |
| `SYSTEM_TEAMPROJECT` | Project name |
| `SYSTEM_ACCESSTOKEN` | Pipeline auth token |

---

## Common Use Cases

### Azure Pipelines Integration

**Minimal setup:**

```yaml
# azure-pipelines.yml
trigger: none

pr:
  branches:
    include:
      - main
      - develop

pool:
  vmImage: 'ubuntu-latest'

steps:
  - task: JavaToolInstaller@0
    inputs:
      versionSpec: '17'
      jdkArchitectureOption: 'x64'
      jdkSourceOption: 'PreInstalled'

  - script: |
      wget -q https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
      ./karandash validate
    displayName: 'Karandash Validation'
    env:
      AZURE_DEVOPS_TOKEN: $(System.AccessToken)
```

**With Docker:**

```yaml
# azure-pipelines.yml
trigger: none
pr:
  - main

pool:
  vmImage: 'ubuntu-latest'

container: rinekri/karandash:latest

steps:
  - script: karandash validate
    displayName: 'Karandash Validation'
    env:
      AZURE_DEVOPS_TOKEN: $(System.AccessToken)
```

**With explicit parameters:**

```yaml
steps:
  - script: |
      karandash validate \
        --platform azure-devops \
        --project "$(System.TeamFoundationCollectionUri)$(System.TeamProject)" \
        --mr-id "$(System.PullRequest.PullRequestId)"
    displayName: 'Karandash Validation'
    env:
      AZURE_DEVOPS_TOKEN: $(System.AccessToken)
```

### Local PR Testing

```bash
export AZURE_DEVOPS_TOKEN="your-pat-here"

# Validate a specific PR
karandash validate --pr https://dev.azure.com/myorg/myproject/_git/myrepo/pullrequest/42

# JSON output
karandash validate --pr https://dev.azure.com/myorg/myproject/_git/myrepo/pullrequest/42 \
  --format json
```

---

## Troubleshooting

### "401 Unauthorized" or "VS403" Errors

```
Error: 401 Unauthorized
```

**Causes:**
- PAT is expired or revoked
- PAT does not have required scopes
- System.AccessToken not mapped to environment variable

**Fix:**
1. Check PAT expiration in **User Settings > Personal access tokens**
2. Verify scopes include **Code: Read**
3. For pipelines, explicitly map the token:

```yaml
env:
  AZURE_DEVOPS_TOKEN: $(System.AccessToken)
```

### "404 Not Found"

**Causes:**
- Wrong organization, project, or repository name
- PAT scoped to a different organization

**Fix:**
1. Verify the URL components match: `https://dev.azure.com/{org}/{project}/_git/{repo}/pullrequest/{id}`
2. Check that the PAT is created in the correct organization

### Azure DevOps Server (On-Premises)

URL parsing expects `dev.azure.com` format. For on-premises instances:

```bash
karandash validate \
  --platform azure-devops \
  --project "myorg/myproject/myrepo" \
  --mr-id 42
```

Set authentication:

```yaml
# .karandash/config.yaml
platforms:
  azure-devops:
    token: "${AZURE_DEVOPS_TOKEN}"
    organization: "myorg"
```

### System.AccessToken Has Insufficient Permissions

**Cause:** The pipeline's service connection may not have pull request read access.

**Fix:**
1. Go to **Project Settings > Pipelines > Service connections**
2. Edit the build service account permissions
3. Grant **Contribute to pull requests** permission

Or use a PAT stored as a pipeline variable:

```yaml
variables:
  - name: AZURE_DEVOPS_TOKEN
    value: $(karandash-pat)  # Stored as a secret variable
```

---

## Examples

### Minimal Azure Pipelines Configuration

```yaml
# azure-pipelines.yml
trigger: none
pr:
  - main

pool:
  vmImage: 'ubuntu-latest'

steps:
  - script: |
      docker run \
        -e AZURE_DEVOPS_TOKEN=$(System.AccessToken) \
        rinekri/karandash:latest karandash validate
    displayName: 'Karandash PR Validation'
    env:
      AZURE_DEVOPS_TOKEN: $(System.AccessToken)
```

### Azure DevOps Configuration

```yaml
# .karandash/config.yaml
platforms:
  azure-devops:
    token: "${AZURE_DEVOPS_TOKEN}"
    organization: "myorg"

rules:
  title-format:
    pattern: "^(feat|fix|chore|docs|refactor): .+"
    max-length: 120
  commit-messages:
    format: "conventional"
    max-length: 100
  branch-naming:
    pattern: "^(feature|bugfix|hotfix|release)/[a-z0-9-]+$"
  draft:
    block-draft: fail
  size-limits:
    max-files: 30
    max-lines: 500
  approval:
    min-approvals: 2
  security:
    scan-for-secrets: true

reporters:
  console: true
```

### Multi-Stage Pipeline with Validation Gate

```yaml
# azure-pipelines.yml
trigger: none
pr:
  - main
  - release/*

stages:
  - stage: Validate
    jobs:
      - job: KarandashValidation
        pool:
          vmImage: 'ubuntu-latest'
        steps:
          - task: JavaToolInstaller@0
            inputs:
              versionSpec: '17'
              jdkArchitectureOption: 'x64'
              jdkSourceOption: 'PreInstalled'
          - script: |
              wget -q https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
              ./karandash validate --format json --output results.json
            displayName: 'Run Karandash'
            env:
              AZURE_DEVOPS_TOKEN: $(System.AccessToken)
          - publish: results.json
            artifact: karandash-results

  - stage: Build
    dependsOn: Validate
    jobs:
      - job: BuildApp
        steps:
          - script: echo "Building..."
```

---

## Authentication Edge Cases

### System.AccessToken Not Passed to Environment

**Issue:** Karandash fails with "Authentication failed for azure-devops provider (HTTP 401)" even though the pipeline is running.

**Root cause:** `System.AccessToken` is not automatically available as an environment variable. It must be explicitly mapped in the `env` section.

**Fix:**

```yaml
steps:
  - script: karandash validate
    env:
      AZURE_DEVOPS_TOKEN: $(System.AccessToken)  # Explicit mapping required
```

Forgetting this mapping is a common mistake that causes authentication failures.

### PAT Scoped to Wrong Organization

**Issue:** "401 Unauthorized" when using a PAT created in a different organization.

**Root cause:** Azure DevOps PATs are organization-specific. A token created in "OrgA" cannot access repositories in "OrgB."

**Fix:**
1. Create a new PAT in the organization containing your repositories
2. Verify the organization URL in the token settings
3. Test with: `curl -u ":$AZURE_DEVOPS_TOKEN" https://dev.azure.com/myorg/_apis/projects`

### PAT Expiration During Long-Running Pipelines

**Issue:** Validation succeeds early in pipeline but fails in later stages.

**Root cause:** Azure DevOps PATs have maximum 1-year expiration. If a PAT expires between pipeline stages, later validations fail.

**Mitigation:**
- Use `System.AccessToken` instead of PATs (auto-scoped and auto-managed)
- Set PAT expiration to 1 year maximum
- Monitor PAT expiration dates
- Regenerate PATs before they expire

---

## Rate Limiting Considerations

### Request Quota Exhaustion

**Issue:** "Too Many Requests" errors after running many validations in parallel.

**Root cause:** Azure DevOps enforces 200 requests per 5-minute window per PAT. Karandash uses 3 API calls per PR. Multiple parallel jobs exhaust quota.

**Calculation:**
- 200 requests ÷ 5 minutes = 40 requests/minute
- 40 ÷ 3 calls per PR = ~13 sequential validations/minute
- For parallel: 13 ÷ number_of_parallel_jobs

**Mitigation:**
- Serialize validations (run one at a time), or
- Stagger validation triggers across multiple pipelines, or
- Use separate PATs for different projects

### Service Connection Rate Limits

**Issue:** `System.AccessToken` validations fail with rate limiting errors.

**Root cause:** `System.AccessToken` shares rate limits with all other API calls in your organization. Heavy usage of other APIs reduces Karandash's quota.

**Workaround:** Use a dedicated PAT for Karandash if you hit limits frequently.

---

## Platform-Specific Limitations

### Three-Part Project Identification

**Issue:** Using two-part project ID (like `owner/repo`) fails.

**Root cause:** Azure DevOps requires three components: organization/project/repository. This differs from GitHub's two-part format.

**Fix:**

```bash
# Correct (three parts)
karandash validate \
  --pr https://dev.azure.com/myorg/myproject/_git/myrepo/pullrequest/42

# Incorrect (two parts won't work)
--project "myorg/myrepo"
```

### Limited Approval Metadata

**Issue:** Karandash cannot distinguish between different reviewer approvals or required vs. optional approvals.

**Root cause:** Azure DevOps API provides limited approval state information. Detailed approval rules exist in the UI but are not fully exposed via API.

**Expected behavior:** Karandash validates minimum approval count. For detailed approval rules, check the Azure DevOps UI.

### No Label Support

**Issue:** "Label not found" errors when validating label rules.

**Root cause:** Azure DevOps PRs do not support labels like GitHub and GitLab. Work items use tags, but PRs don't.

**Fix:** Use Azure DevOps work items and pull request policies instead of label validation.

---

## Azure Pipelines-Specific Edge Cases

### SYSTEM_ACCESSTOKEN Scope in Multi-Project Pipelines

**Issue:** Validating PRs in different projects fails with permission errors.

**Root cause:** `System.AccessToken` is scoped to the current project only. Cross-project validation is not supported.

**Workaround:** Use a PAT with explicit organization-level scope instead.

### Build Service Account Permissions

**Issue:** Pipeline runs but Karandash cannot fetch PR details (404).

**Root cause:** The build service account must have explicit permissions to the repository. Default permissions may be insufficient.

**Fix:**
1. Go to **Project Settings > Repositories**
2. Select the repository
3. Find the build service account and grant **Reader** permission on **Code**

### Environment Variable Name Conflicts

**Issue:** Custom `AZURE_DEVOPS_TOKEN` variable is ignored; pipeline uses default token.

**Root cause:** Azure Pipelines has reserved variable names. Using standard names may cause conflicts.

**Fix:** Use a prefixed variable name:

```yaml
variables:
  KARANDASH_TOKEN: $(my-pat)  # Use non-standard name

steps:
  - script: karandash validate
    env:
      AZURE_DEVOPS_TOKEN: $(KARANDASH_TOKEN)
```

---

## API Differences from Other Platforms

### Change Detection Limitations

**Issue:** File change detection is incomplete or inaccurate.

**Root cause:** Azure DevOps API provides limited change metadata compared to GitHub/GitLab.

**Expected behavior:** Karandash reports file counts and aggregated stats. For detailed diffs, check the PR web UI.

### Approval Rule Complexity

**Issue:** Azure DevOps has advanced approval rules (required reviewers, path-based, etc.) but Karandash only validates minimum count.

**Root cause:** These are organization/project policies, not API-accessible as simple counts.

**Workaround:** Combine Karandash validation with Azure DevOps branch policies for complete PR governance.
