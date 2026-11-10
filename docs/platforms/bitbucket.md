# Bitbucket Platform Guide

## Overview

Karandash provides **standard support** for Bitbucket Cloud (bitbucket.org).

**Support level:** Standard

**Capabilities:**
- Pull request fetching (title, commits, diff stats)
- Comment posting on PRs (post, update, inline comments)
- Auto-fix mutations (draft toggle, update title/description, approve/unapprove)
- Bitbucket Pipelines auto-detection
- Zero-config mode via environment variables
- URL-based PR validation

**Not available:**
- Label management (Bitbucket Cloud REST API does not support labels on pull requests)
- SARIF upload
- Bitbucket Server/Data Center support (Cloud only for URL parsing)

**API used:** Bitbucket Cloud REST API 2.0

---

## Authentication Setup

### Option 1: App Password

App passwords are the recommended authentication method for Bitbucket Cloud.

**Create an app password:**

1. Go to **Personal settings > App passwords** (at `https://bitbucket.org/account/settings/app-passwords/`)
2. Click **Create app password**
3. Set a label (e.g., "karandash-validation")
4. Select permissions:
   - **Repositories**: Read (required)
   - **Pull requests**: Read (required)
5. Click **Create**
6. Copy the generated password immediately

**Using app passwords:** Bitbucket sends an app password as HTTP Basic `username:app-password`. Set your
Bitbucket username alongside it, in config (`username:`) or the environment:

```bash
export BITBUCKET_USERNAME="your-bitbucket-username"
export BITBUCKET_TOKEN="your-app-password"
```

Without a username the token is sent as `Authorization: Bearer <token>`, which is what repository, project and
workspace access tokens (Options 2 and 3) need.

### Option 2: Repository Access Token

For CI/CD pipelines, repository access tokens are scoped to a single repository.

1. Go to **Repository settings > Access tokens**
2. Click **Create Repository Access Token**
3. Set a name and expiration
4. Select permissions: **Pull requests: Read**
5. Click **Create**

### Option 3: Bitbucket Pipelines (BITBUCKET_ACCESS_TOKEN)

In Bitbucket Pipelines, you can use the pipeline's built-in token or configure a repository variable.

**Using repository variables:**

1. Go to **Repository settings > Pipelines > Repository variables**
2. Add `BITBUCKET_TOKEN` with your app password value
3. Mark as **Secured**

### Environment Variable Setup

```bash
# Required
export BITBUCKET_TOKEN="your-app-password-or-token"
```

### Testing Authentication

```bash
export BITBUCKET_TOKEN="your-app-password"
karandash validate --pr https://bitbucket.org/myworkspace/myrepo/pull-requests/1
```

A successful run prints validation results. An authentication failure produces:

```
Authentication failed for bitbucket provider (HTTP 401).
Your token is invalid, expired, or revoked.
Hint: configure token via BITBUCKET_TOKEN.
Verify your app password at: Personal settings > App passwords.
```

---

## Configuration

### Environment Variables (Zero-Config)

```bash
export BITBUCKET_TOKEN="your-app-password"
karandash validate --pr https://bitbucket.org/myworkspace/myrepo/pull-requests/42
```

### Configuration File (.karandash/config.yaml)

```yaml
platforms:
  bitbucket:
    token: "${BITBUCKET_TOKEN}"
    # username: "your-bitbucket-username"   # set for an app password (HTTP Basic); omit for an access token (Bearer)
    # url: "https://api.bitbucket.org/2.0"  # API root; override for a proxy or a mock

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

reporters:
  console: true
  # markdown: true  # Not supported for Bitbucket
```

| Key | Default | Meaning |
|-----|---------|---------|
| `token` | `BITBUCKET_TOKEN` | App password or access token |
| `username` | `BITBUCKET_USERNAME` | When set, `token` is sent as HTTP Basic `username:token` (app passwords); otherwise as a Bearer token |
| `url` | `https://api.bitbucket.org/2.0` | API root every request goes to. Checked against SSRF rules: a loopback or private host needs `allow_localhost: true` |

### URL Formats

```
# Bitbucket Cloud
https://bitbucket.org/{workspace}/{repo-slug}/pull-requests/{id}
```

**Project identification:** `workspace/repo-slug` format (e.g., `myworkspace/myrepo`)

---

## Features and Limitations

### What Works

