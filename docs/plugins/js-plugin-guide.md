# JavaScript Plugin Development Guide

Build plugins in JavaScript for rapid development and easy deployment.

## Overview

JavaScript plugins run in a sandboxed QuickJS (JavaScript/ES2021) environment. Use them when you need:

- **Rapid development**: Write and test quickly
- **Simple logic**: Configuration validation, string matching, basic calculations
- **Easy deployment**: No compilation required, copy `.mjs` files
- **Existing JS/TS code**: Reuse JavaScript libraries and scripts

### When to Use JS Plugins

Choose JS plugins for:
- Quick validation rules
- Regex-based checks
- Configuration validation
- Data transformation
- Integration with JavaScript libraries

### When to Use Other Plugin Types

- **Native plugins**: Performance-critical code, system integration, existing C/C++/Rust code
- **Kotlin plugins**: Full JVM ecosystem, extensive libraries, type safety

## Module Export Contract

JavaScript plugins export a default object with required and optional fields.

### Required Fields

Every plugin must export these fields:

```javascript
export default {
  // Module identity
  id: "my-rule-plugin",
  name: "My Rule Plugin",
  version: "1.0.0",
  type: "RULE",  // "RULE" | "REPORTER" | "PLATFORM_SERVICE"

  // Lifecycle methods
  initialize(context) {
    // Called once when plugin loads
    // context.hostVersion: Karandash version
    // context.pluginConfig: Plugin configuration
    return true;  // Return false to fail initialization
  },

  shutdown() {
    // Called once when plugin unloads
    // Use for cleanup
  }
};
```

### Rule Module Methods

For `type: "RULE"` plugins:

```javascript
export default {
  // ... required fields ...
  type: "RULE",

  /**
   * List of rule IDs this plugin provides.
   * @returns {string[]} Array of rule identifiers
   */
  contributedRules() {
    return ["my-rule-1", "my-rule-2"];
  },

  /**
   * Validate a merge request against a rule.
   * @param {string} ruleId - The rule being validated
   * @param {MergeRequest} mergeRequest - The MR to validate
   * @param {Object} config - Rule configuration
   * @returns {Finding[]} Array of validation findings
   */
  validate(ruleId, mergeRequest, config) {
    const findings = [];

    if (ruleId === "my-rule-1") {
      // Validation logic here
      // Return findings if violations found
    }

    return findings;
  }
};
```

### Reporter Module Methods

For `type: "REPORTER"` plugins:

```javascript
export default {
  // ... required fields ...
  type: "REPORTER",

  /**
   * The format ID this reporter handles.
   * @type {string} Format identifier (e.g., "json", "webhook", "slack")
   */
  formatId: "my-format",

  /**
   * Generate a report from validation findings.
   * @param {Finding[]} findings - Validation findings
   * @param {boolean} passed - Whether validation passed overall
   * @param {Object} config - Reporter configuration
   * @returns {string|null} Optional output string
   */
  report(findings, passed, config) {
    // Generate report
    return JSON.stringify({
      status: passed ? "passed" : "failed",
      findingCount: findings.length,
      findings: findings
    }, null, 2);
  }
};
```

### Serving Several Module Types

One module can serve several types. List them in `describe().types`, with `type` as the first
entry; the host registers one part per declared type under the same `id`, and each part answers only
as its own type. A reporter also needs `formatId`, a config format `configFormatId` and
`fileExtensions`, so the two ids never collide:

```javascript
export default {
  id: "my-composite", name: "My Composite", version: "1.0.0", type: "RULE",
  describe() {
    return { id: "my-composite", types: ["RULE", "REPORTER", "PROFILE", "CLI_COMMAND", "CONFIG_FORMAT"], properties: {} };
  },
  contributedRules() { return ["my-rule"]; },
  validate(ruleId, mr, config) { return []; },
  formatId: "my-report",
  report(findings, passed, config) { return `passed=${passed}`; },
  // profileId/ruleConfigs(), cliCommands(), configFormatId/fileExtensions/parseToMap() as for single-type modules
};
```

All parts share one guest: `initialize` runs once per part the host uses, with that part's config.
`AI_PIPELINE_STAGE` has no SDK interface and is skipped with a warning, as are unknown type names.
Full example: `tools/examples/js-plugins-all-types/composite-plugin.mjs`.

### Running CLI commands

