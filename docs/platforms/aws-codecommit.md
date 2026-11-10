# AWS CodeCommit Platform Guide

## Overview

Karandash provides **standard support** for AWS CodeCommit.

**Support level:** Standard

**Capabilities:**
- Pull request fetching (title, commits, file differences, approvals)
- Zero-config mode via AWS environment variables
- URL-based PR validation (console and regional URLs)
- AWS IAM authentication (access key / secret key)
- Approval rule evaluation

**Not available:**
- Label management (CodeCommit PRs have no label concept)
- Draft toggle (not supported by CodeCommit)
- CI auto-detection
- SARIF upload

**API used:** AWS CodeCommit API (via REST)

---

## Authentication Setup

### Option 1: IAM User Access Keys

Create an IAM user with CodeCommit permissions.

**Create access keys:**

1. Go to the **AWS IAM Console**
2. Create or select an IAM user
3. Attach the `AWSCodeCommitReadOnly` managed policy (or a custom policy -- see below)
4. Go to **Security credentials > Access keys**
5. Click **Create access key**
6. Select **Command Line Interface (CLI)** use case
7. Copy the **Access key ID** and **Secret access key**

**Minimum IAM policy for read-only validation:**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "codecommit:GetPullRequest",
        "codecommit:GetCommit",
        "codecommit:GetDifferences",
        "codecommit:GetCommitsFromMergeBase",
        "codecommit:BatchGetCommits",
        "codecommit:GetPullRequestApprovalStates"
      ],
      "Resource": "arn:aws:codecommit:*:*:*"
    }
  ]
}
```

Restrict the `Resource` to specific repositories for tighter security:

```json
"Resource": "arn:aws:codecommit:us-east-1:123456789012:my-repo"
```

### Option 2: IAM Role (for EC2/ECS/Lambda)

For AWS-native CI/CD, use an IAM role attached to your compute resource.

1. Create an IAM role with the CodeCommit policy above
2. Attach the role to your EC2 instance, ECS task, or Lambda function
3. Karandash uses the AWS SDK credential chain, which automatically picks up role credentials

**Note:** When using IAM roles, you do not need to set `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`. Set only `AWS_REGION`.

### Option 3: AWS SSO / Identity Center

If your organization uses AWS SSO:

```bash
aws sso login --profile my-profile
export AWS_PROFILE=my-profile
export AWS_REGION=us-east-1
```

### Environment Variable Setup

```bash
# Required
export AWS_ACCESS_KEY_ID="AKIAIOSFODNN7EXAMPLE"
export AWS_SECRET_ACCESS_KEY="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

# Optional (defaults to us-east-1)
export AWS_REGION="us-east-1"
# Or
export AWS_DEFAULT_REGION="us-east-1"
```

Karandash checks `AWS_REGION` first, then `AWS_DEFAULT_REGION`, defaulting to `us-east-1`.

### Testing Authentication

```bash
export AWS_ACCESS_KEY_ID="AKIAIOSFODNN7EXAMPLE"
export AWS_SECRET_ACCESS_KEY="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
export AWS_REGION="us-east-1"

karandash validate --pr https://console.aws.amazon.com/codesuite/codecommit/repositories/my-repo/pull-requests/1
```

An authentication failure produces:

```
Authentication failed for awscodecommit provider (HTTP 401).
Your token is invalid, expired, or revoked.
Hint: configure token via AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY (optional AWS_SESSION_TOKEN), or AWS_PROFILE / ~/.aws/credentials.
```

---

## Configuration

### Environment Variables (Zero-Config)

```bash
export AWS_ACCESS_KEY_ID="AKIAIOSFODNN7EXAMPLE"
export AWS_SECRET_ACCESS_KEY="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
export AWS_REGION="us-west-2"

karandash validate --pr https://us-west-2.console.aws.amazon.com/codesuite/codecommit/repositories/my-repo/pull-requests/42
```

### Configuration File (.karandash/config.yaml)

```yaml
platforms:
  aws-codecommit:
    region: "us-east-1"
    access-key-id: "${AWS_ACCESS_KEY_ID}"
    secret-access-key: "${KARANDASH_AWS_SECRET_ACCESS_KEY}"

