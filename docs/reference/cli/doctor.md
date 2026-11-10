# doctor

Check configuration, authentication, and plugin health.

## Overview

The `doctor` command checks your Karandash setup for common problems: an unreadable or invalid config (a missing config file is only a warning: zero-config defaults apply), invalid configuration, unreachable providers, plugin loading issues, and policy expression errors.

## Usage

```bash
karandash doctor [options]
```

## Options

| Option | Description |
|--------|-------------|
| `--plugins` | Show detailed plugin status table (built-in and community modules) |
| `--offline` | Skip every network probe: the credential check against each platform API (only list the configured platforms), the remote cache and the AI endpoint |

## Checks Performed

1. **Config** - Discovers and parses the configuration file; reports rule count
2. **Patterns** - Compiles every `pomsky:` pattern
3. **Extends Chain** - Lists the `extends:` layers
4. **Tokens** - Every `${NAME}` the config references whose name contains `TOKEN`, `KEY`, `SECRET`
   or `PASSWORD`: `NAME: set` or `NAME: missing` (a warning). Values are never printed
5. **Cache** - Writes, reads back and deletes a probe file in `cache.dir` (an unwritable directory fails);
   with `cache.remote`, one existence request against the remote backend (unreachable fails)
6. **AI Endpoint** - With an `ai:` block, a GET of `ai.base-url` within 5 seconds, without the API key; any
   HTTP status counts as reachable, a network error or timeout fails. Warns when `ai.base-url` reads an unset variable; without `ai.base-url` (provider default) nothing is probed
7. **Auth** - For each platform under `platforms:`, initializes its platform service and calls its API
   with the configured credentials (GitLab `GET /api/v4/user`, GitHub and Bitbucket `GET /user`). Rejected
   credentials (HTTP 401/403) or an unreachable API fail the check. Platforms that cannot verify
   credentials are reported as configured. `--offline` skips the API calls
8. **Plugins** - Loads external plugins and reports status
9. **Platform Auto-Detection** - Tests CI/git remote detection
10. **Provider Detection Scores** - Scores each registered provider
11. **Reporter Defaults** - Shows which format is selected for the current environment
12. **Policy Validation** - Validates `when:` expressions in `policies:` against known predicates
13. **Enrichment Providers** - Lists registered enrichment providers
14. **AI Reports / AI Metrics** - Lists registered AI report and metric modules

## Exit Codes

| Code | Meaning |
|------|---------|
| `0` | All checks passed |
| `1` | Some checks failed (non-fatal) |
| `2` | Config parse error (fatal) |

## Examples

```bash
# Run all health checks
karandash doctor

# Run checks and show detailed module table
karandash doctor --plugins
```

## Example Output

```
Karandash Doctor
================

[Config]
  OK: Found .karandash/config.yaml (5 rule(s))

[Patterns]
  OK: pomsky patterns compile without warnings

[Extends Chain]
  OK: 2 layer(s): https://git.example/review-bot/-/raw/1.0.0/base/config.yaml -> .karandash/config.yaml

[Tokens]
  OK: GITLAB_TOKEN: set
  WARN: AI_API_KEY: missing
    → How to fix: Set AI_API_KEY in the CI variables (masked) or the shell environment.

[Cache]
  OK: Cache directory .karandash-cache is readable and writable

[AI Endpoint]
  OK: AI not configured

[Auth]
  OK: gitlab: credentials accepted

[Plugins]
  OK: No plugins configured (using built-in rules only)

[Platform Auto-Detection]
  OK: Platform auto-detection working
     Platform:   gitlab
     Project ID: group/repo
     MR ID:      not detected
     Confidence: HIGH
     Source:     env:CI_PROJECT_ID

[Provider Detection Scores]
  gitlab: 90 (env:CI_PROJECT_ID)
  Selected: gitlab (score=90)

[Reporter Defaults]
  Mode: CI
  Selected format: console
  ...

[Policy Validation]
  SKIP: No policies configured

Result: All checks passed
```

A failed check prints the problem on the `FAIL:` line and, when doctor knows the fix, the action on the next line:

```
[Auth]
  FAIL: github: credentials rejected (HTTP 401/403)
    → How to fix: Verify your token at: Settings > Developer settings > Personal access tokens.
```

## See Also

- [init](init.md) - Create a configuration file
- [validate](validate.md) - Run the full validation pipeline
- [config](config.md) - Validate a config file for schema errors
