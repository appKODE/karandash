# Standalone JavaScript Plugin Example

A complete, self-contained example of a Karandash rule plugin written in JavaScript (ES Module format).

## What This Plugin Does

Provides two validation rules:

1. **js-title-length**: Validates MR title length (min/max bounds)
2. **js-description-required**: Ensures MR has a non-empty description

**Example validations:**
- OK: Title: "Add user authentication system" (within bounds)
- No: Title: "Fix" (too short)
- No: Title: "Very long title that exceeds the maximum allowed length..." (too long)
- No: Missing or empty description

## Prerequisites

- Karandash with JavaScript plugin support (requires JDK 17+)
- QuickJS runtime (automatically included with Karandash)

No build step required - pure JavaScript!

## Installation

### Option 1: Using Karandash CLI

```bash
karandash plugin install example-rule.mjs
```

### Option 2: Manual Installation

```bash
mkdir -p ~/.karandash/plugins
cp example-rule.mjs ~/.karandash/plugins/
```

### Verify Installation

```bash
karandash plugin list
```

You should see "Example JS Rule Plugin" in the list.

## Configuration

Add the rules to your `karandash.config.kts`:

```kotlin
rules {
    // Title length validation
    rule("js-title-length") {
        severity = error
        config {
            "minLength" to 10
            "maxLength" to 100
        }
    }

    // Description required validation
    rule("js-description-required") {
        severity = error
        config {
            "minLength" to 20
            "allowEmpty" to false
        }
    }
}
```

### Configuration Options

#### js-title-length

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `minLength` | Number | `10` | Minimum title length in characters |
| `maxLength` | Number | `100` | Maximum title length in characters |

#### js-description-required

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `minLength` | Number | `20` | Minimum description length in characters |
| `allowEmpty` | Boolean | `false` | Whether to allow empty descriptions |

### Example Configurations

**Strict title requirements:**
```kotlin
rules {
    rule("js-title-length") {
        severity = error
        config {
            "minLength" to 20
            "maxLength" to 72
        }
    }
}
```

**Lenient description:**
```kotlin
rules {
    rule("js-description-required") {
        severity = warning
        config {
            "minLength" to 10
            "allowEmpty" to true
        }
    }
}
```

## Testing

Test the plugin directly with Node.js:

```bash
node example-rule.mjs
```

This loads the module and verifies it exports correctly. To test validation logic:

```bash
node test-plugin.js
```

(Create `test-plugin.js` with sample MR data - see example below)

## Project Structure

```
standalone-js-plugin/
├── example-rule.mjs    # Plugin implementation (ES Module)
├── package.json        # NPM metadata (optional)
└── README.md          # This file
```

## How It Works

### 1. ES Module Export

The plugin exports a default object implementing the Karandash JS plugin interface:

```javascript
export default {
  id: "example-js-plugin",
  name: "Example JS Rule Plugin",
  version: "1.0.0",
  type: "RULE",

  initialize(context) { /* ... */ },
  shutdown() { /* ... */ },
  contributedRules() { /* ... */ },
  validate(ruleId, mergeRequest, config) { /* ... */ }
};
```

### 2. Plugin Interface

Required fields and methods:

- **id**: Unique plugin identifier
- **name**: Human-readable plugin name
- **version**: Semantic version string
- **type**: Plugin type (`"RULE"`, `"REPORTER"`, or `"PROVIDER"`)
- **initialize(context)**: Called when plugin loads
- **shutdown()**: Called when plugin unloads
- **contributedRules()**: Returns array of rule IDs this plugin provides
- **validate(ruleId, mr, config)**: Validates a merge request

### 3. Sandboxed Execution

JavaScript plugins run in a QuickJS sandbox with:
- OK: Full JavaScript ES2022+ support
- OK: Access to provided MR data and configuration
- No: No file system access
- No: No network access
- No: No access to Node.js built-in modules

This ensures plugins cannot perform malicious operations.

### 4. MergeRequest Object

The `mergeRequest` parameter provides:

```javascript
{
  title: "string",              // MR title
  description: "string",         // MR description (may be null)
  sourceBranch: "string",        // Source branch name
  targetBranch: "string",        // Target branch name
  author: "string",              // Author username
  labels: ["string"],            // Applied labels
  commits: [                     // Commit history
    {
      sha: "string",
      message: "string",
      author: "string"
    }
  ],
  changedFiles: [                // Modified files
    {
      path: "string",
      status: "added|modified|deleted",
      additions: 10,
      deletions: 5
    }
  ]
}
```

### 5. Finding Object

Validation results must be objects with:

```javascript
{
  rule: "rule-id",              // Rule that generated this finding
  message: "Error message",      // Human-readable message
  severity: "ERROR|WARNING|INFO", // Severity level
  location: {                    // Optional: specific location
    path: "src/file.js",
    line: 42,
    column: 10
  }
}
```

## Customizing This Plugin

To create your own JS rule plugin:

### 1. Copy the File

```bash
cp example-rule.mjs my-custom-rule.mjs
```

