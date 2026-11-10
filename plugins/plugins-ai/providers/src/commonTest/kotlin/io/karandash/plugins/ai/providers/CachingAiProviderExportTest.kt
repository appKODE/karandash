package io.karandash.plugins.ai.providers

import io.karandash.cache.CacheTier
import io.karandash.cache.TieredCache
import io.karandash.duration.KarandashDuration
import io.karandash.plugins.ai.core.CachedResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class CachingAiProviderExportTest {
    private class CountingProvider : AiProvider {
        override val providerId = "counting"
        var callCount = 0

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse {
            callCount++
            return AiResponse(
                content = "response-$callCount",
                promptTokens = 10,
                completionTokens = 10,
                totalTokens = 20,
                model = model,
            )
        }

        override suspend fun close() {}
    }

    private fun makeCache(
        maxEntries: Int = 100,
        ttl: kotlin.time.Duration? = null
    ): TieredCache<String, AiResponse> =
        TieredCache(
            name = "test-cache",
            maxEntries = maxEntries,
            ttl = ttl,
            tier = CacheTier.MEMORY_ONLY,
        )

    @Test
    fun `exportCache returns non-expired entries`() =
        runTest {
            val delegate = CountingProvider()
            val provider = CachingAiProvider(delegate, makeCache())

            provider.complete("hello", model = "gpt-4o")

            val exported = provider.exportCache()
            assertEquals(1, exported.size)
            val entry = exported.values.first()
            assertEquals("response-1", entry.content)
            assertEquals("gpt-4o", entry.model)
            assertEquals(10, entry.promptTokens)
            assertEquals(10, entry.completionTokens)
            assertEquals(20, entry.totalTokens)
        }

    @Test
    fun `importCache loads entries and they serve as cache hits`() =
        runTest {
            val delegate = CountingProvider()
            val provider = CachingAiProvider(delegate, makeCache())

            // Build a cache key matching what buildCacheKey produces for ("gpt-4o", "hello", null, 1024, 0.0)
            // We export from one provider instance and import into another.
            val source = CachingAiProvider(delegate, makeCache())
            source.complete("hello", model = "gpt-4o")
            val exported = source.exportCache()

            provider.importCache(exported)

            // Should be a cache hit - delegate should not be called again
            val callsBefore = delegate.callCount
            provider.complete("hello", model = "gpt-4o")
            assertEquals(callsBefore, delegate.callCount)
            assertEquals(1, provider.cacheSize())
        }

    @Test
    fun `importCache skips expired entries`() =
        runTest {
            val delegate = CountingProvider()
            val now = FIXED_NOW
            val provider = CachingAiProvider(delegate, makeCache(), clock = FixedClock(now))

            val expiredTimestamp = now - 30.minutes.inWholeSeconds - 1
            val entries = mapOf(
                "some-key" to CachedResponse(
                    content = "stale response",
                    promptTokens = 10,
                    completionTokens = 10,
                    totalTokens = 20,
                    model = "gpt-4o",
                    createdAt = expiredTimestamp,
                )
            )

            provider.importCache(entries, ttl = KarandashDuration.ofMinutes(30))

            assertEquals(0, provider.cacheSize())
        }

    @Test
    fun `importCache respects maxSize`() =
        runTest {
            val delegate = CountingProvider()
            val now = FIXED_NOW
            val provider = CachingAiProvider(delegate, makeCache(maxEntries = 2), clock = FixedClock(now))

            val entries = mapOf(
                "key-1" to CachedResponse("r1", 10, 10, 20, "gpt-4o", now),
                "key-2" to CachedResponse("r2", 10, 10, 20, "gpt-4o", now),
                "key-3" to CachedResponse("r3", 10, 10, 20, "gpt-4o", now),
            )

            provider.importCache(entries)

            assertTrue(provider.cacheSize() <= 2)
        }

    @Test
    fun `exportCache returns empty map when cache is empty`() =
        runTest {
            val delegate = CountingProvider()
            val provider = CachingAiProvider(delegate, makeCache())

            val exported = provider.exportCache()
            assertTrue(exported.isEmpty())
        }

    @Test
    fun `exportCache does not include expired entries`() =
        runTest {
            val delegate = CountingProvider()
            val provider = CachingAiProvider(delegate, makeCache(ttl = kotlin.time.Duration.ZERO))

            provider.complete("hello", model = "gpt-4o")

            // With zero TTL, entries expire immediately
            val exported = provider.exportCache()
            assertTrue(exported.isEmpty())
        }

    private companion object {
        const val FIXED_NOW = 1_700_000_000L
    }

    private class FixedClock(
        private val epochSeconds: Long,
    ) : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds)
    }

    private fun cached(createdAt: Long) = CachedResponse("r", 10, 10, 20, "gpt-4o", createdAt)

    @Test
    fun `importCache TTL boundary uses injected clock`() =
        runTest {
            val now = 1_000_000L
            val ttl = KarandashDuration.ofMinutes(30)
            val provider = CachingAiProvider(CountingProvider(), makeCache(), clock = FixedClock(now))

            provider.importCache(
                mapOf(
                    "fresh" to cached(now - ttl.seconds + 1),
                    "boundary" to cached(now - ttl.seconds),
                    "stale" to cached(now - ttl.seconds - 1),
                ),
                ttl = ttl,
            )

            assertEquals(1, provider.cacheSize())
        }

    @Test
    fun `exportCache stamps createdAt from injected clock`() =
        runTest {
            val provider = CachingAiProvider(CountingProvider(), makeCache(), clock = FixedClock(42L))

            provider.complete("hello", model = "gpt-4o")

            assertEquals(
                42L,
                provider
                    .exportCache()
                    .values
                    .single()
                    .createdAt
            )
        }
}