rules:
  title-format:
    pattern: "^(feat|fix|chore|docs): .+"
    max-length: 120
  commit-messages:
    format: "conventional"
  size-limits:
    max-lines: 500
    max-files: 30
  approval:
    min-approvals: 1
  security:
    scan-for-secrets: true

reporters:
  console: true
```

### Credential resolution

Without `access-key-id` + `secret-access-key` in the config, the plugin uses the standard AWS credential chain
(a subset of the AWS SDK default chain). First match wins, and the key and secret always come from the same source:

1. `access-key-id` + `secret-access-key` (+ `session-token`) in the config - both must be set; one alone is ignored.
2. `profile` in the config: that profile's shared files only (like the AWS CLI `--profile`).
3. Environment: `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` (+ `AWS_SESSION_TOKEN`).
4. Shared files for `AWS_PROFILE` (or `default`): `AWS_SHARED_CREDENTIALS_FILE` or `~/.aws/credentials`
   (`[name]`), then `AWS_CONFIG_FILE` or `~/.aws/config` (`[profile name]`, `[default]`). Keys:
   `aws_access_key_id`, `aws_secret_access_key`, `aws_session_token`, `region`.

Steps 2–4 (the ambient chain) apply only while `endpoint` and `sts-endpoint` are unset - even an
`*.amazonaws.com` host can belong to someone else (API Gateway, EC2, ELB). With any endpoint override, set
`access-key-id` + `secret-access-key` explicitly, otherwise the plugin warns and sends no credentials.

Region: `region` in the config > `AWS_REGION` > `AWS_DEFAULT_REGION` > the profile's `region` > `us-east-1`.
A missing file supplies nothing. SSO, `role_arn`/`source_profile`, `credential_process`, web identity and ECS/EC2
instance metadata are not supported: export the resulting temporary keys (for example with
`aws configure export-credentials --format env`) instead.

`AWS_SECRET_ACCESS_KEY` and `AWS_SESSION_TOKEN` stay on the config-interpolation denylist
(`KarandashEnvSecurity.DENYLIST`), so `${AWS_SECRET_ACCESS_KEY}` does not resolve in a config file and third-party
plugins cannot read it; the built-in AWS chain reads them directly. To put the secret in the config, export it under a
`KARANDASH_` name (`export KARANDASH_AWS_SECRET_ACCESS_KEY=...`).

```yaml
platforms:
  aws-codecommit:
    profile: ci          # ~/.aws/credentials [ci]
```

### Custom Endpoints

By default every CodeCommit call goes to `https://codecommit.<region>.amazonaws.com` and the credential check
(`karandash doctor`, the approve/unapprove caller lookup) goes to STS at `https://sts.<region>.amazonaws.com`.
Two optional keys replace those roots, for a VPC interface endpoint, a FIPS endpoint or a proxy:

| Key | Default | Purpose |
|-----|---------|---------|
| `endpoint` | `https://codecommit.<region>.amazonaws.com` | CodeCommit JSON API root |
| `sts-endpoint` | `https://sts.<region>.amazonaws.com` | STS root for `GetCallerIdentity` |

```yaml
platforms:
  aws-codecommit:
    region: "eu-west-1"
    endpoint: "https://vpce-0abc-codecommit.eu-west-1.vpce.amazonaws.com"
    sts-endpoint: "https://sts.eu-west-1.amazonaws.com"
```

Requests are still SigV4-signed for `region` with the endpoint's host, so the endpoint must accept signatures
for that region. Both URLs pass the SSRF check: they must be HTTPS and public unless `allow_localhost: true`
is set (for example, a local emulator at `http://localhost:4566`).

### URL Formats

```
# Standard console URL
https://console.aws.amazon.com/codesuite/codecommit/repositories/{repo}/pull-requests/{id}

# Regional console URL
https://{region}.console.aws.amazon.com/codesuite/codecommit/repositories/{repo}/pull-requests/{id}
```

