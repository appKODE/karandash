# Environment Variables Reference

All environment variables recognized by Karandash, organized by category.

`EnvVarDocDriftTest` (`cli` jvmTest) keeps this page in step with the code: every variable the production code reads
must have a row here, and every row must name a variable the code reads. A variable read by another tool (not by
Karandash) is marked *(external)*.

## Authentication Tokens

These variables provide API authentication for each supported platform. They are used as fallback when auth is not configured in the config file.

| Variable | Platform | Description |
|----------|----------|-------------|
| `GITHUB_TOKEN` | GitHub | Personal access token or `${{ secrets.GITHUB_TOKEN }}` in Actions |
| `GITLAB_TOKEN` | GitLab | Personal or project access token |
| `GITLAB_APPROVE_TOKEN` | GitLab | Token of a second user that approves the MR (`providers.gitlab.approveToken` wins) |
| `CI_JOB_TOKEN` | GitLab CI | Auto-injected job token in GitLab CI pipelines |
| `BITBUCKET_TOKEN` | Bitbucket | App password or OAuth access token |
| `BITBUCKET_ACCESS_TOKEN` | Bitbucket | Alternative token variable (Bitbucket Pipelines) |
| `BITBUCKET_USERNAME` | Bitbucket | Username for an app password (sends HTTP Basic `username:token`) |
| `AZURE_DEVOPS_TOKEN` | Azure DevOps | Personal access token |
| `SYSTEM_ACCESSTOKEN` | Azure Pipelines | Auto-injected system token in Azure Pipelines |
| `GERRIT_TOKEN` | Gerrit | HTTP access token |
| `GERRIT_USERNAME` | Gerrit | Username sent with `GERRIT_TOKEN` (config `username` wins) |
| `GITEA_TOKEN` | Gitea | API access token; under Gitea/Forgejo Actions `GITHUB_TOKEN` is the fallback |
| `JENKINS_API_TOKEN` | Jenkins | API token for reading the build status (`JENKINS_TOKEN` is the fallback name) |
| `JENKINS_TOKEN` | Jenkins | Fallback name for `JENKINS_API_TOKEN` |
| `JENKINS_USER` | Jenkins | User sent with the Jenkins token (HTTP Basic); without it the build is read anonymously |
| `AWS_ACCESS_KEY_ID` | AWS | Static access key for CodeCommit and the `s3` remote cache |
| `AWS_SECRET_ACCESS_KEY` | AWS | Secret for `AWS_ACCESS_KEY_ID`; read only by the AWS credential chain, never interpolated (denylist) |
| `AWS_SESSION_TOKEN` | AWS | Optional session token for temporary credentials; denylisted like the secret |
| `AWS_PROFILE` | AWS | Profile read from the shared credentials/config files when no static keys are set (default `default`) |
| `AWS_SHARED_CREDENTIALS_FILE` | AWS | Shared credentials file (default `~/.aws/credentials`) |
| `AWS_CONFIG_FILE` | AWS | Shared config file (default `~/.aws/config`) |
| `AWS_REGION` | AWS | Region; `AWS_DEFAULT_REGION` is the fallback |
| `AWS_DEFAULT_REGION` | AWS | Fallback for `AWS_REGION` |
| `JIRA_TOKEN` | Jira enrichment | API token (config `token` wins); also the Confluence fallback |
| `JIRA_EMAIL` | Jira enrichment | Account email for Atlassian Cloud basic auth; also the Confluence fallback |
| `CONFLUENCE_TOKEN` | Confluence enrichment | API token (config wins, then `JIRA_TOKEN`) |
| `CONFLUENCE_EMAIL` | Confluence enrichment | Account email (config wins, then `JIRA_EMAIL`) |

**Token resolution order** (example for GitLab):

1. Config file: `providers.gitlab.token`
2. `GITLAB_TOKEN`
3. `CI_JOB_TOKEN` (in GitLab CI)

```bash
# GitHub Actions
export GITHUB_TOKEN="${{ secrets.GITHUB_TOKEN }}"

# GitLab CI (CI_JOB_TOKEN is auto-injected)
export GITLAB_TOKEN="glpat-xxxxxxxxxxxxxxxxxxxx"

# Bitbucket
export BITBUCKET_TOKEN="your-app-password"
```

