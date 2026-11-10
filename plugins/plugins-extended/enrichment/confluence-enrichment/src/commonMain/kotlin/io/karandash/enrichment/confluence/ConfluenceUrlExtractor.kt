package io.karandash.enrichment.confluence

/**
 * Extracts Confluence page IDs from free-form text using the provider's
 * configured base URL as an anchor.
 *
 * Recognized patterns (all must start with [baseUrl]):
 * - `<baseUrl>/spaces/SPACE/pages/123456[/Title]` → page ID `123456` (Cloud: `baseUrl` ends in `/wiki`)
 * - `<baseUrl>/pages/viewpage.action?pageId=123456` → page ID `123456` (Data Center / Server)
 * - `/x/BASE64` → short-link (page ID resolved lazily by the provider via API)
 *
 * Display-format URLs (`/display/SPACE/Title`) are not supported because they
 * require an extra API round-trip to resolve the title to an ID; they are
 * returned as-is in [extractShortLinks] for optional provider-level resolution.
 */
internal object ConfluenceUrlExtractor {
    private val WIKI_PAGE_REGEX = Regex("""/spaces/[^/\s?#]+/pages/(\d+)|/pages/viewpage\.action\?pageId=(\d+)""")
    private val SHORT_LINK_REGEX = Regex("""/x/([A-Za-z0-9_\-]+)""")

    /**
     * Returns distinct page IDs found in [text] whose surrounding URL starts
     * with [baseUrl].
     */
    fun extractPageIds(
        text: String,
        baseUrl: String,
    ): List<String> {
        val base = baseUrl.trimEnd('/')
        return WIKI_PAGE_REGEX
            .findAll(text)
            .filter { isAnchoredToBase(text, it.range.first, base) }
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            .distinct()
            .toList()
    }

    /**
     * Returns distinct short-link tokens (the base64-like segment after `/x/`)
     * found in [text] whose surrounding URL starts with [baseUrl].
     */
    fun extractShortLinks(
        text: String,
        baseUrl: String,
    ): List<String> {
        val base = baseUrl.trimEnd('/')
        return SHORT_LINK_REGEX
            .findAll(text)
            .filter { isAnchoredToBase(text, it.range.first, base) }
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    private fun isAnchoredToBase(
        text: String,
        matchStart: Int,
        base: String,
    ): Boolean {
        val preceding = text.substring(0, matchStart)
        val lastUrlStart = preceding.lastIndexOfAny(charArrayOf(' ', '\n', '\t', '"', '\'', '<', '>'))
        val urlCandidate = preceding.substring(lastUrlStart + 1)
        return urlCandidate.startsWith(base)
    }
}
