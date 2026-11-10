# GitLab Platform Guide

## Overview

Karandash provides **full support** for GitLab, including gitlab.com and self-hosted GitLab instances.

**Support level:** Full

**Capabilities:**
- Merge request fetching (title, commits, changes/diffs)
- Comment posting on MRs (notes)
- Auto-fix mutations (add/remove labels, set draft status)
- GitLab CI auto-detection
- SARIF output for GitLab SAST integration
- CI/CD job token support

**API used:** GitLab REST API v4

---

## Authentication Setup

### Option 1: Personal Access Token

Use a PAT for local development or external CI systems.

**Create a token:**

1. Go to **User Settings > Access Tokens** (or **Preferences > Access Tokens** in newer GitLab versions)
2. Click **Add new token**
3. Set a name (e.g., "karandash-validation")
4. Set an expiration date
5. Select scopes:
   - `read_api` -- read-only access (sufficient for validation without comment posting)
   - `api` -- full API access (required for comment posting and auto-fix)
6. Click **Create personal access token**
7. Copy the token immediately

**Project access tokens** (recommended for CI/CD):

1. Go to **Project > Settings > Access Tokens**
2. Create a token with `api` scope and `Reporter` role (minimum)
3. Use this token in CI/CD variables

### Option 2: GitLab CI Job Token (CI_JOB_TOKEN)

In GitLab CI, the built-in `CI_JOB_TOKEN` is automatically available.
It has limited permissions scoped to the current project. Karandash uses it only when neither `token` nor
`GITLAB_TOKEN` is set, and sends it in the `JOB-TOKEN` header (GitLab rejects a job token in `PRIVATE-TOKEN`).

**CI_JOB_TOKEN permissions:**
- Read access to the project
- Read access to merge request metadata
- Cannot post comments (use a project access token for that)

**Token Access for projects using Karandash:**

When a project uses the Karandash Docker image or CI templates via `CI_JOB_TOKEN`, the **Karandash repository** must allow that project's job token to access its resources (container registry, CI includes, etc.). By default, GitLab restricts cross-project `CI_JOB_TOKEN` access.

To configure this in the **Karandash repository**:

1. Go to **Settings > CI/CD > Token Access**
2. Under "Allow CI job tokens from the following projects", add the project(s) that will use Karandash
3. Those projects' `CI_JOB_TOKEN` can now pull images and access CI resources from the Karandash repo

Alternatively, disable **"Limit access to this project"** in the Karandash repo to allow any project's job token to access it (less secure, not recommended for production).

Without this configuration, projects using Karandash will fail with `403 Forbidden` when their `CI_JOB_TOKEN` tries to access Karandash resources.

For comment posting in CI, store a project access token as a CI/CD variable:

1. Go to **Project > Settings > CI/CD > Variables**
2. Add `GITLAB_TOKEN` with a project access token value
3. Mark as **Masked** and optionally **Protected**

### Environment Variable Setup

```bash
# Required
export GITLAB_TOKEN="glpat-xxxxxxxxxxxxxxxxxxxx"

# Optional: Self-hosted GitLab
export CI_SERVER_URL="https://gitlab.example.com"

# Optional: User token for approve/unapprove (PAT with api scope)
# Required when GITLAB_TOKEN is a project access token or CI_JOB_TOKEN,
# because GitLab approve/unapprove API requires a user-scoped token.
export GITLAB_APPROVE_TOKEN="glpat-yyyyyyyyyyyyyyyyyyyy"
```

In GitLab CI, the following variables are automatically available:
- `CI_JOB_TOKEN` -- job-scoped token
- `CI_SERVER_URL` -- GitLab instance URL
- `CI_PROJECT_PATH` -- project path (e.g., `group/project`)
- `CI_MERGE_REQUEST_IID` -- merge request internal ID
- `CI_API_V4_URL` -- API v4 base URL

### Testing Authentication

```bash
export GITLAB_TOKEN="glpat-xxxxxxxxxxxxxxxxxxxx"
karandash validate --pr https://gitlab.com/mygroup/myproject/-/merge_requests/1
```

A successful run prints validation results. An authentication failure produces:

```
Authentication failed for gitlab provider (HTTP 401).
Your token is invalid, expired, or revoked.
Hint: configure token via GITLAB_TOKEN or CI_JOB_TOKEN.
Verify your token at: Settings > Access Tokens.
```

---

## Configuration

### Environment Variables (Zero-Config)

