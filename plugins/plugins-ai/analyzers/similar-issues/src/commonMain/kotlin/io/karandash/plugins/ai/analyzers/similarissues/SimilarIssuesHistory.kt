@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.analyzers.similarissues

import io.karandash.api.cache.KarandashCache
import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.run.MergeRequestSearch
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer

/**
 * Past merge requests for `similar-issues`: the most frequent identifiers on the diff's added lines are
 * searched through the platform ([MergeRequestSearch]), and the hits are cached per keyword set in the run cache.
 */
internal object SimilarIssuesHistory {
    const val MAX_KEYWORDS = 3
    const val HITS_PER_KEYWORD = 5
    const val CACHE_NAME = "similar-issues-history"

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{3,}")
    private val STOP_WORDS = setOf(
        "assert",
        "class",
        "const",
        "else",
        "false",
        "final",
        "from",
        "function",
        "import",
        "interface",
        "internal",
        "null",
        "object",
        "override",
        "package",
        "private",
        "protected",
        "public",
        "return",
        "static",
        "string",
        "suspend",
        "this",
        "true",
        "void",
        "when",
        "while",
        "with",
    )
    private val serializer = ListSerializer(MergeRequestSearchHit.serializer())

    /** Up to [MAX_KEYWORDS] identifiers from the added diff lines, most frequent first (ties by first appearance). */
    fun keywords(mr: MergeRequest): List<String> =
        mr.changes
            .asSequence()
            .flatMap { it.diffContent.orEmpty().lineSequence() }
            .filter { it.startsWith("+") && !it.startsWith("+++") }
            .flatMap { line -> IDENTIFIER.findAll(line).map { it.value } }
            .filter { it.lowercase() !in STOP_WORDS }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(MAX_KEYWORDS)
            .map { it.key }

    /** Past MRs (not [mr] itself) matching any keyword, or `null` when the platform cannot search. */
    suspend fun load(
        mr: MergeRequest,
        keywords: List<String>,
        search: MergeRequestSearch,
        cache: KarandashCache?,
        logger: Logger,
    ): List<MergeRequestSearchHit>? {
        val key = keywords.joinToString(" ")
        cached(cache, key, logger)?.let { return it }
        val hits = mutableListOf<MergeRequestSearchHit>()
        for (keyword in keywords) {
            hits += search.search(keyword, null, HITS_PER_KEYWORD) ?: return null
        }
        val result = hits.distinctBy { it.id }.filter { it.id != mr.iid.toString() && it.id != mr.id }
        cache?.put(key, KarandashJson.Default.encodeToString(serializer, result))
        return result
    }

    private suspend fun cached(
        cache: KarandashCache?,
        key: String,
        logger: Logger,
    ): List<MergeRequestSearchHit>? {
        val raw = cache?.get(key) ?: return null
        return try {
            KarandashJson.Default.decodeFromString(serializer, raw)
        } catch (e: SerializationException) {
            logger.warn("similar-issues: dropping unreadable cached history for '$key': ${e.message}")
            cache.invalidate(key)
            null
        }
    }
}
