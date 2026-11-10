// Comprehensive karandash configuration
// Demonstrates all 21 validators and all 8 platform providers

// Platform providers with inline authentication
platforms {
    // GitLab provider
    gitlab {
        url = "https://gitlab.com"
        token = env.GITLAB_TOKEN
    }

    // GitHub provider (default: api.github.com)
    github {
        token = env.GITHUB_TOKEN
        // For GitHub Enterprise:
        // baseUrl = "https://github.enterprise.com/api/v3"
    }

    // Bitbucket Cloud provider
    bitbucket {
        token = env.BITBUCKET_TOKEN
    }

    // Gerrit provider
    gerrit {
        url = "https://gerrit.example.com"
        username = env.GERRIT_USERNAME
        token = env.GERRIT_TOKEN
    }

    // Azure DevOps provider
    azureDevOps {
        token = env.AZURE_DEVOPS_TOKEN
        organization = "my-organization"
    }

    // AWS CodeCommit provider
    awsCodeCommit {
        region = "us-west-2"
        accessKeyId = env.AWS_ACCESS_KEY_ID
        secretAccessKey = env.AWS_SECRET_ACCESS_KEY
    }

    // Gitea provider
    gitea {
        url = "https://gitea.example.com"
        token = env.GITEA_TOKEN
    }
}

rules {
    // 1. Title Format Validator
    // Validates MR/PR title format, length, and optional ticket requirement
    titleFormat {
        pattern = "^(feat|fix|docs|test|refactor|chore|ci|perf|style|build)(\\(.+\\))?: .+"
        minLength = 10
        maxLength = 72
        requireTicket = false
    }

    // 2. Commit Messages Validator
    // Validates all commit messages in the MR/PR
    commitMessages {
        format = "conventional"
        pattern = "^(feat|fix|docs|test|refactor|chore|ci|perf|style|build)(\\(.+\\))?: .+"
        maxLength = 100
    }

    // 3. JIRA Ticket Validator
    // Ensures MR/PR references valid JIRA tickets
    jiraTicket {
        projectKeys = listOf("PROJ", "TASK", "BUG", "FEAT")
        pattern = "([A-Z]+-\\d+)"
    }

    // 4. Branch Naming Validator
    // Enforces branch naming conventions
    branchNaming {
        pattern = "^(feature|bugfix|hotfix|release)/[a-z0-9-]+$"
        allowedPatterns = listOf(
            "^feature/.*",
            "^bugfix/.*",
            "^hotfix/.*",
            "^release/.*"
        )
        requireSourceMatch = true
        requireTargetMatch = false
    }

    // 5. File Changes Validator
    // Enforces rules based on which files are changed
    fileChanges {
        patterns = listOf(
            FilePatternRule(pattern = "**/*.sql", requireApproval = listOf("dba")),
            FilePatternRule(pattern = "**/Dockerfile", requireLabels = listOf("infra-change"))
        )
        requireApproval = listOf("code-owner")
        blockOnDraft = "off"
    }

    // 6. CODEOWNERS Validator
    // Enforces CODEOWNERS file approval requirements
    codeowners {
        path = "CODEOWNERS"
        requireOwnerApproval = true
    }

    // 7. Approval Validator
    // Enforces minimum approval requirements
    approval {
        minApprovals = 2
        requiredApprovers = listOf("tech-lead", "senior-dev")
        noChangesSinceApproval = true
    }

    // 8. Reviewer Validator
    // Enforces specific reviewer requirements
    reviewer {
        requiredReviewers = listOf("senior-dev-1", "senior-dev-2")
        requiredTeams = listOf("backend-team", "security-team")
        excludedReviewers = listOf("external-contractor")
    }

    // 9. CI/CD Status Validator
    // Ensures CI/CD pipelines pass before merge
    cicdStatus {
        requirePassed = true
        requiredJobs = listOf("build", "test", "lint", "security-scan")
        blockOnFailing = "fail"
    }

    // 10. Conflict Validator
    // Blocks merge if there are merge conflicts
    conflict {
        blockOnConflicts = "fail"
    }

    // 11. Draft Validator
    // Prevents merging draft MRs/PRs
    draftPrevention {
        blockDraft = "fail"
        wipPatterns = listOf("WIP", "\\[WIP\\]", "wip:", "WIP:", "DRAFT", "\\[DRAFT\\]")
    }

    // 12. Labels Validator
    // Enforces label requirements on MRs/PRs
    labels {
        requiredLabels = listOf("status: needs-review")
        forbiddenLabels = listOf("status: do-not-merge", "wip", "blocked")
    }

    // 13. Size Limits Validator
    // Prevents overly large MRs/PRs
    sizeLimits {
        maxFiles = 30
        maxLines = 800
        maxCommits = 20
    }

    // 14. Security Validator
    // Scans for secrets and sensitive files
    security {
        scanForSecrets = true
        secretPatterns = listOf(
            "(?i)(api[_-]?key|apikey)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-]{20,}['\"]?",
            "(?i)(password|passwd|pwd)\\s*[:=]\\s*['\"]?[^\\s'\"]{8,}['\"]?",
            "(?i)(aws[_-]?access[_-]?key[_-]?id)\\s*[:=]\\s*['\"]?[A-Z0-9]{20}['\"]?"
        )
        sensitiveFiles = listOf(
            "**/.env",
            "**/.env.*",
            "**/*secret*",
            "**/*.pem",
            "**/*.key",
            "**/id_rsa"
        )
    }
}

