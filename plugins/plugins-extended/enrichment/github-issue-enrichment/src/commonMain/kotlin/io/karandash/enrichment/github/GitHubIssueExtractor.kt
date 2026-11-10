package io.karandash.enrichment.github

/**
 * Extracts GitHub issue references from free-form text.
 *
 * Recognized patterns:
 * - `(Closes|Fixes|Resolves) #N` - standard closing keywords
 * - `#N` standalone - bare issue reference
 * - `owner/repo#N` - only when it names [repoPath]; another repository's issue would
 *   otherwise be fetched from the configured one under the same number
 */
internal object GitHubIssueExtractor {
    private val CLOSING_KEYWORD_REGEX = Regex(
        """(?:closes?|fixes?|resolves?)\s+#(\d+)""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val BARE_ISSUE_REGEX = Regex("""(?<![/\w])#(\d+)(?!\d)""")
    private val CROSS_REPO_REGEX = Regex("""([\w.-]+/[\w.-]+)#(\d+)""")

    fun extract(
        texts: List<String>,
        repoPath: String,
    ): Set<Int> {
        val numbers = mutableSetOf<Int>()
        for (text in texts) {
            CLOSING_KEYWORD_REGEX.findAll(text).forEach { m ->
                m.groupValues[1].toIntOrNull()?.let { numbers.add(it) }
            }
            CROSS_REPO_REGEX.findAll(text).filter { it.groupValues[1].equals(repoPath, ignoreCase = true) }.forEach { m ->
                m.groupValues[2].toIntOrNull()?.let { numbers.add(it) }
            }
            BARE_ISSUE_REGEX.findAll(text).forEach { m ->
                m.groupValues[1].toIntOrNull()?.let { numbers.add(it) }
            }
        }
        return numbers
    }
}
