package io.karandash.utils

/** The conventional-commit types the title and commit rules accept when no `type.allowed` is configured. */
object ConventionalTypes {
    val DEFAULT: List<String> =
        listOf("feat", "fix", "docs", "style", "refactor", "perf", "test", "build", "ci", "chore", "revert")
}