```bash
export GITLAB_TOKEN="glpat-xxxxxxxxxxxxxxxxxxxx"
karandash validate --pr https://gitlab.com/mygroup/myproject/-/merge_requests/42
```

Karandash uses `GITLAB_TOKEN` first, then falls back to `CI_JOB_TOKEN`. The base URL defaults to `https://gitlab.com` unless `CI_SERVER_URL` is set.

### Configuration File (.karandash/config.yaml)

```yaml
platforms:
  gitlab:
    url: "https://gitlab.com"
    token: "${GITLAB_TOKEN}"
    # Optional: user PAT for approve/unapprove (falls back to the GITLAB_APPROVE_TOKEN env var)
    approve-token: "${GITLAB_APPROVE_TOKEN}"
    # Optional: how stale inline comments are deleted. `discussion` (default) deletes through the discussion API,
    # `note` deletes the marked MR notes directly.
    delete-inline-strategy: discussion

rules:
  title-format:
    pattern: "^(feat|fix|chore|docs|refactor): .+"
    min-length: 10
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
  markdown: true   # Post results as MR note
```

### URL Formats

```
# gitlab.com
https://gitlab.com/{group}/{project}/-/merge_requests/{iid}

# Self-hosted
https://gitlab.example.com/{group}/{project}/-/merge_requests/{iid}

# Nested groups
https://gitlab.com/{group}/{subgroup}/{project}/-/merge_requests/{iid}
```

**Project identification:** Full project path (e.g., `mygroup/myproject` or `mygroup/subgroup/myproject`)

### Self-Hosted GitLab

```yaml
# .karandash/config.yaml
platforms:
  gitlab:
    url: "https://gitlab.example.com"
    token: "${GITLAB_TOKEN}"
```

Or via environment variables:

```bash
export CI_SERVER_URL="https://gitlab.example.com"
export GITLAB_TOKEN="glpat-xxxxxxxxxxxxxxxxxxxx"
```

---

## Features and Limitations

### What Works

| Feature | Status | Notes |
|---------|--------|-------|
| MR fetching | Yes | Title, description, source/target branches |
| Commit validation | Yes | All commits in the MR |
| Change detection | Yes | Full diffs via changes API |
| Approval checking | Yes | Approval count from MR metadata |
| Comment posting | Yes | Posts MR notes via Notes API |
| Comment updating | Yes | Finds and updates existing Karandash notes |
| Inline comments | Yes | Posts diff discussions via GitLab discussions API |
| Resolve inline comments | Yes | Resolves marked diff discussions |
| Label management (--fix) | Yes | Reads current labels, merges, and updates |
| Draft status (--fix) | Yes | Toggles "Draft: " title prefix |
| Title update (--fix) | Yes | Updates MR title |
| Description update (--fix) | Yes | Updates MR description |
| Approve / unapprove | Yes | Requires a user-scoped token (`GITLAB_APPROVE_TOKEN`) |
| CI auto-detection | Yes | Detects GITLAB_CI, CI_PROJECT_PATH, CI_MERGE_REQUEST_IID |
| SARIF output | Yes | Compatible with GitLab SAST artifacts |

### What Does Not Work

| Feature | Reason |
|---------|--------|
| Pipeline triggering | Karandash reports results, does not trigger pipelines |

### Platform-Specific Considerations

- **CI_JOB_TOKEN limitations:** Cannot post comments or modify MRs. Use a project access token stored as a CI/CD variable for full functionality.
- **Rate limiting:** GitLab.com allows 300 requests/minute for authenticated users. Karandash makes 3 API calls per MR validation (MR, commits, changes).
- **Draft MRs:** GitLab uses the "Draft: " title prefix convention. Karandash adds/removes this prefix when toggling draft status via `--fix`.
- **Nested groups:** Fully supported. The project path can include multiple group levels.
- **API version:** Karandash uses GitLab API v4. Minimum GitLab version: 12.0+.

---

## Common Use Cases

### GitLab CI Integration

**Minimal setup (zero-config with CI_JOB_TOKEN):**

```yaml
# .gitlab-ci.yml
karandash:
  stage: test
  image: rinekri/karandash:latest
  rules:
    - if: $CI_MERGE_REQUEST_IID
  script:
    - karandash validate
  variables:
    GITLAB_TOKEN: $CI_JOB_TOKEN
```

**With comment posting (requires project access token):**

