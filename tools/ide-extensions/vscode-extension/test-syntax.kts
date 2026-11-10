// This is a line comment testing all Karandash DSL syntax elements

/*
 * This is a block comment
 * Testing multi-line comments
 */

// Top-level blocks (keywords)
validation {
    enabled = true
}

rules {
    // Validator blocks with properties
    titleFormat {
        pattern = "^(feat|fix|docs|test|refactor|chore): .+"
        minLength = 10
        maxLength = 72
        requireTicket = false
    }

    commitMessages {
        format = "conventional"
        maxLength = 100
        pattern = "^[A-Z]+-\\d+:"
    }

    draft {
        blockDraft = true
        allowWip = false
    }

    approval {
        minApprovals = 2
        maxApprovals = 5
        percentage = 0.75
        requiredApprovers = listOf("senior-team", "tech-lead")
    }

    sizeLimits {
        maxLines = 500
        maxFiles = 20
        warningThreshold = 300
    }

    conflict {
        blockOnConflicts = true
    }

    jiraTicket {
        projectKeys = listOf("PROJ", "TASK", "BUG")
        pattern = "([A-Z]+-\\d+)"
        required = true
    }

    branchName {
        pattern = "^(feature|bugfix|hotfix)/.+"
        allowedPrefixes = setOf("feature", "bugfix", "hotfix")
    }

    description {
        minLength = 50
        maxLength = 1000
        required = true
    }

    labels {
        required = listOf("status:review", "priority")
        forbidden = arrayOf("wip", "do-not-merge")
    }

    codeowners {
        enforce = true
        paths = mapOf(
            "src/core/" to "@core-team",
            "docs/" to "@doc-team"
        )
    }

    wip {
        blockMerge = true
    }

    rebase {
        requireRebase = true
    }
}

providers {
    github {
        token = "ghp_example123"
        apiUrl = "https://api.github.com"
    }

    gitlab {
        token = 'glpat_example456'
        apiUrl = 'https://gitlab.com/api/v4'
    }
}

reporters {
    console = true
    comment = true
    json = false
}