| Feature | Status | Notes |
|---------|--------|-------|
| PR fetching | Yes | Title, description, source/target branches |
| Commit validation | Yes | All commits in the PR |
| Diff stat detection | Yes | File changes via diffstat API |
| Comment posting | Yes | Post and update PR comments, inline comments |
| Draft toggle (--fix) | Yes | Adds/removes "Draft: " or "WIP: " title prefix |
| Title/description update (--fix) | Yes | Updates PR title and description |
| Approve/unapprove (--fix) | Yes | Approves or unapproves the PR |
| CI auto-detection | Yes | Detects BITBUCKET_BUILD_NUMBER, BITBUCKET_REPO_FULL_NAME, BITBUCKET_PR_ID |
| Zero-config mode | Yes | Via BITBUCKET_TOKEN environment variable |
| URL parsing | Yes | Parses bitbucket.org PR URLs |

### What Does Not Work

| Feature | Reason |
|---------|--------|
| Label management (--fix) | Bitbucket Cloud REST API does not support labels on pull requests |
| SARIF upload | No native Bitbucket Code Scanning integration |
| Bitbucket Server URLs | URL parser only handles bitbucket.org format |
| Approval details | Limited to PR metadata (no detailed review states) |

### Platform-Specific Considerations

- **API version:** Bitbucket Cloud REST API 2.0. The older 1.0 API is not supported.
- **Rate limiting:** Bitbucket Cloud allows 1,000 requests/hour for authenticated users. Karandash makes 3 API calls per PR validation (PR, commits, diffstat).
- **Workspace vs. Team:** Bitbucket uses "workspace" terminology. The workspace slug is the first path component in URLs.
- **Bitbucket Server/Data Center:** URL parsing only supports Cloud (bitbucket.org). Server instances use different URL patterns and may require manual platform specification.

---

## Common Use Cases

### Bitbucket Pipelines Integration

**Minimal setup:**

```yaml
# bitbucket-pipelines.yml
pipelines:
  pull-requests:
    '**':
      - step:
          name: Karandash Validation
          image: rinekri/karandash:latest
          script:
            - export BITBUCKET_TOKEN=$BITBUCKET_TOKEN
            - karandash validate
```

**With explicit project and MR ID:**

```yaml
# bitbucket-pipelines.yml
pipelines:
  pull-requests:
    '**':
      - step:
          name: Karandash Validation
          image: rinekri/karandash:latest
          script:
            - >
              karandash validate
              --platform bitbucket
              --project $BITBUCKET_REPO_FULL_NAME
              --mr-id $BITBUCKET_PR_ID
```

**With Java JAR:**

```yaml
# bitbucket-pipelines.yml
pipelines:
  pull-requests:
    '**':
      - step:
          name: Karandash Validation
          image: eclipse-temurin:17-jre
          script:
            - wget -q https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
            - ./karandash validate
```

### Local PR Testing

```bash
export BITBUCKET_TOKEN="your-app-password"

# Validate a specific PR
karandash validate --pr https://bitbucket.org/myworkspace/myrepo/pull-requests/42

# JSON output
karandash validate --pr https://bitbucket.org/myworkspace/myrepo/pull-requests/42 \
  --format json
```

### Using with Jenkins (Bitbucket Source)

If you use Jenkins with Bitbucket repositories:

```bash
# Jenkinsfile
pipeline {
    agent any
    environment {
        BITBUCKET_TOKEN = credentials('bitbucket-karandash-token')
    }
    stages {
        stage('Validate PR') {
            when {
                changeRequest()
            }
            steps {
                sh '''
                    karandash validate \
                      --platform bitbucket \
                      --project "${BITBUCKET_REPO_FULL_NAME}" \
                      --mr-id "${CHANGE_ID}"
                '''
            }
        }
    }
}
```

---

## Troubleshooting

### "401 Unauthorized"

```
Error: 401 Unauthorized
```

**Causes:**
- App password is expired or revoked
- Token does not have required permissions
- Using a personal password instead of an app password

**Fix:**
1. Verify the token: `curl -u "username:$BITBUCKET_TOKEN" https://api.bitbucket.org/2.0/user`
2. Regenerate the app password with correct permissions
3. App passwords require the username:password format for some operations, but Karandash uses bearer token authentication

### "404 Not Found"

```
Error: 404 Not Found
```

**Causes:**
- Wrong workspace or repository slug
- Repository is private and token lacks access
- PR ID does not exist

**Fix:**
1. Verify the workspace/repo slug matches the URL exactly
2. Check repository visibility and token permissions
3. Confirm the PR exists in the Bitbucket UI

### Bitbucket Server / Data Center

Karandash URL parsing only supports Bitbucket Cloud (bitbucket.org). For Bitbucket Server:

1. Use explicit platform and project flags:

```bash
karandash validate \
  --platform bitbucket \
  --project "myworkspace/myrepo" \
  --mr-id 42
```

2. Set the API base URL if needed via configuration.

### Comment Posting Not Available

**Cause:** Bitbucket CommentPoster is not implemented in the current version.