### 2. Update Plugin Metadata

Edit the exported object:

```javascript
export default {
  id: "my-custom-plugin",           // Change this
  name: "My Custom Rule Plugin",    // Change this
  version: "1.0.0",
  type: "RULE",
  // ...
};
```

### 3. Implement Your Rules

Add your rule IDs:

```javascript
contributedRules() {
  return ["my-rule-one", "my-rule-two"];
}
```

Implement validation logic:

```javascript
validate(ruleId, mergeRequest, config) {
  switch (ruleId) {
    case "my-rule-one":
      return validateRuleOne(mergeRequest, config);
    case "my-rule-two":
      return validateRuleTwo(mergeRequest, config);
    default:
      return [];
  }
}
```

### 4. Test Locally

Create `test-plugin.js`:

```javascript
import plugin from './my-custom-rule.mjs';

// Initialize
const context = { hostVersion: "0.1.0" };
plugin.initialize(context);

// Test data
const testMR = {
  title: "Test MR",
  description: "Test description",
  sourceBranch: "feature/test",
  targetBranch: "main",
  author: "testuser",
  labels: [],
  commits: [],
  changedFiles: []
};

// Validate
const rules = plugin.contributedRules();
rules.forEach(ruleId => {
  const findings = plugin.validate(ruleId, testMR, {});
  console.log(`${ruleId}:`, findings);
});

// Shutdown
plugin.shutdown();
```

Run:

```bash
node test-plugin.js
```

### 5. Install and Use

```bash
cp my-custom-rule.mjs ~/.karandash/plugins/
karandash plugin list
```

## Advanced Topics

### Accessing Configuration

Configuration comes from `karandash.config.kts`:

```javascript
function myValidation(mr, config) {
  const threshold = config.threshold || 50;  // Default value
  const enabled = config.enabled !== false;  // Default true
  const pattern = config.pattern || ".*";    // Default pattern

  // Use configuration...
}
```

### Multiple Findings

Return an array of findings:

```javascript
function myValidation(mr, config) {
  const findings = [];

  if (condition1) {
    findings.push({
      rule: "my-rule",
      message: "Issue 1",
      severity: "ERROR",
      location: null
    });
  }

  if (condition2) {
    findings.push({
      rule: "my-rule",
      message: "Issue 2",
      severity: "WARNING",
      location: null
    });
  }

  return findings;
}
```

### File-Specific Findings

```javascript
function validateFiles(mr, config) {
  const findings = [];

  mr.changedFiles.forEach(file => {
    if (file.path.endsWith('.js') && file.additions > 500) {
      findings.push({
        rule: "file-size-check",
        message: `File ${file.path} has too many additions (${file.additions})`,
        severity: "WARNING",
        location: {
          path: file.path,
          line: null,
          column: null
        }
      });
    }
  });

  return findings;
}
```

### Commit Validation

```javascript
function validateCommits(mr, config) {
  const findings = [];
  const pattern = /^(feat|fix|docs|style|refactor|test|chore):/;

  mr.commits.forEach(commit => {
    if (!pattern.test(commit.message)) {
      findings.push({
        rule: "commit-format",
        message: `Commit ${commit.sha.substring(0, 7)} doesn't follow conventional format`,
        severity: "WARNING",
        location: null
      });
    }
  });

  return findings;
}
```

### Using Modern JavaScript

QuickJS supports modern JavaScript features:

```javascript
// Async/await (if needed for complex operations)
async function myValidation(mr, config) {
  // Note: No network/file access in sandbox
  return [];
}

// Destructuring
function validateTitlePattern({ title }, { pattern = ".*" }) {
  return new RegExp(pattern).test(title) ? [] : [/* findings */];
}

// Array methods
function checkLabels(mr, config) {
  const required = config.requiredLabels || [];
  const missing = required.filter(label => !mr.labels.includes(label));
  return missing.map(label => ({
    rule: "required-labels",
    message: `Missing required label: ${label}`,
    severity: "ERROR",
    location: null
  }));
}
```

## Troubleshooting

**Plugin not discovered:**
- Ensure file has `.mjs` extension
- Verify file is in `~/.karandash/plugins/` directory
- Check that `export default` is present

**Validation not running:**
- Verify rule ID in `karandash.config.kts` matches `contributedRules()`
- Check plugin logs for initialization errors
- Ensure `initialize()` returns `true`

**Syntax errors:**
- QuickJS supports ES2022+ but not all Node.js APIs
- Avoid `require()` - use ES modules (`import/export`)
- No access to `fs`, `http`, `path`, etc.

**Testing fails:**
- Use Node.js 14+ for local testing
- Ensure `package.json` has `"type": "module"`
- Check that test data matches expected MR structure

## Performance Tips

- Keep validation logic simple and fast
- Avoid complex regex on large texts
- Don't iterate unnecessarily over commits/files
- Return early when validation passes
- Cache expensive computations if needed

## License

This example is public domain. Copy and modify freely for your own plugins.
