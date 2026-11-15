# Changelog

All notable changes to Karandash will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.1] - 2026-11-15

- Fixed: the SDK is now published to Maven Central; 1.0.0 was released without it.

## [1.0.0] - 2026-11-14

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