**Workaround:** Use JSON output and post comments via Bitbucket API in your pipeline:

```yaml
# bitbucket-pipelines.yml
- step:
    script:
      - karandash validate --format json --output results.json || true
      - |
        # Post comment via Bitbucket API
        curl -X POST \
          -u "user:$BITBUCKET_TOKEN" \
          -H "Content-Type: application/json" \
          -d "{\"content\": {\"raw\": \"$(cat results.json)\"}}" \
          "https://api.bitbucket.org/2.0/repositories/$BITBUCKET_REPO_FULL_NAME/pullrequests/$BITBUCKET_PR_ID/comments"
```

---

## Edge Cases and Limitations

### App Password vs Bearer Token Authentication

**Issue:** App passwords authenticate with HTTP Basic (`username:app-password`); access tokens with Bearer.

**Expected behavior:** Set `username` (or `BITBUCKET_USERNAME`) with an app password and Karandash sends Basic auth;
leave it unset with an access token and Karandash sends `Authorization: Bearer <token>`.

### Workspace Slug vs Workspace Name

**Issue:** Validation fails with "404 Not Found" even though the workspace exists.

**Root cause:** Bitbucket uses "workspace slug" (lowercase, no spaces) in URLs, not the display name. For example, workspace "My Team" has slug "myteam".

**Fix:** Use the workspace slug from the URL, not the display name:

```bash
# Correct (slug)
https://bitbucket.org/myteam/repo/pull-requests/1

# Incorrect (display name won't work)
https://bitbucket.org/My%20Team/repo/pull-requests/1
```

### Repository Access Token Scope Limitations

**Issue:** Repository access tokens work locally but fail in CI/CD.

**Root cause:** Repository access tokens are scoped to a single repository. They cannot access other repositories or workspace settings.

**Expected behavior:** This is by design. Use workspace-level app passwords for broader access.

### Rate Limiting Edge Case

**Issue:** Validation fails with "429 Too Many Requests" after running many times in parallel.

**Root cause:** Bitbucket Cloud allows 1,000 requests/hour per IP. If multiple parallel Karandash processes run from the same IP, they share the quota.

**Mitigation:**
- Serialize MR validations in your CI/CD (run sequentially, not in parallel), or
- Implement exponential backoff in your pipeline, or
- Request rate limit increase from Bitbucket support

### Bitbucket Server URL Parsing

**Issue:** Bitbucket Server URL does not parse correctly.

**Root cause:** Karandash only supports Bitbucket Cloud (bitbucket.org). Server instances use different URL structures:

```
# Cloud (supported)
https://bitbucket.org/workspace/repo/pull-requests/123

# Server (NOT supported)
https://bitbucket.example.com/projects/KEY/repos/repo/pull-requests/123
```

**Workaround:** Use explicit `--platform` and `--project` flags:

```bash
karandash validate \
  --platform bitbucket \
  --project "workspace/repo" \
  --mr-id 123
```

Then manually configure the API base URL in `.karandash/config.yaml` if needed.

### Diffstat API Limitations

**Issue:** File change detection shows limited information compared to GitHub or GitLab.

**Root cause:** Bitbucket's diffstat API provides file counts and additions/deletions but not full change details.

**Expected behavior:** Karandash reports file counts and total line changes. For full diffs, fetch the PR diff endpoint separately.

### PR Merge State Detection

**Issue:** Validating a PR immediately after merge succeeds but shows "merged" status inconsistently.

**Root cause:** Bitbucket's merge state propagates eventually. Immediately after merge, the PR object may show "OPEN" or "MERGED" inconsistently.

**Expected behavior:** Wait a few seconds after merge to validate, or check the actual merge status in the Bitbucket UI.

### Clone Links and SSH Keys

**Issue:** Karandash validation fails even though the repository is cloned successfully in the same pipeline.

**Root cause:** Karandash uses HTTP API (not SSH/git), so SSH keys in your pipeline are not used for Karandash. Only the BITBUCKET_TOKEN matters.

**Expected behavior:** SSH keys and git clone credentials are separate from API token credentials.

### Workspace-Level App Passwords

**Issue:** App password works when tested manually but fails in Bitbucket Pipelines.

**Root cause:** Bitbucket Pipelines repository variables may not be passed correctly to the container.

**Fix:** Verify the variable is marked as "Secured" and properly scoped:

1. Go to **Repository settings > Pipelines > Repository variables**
2. Verify `BITBUCKET_TOKEN` is listed and marked as "Secured"
3. Verify it's not restricted to a specific branch

### Large PR with Many File Changes

**Issue:** Validation times out or is slow on PRs with >500 file changes.

