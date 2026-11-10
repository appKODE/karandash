package io.karandash.enrichment.jira

import kotlinx.serialization.json.JsonElement

/**
 * Extracts acceptance-criteria bullet items from a Jira ticket.
 *
 * Two strategies run in order and results are merged and deduplicated:
 * 1. **Custom-field**: if [customFieldAdf] is provided, converts ADF to markdown
 *    and treats every bullet line as an AC item.
 * 2. **Heading-based**: searches the ticket description (converted from
 *    [descriptionAdf]) for a section titled "Acceptance Criteria" or "AC"
 *    (case-insensitive; heading or bold-text marker) and extracts bullet lines
 *    until the next heading or end of text.
 *
 * Each item is normalised (leading `-`, `*`, or `N.` stripped, whitespace
 * trimmed). Duplicates are removed after normalisation.
 */
internal object AcceptanceCriteriaExtractor {
    private val acHeadingRegex = Regex(
        """(?im)(?:^#{1,6}\s*(?:acceptance[_\s-]criteria|ac)\s*${'$'})|(?:\*\*\s*(?:acceptance[_\s-]criteria|ac):?\s*\*\*)""",
    )
    private val nextHeadingRegex = Regex("""(?m)^#{1,6}\s""")
    private val bulletPrefixRegex = Regex("""^(?:[-*]|\d+\.)\s""")
    private val normalizeRegex = Regex("""^(?:[-*]|\d+\.\s*)""")

    fun extract(
        descriptionAdf: JsonElement?,
        customFieldAdf: JsonElement? = null,
    ): List<String> {
        val fromField = if (customFieldAdf != null) {
            bulletsFrom(AdfToMarkdown.convert(customFieldAdf))
        } else {
            emptyList()
        }
        val fromHeading = bulletsFromHeading(AdfToMarkdown.convert(descriptionAdf))
        return (fromField + fromHeading).map { normalize(it) }.distinct()
    }

    private fun bulletsFrom(text: String): List<String> = text.lines().filter { bulletPrefixRegex.containsMatchIn(it.trimStart()) }

    private fun bulletsFromHeading(markdown: String): List<String> {
        val headingMatch = acHeadingRegex.find(markdown) ?: return emptyList()
        val afterHeading = markdown.substring(headingMatch.range.last + 1)
        val sectionEnd = nextHeadingRegex.find(afterHeading)?.range?.first ?: afterHeading.length
        return afterHeading
            .substring(0, sectionEnd)
            .lines()
            .filter { bulletPrefixRegex.containsMatchIn(it.trimStart()) }
    }

    private fun normalize(line: String): String = normalizeRegex.replace(line.trim(), "").trim()
}
