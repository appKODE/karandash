package io.karandash.plugins.ai.providers

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.cache.TieredCache
import io.karandash.crypto.sha256Hex
import io.karandash.duration.KarandashDuration
import io.karandash.plugins.ai.core.CachedResponse
import io.karandash.plugins.ai.core.ReasoningEffortOverride
import io.karandash.plugins.ai.core.ResponseCacheSalt
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.utils.CacheStats
import kotlin.time.Clock

/**
 * Caching decorator for [AiProvider] that avoids redundant API calls.
 *
 * Cache key is derived from (provider, [keyScope], model, prompt, systemPrompt, maxTokens,
 * temperature, jsonMode, responseSchema, a [ReasoningEffortOverride]) under a `v3` namespace, so two providers or endpoints sharing one cache
 * never serve each other's responses. Prompts are keyed by SHA-256, not `hashCode`, so colliding
 * prompts never share an answer. Keys persisted by older versions do not match and are ignored.
 * The cache is managed externally via [CacheManager] and provided at construction time,
 * enabling unified lifecycle management and optional file-tier persistence.
 *
 * Thread-safe via [TieredCache].
 *
 * @param delegate The underlying provider to cache calls for.
 * @param cache The managed cache instance to use for storing responses.
 * @param keyScope Extra key component, e.g. the provider base URL.
 * @param logger Receives one info line per call (cache hit or miss) and the hit/miss totals on [close].
 * @param clock Time source for export timestamps and import TTL checks; injectable for tests.
 */
class CachingAiProvider(
    private val delegate: AiProvider,
    private val cache: TieredCache<String, AiResponse>,
    private val keyScope: String = "",
    private val logger: Logger = NoOpLogger,
    private val clock: Clock = Clock.System,
) : AiProvider {
    override val providerId: String get() = delegate.providerId

    override suspend fun complete(
        prompt: String,
        systemPrompt: String?,
        model: String,
        maxTokens: Int,
        temperature: Double,
        jsonMode: Boolean,
    ): AiResponse = completeRequest(AiCompletionRequest(prompt, systemPrompt, model, maxTokens, temperature, jsonMode))

    override suspend fun completeRequest(request: AiCompletionRequest): AiResponse {
        val key = buildCacheKey(request, ReasoningEffortOverride.current(), ResponseCacheSalt.current())
        cache.get(key)?.let {
            logger.info("[ai] cache hit: provider=$providerId model=${request.model}")
            return it
        }
        logger.info("[ai] cache miss: provider=$providerId model=${request.model}")
        val response = delegate.completeRequest(request)
        // a reply cut at the token limit is not the answer to this prompt: the next call must ask again
        if (!response.truncated) cache.put(key, response)
        return response
    }

    override suspend fun close() {
        val stats = cacheStats()
        if (stats.hits + stats.misses > 0) logger.info("[ai] response cache: ${stats.hits} hits, ${stats.misses} misses")
        cache.clear()
        delegate.close()
    }

    /** Returns the number of entries currently in the cache. */
    fun cacheSize(): Int = cache.size()

    /** Clears all cached entries. */
    fun clearCache() {
        cache.clear()
    }

    /** Returns cache performance statistics. */
    fun cacheStats(): CacheStats = cache.stats()

    /**
     * Exports all non-expired cache entries for disk persistence.
     *
     * @return Map of cache key to [CachedResponse] for all entries that have not yet expired.
     */
    fun exportCache(): Map<String, CachedResponse> =
        cache.snapshot().entries.associate { (key, response) ->
            key to CachedResponse(
                content = response.content,
                promptTokens = response.promptTokens,
                completionTokens = response.completionTokens,
                totalTokens = response.totalTokens,
                model = response.model,
                createdAt = clock.now().epochSeconds,
            )
        }

    /**
     * Imports previously persisted cache entries, skipping expired ones.
     *
     * @param entries Map of cache key to [CachedResponse] loaded from disk.
     * @param ttl Maximum age for an imported entry to be considered valid.
     */
    fun importCache(
        entries: Map<String, CachedResponse>,
        ttl: KarandashDuration = DEFAULT_IMPORT_TTL,
    ) {
        val now = clock.now().epochSeconds
        for ((key, cached) in entries) {
            val age = now - cached.createdAt
            if (age < ttl.seconds) {
                cache.put(
                    key,
                    AiResponse(
                        content = cached.content,
                        promptTokens = cached.promptTokens,
                        completionTokens = cached.completionTokens,
                        totalTokens = cached.totalTokens,
                        model = cached.model,
                    ),
                )
            }
        }
    }

    private fun buildCacheKey(
        request: AiCompletionRequest,
        effort: String?,
        salt: String?,
    ): String =
        buildString {
            append(KEY_NAMESPACE)
            append('|')
            append(providerId)
            append('|')
            append(keyScope)
            append('|')
            append(request.model)
            append('|')
            // the flattened text, so moving part of a prompt into context blocks keeps the persisted keys
            append(sha256Hex(request.withContextInPrompt().prompt))
            append('|')
            append(request.systemPrompt?.let(::sha256Hex).orEmpty())
            append('|')
            append(request.maxTokens)
            append('|')
            append(request.temperature)
            append('|')
            append(request.jsonMode)
            // appended only when set, so schema-less keys stay identical to the persisted v3 ones
            request.responseSchema?.let { append("|schema:").append(sha256Hex(it.toString())) }
            // a call retried at another reasoning effort must not be answered by the reply it retries
            effort?.let { append("|effort:").append(it) }
            // a later review-comments sample must not be answered by an earlier sample's reply
            salt?.let { append("|salt:").append(it) }
        }

    companion object {
        private const val KEY_NAMESPACE = "v3"

        /** Default TTL used when importing cache entries without an explicit TTL. */
        val DEFAULT_IMPORT_TTL: KarandashDuration = KarandashDuration.ofMinutes(30)
    }
}
