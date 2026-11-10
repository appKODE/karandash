package io.karandash.utils.suggestion

import io.karandash.models.finding.codeSpan

/**
 * Generates context-aware suggestions using actual MR data for cases where
 * pattern-based generation is insufficient.
 *
 * Pattern-based suggestions (title, commit message, branch, description section)
 * are handled by `io.karandash.patterns.PatternFix` in `core/multipattern`.
 *
 * This class retains methods that require **domain-specific logic** beyond
 * regex pattern structure:
 * - [suggestTitleWithTicket]: extracts ticket references from branch names
 * - [suggestConventionalCommit]: infers commit type from message keywords
 */
object ContextAwareSuggestionGenerator {
    private val ticketRegex = Regex("[A-Z]+-\\d+")

    /**
     * Suggests a corrected title that includes a ticket reference.
     *
     * Attempts to extract a ticket reference from the source branch name
     * (e.g., `feature/PROJ-123-login` → `PROJ-123`) and prepends it to the title.
     *
     * @param actualTitle The current MR title missing a ticket reference
     * @param sourceBranch The MR source branch, which may contain a ticket reference
     * @return Suggestion with ticket reference, or a generic suggestion
     */
    fun suggestTitleWithTicket(
        actualTitle: String,
        sourceBranch: String,
    ): String {
        val ticket = ticketRegex.find(sourceBranch)?.value
        if (ticket != null && actualTitle.isNotBlank()) {
            return "Change the title to ${codeSpan("$ticket: $actualTitle")}."
        }
        val title = actualTitle.ifBlank { "<title>" }
        return "Add a ticket reference to the title, e.g. ${codeSpan("PROJ-123: $title")}."
    }

    /**
     * Suggests a corrected commit message following conventional commit format.
     *
     * Infers the commit type from the message content using keyword heuristics,
     * then formats the message as `type: description`.
     *
     * @param actualMessage The current commit message that failed conventional format
     * @param conventionalTypes The allowed conventional commit types
     * @return Suggested conventional commit message
     */
    fun suggestConventionalCommit(
        actualMessage: String,
        conventionalTypes: List<String>,
    ): String {
        if (actualMessage.isBlank()) {
            return "Follow the conventional commit format, e.g. ${codeSpan("${conventionalTypes.first()}: describe your change")}."
        }
        val type = inferConventionalType(actualMessage, conventionalTypes)
        val cleaned = stripExistingPrefix(actualMessage, type)
        return "Change the commit message to ${codeSpan("$type: $cleaned")}."
    }

    private const val MAX_PREFIX_WORD_LENGTH = 15

    private fun inferConventionalType(
        message: String,
        types: List<String>,
    ): String {
        val lower = message.lowercase()
        val keywordsByCanonical = mapOf(
            "fix" to listOf("fix", "bug", "patch", "repair", "resolve", "issue"),
            "feat" to listOf("feat", "feature", "add", "new", "implement", "introduce"),
            "docs" to listOf("doc", "readme", "comment", "documentation"),
            "refactor" to listOf("refactor", "restructure", "reorganize", "clean"),
            "test" to listOf("test", "spec", "coverage"),
            "style" to listOf("style", "format", "lint", "whitespace"),
            "perf" to listOf("perf", "optim", "speed", "fast"),
            "build" to listOf("build", "gradle", "maven", "dependency", "dep"),
            "ci" to listOf("ci", "pipeline", "workflow", "action"),
            "chore" to listOf("chore", "update", "upgrade", "bump", "misc"),
            "revert" to listOf("revert", "undo", "rollback"),
        )

        for (type in types) {
            val typeLower = type.lowercase()
            val keywords = keywordsByCanonical.entries
                .firstOrNull { (_, words) -> words.any { typeLower.contains(it) } }
                ?.value ?: listOf(typeLower)
            if (keywords.any { keyword -> lower.split(" ", "-", "_", "/").any { it.startsWith(keyword) } }) {
                return type
            }
        }

        return types.firstOrNull() ?: "feat"
    }

    private fun stripExistingPrefix(
        message: String,
        inferredType: String,
    ): String {
        val colonIdx = message.indexOf(": ")
        if (colonIdx in 1..MAX_PREFIX_WORD_LENGTH) {
            val prefix = message.substring(0, colonIdx)
            if (prefix.all { it.isLetterOrDigit() || it == '(' || it == ')' || it == '!' }) {
                return message.substring(colonIdx + 2)
            }
        }
        val lowerMsg = message.lowercase()
        val lowerType = inferredType.lowercase()
        if (lowerMsg.startsWith("$lowerType ")) {
            return message.substring(inferredType.length + 1)
        }
        return message
    }
}