```yaml
# .gitlab-ci.yml
karandash:
  stage: test
  image: rinekri/karandash:latest
  rules:
    - if: $CI_MERGE_REQUEST_IID
  script:
    - karandash validate
  variables:
    GITLAB_TOKEN: $KARANDASH_TOKEN  # Project access token stored in CI/CD variables
```

**With SARIF output for GitLab SAST:**

```yaml
# .gitlab-ci.yml
karandash:
  stage: test
  image: rinekri/karandash:latest
  rules:
    - if: $CI_MERGE_REQUEST_IID
  script:
    - karandash validate --format sarif --output gl-sast-report.json
  artifacts:
    reports:
      sast: gl-sast-report.json
    expire_in: 1 week
  variables:
    GITLAB_TOKEN: $CI_JOB_TOKEN
```

Results appear in the MR's **Security** widget.

### Local MR Testing

```bash
export GITLAB_TOKEN="glpat-xxxxxxxxxxxxxxxxxxxx"

# Validate a specific MR
karandash validate --pr https://gitlab.com/mygroup/myproject/-/merge_requests/42

# Self-hosted GitLab
karandash validate --pr https://gitlab.example.com/team/app/-/merge_requests/10

# With JSON output
karandash validate --pr https://gitlab.com/mygroup/myproject/-/merge_requests/42 \
  --format json

# With auto-fix
karandash validate --pr https://gitlab.com/mygroup/myproject/-/merge_requests/42 \
  --fix
```

### Config Inheritance Across Repos

Host a shared base config and extend it per-repository:

```yaml
# https://gitlab.com/mygroup/configs/-/raw/main/karandash-base.yaml
rules:
  title-format:
    pattern: "^(feat|fix|chore|docs): .+"
  commit-messages:
    format: "conventional"
  security:
    scan-for-secrets: true
```

```yaml
# .karandash/config.yaml in each repository
extends: "https://gitlab.com/mygroup/configs/-/raw/main/karandash-base.yaml"

platforms:
  gitlab:
    url: "https://gitlab.com"
    token: "${GITLAB_TOKEN}"

rules:
  approval:
    min-approvals: 2
```

### Auto-Fix Support

GitLab supports auto-fix via `--fix` for safe, mechanical fixes:

```bash
# Apply fixes
karandash validate --pr https://gitlab.com/org/repo/-/merge_requests/5 --fix

# Supported fixes:
# - Add required labels (reads current labels, merges, updates)
# - Remove forbidden labels
# - Toggle draft status (adds/removes "Draft: " title prefix)
```

Auto-fix requires a token with `api` scope.

---

## Troubleshooting

### "401 Unauthorized"

```
Error: 401 Unauthorized
```

**Causes:**
- Token is expired or revoked
- Token does not have required scopes
- CI_JOB_TOKEN used outside its project scope

**Fix:**
1. Verify the token: `curl --header "PRIVATE-TOKEN: $GITLAB_TOKEN" "https://gitlab.com/api/v4/user"`
2. Check token scopes (needs `read_api` minimum, `api` for comment posting)
3. If using CI_JOB_TOKEN, confirm the MR belongs to the same project

### "404 Not Found" on MR Fetch

```
Error: 404 Not Found
```

**Causes:**
- Wrong project path (check for typos, correct group nesting)
- Token does not have access to the project
- MR IID does not exist

**Fix:**
1. Verify the project path matches the URL: `group/subgroup/project`
2. Check that the token has access: `curl --header "PRIVATE-TOKEN: $GITLAB_TOKEN" "https://gitlab.com/api/v4/projects/group%2Fproject"`
3. URL-encode the project path when testing directly: `group%2Fsubgroup%2Fproject`

### CI_JOB_TOKEN Cannot Access Karandash Resources (403)

**Cause:** The project's `CI_JOB_TOKEN` is blocked from accessing the Karandash repository (container registry, CI templates). GitLab restricts cross-project job token access by default.

**Fix:**
1. In the **Karandash repository**, go to **Settings > CI/CD > Token Access**
2. Under "Allow CI job tokens from the following projects", add the project that needs access
3. Alternatively, disable "Limit access to this project" in the Karandash repo (less secure)

### CI_JOB_TOKEN Cannot Post Comments

**Cause:** CI_JOB_TOKEN has limited permissions and cannot create MR notes.

**Fix:**
1. Create a project access token with `api` scope
2. Store it as a CI/CD variable named `KARANDASH_TOKEN`
3. Use it in your pipeline:

