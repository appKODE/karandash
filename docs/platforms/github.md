# GitHub Platform Guide

## Overview

Karandash provides **full support** for GitHub, including github.com and GitHub Enterprise Server.

**Support level:** Full

**Capabilities:**
- Pull request fetching (title, commits, files, reviews)
- Comment posting on PRs
- Auto-fix mutations (add/remove labels, set draft status)
- GitHub Actions auto-detection
- SARIF upload for Code Scanning integration
- GitHub Actions composite action included

**API used:** GitHub REST API v3

---

## Authentication Setup

### Option 1: Personal Access Token (PAT)

Use a PAT for local development or non-Actions CI systems.

**Create a token:**

1. Go to **Settings > Developer settings > Personal access tokens > Tokens (classic)**
2. Click **Generate new token (classic)**
3. Set a descriptive name (e.g., "karandash-validation")
4. Select the following scopes:
   - `repo` -- full repository access (required for private repos)
   - For public repos only, `public_repo` is sufficient
5. Click **Generate token**
6. Copy the token immediately (it will not be shown again)

**Fine-grained tokens** (recommended for tighter security):

1. Go to **Settings > Developer settings > Personal access tokens > Fine-grained tokens**
2. Click **Generate new token**
3. Set the resource owner and repository access
4. Under **Repository permissions**, grant:
   - **Pull requests**: Read (required) or Read and write (for comment posting / auto-fix)
   - **Contents**: Read (required for file change validation)
   - **Issues**: Read and write (required for comment posting -- GitHub uses Issues API for PR comments)
5. Click **Generate token**

### Option 2: GitHub Actions Token (GITHUB_TOKEN)

In GitHub Actions, the built-in `GITHUB_TOKEN` is automatically available.
No manual token creation is needed.

**Default permissions for `GITHUB_TOKEN`:**
- `contents: read`
- `pull-requests: read`

For comment posting or SARIF upload, add permissions to your workflow:

```yaml
permissions:
  contents: read
  pull-requests: write      # For comment posting
  security-events: write    # For SARIF upload
```

### Option 3: GitHub App

Run Karandash as a GitHub App installation instead of as a user: comments are posted by the app's bot account,
and the token is scoped to the repositories the app is installed on.

1. Create the app under **Settings > Developer settings > GitHub Apps** with these repository permissions:
   **Pull requests: Read and write**, **Contents: Read**, **Issues: Read and write**, **Checks: Read**,
   **Commit statuses: Read**.
2. Generate a private key on the app's page (a `.pem` file) and note the **App ID**.
3. Install the app on the repositories and note the installation ID (the number at the end of the installation's
   settings URL, `.../settings/installations/<id>`).

```yaml
# .karandash/config.yaml
platforms:
  github:
    auth:
      type: app
      appId: "123456"
      installationId: "7890123"
      privateKey: { file: "github-app.pem" }   # relative to this config file
      # or: privateKey: "${GITHUB_APP_PRIVATE_KEY}"  (the PEM text; literal \n escapes are accepted)
```

How it works:

- Karandash signs a short-lived JWT with the private key (RS256; `iat` 60 seconds in the past for clock drift,
  `exp` 9 minutes ahead, `iss` the App ID) and exchanges it at
  `POST {url}/app/installations/{installationId}/access_tokens` for an installation token.
- The installation token is used for every API call and reused until 5 minutes before its `expires_at`. It is
  exchanged again then, and once more after any 401 response, so long runs and watch mode keep working.
- `url` works as for tokens, so GitHub Enterprise Server is `url: "https://github.example.com/api/v3"`; the URL
  passes the same SSRF check.
- `token` and `GITHUB_TOKEN` are not needed (and are ignored) when `auth.type` is `app`. `auth.type: token` is the
  default.
- The key and the tokens are never logged. `doctor` checks the installation with `GET /installation/repositories`,
  since an installation token cannot read `/user`.

