# Gitea Platform Guide

## Overview

Karandash provides **standard support** for Gitea and Forgejo instances.

**Support level:** Standard

**Capabilities:**
- Pull request fetching (title, commits, files, reviews)
- Zero-config mode via environment variables
- URL-based PR validation
- Self-hosted instance support

**Not available:**
- CI auto-detection (no standard Gitea CI environment variables)
- SARIF upload

**API used:** Gitea API v1 (compatible with Forgejo)

---

## Authentication Setup

### Option 1: Personal Access Token

**Create a token:**

1. Log in to your Gitea instance
2. Go to **Settings > Applications** (at `https://gitea.example.com/user/settings/applications`)
3. Under **Manage Access Tokens**, enter a token name (e.g., "karandash-validation")
4. Select scopes (if Gitea version supports scoped tokens):
   - **repo**: Read (required)
   - **issue**: Read (for PR metadata)
5. Click **Generate Token**
6. Copy the token immediately

### Option 2: Application Token

For CI/CD systems, application-level tokens (if supported by your Gitea version) provide broader access without being tied to a user account.

### Option 3: Anonymous Access

For public Gitea repositories, Karandash can fetch PR data without a token:

```bash
export GITEA_URL="https://gitea.example.com"
# No GITEA_TOKEN needed for public repos
karandash validate --pr https://gitea.example.com/owner/repo/pulls/1
```

### Environment Variable Setup

```bash
# Required
export GITEA_URL="https://gitea.example.com"

# Optional (not needed for public repos)
export GITEA_TOKEN="your-token-here"
```

**Both `GITEA_URL` and `GITEA_TOKEN` are checked.** The URL is required for API communication. The token is optional for public repositories.

### Testing Authentication

```bash
export GITEA_URL="https://gitea.example.com"
export GITEA_TOKEN="your-token-here"
karandash validate --pr https://gitea.example.com/myorg/myrepo/pulls/1
```

A successful run prints validation results. An authentication failure produces:

```
Authentication failed for gitea provider (HTTP 401).
Your token is invalid, expired, or revoked.
Hint: configure token via GITEA_TOKEN.
```

---

## Configuration

### Environment Variables (Zero-Config)

```bash
export GITEA_URL="https://gitea.example.com"
export GITEA_TOKEN="your-token-here"
karandash validate --pr https://gitea.example.com/myorg/myrepo/pulls/42
```

### Configuration File (.karandash/config.yaml)

```yaml
platforms:
  gitea:
    url: "https://gitea.example.com"
    token: "${GITEA_TOKEN}"

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
```

### URL Formats

```
# Gitea / Forgejo
https://{host}/{owner}/{repo}/pulls/{id}

# Examples
https://gitea.example.com/myorg/myrepo/pulls/42
https://codeberg.org/myorg/myrepo/pulls/10
```

**Project identification:** `owner/repo` format (e.g., `myorg/myrepo`)

### Forgejo Compatibility

Forgejo is a soft fork of Gitea with a compatible API. Karandash works with Forgejo instances using the same configuration:

```yaml
platforms:
  gitea:
    url: "https://forgejo.example.com"
    token: "${GITEA_TOKEN}"
```

Codeberg.org (a popular Forgejo host) works the same way:

```bash
export GITEA_URL="https://codeberg.org"
export GITEA_TOKEN="your-codeberg-token"
karandash validate --pr https://codeberg.org/myorg/myrepo/pulls/5
```

---

## Features and Limitations

### What Works

| Feature | Status | Notes |
|---------|--------|-------|
| PR fetching | Yes | Title, description, source/target branches |
| Commit validation | Yes | All commits in the PR |
| File change detection | Yes | Files changed via PR files API |
| Review state checking | Yes | Fetches PR review states |
| Comment posting | Yes | Posts issue comments on PRs |
| Comment updating | Yes | Finds and updates existing Karandash comments |
| Inline comments | Yes | Posts review comments on PR diffs |
| Label management (--fix) | Yes | Add and remove labels by name or ID |
| Draft status (--fix) | Yes | Toggle draft field |
| Title update (--fix) | Yes | Updates PR title |
| Description update (--fix) | Yes | Updates PR body |
| Approve / unapprove | Yes | Creates or dismisses approval reviews |
| Zero-config mode | Yes | Via GITEA_URL + optional GITEA_TOKEN |
| URL parsing | Yes | Parses `{host}/{owner}/{repo}/pulls/{id}` format |
| Forgejo compatibility | Yes | Same API, same configuration |
| Public repos | Yes | Token is optional for public repositories |

