package io.karandash.plugins.formats

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Most commit counts the contributor line lists; the rest are folded into the contributor count. */
private const val CONTRIBUTOR_COUNTS_MAX = 10

/**
 * One line for the `local-git:contributor-stats` enrichment (`{"contributors": [{name, email, commits}]}`): the
 * contributor count, the top contributor's share of commits and the commit counts, most active first. Counts only:
 * names and emails never reach the summary. Null when there are no contributors or no commits.
 */
internal fun SummaryLocale.contributorLine(stats: JsonElement?): String? {
    val commits = ((stats as? JsonObject)?.get("contributors") as? JsonArray)
        ?.mapNotNull { ((it as? JsonObject)?.get("commits") as? JsonPrimitive)?.intOrNull?.takeIf { c -> c > 0 } }
        ?.sortedDescending()
        .orEmpty()
    val total = commits.sum()
    if (total == 0) return null
    return t(SummaryText.CONTRIBUTORS)
        .replaceFirst("%d", commits.size.toString())
        .replaceFirst("%d", (commits.first() * PERCENT / total).toString())
        .replaceFirst("%s", commits.take(CONTRIBUTOR_COUNTS_MAX).joinToString(", "))
}

private const val PERCENT = 100
