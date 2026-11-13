# Changelog

All notable changes to Karandash will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

2.0.1 changes:

- AI provider that cannot be reached is named `unreachable: <host> (<kind>)`; calls to it fail fast for the rest of the run, unless it answered earlier in that run.
- Connect timeout is capped at 15s; a shorter `ai.timeout` is kept.
- The review-thread analysis shows as skipped in the report instead of passed.
- Skip lines are split by cause, and the info-row text is clearer.
- Findings carry a "How to fix" suggestion.
- A budget note explains which context was cut by `ai.budget`.
- Enricher results have a Details section in the report.
- Jira enrichment skips with a reason when its url is not set.
- Local git enrichment says why it gave no data (git not available, shallow clone).
- `cache.dir` is honored for remote `extends`.
- Docker runtime images include git.
- Execution timeout is 11 minutes.

## [1.0.0] - 2026-11-09

First public release.

- Merge request validation for GitLab, GitHub, Bitbucket, Azure DevOps, Gitea, Gerrit and AWS CodeCommit: built-in rules, conditional `policies:`, `extends:` config layering, YAML and Kotlin script configs.
- AI review analyzers with pluggable providers, plus a plugin SDK (`ru.kode.karandash:sdk` on Maven Central) and native/WASM/JS plugin bridges.
- Output as console, JSON, SARIF, rdjson, HTML, Markdown and inline MR comments.
- Distributed as a JVM jar, native binaries, Docker images (`rinekri/karandash`) and a GitHub Action (`appKODE/karandash@v1.0.0`).

Notes:

- The plugin bridge `capabilities` op is gone; a guest's `supports()` now defaults to `false`.
- The env vars `AI_MAX_TOKEN_BUDGET`, `REVIEW__MODE` and `REVIEW__DRY_RUN` are no longer read; use `KARANDASH_AI_MAX_TOKEN_BUDGET`, `KARANDASH_REVIEW_MODE` and `KARANDASH_AI_DRY_RUN`.
- `validate-extends` no longer takes `--check-auth` or `--timeout`.
- `ai.timeout` given as a bare number means seconds; a value of 1000 or more logs a warning (write `"30000ms"` for milliseconds).