`cliCommands()` lists commands (with nested `subcommands`). To make them runnable, also export
`runCliCommand(command, args)`: `command` is the path of the leaf that ran (names joined by a space,
e.g. `"tool greet"`), `args` the remaining arguments. Return
`{ exitCode, stdout: [lines], stderr: [lines] }`; the host prints the lines and exits with `exitCode`.
Without `runCliCommand` the commands are listed in `--help` but not runnable.

```javascript
runCliCommand(command, args) {
  return { exitCode: 0, stdout: [`ran ${command} ${args.join(" ")}`], stderr: [] };
}
```

## Data Types

### MergeRequest

The merge request being validated:

```javascript
{
  id: "123",
  iid: 456,
  title: "Fix: handle null values in parser",
  description: "Prevent crashes when input is null",
  source_branch: "feature/null-handling",
  target_branch: "main",
  author: {
    username: "alice",
    name: "Alice Developer",
    email: "alice@example.com"
  },
  state: "opened",
  draft: false,
  labels: ["bug", "critical"],
  commits: [
    {
      sha: "abc123",
      message: "Fix null handling in parser",
      author: { name: "Alice Developer", email: "alice@example.com" },
      timestamp: "2024-02-10T10:30:00Z"
    }
  ],
  changes: [
    {
      path: "src/parser.js",
      changeType: "MODIFIED",
      additions: 5,
      deletions: 2
    }
  ]
}
```

Note: the merge request is serialized from the shared Kotlin `MergeRequest` model, so a few fields keep
their `@SerialName` wire keys (`source_branch`, `target_branch`) instead of being camelCased for JS.

### Finding

Validation result for a violation:

```javascript
{
  severity: "ERROR",  // "ERROR" | "WARNING" | "INFO"
  message: "Descriptive error message",
  rule: "rule-id",
  location: {
    type: "file",  // sealed on `type`: "file" {path, line?, endLine?} or "commit" {sha}
    path: "path/to/file.js",
    line: 42
  },
  // OR location: null  (for title/description violations)
  suggestion: "Short actionable hint for the MR author",  // optional
  explanation: "Longer explanation of why this rule exists"  // optional
}
```

### Configuration

Plugins receive configuration as a plain JavaScript object:

```javascript
{
  minTitleLength: 10,
  requireIssueKey: true,
  customPattern: "^[A-Z]"
}
```

Access values directly:

```javascript
validate(ruleId, mr, config) {
  const minLength = config.minTitleLength || 10;
  const pattern = config.customPattern || ".*";
  // ...
}
```

An `AI_ANALYZER` plugin's `analyze(mr, budget, model, config)` gets the whole `ai:` block as `config`;
its own settings are at `config.rules[<analyzerId>]`. The host adds `aiServiceAvailable`, `resolvedPrompt`,
`instructions` (extra instructions only) and `extraInstructions` (shared MR context blocks plus the extra
instructions, for a plugin that calls its own provider).

Typings for all of this live in `tools/examples/karandash-plugin.d.ts`.

## Host Services

`initialize(ctx)` receives the host context. Keep it in a module variable: its methods are the plugin's way out of
the sandbox. Every call is synchronous and never throws; a failure comes back as `{ err }`.

| Call | Returns |
|------|---------|
| `ctx.log(msg)`, `ctx.log.info/warn/error(msg)`, `ctx.warn(msg)` | - |
| `ctx.cache.get(cache, key)` / `ctx.cache.put(cache, key, value)` | string or `null`; keys are scoped to this plugin |
| `ctx.content.resolve(source)` | `{ content }` or `{ err }`; `source` is a content source as in config: inline (`"text"`, `{ inline }`) or `{ file }` inside the config directory or its git checkout (1 MB cap); `{ url }` and `{ "repo-file" }` answer `{ err }` (no network egress for guests) |
| `ctx.ai.complete(request)` | `{ content, model, usage: { promptTokens, completionTokens, totalTokens } }` or `{ err, retryable? }` |
| `ctx.env(name)`, `ctx.resolvePattern(pattern)` | string or `null` |
| `ctx.skip(reason)` | `{}`, or `{ err }` outside `analyze`; the analyzer declines this run, like returning `{ skip: reason }` from `analyze` |

`ctx.ai.complete` takes `{ prompt, systemPrompt?, model?, maxTokens?, temperature?, jsonMode? }` or a plain prompt
string. Inside `analyze` it uses that call's AI service, model and budget: `maxTokens` is checked against the budget
first (`{ err: "budget exhausted" }`) and the tokens used are recorded. The host adds the shared MR context blocks,
so build the prompt from `config.instructions`, not `extraInstructions`. In an `AI_INTERCEPTOR`, host service
`ai.proceed` continues the chain.