**Project identification:** Repository name (e.g., `my-repo`)

Note: Unlike other platforms, CodeCommit uses just the repository name, not an `owner/repo` format.

### Region Detection

Karandash extracts the region from regional URLs automatically:

```
https://us-west-2.console.aws.amazon.com/...  ->  region = us-west-2
https://console.aws.amazon.com/...             ->  region = us-east-1 (default)
```

---

## Features and Limitations

### What Works

| Feature | Status | Notes |
|---------|--------|-------|
| PR fetching | Yes | Title, description, source/target branches |
| Commit validation | Yes | All commits between source and destination |
| File difference detection | Yes | Via GetDifferences API |
| Approval checking | Yes | Fetches approval states and rules |
| Comment posting | Yes | Posts comments via postCommentForPullRequest API |
| Comment updating | Yes | Updates existing comments |
| Inline comments | Yes | Posts inline comments on PR diffs |
| Title update (--fix) | Yes | Updates PR title |
| Description update (--fix) | Yes | Updates PR description |
| Approve / unapprove | Yes | Approves or revokes approval on the PR |
| Zero-config mode | Yes | Via AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY |
| URL parsing | Yes | Console and regional console URLs |
| Region auto-detection | Yes | From URL or environment variables |

### What Does Not Work

| Feature | Reason |
|---------|--------|
| Label management (--fix) | CodeCommit PRs have no label concept |
| Draft toggle (--fix) | Not supported by AWS CodeCommit |
| CI auto-detection | No standard CodeCommit CI variables (use CodeBuild vars manually) |
| SARIF upload | No native CodeCommit code scanning |

### Platform-Specific Considerations

- **AWS authentication model:** Uses IAM credentials, not simple tokens. Both access key ID and secret access key are required (unless using IAM roles).
- **Single-target PRs:** CodeCommit PRs typically have one target (destination branch). Karandash uses the first target.
- **Approval rules:** CodeCommit has a dedicated approval rule system. Karandash fetches approval states when a revision ID is available.
- **No labels:** CodeCommit PRs do not have a label concept. Label-related rules will not find data to validate.
- **Region-specific:** Each CodeCommit repository exists in a specific AWS region. Make sure the region is correct.
- **API throttling:** AWS applies service quotas. Default: 1,000 API calls per second per account per region.

---

## Common Use Cases

### AWS CodeBuild Integration

**buildspec.yml:**

```yaml
version: 0.2

phases:
  install:
    runtime-versions:
      java: corretto17
    commands:
      - wget -q https://github.com/appKODE/karandash/releases/latest/download/karandash.jar

  build:
    commands:
      - |
        ./karandash validate \
          --platform awscodecommit \
          --project "$REPOSITORY_NAME" \
          --mr-id "$PULL_REQUEST_ID"

artifacts:
  files:
    - karandash-results.json
```

**Setting up the CodeBuild trigger:**

1. Create a CodeBuild project
2. Set source to CodeCommit
3. Configure an EventBridge rule to trigger on pull request events:

```json
{
  "source": ["aws.codecommit"],
  "detail-type": ["CodeCommit Pull Request State Change"],
  "detail": {
    "event": ["pullRequestCreated", "pullRequestSourceBranchUpdated"]
  }
}
```

4. Pass PR information via environment variables in the EventBridge rule.

### AWS CodePipeline Integration

```yaml
# CloudFormation / CDK
CodePipelineValidationStage:
  Type: AWS::CodePipeline::Pipeline
  Properties:
    Stages:
      - Name: Validate
        Actions:
          - Name: KarandashValidation
            ActionTypeId:
              Category: Build
              Owner: AWS
              Provider: CodeBuild
              Version: '1'
            Configuration:
              ProjectName: karandash-validation
            InputArtifacts:
              - Name: SourceOutput
```

### Local PR Testing

