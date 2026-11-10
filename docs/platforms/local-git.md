# Local Git Platform Guide

## Overview

The Local Git platform service builds a `MergeRequest` from the **local git working copy** instead of a hosted platform API. It shells out to the `git` CLI to collect the current branch, the commits ahead of a target branch, and the diff - no network access, no token, no PR or MR needs to exist.

**Support level:** Local-only

**Capabilities:**
- Commit and diff validation against any local target branch
- Fully offline - no API, no authentication
- Pre-push validation and fast local feedback loops

**Not available:**
- Comment posting (inline or top-level)
- Auto-fix mutations (`--fix`): labels, draft, title, description, approve/unapprove
- CI auto-detection
- SARIF upload

**Data source:** `git` CLI (`rev-parse`, `config`, `log`, `diff`)

---

## How It Differs from Remote Platform Services

Every other platform guide in this section describes a client for a hosted API. Local Git is the exception:

| | Remote platform services | Local Git |
|---|---|---|
| Data source | Platform REST API | `git` commands in the working copy |
| Authentication | Token required | None |
| Network | Required | Never used |
| Reads | MR metadata, labels, approvals, reviewers, description | Branch, commits, diff, git author identity only |
| Writes | Comments, labels, draft, title, description, approvals | None - every mutation throws |
| Requires an open MR/PR | Yes | No - validates unpushed work |

Because it reads no platform metadata, rules that depend on labels, approvals, reviewers, or the MR description always see empty values under Local Git. Rules that inspect the title, branch names, commits, or the diff work normally.

---

## Authentication Setup

None. Local Git never contacts a remote, so no token or URL is configured. It only requires:

- `git` available on `PATH`
- The command run from inside a git working tree
- A target branch that exists locally (fetch it first if it does not)

---

## Configuration

Local Git is a built-in module but is **disabled by default** (`defaultEnabled = false`) and has the lowest provider priority, so it never displaces a real platform. Enable it explicitly:

```yaml
platforms:
  local-git: true

rules:
  title-format:
    pattern: "^(feat|fix|chore|docs): .+"
  commit-messages:
    format: "conventional"
  size-limits:
    max-lines: 500
    max-files: 30

reporters:
  console: true
```

The module has no configuration keys of its own - `enabled` is the only setting.

### Selecting it on the CLI

Live mode requires all three of `--platform`, `--project`, and `--mr-id`:

```bash
karandash validate --platform local-git --project main --mr-id local
```

| Flag | Meaning under Local Git |
|------|------------------------|
| `--platform local-git` | Selects this provider |
| `--project <branch>` | **The target branch to diff against** - not a project path. Empty falls back to `main` |
| `--mr-id <anything>` | Ignored; still required by the CLI's live-mode argument check |

`validate-local` is the shorthand for the same run, with `--target` (default `main`) as the target branch and an
optional `--rules` list to run only some of the configured rules:

```bash
karandash validate-local
karandash validate-local --target develop --rules title-format,size-limits
```

Selecting the provider explicitly (`validate-local` or `--platform local-git`) works without enabling it under
`platforms:`.

To validate against a different base branch, pass it as `--project`:

```bash
karandash validate --platform local-git --project develop --mr-id local
```

### Environment variables

The same selection works through the explicit-override environment variables:

```bash
export KARANDASH_PLATFORM=local-git
export KARANDASH_PROJECT_ID=main
export KARANDASH_MR_ID=local
karandash validate
```

### Diff context lines

The unified diff is generated with `git diff -U<n>`, where `n` comes from the `ai.context-lines` config value (default 3, clamped to 1..50).

---

## What Gets Built

| `MergeRequest` field | Source |
|----------------------|--------|
| `id` / `iid` | Constant `"local"` / `0` |
| `title` | Subject of the first commit in `<target>..HEAD`, or the current branch name when there are no commits |
| `description` | Always `null` |
| `author` | `git config user.name` / `user.email` (`unknown` if unset) |
| `sourceBranch` | `git rev-parse --abbrev-ref HEAD` |
| `targetBranch` | The `--project` value |
| `state` | Always `OPENED` |
| `draft` | `true` when the current branch starts with `draft/` or `wip/` |
| `commits` | `git log <target>..HEAD` (sha, subject, author, ISO timestamp) |
| `changes` | `git diff --numstat <target>...HEAD` combined with `git diff -U<n> <target>...HEAD` |
| `labels`, `approvals`, `reviewers` | Always empty |