### What Does Not Work

| Feature | Reason |
|---------|--------|
| CI auto-detection | No standard Gitea CI environment variables |
| SARIF upload | No native Gitea code scanning integration |

### Platform-Specific Considerations

- **URL ambiguity:** Gitea PR URLs (`/pulls/{id}`) look similar to other platforms. Karandash distinguishes Gitea URLs by checking that the host does not contain "github" (GitHub uses `/pull/{id}`, Gitea uses `/pulls/{id}`).
- **API version:** Karandash uses Gitea API v1. Minimum Gitea version: 1.12+ (for PR review API).
- **Self-hosted only:** There is no central Gitea SaaS (Codeberg.org uses Forgejo). You must always provide `GITEA_URL`.
- **Rate limiting:** Self-hosted instances may or may not have rate limits. Check your instance configuration. When the instance sends `X-RateLimit-*` headers, `karandash doctor` and the debug log show the remaining quota; without them none is reported.
- **Review data:** Gitea provides review states (approved, changes requested, etc.) which Karandash uses for approval validation.

---

## Common Use Cases

### Local PR Testing

```bash
export GITEA_URL="https://gitea.example.com"
export GITEA_TOKEN="your-token-here"

# Validate a specific PR
karandash validate --pr https://gitea.example.com/myorg/myrepo/pulls/42

# JSON output
karandash validate --pr https://gitea.example.com/myorg/myrepo/pulls/42 \
  --format json

# Codeberg
export GITEA_URL="https://codeberg.org"
karandash validate --pr https://codeberg.org/org/repo/pulls/10
```

### Gitea Actions Integration

Gitea Actions (available in Gitea 1.19+) uses a GitHub Actions-compatible syntax:

```yaml
# .gitea/workflows/karandash.yml
name: Karandash Validation
on:
  pull_request:
    types: [opened, synchronize, reopened]

jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Run Karandash
        run: |
          wget -q https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
          ./karandash validate \
            --platform gitea \
            --project "${{ github.repository }}" \
            --mr-id "${{ github.event.pull_request.number }}"
        env:
          GITEA_URL: ${{ github.server_url }}
          GITEA_TOKEN: ${{ secrets.GITEA_TOKEN }}
```

**Note:** Gitea Actions provides `github.*` context variables for compatibility with GitHub Actions workflows.

### Woodpecker CI Integration

Woodpecker CI is commonly used with Gitea:

```yaml
# .woodpecker.yml
pipeline:
  karandash:
    image: rinekri/karandash:latest
    commands:
      - karandash validate --platform gitea --project "${CI_REPO}" --mr-id "${CI_COMMIT_PULL_REQUEST}"
    environment:
      GITEA_URL: https://gitea.example.com
    secrets: [gitea_token]
    when:
      event: pull_request
```

**Woodpecker CI environment variables:**

| Variable | Description |
|----------|-------------|
| `CI_REPO` | Repository in `owner/repo` format |
| `CI_COMMIT_PULL_REQUEST` | Pull request number |
| `CI_REPO_LINK` | Repository URL |

### Drone CI Integration

```yaml
# .drone.yml
kind: pipeline
type: docker
name: validate

trigger:
  event:
    - pull_request

steps:
  - name: karandash
    image: rinekri/karandash:latest
    environment:
      GITEA_URL: https://gitea.example.com
      GITEA_TOKEN:
        from_secret: gitea_token
    commands:
      - karandash validate --platform gitea --project "${DRONE_REPO}" --mr-id "${DRONE_PULL_REQUEST}"
```

### Jenkins Integration