Time spent inside a host call does not count against the plugin's call timeout. A host call made while another one
of the same plugin is still running answers `{ err: "reentrant" }`.

```javascript
/// <reference path="../karandash-plugin.d.ts" />
let ctx = null;
export default {
  id: "my-analyzer", name: "My Analyzer", version: "1.0.0", type: "AI_ANALYZER",
  initialize(context) { ctx = context; return true; },
  analyze(mr, budget, model, config) {
    const reply = ctx.ai.complete({ prompt: [config.instructions, mr.title].filter(Boolean).join("\n\n") });
    if ("err" in reply) { ctx.log.warn(reply.err); return []; }
    return [{ severity: "INFO", message: reply.content, rule: "my-analyzer", location: null }];
  }
};
```

See `tools/examples/js-plugins-all-types/ai-analyzer-plugin.mjs` for a complete example.

## Sandboxing

JavaScript plugins run in a restricted environment for security. Understand what's allowed and blocked.

### What's Allowed

- Pure JavaScript computation
- ES2021 features (async/await, classes, modules, etc.)
- Array and string methods
- Regular expressions
- JSON parsing/serialization
- Math operations
- Built-in objects (Date, Map, Set, etc.)
- console.log() for debugging

### What's Blocked

- File system access (`fs` module) - config-relative files are readable through `ctx.content.resolve`
- Network requests (`fetch`, `XMLHttpRequest`) - the host's AI service is reachable through `ctx.ai.complete`
- Process spawning (`child_process`)
- System commands
- Loading external modules
- Accessing private Karandash APIs
- setTimeout/setInterval (no async I/O)

### Working Within Limits

Process data synchronously:

```javascript
// GOOD: Pure computation
validate(ruleId, mr, config) {
  const titleRegex = new RegExp(config.pattern || ".*");
  if (!titleRegex.test(mr.title)) {
    return [{
      severity: "ERROR",
      message: "Title doesn't match pattern",
      rule: ruleId,
      location: null
    }];
  }
  return [];
}

// BAD: Network request (blocked)
validate(ruleId, mr, config) {
  // fetch() is blocked!
  const response = await fetch("https://api.example.com/check");
  // ...
}

// BAD: File I/O (blocked)
validate(ruleId, mr, config) {
  // fs module is blocked!
  const rules = fs.readFileSync("rules.json");
  // ...
}
```

For integration with external systems, use a native or Kotlin plugin instead.

## Example Walkthrough

Here's the example JS plugin at `tools/examples/js-plugins-all-types/rule-plugin.mjs`.

### Module Identity

```javascript
export default {
  id: "js-title-format",
  name: "JS Title Format Validator",
  version: "1.0.0",
  type: "RULE",
```

Basic metadata identifying the plugin.

### Lifecycle

```javascript
  initialize(context) {
    console.log("JS plugin initialized:", context.hostVersion);
    return true;
  },

  shutdown() {
    console.log("JS plugin shutting down");
  },
```

Simple lifecycle: log initialization and shutdown. Return `true` from initialize to succeed.

### Contributed Rules

```javascript
  contributedRules() {
    return ["title-prefix-check"];
  },
```

This plugin provides one rule: `title-prefix-check`.

### Validation Logic

```javascript
  validate(ruleId, mergeRequest, config) {
    const findings = [];

    if (ruleId === "title-prefix-check") {
      // Check if title starts with a bracket prefix like [FEAT]
      if (!mergeRequest.title.match(/^\[[\w]+\]/)) {
        findings.push({
          severity: "WARNING",
          message: "Title should start with a bracket prefix like [FEAT], [FIX], [DOCS]",
          rule: "title-prefix-check",
          location: null
        });
      }
    }

    return findings;
  }
};
```

The validation logic is straightforward:
1. Match rule ID
2. Apply validation logic (regex check)
3. Push findings if violations found
4. Return findings array

## Installation

### Manual Installation

Copy the plugin file to the Karandash plugins directory:

```bash
cp my-plugin.mjs ~/.karandash/plugins/
```

### Via Configuration

Reference plugins in your Karandash configuration:

```toml
[[plugins]]
id = "js-title-format"
path = "./plugins/title-format-rule.mjs"

[plugins.config]
requirePrefix = true
```

### Directory Structure

Organize multi-file plugins as a package:

