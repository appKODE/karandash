# Markdown Reporter

## Overview

The Markdown Reporter formats validation findings as Markdown tables and sections, then delivers the result as an MR/PR comment via the platform API. On CI with a configured platform, it activates automatically - no explicit config required.

It replaces the old `comment` reporter concept. The Markdown format is also available for file output.

## How it works

The Markdown reporter composes two modules:

- **`MarkdownFormatModule`** - renders findings into structured Markdown (summary table, per-rule sections, inline file references)
- **`PlatformOutputModule`** - posts the rendered Markdown as a comment on the open MR/PR via the platform API (GitLab, GitHub, Bitbucket, etc.)

When `--output` is provided, `FilesystemOutputModule` is used instead, writing the Markdown to a file.

## Configuration

### Auto-activation on CI

When a platform is configured and Karandash is running in a CI environment with an open MR, the Markdown reporter activates automatically:

```yaml
platforms:
  gitlab:
    token: "${GITLAB_TOKEN}"
```

No `reporters:` entry needed.

### Explicit MR comment

```yaml
reporters:
  markdown: true
```

### Preview Mode (format only, no posting)

```yaml
reporters:
  markdown:
    preview: true
```

### Write to file

```bash
karandash validate --format markdown --output report.md
```

### Disable auto-activation

```yaml
reporters:
  markdown: false
```

## Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `preview` | bool | `false` | Format only - the pipeline may skip posting (useful for dry-run or file output) |
| `flowArtifactDir` | string | `.karandash/debug` | Directory where every run with provenance writes `review-flow.md` (the Mermaid flow diagram). Empty disables the file; a `preview` run never writes it |

### Platform comment options

The `PlatformOutputModule` that posts the comment reads its own block, `reporters.platform`:

```yaml
reporters:
  platform:
    commentMarker: "<!-- my-bot -->"   # finds and updates the summary comment; default is the format's marker
    inlineComments: true
    comments: all                         # all | summary | inline | none: which MR comments are posted
    when: changed                         # always | changed (verdict or findings differ from the last post) | failed | never
    updateOnTextChange: true              # also edit the summary when only its text changed; false: only verdict and findings count
    inline:
      maxPerRun: 8                        # new inline comments per run; the rest are listed collapsed in the summary
      publish: batch                      # GitLab: one review (draft notes + bulk publish), one email; `each` posts one by one
      onFixed: edit                       # edit the bot's note to "Fixed in <sha>" and resolve; `reply` adds a reply note
```

The summary comment carries a hidden `<!-- karandash-state ... -->` line with the verdict, a hash of the finding set and
a hash of the body. An unchanged body is not re-sent. With `when: changed` a run whose verdict and findings match the
last post still edits the comment in place when its text changed (a new layout, an inline thread opened or fixed); an
edit sends no notification. `updateOnTextChange: false` leaves such a comment alone. The CLI hands the platform reporter this run's state as `notificationState`
and the state it read from the MR as `previousNotificationState`; leave both keys unset.

`comments: none` keeps the MR free of bot comments, for a team that reads results from the [status](status.md)
reporter (a check run, commit status, Code Insights report or PR status) or a [Code Quality](codequality.md) report.
`summary` posts only the summary comment, `inline` only the inline comments.

## Comment Format

The posted comment contains:

- A status header (`Validation PASSED` / `Validation FAILED`)
- A summary table: errors, warnings, info counts
- Per-finding sections grouped by severity, with file/line links where available
- A findings table with a **How to fix** column (the suggestion and the required format); the message cell adds a
  **Where** line when the finding carries a mismatch
- Inline comments with labelled **Where**, **How to fix** and **Format** lines (localized in `en`, `ru`, `pl`); when the
  body is over the inline line cap, **Format** is dropped first, then **Where**, and **How to fix** is kept
- A collapsed `<details>` block with the format explanation and diagram when the finding carries them
- A footer with the Karandash version and run timestamp

## Notes

- The `PlatformOutputModule` requires a configured platform with comment-posting credentials. Without a platform, the Markdown reporter falls back to stdout.
- Existing Karandash comments on the same MR are updated in place (not duplicated) on re-runs.

## Related Reporters

- [Console](console.md) - Human-readable terminal output
- [HTML](html.md) - Standalone HTML report file
- [SARIF](sarif.md) - SARIF 2.1.0 for code scanning tools