```yaml
variables:
  GITLAB_TOKEN: $KARANDASH_TOKEN
```

### "404 Not Found" on Approve/Unapprove

```
Error: GitLab API error: HTTP 404 Not Found (POST /projects/.../merge_requests/.../approve)
```

**Cause:** GitLab's approve/unapprove API requires a **user-scoped token** (Personal Access Token or OAuth token with `api` scope). Project access tokens and `CI_JOB_TOKEN` cannot approve MRs - GitLab returns 404 (not 403) as a security measure.

**Fix:**
1. Create a Personal Access Token with `api` scope (User Settings > Access Tokens)
2. Set it as `GITLAB_APPROVE_TOKEN`:

```bash
export GITLAB_APPROVE_TOKEN="glpat-xxxxxxxxxxxxxxxxxxxx"
```

Or in config:

```yaml
platforms:
  gitlab:
    approve-token: "${GITLAB_APPROVE_TOKEN}"
```

The main `GITLAB_TOKEN` (project token) continues to be used for all other operations. The approve token is only used for approve/unapprove calls.

### Self-Hosted GitLab -- SSL Certificate Errors

**Fix for Docker:**

```bash
docker run \
  -v /path/to/ca-cert.pem:/usr/local/share/ca-certificates/ca.crt \
  -e GITLAB_TOKEN=$GITLAB_TOKEN \
  -e CI_SERVER_URL=https://gitlab.example.com \
  rinekri/karandash:latest karandash validate
```

**Fix for JAR:**

```bash
java -Djavax.net.ssl.trustStore=/path/to/truststore.jks \
  -jar karandash.jar validate
```

### Rate Limiting (429)

```
Error: 429 Too Many Requests
```

**Fix:**
- GitLab.com: 300 requests/minute. Karandash uses ~3 calls per MR.
- Self-hosted: Check your instance rate limits in **Admin > Settings > Network > User and IP rate limits**
- Avoid running Karandash on every push; trigger only on `merge_request_event`

---

## Edge Cases and Limitations

### CI_JOB_TOKEN Expiration

**Issue:** CI_JOB_TOKEN works in the pipeline job but fails when used outside the pipeline context.

**Root cause:** CI_JOB_TOKEN is only valid during the job execution. It expires after the job completes, typically within seconds.

**Workaround:**
- Only use CI_JOB_TOKEN during the pipeline job itself
- For comment posting and auto-fix, store a project access token in CI/CD variables instead

### Nested Group Path Resolution

**Issue:** Projects in deeply nested groups fail with "404 Not Found" when accessed via URL encoding.

**Root cause:** GitLab API requires URL encoding of forward slashes in project paths (e.g., `group%2Fsubgroup%2Fproject`), but Karandash auto-detects and handles this internally.

**Expected behavior:** Karandash correctly parses URLs like:

```
https://gitlab.com/team/backend/services/auth/-/merge_requests/42
```

**If issues persist:** Verify the project path exactly:

```bash
curl --header "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "https://gitlab.com/api/v4/projects/team%2Fbackend%2Fservices%2Fauth"
```

### Draft MR Prefix Edge Case

**Issue:** Using `--fix` to un-draft a MR with custom "Draft" text (not the default "Draft: ") does not remove the prefix.

**Root cause:** Karandash only recognizes the standard GitLab "Draft: " prefix. Custom draft indicators (e.g., "[DRAFT]", "WIP:") are not toggled.

**Workaround:**
- Use the standard GitLab draft feature instead of custom prefixes, or
- Manually set draft status in the MR UI before calling Karandash

### Approval State Cache

**Issue:** MR shows as approved in the UI but validation fails with "insufficient approvals."

**Root cause:** Karandash queries the MR API at validation time. If an approval was just added, there may be a brief replication delay in GitLab's cache.

**Expected behavior:** Wait a few seconds after approving and retry validation.

### Personal Access Token Scopes on Self-Hosted

**Issue:** A token with `api` scope still cannot post comments on self-hosted GitLab.

**Root cause:** Some self-hosted GitLab instances have additional permission restrictions or use custom authentication plugins.

**Fix:**
1. Verify the token user has at least Developer role on the project
2. Check **Project Settings > Members** for the token owner's permissions
3. Test the token directly: `curl --header "PRIVATE-TOKEN: $GITLAB_TOKEN" "https://gitlab.example.com/api/v4/projects/{id}/merge_requests/{iid}/notes"`

