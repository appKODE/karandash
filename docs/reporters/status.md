# Status Reporter

The `status` reporter publishes the verdict as the platform's own result status, next to the CI checks, instead of
(or as well as) an MR comment. It is opt-in and needs a platform context (an MR/PR run).

| Platform | What is published | Annotations |
|----------|-------------------|-------------|
| GitHub | A check run (`POST /repos/{owner}/{repo}/check-runs`), conclusion `success` or `failure` from the verdict | Yes, at most 50 per request (later batches are sent with `PATCH`) |
| GitLab | A commit status on the MR head (`POST /projects/:id/statuses/:sha`), `success` or `failed` | No; use the [Code Quality](codequality.md) report for line findings |
| Bitbucket Cloud | A Code Insights report on the head commit, `PASSED` or `FAILED` | Yes, at most 100 per request, 1000 in all |
| Azure DevOps | A PR status (`POST .../pullRequests/{id}/statuses`), `succeeded` or `failed` | No |
| Gerrit, AWS CodeCommit | Not supported: a warning is logged and nothing is published | |

## Configuration

<!-- docs-test: skip -->
```yaml
reporters:
  status:
    name: karandash                   # check run name / status context
    detailsUrl: "${CI_JOB_URL}"       # optional link shown with the status
    onError: warn                     # or fail
  platform:
    comments: none                    # optional: no MR comments, results only in the status
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `name` | string | `karandash` | Check run name, commit status context, Code Insights report title or PR status context |
| `detailsUrl` | string | none | Link shown with the status |
| `onError` | `warn` or `fail` | `warn` | `fail` fails the run when the status cannot be published |

The verdict is the run's verdict (the same one the summary comment shows). The reporter reads the `rdjsonl` format,
one diagnostic per line, so each annotation carries the file, the line range, the rule and the message.

## Permissions

- **GitHub:** the Checks API takes a GitHub App installation token or the Actions `GITHUB_TOKEN` with
  `permissions: checks: write`. A personal access token cannot create check runs.
- **GitLab:** a token with the `api` scope and at least Developer role.
- **Bitbucket Cloud:** a token with `repository:write` (Code Insights reports).
- **Azure DevOps:** a PAT with **Code (status)** scope.