```bash
export AWS_ACCESS_KEY_ID="AKIAIOSFODNN7EXAMPLE"
export AWS_SECRET_ACCESS_KEY="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
export AWS_REGION="us-east-1"

# Validate a specific PR
karandash validate --pr \
  https://console.aws.amazon.com/codesuite/codecommit/repositories/my-repo/pull-requests/42

# Regional URL
karandash validate --pr \
  https://us-west-2.console.aws.amazon.com/codesuite/codecommit/repositories/my-repo/pull-requests/42

# JSON output
karandash validate --pr \
  https://console.aws.amazon.com/codesuite/codecommit/repositories/my-repo/pull-requests/42 \
  --format json

# Explicit parameters
karandash validate \
  --platform awscodecommit \
  --project my-repo \
  --mr-id 42
```

### Using with AWS SSO Profiles

```bash
# Login via SSO
aws sso login --profile dev-account

# Set profile
export AWS_PROFILE=dev-account
export AWS_REGION=us-east-1

# Validate (Karandash uses the AWS credential chain)
karandash validate --pr \
  https://console.aws.amazon.com/codesuite/codecommit/repositories/my-repo/pull-requests/42
```

---

## Troubleshooting

### "InvalidSignatureException" or "SignatureDoesNotMatch"

```
Error: The request signature we calculated does not match the signature you provided.
```

**Causes:**
- Incorrect secret access key
- Clock skew on the machine (AWS requires accurate timestamps)
- Region mismatch

**Fix:**
1. Verify credentials: `aws sts get-caller-identity`
2. Check system clock: `date -u` (must be within 5 minutes of actual time)
3. Verify region matches the repository location

### "AccessDeniedException"

```
Error: User is not authorized to perform codecommit:GetPullRequest
```

**Cause:** IAM user/role lacks required CodeCommit permissions.

**Fix:** Attach the `AWSCodeCommitReadOnly` managed policy or the custom policy listed in the Authentication section.

### "RepositoryDoesNotExistException"

```
Error: Repository does not exist
```

**Causes:**
- Wrong repository name
- Repository is in a different AWS region
- Repository is in a different AWS account

**Fix:**
1. Verify the repository name (case-sensitive)
2. Check the region: `aws codecommit list-repositories --region us-east-1`
3. Confirm you are using credentials for the correct account

### "PullRequestDoesNotExistException"

```
Error: Pull request does not exist
```

**Fix:**
1. Verify the PR ID exists in the CodeCommit console
2. Check that the PR has not been closed or merged
3. Confirm the repository name is correct

### Both AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY Required

```
Authentication failed for awscodecommit provider (HTTP 401).
Your token is invalid, expired, or revoked.
Hint: configure token via AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY (optional AWS_SESSION_TOKEN), or AWS_PROFILE / ~/.aws/credentials.
```

**Cause:** Only one of the two required variables is set, so the key pair is incomplete and AWS rejects the request.

**Fix:** Both must be set:

```bash
export AWS_ACCESS_KEY_ID="AKIA..."
export AWS_SECRET_ACCESS_KEY="wJal..."
```

Or use an IAM role / AWS profile instead of explicit keys.

---

## Examples

### Minimal Configuration

```yaml
# .karandash/config.yaml
platforms:
  aws-codecommit:
    region: "${AWS_REGION:-us-east-1}"
    access-key-id: "${AWS_ACCESS_KEY_ID}"
    secret-access-key: "${KARANDASH_AWS_SECRET_ACCESS_KEY}"

rules:
  title-format:
    pattern: "^(feat|fix|docs): .+"
  draft:
    block-draft: fail

reporters:
  console: true
```

### CodeCommit-Optimized Configuration

