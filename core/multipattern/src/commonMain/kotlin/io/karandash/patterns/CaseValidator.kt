package io.karandash.patterns

/**
 * Validates whether a text string conforms to a given [CaseType] convention.
 *
 * Uses the same regex constants as [CorePatternRegistry] to avoid duplication.
 * All regexes are compiled once at object initialisation time.
 */
object CaseValidator {
    private val kebabRegex = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")
    private val snakeRegex = Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*")
    private val camelRegex = Regex("[a-z][a-zA-Z0-9]*")
    private val pascalRegex = Regex("[A-Z][a-zA-Z0-9]*")
    private val sentenceRegex = Regex("[A-Z][a-z].*")

    /**
     * Returns `true` if [text] matches the [case] convention.
     *
     * Non-letter characters (digits, hyphens, etc.) are permitted where the
     * convention allows them. Empty strings always return `false`.
     */
    fun matches(
        text: String,
        case: CaseType
    ): Boolean {
        if (text.isEmpty()) return false
        return when (case) {
            CaseType.LOWER -> text.all { it.isLowerCase() || !it.isLetter() }
            CaseType.UPPER -> text.all { it.isUpperCase() || !it.isLetter() }
            CaseType.KEBAB -> kebabRegex.matches(text)
            CaseType.SNAKE -> snakeRegex.matches(text)
            CaseType.CAMEL -> camelRegex.matches(text)
            CaseType.PASCAL -> pascalRegex.matches(text)
            CaseType.SENTENCE -> sentenceRegex.matches(text)
        }
    }

    /**
     * Returns a human-readable description of the [case] convention for error messages.
     */
    fun descriptionOf(case: CaseType): String =
        when (case) {
            CaseType.LOWER -> "all lowercase"
            CaseType.UPPER -> "all uppercase"
            CaseType.KEBAB -> "kebab-case (e.g. my-feature-branch)"
            CaseType.SNAKE -> "snake_case (e.g. my_feature_branch)"
            CaseType.CAMEL -> "camelCase (e.g. myFeatureBranch)"
            CaseType.PASCAL -> "PascalCase (e.g. MyFeatureBranch)"
            CaseType.SENTENCE -> "Sentence case (e.g. My feature branch)"
        }

    /**
     * Returns the regex pattern string that matches [case], reusing the same
     * constants as [CorePatternRegistry].
     */
    fun toRegex(case: CaseType): String =
        when (case) {
            CaseType.LOWER -> "[a-z]+"
            CaseType.UPPER -> "[A-Z]+"
            CaseType.KEBAB -> "[a-z][a-z0-9]*(-[a-z0-9]+)*"
            CaseType.SNAKE -> "[a-z][a-z0-9]*(_[a-z0-9]+)*"
            CaseType.CAMEL -> "[a-z][a-zA-Z0-9]*"
            CaseType.PASCAL -> "[A-Z][a-zA-Z0-9]*"
            CaseType.SENTENCE -> "[A-Z][a-z].*"
        }
}