Errors: a missing `appId`, a non-numeric `installationId` or a missing `privateKey` leaves GitHub unconfigured with
a warning naming the key. A key that does not match the app fails the exchange with GitHub's
`A JSON web token could not be decoded` (401).

### Environment Variable Setup

```bash
# Required
export GITHUB_TOKEN="ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

# Optional: GitHub Enterprise Server
export GITHUB_API_URL="https://github.example.com/api/v3"
```

### Testing Authentication

```bash
export GITHUB_TOKEN="ghp_your_token_here"
karandash validate --pr https://github.com/owner/repo/pull/1
```

A successful run prints validation results. An authentication failure produces:

```
Authentication failed for github provider (HTTP 401).
Your token is invalid, expired, or revoked.
Hint: configure token via GITHUB_TOKEN.
Verify your token at: Settings > Developer settings > Personal access tokens.
```

---

## Configuration

### Environment Variables (Zero-Config)

No configuration file needed. Set the token and validate:

```bash
export GITHUB_TOKEN="ghp_xxxxxxxxxxxx"
karandash validate --pr https://github.com/myorg/myrepo/pull/42
```

### Configuration File (.karandash/config.yaml)

```yaml
platforms:
  github:
    token: "${GITHUB_TOKEN}"
    # Default: https://api.github.com
    # For GitHub Enterprise; the older key `baseUrl` is an alias of `url` (if both are set, `url` wins and a warning names it):
    url: "https://github.example.com/api/v3"

rules:
  title-format:
    pattern: "^(feat|fix|chore|docs|refactor|test|ci): .+"
    max-length: 72
  commit-messages:
    format: "conventional"
  draft:
    block-draft: fail
  size-limits:
    max-files: 30
    max-lines: 500
  approval:
    min-approvals: 1

reporters:
  console: true
  markdown: true   # Post results as PR comment
```

### URL Formats

```
# github.com
https://github.com/{owner}/{repo}/pull/{number}

# GitHub Enterprise Server
https://github.example.com/{owner}/{repo}/pull/{number}
```

**Project identification:** `owner/repo` format (e.g., `myorg/myrepo`)

### GitHub Enterprise Server

For GHE, set the API base URL:

```yaml
# .karandash/config.yaml
platforms:
  github:
    token: "${GITHUB_TOKEN}"
    url: "https://github.example.com/api/v3"
```

Or via environment variable:

```bash
export GITHUB_API_URL="https://github.example.com/api/v3"
export GITHUB_TOKEN="ghp_xxxxxxxxxxxx"
```

Karandash auto-detects the API URL from PR URLs. If you pass:

```bash
karandash validate --pr https://github.example.com/org/repo/pull/5
```

The base URL is automatically set to `https://github.example.com/api/v3`.

---

## Features and Limitations

### What Works

| Feature | Status | Notes |
|---------|--------|-------|
| PR fetching | Yes | Title, body, commits, files, reviews |
| Commit validation | Yes | All commits in the PR |
| File change detection | Yes | Full diff stats |
| Review/approval checking | Yes | Fetches review states |
| Comment posting | Yes | Uses Issues API for PR comments |
| Comment updating | Yes | Finds and updates existing Karandash comments |
| Inline comments | Yes | Posts review comments on PR diffs |
| Label management (--fix) | Yes | Add and remove labels via API |
| Draft status (--fix) | Yes | Toggle draft via `draft` field |
| Title update (--fix) | Yes | Updates PR title |
| Description update (--fix) | Yes | Updates PR body |
| Approve / unapprove | Yes | Creates or dismisses approval reviews |
| SARIF upload | Yes | GitHub Code Scanning integration |
| CI auto-detection | Yes | Detects GITHUB_ACTIONS, GITHUB_REPOSITORY, GITHUB_REF |

### What Does Not Work

| Feature | Reason |
|---------|--------|
| Check runs creation | Karandash reports via SARIF, not Checks API |
| Branch protection bypass | Karandash validates but does not enforce |

### Platform-Specific Considerations