```groovy
// Jenkinsfile
pipeline {
    agent {
        docker {
            image 'rinekri/karandash:latest'
        }
    }
    environment {
        GITEA_URL = 'https://gitea.example.com'
        GITEA_TOKEN = credentials('gitea-token')
    }
    stages {
        stage('Validate PR') {
            when {
                changeRequest()
            }
            steps {
                sh '''
                    karandash validate \
                      --platform gitea \
                      --project "${CHANGE_URL##*/}" \
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
- Token is expired or revoked
- Token does not have required scopes
- GITEA_URL does not match the instance

**Fix:**
1. Regenerate the token in Gitea Settings > Applications
2. Verify the URL matches your instance exactly (including protocol)
3. Test: `curl -H "Authorization: token $GITEA_TOKEN" "$GITEA_URL/api/v1/user"`

### "404 Not Found"

**Causes:**
- Wrong owner or repository name
- Repository is private and token lacks access
- PR number does not exist

**Fix:**
1. Verify owner/repo match the URL
2. Check repository visibility
3. Confirm the PR exists in the Gitea web UI

### Gitea URL Detected as GitHub

**Cause:** If your Gitea hostname contains "github" (unlikely but possible), Karandash may misidentify it as GitHub.

**Fix:** Use explicit platform flag:

```bash
karandash validate \
  --platform gitea \
  --project "owner/repo" \
  --mr-id 42
```

### Forgejo API Differences

**Cause:** Newer Forgejo versions may diverge from Gitea API.

**Fix:** Karandash targets Gitea API v1, which Forgejo maintains compatibility with. If you encounter API issues with a very new Forgejo version, report it as a Karandash issue.

---

## Examples

### Minimal Configuration

```yaml
# .karandash/config.yaml
platforms:
  gitea:
    url: "${GITEA_URL}"
    token: "${GITEA_TOKEN}"

rules:
  title-format:
    pattern: "^(feat|fix|docs): .+"
  draft:
    block-draft: fail

reporters:
  console: true
```

### Gitea-Optimized Configuration

```yaml
# .karandash/config.yaml
platforms:
  gitea:
    url: "${GITEA_URL}"
    token: "${GITEA_TOKEN}"

rules:
  title-format:
    pattern: "^(feat|fix|chore|docs|refactor|test): .+"
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
    max-commits: 20
  approval:
    min-approvals: 1
  security:
    scan-for-secrets: true

reporters:
  console: true
```

### Codeberg Configuration

```yaml
# .karandash/config.yaml for Codeberg.org
platforms:
  gitea:
    url: "https://codeberg.org"
    token: "${CODEBERG_TOKEN}"

rules:
  title-format:
    pattern: "^(feat|fix|chore|docs): .+"
  commit-messages:
    format: "conventional"
  size-limits:
    max-lines: 500

reporters:
  console: true
```

---

## Authentication Edge Cases

### Token Expiration on Self-Hosted Instances

**Issue:** Validation fails with "401 Unauthorized" after token expires.

**Root cause:** Personal access tokens on self-hosted Gitea have configurable expiration dates. Expired tokens no longer authenticate.

**Fix:**
1. Go to **Settings > Applications > Manage Access Tokens**
2. Check token expiration date
3. Regenerate a new token if needed
4. Update `GITEA_TOKEN` in your CI/CD configuration

### Forgejo API Drift from Gitea

**Issue:** Validation fails on very new Forgejo versions with "API not supported" errors.

**Root cause:** Forgejo is a soft fork of Gitea. While mostly compatible, new Forgejo versions may diverge slightly from Gitea API v1.

**Mitigation:**
- Report incompatibilities as Karandash issues
- Use a Gitea-compatible Forgejo version
- Test with a Codeberg mirror if using Forgejo

### Anonymous Access Token Confusion

**Issue:** Attempting to use `GITEA_TOKEN` for public repos fails or behaves unexpectedly.

**Root cause:** For public repositories, `GITEA_TOKEN` is optional. Karandash authenticates if a token is provided, but public repos work without one.

**Expected behavior:** Public repo validation works with or without a token. Private repos require a token.

### URL Detection Edge Case (hostname contains "git")

**Issue:** Self-hosted Gitea URL is misidentified as GitHub or another platform.

**Root cause:** Karandash's URL detection checks hostnames. A hostname like "github-private.company.com" hosting Gitea could be misidentified.

**Fix:** Use explicit platform flag:

```bash
karandash validate \
  --platform gitea \
  --project "owner/repo" \
  --mr-id 42
