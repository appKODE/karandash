// Minimal karandash configuration
// Perfect for getting started quickly

rules {
    titleFormat {
        pattern = "^(feat|fix|docs|chore): .+"
        maxLength = 72
        suggestion = "Start with a type prefix: 'feat: ', 'fix: ', 'docs: ', or 'chore: '"
        explanation = "Conventional commit prefixes enable automated changelogs and semantic versioning"
    }

    sizeLimits {
        maxLines = 500
    }
}

reporters {
    console = true
}
