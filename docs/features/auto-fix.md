# Auto-Fix Mode

Auto-fix mode automatically corrects certain validation failures by making changes directly to the merge request through the platform API. This saves time on simple, mechanical fixes.

## Usage

```bash
karandash validate --fix --pr https://github.com/owner/repo/pull/123
```

### Non-Interactive Mode (CI/CD)

In CI environments, use `--yes` to skip confirmation prompts:

```bash
karandash validate --fix --yes --pr <url>
```

## What Can Be Auto-Fixed?

Auto-fix is driven entirely by the structured `FixSuggestion` values a rule attaches to its findings - it never parses finding messages. The suggestion types the fixer can apply are:

| `FixSuggestion` | Fix Action | Emitted by |
|-----------------|------------|------------|
| `AddLabel` | Adds a required label | `labels` rule (missing required label) |
| `RemoveLabel` | Removes a forbidden label | `labels` rule (forbidden label present) |
| `SetDraft(draft = false)` | Marks the MR as ready | `draft` rule |
| `SetTitle` | Replaces the MR title | No built-in rule yet - available to plugins |
| `SetDescription` | Replaces the MR description | No built-in rule yet - available to plugins |

`SetDraft(draft = true)` is deliberately ignored: auto-fix will never *make* an MR a draft.

Findings carrying a `confidence` metadata value below `0.8` are skipped.

## Requirements

### Platform Support
Auto-fix works with any platform service that implements the label/draft/title mutations:

- **GitLab** (gitlab.com & self-hosted)
- **GitHub** (github.com & GitHub Enterprise)
- **Azure DevOps**, **Gitea**, **Gerrit**
- **Not supported**: Bitbucket Cloud (its REST API has no pull-request labels), AWS CodeCommit, local Git

### Mode Requirements
- **Live mode only**: Must use `--pr` URL or `--platform`/`--project`/`--mr-id` combination
- **Not supported**: File mode (`--mr file.json`) does not support auto-fix

### Permissions
- **Write access** to the repository
- **Valid authentication token** with permissions to:
  - Modify labels
  - Update merge request status

## How It Works

1. **Validation**: Runs normal validation first
2. **Analysis**: If failures found, analyzes which are fixable
3. **Preview**: Shows what will be fixed
4. **Confirmation**: Asks for confirmation (unless `--yes` used)
5. **Application**: Applies fixes via platform API
6. **Re-validation**: Runs validation again after fixes
7. **Report**: Shows results of applied fixes

## Examples

### Interactive Fix

```bash
karandash validate --fix --pr https://github.com/owner/repo/pull/123
```

Output:
```
Auto-fixable issues found (2):
  - Add missing label 'ready-for-review'
  - Mark MR as ready (remove draft status)

Apply these fixes? [y/N] y

  [OK] Add missing label 'ready-for-review'
  [OK] Mark MR as ready (remove draft status)

Applied 2 fix(es).

Re-validating...
✓ All checks passed!
```

### CI/CD Auto-Fix

```yaml
# .gitlab-ci.yml
validate:
  script:
    - karandash validate --fix --yes --mr-id $CI_MERGE_REQUEST_IID --platform gitlab --project $CI_PROJECT_ID
```

```yaml
# .github/workflows/validation.yml
- name: Validate with auto-fix
  run: karandash validate --fix --yes --pr ${{ github.event.pull_request.html_url }}
```

### Dry Run First

```bash
# See what would be fixed without applying
karandash validate --pr <url>  # Shows validation results

# Apply fixes
karandash validate --fix --pr <url>
```

## Limitations

### Cannot Fix
Auto-fix **cannot** currently address:
- Title format issues
- Commit message problems
- Missing approvals
- CODEOWNERS violations
- File change violations
- Security issues
- Merge conflicts

These require manual intervention.

### Mutual Exclusions
- **Cannot use with --watch**: Auto-fix and watch mode are mutually exclusive
- **Cannot use with file mode**: Requires live API access

### Platform Limitations
- Not all platforms support all mutations
- Some operations may fail due to permissions
- API rate limits may apply

## Configuration

Auto-fix behavior is controlled by your validation rules:

```yaml
# .karandash/config.yaml
rules:
  labels:
    requiredLabels:
      - ready-for-review
      - bug
    forbiddenLabels:
      - do-not-merge
    # Auto-fix will add missing required labels and remove forbidden labels

  draft:
    blockDraft: fail
    # Auto-fix will mark ready if draft is not allowed
```

## Error Handling

### No Fixable Issues

```
No auto-fixable issues found. Remaining issues require manual intervention.
```

### Permission Denied

```
[FAILED] Add missing label 'ready-for-review' (403 Forbidden)
```

Solution: Make sure your token has write permissions to the repository.

### File Mode Used

```
Error: --fix requires live mode with a provider that supports mutations (GitLab, GitHub).
File mode (--mr) does not support auto-fix.
```

Solution: Use `--pr` URL instead of `--mr file.json`.

### Non-Interactive CI Without --yes

```
Error: --fix requires --yes flag in CI/non-interactive environments
```

Solution: Add `--yes` flag when running in CI.

### Provider Not Supported

```
Error: --fix requires live mode with a provider that supports mutations (GitLab, GitHub).
```

Solution: Use a supported platform (GitHub or GitLab) with the `--pr` flag or live mode parameters.

## Best Practices

1. **Test First**: Run without `--fix` to preview issues
2. **Use in CI**: Automate simple fixes in your pipeline
3. **Monitor Failures**: Check for partial fix failures in output
4. **Limit Scope**: Only enable auto-fix for safe, mechanical issues
5. **Secure Tokens**: Keep authentication tokens secure with proper CI/CD secrets

## Security Considerations

- **Token Security**: Auto-fix requires write access; secure your tokens in CI environments
- **Review Changes**: Auto-fix makes real changes to PRs and MRs
- **Audit Logs**: Platform API calls are logged and appear in repository history
- **Rate Limits**: Be mindful of API quotas when using auto-fix frequently

## Advanced Usage

### Combining with Other Flags

```bash
# Fix, then output JSON report
karandash validate --fix --yes --format json --output report.json --pr <url>

# Fix with verbose output
karandash validate --fix -v --pr <url>

# Fix multiple issues in one run
karandash validate --fix --yes --pr <url>
```

## Applying Fixes Standalone with `mutate`

In addition to the `--fix` flag on `validate`, the [`mutate`](../reference/cli/mutate.md) command can be used to run mutation tests against your rule configuration. This verifies that your rules correctly catch the same categories of issues that auto-fix addresses, before deploying a new config.

## See Also

- [mutate](../reference/cli/mutate.md) - Run mutation tests against rule configuration
- [Watch Mode](watch-mode.md) - Monitor config changes
- [Platform Support](../platforms/index.md) - Platform-specific guides
- [CI/CD Integration](../integrations/ci-cd-integration.md) - Running in pipelines
- [Rules Reference](../rules/index.md) - All available validation rules
- [Labels Rule](../rules/labels.md) - Label validation configuration
- [Draft Detection Rule](../rules/draft-detection.md) - Draft status validation
