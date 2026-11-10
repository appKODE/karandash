# Security

## Overview

The Security validator scans merge requests for potential security issues, including hardcoded secrets in code, commit messages, and changes to sensitive files.

## Configuration

### Optional Properties

- `scanForSecrets`: Enable secret scanning (boolean, default: `true`)
- `secretPatterns`: Regex patterns for secret detection (list of strings, default: built-in patterns)
- `sensitiveFiles`: Glob patterns for sensitive files (list of strings, default: built-in patterns)
- `scan`: Controls diff scanning behaviour (`SecurityScanConfig`, default: see sub-properties below)
  - `maxDiffSize`: Maximum diff size to scan for secrets, measured in UTF-8 bytes (not characters). Diffs exceeding this threshold are skipped with a finding (see `severities.largeDiffSkipped`). Must be between 64KB and 50MB, otherwise the config is rejected (size, default: `1MB`)
- `severities`: Controls the severity of specific security findings (`SecuritySeverityConfig`, default: see sub-properties below).
  - `secretFound`: Severity when a secret is detected: `"error"`, `"warning"` or `"info"` (string, default: `"error"`)
  - `largeDiffSkipped`: Severity when a diff is too large to scan: `"error"`, `"warning"` or `"info"` (string, default: `"warning"`)
  - Any other value is rejected when the config loads (`severities.secretFound must be one of [error, warning, info]`) instead of silently becoming `error`.

## Examples

### Basic Usage - Default Patterns

```kotlin
karandash {
    rules {
        security {
            scanForSecrets = true
        }
    }
}
```

### Custom Secret Patterns

```kotlin
karandash {
    rules {
        security {
            scanForSecrets = true
            secretPatterns = listOf(
                "(?i)(api[_-]?key|apikey)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-]{20,}['\"]?",
                "(?i)(password|passwd)\\s*[:=]\\s*['\"]?[^\\s'\"]{8,}['\"]?",
                "my-company-secret-pattern"
            )
        }
    }
}
```

### Custom Sensitive Files

```kotlin
karandash {
    rules {
        security {
            sensitiveFiles = listOf(
                "**/.env*",
                "**/config/secrets.yml",
                "**/credentials/**",
                "**/*.pem",
                "**/*.key"
            )
        }
    }
}
```

### Strict Security Policy

```kotlin
karandash {
    rules {
        security {
            scanForSecrets = true
            secretPatterns = listOf(
                // API Keys
                "(?i)(api[_-]?key|apikey)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-]{20,}['\"]?",
                // Passwords
                "(?i)(password|passwd|pwd)\\s*[:=]\\s*['\"]?[^\\s'\"]{8,}['\"]?",
                // AWS Keys
                "(?i)(aws[_-]?access[_-]?key[_-]?id)\\s*[:=]\\s*['\"]?[A-Z0-9]{20}['\"]?",
                // Private Keys
                "(?i)(private[_-]?key)\\s*[:=]\\s*['\"]?-----BEGIN",
                // Tokens
                "(?i)(token|bearer)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-\\.]{20,}['\"]?"
            )
            sensitiveFiles = listOf(
                "**/.env*",
                "**/secrets/**",
                "**/*.pem",
                "**/*.key",
                "**/*.p12",
                "**/id_rsa"
            )
        }
    }
}
```

## Default Secret Patterns

Karandash includes built-in patterns for common secrets:

```kotlin
// API Keys
"(?i)(api[_-]?key|apikey)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-]{20,}['\"]?"

// Secret Keys
"(?i)(secret[_-]?key|secretkey)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-]{20,}['\"]?"

// Passwords
"(?i)(password|passwd|pwd)\\s*[:=]\\s*['\"]?[^\\s'\"]{8,}['\"]?"

// Access Tokens
"(?i)(access[_-]?token|accesstoken)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-]{20,}['\"]?"

// Bearer Tokens
"(?i)(bearer\\s+[a-zA-Z0-9_\\-\\.]{20,})"

// AWS Access Keys
"(?i)(aws[_-]?access[_-]?key[_-]?id)\\s*[:=]\\s*['\"]?[A-Z0-9]{20}['\"]?"

// AWS Secret Keys
"(?i)(aws[_-]?secret[_-]?access[_-]?key)\\s*[:=]\\s*['\"]?[a-zA-Z0-9/+=]{40}['\"]?"

// Private Keys
"(?i)(private[_-]?key)\\s*[:=]\\s*['\"]?-----BEGIN"

// GitHub Tokens
"(?i)(github[_-]?token|gh[_-]?token)\\s*[:=]\\s*['\"]?ghp_[a-zA-Z0-9]{36}['\"]?"

// GitLab Tokens
"(?i)(gitlab[_-]?token|gl[_-]?token)\\s*[:=]\\s*['\"]?glpat-[a-zA-Z0-9_\\-]{20}['\"]?"
```

