# Script-Based Validation

The `scripts:` block lets you write custom validation logic in JavaScript. Scripts integrate with the same finding pipeline as built-in rules - failures, warnings, and messages from scripts appear in all configured reporters.

## Quick Start

Add a `scripts:` block to your config:

```yaml
scripts:
  - path: .karandash/checks.js
```

Create `.karandash/checks.js`:

```javascript
const mr = karandash.mr;

if (mr.title.length < 10) {
    karandash.fail("PR title is too short (minimum 10 characters)", null, null,
        "Expand the title to at least 10 characters.");
}
```

Run validation:

```bash
karandash validate
```

## Configuration Syntax

### YAML

```yaml
scripts:
  - path: .karandash/checks.js          # sandbox mode (default for .js)
  - path: .karandash/security.js
    mode: subprocess                     # opt-in Node.js execution
    timeout: 15000                       # milliseconds (default: 30000)
    env: [JIRA_TOKEN]                    # the only env vars the script may read
```

### Kotlin DSL

```kotlin
scripts {
    script {
        path = ".karandash/checks.js"
    }
    script {
        path = ".karandash/security.js"
        mode = "subprocess"
        timeout = 15000
        env = listOf("JIRA_TOKEN")
    }
}
```

### Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `path` | string | required | Path to the script file, relative to the repository root |
| `mode` | `sandbox` \| `subprocess` | `sandbox` | Execution mode (see below) |
| `timeout` | integer (ms) | `30000` | Maximum execution time before the script is terminated |
| `env` | list of strings | absent | Env var names the script may read (see [Script environment](#script-environment-env)) |

### Script environment (`env`)

`env` lists the environment variables a script may read. It applies to `process.env` and `karandash.env()` in
subprocess mode and to `karandash.env()` in sandbox mode. Names in `KarandashEnvSecurity.DENYLIST` stay hidden even
when listed: the denylist always wins.

- **Declared** (`env: [A, B]`, empty list included): the script sees only the listed variables. In subprocess mode
  every other variable is removed when node starts, so it is not in node's own initial environment
  (`/proc/self/environ`, `ps -E`) or in child processes the script starts. `env: []` gives the script no environment
  variables at all.
- **Undeclared** (no `env` key): for this release the script still sees every non-denylisted variable, but Karandash
  records which ones it reads and, after the run, logs one warning per script:

  ```
  Script '.karandash/security.js' read undeclared env vars [CI_PROJECT_ID, JIRA_TOKEN]; declare them in scripts[].env - undeclared reads will be stripped in the next release
  ```

  The warning lists names only, never values; only names matching `[A-Za-z_][A-Za-z0-9_]{0,127}` are shown, at most
  50 (the rest as "and N more"). In subprocess mode a read is any in-process `process.env.X`, `'X' in process.env`
  or `karandash.env('X')`, and enumerating `process.env` (`Object.keys`, spreading it) counts as reading every
  variable. Probes for names that are not set are recorded too. Children that inherit the environment are not
  observed: `child_process.exec('echo $X')` is not recorded. In sandbox mode only `karandash.env()` can read the
  environment. Denylisted names are matched ignoring case (`Path` is treated like `PATH`). On native targets the
  environment is listed through `environ`, which must not race with `setenv` from another thread.

**Migration:** add `env:` with the names from the warning to each script entry. In the next release a script
without `env` will see no environment variables. `env` inherits through `extends:` with the rest of the `scripts:`
block.

## Execution Modes

### sandbox (default)

Scripts run inside the embedded [QuickJS](https://bellard.org/quickjs/) engine - a lightweight JavaScript interpreter bundled with the Karandash binary. No external runtime is required.

- No filesystem access
- No network access
- No access to environment variables (except via `karandash.env()`, limited by `env` when declared)
- Runs on all platforms without additional dependencies
- Suitable for logic-only checks against the MR data

### subprocess

Scripts are executed via `node` (Node.js). The `node` binary must be present on `PATH`.

- Full Node.js APIs available (`fs`, `path`, `child_process`, etc.)
- Network access available
- Environment variables available, limited to `env` when declared and never those in `KarandashEnvSecurity.DENYLIST` (see [Security Considerations](#security-considerations))
- Higher startup overhead than sandbox mode
- Requires explicit opt-in (`mode: subprocess`)

Use subprocess mode only when your script genuinely needs filesystem or network access. For all other cases, prefer sandbox mode.

## Karandash API

Scripts communicate with Karandash through the `karandash` global object.

### `karandash.mr`

The full MergeRequest object for the current validation run.

```javascript
const mr = karandash.mr;

console.log(mr.title);          // PR/MR title string
console.log(mr.description);    // PR/MR body/description string
console.log(mr.sourceBranch);   // source branch name
console.log(mr.targetBranch);   // target branch name
console.log(mr.author.username); // author username string
console.log(mr.changedFiles);   // array of changed file paths
console.log(mr.additions);      // total lines added
console.log(mr.deletions);      // total lines removed
console.log(mr.commits);        // array of commit objects
```

### Reporting Functions

| Function | Severity | Description |
|----------|----------|-------------|
| `karandash.fail(msg, file?, line?, fix?)` | error | Reports an error finding. Causes validation to fail. |
| `karandash.warn(msg, file?, line?, fix?)` | warning | Reports a warning finding. Does not fail validation by default. |
| `karandash.message(msg, file?, line?, fix?)` | info | Reports an informational finding. |
| `karandash.log(msg)` | debug | Writes a debug message to Karandash logs. Not visible in reporters. |

The optional `file` and `line` parameters attach the finding to a specific location in the diff, enabling inline annotations in supported reporters (SARIF, reviewdog, comment reporter).

Keep `msg` to the problem and pass the fix as the optional `fix`: one imperative sentence, with the exact value where
possible. It becomes the finding's suggestion, which reporters show as the "how to fix" line. Pass `null` for `file`
and `line` to give a fix without a location. Calls with fewer arguments work as before. Typings for editors:
`tools/examples/karandash-script.d.ts` in the repository.

```javascript
// Simple message
karandash.fail("PR title is too short");

// With file location for inline annotation
karandash.warn("Consider adding a test for this change", "src/main/App.kt", 42);

// With a fix (the finding suggestion)
karandash.fail("PR title has no ticket reference", null, null, "Prefix the title with the ticket, e.g. PROJ-123: ...");
```

### `karandash.env(name)`

Reads an environment variable. It returns `null` if the variable is not set, is in `KarandashEnvSecurity.DENYLIST`, or is missing from the entry's declared `env` list. Without `env`, any other variable of the Karandash process, `JIRA_TOKEN` included, is returned and the read is warned about (see [Script environment](#script-environment-env)).

```javascript
const token = karandash.env("JIRA_TOKEN");
if (!token) {
    karandash.warn("JIRA_TOKEN is not set - skipping JIRA validation");
}
```

## Danger.js Compatibility

Scripts can use Danger.js-style globals for teams migrating from Danger:

| Danger global | Equivalent |
|---------------|-----------|
| `danger.gitlab.mr` | `karandash.mr` |
| `danger.github.pr` | `karandash.mr` |
| `fail(msg, file?, line?, fix?)` | `karandash.fail(msg, file?, line?, fix?)` |
| `warn(msg, file?, line?, fix?)` | `karandash.warn(msg, file?, line?, fix?)` |
| `message(msg, file?, line?, fix?)` | `karandash.message(msg, file?, line?, fix?)` |
| `markdown(msg)` | `karandash.message(msg)` |

These compatibility globals are injected automatically - no import required.

```javascript
// Danger.js-style script - works without modification
const { title } = danger.github.pr;

if (title.includes("WIP")) {
    warn("This PR is marked as WIP");
}
```

## Example Scripts

### Title and description checks

```javascript
const mr = karandash.mr;

if (mr.title.length < 10) {
    karandash.fail("PR title is too short (minimum 10 characters)");
}

if (!mr.description || mr.description.trim().length === 0) {
    karandash.warn("PR has no description", null, null, "Add a description summarising what changed and why.");
}

if (mr.description && mr.description.match(/\bTODO\b|\bFIXME\b|\bWIP\b/i)) {
    karandash.warn("Description contains TODO/FIXME/WIP markers", null, null,
        "Resolve or remove the TODO/FIXME/WIP markers from the description.");
}
```

### File-based checks (sandbox mode)

```javascript
const mr = karandash.mr;

const migrationFiles = (mr.changedFiles || []).filter(f => f.includes("migration"));
const schemaFiles = (mr.changedFiles || []).filter(f => f.endsWith("schema.sql"));

if (migrationFiles.length > 0 && schemaFiles.length === 0) {
    karandash.warn("Migration files changed without a schema update", null, null,
        "Update schema.sql to match the migration, or drop the migration.");
}

if ((mr.changedFiles || []).length > 20) {
    karandash.warn("This PR touches " + mr.changedFiles.length + " files", null, null,
        "Split the PR into parts of at most 20 files.");
}
```

### External API check (subprocess mode)

```javascript
// mode: subprocess - requires Node.js on PATH
const https = require("https");

const token = karandash.env("JIRA_TOKEN");
const mr = karandash.mr;
const ticketMatch = mr.title.match(/([A-Z]+-\d+)/);

if (!ticketMatch) {
    karandash.fail("PR title has no JIRA ticket reference", null, null,
        "Add the ticket key to the title, e.g. PROJ-123: ...");
}
```

## Security Considerations

- **Sandbox is the default and recommended mode.** Scripts run in an isolated QuickJS environment with no access to the host filesystem or network.
- **subprocess mode requires explicit opt-in** via `mode: subprocess`. Only use it when Node.js APIs are genuinely needed.
- Scripts run with the same OS user as the Karandash process. In subprocess mode, apply the principle of least privilege to the CI runner.
- **Declare `env` on every script.** Without it, a subprocess script sees every environment variable of the Karandash process except those in `KarandashEnvSecurity.DENYLIST` (`PATH`, `HOME`, `USER`, `SHELL`, `LOGNAME`, `LANG`, SSH agent and key variables, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`, `GOOGLE_APPLICATION_CREDENTIALS`). Platform and AI tokens such as `GITLAB_TOKEN`, `CI_JOB_TOKEN` or `ANTHROPIC_API_KEY` are **not** in the denylist and are readable through `process.env`. With `env` declared, only the listed names reach `process.env` and `karandash.env()`.
- Script execution time is bounded by the `timeout` field (default 30 seconds). Long-running scripts are terminated automatically.

### Subprocess environment: threat model and residuals

The threat is a subprocess script reading a denylisted variable, or a secret Karandash holds only in memory, through a channel other than `process.env`. Karandash removes the denylisted variables from node's environment when it starts node, not only from `process.env`, so they are absent from node's own initial environment (`/proc/self/environ`, `ps -E`). On Linux it also marks itself non-dumpable (`prctl(PR_SET_DUMPABLE, 0)`) before the first subprocess script. After that, same-user processes cannot read `/proc/<karandash-pid>/environ`, `mem` or `maps`, and cannot `ptrace` it. Children are not affected, because `execve` resets the flag.

If the `prctl` call fails, Karandash logs a warning and runs the script anyway; the script can then read `/proc/<karandash-pid>/environ`.

Side effects of the non-dumpable flag, from the first subprocess script to the end of the run: Karandash writes no core dump, and `jcmd`, `jstack`, debuggers and profilers running as the same user cannot attach.

What is left:

- **Ancestor processes.** The CI job shell, the runner and the container's PID 1 usually carry the same variables and stay readable through `/proc/<pid>/environ` by any process of the same user. Karandash cannot change other processes. Run Karandash under a separate user or PID namespace (for example a dedicated container step) if scripts are untrusted.
- **macOS.** There is no non-dumpable flag. Any same-user process can read Karandash's initial environment through `KERN_PROCARGS2` (`ps -E`).
- **macOS watchdog.** With a script `timeout`, macOS runs the script under a small `sh` watchdog (there is no `timeout(1)`). That `sh` is node's parent and keeps the full environment until the script ends; only node itself starts without the denylisted (and, with `env`, undeclared) variables.
- **Windows.** The Windows runner starts `node` with `CreateProcessW` and an explicit environment block without the denylisted (and, with `env`, undeclared) variables, so node's initial environment does not hold them. Windows has no per-process lock comparable to the non-dumpable flag, so Karandash's own environment stays readable by same-user processes.
- **Non-denylisted variables.** Without `env`, every other variable is visible (see above). With `env`, every variable of the Karandash process that is not declared is removed at exec together with the denylist (Linux, macOS, Windows, JVM), so node's own initial environment holds only the declared names. Ancestor processes and the macOS watchdog above still carry them.

