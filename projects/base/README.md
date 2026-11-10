# Karandash Base Config

Shared Karandash configuration for all Android and KMP projects at Acme.

## Architecture

```
base                    ← all projects (provider, rules, cache, AI)
  └── base-kmp          ← KMP projects: narrows android scope to android/**

Project configs:
  wallet     extends base-kmp
  navigator    extends base
  gridmon extends base
  telecom    extends base
  ledger    extends base
  coinbox    extends base
```

## Environment Variables

Set these in GitLab CI/CD per project (group-level vars for shared ones, project-level for project-specific).

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `KARANDASH_GITLAB_TOKEN` | ✅ | - | GitLab auth token for MR data fetching |
| `BIFROST_API_KEY` | ✅ | - | AI provider (Bifrost) API key |
| `BIFROST_BASE_URL` | ✅ | - | AI provider base URL |
| `KARANDASH_CONFIG` | ✅ | `.karandash` | Path to the project's `.karandash/` dir |
| `KARANDASH_JIRA_KEY` | ✅ | - | JIRA project key (e.g. `TEL`, `WAL`, `GRD`) |
| `KARANDASH_TASK_PATTERN` | ✅ | `[A-Z]+-\d+` | Regex for task IDs (e.g. `TEL-\d+`) |
| `KARANDASH_AI_MAX_TOKENS` | ✅ | `20000` | AI token budget per run |
| `KARANDASH_BASE_TOKEN` | ✅ (prod) | - | GitLab read-only token for this base config repo |

### Per-project values

| Project | `KARANDASH_CONFIG` | `KARANDASH_JIRA_KEY` | `KARANDASH_TASK_PATTERN` | `KARANDASH_AI_MAX_TOKENS` |
|---------|-------------------|---------------------|--------------------------|--------------------------|
| wallet | `projects/wallet/.karandash` | `WAL` | `WAL-\d+` | `30000` |
| navigator | `projects/maps/.karandash` | `NAV` | `NAV-\d+` | `30000` |
| gridmon | `projects/gridmon/.karandash` | `GRD` | `GRD-\d+` | `15000` |
| telecom | `projects/telecom/.karandash` | `TEL` | `TEL-\d+` | `20000` |
| ledger | `projects/ledger/.karandash` | `LDG` | `LDG-\d+` | `20000` |
| coinbox | `projects/coinbox/.karandash` | `CBX` | `CBX-\d+` | `10000` |

## MR Convention

All projects enforce the same title and CHANGELOG format:

```
# MR title format (JIRA ticket optional, platform tag required for KMP projects)
([PROJ-ID])?[android|ios] Description in English

# Examples
[android] Fix NPE on profile screen
[TEL-2247][android] Add device profile screen
[WAL-3953][ios] Fix login OTP timer

# Commit body (CHANGELOG line, description can be in Russian)
# Required only when MR title contains a JIRA ticket AND [android] tag
CHANGELOG: [PROJ-ID] Description
CHANGELOG: [TEL-2247] Добавить дополнительную apn во флоу.
```

Rules:
- `[android]` or `[ios]` is **required** in every MR title for KMP projects (base-kmp, wallet, maps), **lowercase only**
- Platform tag is **not required** for Android-only projects (telecom, gridmon, ledger, coinbox)
- JIRA ticket is **optional** in MR title
- CHANGELOG line is required only for Android changes (`[android]`) that have a JIRA ticket in the MR title
- The CHANGELOG body contains the task ID and description but does **not** include the platform tag
- iOS changes are exempt from CHANGELOG requirement
- MR titles, commit titles, and branch names must be in English
- Commit bodies and MR descriptions are not language-checked (Cyrillic allowed)

## Production Setup

### 1. Create the base config repo on GitLab

Create a GitLab repo (e.g. `acme/karandash-base-config`) and push this `base/` and `base-kmp/` directory.

### 2. Create a read-only project access token

In the base config repo: **Settings → Access Tokens** → create token with `read_repository` scope.
Set this token as `KARANDASH_BASE_TOKEN` in the GitLab group CI/CD variables (inherited by all projects).

### 3. Get the numeric project ID

Find it in **Settings → General** (e.g. `12345`). Use it in project configs below.

### 4. Each project's `.karandash/config.yaml`

**Android projects** (gridmon, telecom, ledger, coinbox):
```yaml
extends:
  url: "https://gitlab.example.com/api/v4/projects/12345/repository/files/.karandash%2Fconfig.yaml/raw?ref=main"
  auth:
    type: bearer-token
    token: "${KARANDASH_BASE_TOKEN}"
```

**KMP projects** (wallet):
```yaml
extends:
  url: "https://gitlab.example.com/api/v4/projects/12345/repository/files/.karandash-kmp%2Fconfig.yaml/raw?ref=main"
  auth:
    type: bearer-token
    token: "${KARANDASH_BASE_TOKEN}"
```

**Navigator project** (maps):
```yaml
extends:
  url: "https://gitlab.example.com/api/v4/projects/12345/repository/files/.karandash%2Fconfig.yaml/raw?ref=main"
  auth:
    type: bearer-token
    token: "${KARANDASH_BASE_TOKEN}"
```

### 5. Add AI support files (optional)

The base configs reference no prompt files. To give the AI reviewer project context, add the file to the
project's `.karandash/` and reference it from that project's `config.yaml`, e.g.
`extra-instructions: { file: "extra-instructions.md" }` or a scope's `instructions: { file: "scopes/android-instructions.md" }`.
A referenced file that is missing fails config loading (E0023), so commit it with the reference.

See the `projects/<name>/.karandash/` directories in this repo for reference content.

## Local Testing

```bash
# Test base config (generic scenarios)
cd karandash
./projects/base/tests/run-tests.sh

# Test a specific project
./projects/telecom/tests/run-tests.sh

# Manual dry-run (requires env vars set)
export KARANDASH_GITLAB_TOKEN=...
export KARANDASH_CONFIG=projects/telecom/.karandash
export KARANDASH_JIRA_KEY=TEL
export KARANDASH_TASK_PATTERN='TEL-\d+'
export KARANDASH_AI_MAX_TOKENS=20000
./gradlew :cli:jvmRun --args="validate --config projects/telecom/.karandash/config.yaml --mr-url <MR_URL>"

# Validate config inheritance chain
./gradlew :cli:jvmRun --args="doctor --config projects/wallet/.karandash/config.yaml"
```