```
my-js-plugin/
  package.json          # Optional, but recommended
  index.mjs             # Main entry point
  utils.mjs             # Helper modules
  rules/
    rule1.mjs
    rule2.mjs
```

Use ES6 imports within the plugin directory:

```javascript
// index.mjs
import { validateTitle } from "./utils.mjs";

export default {
  id: "multi-file-plugin",
  // ...
  validate(ruleId, mr, config) {
    // Use imported functions
    return validateTitle(mr.title);
  }
};
```

## Best Practices

### Keep Logic Simple

Focus on data validation and transformation:

```javascript
// GOOD: Simple, focused logic
validate(ruleId, mr, config) {
  if (mr.title.length < 5) {
    return [{
      severity: "ERROR",
      message: "Title too short",
      rule: ruleId,
      location: null
    }];
  }
  return [];
}

// COMPLEX: Don't do this in JS plugins
validate(ruleId, mr, config) {
  // Complex algorithms better in native plugins
  const analysis = performDeepCodeAnalysis(mr.changes);
  // ...
}
```

### Use Modern JavaScript

Use ES2021 features:

```javascript
// GOOD: Modern syntax
validate(ruleId, mr, config) {
  const findings = mr.commits
    .filter(c => c.message.length === 0)
    .map(c => ({
      severity: "WARNING",
      message: "Empty commit message",
      rule: ruleId,
      location: null
    }));
  return findings;
}
```

### Return Proper Finding Objects

Always include all required fields. Use `suggestion` and `explanation` to help MR authors fix issues:

```javascript
// CORRECT - with helpful suggestion and explanation
{
  severity: "ERROR",
  message: "MR title does not match required pattern",
  rule: "rule-id",
  location: null,  // or { type: "file", path: "path", line: 42 }
  suggestion: "Start your title with a type prefix like 'feat: ' or 'fix: '",
  explanation: "Conventional commits enable automated changelog generation"
}

// MINIMAL - required fields only
{
  severity: "ERROR",
  message: "Clear, actionable message",
  rule: "rule-id",
  location: null
}

// INCOMPLETE
{
  message: "Title too short"  // Missing severity, rule, location
}
```

### Handle Errors Gracefully

Use try/catch for unexpected input:

```javascript
validate(ruleId, mr, config) {
  try {
    // Parse custom config format
    const pattern = new RegExp(config.pattern || ".*");

    if (!pattern.test(mr.title)) {
      return [{
        severity: "ERROR",
        message: "Title doesn't match pattern",
        rule: ruleId,
        location: null
      }];
    }

    return [];
  } catch (error) {
    console.log("Pattern error:", error.message);
    // Graceful degradation: return empty findings
    return [];
  }
}
```

### Use console.log for Debugging

Debug output is captured and available in verbose mode:

```javascript
validate(ruleId, mr, config) {
  console.log("Validating rule:", ruleId);
  console.log("MR title:", mr.title);
  console.log("Config:", config);

  // Validation logic...
  return findings;
}
```

## Example: Complete Rule Plugin

Here's a practical example with multiple rules:

