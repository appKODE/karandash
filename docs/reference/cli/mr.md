# mr

Merge, close or reopen a merge/pull request by URL.

## Overview

`karandash mr` resolves the platform from the URL, initializes its platform service with the matching
`platforms:` block of the discovered config (or `--config`), and calls the platform API. Without a config
block the service reads its token from the usual environment variables, as `validate --pr` does.

## Subcommands

| Subcommand | Description |
|------------|-------------|
| `merge` | Merge the MR/PR |
| `close` | Close (decline, abandon) the MR/PR without merging |
| `reopen` | Reopen a closed MR/PR |

## Synopsis

```
karandash mr merge <url> [--squash] [--delete-source-branch] [--message <text>] [--config <file>]
karandash mr close <url> [--config <file>]
karandash mr reopen <url> [--config <file>]
```

## Options

| Flag | Short | Subcommand | Description |
|------|-------|------------|-------------|
| `--squash` | -- | `merge` | Squash the commits |
| `--delete-source-branch` | -- | `merge` | Delete the source branch after merging |
| `--message` | `-m` | `merge` | Merge commit message |
| `--config` | `-c` | all | Config file path (default: auto-discover) |

## Platform support

| Platform | merge | close | reopen |
|----------|-------|-------|--------|
| GitLab | yes | yes | yes |
| GitHub | yes | yes | yes |
| Bitbucket | yes | yes (decline) | no: Bitbucket cannot reopen a declined PR |
| Gitea | yes | yes | yes |
| Azure DevOps | yes | yes (abandon) | yes |
| Gerrit | yes (submit) | yes (abandon) | yes (restore) |
| AWS CodeCommit | yes | yes | yes |
| Jenkins, local-git | no | no | no |

An unsupported operation fails with an error naming the platform.

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | Done |
| 2 | URL not recognized, or the platform service failed to initialize |
| 3 | The platform rejected the operation, or the operation is not supported |

## Examples

```bash
karandash mr merge https://gitlab.com/group/repo/-/merge_requests/7 --squash --delete-source-branch
karandash mr close https://github.com/owner/repo/pull/12
karandash mr reopen https://github.com/owner/repo/pull/12
```

Auto-merge after a clean validation is configured with `autoApprove.merge: true` (see [config](config.md)).
