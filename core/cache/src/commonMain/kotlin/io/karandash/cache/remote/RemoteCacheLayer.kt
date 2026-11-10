package io.karandash.cache.remote

import io.karandash.api.support.rethrowCancellation
import io.karandash.cache.CacheCategory
import io.karandash.cache.cas.CasBlobStore
import io.karandash.cache.cas.CasDigest
import io.karandash.logger.Logger
import io.karandash.utils.CacheStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException

/**
 * Transparent remote cache layer that wraps a local [CasBlobStore].
 *
 * On `get()`: local hit returns immediately. Local miss + pull enabled triggers
 * a remote pull with timeout, stores locally on success.
 *
 * On `put()`: stores locally first. If push enabled, fires off an async push
 * to the remote (fire-and-forget with timeout). Push failures are logged, never thrown.
 * Content already in the local store was pushed when it was first stored (or by `cache push`), so it is not
 * pushed again; [stats] counts those skips as `deduplicatedWrites`, next to pull hits and misses.
 *
 * @param local Local CAS blob store for fast access.
 * @param remote Remote cache backend for distributed sharing.
 * @param config Remote cache configuration (timeouts, push/pull flags).
 * @param scope Coroutine scope for async push operations.
 * @param logger Logger for debug and warning messages.
 */
class RemoteCacheLayer(
    private val local: CasBlobStore,
    private val remote: RemoteCacheBackend,
    private val config: RemoteCacheConfig,
    private val scope: CoroutineScope,
    private val logger: Logger,
) {
    /**
     * Get blob content by digest. Checks local first, then remote if pull is enabled.
     *
     * @return Blob content, or null if not found in either local or remote cache.
     */
    suspend fun get(digest: CasDigest): ByteArray? {
        val localResult = local.get(digest)
        if (localResult != null) return localResult

        if (!config.pull) return null

        return pullFromRemote(digest).also { pulled -> (if (pulled != null) pullHits else pullMisses).update { it + 1 } }
    }

    /**
     * Put blob content. Stores locally, then pushes to remote asynchronously if enabled.
     *
     * @return The digest of the stored content.
     */
    fun put(content: ByteArray): CasDigest {
        val digest = CasDigest.of(content)
        if (existsLocally(digest)) {
            deduplicated.update { it + 1 }
            return digest
        }
        local.put(content)
        push(digest, content)
        return digest
    }

    /** Pull hits and misses, pushes stored and failed, and the pushes skipped by [put] for content already stored locally. */
    fun stats(): CacheStats =
        CacheStats(
            hits = pullHits.value,
            misses = pullMisses.value,
            deduplicatedWrites = deduplicated.value,
            pushes = pushes.value,
            pushFailures = pushFailures.value,
        )

    /** Pushes [content] (already stored locally under [digest]) to the remote asynchronously, if push is enabled. */
    fun push(
        digest: CasDigest,
        content: ByteArray,
    ) {
        if (config.push && content.size <= config.maxBlobSize.bytes) {
            scope.launch { pushToRemote(digest, content) }
        }
    }

    /**
     * Pulls an index entry that `CasCache` [push]ed under its synthetic [digest], or null when pull is disabled,
     * the remote has none or the pull fails. Not stored locally and not checked against [digest], which names the
     * entry, not its content; the blob the entry points to is still verified when it is pulled through [get].
     */
    suspend fun pullIndex(digest: CasDigest): ByteArray? = if (config.pull) pullBounded(digest) else null

    /**
     * Whether the cache [namespace] is shared through this layer: `cache.remote.include` minus `cache.remote.exclude`.
     * An empty `include` means every namespace except [LOCAL_ONLY_BY_DEFAULT]: provider responses are keyed without the
     * caller's identity, so sharing them would hand one token's view of a private repo to every other cache reader.
     */
    fun covers(namespace: String): Boolean =
        (if (config.include.isEmpty()) namespace !in LOCAL_ONLY_BY_DEFAULT else namespace in config.include) &&
            namespace !in config.exclude

    /** Check if a blob exists locally. */
    fun existsLocally(digest: CasDigest): Boolean = local.exists(digest)

    private val deduplicated = MutableStateFlow(0L)
    private val pullHits = MutableStateFlow(0L)
    private val pullMisses = MutableStateFlow(0L)
    private val pushes = MutableStateFlow(0L)
    private val pushFailures = MutableStateFlow(0L)

    private suspend fun pullFromRemote(digest: CasDigest): ByteArray? {
        val content = pullBounded(digest) ?: return null
        val actualDigest = CasDigest.of(content)
        if (actualDigest != digest) {
            logger.warn(
                "Remote cache integrity failure: expected ${digest.hex.take(LOG_DIGEST_LEN)}, " +
                    "got ${actualDigest.hex.take(LOG_DIGEST_LEN)}"
            )
            return null
        }

        local.put(content)
        logger.debug("Remote cache pull hit for ${shortDigest(digest)} (${content.size} bytes)")
        return content
    }

    /** Pulls [digest] within `pullTimeout`; null on a miss, a failure or content over `maxBlobSize`. */
    private suspend fun pullBounded(digest: CasDigest): ByteArray? {
        val content = try {
            withTimeoutOrNull(config.pullTimeout.millis) { remote.pull(digest) }
        } catch (e: IllegalStateException) {
            e.rethrowCancellation()
            logger.warn("Remote cache pull failed for ${shortDigest(digest)}: ${e.message}")
            null
        } catch (e: IOException) {
            logger.warn("Remote cache pull failed for ${shortDigest(digest)}: ${e.message}")
            null
        } ?: return null
        if (content.size > config.maxBlobSize.bytes) {
            logger.warn("Remote cache pull for ${shortDigest(digest)} skipped: ${content.size} bytes exceeds maxBlobSize")
            return null
        }
        return content
    }

    private suspend fun pushToRemote(
        digest: CasDigest,
        content: ByteArray
    ) {
        val pushTimeoutMs = config.pushTimeout.millis
        val stored = try {
            withTimeoutOrNull(pushTimeoutMs) { remote.push(digest, content) }.also { result ->
                when (result) {
                    true -> logger.debug("Remote cache push for ${shortDigest(digest)} (${content.size} bytes)")

                    // the backend logged the refusal
                    false -> Unit

                    null -> logger.warn("Remote cache push timed out for ${shortDigest(digest)} after ${pushTimeoutMs}ms")
                }
            }
        } catch (e: IllegalStateException) {
            e.rethrowCancellation()
            logger.warn("Remote cache push failed for ${shortDigest(digest)}: ${e.message}")
            null
        } catch (e: IOException) {
            logger.warn("Remote cache push failed for ${shortDigest(digest)}: ${e.message}")
            null
        }
        (if (stored == true) pushes else pushFailures).update { it + 1 }
    }

    private fun shortDigest(digest: CasDigest): String = digest.hex.take(LOG_DIGEST_LEN)

    private companion object {
        const val LOG_DIGEST_LEN = 12

        /** Namespaces an empty `cache.remote.include` keeps local; naming one in `include` shares it. */
        val LOCAL_ONLY_BY_DEFAULT: List<String> = CacheCategory.PROVIDER_RESPONSES.namespaces
    }
}