File changes are classified as added, deleted, renamed, or modified from the diff headers. Binary files report `Binary files differ` with 0 additions and 0 deletions.

Note the three-dot form (`<target>...HEAD`) for diffs: changes are computed against the **merge base**, matching what a platform PR would show. Commits use the two-dot form (`<target>..HEAD`).

---

## Features and Limitations

### What Works

| Feature | Status | Notes |
|---------|--------|-------|
| Commit validation | Yes | All commits ahead of the target branch |
| File change detection | Yes | With per-file addition/deletion counts |
| Diff content | Yes | Real unified diff, so diff-based and AI rules work |
| Branch naming validation | Yes | From the checked-out branch |
| Draft detection | Yes | Heuristic: `draft/` or `wip/` branch prefix |
| Offline operation | Yes | No network access at any point |

### What Does Not Work

| Feature | Reason |
|---------|--------|
| Comment posting / updating | The provider has no place to post - calls throw `UnsupportedOperationException` |
| Inline comments | Same |
| `--fix` mutations (labels, draft, title, description, approve) | Same - there is no MR object to mutate |
| CI auto-detection | The provider reports no CI environment; it can never be auto-selected |
| Platform auto-detection | Detection resolves platforms from `KARANDASH_*` variables, CI variables, or the git remote - never to Local Git. Select it explicitly |
| Label / approval / reviewer rules | The data is always empty, so these rules cannot produce meaningful findings |
| `--pr <url>` | URL mode is not applicable; the target branch would always fall back to `main` |

### Silent Failure Mode

If `git` is missing, the directory is not a git repository, or the target branch does not exist locally, the underlying git call returns nothing and Karandash builds an MR with **empty commits and changes** rather than erroring. A validation run that unexpectedly reports zero findings is the usual symptom - check with:

```bash
git rev-parse --is-inside-work-tree
git log --oneline main..HEAD
git diff --numstat main...HEAD
```

---

## Common Use Cases

### Pre-push validation

Check your work before it ever reaches a review platform:

```bash
karandash validate --platform local-git --project main --mr-id local
```

### Pre-push git hook

```bash
# .git/hooks/pre-push
#!/bin/sh
karandash validate --platform local-git --project main --mr-id local || exit 1
```

### Offline or air-gapped review

```bash
karandash validate \
  --platform local-git \
  --project release/2.0 \
  --mr-id local \
  --format json --output findings.json
```

---

## Troubleshooting

### Zero commits and zero file changes

**Cause:** The target branch passed to `--project` does not exist locally, or `HEAD` has nothing ahead of it.

**Fix:** Fetch the branch (`git fetch origin main:main`) and confirm `git log --oneline <target>..HEAD` is non-empty.

### Findings for label, approval, or reviewer rules never fire

**Cause:** Local Git supplies no platform metadata - labels, approvals, and reviewers are always empty lists.

**Fix:** Restrict these rules to remote platforms, or accept that they are inert in local runs.

### `--fix` reports an unsupported operation

**Cause:** All mutation and comment methods throw under Local Git by design.

**Fix:** Run `--fix` against the real platform provider once the branch is pushed.

### Karandash picks a different platform

**Cause:** Local Git is disabled by default and has the lowest provider priority, so auto-detection prefers GitHub, GitLab, and the rest.

**Fix:** Pass `--platform local-git` explicitly (or set `KARANDASH_PLATFORM=local-git`), and enable it in `platforms:`.

---

## Related

- [Platform Guides index](index.md) - comparison of the hosted platform services
- [GitHub](github.md) / [GitLab](gitlab.md) - the remote counterparts for the same repository once pushed