- **Rate limiting:** GitHub API allows 5,000 requests/hour for authenticated users, 60 for unauthenticated. Karandash makes 4 API calls per PR validation (PR, commits, files, reviews).
- **GITHUB_TOKEN in Actions:** Scoped to the current repository. Cross-repo validation requires a PAT.
- **Draft PRs:** GitHub uses the `draft` boolean field. Karandash reads this directly.
- **Fork PRs:** `GITHUB_TOKEN` in Actions has read-only access for PRs from forks. Comment posting will not work for fork PRs unless you use a PAT.

---

## Common Use Cases

### GitHub Actions Integration (Recommended)

Use the built-in composite action:

```yaml
# .github/workflows/karandash.yml
name: Karandash Validation
on:
  pull_request:
    types: [opened, synchronize, reopened]

jobs:
  validate:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      pull-requests: write
      security-events: write
    steps:
      - uses: actions/checkout@v4
      - uses: appKODE/karandash@v1.0.1
        with:
          comment: 'true'
          sarif: 'true'
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

**Action inputs:**

| Input | Default | Description |
|-------|---------|-------------|
| `token` | `github.token` | Token the CLI reads the pull request with (exported as `GITHUB_TOKEN`) |
| `version` | `latest` | Release tag to download from [GitHub Releases](https://github.com/appKODE/karandash/releases), e.g. `v1.0.0` |
| `config` | (auto-discover) | Path to config file (`config-path` is accepted as an alias) |
| `format` | `console` | Output format printed to the log: console, json, sarif, rdjsonl, rdjson |
| `sarif` | `false` | Write `karandash-results.sarif` and upload it to Code Scanning |
| `reviewdog-format` | (none) | Also write `karandash-results.<format>` (`rdjsonl` or `rdjson`) for reviewdog |
| `comment` | `false` | Post results as PR comment |

**Requirements:** the action downloads `karandash.jar` and `SHA256SUMS` from the GitHub release anonymously and stops when the checksum does not match, so the runner must reach `github.com` (and `api.github.com` when `version` is `latest`; the `token` input raises its rate limit). The runner also needs `jq`; `tools/action/install.sh` stops with an error when it is missing (it is preinstalled on GitHub-hosted runners). One validation run writes the JSON, SARIF and reviewdog files together; with a `.kts` config each extra file costs another run.

**Action outputs:**

| Output | Description |
|--------|-------------|
| `passed` | `true` or `false` |
| `findings-count` | Number of findings (length of `findings` in `--format json`) |
| `sarif-file` | Path of the SARIF file when `sarif` is enabled |
| `reviewdog-enabled` | `true` when `reviewdog-format` is set |
| `reviewdog-file` | Path of the reviewdog file when `reviewdog-format` is set |

### GitHub Actions with Docker

```yaml
name: Karandash Validation
on: pull_request

jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: docker://rinekri/karandash:v1.0.1
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        with:
          args: karandash validate
```

### Local PR Testing

```bash
export GITHUB_TOKEN="ghp_xxxxxxxxxxxx"

# Validate a specific PR
karandash validate --pr https://github.com/myorg/myrepo/pull/42

# With custom config
karandash validate --pr https://github.com/myorg/myrepo/pull/42 \
  --config .karandash/config.yaml

# JSON output
karandash validate --pr https://github.com/myorg/myrepo/pull/42 \
  --format json
```

### SARIF Code Scanning

Generate SARIF output and upload to GitHub Code Scanning:

```yaml
steps:
  - uses: actions/checkout@v4
  - uses: actions/setup-java@v4
    with:
      distribution: temurin
      java-version: '17'
  - run: |
      ./karandash validate \
        --format sarif \
        --output karandash-results.sarif
    env:
      GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
  - uses: github/codeql-action/upload-sarif@v3
    if: always()
    with:
      sarif_file: karandash-results.sarif
      category: karandash
```

Results appear in the repository's **Security > Code scanning** tab.

### Auto-Fix Support

GitHub supports auto-fix via `--fix` for safe, mechanical fixes:

```bash
# Add missing labels
karandash validate --pr https://github.com/org/repo/pull/5 --fix