---

## Karandash Overrides

Explicit overrides that take highest priority for platform detection.

| Variable | Type | Description |
|----------|------|-------------|
| `KARANDASH_PLATFORM` | `String` | Force platform: `github`, `gitlab`, `bitbucket`, `azure`, `gerrit`, `gitea` |
| `KARANDASH_PROJECT_ID` | `String` | Force project identifier (e.g., `owner/repo`) |
| `KARANDASH_MR_ID` | `String` | Force MR/PR identifier |
| `KARANDASH_BASE_URL` | `String` | Force API base URL |
| `KARANDASH_TOKEN` | `String` | Force authentication token |
| `KARANDASH_PLUGIN_DIR` | `String` | Replaces the default plugin directory (`~/.karandash/plugins/`). Precedence for plugin commands: `--plugin-dir` > manifest `plugins.directory` > this variable > default. When plugins load, the manifest directory is scanned in addition |
| `KARANDASH_LOG_LEVEL` | `String` | Log verbosity: `debug`/`trace` (= `--verbose`), `error` (= `--quiet`), `info`/`warn` (default). CLI flags take precedence |
| `KARANDASH_AI_TRACE` | `Boolean` | `1`/`true`/`yes` adds full AI responses and per-rule AI diff file lists to the verbose log (= `--trace`); in GitLab CI they fold into collapsed log sections |
| `KARANDASH_CONFIG_DIR` | `String` | Directory config auto-discovery starts from (walking up to the root) instead of the working directory. Ignored when a config path is given |
| `KARANDASH_CONFIG` | `String` | Config file path used when `--config` (`config validate --file`) is not given: `validate`, `validate-extends`, `explain`, `config describe`, `config validate` |
| `KARANDASH_STRICT` | `Boolean` | `true`/`1`/`yes`/`on` acts as `validate --strict` (warnings fail the run, exit 5) |
| `KARANDASH_HTTP_TIMEOUT` | `Duration` | HTTP request timeout for a `validate` run (`90`, `90s`, `2m`); wins over `timeouts.http`. An invalid or non-positive value is ignored with a warning |
| `KARANDASH_TLS_INSECURE_HOSTS` | `String` | Comma-separated hosts whose HTTPS certificates are not verified (e.g. a GitLab reached by IP). A client skips verification only when every host its credentials are keyed for is listed; a warning is logged |
| `KARANDASH_BRIDGE_JAR` | `String` | Native binary only: path of `karandash-jar-bridge.jar`, which runs JAR plugins in a JVM subprocess. Default: `~/.karandash/lib/karandash-jar-bridge.jar`, then the jar next to the executable |
| `KARANDASH_PLUGIN_CALL_TIMEOUT` | `Duration` | Limit on a single call into a JS/WASM plugin (`30s`, `2m`, `PT45S` or bare seconds; default `30s`). A call that runs over fails and its engine is abandoned |

When `KARANDASH_PLATFORM` is set, platform auto-detection is skipped and detection confidence is `HIGH` with source `"explicit"`.

```bash
# Explicit platform override
export KARANDASH_PLATFORM=github
export KARANDASH_PROJECT_ID=myorg/myrepo
export KARANDASH_MR_ID=42
export KARANDASH_TOKEN=ghp_xxxxxxxxxxxx
```

---

## CI Detection Variables

Karandash reads these variables to auto-detect the CI platform, project, and MR context.

### GitHub Actions

| Variable | Description |
|----------|-------------|
| `GITHUB_ACTIONS` | Set to `"true"` when running in GitHub Actions |
| `GITHUB_REPOSITORY` | Repository in `owner/repo` format |
| `GITHUB_REF` | Git ref (e.g., `refs/pull/123/merge`) |
| `GITHUB_TOKEN` | Authentication token |
| `GITHUB_API_URL` | API base URL (GitHub Enterprise); configured `url` wins |
| `GITHUB_SERVER_URL` | Web URL; builds the MR link in reports and is the Gitea URL fallback under Gitea/Forgejo Actions |
| `GITHUB_EVENT_PATH` | Path to the JSON event payload. When `GITHUB_REF` is not `refs/pull/N/merge`, the PR number is read from `pull_request.number` (else top-level `number`) |