// Conditional rule sets: each policy applies to merge requests matching its 'when' expression
policies {
    typescript {
        when = "file_path_matches(\"src/**/*.ts\")"
        modules = listOf("titleFormat", "fileChanges")
    }
    drafts {
        when = "is_draft"
        enabled = false
    }
}

reporters {
    // Console reporter (always on for debugging)
    console = true

    // Platform output (posts results as MR/PR comments)
    platform = true

    // Slack reporter (optional)
    // slack {
    //     webhook = env.SLACK_WEBHOOK
    //     channel = "#code-reviews"
    // }
}

enrichment {
    jira {
        url = "https://acme.atlassian.net"
        token = env.JIRA_TOKEN
        email = env.JIRA_EMAIL
        // Sub-blocks enable individual enrichment IDs produced by this provider.
        // Omitting a sub-block leaves it at its default (enabled = true).
        tickets            { enabled = true; maxPerMr = 10 }
        acceptanceCriteria { enabled = true }
        linkedIssues       { enabled = true }
        ticketKeys         { enabled = true }
    }
    confluence {
        url   = "https://acme.atlassian.net/wiki"
        token = env.CONFLUENCE_TOKEN
        email = env.CONFLUENCE_EMAIL
        pages { enabled = true; maxPagesPerMr = 5; maxCharsPerPage = 4000 }
    }
    githubIssue {
        owner = "acme"
        repo  = "backend"
        token = env.GITHUB_TOKEN
        issues { enabled = true }
    }
    gitlabIssue {
        projectId = 12345678
        token     = env.GITLAB_TOKEN
        issues { enabled = true }
    }
    ci {
        platform  = "gitlab"
        projectId = "12345678"
        token     = env.GITLAB_TOKEN
        failedJobs    { enabled = true; maxJobs = 10 }
        coverageGaps  { enabled = true }
    }
    symbolContext {
        changed { enabled = true }
    }
    localGit {
        fileFrequency { enabled = true; period = "6m" }
    }
}

// Rule-to-enrichment bindings - additive to code-declared supportedEnrichment.
ruleEnrichment {
    ruleBinding("review-comments")   { enrich = listOf("jira:*", "confluence:*", "ci:*", "symbols:*") }
    ruleBinding("risk-scoring")      { enrich = listOf("jira:tickets", "ci:failed-jobs", "ci:coverage-gaps") }
}

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
