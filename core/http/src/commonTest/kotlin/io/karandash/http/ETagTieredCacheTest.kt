package io.karandash.http

import io.karandash.cache.CacheTier
import io.karandash.cache.TieredCache
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class ETagTieredCacheTest {
    private val backing = TieredCache<String, CachedHttpResponse>(name = "etag-test", maxEntries = 8, tier = CacheTier.MEMORY_ONLY)
    private val cache = ETagTieredCache(cache = backing, defaultTtl = 5.minutes)

    private fun expired(etag: String?) =
        CachedHttpResponse(
            body = "old",
            etag = etag,
            storedAtEpochSeconds = Clock.System.now().epochSeconds - 3_600,
            ttlSeconds = 60,
        )

    @Test
    fun subSecondTtlStillExpiresInsteadOfLivingForever() =
        runTest {
            cache.put("k", body = "body", ttl = 500.milliseconds)

            assertEquals(1, backing.getMemory("k")?.ttlSeconds)
        }

    @Test
    fun missReturnsNull() =
        runTest {
            assertNull(cache.get("absent"))
        }

    @Test
    fun entryWithinTtlIsFreshAndKeepsEtagAndNextLink() =
        runTest {
            cache.put("k", body = "body", etag = "\"v1\"", nextLink = "https://example.com/page2")

            val hit = assertIs<ETagCacheHit.Fresh>(cache.get("k"))
            assertEquals("body", hit.response.body)
            assertEquals("\"v1\"", hit.response.etag)
            assertEquals("https://example.com/page2", hit.response.nextLink)
            assertEquals(5.minutes.inWholeSeconds, hit.response.ttlSeconds)
        }

    @Test
    fun expiredEntryWithEtagIsStaleForConditionalRequest() =
        runTest {
            backing.put("k", expired(etag = "\"v1\""))

            val hit = assertIs<ETagCacheHit.StaleWithEtag>(cache.get("k"))
            assertEquals("\"v1\"", hit.etag)
            assertEquals("old", hit.response.body)
        }

    @Test
    fun expiredEntryWithoutEtagIsAMiss() =
        runTest {
            backing.put("k", expired(etag = null))

            assertNull(cache.get("k"))
        }

    @Test
    fun zeroTtlNeverExpires() =
        runTest {
            backing.put("k", expired(etag = null).copy(ttlSeconds = 0))

            assertIs<ETagCacheHit.Fresh>(cache.get("k"))
        }

    @Test
    fun refreshTtlAfter304MakesStaleEntryFreshAndAdoptsNewEtag() =
        runTest {
            backing.put("k", expired(etag = "\"v1\""))

            val refreshed = assertNotNull(cache.refreshTtl("k", newEtag = "\"v2\""))

            assertEquals("old", refreshed.body)
            assertEquals("\"v2\"", refreshed.etag)
            val hit = assertIs<ETagCacheHit.Fresh>(cache.get("k"))
            assertEquals("\"v2\"", hit.response.etag)
        }

    @Test
    fun refreshTtlKeepsOldEtagWhenServerSendsNone() =
        runTest {
            backing.put("k", expired(etag = "\"v1\""))

            assertEquals("\"v1\"", cache.refreshTtl("k")?.etag)
        }

    @Test
    fun refreshTtlOnMissingKeyReturnsNullAndStoresNothing() =
        runTest {
            assertNull(cache.refreshTtl("absent"))
            assertEquals(0, cache.size())
        }

    @Test
    fun clearDropsEveryEntry() =
        runTest {
            cache.put("a", body = "1")
            cache.put("b", body = "2")
            assertEquals(2, cache.size())

            cache.clear()

            assertEquals(0, cache.size())
            assertNull(cache.get("a"))
        }

    @Test
    fun serializerRoundTripsEveryField() =
        runTest {
            val value = expired(etag = "\"v1\"").copy(nextLink = "https://example.com/n")

            val decoded = CachedHttpResponseSerializer.deserializeValue(CachedHttpResponseSerializer.serializeValue(value))

            assertEquals(value, decoded)
            assertTrue(httpResponseCacheSpec(4, tier = CacheTier.MEMORY_AND_FILE).serializer != null)
            assertNull(httpResponseCacheSpec(4).serializer)
        }
}
