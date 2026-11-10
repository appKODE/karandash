rules {
    titleFormat {
        pattern = "^(feat|fix|docs|chore): .+"
        minLength = 10
        maxLength = 72
    }

    commitMessages {
        format = "conventional"
        maxLength = 72
    }

    jiraTicket {
        projectKeys = listOf("PROJ", "TEST")
        validateStatus = listOf("In Progress", "Review")
    }

    branchNaming {
        pattern = "^(feature|bugfix|hotfix)/.+"
    }

    approval {
        minApprovals = 2
    }

    draft {
        blockDraft = true
    }
}

providers {
    gitlab {
        url = "https://gitlab.com"
        token = "glpat-xxx"
    }
}

reporters {
    console = true
    comment = true
}
