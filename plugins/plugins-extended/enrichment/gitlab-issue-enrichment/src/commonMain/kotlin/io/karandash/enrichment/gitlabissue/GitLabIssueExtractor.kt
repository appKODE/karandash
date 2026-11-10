package io.karandash.enrichment.gitlabissue

/**
 * Extracts GitLab issue references from free-form text.
 *
 * Recognized patterns:
 * - `(Closes|Fixes|Resolves) #N` - standard closing keywords
 * - `#N` standalone - bare issue reference
 *
 * `group/project#N` is ignored: issues are fetched by numeric project id, so another
 * project's issue would otherwise be fetched from the configured one under the same number.
 */
internal object GitLabIssueExtractor {
    private val CLOSING_KEYWORD_REGEX = Regex(
        """(?:closes?|fixes?|resolves?)\s+#(\d+)""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val BARE_ISSUE_REGEX = Regex("""(?<![/\w])#(\d+)(?!\d)""")

    fun extract(texts: List<String>): Set<Int> {
        val numbers = mutableSetOf<Int>()
        for (text in texts) {
            CLOSING_KEYWORD_REGEX.findAll(text).forEach { m ->
                m.groupValues[1].toIntOrNull()?.let { numbers.add(it) }
            }
            BARE_ISSUE_REGEX.findAll(text).forEach { m ->
                m.groupValues[1].toIntOrNull()?.let { numbers.add(it) }
            }
        }
        return numbers
    }
}
