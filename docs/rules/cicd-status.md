# CI/CD Status

## Overview

The CI/CD Status validator checks that continuous integration and deployment pipelines have passed before allowing merge requests to be merged. It can require overall pipeline success and specific job completion.

## Configuration

### Optional Properties

- `requirePassed`: Require pipeline to have passed status (boolean, default: `true`)
- `requiredJobs`: List of specific job names that must pass (list of strings, default: `[]`)
- `blockOnFailing`: `fail` reports pipeline and required-job failures as errors; `warn` reports `requirePassed`/`requiredJobs` failures as warnings and skips the failing-only check; `off` reports no failure (`fail | warn | off`, default: `fail`)

## Examples

### Basic Usage - Require Passed Pipeline

```kotlin
karandash {
    rules {
        cicdStatus {
            requirePassed = true
        }
    }
}
```

### Required Specific Jobs

```kotlin
karandash {
    rules {
        cicdStatus {
            requirePassed = true
            requiredJobs = listOf("test", "lint", "build")
        }
    }
}
```

### Block on Failing Pipeline

```kotlin
karandash {
    rules {
        cicdStatus {
            requirePassed = true
            blockOnFailing = "fail"
        }
    }
}
```

### Comprehensive CI/CD Policy

```kotlin
karandash {
    rules {
        cicdStatus {
            requirePassed = true
            requiredJobs = listOf(
                "unit-tests",
                "integration-tests",
                "security-scan",
                "lint",
                "build",
                "deploy-staging"
            )
            blockOnFailing = "fail"
        }
    }
}
```

## Pipeline Status Values

Different platforms use different status values:

**GitLab:**
- `success` - All jobs passed
- `failed` - At least one job failed
- `running` - Pipeline is in progress
- `pending` - Pipeline is queued
- `canceled` - Pipeline was canceled
- `skipped` - Pipeline was skipped

**GitHub:**
- `success` - All checks passed
- `failure` - At least one check failed
- `pending` - Checks are running
- `error` - Check encountered an error

**Bitbucket:**
- `SUCCESSFUL` - All builds passed
- `FAILED` - At least one build failed
- `INPROGRESS` - Build is running
- `STOPPED` - Build was stopped

## Error Messages

- **Pipeline required**: `CI/CD status validation requires pipeline status to be passed` (INFO severity)
- **Jobs required**: `CI/CD validation requires these jobs to pass: {jobs}` (INFO severity)

**Note**: Current implementation provides informational messages. Full integration with platform CI/CD APIs is required for enforcement.

## Best Practices

### When to Use This Rule

- Enforce quality gates before merge
- Require tests to pass
- Make sure security scans complete
- Validate builds succeed
- Prevent broken code from merging

### Job Categories

**Quality Gates:**
```kotlin
cicdStatus {
    requiredJobs = listOf(
        "unit-tests",
        "integration-tests",
        "code-coverage",
        "lint"
    )
}
```

**Security Gates:**
```kotlin
cicdStatus {
    requiredJobs = listOf(
        "security-scan",
        "dependency-audit",
        "container-scan",
        "sast",
        "dast"
    )
}
```

**Build Gates:**
```kotlin
cicdStatus {
    requiredJobs = listOf(
        "build",
        "compile",
        "package",
        "docker-build"
    )
}
```

**Deployment Gates:**
```kotlin
cicdStatus {
    requiredJobs = listOf(
        "deploy-staging",
        "smoke-tests",
        "performance-tests"
    )
}
```

### Tips

1. Start with essential jobs only (tests, build)
2. Add more gates incrementally
3. Make sure job names match pipeline configuration exactly
4. Use descriptive job names
5. Consider job execution time in review workflow

## Pipeline Configuration Examples

### GitLab CI

```yaml
# .gitlab-ci.yml
stages:
  - test
  - security
  - build
  - deploy

unit-tests:
  stage: test
  script:
    - npm test

security-scan:
  stage: security
  script:
    - npm audit

build:
  stage: build
  script:
    - npm run build
```

**Karandash Configuration:**
```kotlin
cicdStatus {
    requiredJobs = listOf("unit-tests", "security-scan", "build")
}
```

### GitHub Actions

```yaml
# .github/workflows/ci.yml
name: CI
on: [pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - run: npm test

  lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - run: npm run lint

  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - run: npm run build
```

**Karandash Configuration:**
```kotlin
cicdStatus {
    requiredJobs = listOf("test", "lint", "build")
}
```

## Combining with Other Rules

### With Approvals

```kotlin
rules {
    cicdStatus {
        requirePassed = true
        requiredJobs = listOf("test", "build")
    }

    approval {
        minApprovals = 2  // Tests pass AND human approval
    }
}
```

### With Labels