# Supported fixes:
# - Add required labels
# - Remove forbidden labels
# - Toggle draft status
```

Auto-fix requires a token with `pull-requests: write` permission.

---

## Troubleshooting

### "Bad credentials" or 401 Unauthorized

```
Error: 401 Unauthorized
```

**Causes:**
- Token is expired or revoked
- Token does not have required scopes
- Wrong token for the repository

**Fix:**
1. Verify the token works: `curl -H "Authorization: token $GITHUB_TOKEN" https://api.github.com/user`
2. Check token scopes match the requirements above
3. For fine-grained tokens, confirm repository access is granted

### "Resource not accessible by integration" (403)

```
Error: 403 Forbidden - Resource not accessible by integration
```

**Cause:** `GITHUB_TOKEN` in Actions lacks required permissions.

**Fix:** Add explicit permissions to your workflow:

```yaml
permissions:
  contents: read
  pull-requests: write
```

### Rate Limiting (403 with rate limit headers)

```
Error: API rate limit exceeded
```

**Fix:**
- Karandash makes ~4 API calls per validation. At 5,000/hour, this supports ~1,250 validations/hour.
- If you hit limits, check for other automation sharing the token.
- Use a dedicated PAT for Karandash if needed.

### PR From Fork -- Comment Posting Fails

**Cause:** `GITHUB_TOKEN` for fork PRs is read-only by default.

**Fix:** Use a PAT stored as a repository secret, or use `pull_request_target` trigger (use with caution -- it runs in the context of the base branch):

```yaml
on:
  pull_request_target:
    types: [opened, synchronize]
```

### GitHub Enterprise -- SSL Certificate Errors

**Cause:** Self-signed or internal CA certificates.

**Fix:** Set the Java trust store:

```bash
java -Djavax.net.ssl.trustStore=/path/to/truststore.jks \
  -jar karandash.jar validate
```

Or for Docker:

```bash
docker run -v /path/to/ca-cert.pem:/usr/local/share/ca-certificates/ca.crt \
  -e GITHUB_TOKEN=$GITHUB_TOKEN \
  rinekri/karandash:v1.0.1 karandash validate
```

---

## Edge Cases and Limitations

### Token Scope Conflicts

**Issue:** Fine-grained tokens with restricted repository access fail validation.

**Root cause:** Fine-grained tokens are scoped to specific repositories. If the token was created for one repo but you validate a different repo, access is denied.

**Workaround:**
- Generate separate fine-grained tokens for each repository, or
- Use a personal access token (classic) with broader `repo` scope, or
- Verify the token repository scope matches the PR URL

### Merged PR Validation

**Issue:** Validating an already-merged PR returns success but data may be stale.

**Root cause:** GitHub keeps merged PRs accessible but closes the PR object. Comments, reviews, and file changes remain available but may not reflect the final merge state.

**Expected behavior:** Karandash validates the PR as it exists at the point of the validation call, not at the time of merge.

### Private Fork Protection

**Issue:** Comment posting fails on PRs from private forks.

**Root cause:** `GITHUB_TOKEN` in Actions has read-only access for PRs from forks, even if the base repo is public. GitHub restricts write operations on fork PRs for security.

**Workaround:**
- Use `pull_request_target` trigger with a PAT (use with caution), or
- Require fork authors to sync via bot (e.g., Dependabot), or
- Post comments only for non-fork PRs with conditional logic

### Rate Limit on High-Volume Validation

**Issue:** "API rate limit exceeded" after many validations in quick succession.

**Root cause:** GitHub's 5,000 request/hour limit is shared across all GitHub Actions and user tokens. Karandash makes 4 API calls per validation (PR, commits, files, reviews).

**Calculation:** ~1,250 validations/hour is sustainable. At 200 PRs/day, no issue. At 2,000 PRs/day, rate limits are likely.

