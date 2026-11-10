package io.karandash.enrichment.ci

internal object FailedJobSummarizer {
    private val ERROR_PATTERNS = listOf(
        Regex("error:", RegexOption.IGNORE_CASE),
        Regex("\\bFAIL(ED)?\\b"),
        Regex("\\bAssertionError\\b"),
        Regex("\\bException\\b"),
        Regex("\\bexport\\s+\\w+="),
        Regex("(?:mongodb|postgres|redis|mysql)://"),
        Regex("Authorization:\\s*Bearer\\s+\\S+", RegexOption.IGNORE_CASE),
    )
    private const val MAX_SNIPPET_CHARS = 200
    private const val MAX_LOG_LINES = 200
    private const val FALLBACK_TAIL_LINES = 5

    fun extractSnippet(logText: String): String {
        val lines = logText.lines().takeLast(MAX_LOG_LINES)
        val matched = lines.filter { line -> ERROR_PATTERNS.any { it.containsMatchIn(line) } }
        val selected = matched.ifEmpty { lines.takeLast(FALLBACK_TAIL_LINES) }
        return selected.joinToString("\n").takeLast(MAX_SNIPPET_CHARS).trim()
    }
}