```yaml
# .karandash/config.yaml
platforms:
  aws-codecommit:
    region: "${AWS_REGION:-us-east-1}"
    access-key-id: "${AWS_ACCESS_KEY_ID}"
    secret-access-key: "${KARANDASH_AWS_SECRET_ACCESS_KEY}"

rules:
  title-format:
    pattern: "^(feat|fix|chore|docs|refactor): .+"
    max-length: 120
  commit-messages:
    format: "conventional"
    max-length: 100
  size-limits:
    max-files: 30
    max-lines: 500
  approval:
    min-approvals: 1
  security:
    scan-for-secrets: true
    sensitive-files:
      - "**/.env"
      - "**/*.pem"
      - "**/*.key"
      - "**/credentials"

reporters:
  console: true
```

### CodeBuild buildspec.yml

```yaml
version: 0.2

env:
  variables:
    AWS_REGION: "us-east-1"

phases:
  install:
    runtime-versions:
      java: corretto17
    commands:
      - wget -q https://github.com/appKODE/karandash/releases/latest/download/karandash.jar

  build:
    commands:
      - |
        ./karandash validate \
          --platform awscodecommit \
          --project "$REPOSITORY_NAME" \
          --mr-id "$PULL_REQUEST_ID" \
          --format json \
          --output karandash-results.json

  post_build:
    commands:
      - echo "Validation complete"

artifacts:
  files:
    - karandash-results.json
  discard-paths: yes
```

---

## Authentication Edge Cases

### Clock Skew Causing Signature Failures

**Issue:** "InvalidSignatureException" or "SignatureDoesNotMatch" errors despite correct credentials.

**Root cause:** AWS signature verification is time-sensitive. Clock skew >5 minutes causes signature mismatches.

**Fix:**
1. Check system clock: `date -u`
2. Sync time if skewed: `sudo ntpdate ntp.ubuntu.com` (or use your OS time sync)
3. For Docker: use `--pids host` to share host time

### Credential Chain Precedence

**Issue:** Wrong credentials are being used; validation fails with unexpected account error.

**Root cause:** AWS SDK tries credentials in this order:
1. Environment variables: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`
2. AWS profile: `AWS_PROFILE` environment variable
3. IAM instance role (on EC2/ECS/Lambda)
4. `~/.aws/credentials` file

**Fix:**
1. Check which credentials are active: `aws sts get-caller-identity`
2. Explicitly set `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` if needed
3. Verify IAM role permissions if using instance credentials

### IAM User Access Key Rotation

**Issue:** Validation fails after rotating IAM access keys.

**Root cause:** Old access key is still in use. New keys are not automatically used.

**Fix:**
1. Generate a new access key in IAM console
2. Update `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` environment variables
3. Deactivate old access key after confirming new one works

### IAM Role without CodeCommit Permissions

**Issue:** EC2/ECS/Lambda task fails with "AccessDeniedException" even though the role exists.

**Root cause:** The IAM role does not have CodeCommit permissions attached.

**Fix:**
1. Go to **IAM > Roles > (select role)**
2. Click **Attach policies**
3. Add `AWSCodeCommitReadOnly` managed policy or custom policy
4. Test immediately with `aws sts get-caller-identity`

### AWS SSO Credential Expiration

**Issue:** Validation works initially but fails after SSO session expires.

**Root cause:** AWS SSO credentials are temporary and expire (default: 12 hours).

**Fix:**
1. Re-authenticate: `aws sso login --profile my-profile`
2. For CI/CD: use permanent IAM access keys instead of SSO

---

## Rate Limiting and Quota Considerations

CodeCommit does not expose remaining quota in its API responses, so `karandash doctor` reports no rate limit for it; throttling only shows up as a `ThrottlingException`.

### Service Quotas for CodeCommit

**Issue:** Validation fails with "ServiceQuotaExceededException" after many parallel validations.

**Root cause:** AWS CodeCommit has service quotas: 1,000 API calls/second per region per account.

**Calculation:**
- 1,000 calls/second ÷ 3 calls per PR = ~333 sequential validations/second
- For parallel: 333 ÷ number_of_parallel_jobs

**Mitigation:**
- Serialize validations (run one at a time), or
- Implement exponential backoff for retries, or
- Request quota increase from AWS

### Throttling on Regional Endpoints

**Issue:** Validation is slow or times out during peak usage.

**Root cause:** If your region is experiencing high load, API calls are throttled.

**Mitigation:**
- Retry with exponential backoff
- Spread validations across time instead of parallel
- Contact AWS support for quota increase

---

## Region-Specific Edge Cases

### Region Mismatch Between URL and Credentials

**Issue:** "RepositoryDoesNotExistException" even though the repository exists.

**Root cause:** Repository is in `us-west-2` but credentials/region is set to `us-east-1`.

**Fix:**
1. Extract region from console URL: `https://us-west-2.console.aws.amazon.com/...` = us-west-2
2. Set `AWS_REGION` or `AWS_DEFAULT_REGION` to match
3. Verify with: `aws codecommit list-repositories --region us-west-2`

