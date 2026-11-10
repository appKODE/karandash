package io.karandash.http

import io.karandash.cache.CacheConfig
import io.karandash.cache.CacheManager
import io.karandash.cache.CacheTier
import io.karandash.cache.cas.ConditionalResponse
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

/** a MEMORY_AND_FILE response cache is read back from the disk tier by a later run. */
class ResponseCacheDiskTierTest {
    private val dir = TestFiles.createTestTempDirectory("test-response-disk")

    private fun etagCache(): ETagTieredCache {
        SystemFileSystem.createDirectories(dir)
        val manager = CacheManager(config = CacheConfig(), cacheDir = dir)
        val tiered = manager.getOrCreate(ProviderResponseCache.CACHE_NAME, httpResponseCacheSpec(8, tier = CacheTier.MEMORY_AND_FILE))
        return ETagTieredCache(tiered, 5.minutes)
    }

    @Test
    fun `an entry written by one run is fresh for the next run on the same cache dir`() =
        runTest {
            etagCache().put("k", body = "persisted", etag = "\"v1\"")

            val hit = assertIs<ETagCacheHit.Fresh>(etagCache().get("k"))
            assertEquals("persisted", hit.response.body)
        }

    @Test
    fun `provider getOrFetch serves the persisted body without fetching`() =
        runTest {
            ProviderResponseCache(etagCache()).getOrFetch("gitlab", "GET", "https://x/mr/1") {
                ConditionalResponse(200, "first", "\"v1\"")
            }

            val second = ProviderResponseCache(etagCache()).getOrFetch("gitlab", "GET", "https://x/mr/1") {
                error("must be served from the disk tier")
            }
            assertEquals("first", second.content)
        }
}