## Default Sensitive Files

```kotlin
**/.env
**/.env.*
**/config/secrets.yml
**/config/credentials.yml
**/*secret*
**/*credential*
**/*.pem
**/*.key
**/*.p12
**/*.pfx
**/id_rsa
**/id_dsa
```

## Error Messages

- **Secret in commit**: `Potential secret detected in commit message: {sha}`
- **Secret in a diff**: `Potential secret in an added line` (on the file and line; the path is the finding's location, not part of the message)
- **Secret in title**: `Potential secret detected in MR title`
- **Secret in description**: `Potential secret detected in MR description`
- **Sensitive file**: `Sensitive file change detected: {path}` (WARNING severity)
- **Invalid pattern**: `Invalid regex pattern in secret patterns configuration: '{pattern}' - {error}`

## Best Practices

### When to Use This Rule

- Prevent accidental secret commits
- Detect credential leaks before merge
- Flag sensitive file changes for extra review
- Implement security compliance requirements
- Protect production credentials

### Secret Detection Strategy

**Multi-Layer Approach:**

1. **Pre-commit**: Git hooks scan locally
2. **MR Validation**: Karandash scans during review
3. **CI/CD**: Additional scanning in pipeline
4. **Repository Scanning**: Regular historical scans

### Where Secrets Are Scanned

```kotlin
rules {
    security {
        scanForSecrets = true
    }
}
```

Scans commit messages, the MR title and the MR description. It does not scan file contents (use CI/CD tools for that).

**Recommendation**: Combine with dedicated secret scanning tools:
- [GitGuardian](https://www.gitguardian.com/)
- [TruffleHog](https://github.com/trufflesecurity/trufflehog)
- [git-secrets](https://github.com/awslabs/git-secrets)
- [detect-secrets](https://github.com/Yelp/detect-secrets)

### Sensitive Files Strategy

```kotlin
rules {
    security {
        sensitiveFiles = listOf(
            "**/.env*",              // Environment configs
            "**/config/secrets.yml", // Secret configs
            "**/*.pem",              // Certificates
            "**/*.key",              // Private keys
            "**/credentials/**"      // Credential directories
        )
    }
}
```

This generates **WARNING** findings to alert reviewers.

### Tips

1. Customize patterns for your organization's secret formats
2. Use environment variables for secrets, never hardcode
3. Document secret management practices
4. Use secret management tools (Vault, AWS Secrets Manager)
5. Combine with pre-commit hooks for early detection
6. Regular security audits of repository history

## Common Secret Patterns

### API Keys

```kotlin
// Generic API key
"(?i)(api[_-]?key|apikey)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-]{20,}['\"]?"

// Service-specific
"(?i)stripe[_-]key\\s*[:=]\\s*['\"]?sk_(test|live)_[a-zA-Z0-9]{24}['\"]?"
"(?i)slack[_-]token\\s*[:=]\\s*['\"]?xox[abp]-[a-zA-Z0-9\\-]{10,}['\"]?"
```

### Cloud Provider Credentials

```kotlin
// AWS
"(?i)aws[_-]?access[_-]?key[_-]?id\\s*[:=]\\s*['\"]?[A-Z0-9]{20}['\"]?"
"(?i)aws[_-]?secret[_-]?access[_-]?key\\s*[:=]\\s*['\"]?[a-zA-Z0-9/+=]{40}['\"]?"

// GCP
"(?i)gcp[_-]?key\\s*[:=]\\s*['\"]?.*['\"]?"

// Azure
"(?i)azure[_-]?key\\s*[:=]\\s*['\"]?[a-zA-Z0-9/+=]{40,}['\"]?"
```

### Database Credentials

```kotlin
// Connection strings
"(?i)(mongodb|mysql|postgres)://[^\\s]+:[^\\s]+@"

// Database passwords
"(?i)db[_-]?(password|passwd)\\s*[:=]\\s*['\"]?[^\\s'\"]{8,}['\"]?"
```

### Private Keys

```kotlin
// SSH/TLS Keys
"(?i)-----BEGIN (RSA |DSA |EC |OPENSSH )?PRIVATE KEY-----"

// PGP Keys
"(?i)-----BEGIN PGP PRIVATE KEY BLOCK-----"
```

## False Positive Management

### Common False Positives

**Example values in documentation:**
```python
# This will trigger false positive:
api_key = "YOUR_API_KEY_HERE"

# Better approach (in docs):
api_key = "<your-api-key>"
api_key = "${API_KEY}"  # Reference to env var
```

**Test fixtures:**
```kotlin
// Use obviously fake values:
const val TEST_API_KEY = "test-key-not-real-12345"
const val MOCK_PASSWORD = "mock-password-for-testing"
```

### Excluding False Positives

Option 1: Use placeholder patterns
```javascript
const apiKey = process.env.API_KEY;  // Won't trigger
const apiKey = "sk_test_abc123";     // Will trigger
```

Option 2: Customize patterns to be more specific
```kotlin
// Too broad (many false positives)
"(?i)password\\s*[:=]"

// More specific (fewer false positives)
"(?i)password\\s*[:=]\\s*['\"](?!test|mock|example)[^\\s'\"]{8,}['\"]"
```

## Sensitive File Warnings

When sensitive files are changed:

```
⚠️ WARNING: Sensitive file change detected: config/secrets.yml
```

**Actions to take:**
1. Extra reviewer scrutiny
2. Verify no secrets in diff
3. Make sure proper secret management
4. Check file is in .gitignore if needed
5. Verify deployment process handles secrets correctly

## Combining with Other Rules

### With File Changes

```kotlin
rules {
    security {
        scanForSecrets = true
        sensitiveFiles = listOf("**/.env*", "**/*.key")
    }

    fileChanges {
        patterns = listOf(
            FilePatternRule(
                pattern = "**/*.key",
                requireApproval = listOf("security-team")
            )
        )
    }
}
```

### With Approvals

```kotlin
rules {
    security {
        scanForSecrets = true
    }

    approval {
        requiredApprovers = listOf("security-team")
    }
}
```

### With Labels

```kotlin
rules {
    security {
        sensitiveFiles = listOf("**/secrets/**")
    }

    labels {
        conditionalLabels = listOf(
            ConditionalLabel(
                condition = LabelCondition(hasLabel = "security"),
                requiredLabels = listOf("security-review", "pen-test")
            )
        )
    }
}
```

## Secret Management Best Practices

### Do

- Use environment variables
- Use secret management services (Vault, AWS Secrets Manager)
- Use `.env` files (add to .gitignore)
- Rotate secrets regularly
- Use different secrets for dev/staging/prod
- Encrypt secrets at rest

### Don't

- Commit secrets to version control
- Share secrets via chat/email
- Reuse secrets across environments
- Use default/example secrets in production
- Log secrets in application logs
- Include secrets in error messages

## Integration with Secret Scanning Tools

### Pre-commit Hooks

```bash
# .git/hooks/pre-commit
#!/bin/sh
karandash validate-local --rules security
```

### CI/CD Pipeline

```yaml
# .gitlab-ci.yml
security-scan:
  stage: security
  script:
    - karandash validate --rules security
    - trufflehog filesystem .
    - gitleaks detect
```

## Compliance Requirements

For regulated industries (HIPAA, PCI-DSS, SOC2):

```kotlin
rules {
    security {
        scanForSecrets = true
        secretPatterns = listOf(
            // Add industry-specific patterns
            "(?i)(ssn|social.security)\\s*[:=]\\s*\\d{3}-\\d{2}-\\d{4}",
            "(?i)(credit.card|ccn)\\s*[:=]\\s*\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}"
        )
    }
}
```

## Related Rules

- [File Changes](file-changes.md) - Monitor sensitive file patterns
- [Approvals](approvals.md) - Require security team approval
- [Labels](labels.md) - Tag security-related changes
- [Codeowners](codeowners.md) - Security team as code owners