### Default Region Assumption

**Issue:** Validation fails with "RepositoryDoesNotExistException" when no region is set.

**Root cause:** Karandash defaults to `us-east-1`. If your repository is in a different region, it won't be found.

**Fix:**
1. Always set `AWS_REGION` explicitly: `export AWS_REGION="us-west-2"`
2. Or extract from URL if using regional console URL

### Regional Endpoint Differences

**Issue:** Validation works in one region but fails in another.

**Root cause:** CodeCommit is available in all AWS regions, but each region has a separate endpoint. Some features may be limited in certain regions.

**Expected behavior:** CodeCommit features are consistent across regions. Region-specific errors are rare.

---

## Platform-Specific Limitations

### No Label Support on CodeCommit PRs

**Issue:** "Label validation failed" error when using label rules.

**Root cause:** AWS CodeCommit PRs do not support labels like GitHub/GitLab. CodeCommit uses tags on the repository, not PR labels.

**Fix:** Remove label validation rules or use alternative validation methods.

### Limited PR Metadata

**Issue:** Approval information is incomplete or missing.

**Root cause:** CodeCommit's API provides limited approval metadata compared to GitHub/GitLab.

**Expected behavior:** Karandash validates approval count. For detailed approval rules, check CodeCommit console.

### No PR Comment Support

**Issue:** Comment posting fails silently or with "not supported" error.

**Root cause:** CodeCommit does not support PR comments in the same way as GitHub/GitLab.

**Workaround:** Use SNS notifications or CloudWatch events to notify teams of validation results.

### Diff Information Limitations

**Issue:** File change detection shows incomplete information.

**Root cause:** CodeCommit's GetDifferences API has limitations on what change details it provides.

**Expected behavior:** Karandash reports file counts and aggregated stats.

---

## Cross-Account and Cross-Region Scenarios

### Cross-Account PR Validation

**Issue:** Validation fails with "AccessDeniedException" when validating a PR in a different AWS account.

**Root cause:** IAM credentials are account-specific. Cross-account access requires explicit role assumption.

**Fix:**
1. Create a cross-account IAM role in the target account
2. Grant assume role permission to your primary account
3. Use STS to assume the role:

```bash
ASSUMED_ROLE=$(aws sts assume-role \
  --role-arn "arn:aws:iam::ACCOUNT2:role/CodeCommitAccess" \
  --role-session-name "karandash-session" \
  --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' \
  --output text)

export AWS_ACCESS_KEY_ID=$(echo $ASSUMED_ROLE | cut -d' ' -f1)
export AWS_SECRET_ACCESS_KEY=$(echo $ASSUMED_ROLE | cut -d' ' -f2)
export AWS_SESSION_TOKEN=$(echo $ASSUMED_ROLE | cut -d' ' -f3)
export AWS_REGION="us-east-1"

karandash validate --pr https://console.aws.amazon.com/...
```

### Multi-Region Repository Access

**Issue:** Validating PRs from repositories in different regions fails inconsistently.

**Root cause:** Each region has separate API endpoints. Credentials need region information.

**Fix:**
1. Set region per validation: `export AWS_REGION="us-west-2"`
2. Or use regional console URL to auto-detect region

---

## CI/CD Integration Edge Cases

### CodeBuild Environment Variables Missing