```javascript
// commit-format-plugin.mjs
export default {
  id: "commit-format",
  name: "Commit Format Validator",
  version: "1.0.0",
  type: "RULE",

  initialize(context) {
    console.log("Commit format plugin loaded");
    return true;
  },

  shutdown() {
    // No cleanup needed
  },

  contributedRules() {
    return ["commit-message-format", "commit-message-length"];
  },

  validate(ruleId, mergeRequest, config) {
    switch (ruleId) {
      case "commit-message-format":
        return this.validateFormat(mergeRequest, config);
      case "commit-message-length":
        return this.validateLength(mergeRequest, config);
      default:
        return [];
    }
  },

  validateFormat(mr, config) {
    const pattern = config.pattern || "^[A-Z][a-z].*";
    const regex = new RegExp(pattern);
    const findings = [];

    for (const commit of mr.commits) {
      if (!regex.test(commit.message)) {
        findings.push({
          severity: "ERROR",
          message: `Commit message doesn't match format: "${commit.message}"`,
          rule: "commit-message-format",
          location: null
        });
      }
    }

    return findings;
  },

  validateLength(mr, config) {
    const maxLength = config.maxLength || 72;
    const findings = [];

    for (const commit of mr.commits) {
      const lines = commit.message.split("\n");
      if (lines[0].length > maxLength) {
        findings.push({
          severity: "WARNING",
          message: `First line too long: ${lines[0].length} > ${maxLength}`,
          rule: "commit-message-length",
          location: null
        });
      }
    }

    return findings;
  }
};
```

## Example: Complete Reporter Plugin

A reporter that formats findings as markdown:

```javascript
// markdown-reporter.mjs
export default {
  id: "markdown-reporter",
  name: "Markdown Reporter",
  version: "1.0.0",
  type: "REPORTER",

  initialize(context) {
    return true;
  },

  shutdown() {},

  formatId() {
    return "markdown";
  },

  report(findings, passed, config) {
    let md = `# Validation Report\n\n`;

    md += passed
      ? "✅ All validations passed\n\n"
      : `❌ Validation failed (${findings.length} issues)\n\n`;

    if (findings.length === 0) {
      return md;
    }

    // Group findings by severity
    const bySeverity = {};
    for (const finding of findings) {
      if (!bySeverity[finding.severity]) {
        bySeverity[finding.severity] = [];
      }
      bySeverity[finding.severity].push(finding);
    }

    // Format by severity
    for (const severity of ["ERROR", "WARNING", "INFO"]) {
      const items = bySeverity[severity];
      if (!items) continue;

      const icon = severity === "ERROR" ? "🔴"
                 : severity === "WARNING" ? "🟡" : "🔵";

      md += `## ${icon} ${severity}s (${items.length})\n\n`;

      for (const finding of items) {
        md += `- **${finding.rule}**: ${finding.message}\n`;
        if (finding.location?.type === "file") {
          md += `  - File: ${finding.location.path}`;
          if (finding.location.line) {
            md += `:${finding.location.line}`;
          }
          md += "\n";
        }
      }
      md += "\n";
    }

    return md;
  }
};
```

## Kotlin/JS Compilation

You can also write plugins in Kotlin and compile to JavaScript:

### Setup

Create a Kotlin/JS project:

```gradle
plugins {
    kotlin("multiplatform") version "2.0.0"
}

kotlin {
    js(IR) {
        moduleName = "my-plugin"
        useEsModules()
        browser()
    }
}
```

### Example

```kotlin
// src/jsMain/kotlin/MyPlugin.kt
external fun require(module: String): dynamic

@JsExport
val default = object {
    val id = "kotlin-js-plugin"
    val name = "Kotlin/JS Plugin"
    val version = "1.0.0"
    val type = "RULE"

    fun initialize(context: dynamic): Boolean {
        console.log("Kotlin plugin initialized")
        return true
    }

    fun shutdown() {
        console.log("Kotlin plugin shutting down")
    }

    fun contributedRules(): Array<String> {
        return arrayOf("my-rule")
    }

    fun validate(ruleId: String, mr: dynamic, config: dynamic): Array<dynamic> {
        return arrayOf()  // No findings
    }
}
```

### Build

```bash
./gradlew jsBrowserDistribution
# Output: build/dist/js/productionExecutable/my-plugin.js
```

Compile to ES modules:

```bash
node --input-type=module < my-plugin.js > my-plugin.mjs
```

## Troubleshooting

### Plugin fails to load

Check the console output for errors. Common issues:

- **Syntax error**: Invalid JavaScript
- **Missing export**: Must export default object
- **Missing required fields**: Must have `id` and `name` (non-empty strings); `version` and `type` default to `"0.0.0"` and `"RULE"` if omitted, and `initialize`/`shutdown` are optional
- **Circular imports**: Check for module import cycles

### Validation not running

Verify the plugin is enabled in configuration:

```toml
[[plugins]]
id = "my-plugin"
path = "./my-plugin.mjs"
enabled = true
```

### Findings not returned

Make sure findings have all required fields:

```javascript
// GOOD
{
  severity: "ERROR",
  message: "Error description",
  rule: "rule-id",
  location: null
}

// BAD - missing fields
{
  message: "Error"
}
```

### Performance issues

JavaScript plugins may be slow for large datasets. Profile and optimize:

```javascript
validate(ruleId, mr, config) {
  console.time("validate");

  // Your logic here

  console.timeEnd("validate");
  return findings;
}
```

If performance is critical, consider a native plugin.

## Next Steps

- Review the example JS plugin in `tools/examples/js-plugins-all-types/`
- Check [JavaScript MDN docs](https://developer.mozilla.org/en-US/docs/Web/JavaScript/)
- Explore [QuickJS capabilities](https://bellard.org/quickjs/)
- See [Kotlin/JS guide](https://kotlinlang.org/docs/js-overview.html)