### Project Access Token Expiration

**Issue:** Project access tokens expire silently, causing validation to fail with "401 Unauthorized" without warning.

**Root cause:** Project access tokens have configurable expiration dates. No notification is sent when they expire.

**Fix:**
- Check token expiration in **Project Settings > Access Tokens**
- Set long expiration dates (1 year or more) or regenerate regularly
- Monitor CI/CD job logs for authentication failures

### Rate Limiting with Multiple Projects

**Issue:** Validating MRs from multiple projects hits rate limits faster.

**Root cause:** GitLab applies rate limits per IP, not per token. If you validate 300+ MRs across different projects within 1 minute from the same IP, the limit is hit.

**Mitigation:**
- Stagger MR validation across time, or
- Use multiple tokens from different IP addresses (if possible), or
- Request higher rate limits from GitLab.com support

### API Version Mismatch

**Issue:** Self-hosted GitLab instance predates API v4 (very old instances).

**Root cause:** Karandash uses GitLab API v4. Instances older than GitLab 12.0 (released 2019) use API v3 or earlier.

**Fix:** Upgrade your GitLab instance, or use Karandash with a supported GitLab version (12.0+).

### Large MR with Many Commits

**Issue:** Validation is slow or times out on MRs with >100 commits.

**Root cause:** Karandash fetches the full commit list for each MR. Very large commit histories require multiple API calls with pagination.

**Expected behavior:** Validation may take 10-30 seconds for very large MRs. This is normal.

**Optimization:** GitLab's API returns commits paginated (20 per page by default). Karandash handles pagination transparently.

### MR with Force-Pushed Updates

**Issue:** Validation shows outdated commit information after a force-push.

**Root cause:** After a force-push, the MR's commit list changes. Karandash queries the current state, so it will reflect the new commits.

**Expected behavior:** Run validation again after force-pushing to see updated results.

---

## Examples

### Minimal GitLab CI Pipeline

```yaml
karandash:
  stage: test
  image: rinekri/karandash:latest
  rules:
    - if: $CI_MERGE_REQUEST_IID
  script:
    - karandash validate
```

### Full GitLab CI Pipeline with Comments and SARIF

```yaml
stages:
  - validate

karandash:
  stage: validate
  image: rinekri/karandash:latest
  rules:
    - if: $CI_MERGE_REQUEST_IID
  script:
    - karandash validate --format sarif --output gl-sast-report.json
  artifacts:
    reports:
      sast: gl-sast-report.json
    expire_in: 1 week
  variables:
    GITLAB_TOKEN: $KARANDASH_TOKEN
  allow_failure: false
```

### GitLab-Optimized Configuration

```yaml
# .karandash/config.yaml
platforms:
  gitlab:
    url: "${CI_SERVER_URL:-https://gitlab.com}"
    token: "${GITLAB_TOKEN}"

rules:
  title-format:
    pattern: "^(feat|fix|chore|docs|refactor|test|ci): .+"
    min-length: 10
    max-length: 120
  commit-messages:
    format: "conventional"
    max-length: 100
  branch-naming:
    pattern: "^(feature|bugfix|hotfix|release)/[a-z0-9-]+$"
  draft:
    block-draft: fail
    wip-patterns:
      - "WIP"
      - "\\[WIP\\]"
      - "DRAFT"
  size-limits:
    max-files: 30
    max-lines: 500
    max-commits: 20
  approval:
    min-approvals: 2
  labels:
    required-labels:
      - "workflow::review"
    forbidden-labels:
      - "workflow::wip"
      - "do-not-merge"
  security:
    scan-for-secrets: true
    sensitive-files:
      - "**/.env"
      - "**/*.pem"
      - "**/*.key"

reporters:
  console: true
  markdown: true
```

### Self-Hosted GitLab Enterprise Configuration

```yaml
# .karandash/config.yaml
extends: "https://gitlab.corp.example.com/devops/configs/-/raw/main/base.yaml"

platforms:
  gitlab:
    url: "https://gitlab.corp.example.com"
    token: "${GITLAB_TOKEN}"

rules:
  title-format:
    pattern: "^\\[JIRA-\\d+\\] (feat|fix|chore): .+"
    require-ticket: true
  jira-ticket:
    project-keys:
      - "PROJ"
      - "INFRA"
  approval:
    min-approvals: 3
    required-approvers:
      - "tech-lead"
      - "security-team"

reporters:
  console: true
  markdown: true
```