### GitLab CI

| Variable | Description |
|----------|-------------|
| `GITLAB_CI` | Set to `"true"` when running in GitLab CI |
| `CI_PROJECT_ID` | Numeric project ID |
| `CI_PROJECT_PATH` | Project path in `group/project` format |
| `CI_MERGE_REQUEST_IID` | Merge request internal ID |
| `CI_JOB_TOKEN` | Auto-injected CI job token |
| `CI_SERVER_URL` | GitLab instance URL (default: `https://gitlab.com`) |
| `GITLAB_URL` | GitLab instance URL outside GitLab CI; used when `CI_SERVER_URL` is unset (configured `url` still wins) |
| `CI_API_V4_URL` | GitLab API v4 URL (default: `https://gitlab.com/api/v4`) |
| `CI_MERGE_REQUEST_PROJECT_URL` | Builds the MR link in reports when `CI_MERGE_REQUEST_IID` is the validated MR |
| `CI_PROJECT_URL` | Builds the MR link in `--diagnostic` output outside a merge request pipeline |
| `CI_COMMIT_SHA` | Head SHA recorded in `--diagnostic` output when the MR has no diff refs |

### Bitbucket Pipelines

| Variable | Description |
|----------|-------------|
| `BITBUCKET_BUILD_NUMBER` | Present when running in Bitbucket Pipelines |
| `BITBUCKET_REPO_FULL_NAME` | Repository in `owner/repo` format |
| `BITBUCKET_PR_ID` | Pull request ID |
| `BITBUCKET_ACCESS_TOKEN` | Pipeline access token |
| `BITBUCKET_API_URL` | API base URL (Bitbucket Server/Data Center) when config sets no `url` |

### Azure Pipelines

| Variable | Description |
|----------|-------------|
| `TF_BUILD` | Set to `"True"` when running in Azure Pipelines |
| `AZURE_HTTP_USER_AGENT` | Present in Azure Pipelines environment |
| `BUILD_REPOSITORY_NAME` | Repository name |
| `SYSTEM_PULLREQUEST_PULLREQUESTID` | Pull request ID |
| `SYSTEM_TEAMFOUNDATIONCOLLECTIONURI` | Azure DevOps server URL |
| `SYSTEM_TEAMFOUNDATIONSERVERURI` | Marks an Azure Pipelines run; server URL when the collection URI is unset |
| `SYSTEM_TEAMPROJECT` | Azure DevOps project name |
| `SYSTEM_ACCESSTOKEN` | System access token |

### Jenkins

| Variable | Description |
|----------|-------------|
| `JENKINS_URL` | Present when running in Jenkins |
| `CHANGE_URL` | PR/MR URL (e.g., `https://github.com/owner/repo/pull/123`) |
| `BUILD_URL` | Build whose status fills the MR's CI status when its platform reports none; must be on the `JENKINS_URL` host |
| `JOB_NAME` | Job name shown for that CI status (default `jenkins`) |

### Gerrit (Gerrit Trigger or any job exporting `GERRIT_URL`)

