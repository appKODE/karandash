package io.karandash.providers.common

import io.karandash.api.PluginContext
import io.karandash.api.cache.KarandashCache
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Cache and rate limit shared by the enrichment providers that call a remote API. Build one in `initialize()`.
 *
 * [enrich] reuses the last result while the MR inputs in its dependencies are unchanged (only when the run cache is
 * on, under the [cacheName] string cache); [request] lets at most [maxRequestsPerMinute] requests start in any sliding
 * minute, one at a time.
 */
class CachedEnrichment(
    context: PluginContext,
    private val cacheName: String,
    private val maxRequestsPerMinute: Int,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private val cache: KarandashCache? =
        if (context.cacheProvider.enabled) context.cacheProvider.getStringCache(cacheName) else null
    private val mutex = Mutex()

    /** Start marks of the requests in the last minute; read and written only under [mutex]. */
    private val window = ArrayDeque<TimeMark>()

    suspend fun enrich(
        dependencies: Map<String, String>,
        compute: suspend () -> Map<String, JsonElement>,
    ): Map<String, JsonElement> {
        val c = cache ?: return compute()
        val cached = c.getOrPut(cacheName, dependencies) { KarandashJson.Default.encodeToString(SERIALIZER, compute()) }
        return KarandashJson.Default.decodeFromString(SERIALIZER, cached)
    }

    suspend fun <T> request(block: suspend () -> T): T =
        mutex.withLock {
            val limit = maxRequestsPerMinute.coerceAtLeast(1)
            while (window.size >= limit) {
                val wait = WINDOW - window.first().elapsedNow()
                if (wait.isPositive()) delay(wait)
                window.removeFirst()
            }
            window.addLast(timeSource.markNow())
            block()
        }

    companion object {
        /** Requests per minute for the providers without a `max-requests-per-minute` key. */
        const val DEFAULT_MAX_REQUESTS_PER_MINUTE = 60
        private val WINDOW = 1.minutes
        private val SERIALIZER = MapSerializer(String.serializer(), JsonElement.serializer())

        /** The MR text every issue/ticket provider extracts references from, as cache dependencies. */
        fun mrTextDependencies(
            mr: MergeRequest,
            vararg extra: Pair<String, String>,
        ): Map<String, String> =
            mapOf(
                "title" to mr.title,
                "description" to (mr.description ?: ""),
                "sourceBranch" to mr.sourceBranch,
                "commits" to mr.commits.joinToString(",") { it.message },
            ) + extra
    }
}