**Workaround:**
- Add exponential backoff retry logic in your CI/CD, or
- Use a dedicated service account token, or
- Stagger validation triggers (avoid validating on every push)

### Draft PR with Auto-Fix Conflicts

**Issue:** Using `--fix` to un-draft a PR fails with permissions error.

**Root cause:** The token's `pull-requests: write` scope is insufficient for draft toggling. Draft status requires `pull-requests: write` permission on the specific PR.

**Fix:** Make sure your token has `pull-requests: write` and validate the token works:

```bash
curl -H "Authorization: token $GITHUB_TOKEN" \
  -X PATCH https://api.github.com/repos/owner/repo/pulls/123 \
  -d '{"draft": false}'
```

### GITHUB_TOKEN Scoped to Base Branch

**Issue:** Validating PRs across different branches or repositories fails.

**Root cause:** `GITHUB_TOKEN` in Actions is scoped to the current repository and workflow context. Cross-repo validation is not supported.

**Workaround:** Use a PAT stored as a repository secret instead of `GITHUB_TOKEN` for cross-repo validation.

### Large PR Validation Timeout

**Issue:** Validation times out or freezes on PRs with >100 commits or >500 file changes.

**Root cause:** GitHub API pagination defaults to 30 items per page. Large PRs require multiple API calls, increasing latency.

**Expected behavior:** Karandash fetches all data and may take 5-30 seconds for very large PRs.

**Workaround:**
- Split large PRs into smaller ones for faster validation, or
- Increase timeout settings in your CI/CD if available

### API Rate Limit Headers

**Issue:** Response headers show X-RateLimit-Remaining is 0, but no error is returned.

**Root cause:** GitHub returns 403 with rate-limit headers when the limit is hit. Karandash handles this gracefully with a clear error message.

**Check remaining quota:**

```bash
curl -H "Authorization: token $GITHUB_TOKEN" https://api.github.com/rate_limit
```

---

## Examples

### Minimal GitHub Actions Workflow

```yaml
name: PR Validation
on: pull_request
jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: docker://rinekri/karandash:v1.0.1
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        with:
          args: karandash validate
```

### Full GitHub Actions Workflow with SARIF and Comments

```yaml
name: Karandash PR Validation
on:
  pull_request:
    types: [opened, synchronize, reopened, ready_for_review]

permissions:
  contents: read
  pull-requests: write
  security-events: write

jobs:
  validate:
    runs-on: ubuntu-latest
    if: github.event.pull_request.draft == false
    steps:
      - uses: actions/checkout@v4

      - uses: appKODE/karandash@v1.0.1
        with:
          comment: 'true'
          sarif: 'true'
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

### Configuration for GitHub-Specific Workflows

```yaml
# .karandash/config.yaml -- GitHub-optimized
platforms:
  github:
    token: "${GITHUB_TOKEN}"

rules:
  title-format:
    pattern: "^(feat|fix|chore|docs|refactor|test|ci|build|perf): .+"
    max-length: 72
  commit-messages:
    format: "conventional"
    max-length: 100
  branch-naming:
    pattern: "^(feature|bugfix|hotfix|release|chore)/[a-z0-9-]+$"
  draft:
    block-draft: fail
  size-limits:
    max-files: 30
    max-lines: 500
    max-commits: 20
  approval:
    min-approvals: 1
  labels:
    required-labels:
      - "status: needs-review"
    forbidden-labels:
      - "status: do-not-merge"
      - "wip"
  security:
    scan-for-secrets: true

reporters:
  console: true
  markdown: true
```

### GitHub Enterprise Configuration

```yaml
# .karandash/config.yaml -- GitHub Enterprise
platforms:
  github:
    token: "${GITHUB_TOKEN}"
    url: "https://github.corp.example.com/api/v3"

rules:
  title-format:
    pattern: "^\\[JIRA-\\d+\\] .+"
  jira-ticket:
    project-keys:
      - "PROJ"
      - "TEAM"
  approval:
    min-approvals: 2

reporters:
  console: true
  markdown: true
```
