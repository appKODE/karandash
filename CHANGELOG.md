# Changelog

All notable changes to Karandash will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

2.0.1 changes:

- An AI provider that cannot be reached is reported as `unreachable: <host> (<kind>)` in the MR note and logged once at WARN. Connect failures are not retried, and later calls to the same base URL fail at once for the rest of the run, unless that host answered earlier in the run.
- The AI connect timeout is capped at 15s (`ai.timeout` still bounds the whole call).
- An AI rule that made no call (for example review-thread-analysis with no open threads) shows as skipped, with no model, and is not counted by `ai:health`.
- The report lists AI provider errors on their own "N AI rules failed" line, apart from real skips. Rule ids in that list are canonical.
- The Passed line leaves out rules that have only info findings.
- ai-incremental moves to "How this review was made". ai-large-mr counts add up (reviewed, not reviewed, excluded or unchanged).
- The `ai:health`, ai-large-mr and ai-test-gap info rows have a "How to fix" text.
- When the budget plan underfunds a rule, the MR note shows the per-rule share and the `ai.cost-budget` value needed.
- The Enricher table fills its Details column (items, no data, cached). Jira with no url is skipped with a reason, and local-git says why it gave no data (git not available, shallow clone).
- The remote `extends:` cache uses `cache.dir`, and a cache directory that cannot be created is reported instead of failing later with E0019.
- The Docker runtime images include git.
- The base config `projects/base` sets `execution-timeout: "11m"` to fit 5m x 2 tries.

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
