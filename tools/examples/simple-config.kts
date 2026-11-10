// Simple Karandash Configuration Example
// This demonstrates the basic Kotlin-like DSL for MR validation

rules {
    titleFormat {
        pattern = "^(feat|fix|docs|style|refactor|test|chore): .+"
        minLength = 10
        maxLength = 72
        requireTicket = false
        suggestion = "Start with a type prefix: 'feat: ', 'fix: ', 'docs: ', etc."
        explanation = "Conventional commit prefixes enable automated changelogs and semantic versioning"
    }

    commitMessages {
        format = "conventional"
        maxLength = 72
        suggestion = "Use conventional commit format: '<type>[(scope)]: <description>'"
        explanation = "Conventional commits enable automated changelog generation and release tooling"
    }

    jiraTicket {
        projectKeys = listOf("PROJ", "TASK", "BUG")
        pattern = "([A-Z]+-\\d+)"
    }
}

reporters {
    platform = true
    console = true
}
