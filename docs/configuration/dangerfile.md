# Dangerfile

Karandash can run an existing [Danger](https://danger.systems/js/) `Dangerfile` as part of `validate`. The script
sees the merge request through a `danger` object shaped like Danger's DSL, and what it reports with `warn`, `fail`,
`message` and `markdown` becomes Karandash findings, reported and gated like any rule's.

This lets a team move from Danger to Karandash without rewriting its checks first. For new checks, prefer rules or
[scripts](scripts.md).

## Detection

A Dangerfile is picked, in this order:

1. `validate --dangerfile <path>`
2. the top-level `dangerfile:` key in the config
3. the first of `Dangerfile.js`, `Dangerfile`, `Dangerfile.mjs`, `Dangerfile.df.kts`, `Dangerfile.kts` found in the
   directory that holds the config file (the current directory when no config file is used)

```yaml
# .karandash/config.yaml
dangerfile: ci/Dangerfile.js   # optional; relative paths resolve against the current directory
```

A path that does not exist is ignored (nothing runs). A `.kts` path runs as a Kotlin Dangerfile, anything else as
JavaScript. A policy can switch the stage off per MR:

```yaml
policies:
  - when: 'is_draft'
    dangerfile: false
```

## Requirements

- **JavaScript:** `node` on `PATH`. Any maintained Node.js release works; only its built-ins (`fs`, `path`, `os`,
  `vm`) are used, so there is no `npm install` and the `danger` npm package is not needed. The official toolchain
  Docker image ships `node`.
- **Kotlin (`.df.kts`, `.kts`):** [`danger-kotlin`](https://github.com/danger/kotlin) on `PATH`. Karandash hands it
  the DSL with `danger-kotlin ci --dangerfile <path>` over the standard `danger://dsl/<file>` protocol.

When the runner is missing or the script throws, `validate` logs `Dangerfile execution failed: ...` (or the script's
`Dangerfile error: ...`) as a warning and goes on without Dangerfile findings; it does not fail the run.

The JavaScript Dangerfile runs with the CLI's permissions (it gets `require`, `process` and the file system), so
treat it like any other build script in the repository.

## Script API

The JavaScript runner strips `import ... from "danger"` and `const { ... } = require("danger")` lines, so a
Dangerfile written for Danger JS runs unchanged as long as it only uses the API below.

| Global | Meaning |
|--------|---------|
| `warn(message, file?, line?)` | a `WARNING` finding |
| `fail(message, file?, line?)` | an `ERROR` finding (fails the run like any error) |
| `message(message, file?, line?)` | an `INFO` finding |
| `markdown(text)` | an `INFO` finding marked as Markdown |
| `schedule(fn)` | accepted and ignored (no async work is awaited) |
| `danger.utils.href(url, text)` | a Markdown link |

All findings carry the rule id `dangerfile`. Passing `file` (and `line`) gives the finding a file location, so
platform reporters can post it inline.

`danger.git`:

| Field | Content |
|-------|---------|
| `modified_files`, `created_files`, `deleted_files`, `renamed_files` | changed paths, by change type |
| `commits[]` | `sha`, `message`, `author { name, email }` |

`danger.github.pr`: `title`, `body`, `number`, `state`, `draft`, `user.login`, `head.ref`, `base.ref`,
`labels[] { name }`, `requested_reviewers[] { login }`, `commits[] { sha, commit { message, author { name, email } } }`.

`danger.gitlab.mr`: `title`, `description`, `iid`, `state`, `draft`, `labels[]` (strings), `source_branch`,
`target_branch`, `author { name, username }`, `commits[] { id, message, author_name, author_email }`.

Both `github` and `gitlab` blocks are filled from the same merge request whatever the platform, so a Dangerfile
written for either works on GitHub, GitLab, Bitbucket and the rest. Danger APIs that call the platform
(`danger.github.api`, `danger.gitlab.utils`, file diffs via `danger.git.diffForFile`) are not available.

## Example

```js
// Dangerfile.js
import { danger, warn, fail, message } from "danger"

const touched = [...danger.git.modified_files, ...danger.git.created_files]

if (!touched.includes("CHANGELOG.md")) {
  warn("Please add a CHANGELOG entry.")
}

if (danger.github.pr.body.length < 10) {
  fail("Describe the change in the MR description.")
}

for (const file of danger.git.created_files.filter((f) => f.endsWith(".kt"))) {
  message("New Kotlin file", file, 1)
}
```

```bash
karandash validate --pr "$CI_MERGE_REQUEST_URL"   # the warning shows up under the rule id dangerfile
```

## See also

- [Scripts](scripts.md): executable checks in any language that return findings as JSON
- [`validate` reference](../reference/cli/validate.md): `--dangerfile`
