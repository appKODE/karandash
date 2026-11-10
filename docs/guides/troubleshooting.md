# Troubleshooting Guide

This guide helps you resolve common issues when using Karandash for merge request validation. Follow the sections below to diagnose and fix problems.

## Authentication Issues

### Missing or Invalid Token

**Problem**: Validation fails with "authentication failed" or "unauthorized"

**Root Cause**: Token not set, expired, or has insufficient permissions

**Solution**:

1. Verify your token is set as an environment variable:
   ```bash
   # GitHub
   export GITHUB_TOKEN="your-token-here"

   # GitLab
   export GITLAB_TOKEN="your-token-here"

   # Bitbucket
   export BITBUCKET_TOKEN="your-token-here"
   ```

2. Check token permissions:
   - **GitHub**: Requires `repo` scope for private repos, `public_repo` for public
   - **GitLab**: Requires `api` scope minimum
   - **Bitbucket**: Requires `repository:read` and `pullrequest:read`

3. Verify token is not expired:
   ```bash
   # Test GitHub token
   curl -H "Authorization: token $GITHUB_TOKEN" https://api.github.com/user

   # Test GitLab token
   curl -H "PRIVATE-TOKEN: $GITLAB_TOKEN" https://gitlab.com/api/v4/user
   ```

4. If token is invalid, regenerate it in your platform:
   - [GitHub Personal Access Tokens](https://github.com/settings/tokens)
   - [GitLab Personal Access Tokens](https://gitlab.com/-/profile/personal_access_tokens)
   - [Bitbucket App Passwords](https://bitbucket.org/account/settings/app-passwords/)

### Token Permissions Insufficient

**Problem**: "Permission denied" error during validation

**Root Cause**: Token has limited scopes or user lacks repository access

**Solution**:

1. Verify you have repository access on the platform
2. Check your token has the required scopes:
   ```bash
   # GitHub: List token scopes
   curl -H "Authorization: token $GITHUB_TOKEN" https://api.github.com/user -I | grep X-OAuth-Scopes
   ```
3. Create a new token with broader permissions if needed
4. For CI/CD, use repository-specific tokens or service accounts with minimal required access

### Platform-Specific Auth Problems

**GitHub**: OAuth app rate limits or suspended token
- Solution: Check OAuth app status, regenerate token

**GitLab**: API version mismatch on self-hosted instances
- Solution: Verify API version compatibility (see Platform-Specific Issues)

**Bitbucket**: Workspace vs. user permissions
- Solution: Make sure token user has access to the workspace containing the repository

## Configuration Errors

### Config File Not Found

**Problem**: "Configuration file not found" warning during startup

**Root Cause**: Karandash cannot locate `.karandash/config.kts`, `.karandash/config.yaml`, or `.karandash/config.json`

**Solution**:

1. Create a configuration file in your project root:
   ```bash
   karandash init
   ```

2. Verify the file exists:
   ```bash
   ls -la .karandash/config.kts
   ```

3. Use explicit path if needed:
   ```bash
   karandash validate --config /path/to/config.kts
   ```

4. For zero-config mode (uses sensible defaults, no config file needed):
   ```bash
   karandash validate
   ```

### Invalid YAML/JSON Syntax

**Problem**: "YAML syntax error" or "JSON parse error" when loading config

**Root Cause**: Malformed YAML indentation or JSON structure

**Solution**:

1. Validate YAML syntax:
   ```bash
   # Using yamllint if available
   yamllint .karandash/config.yaml

   # Or manually check indentation (spaces, not tabs)
   cat -A .karandash/config.yaml | grep "^I" # Shows tabs
   ```

2. Validate JSON syntax:
   ```bash
   jq empty .karandash/config.json
   ```

3. Common YAML issues:
   - Use 2-space indentation (not tabs or 4 spaces)
   - Quote strings containing special characters: `pattern: "^(feat|fix):"`
   - Use `|` for multiline strings
   - Use `>` for folded multiline strings

4. Example valid YAML config:
   ```yaml
   karandash:
     rules:
       titleFormat:
         pattern: "^(feat|fix|docs):"
         minLength: 10
   ```

### Invalid Kotlin DSL Syntax

**Problem**: "Syntax error in .karandash/config.kts" during parsing

**Root Cause**: Invalid Kotlin syntax or DSL usage

**Solution**:

1. Verify Kotlin syntax in `.karandash/config.kts`:
   ```text
   // Correct syntax
   karandash {
       rules {
           titleFormat {
               pattern = "^(feat|fix):"  // Use = for assignments
               minLength = 10
           }
       }
   }
   ```

2. Common mistakes:
   - Missing `=` in property assignments
   - Unclosed braces `{}`
   - Missing commas in lists: `listOf("a", "b")`
   - Using `==` instead of `=` for property assignment

3. Check for typos in rule names (case-sensitive):
   ```text
   titleFormat { }        // Correct
   TitleFormat { }        // Wrong - case sensitive
   title_format { }       // Wrong - use camelCase
   ```

### Unknown Rule Names

**Problem**: "Unknown rule 'xyz'" or rule not recognized during validation

**Root Cause**: Rule name misspelled or rule doesn't exist

**Solution**:

1. Use only valid rule names:
   - `titleFormat` - Title validation
   - `commitMessages` - Commit message validation
   - `jiraTicket` - JIRA ticket reference
   - `branchNaming` - Branch naming conventions
   - `fileChanges` - File-based rules
   - `codeowners` - CODEOWNERS compliance
   - `approvals` - Approval requirements
   - `reviewers` - Reviewer requirements
   - `cicdStatus` - CI/CD pipeline status
   - `conflictDetection` - Merge conflicts
   - `draftDetection` - Draft/WIP status
   - `labels` - Label requirements
   - `sizeLimits` - Change size limits
   - `security` - Secret scanning
   - `conditional` - Conditional rules

2. Check rule name spelling and case:
   <!-- docs-test: skip -->
   ```kotlin
   // Correct
   titleFormat { pattern = "^feat:" }

   // Incorrect - unknown rule
   title_format { pattern = "^feat:" }
   ```

### Missing Required Fields

**Problem**: "Missing required field 'X'" error during validation

**Root Cause**: Configuration missing mandatory properties for a rule

**Solution**:

1. Check required fields for each rule type (varies by rule)
2. For conditional rules, always specify at least:
   ```text
   conditional {
       when {
           // At least one condition required
           filePathMatches("src/.*")
       }
       then {
           // At least one validation required
           requireApprovals(1)
       }
   }
   ```

3. For file change rules, every pattern entry needs a `pattern`:
   ```kotlin
   fileChanges {
       patterns = listOf(
           FilePatternRule(
               pattern = "db/migrations/**",  // required
               requireApproval = listOf("dba-team")
           )
       )
   }
   ```

### Type Mismatches

**Problem**: "Type mismatch: expected String, got Integer" or similar

**Root Cause**: Wrong data type used for configuration property

**Solution**:

1. Use correct types for each property:
   ```kotlin
   // Correct types
   minLength = 10                    // Int
   pattern = "^feat:"               // String
   requiredApprovers = listOf("a")  // List<String>
   requiresDescription = true        // Boolean
   ```

2. Common type mistakes:
   ```kotlin
   minLength = "10"          // Wrong - should be Int
   maxLength = 72.5          // Wrong - should be Int, not Double
   requiresDescription = "true"  // Wrong - should be Boolean
   ```

## Platform-Specific Issues

### GitHub Rate Limiting

**Problem**: "API rate limit exceeded" or "403 Forbidden" during validation

**Root Cause**: GitHub API rate limit reached (60 requests/hour unauthenticated, 5000/hour authenticated)

**Solution**:

1. Use authenticated requests (already configured if `GITHUB_TOKEN` is set)
2. Reduce API calls:
   ```bash
   # Use file mode to validate locally without live API calls
   karandash validate --mr mr_data.json
   ```
3. Wait for rate limit reset (1 hour from first request)
4. Check rate limit status:
   ```bash
   curl -H "Authorization: token $GITHUB_TOKEN" \
     https://api.github.com/rate_limit
   ```

### GitLab API Version Mismatch

**Problem**: "API version not supported" on self-hosted GitLab

**Root Cause**: Self-hosted instance running older GitLab version incompatible with Karandash

**Solution**:

1. Check your GitLab version:
   ```bash
   curl -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
     https://your-gitlab.com/api/v4/version
   ```

2. Make sure GitLab version 13.0 or later (for full API compatibility)
3. For older versions, some features may not work:
   - Draft MR detection requires GitLab 13.2+
   - CODEOWNERS validation requires GitLab 13.0+

4. Upgrade GitLab if possible, or use only compatible rules

### Bitbucket Authentication

**Problem**: "Bitbucket authentication failed" or "401 Unauthorized"

**Root Cause**: Invalid app password or workspace permissions issue

**Solution**:

1. Verify app password is set correctly:
   ```bash
   export BITBUCKET_TOKEN="your-app-password"
   ```

2. Make sure app password has correct permissions:
   - `repository:read` - Read repository data
   - `pullrequest:read` - Read pull request data

3. Check workspace access:
   ```bash
   curl -u "username:$BITBUCKET_TOKEN" \
     https://api.bitbucket.org/2.0/user
   ```

4. For Bitbucket Server (self-hosted):
   - Use `BITBUCKET_SERVER_TOKEN` instead
   - Set API endpoint: `BITBUCKET_SERVER_URL=https://your-server.com`

### Self-Hosted Instance Configuration

**Problem**: "Cannot connect to self-hosted instance" or "SSL certificate error"

**Root Cause**: Incorrect API endpoint or SSL/TLS issues

**Solution**:

1. Set correct API endpoint for self-hosted instances:
   ```bash
   # GitLab self-hosted
   export GITLAB_URL="https://gitlab.company.com"

   # Gitea self-hosted
   export GITEA_URL="https://gitea.company.com"
   ```

2. Handle SSL certificate warnings:
   ```bash
   # Add the certificate to the trusted store
   # On macOS: security add-trusted-cert /path/to/cert.pem
   # On Linux: sudo cp cert.pem /usr/local/share/ca-certificates/
   ```

3. Test connectivity:
   ```bash
   curl -v -H "Authorization: token $TOKEN" \
     $API_ENDPOINT/user
   ```

### Network and Proxy Issues

**Problem**: "Network timeout" or "Cannot connect to server"

**Root Cause**: Firewall, proxy, or DNS resolution issues

**Solution**:

1. Test basic connectivity:
   ```bash
   ping api.github.com
   curl -v https://api.github.com/user
   ```

2. Configure proxy if behind corporate firewall:
   ```bash
   export HTTP_PROXY="http://proxy.company.com:8080"
   export HTTPS_PROXY="https://proxy.company.com:8080"
   ```

3. Configure proxy authentication:
   ```bash
   export HTTP_PROXY="http://username:password@proxy.com:8080"
   ```

4. Set longer HTTP timeout via environment variable for slow connections:
   ```bash
   KARANDASH_HTTP_TIMEOUT=60 karandash validate
   ```

## Validation Errors

### "MR Not Found" Error

**Problem**: "Merge request #123 not found" during validation

**Root Cause**: MR ID doesn't exist, wrong repository, or insufficient permissions

**Solution**:

1. Verify MR exists:
   ```bash
   # GitHub
   curl -H "Authorization: token $GITHUB_TOKEN" \
     https://api.github.com/repos/owner/repo/pulls/123

   # GitLab
   curl -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
     https://gitlab.com/api/v4/projects/owner%2Frepo/merge_requests/123
   ```

2. Check repository path:
   ```bash
   karandash validate owner/repo#123
   ```

3. Verify token has access to the repository:
   ```bash
   # GitHub
   curl -H "Authorization: token $GITHUB_TOKEN" \
     https://api.github.com/repos/owner/repo
   ```

### "Permission Denied" Error

**Problem**: "Permission denied" or "access forbidden" during validation

**Root Cause**: Token user doesn't have permission to read repository or MR

**Solution**:

1. Verify user has repository access on the platform
2. Check token has correct scopes:
   ```bash
   # GitHub
   curl -H "Authorization: token $GITHUB_TOKEN" \
     https://api.github.com/user -I | grep X-OAuth-Scopes
   ```

3. For private repositories, make sure token has `repo` scope (GitHub) or `api` scope (GitLab)
4. Request access or use a different token with appropriate permissions

### API Error Responses (4xx, 5xx)

**Problem**: "HTTP 400", "HTTP 401", "HTTP 500" errors

**Root Cause**: API error from platform (varies by status code)

**Solution**:

| Status | Cause | Fix |
|--------|-------|-----|
| 400 | Invalid request parameters | Check API endpoint and query parameters |
| 401 | Unauthorized | Verify authentication token is valid and set |
| 403 | Forbidden | Check token permissions and repository access |
| 404 | Not found | Verify MR/repo exists and path is correct |
| 429 | Rate limited | Wait for reset or use authenticated requests |
| 500 | Server error | Retry after a short delay; contact platform support |
| 503 | Service unavailable | Platform maintenance; retry later |

**Example debugging**:
```bash
# Enable verbose output to see full error
karandash validate --verbose

# Check platform status page
# GitHub: https://status.github.com
# GitLab: https://status.gitlab.com
```

### Timeout Issues

**Problem**: "Request timeout" or validation hangs

**Root Cause**: Network latency, large MR, or slow API response

**Solution**:

1. Increase HTTP timeout via environment variable:
   ```bash
   KARANDASH_HTTP_TIMEOUT=120 karandash validate
   ```

2. Check network latency:
   ```bash
   ping -c 5 api.github.com
   curl -w "Time: %{time_total}s\n" https://api.github.com/user
   ```

3. Validate locally without API calls using file mode:
   ```bash
   karandash validate --mr mr_data.json
   ```

4. Check for slow rules:
   ```bash
   karandash validate --verbose  # Shows timing per rule
   ```

## Performance Issues

### Slow Validation Speed

**Problem**: Validation takes 10+ seconds for a single MR

**Root Cause**: Network latency, large MR analysis, or expensive plugin operations

**Solution**:

1. Profile validation to find bottlenecks:
   ```bash
   karandash validate --profile
   ```

2. Disable unnecessary rules:
   ```kotlin
   karandash {
       rules {
           // Only enable rules you need
           titleFormat = true
           // security rule is expensive - disable if not needed
           security = false
       }
   }
   ```

3. Optimize rule configuration:
   ```kotlin
   // Bad: Expensive regex with backtracking
   commitMessages {
       pattern = "^(feat|fix|chore|refactor|perf|test|docs|style|build|ci|revert)(.+)?!?:(.+)$"
   }

   // Better: Simple pattern
   commitMessages {
       pattern = "^[a-z]+:"
   }
   ```

4. Use file mode to avoid live API calls:
   ```bash
   karandash validate --mr mr_data.json
   ```

### Large MR Handling

**Problem**: Validation fails or times out on MRs with 100+ files or 1000+ line changes

**Root Cause**: Memory constraints or API payload size limits

**Solution**:

1. Split large MR into multiple smaller ones - Karandash validates all changed files; the `sizeLimits` rule flags MRs
   that grow past a reviewable size:
   ```kotlin
   rules {
       sizeLimits {
           maxFiles = 100
           maxLines = 1000
       }
   }
   ```

2. Disable expensive rules for large MRs:
   ```text
   conditional {
       when { commitCount > 100 }
       then {
           // Skip expensive security scan
           security { enabled = false }
       }
   }
   ```

3. Increase memory for Karandash (JVM distribution only - native binary manages memory automatically):
   ```bash
   export JAVA_OPTS="-Xmx2g"
   java -jar karandash.jar validate
   ```

4. Use streaming output for large result sets:
   ```bash
   karandash validate --format sarif --output results.sarif
   ```

### Plugin Performance Issues

**Problem**: Validation is slow when plugins are loaded

**Root Cause**: Plugin initialization overhead or inefficient plugin code

**Solution**:

1. Identify slow plugins:
   ```bash
   karandash validate --profile  # Shows per-plugin timing
   ```

2. Disable unused plugins:
   ```kotlin
   karandash {
       plugins {
           // Remove or disable unused plugins
       }
   }
   ```

3. Check plugin implementation:
   - Make sure plugins use async operations where possible
   - Avoid repeated API calls
   - Cache external data

4. Report performance issues to plugin author

### Memory Issues

**Problem**: "Out of memory" error or process crashes

**Root Cause**: Large MRs exceeding available heap memory

**Solution**:

1. Increase JVM heap memory (JVM distribution only - native binary uses fixed low memory footprint):
   ```bash
   export JAVA_OPTS="-Xmx4g"  # Set to 4GB
   java -jar karandash.jar validate
   ```

2. Check available memory:
   ```bash
   # Linux/macOS
   free -h  # or: vm_stat
   ```

3. Keep MRs within a size the run can hold using the `sizeLimits` rule:
   ```kotlin
   rules {
       sizeLimits {
           maxFiles = 100
       }
   }
   ```

4. Disable memory-intensive rules:
   ```kotlin
   rules {
       security = false  // Most memory-intensive
   }
   ```

## CI/CD Problems

### Non-Zero Exit Codes

**Problem**: CI job fails with exit code 1, 2, or other non-zero values

**Root Cause**: Validation failures or errors during execution

**Solution**:

| Exit Code | Meaning | Fix |
|-----------|---------|-----|
| 0 | Success | No action needed |
| 1 | Validation failed | Fix violations in MR |
| 2 | Configuration error | Fix config file syntax |
| 3 | Authentication error | Verify token is set |
| 4 | MR not found | Verify MR ID is correct |

**Example CI configuration**:
```yaml
# GitLab CI
validate_mr:
  script:
    - karandash validate
  allow_failure: false  # Fail job if validation fails
```

### Output Format Issues

**Problem**: Output is malformed or missing expected fields

**Root Cause**: Wrong output format specified or incompatible with consumer

**Solution**:

1. Check supported output formats:
   ```bash
   karandash validate --format json     # JSON format
   karandash validate --format sarif    # SARIF format
   karandash validate --format console  # Human-readable console output (default)
   ```

2. Validate JSON output:
   ```bash
   karandash validate --format json | jq empty
   ```

3. For SARIF, validate against schema:
   ```bash
   # Install sarif tool
   npm install -g @microsoft/sarif-multitool

   # Validate
   sarif validate results.sarif
   ```

### Comment Posting Failures

**Problem**: Results comment fails to post to MR

**Root Cause**: Insufficient permissions or API quota exhausted

**Solution**:

1. Verify token has comment posting permission:
   - **GitHub**: `pull_requests:write` scope
   - **GitLab**: `api` scope with write access

2. Check for API quota:
   ```bash
   # GitHub: Rate limit
   curl -H "Authorization: token $GITHUB_TOKEN" \
     https://api.github.com/rate_limit
   ```

3. Disable auto-comment if not needed (set in config):
   ```yaml
   reporters:
     markdown: false
   ```

4. Check MR status (cannot comment on closed/merged MRs):
   ```bash
   # Verify MR is open
   curl -H "Authorization: token $GITHUB_TOKEN" \
     https://api.github.com/repos/owner/repo/pulls/123 \
     | jq .state
   ```

### Watch Mode in CI/CD

**Problem**: "Watch mode not supported in CI/CD" warning

**Root Cause**: Watch mode requires interactive terminal

**Solution**:

1. Disable watch mode in CI/CD:
   ```bash
   # Use normal validation instead
   karandash validate --once
   ```

2. Watch mode is for local development only:
   ```bash
   # Local: Works fine
   karandash validate --watch

   # CI/CD: Use standard mode
   karandash validate
   ```

## Plugin Issues

### Plugin Not Found

**Problem**: "Plugin 'xyz' not found" during startup

**Root Cause**: Plugin not installed or incorrect plugin name

**Solution**:

1. List available plugins:
   ```bash
   karandash plugin list
   ```

2. Install missing plugin:
   ```bash
   karandash plugin install plugin-name

   # Or from registry
   karandash plugin install @registry/plugin-name
   ```

3. Check plugin name in configuration:
   ```kotlin
   plugins {
       plugin("plugin-name") {  // Must match exactly
           version = "1.0.0"
       }
   }
   ```

### Plugin Load Errors

**Problem**: "Failed to load plugin" or plugin initialization error

**Root Cause**: Plugin incompatibility, missing dependencies, or corrupted plugin JAR

**Solution**:

1. Check plugin compatibility:
   ```bash
   karandash plugin info plugin-name
   ```

2. Verify plugin version matches Karandash version:
   ```bash
   karandash --version
   karandash plugin info plugin-name | grep Version
   ```

3. Reinstall plugin:
   ```bash
   karandash plugin uninstall plugin-name
   karandash plugin install --registry plugin-name
   ```

4. Check the karandash version the plugin requires:
   ```bash
   karandash plugin info plugin-name --dependencies
   ```

5. Enable debug logging:
   ```bash
   karandash --debug validate
   ```

### Native Plugin Compilation

**Problem**: "Failed to compile native plugin" or linking errors

**Root Cause**: Missing build tools or incompatible C/C++ code

**Solution**:

1. Verify build tools are installed:
   ```bash
   # macOS
   xcode-select --install

   # Linux
   sudo apt-get install build-essential

   # Windows
   # Install Visual Studio Build Tools
   ```

2. Find the installed plugin file:
   ```bash
   karandash plugin info plugin-name --source
   ```

3. Reinstall the registry build (fresh download, checksum-verified):
   ```bash
   karandash plugin rebuild plugin-name
   ```

4. Check compilation logs:
   ```bash
   karandash --debug validate 2>&1 | grep -i "compile\|error"
   ```

### Plugin Compatibility

**Problem**: "Plugin incompatible with current Karandash version"

**Root Cause**: Plugin built for different Karandash version

**Solution**:

1. Check plugin version requirements:
   ```bash
   karandash plugin info plugin-name
   ```

2. Upgrade or downgrade plugin:
   ```bash
   # Upgrade
   karandash plugin install plugin-name@latest

   # Specific version
   karandash plugin install plugin-name@1.2.0
   ```

3. Check Karandash version:
   ```bash
   karandash --version
   ```

4. Upgrade Karandash if plugin requires newer version:
   ```bash
   brew upgrade karandash
   ```

## Common Error Messages

### "Config validation failed: unknown rule 'xyz'"

**Error Text**: `Config validation failed: unknown rule 'xyz'`

**Root Cause**: Typo in rule name or rule doesn't exist

**Solution**:
- Check rule name spelling (case-sensitive)
- See rule names list in "Unknown Rule Names" section above
- Run `karandash explain` to see the rules in the effective config

**See Also**: [Unknown Rule Names](#unknown-rule-names)

---

### "Failed to authenticate: invalid token"

**Error Text**: `Failed to authenticate: invalid token`

**Root Cause**: Token is expired, revoked, or has insufficient permissions

**Solution**:
- Verify token environment variable is set correctly
- Regenerate token on platform
- Check token permissions

**See Also**: [Missing or Invalid Token](#missing-or-invalid-token)

---

### "MR not found: could not retrieve merge request details"

**Error Text**: `MR not found: could not retrieve merge request details`

**Root Cause**: MR ID doesn't exist or user lacks access

**Solution**:
- Verify MR ID is correct
- Check token has repository access
- Make sure MR is open (not closed/merged)

**See Also**: ["MR Not Found" Error](#mr-not-found-error)

---

### "Configuration file not found: no config at .karandash/config.kts or .karandash/config.yaml"

**Error Text**: `Configuration file not found: no config at .karandash/config.kts or .karandash/config.yaml`

**Root Cause**: No config file in project root

**Solution**:
- Run `karandash init` to create default config
- Place config in project root or use `--config` flag

**See Also**: [Config File Not Found](#config-file-not-found)

---

### "YAML parse error at line X: expected key"

**Error Text**: `YAML parse error at line X: expected key`

**Root Cause**: YAML indentation or syntax error

**Solution**:
- Check indentation (2 spaces, not tabs)
- Verify quotes around strings with special characters
- Use `yamllint` tool to validate

**See Also**: [Invalid YAML/JSON Syntax](#invalid-yamljson-syntax)

---

### "API rate limit exceeded: 429 Too Many Requests"

**Error Text**: `API rate limit exceeded: 429 Too Many Requests`

**Root Cause**: Too many API requests in short timeframe

**Solution**:
- Wait 1 hour for rate limit reset
- Use authenticated requests
- Reduce validation frequency in CI/CD

**See Also**: [GitHub Rate Limiting](#github-rate-limiting)

---

### "Request timeout: connection timeout after 30s"

**Error Text**: `Request timeout: connection timeout after 30s`

**Root Cause**: Network latency or slow API response

**Solution**:
- Increase HTTP timeout: `KARANDASH_HTTP_TIMEOUT=60 karandash validate`
- Check network connectivity
- Check platform status page

**See Also**: [Timeout Issues](#timeout-issues)

---

## Getting Help

If you can't resolve your issue:

1. Check this troubleshooting guide first
2. Search [GitHub Issues](https://github.com/appKODE/karandash/issues)
3. Check [Documentation](../index.md)
4. Open a [New Issue](https://github.com/appKODE/karandash/issues/new) with:
   - Karandash version: `karandash --version`
   - OS and platform: `uname -a`
   - Configuration file (sanitized)
   - Full error message with `--verbose` flag
   - Steps to reproduce

## Related Documentation

- [Installation Guide](../getting-started/installation.md)
- [Quick Start](../getting-started/quick-start.md)
- [Rules Reference](../rules/index.md)
- [Plugin Guide](../plugins/overview.md)
- [CI/CD Integration](../integrations/ci-cd-integration.md)