```kotlin
rules {
    cicdStatus {
        requirePassed = true
    }

    labels {
        conditionalLabels = listOf(
            ConditionalLabel(
                condition = LabelCondition(hasLabel = "skip-ci"),
                requiredLabels = listOf("tech-lead-approved")
            )
        )
    }
}
```

### With Conditional Rules

```kotlin
policies {
    mainBranch {
        when = "target_branch_matches(\"^main$\")"
        modules = listOf("cicdStatus")
        override {
            cicdStatus {
                requirePassed = true
                requiredJobs = listOf(
                    "unit-tests",
                    "integration-tests",
                    "security-scan",
                    "deploy-staging",
                    "smoke-tests",
                )
            }
        }
    }
}
```

## Quality Gates by Branch

**Development Branches:**
```kotlin
cicdStatus {
    requiredJobs = listOf("test", "lint")
}
```

**Staging/Release Branches:**
```kotlin
cicdStatus {
    requiredJobs = listOf(
        "test",
        "lint",
        "security-scan",
        "build",
        "deploy-staging"
    )
}
```

**Production/Main Branch:**
```kotlin
cicdStatus {
    requirePassed = true
    requiredJobs = listOf(
        "unit-tests",
        "integration-tests",
        "e2e-tests",
        "security-scan",
        "performance-tests",
        "build",
        "deploy-staging",
        "smoke-tests"
    )
    blockOnFailing = "fail"
}
```

## Handling Pipeline Failures

### Transient Failures

```
1. Identify failure cause
2. Retry if infrastructure issue
3. Fix if code issue
4. Update MR with fix
5. Wait for pipeline to pass
```

### Skip CI Scenarios

Some platforms support `[skip ci]` in commit messages:

```
fix: typo in docs [skip ci]
```

**Policy for skipping CI:**
```kotlin
// Require approval when CI is skipped
labels {
    conditionalLabels = listOf(
        ConditionalLabel(
            condition = LabelCondition(hasLabel = "skip-ci"),
            requiredLabels = listOf("tech-lead-approved")
        )
    )
}
```

## Job Dependencies

Make sure job execution order in pipeline:

```yaml
# GitLab example
stages:
  - build
  - test
  - security
  - deploy

build:
  stage: build

test:
  stage: test
  needs: [build]  # Runs after build

security:
  stage: security
  needs: [build]

deploy:
  stage: deploy
  needs: [test, security]  # Runs after both
```

Match `requiredJobs` to critical path jobs.

## Performance Considerations

**Fast Feedback:**
```kotlin
// Prioritize fast jobs
requiredJobs = listOf(
    "lint",         // ~30s
    "unit-tests",   // ~2min
    "build"         // ~3min
)
// Total: ~5-6 minutes
```

**Comprehensive but Slow:**
```kotlin
// Include all quality gates
requiredJobs = listOf(
    "lint",
    "unit-tests",
    "integration-tests",
    "e2e-tests",
    "security-scan",
    "performance-tests",
    "build",
    "deploy-staging"
)
// Total: ~30-60 minutes
```

**Balanced Approach:**
```kotlin
// Essential jobs only
requiredJobs = listOf(
    "lint",
    "unit-tests",
    "security-scan",
    "build"
)
// Run the full test suite post-merge or nightly
```

## Platform-Specific Features

### GitLab

- Pipeline status API
- Job artifacts
- Manual approval jobs
- Environment deployments
- Protected branch pipeline requirements

### GitHub

- Status checks API
- Required status checks per branch
- Check runs and check suites
- Branch protection rules

### Bitbucket

- Build status API
- Required builds per repository
- Merge checks

## Common Job Names

**Testing:**
- `test`, `tests`, `unit-tests`, `integration-tests`, `e2e-tests`
- `pytest`, `jest`, `mocha`, `rspec`

**Code Quality:**
- `lint`, `format`, `style-check`
- `eslint`, `prettier`, `rubocop`, `pylint`

**Security:**
- `security-scan`, `security`, `audit`
- `sast`, `dast`, `dependency-scan`
- `snyk`, `sonarqube`, `trivy`

**Build:**
- `build`, `compile`, `package`
- `webpack`, `gradle`, `maven`

**Deploy:**
- `deploy`, `deploy-staging`, `deploy-production`
- `release`, `publish`

## Troubleshooting

**Pipeline not detected:**
- Verify platform API integration
- Check pipeline configuration
- Make sure pipeline runs on MR creation

**Jobs not found:**
- Check exact job names in pipeline config
- Verify jobs actually ran
- Check for conditional job execution

**Status not updating:**
- Make sure webhook/polling is configured
- Check API rate limits
- Verify authentication

## Related Rules

- [Labels](labels.md) - Label based on CI status
- [Approvals](approvals.md) - Combine with human approval
- [Conditional Rules](conditional.md) - Different rules by branch
- [Draft Detection](draft-detection.md) - Allow CI to run on drafts
