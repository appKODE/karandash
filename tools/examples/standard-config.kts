// Standard karandash configuration
// Balanced rules for most teams

rules {
    titleFormat {
        pattern = "^(feat|fix|docs|test|refactor|chore): .+"
        maxLength = 72
        suggestion = "Use a conventional commit prefix, e.g. 'feat: add login page' or 'fix: handle null response'"
        explanation = "Conventional commit prefixes enable automated changelogs and semantic versioning"
    }

    commitMessages {
        pattern = "^(feat|fix|docs|test|refactor|chore): .+"
        maxLength = 100
        suggestion = "Format commits as '<type>: <description>', e.g. 'feat: add OAuth2 support'"
        explanation = "Consistent commit messages improve git history readability and support tooling like release-please"
    }

    draftPrevention {
        blockDraft = "fail"
    }

    approval {
        minApprovals = 2
        requiredApprovers = listOf("senior-team")
    }

    sizeLimits {
        maxLines = 500
        maxFiles = 20
    }

    conflict {
        blockOnConflicts = "fail"
    }
}

reporters {
    console = true
}
