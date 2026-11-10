// GitHub-optimized karandash configuration
// Designed for GitHub Actions + Code Scanning

platforms {
    github {
        token = env.GITHUB_TOKEN
        // For GitHub Enterprise:
        // baseUrl = "https://github.enterprise.com/api/v3"
    }
}

rules {
    titleFormat {
        pattern = "^(feat|fix|docs|test|refactor|chore|ci|perf|style|build)(\\(.+\\))?: .+"
        maxLength = 72
        suggestion = "Use conventional commit format, e.g. 'feat(auth): add OAuth2 support' or 'fix: handle null response'"
        explanation = "Conventional commits enable GitHub Actions to generate release notes and changelogs automatically"
    }

    commitMessages {
        pattern = "^(feat|fix|docs|test|refactor|chore|ci|perf|style|build)(\\(.+\\))?: .+"
        maxLength = 100
        suggestion = "Format commits as '<type>[(scope)]: <description>', e.g. 'fix(api): resolve timeout on large responses'"
        explanation = "Consistent commit messages improve git history and are required for semantic-release tooling"
    }

    labels {
        requiredLabels = listOf("status: needs-review")
        forbiddenLabels = listOf("status: do-not-merge", "wip")
    }

    approval {
        minApprovals = 1
    }

    codeowners {
        path = "CODEOWNERS"
        requireOwnerApproval = true
    }

    cicdStatus {
        requirePassed = true
        requiredJobs = listOf("build", "test", "lint")
        blockOnFailing = "fail"
    }

    draftPrevention {
        blockDraft = "fail"
    }

    conflict {
        blockOnConflicts = "fail"
    }

    sizeLimits {
        maxLines = 800
        maxFiles = 30
    }
}

reporters {
    console = true
    sarif {
        outputFile = "karandash-results.sarif"
    }
}
