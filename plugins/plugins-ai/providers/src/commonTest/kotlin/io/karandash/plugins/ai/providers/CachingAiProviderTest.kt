package io.karandash.plugins.ai.providers

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.cache.CacheTier
import io.karandash.cache.TieredCache
import io.karandash.plugins.ai.core.ResponseCacheSalt
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CachingAiProviderTest {
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
    fun `cache hit returns same response without calling delegate twice`() =
        runTest {
            val delegate = CountingProvider()
            val provider = CachingAiProvider(delegate, makeCache())

            val first = provider.complete("hello", model = "gpt-4o")
            val second = provider.complete("hello", model = "gpt-4o")

            assertEquals(1, delegate.callCount)
            assertEquals(first.content, second.content)
            assertEquals(1, provider.cacheSize())
        }

    @Test
    fun `a salted call never reads the unsalted entry but a rerun is served from the cache`() =
        runTest {
            val delegate = CountingProvider()
            val provider = CachingAiProvider(delegate, makeCache())

            provider.complete("hello", model = "gpt-4o")
            val second = ResponseCacheSalt.with("sample2") { provider.complete("hello", model = "gpt-4o") }
            val replay = ResponseCacheSalt.with("sample2") { provider.complete("hello", model = "gpt-4o") }

            assertEquals(2, delegate.callCount)
            assertEquals("response-2", second.content)
            assertEquals("response-2", replay.content)
        }

    @Test
    fun `a reply cut at the token limit is not cached`() =
        runTest {
            var calls = 0
            val delegate = object : AiProvider by CountingProvider() {
                override suspend fun completeRequest(request: AiCompletionRequest): AiResponse {
                    calls++
                    return AiResponse("cut", 10, 10, 20, request.model, truncated = true)
                }
            }
            val provider = CachingAiProvider(delegate, makeCache())

            repeat(2) { provider.complete("hello", model = "gpt-4o") }

            assertEquals(2, calls)
            assertEquals(0, provider.cacheSize())
        }

    @Test
    fun `different prompts are cached separately`() =
        runTest {
            val delegate = CountingProvider()
            val provider = CachingAiProvider(delegate, makeCache())

            provider.complete("prompt-a", model = "gpt-4o")
            provider.complete("prompt-b", model = "gpt-4o")

            assertEquals(2, delegate.callCount)
            assertEquals(2, provider.cacheSize())
        }

    @Test
    fun `cache respects max size with LRU eviction`() =
        runTest {
            val delegate = CountingProvider()
            val provider = CachingAiProvider(delegate, makeCache(maxEntries = 2))

            provider.complete("prompt-a", model = "gpt-4o")
            provider.complete("prompt-b", model = "gpt-4o")
            provider.complete("prompt-c", model = "gpt-4o")

            assertEquals(3, delegate.callCount)
            assertEquals(2, provider.cacheSize())

            // prompt-a was the oldest and should have been evicted; calling it again hits delegate
            provider.complete("prompt-a", model = "gpt-4o")
            assertEquals(4, delegate.callCount)
        }

    @Test
    fun `close clears cache and delegates`() =
        runTest {
            val delegate = CountingProvider()
            val provider = CachingAiProvider(delegate, makeCache())

            provider.complete("hello", model = "gpt-4o")
            assertEquals(1, provider.cacheSize())

            provider.close()

            assertEquals(0, provider.cacheSize())
        }

    @Test
    fun `clearCache removes all entries`() =
        runTest {
            val delegate = CountingProvider()
            val provider = CachingAiProvider(delegate, makeCache())

            provider.complete("prompt-a", model = "gpt-4o")
            provider.complete("prompt-b", model = "gpt-4o")
            assertEquals(2, provider.cacheSize())

            provider.clearCache()

            assertEquals(0, provider.cacheSize())
        }

    @Test
    fun `prompts with equal hashCode get separate entries`() =
        runTest {
            val delegate = CountingProvider()
            val provider = CachingAiProvider(delegate, makeCache())
            check("Aa".hashCode() == "BB".hashCode())

            provider.complete("Aa", model = "gpt-4o")
            val second = provider.complete("BB", model = "gpt-4o")

            assertEquals(2, delegate.callCount)
            assertEquals("response-2", second.content)
        }

    @Test
    fun `each call logs a cache hit or miss at info`() =
        runTest {
            val logger = RecordingLogger()
            val provider = CachingAiProvider(CountingProvider(), makeCache(), logger = logger)

            provider.complete("hello", model = "gpt-4o")
            provider.complete("hello", model = "gpt-4o")

            assertEquals(
                listOf(
                    "INFO [ai] cache miss: provider=counting model=gpt-4o",
                    "INFO [ai] cache hit: provider=counting model=gpt-4o",
                ),
                logger.lines,
            )
        }

    /** close() logs the run's hit and miss totals. */
    @Test
    fun `close logs the cache hit and miss totals`() =
        runTest {
            val logger = RecordingLogger()
            val provider = CachingAiProvider(CountingProvider(), makeCache(), logger = logger)

            provider.complete("hello", model = "gpt-4o")
            provider.complete("hello", model = "gpt-4o")
            provider.close()

            assertEquals("INFO [ai] response cache: 1 hits, 1 misses", logger.lines.last())
        }
}