**Issue:** `REPOSITORY_NAME` or `PULL_REQUEST_ID` variables are empty in CodeBuild job.

**Root cause:** These variables are not automatically set by CodeBuild. They come from EventBridge source.

**Fix:**
1. Make sure EventBridge is configured to trigger CodeBuild
2. Pass these variables through environment variables in the EventBridge rule
3. Set them manually in buildspec.yml if needed:

```yaml
env:
  variables:
    REPOSITORY_NAME: "my-repo"
    PULL_REQUEST_ID: "123"
```

### CodePipeline Artifact Passing

**Issue:** Karandash results are not available to downstream pipeline stages.

**Root cause:** Artifacts must be explicitly declared and passed between stages.

**Fix:**
1. In CodeBuild stage, declare output artifacts
2. In downstream stage, use the artifacts as input

### EventBridge Rule Not Triggering CodeBuild

**Issue:** CodeBuild job doesn't trigger on CodeCommit PR events.

**Root cause:** EventBridge rule may be misconfigured or CodeBuild target is not set.

**Fix:**
1. Verify rule pattern matches CodeCommit PR events
2. Check CodeBuild project is set as target
3. Test with: `aws events list-rules --query 'Rules[?Name==`my-rule`]'`

---

## API Differences from Other Platforms

### GetDifferences API Limitations

**Issue:** File change detection is less detailed than expected.

**Root cause:** CodeCommit's GetDifferences API provides file counts and stats but not full diff content.

**Expected behavior:** Karandash reports file changes and aggregate stats. For detailed diffs, use CodeCommit console.

### Approval Rule Complexity

**Issue:** CodeCommit has advanced approval rules but Karandash only validates minimum approval count.

**Root cause:** Approval rules are org/repo policies, not simple API-accessible counts.

**Workaround:** Combine Karandash validation with CodeCommit approval rules.

### No Status Checks / Commit Statuses

**Issue:** Karandash cannot set commit status or check runs like GitHub.

**Root cause:** CodeCommit does not have a native status check system.

**Workaround:** Use CodePipeline status or CloudWatch notifications for validation results.

---

## AWS Service Integration Considerations

### CodeBuild Permissions for CodeCommit Access

**Issue:** CodeBuild job runs but cannot access CodeCommit repository.

**Root cause:** CodeBuild service role lacks CodeCommit permissions.

**Fix:**
1. Go to **CodeBuild > Projects > (select project)**
2. Click **Edit > Environment**
3. Expand **Service role**
4. Attach `AWSCodeCommitReadOnly` policy to the role

### EventBridge IAM Permissions

**Issue:** EventBridge rule exists but CodeBuild is not triggered.

**Root cause:** EventBridge role lacks permission to invoke CodeBuild.

**Fix:**
1. Create EventBridge role with CodeBuild invoke permission
2. Attach role to EventBridge rule
3. Test manually: `aws events put-events --entries '[{"Source":"aws.codecommit","DetailType":"CodeCommit Pull Request State Change"}]'`

### CloudWatch Logs for Troubleshooting

**Issue:** Cannot debug why validation is failing in CodeBuild.

**Root cause:** CloudWatch logs are not being collected or are too verbose.

**Fix:**
1. Enable CodeBuild logging in buildspec.yml
2. Check CloudWatch Logs console under `/aws/codebuild/project-name`
3. Use `--format json` in Karandash to get structured output

---

## Console URL Variations

### Standard vs Regional Console URLs

**Issue:** URL parsing works for one console URL format but not another.

**Root cause:** AWS supports both `console.aws.amazon.com` and regional URLs like `us-west-2.console.aws.amazon.com`.

**Expected behavior:** Karandash handles both formats. Region is auto-detected from regional URLs, defaults to us-east-1 for standard URLs.

**Fix:** Be explicit with region if using standard console URL:

```bash
export AWS_REGION="us-west-2"
karandash validate --pr https://console.aws.amazon.com/codesuite/codecommit/repositories/my-repo/pull-requests/42
```
