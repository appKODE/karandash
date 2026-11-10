/**
 * Test configuration for integration tests
 */
rules {
    titleFormat {
        minLength = 3
        maxLength = 120
        pattern = "[A-Z].*"
    }

    commitMessages {
        pattern = "^(feat|fix|docs|style|refactor|test|chore):"
        maxLength = 100
    }

    fileChanges {
        patterns = listOf(
            "**/*"
        )
    }

    branchNaming {
        pattern = "^(feature|fix|hotfix|release)/.+"
    }

    approval {
        minApprovals = 0
    }
}

/**
 * Reporter configuration for test runs
 */
reporters {
    console = true
}