**Root cause:** Bitbucket's diffstat API returns file counts, but fetching full change details may require multiple API calls.

**Expected behavior:** Karandash fetches file stats efficiently. Validation should complete in 5-10 seconds even for large PRs.

---

## Authentication Edge Cases

### App Password Expiration

**Issue:** Validation fails with "401 Unauthorized" after an app password expires.

**Root cause:** App passwords have expiration dates set during creation. Expired passwords no longer authenticate.

**Fix:**
1. Check app password expiration in **Personal settings > App passwords**
2. Regenerate a new password with extended expiration
3. Update `BITBUCKET_TOKEN` in your CI/CD configuration

### Repository Access Token Scope Mismatch

**Issue:** Repository access token works for one repository but fails for another.

**Root cause:** Repository access tokens are strictly scoped to a single repository. They cannot be reused across multiple repositories.

**Mitigation:**
- Create separate tokens for each repository, or
- Use workspace-level app passwords for broader coverage, or
- Implement token rotation in your CI/CD pipeline

### Bearer Token vs Basic Auth Confusion

**Issue:** An app password sent as a Bearer token is rejected with 401.

**Root cause:** Without `username` Karandash sends the token as Bearer, which only access tokens accept.

**Expected behavior:** Set `username` (or `BITBUCKET_USERNAME`) for an app password.

---

## Rate Limiting Considerations

### Parallel PR Validations

**Issue:** Multiple Karandash validations running in parallel fail with "429 Too Many Requests."

**Root cause:** Bitbucket Cloud enforces 1,000 requests/hour per IP. Each validation uses 3 API calls. Parallel execution from the same IP exhausts quota quickly.

**Calculation:**
- 1,000 requests/hour ÷ 3 calls per PR = 333 sequential validations
- For parallel execution: (1,000 ÷ 3) ÷ number_of_parallel_jobs

**Mitigation:**
- Serialize MR validations (run one at a time), or
- Implement exponential backoff (2s, 4s, 8s delays), or
- Contact Bitbucket support for rate limit increase

### Rate Limit Headers

**Issue:** Response includes `X-RateLimit-Remaining: 0` but validation succeeds anyway.

**Root cause:** Bitbucket returns rate limit headers with every response. Karandash only errors when the limit is actually exceeded (HTTP 429).

**Check remaining quota:**

```bash
curl -H "Authorization: Bearer $BITBUCKET_TOKEN" \
  https://api.bitbucket.org/2.0/user
```

Response includes rate limit headers showing your current quota.

### Workspace vs Repository Token Limits

**Issue:** Token works for individual repos but fails under load with multiple repositories.

**Root cause:** Each token has its own rate limit quota. Repository tokens may have lower limits than workspace tokens.

**Fix:** Use workspace-level app passwords for multi-repository validation scenarios.

---

## API Differences from Other Platforms

### Limited Change Details

**Issue:** Karandash reports file counts but not detailed change information (additions/deletions per file).

**Root cause:** Bitbucket's diffstat API provides aggregate stats only. Full diff requires a separate API call.

**Expected behavior:** Karandash reports file counts and total additions/deletions. This is sufficient for size validation.

### Approval Information Limitations

**Issue:** PR validation cannot check specific approvers or detailed review states.

**Root cause:** Bitbucket's API provides limited approval metadata compared to GitHub or GitLab.

**Workaround:** Use approval rules in the Bitbucket UI for more granular control.

---

## Examples

### Minimal Bitbucket Pipelines Configuration

```yaml
# bitbucket-pipelines.yml
pipelines:
  pull-requests:
    '**':
      - step:
          name: PR Validation
          image: rinekri/karandash:latest
          script:
            - karandash validate
```

### Full Bitbucket Pipelines with JSON Artifact

```yaml
# bitbucket-pipelines.yml
pipelines:
  pull-requests:
    '**':
      - step:
          name: Karandash PR Validation
          image: rinekri/karandash:latest
          script:
            - karandash validate --format json --output karandash-results.json
          artifacts:
            - karandash-results.json
```

### Bitbucket-Optimized Configuration

```yaml
# .karandash/config.yaml
platforms:
  bitbucket:
    token: "${BITBUCKET_TOKEN}"

rules:
  title-format:
    pattern: "^(feat|fix|chore|docs|refactor): .+"
    max-length: 120
  commit-messages:
    format: "conventional"
    max-length: 100
  branch-naming:
    pattern: "^(feature|bugfix|hotfix|release)/[A-Z]+-[0-9]+-[a-z0-9-]+$"
  draft:
    block-draft: fail
  size-limits:
    max-files: 30
    max-lines: 500
  security:
    scan-for-secrets: true

reporters:
  console: true
```