| Variable | Description |
|----------|-------------|
| `GERRIT_URL` | Gerrit web root; config `url` wins |
| `GERRIT_CHANGE_URL` | Change URL; its origin is the web root when `GERRIT_URL` is unset |
| `GERRIT_HOST` | Host used when neither URL is set |
| `GERRIT_SCHEME` | Scheme for `GERRIT_HOST`: `http` or `https`; anything else (the trigger's `ssh`) means `https` |
| `GERRIT_PROJECT` | Project name |
| `GERRIT_CHANGE_NUMBER` | Change number |

### Gitea / Forgejo

| Variable | Description |
|----------|-------------|
| `GITEA_ACTIONS` / `FORGEJO_ACTIONS` | `true` marks a Gitea/Forgejo Actions run: the GitHub platform skips it and Gitea reads the `GITHUB_*` variables |
| `GITEA_URL` | Instance URL; config `url` wins |
| `GITEA_SERVER_URL` | Instance URL fallback after `GITEA_URL` (first in CI detection) |
| `GITEA_REPO_OWNER` | Repository owner (with `GITEA_REPO_NAME`) |
| `GITEA_REPO_NAME` | Repository name |
| `GITEA_PR_ID` | Pull request number |

### Woodpecker

| Variable | Description |
|----------|-------------|
| `CI` | `woodpecker` marks a Woodpecker build; no other value of `CI` is read |
| `CI_FORGE_TYPE` | Forge of the build (`github`, `gitlab`, `gitea`, `forgejo`, `bitbucket`): picks the platform |
| `CI_FORGE_URL` | Forge web URL, mapped to the platform's API base URL |
| `CI_REPO` | Repository (`owner/repo`) |
| `CI_COMMIT_PULL_REQUEST` | Pull request number |

### AWS CodeBuild

| Variable | Description |
|----------|-------------|
| `CODEBUILD_BUILD_ID` | Marks a CodeBuild run |
| `CODEBUILD_SOURCE_REPO_URL` | Source repository: a CodeCommit, GitHub, GitLab or Bitbucket URL picks the platform |
| `CODEBUILD_WEBHOOK_TRIGGER` | `pr/<n>` names the pull request |
| `CODEBUILD_SOURCE_VERSION` | `pr/<n>` names the pull request when the webhook trigger does not |

CI mode (the default output format switches to a CI reporter) means that a platform detected its CI build from the
variables above; there is no separate generic `CI` check.

---

## AI Providers

See [AI features](../features/AI_FEATURES_REFERENCE.md#environment-variables). `ai.apiKey` / `ai.baseUrl` in config win over these.

| Variable | Description |
|----------|-------------|
| `OPENAI_API_KEY` | OpenAI API key |
| `OPENAI_BASE_URL` | Replaces the built-in OpenAI endpoint |
| `ANTHROPIC_API_KEY` | Anthropic API key |
| `ANTHROPIC_BASE_URL` | Replaces the built-in Anthropic endpoint |
| `GEMINI_API_KEY` | Google Gemini API key |
| `GEMINI_BASE_URL` | Replaces the built-in Gemini endpoint |
| `OLLAMA_HOST` | Ollama host (`host:port` or URL) |
| `LITELLM_API_KEY` | LiteLLM proxy API key |
| `LITELLM_BASE_URL` | LiteLLM proxy URL (required unless `ai.baseUrl` is set) |
| `BIFROST_API_KEY` | Bifrost gateway API key |
| `BIFROST_BASE_URL` | Bifrost gateway URL (required unless `ai.baseUrl` is set) |
| `CUSTOM_AI_API_KEY` | API key of the `custom` provider |
| `KARANDASH_AI_DRY_RUN` | `true`/`1`/`yes`: AI rules build prompts but make no calls |
| `KARANDASH_AI_MAX_TOKEN_BUDGET` | Positive integer that replaces `ai.maxTokenBudget` |
| `KARANDASH_REVIEW_MODE` | Replaces `ai.reviewMode` (e.g. `added_only`) |
| `AI_CONCURRENCY` | Parallel AI calls, clamped to 1-20; wins over `ai.concurrency` |
| `CORE__CONCURRENCY` | Fallback for `AI_CONCURRENCY` |

---

## TLS and Terminal

| Variable | Description |
|----------|-------------|
| `SSL_CERT_FILE` | CA bundle file that replaces the default trust anchors (JVM and native curl; Windows uses the system store) |
| `CURL_CA_BUNDLE` | Same as `SSL_CERT_FILE`, read when that is unset |
| `NO_COLOR` | Any non-empty value turns off colored output |
| `TERM` | `dumb` turns off colored output |

---

## Config File Environment Variable Access

Config values may reference environment variables with `${VAR_NAME}` syntax in
YAML/JSON, or `env.VAR_NAME` in the Kotlin DSL (which lowers to the same
`${VAR_NAME}` placeholder before interpolation).

**There is no prefix allowlist.** Any environment variable can be interpolated
*except* the names on a fixed security denylist.

### Denylist

These names are never interpolated, no matter how they are referenced. The list
is defined once in `KarandashEnvSecurity.DENYLIST` and shared by config parsing
and plugin bootstrap:

| Variable | Why it is denied |
|----------|------------------|
| `PATH` | Leaks local filesystem layout |
| `HOME` | Leaks the user's home directory |
| `USER` | Leaks the local account name |
| `SHELL` | Leaks local environment details |
| `LOGNAME` | Leaks the local account name |
| `LANG` | Leaks locale/environment details |
| `SSH_AUTH_SOCK` | SSH agent access |
| `SSH_AGENT_PID` | SSH agent access |
| `SSH_PRIVATE_KEY` | Private key material |
| `AWS_SECRET_ACCESS_KEY` | Cloud credential secret |
| `AWS_SESSION_TOKEN` | Cloud credential secret |
| `GOOGLE_APPLICATION_CREDENTIALS` | Cloud credential path |

A denied reference is **not** an error during interpolation - the `${VAR}` token
is left in place verbatim and the name is reported as a denied variable. Note
that `AWS_SECRET_ACCESS_KEY` is denied; `AWS_ACCESS_KEY_ID`, which is not on the
list, interpolates normally.

Declaring a denylisted name in an `env:` block *is* an error:

```
Restricted environment variable(s) in 'env:' block: AWS_SECRET_ACCESS_KEY.
These system variables cannot be declared in config.
```

### The `env:` declaration block

Interpolation works without any declaration. The optional top-level `env:` block
exists to document the variables a config depends on and to supply fallback
defaults when they are unset. It accepts a list form or a map form:

```yaml
# List form - declared, no defaults
env:
  - TASK_ID
  - JIRA_PROJECT_KEY

# Map form - optional defaults
env:
  TASK_ID: null             # no default; unset means "missing"
  JIRA_PROJECT_KEY: "PROJ"  # falls back to "PROJ" when unset
```

Across an `extends:` chain, all declarations are merged and the first non-null
default wins.

### Resolution order for a `${VAR}` token

1. Denylisted → left as the literal `${VAR}` placeholder, recorded as denied.
2. Value from the process environment.
3. Default from the `env:` block.
4. Inline default from `${VAR:-fallback}` syntax.
5. Otherwise → replaced with an empty string and recorded as a missing variable.

**Usage in config:**

```yaml
env:
  SLACK_WEBHOOK_URL: null

auth:
  gitlab:
    url: "${CI_SERVER_URL:-https://gitlab.com}"
    token: "${CI_JOB_TOKEN}"
reporters:
  slack:
    webhook: "${SLACK_WEBHOOK_URL}"
```

The Kotlin DSL equivalent:

```kotlin
karandash {
    auth {
        gitlab {
            url = env.CI_SERVER_URL
            token = env.CI_JOB_TOKEN
        }
    }
    reporters {
        slack {
            webhook = env.SLACK_WEBHOOK_URL
        }
    }
}
```

---

## Platform-Specific Variables

| Variable | Description |
|----------|-------------|
| `HOME` | Home directory for plugins, caches and AWS files (on Windows, read after `USERPROFILE`) |
| `USERPROFILE` | Home directory on Windows; fallback for `HOME` elsewhere |
| `TMPDIR` | Temp directory on macOS/Linux native (default `/tmp`) |
| `TEMP` / `TMP` | Temp directory on Windows native |
| `JAVA_HOME` | Native binary: the `java` that runs the JAR plugin bridge (else `java` on `PATH`) |
| `OS` | Native binary: a value containing `Windows` selects the `;` classpath separator for the JAR bridge |

---

## Detection Priority

When multiple environment variable sources are available, Karandash uses this priority order:

1. **Explicit overrides** (`KARANDASH_*`) -- highest confidence
2. **CI environment variables** (platform-specific) -- high confidence
3. **Git remote parsing** -- medium confidence
4. **CLI flags** (`--platform`, `--project`, `--mr-id`) -- override all detection

```bash
# These KARANDASH_* vars override everything, even CI auto-detection
export KARANDASH_PLATFORM=github
export KARANDASH_PROJECT_ID=explicit/repo
# Even if GITLAB_CI=true, Karandash will use GitHub
```

## See Also

- [CLI Reference](cli-reference.md) -- CLI flags that override env vars
- [Configuration Schema](configuration-schema.md) -- `auth` block for config-based auth
- [Error Codes](error-codes.md) -- exit code `3` for provider/auth errors
