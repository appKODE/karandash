# Reviewdog Integration Guide

Integrate Karandash with [reviewdog](https://github.com/reviewdog/reviewdog) to post inline annotations on pull requests and merge requests. Reviewdog transforms validation results into platform-specific comments for GitHub, GitLab, Bitbucket, and other version control systems.

## Overview

Reviewdog is a code review automation tool that reads diagnostic results and posts them as inline comments on code changes. Karandash outputs validation findings in reviewdog's `rdjson` or `rdjsonl` format, enabling automated code review feedback directly on modified lines.

### Benefits

- **Inline annotations**: Comments appear on exact lines with issues
- **Multi-platform support**: Works with GitHub, GitLab, Bitbucket, Gitea, and more
- **Automatic filtering**: Only shows issues on changed lines (by default)
- **Native integration**: Uses platform-native comment APIs
- **Zero additional setup**: Works alongside existing Karandash configuration

### Supported Platforms

| Platform | Reporter | Comment Type |
|----------|----------|--------------|
| **GitHub** | `github-pr-review` | PR review comments |
| **GitHub** | `github-pr-check` | Check annotations |
| **GitHub** | `github-check` | Check run summary |
| **GitLab** | `gitlab-mr-discussion` | Inline MR comments |
| **GitLab** | `gitlab-mr-commit` | Commit comments |
| **Bitbucket** | `bitbucket-code-report` | Code report (Cloud) |
| **Gitea** | `gitea-pr-review` | PR review comments |
| **Local** | `local` | Console output (for testing) |

---

## Output Formats

Karandash emits reviewdog findings in the `rdjson` or `rdjsonl` format.

### RDJSON Format

**Format**: Single JSON document with envelope structure

All findings wrapped in a diagnostic result object:

```json
{
  "source": {
    "name": "karandash",
    "url": "https://github.com/appKODE/karandash"
  },
  "diagnostics": [
    {
      "message": "Title too long",
      "location": {
        "path": "CHANGELOG.md",
        "range": {
          "start": {
            "line": 1,
            "column": 1
          }
        }
      },
      "severity": "WARNING",
      "code": {
        "value": "titleFormat"
      }
    }
  ]
}
```

**Usage**:
- Piping straight into reviewdog
- File-based output and later processing
- Easy to inspect and debug with `jq`

**Command**:
```bash
karandash validate --format rdjson | reviewdog -f=rdjson -name="karandash"
```

Or via a file:

```bash
karandash validate --format rdjson --output karandash.rdjson
cat karandash.rdjson | reviewdog -f=rdjson -name="karandash"
```

### RDJSONL Format

**Format**: Newline-delimited JSON, one bare diagnostic per line, no `source`/`diagnostics` envelope

```jsonl
{"message":"Title too long","location":{"path":"CHANGELOG.md","range":{"start":{"line":1,"column":1}}},"severity":"WARNING","code":{"value":"titleFormat"}}
{"message":"Missing description","location":{"path":""},"severity":"ERROR","code":{"value":"descriptionRequired"}}
```

**Usage**:
- Streaming output - reviewdog can start processing before validation finishes writing
- Line-oriented tooling (`grep`, `wc -l`, log pipelines) that doesn't parse a JSON envelope

**Command**:
```bash
karandash validate --format rdjsonl | reviewdog -f=rdjsonl -name="karandash"
```

Or via a file:

```bash
karandash validate --format rdjsonl --output karandash.rdjsonl
cat karandash.rdjsonl | reviewdog -f=rdjsonl -name="karandash"
```

---

## CLI Usage

### Basic Command

Run Karandash with reviewdog format and pipe to reviewdog:

```bash
karandash validate --format rdjson | reviewdog -f=rdjson -name="karandash" -reporter=github-pr-review
```

### With Output File

Output to file first, then process:

```bash
karandash validate --format rdjson --output results.rdjson
cat results.rdjson | reviewdog -f=rdjson -name="karandash" -reporter=github-pr-review
```

### With Custom Configuration

Specify custom rules while outputting for reviewdog:

```bash
karandash validate --config .karandash-strict.yml --format rdjson | \
  reviewdog -f=rdjson -name="karandash" -reporter=github-pr-review
```

### Suppress Errors

Prevent CI/CD failures on validation errors (useful for initial rollout):

```bash
karandash validate --format rdjson 2>/dev/null | \
  reviewdog -f=rdjson -name="karandash" -reporter=github-pr-review || true
```

### Reviewdog Options

Common reviewdog flags when using Karandash output:

```bash
# Format specification (required)
-f=rdjson               # Input format: rdjson

# Reporter selection (required in CI/CD)
-reporter=github-pr-review   # GitHub: inline review comments
-reporter=github-pr-check    # GitHub: check annotations
-reporter=gitlab-mr-discussion  # GitLab: inline MR comments
-reporter=local              # Debug: print to console

# Tool identification
-name="karandash"            # Tool name (appears in comments)

# Behavior control
-filter-mode=added           # Only show findings on added lines (default)
-filter-mode=diff_context    # Include context lines
-filter-mode=file            # Show all findings in modified files
-filter-mode=nofilter        # Show all findings

# Fail conditions
-fail-on-error=true          # Exit code 1 if findings exist
-fail-on-error=false         # Always exit code 0 (default for reviewdog)
```

---

## GitHub Actions Setup

### Step-by-Step Setup

1. Create `.github/workflows/karandash-reviewdog.yml`:

```yaml
name: Karandash with Reviewdog

on:
  pull_request:
    types: [opened, synchronize, reopened]

jobs:
  validate:
    runs-on: ubuntu-latest

    permissions:
      contents: read
      pull-requests: write
      checks: write

    steps:
      - name: Checkout code
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Set up reviewdog
        uses: reviewdog/action-setup@v1
        with:
          reviewdog_version: latest

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Download Karandash
        run: |
          wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar

      - name: Run Karandash with reviewdog
        env:
          REVIEWDOG_GITHUB_API_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          ./karandash validate --format rdjson 2>/dev/null | \
            reviewdog -f=rdjson \
              -name="karandash" \
              -reporter=github-pr-review \
              -fail-on-error=false || true
```

2. Commit and push:

```bash
git add .github/workflows/karandash-reviewdog.yml
git commit -m "Add Karandash + reviewdog workflow"
git push
```

3. Create a pull request to see inline annotations on modified lines.

### Using the Official Karandash Action

If using the official Karandash GitHub Action with reviewdog support:

```yaml
name: Karandash with Reviewdog

on:
  pull_request:
    types: [opened, synchronize, reopened]

jobs:
  validate:
    runs-on: ubuntu-latest

    permissions:
      contents: read
      pull-requests: write
      checks: write

    steps:
      - name: Checkout code
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Run Karandash validation
        uses: appKODE/karandash@v1.0.1
        with:
          reviewdog: 'pr-review'
```

### Reviewdog Reporter Options for GitHub

**PR Review Comments** (Recommended for file-based issues):
```yaml
-reporter=github-pr-review
```

Comments appear on the exact lines with issues. Users can reply and mark as resolved. Most visual and interactive.

**Check Annotations** (Better for non-file issues):
```yaml
-reporter=github-pr-check
```

Appears in the Checks tab with better filtering options. Good for summary-level findings (missing approvals, title format, etc.).

**Check Run Summary** (For overview):
```yaml
-reporter=github-check
```

Single check run result in Checks tab. Best for high-level pass/fail status.

### GitHub Actions Advanced Example

Filter findings and use multiple reporters:

```yaml
name: Karandash with Reviewdog (Advanced)

on:
  pull_request:
    types: [opened, synchronize, reopened]

jobs:
  validate:
    runs-on: ubuntu-latest

    permissions:
      contents: read
      pull-requests: write
      checks: write

    steps:
      - name: Checkout code
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Set up reviewdog
        uses: reviewdog/action-setup@v1

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Download Karandash
        run: |
          wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar

      # File-level issues go to PR review
      - name: Run Karandash (file-based rules) with reviewdog
        env:
          REVIEWDOG_GITHUB_API_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          ./karandash validate \
            --config .karandash-files.yml \
            --format rdjson 2>/dev/null | \
            reviewdog -f=rdjson \
              -name="karandash-files" \
              -reporter=github-pr-review \
              -filter-mode=diff_context \
              -fail-on-error=false || true

      # Summary-level issues go to checks
      - name: Run Karandash (summary rules) with reviewdog
        env:
          REVIEWDOG_GITHUB_API_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          ./karandash validate \
            --config .karandash-summary.yml \
            --format rdjson 2>/dev/null | \
            reviewdog -f=rdjson \
              -name="karandash-summary" \
              -reporter=github-pr-check \
              -fail-on-error=false || true
```

---

## GitLab CI Setup

### Step-by-Step Setup

1. Create or update `.gitlab-ci.yml`:

```yaml
karandash-reviewdog:
  stage: test
  image: eclipse-temurin:17-jre

  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'

  before_script:
    # Download karandash
    - wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
    # Download reviewdog
    - curl -sfL https://raw.githubusercontent.com/reviewdog/reviewdog/master/install.sh | sh -s

  script:
    - |
      ./karandash validate --format rdjson 2>/dev/null | \
        ./bin/reviewdog -f=rdjson \
          -name="karandash" \
          -reporter=gitlab-mr-discussion \
          -fail-on-error=false || true

  artifacts:
    reports:
      sarif: karandash-results.sarif
    expire_in: 1 week
```

2. Commit and push:

```bash
git add .gitlab-ci.yml
git commit -m "Add Karandash + reviewdog job"
git push
```

3. Create a merge request to see inline annotations.

### GitLab Reviewdog Reporters

**MR Discussion** (Recommended):
```yaml
-reporter=gitlab-mr-discussion
```

Inline comments on changed lines. Users can reply and resolve. Most interactive.

**Commit Comments**:
```yaml
-reporter=gitlab-mr-commit
```

Comments on specific commits. Good for historical tracking.

### GitLab CI Advanced Configuration

```yaml
karandash-reviewdog:
  stage: test
  image: eclipse-temurin:17-jre

  tags:
    - docker
    - linux

  rules:
    - if: '$CI_PIPELINE_SOURCE == "merge_request_event" && $CI_MERGE_REQUEST_TARGET_BRANCH_NAME == "main"'

  variables:
    KARANDASH_CONFIG: '.karandash/config.yaml'

  cache:
    key: karandash
    paths:
      - karandash.jar
      - bin/reviewdog

  before_script:
    # Download karandash
    - |
      if [ ! -f karandash.jar ]; then
        wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar
      fi
    # Download reviewdog
    - |
      if [ ! -f bin/reviewdog ]; then
        curl -sfL https://raw.githubusercontent.com/reviewdog/reviewdog/master/install.sh | sh -s
      fi

  script:
    # Run Karandash and pipe to reviewdog
    - |
      ./karandash validate \
        --config "$KARANDASH_CONFIG" \
        --format rdjson 2>/dev/null | \
        ./bin/reviewdog -f=rdjson \
          -name="karandash" \
          -reporter=gitlab-mr-discussion \
          -filter-mode=diff_context \
          -fail-on-error=false || true

  artifacts:
    reports:
      sarif: karandash-results.sarif
    expire_in: 1 week
```

---

## YAML Configuration

Configure reviewdog format preferences in `.karandash/config.yaml`:

```yaml
reporters:
  rdjson: true
  # rdjsonl: true    # line-delimited variant, configured the same way
```

### Full Example Configuration

```yaml
rules:
  titleFormat:
    pattern: '^(feat|fix|docs|style|refactor|test|chore)(\(.+\))?!?: .+'

  commitBodyLength:
    enabled: true
    maxLineLength: 100
    message: 'Commit body lines must not exceed 100 characters'

reporters:
  console: true
  rdjson: true
  # rdjsonl: true    # line-delimited variant, configured the same way
  sarif:
    enabled: true
    outputFile: karandash-results.sarif
```

---

## Kotlin DSL Configuration

Configure reviewdog in the Kotlin DSL format (`.karandash/config.kts`):

```kotlin
rules {
    titleFormat {
        pattern = "^(feat|fix|docs|style|refactor|test|chore)(\\(.+\\))?!?: .+"
    }

    commitBodyLength {
        enabled = true
        maxLineLength = 100
        message = "Commit body lines must not exceed 100 characters"
    }
}

reporters {
    console = true
    rdjson = true
    // rdjsonl = true    // line-delimited variant, configured the same way
    sarif {
        enabled = true
        outputFile = "karandash-results.sarif"
    }
}
```

---

## Common Use Cases

### Case 1: Filter Issues to Changed Lines Only

Show only findings on lines that were modified in the PR/MR:

```bash
karandash validate --format rdjson | \
  reviewdog -f=rdjson \
    -name="karandash" \
    -reporter=github-pr-review \
    -filter-mode=added  # Only added lines
```

### Case 2: Include Context Around Changes

Show findings on changed lines plus surrounding context:

```bash
karandash validate --format rdjson | \
  reviewdog -f=rdjson \
    -name="karandash" \
    -reporter=github-pr-review \
    -filter-mode=diff_context  # Changed lines + context
```

### Case 3: Show All Findings in Modified Files

Display all findings in files with changes, not just on changed lines:

```bash
karandash validate --format rdjson | \
  reviewdog -f=rdjson \
    -name="karandash" \
    -reporter=github-pr-review \
    -filter-mode=file  # All findings in modified files
```

### Case 4: Fail CI on Findings

Exit with code 1 if any findings exist:

```bash
karandash validate --format rdjson | \
  reviewdog -f=rdjson \
    -name="karandash" \
    -reporter=github-pr-review \
    -fail-on-error=true
```

### Case 5: Custom Rule Set for Code Review

Use strict rules only in code review pipeline:

```yaml
name: Strict Code Review

on:
  pull_request:
    types: [opened, synchronize, reopened]

jobs:
  review:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      pull-requests: write

    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - uses: reviewdog/action-setup@v1

      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - run: wget https://github.com/appKODE/karandash/releases/latest/download/karandash.jar

      - name: Code review validation
        env:
          REVIEWDOG_GITHUB_API_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          ./karandash validate \
            --config .karandash-strict.yml \
            --format rdjson 2>/dev/null | \
            reviewdog -f=rdjson \
              -name="karandash-strict" \
              -reporter=github-pr-review \
              -fail-on-error=false || true
```

### Case 6: Multiple Validation Passes

Run different rule sets sequentially:

```bash
#!/bin/bash

# File-level validation
echo "Running file-level validation..."
./karandash validate \
  --config .karandash-files.yml \
  --format rdjson 2>/dev/null | \
  reviewdog -f=rdjson -name="karandash-files" -reporter=github-pr-review || true

# Summary validation
echo "Running summary validation..."
./karandash validate \
  --config .karandash-summary.yml \
  --format rdjson 2>/dev/null | \
  reviewdog -f=rdjson -name="karandash-summary" -reporter=github-pr-check || true

# Security validation
echo "Running security validation..."
./karandash validate \
  --config .karandash-security.yml \
  --format rdjson --output security.json
cat security.json | \
  reviewdog -f=rdjson -name="karandash-security" -reporter=github-pr-check || true
```

---

## Troubleshooting

### Reviewdog Command Not Found

**Problem**: `command not found: reviewdog`

**Solutions**:

1. **Install reviewdog** (GitHub Actions):
   ```yaml
   - uses: reviewdog/action-setup@v1
     with:
       reviewdog_version: latest
   ```

2. **Download binary manually**:
   ```bash
   curl -sfL https://raw.githubusercontent.com/reviewdog/reviewdog/master/install.sh | sh -s
   ```

3. **Use Docker image**:
   ```bash
   docker run --rm -v $(pwd):/src ghcr.io/reviewdog/reviewdog:latest \
     -f=rdjson -name="karandash" -reporter=github-pr-review
   ```

### No Comments Posted

**Problem**: Karandash runs successfully but reviewdog doesn't post comments

**Solutions**:

1. **Verify format output**:
   ```bash
   ./karandash validate --format rdjson
   # Should see a JSON document with "source" and "diagnostics"
   ```

2. **Check permissions** (GitHub):
   ```yaml
   permissions:
     pull-requests: write  # Required for comments
   ```

3. **Test reviewdog locally**:
   ```bash
   ./karandash validate --format rdjson | \
     reviewdog -f=rdjson -name="karandash" -reporter=local
   # Should see output in console
   ```

4. **Verify CI/CD context** (only works in PR/MR pipelines):
   ```yaml
   # GitHub: pull_request event
   on:
     pull_request:
       types: [opened, synchronize, reopened]

   # GitLab: merge_request_event
   rules:
     - if: '$CI_PIPELINE_SOURCE == "merge_request_event"'
   ```

5. **Check token permissions**:
   ```bash
   # GitHub: Verify GITHUB_TOKEN has write access
   echo $GITHUB_TOKEN | wc -c  # Should show token length
   ```

### Empty Output

**Problem**: Karandash produces no findings, reviewdog has nothing to post

**Solutions**:

1. **Verify findings exist**:
   ```bash
   ./karandash validate --format json | jq '.findings | length'
   ```

2. **Check configuration**:
   ```bash
   ./karandash validate --config .karandash/config.yaml --format console
   ```

3. **Verify reviewdog output**:
   ```bash
   ./karandash validate --format rdjson | \
     reviewdog -f=rdjson -reporter=local
   ```

### Format Parsing Error

**Problem**: `Error parsing rdjson` or `Invalid JSON`

**Solutions**:

1. **Validate the output**:
   ```bash
   ./karandash validate --format rdjson | jq .
   ```

2. **Use the matching format flag** - `--format rdjson` must be paired with `-f=rdjson`:
   ```bash
   ./karandash validate --format rdjson | reviewdog -f=rdjson ...
   ```

3. **Keep log output off the pipe** - anything Karandash writes to stderr must not reach reviewdog:
   ```bash
   ./karandash validate --format rdjson 2>/dev/null | \
     reviewdog -f=rdjson ...
   ```

### Token Issues

**Problem**: `401 Unauthorized` or `403 Forbidden`

**Solutions**:

1. **GitHub Actions** (automatic):
   ```yaml
   env:
     REVIEWDOG_GITHUB_API_TOKEN: ${{ secrets.GITHUB_TOKEN }}
   ```

2. **GitLab CI** (requires setup):
   ```yaml
   env:
     REVIEWDOG_GITLAB_API_TOKEN: $CI_JOB_TOKEN
   ```

3. **Manual token**:
   ```bash
   export REVIEWDOG_GITHUB_API_TOKEN=ghp_xxxxx
   reviewdog -f=rdjson -reporter=github-pr-review
   ```

### Performance Issues

**Problem**: Pipeline times out or runs very slowly

**Solutions**:

1. **Use filter mode to reduce output**:
   ```bash
   reviewdog -filter-mode=added  # Only changed lines
   ```

2. **Cache JAR file**:
   ```yaml
   # GitHub
   - uses: actions/cache@v3
     with:
       path: karandash.jar
       key: karandash-${{ runner.os }}

   # GitLab
   cache:
     key: karandash
     paths:
       - karandash.jar
   ```

3. **Run in parallel** with other checks instead of sequentially

---

## Tips and Best Practices

### 1. Start with PR Review Reporter

For most teams, `github-pr-review` or `gitlab-mr-discussion` provides the best user experience:

```bash
# GitHub
reviewdog -f=rdjson -reporter=github-pr-review

# GitLab
reviewdog -f=rdjson -reporter=gitlab-mr-discussion
```

### 2. Use Lint Level for Severity

Map Karandash severity to filtering:

```bash
# Show only errors
karandash validate --format rdjson | \
  jq '.diagnostics |= map(select(.severity == "ERROR"))' | \
  reviewdog -f=rdjson -reporter=github-pr-review
```

### 3. Add Tool Name for Clarity

Use `-name` to identify findings in the UI:

```bash
reviewdog -f=rdjson -name="karandash-titles" -reporter=github-pr-review
```

### 4. Document in README

Add reviewdog setup instructions to your project:

```markdown
## Code Review

Karandash runs automatically on pull requests using reviewdog.
Comments appear directly on changed lines.

To run locally:
```bash
./karandash validate --format rdjson | \
  reviewdog -f=rdjson -reporter=local
```
```

### 5. Test Locally

Always test output before deploying to CI/CD:

```bash
# See console output
./karandash validate --format rdjson | \
  reviewdog -f=rdjson -reporter=local

# Save to file for inspection
./karandash validate --format rdjson --output results.json
jq . results.json
```

### 6. Gradual Rollout

Start with warnings, gradually move to errors:

```yaml
rules:
  titleFormat:
    severity: WARNING  # Start here
    # Later: severity: ERROR
```

### 7. Monitor Findings

Track findings over time:

```bash
# Extract just messages
./karandash validate --format rdjson | \
  jq -r '.diagnostics[].message' | sort | uniq -c | sort -rn
```

---

## Integration with Other Systems

### GitHub Code Scanning

Combine reviewdog comments with Code Scanning in GitHub:

```yaml
- name: Run Karandash with reviewdog
  env:
    REVIEWDOG_GITHUB_API_TOKEN: ${{ secrets.GITHUB_TOKEN }}
  run: |
    ./karandash validate --format rdjson 2>/dev/null | \
      reviewdog -f=rdjson -reporter=github-pr-review -fail-on-error=false || true

- name: Upload to Code Scanning
  uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: karandash-results.sarif
```

### GitLab SAST

Combine reviewdog comments with GitLab SAST:

```yaml
karandash-reviewdog:
  script:
    - ./karandash validate --format rdjson 2>/dev/null | \
        reviewdog -f=rdjson -reporter=gitlab-mr-discussion || true
    - ./karandash validate --format sarif --output gl-sast-report.json

  artifacts:
    reports:
      sast: gl-sast-report.json
```

---

## Next Steps

After integrating reviewdog:

1. **Customize rules**: Adjust rule severity based on team feedback
2. **Monitor metrics**: Track findings by type and author
3. **Iterate**: Refine rules based on what reviewdog highlights
4. **Expand scope**: Add more validation rules for broader coverage
5. **Document results**: Create runbook for handling common findings

## Additional Resources

- [Reviewdog Documentation](https://github.com/reviewdog/reviewdog)
- [Karandash Rules Reference](../rules/index.md)
- [Karandash CLI Reference](../reference/cli-reference.md)
- [CI/CD Integration Guide](./ci-cd-integration.md)