```

---

## Rate Limiting Considerations

### Self-Hosted Instance Rate Limits

**Issue:** Validation hits rate limits even on self-hosted Gitea.

**Root cause:** Some self-hosted Gitea instances have rate limiting enabled in **Admin > Settings > Security > Rate limiting**.

**Check instance configuration:**
- Ask your Gitea administrator about rate limits
- Test with: `curl -H "Authorization: token $GITEA_TOKEN" https://gitea.example.com/api/v1/user`

### Codeberg Rate Limits

**Issue:** Codeberg.org validation fails with "429 Too Many Requests."

**Root cause:** Codeberg applies rate limits (default: 300 requests/minute per IP).

**Mitigation:**
- Serialize validations instead of running in parallel
- Implement exponential backoff
- Contact Codeberg support for rate limit exceptions

---

## Platform-Specific Limitations

### Limited to PR Metadata

**Issue:** Karandash cannot trigger CI pipelines or set review status.

**Root cause:** Gitea PR API is read-only for Karandash. Pipeline triggers and review actions are not implemented.

**Expected behavior:** Karandash validates PR data only. Use Gitea Actions or Woodpecker CI for automated workflows.

### No Draft PR Support in Older Gitea

**Issue:** "Draft: " title prefix validation fails on Gitea < 1.17.

**Root cause:** Draft PR support was added in Gitea 1.17. Older versions don't recognize draft status.

**Fix:** Upgrade Gitea to 1.17+ or disable draft validation rules.

### Forgejo-Specific "Published" State

**Issue:** Forgejo PRs show unexpected state compared to Gitea PRs.

**Root cause:** Forgejo adds draft state management different from Gitea. Some Forgejo versions have "published" vs "draft" semantics.

**Expected behavior:** Karandash handles both Gitea and Forgejo draft states, but behavior may vary slightly.

---

## API Differences from Other Platforms

### Limited Review Information

**Issue:** Karandash cannot identify specific reviewers or detailed review states.

**Root cause:** Gitea's PR review API provides limited metadata compared to GitHub or GitLab.

**Expected behavior:** Karandash validates approval count only. For detailed reviews, check the Gitea UI.

### No Support for Status Checks

**Issue:** Karandash cannot read or set status checks/commit statuses.

**Root cause:** Status check integration is not implemented for Gitea.

**Workaround:** Use Gitea Actions for status checks and commit statuses.

### File Change Detection Variance

**Issue:** File counts or change stats differ from what the Gitea UI shows.

**Root cause:** Gitea's PR files API may calculate stats differently than the web UI.

**Expected behavior:** Minor discrepancies are normal. Use Gitea UI for authoritative file change info.

---

## Codeberg-Specific Considerations

### Personal Access Token Creation on Codeberg

**Issue:** Standard Gitea token creation instructions don't work on Codeberg.

**Root cause:** Codeberg uses Forgejo with slightly different UI paths.

**Fix:**
1. Log in to Codeberg.org
2. Go to **Settings > Applications**
3. Create an access token with `repo` scope
4. Copy the token

### Rate Limits on Codeberg

**Issue:** Validation hits rate limits faster on Codeberg than self-hosted Gitea.

**Root cause:** Codeberg (as a public service) enforces strict rate limits: 300 requests/minute per IP.

**Mitigation:**
- Serialize validations, or
- Use separate IPs if possible, or
- Wait between validations (implement delays)

---

## CI/CD Integration Edge Cases

### Gitea Actions Context Variables

**Issue:** `github.repository` variable is unavailable in Gitea Actions.

**Root cause:** Gitea Actions provides GitHub Actions-compatible context, but some variables may be unavailable.

**Workaround:**
```yaml
- run: |
    karandash validate \
      --platform gitea \
      --project "${{ github.repository }}" \
      --mr-id "${{ github.event.pull_request.number }}"
```

If `github.repository` is empty, manually set it:
```yaml
env:
  GITEA_PROJECT: "owner/repo"
```

### Woodpecker CI Secrets in Gitea

**Issue:** Woodpecker secrets are not passed to Karandash properly.

**Root cause:** Woodpecker's secret handling requires explicit `secrets:` declaration in the step.

**Fix:**
```yaml
pipeline:
  karandash:
    image: rinekri/karandash:latest
    secrets: [gitea_token]  # Explicitly declare
    environment:
      GITEA_TOKEN: ${GITEA_TOKEN}  # Reference secret
    commands:
      - karandash validate
```
