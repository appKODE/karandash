package io.karandash.http

import io.karandash.cache.CacheTier
import io.karandash.cache.TieredCache
import io.karandash.cache.cas.ConditionalFetch
import io.karandash.cache.cas.ConditionalResponse
import io.karandash.crypto.sha256Hex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ProviderResponseCacheTest {
    private val backing = TieredCache<String, CachedHttpResponse>(name = "provider-test", maxEntries = 2, tier = CacheTier.MEMORY_ONLY)
    private val cache = ProviderResponseCache(cache = ETagTieredCache(backing, defaultTtl = 5.minutes), defaultTtl = 5.minutes)

    private fun putExpired(
        url: String,
        etag: String?,
        nextLink: String? = null,
    ) = backing.put(
        sha256Hex("gitlab:GET:$url"),
        CachedHttpResponse(
            body = "old",
            etag = etag,
            storedAtEpochSeconds = Clock.System.now().epochSeconds - 3_600,
            ttlSeconds = 60,
            nextLink = nextLink,
        ),
    )

    @Test
    fun `fresh entry is a hit carrying body etag next link and the default ttl`() =
        runTest {
            cache.put("gitlab", "GET", "https://x/mr/1", "body", etag = "\"v1\"", nextLink = "https://x/mr/1?page=2")

            val lookup = cache.get("gitlab", "GET", "https://x/mr/1")

            val hit = assertNotNull(lookup.hit)
            assertNull(lookup.staleEtag)
            assertEquals("body", hit.responseBody)
            assertEquals("\"v1\"", hit.etag)
            assertEquals("https://x/mr/1?page=2", hit.nextLink)
            assertEquals(5.minutes.inWholeSeconds, hit.ttlSeconds)
        }

    @Test
    fun `entries are keyed by provider method and url`() =
        runTest {
            cache.put("gitlab", "GET", "https://x/mr/1", "body")

            assertNull(cache.get("github", "GET", "https://x/mr/1").hit)
            assertNull(cache.get("gitlab", "POST", "https://x/mr/1").hit)
            assertNull(cache.get("gitlab", "GET", "https://x/mr/2").hit)
            assertNotNull(cache.get("gitlab", "GET", "https://x/mr/1").hit)
        }

    @Test
    fun `per entry ttl overrides the default`() =
        runTest {
            cache.put("gitlab", "GET", "https://x/a", "body", ttl = 30.seconds)

            assertEquals(30, cache.get("gitlab", "GET", "https://x/a").hit?.ttlSeconds)
        }

    @Test
    fun `expired entry with etag is no hit but offers the etag for a conditional request`() =
        runTest {
            putExpired("https://x/a", etag = "\"v1\"")

            val lookup = cache.get("gitlab", "GET", "https://x/a")

            assertNull(lookup.hit)
            assertEquals("\"v1\"", lookup.staleEtag)
        }

    @Test
    fun `expired entry without etag is a plain miss`() =
        runTest {
            putExpired("https://x/a", etag = null)

            assertEquals(CacheLookup(hit = null, staleEtag = null), cache.get("gitlab", "GET", "https://x/a"))
        }

    @Test
    fun `refresh after 304 turns a stale entry into a hit with the new etag`() =
        runTest {
            putExpired("https://x/a", etag = "\"v1\"")

            val refreshed = assertNotNull(cache.refreshTtl("gitlab", "GET", "https://x/a", newEtag = "\"v2\""))

            assertEquals("old", refreshed.responseBody)
            val hit = assertNotNull(cache.get("gitlab", "GET", "https://x/a").hit)
            assertEquals("\"v2\"", hit.etag)
        }

    @Test
    fun `refresh of an unknown entry returns null and stores nothing`() =
        runTest {
            assertNull(cache.refreshTtl("gitlab", "GET", "https://x/none"))
            assertEquals(0, cache.size())
        }

    @Test
    fun `least recently used entry is evicted at capacity`() =
        runTest {
            cache.put("gitlab", "GET", "https://x/1", "one")
            cache.put("gitlab", "GET", "https://x/2", "two")
            cache.get("gitlab", "GET", "https://x/1")
            cache.put("gitlab", "GET", "https://x/3", "three")

            assertEquals(2, cache.size())
            assertNull(cache.get("gitlab", "GET", "https://x/2").hit)
            assertNotNull(cache.get("gitlab", "GET", "https://x/1").hit)
            assertNotNull(cache.get("gitlab", "GET", "https://x/3").hit)
        }

    @Test
    fun `clear empties the cache`() =
        runTest {
            cache.put("gitlab", "GET", "https://x/1", "one")
            cache.clear()

            assertEquals(0, cache.size())
            assertNull(cache.get("gitlab", "GET", "https://x/1").hit)
        }

    @Test
    fun `standalone constructor caches with the given ttl`() =
        runTest {
            val standalone = ProviderResponseCache(maxEntries = 4, defaultTtl = 10.seconds)
            standalone.put("github", "GET", "https://x/1", "one")

            assertEquals(10, standalone.get("github", "GET", "https://x/1").hit?.ttlSeconds)
        }

    @Test
    fun `getOrFetch revalidates a stale entry and a 304 keeps the cached body and next link`() =
        runTest {
            putExpired("https://x/a", etag = "\"v1\"", nextLink = "https://x/a?page=2")
            val sentEtags = mutableListOf<String?>()

            val page = cache.getOrFetch("gitlab", "GET", "https://x/a") { etag ->
                sentEtags += etag
                ConditionalResponse(statusCode = 304, content = "")
            }

            assertEquals(listOf<String?>("\"v1\""), sentEtags)
            assertEquals("old", page.content)
            assertEquals("https://x/a?page=2", page.nextLink)
            assertNotNull(cache.get("gitlab", "GET", "https://x/a").hit)
        }

    @Test
    fun `getOrFetch stores a miss with its etag and next link and serves the next call from the cache`() =
        runTest {
            val fetched = ConditionalResponse(statusCode = 200, content = "new", etag = "\"v1\"", nextLink = "https://x/b?page=2")
            val calls = mutableListOf<String?>()
            val fetch =
                ConditionalFetch { etag ->
                    calls += etag
                    fetched
                }

            cache.getOrFetch("gitlab", "GET", "https://x/b", fetch)
            val second = cache.getOrFetch("gitlab", "GET", "https://x/b", fetch)

            assertEquals(listOf<String?>(null), calls)
            assertEquals("new", second.content)
            assertEquals("https://x/b?page=2", second.nextLink)
            assertEquals("\"v1\"", cache.get("gitlab", "GET", "https://x/b").hit?.etag)
        }

    @Test
    fun `concurrent calls for one url fetch once`() =
        runTest {
            var fetches = 0
            val fetch = ConditionalFetch {
                fetches++
                delay(10)
                ConditionalResponse(200, "body", null, null)
            }

            val all = List(5) { async { cache.getOrFetch("gitlab", "GET", "https://x/search", fetch) } }.awaitAll()

            assertEquals(1, fetches)
            assertEquals(5, all.count { it.content == "body" })
        }
}
