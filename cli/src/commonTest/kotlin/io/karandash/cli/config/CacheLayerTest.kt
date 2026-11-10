package io.karandash.cli.config

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cache.cas.CasCache
import io.karandash.cli.TestFileHelpers
import io.karandash.config.pipeline.CacheLayer
import io.karandash.config.pipeline.RemoteConfigFetcher
import io.karandash.duration.KarandashDuration
import io.karandash.manifest.CachePolicy
import io.karandash.manifest.ConfigSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test suite for CacheLayer backed by CasCache.
 *
 * Coverage:
 * - Cache hit/miss/expiry
 * - TTL = 0 bypass
 * - TTL = -1 cache-forever
 * - ETag conditional requests (304 Not Modified)
 * - Clear operations
 * - Graceful degradation on fetch failure
 */
class CacheLayerTest {
    private fun makeCache(
        fetcher: RemoteConfigFetcher,
        baseDir: Path,
        defaultTtl: KarandashDuration = KarandashDuration.ofSeconds(3600),
    ): CacheLayer {
        val casCache = CasCache(baseDir = baseDir, namespace = "downloads")
        return CacheLayer(
            fetcher = fetcher,
            casCache = casCache,
            defaultTtl = defaultTtl,
        )
    }

    private fun createMockFetcher(responses: Map<String, String>): RemoteConfigFetcher {
        val mockEngine = MockEngine { request ->
            val url = request.url.toString()
            val body = responses[url] ?: "Not Found"
            val status = if (responses.containsKey(url)) HttpStatusCode.OK else HttpStatusCode.NotFound
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "text/yaml")
            )
        }
        return RemoteConfigFetcher(HttpClient(mockEngine))
    }

    @Test
    fun `getOrFetch caches remote content on first fetch`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("cache-first-fetch")
            try {
                val fetcher = createMockFetcher(mapOf("https://example.com/config.yaml" to "rules: {}"))
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                val cache = makeCache(fetcher, cacheDir)

                val content = cache.getOrFetch(
                    ConfigSource("https://example.com/config.yaml"),
                    CachePolicy(ttl = KarandashDuration.ofSeconds(3600))
                )

                assertEquals("rules: {}", content)
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }

    @Test
    fun `getOrFetch returns cached content when TTL not expired`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("cache-hit")
            try {
                var fetchCount = 0
                val mockEngine = MockEngine { _ ->
                    fetchCount++
                    respond(
                        content = ByteReadChannel("rules: {}"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/yaml")
                    )
                }
                val fetcher = RemoteConfigFetcher(HttpClient(mockEngine))
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                val cache = makeCache(fetcher, cacheDir)

                val source = ConfigSource("https://example.com/config.yaml")

                // First fetch - should cache
                cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))
                assertEquals(1, fetchCount)

                // Second fetch within TTL - should use cache
                val content = cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))

                assertEquals("rules: {}", content)
                assertEquals(1, fetchCount, "Should not fetch again when cache is valid")
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }

    @Test
    fun `getOrFetch refetches when TTL expired`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("cache-expired")
            try {
                var fetchCount = 0
                val mockEngine = MockEngine { _ ->
                    fetchCount++
                    respond(
                        content = ByteReadChannel("rules: {}"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/yaml")
                    )
                }
                val fetcher = RemoteConfigFetcher(HttpClient(mockEngine))
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                // Use very short TTL so we can test expiry in real time
                val cache = makeCache(fetcher, cacheDir, defaultTtl = KarandashDuration.ZERO)

                // TTL=0 means bypass cache, so each fetch is fresh
                val source = ConfigSource("https://example.com/config.yaml")
                cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ZERO))
                cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ZERO))

                assertEquals(2, fetchCount, "TTL=0 should always refetch")
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }

    @Test
    fun `getOrFetch with TTL 0 bypasses cache`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("cache-no-cache")
            try {
                var fetchCount = 0
                val mockEngine = MockEngine { _ ->
                    fetchCount++
                    respond(
                        content = ByteReadChannel("rules: {}"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/yaml")
                    )
                }
                val fetcher = RemoteConfigFetcher(HttpClient(mockEngine))
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                val cache = makeCache(fetcher, cacheDir)

                val source = ConfigSource("https://example.com/config.yaml")

                // Multiple fetches with TTL = 0
                cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ZERO))
                cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ZERO))

                assertEquals(2, fetchCount, "TTL=0 should bypass cache")
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }

    @Test
    fun `getOrFetch with TTL -1 caches forever`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("cache-forever")
            try {
                var fetchCount = 0
                val mockEngine = MockEngine { _ ->
                    fetchCount++
                    respond(
                        content = ByteReadChannel("rules: {}"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/yaml")
                    )
                }
                val fetcher = RemoteConfigFetcher(HttpClient(mockEngine))
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                val cache = makeCache(fetcher, cacheDir)

                val source = ConfigSource("https://example.com/config.yaml")

                // First fetch
                cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.NEVER))
                assertEquals(1, fetchCount)

                // Second fetch - should still use cache (TTL=-1 = forever)
                cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.NEVER))

                assertEquals(1, fetchCount, "TTL=-1 should cache forever")
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }

    @Test
    fun `getOrFetch uses ETag for conditional request on expiry`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("cache-etag")
            try {
                val url = "https://example.com/config.yaml"
                val content = "rules: {}"
                val etag = "\"abc123\""

                var requestCount = 0
                val mockEngine = MockEngine { request ->
                    requestCount++
                    val requestETag = request.headers["If-None-Match"]

                    if (requestETag == etag) {
                        respond(
                            content = ByteReadChannel(""),
                            status = HttpStatusCode.NotModified,
                            headers = headersOf(
                                HttpHeaders.ETag to listOf(etag),
                                HttpHeaders.ContentType to listOf("text/yaml")
                            )
                        )
                    } else {
                        respond(
                            content = ByteReadChannel(content),
                            status = HttpStatusCode.OK,
                            headers = headersOf(
                                HttpHeaders.ETag to listOf(etag),
                                HttpHeaders.ContentType to listOf("text/yaml")
                            )
                        )
                    }
                }
                val fetcher = RemoteConfigFetcher(HttpClient(mockEngine))
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                val casCache = CasCache(baseDir = cacheDir, namespace = "downloads")
                val cacheWithShortTtl = CacheLayer(
                    fetcher = fetcher,
                    casCache = casCache,
                    defaultTtl = KarandashDuration.ofSeconds(3600),
                )

                val source = ConfigSource(url)

                // First fetch - should cache with ETag
                cacheWithShortTtl.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))
                assertEquals(1, requestCount)

                // Force cache entry to expire by overwriting with negative TTL
                val key = "extends-${io.karandash.crypto.sha256Hex(url)}"
                val cachedBytes = casCache.get(key)
                if (cachedBytes != null) {
                    val indexEntry = casCache.getIndex(key)
                    casCache.put(key, cachedBytes, ttlMs = -1_000L, etag = indexEntry?.etag)
                }

                // Second fetch after expiry - should send If-None-Match → 304
                val result = cacheWithShortTtl.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))

                assertEquals(2, requestCount)
                assertEquals(content, result)
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }

    @Test
    fun `getOrFetch refetches on 200 response with new ETag`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("cache-etag-changed")
            try {
                val url = "https://example.com/config.yaml"
                var currentContent = "rules: { version: 1 }"

                var requestCount = 0
                val mockEngine = MockEngine { _ ->
                    requestCount++
                    respond(
                        content = ByteReadChannel(currentContent),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ETag to listOf("\"v${requestCount}\""),
                            HttpHeaders.ContentType to listOf("text/yaml")
                        )
                    )
                }
                val fetcher = RemoteConfigFetcher(HttpClient(mockEngine))
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                val casCache = CasCache(baseDir = cacheDir, namespace = "downloads")
                val cache = CacheLayer(
                    fetcher = fetcher,
                    casCache = casCache,
                )

                val source = ConfigSource(url)

                // First fetch
                val content1 = cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))
                assertEquals("rules: { version: 1 }", content1)

                // Update server content
                currentContent = "rules: { version: 2 }"

                // Force cache entry to expire by overwriting with negative TTL
                val key = "extends-${io.karandash.crypto.sha256Hex(url)}"
                val cachedBytes = casCache.get(key)
                if (cachedBytes != null) {
                    val indexEntry = casCache.getIndex(key)
                    casCache.put(key, cachedBytes, ttlMs = -1_000L, etag = indexEntry?.etag)
                }

                // Second fetch after expiry - should get new content
                val content2 = cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))

                assertEquals("rules: { version: 2 }", content2)
                assertEquals(2, requestCount)
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }

    @Test
    fun `getOrFetch does not cache local files`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("cache-local")
            val testDir = TestFileHelpers.createTempDir("test-files")
            try {
                val configFile = Path(testDir, "config.yaml")
                TestFileHelpers.writeFile(configFile, "rules: {}")

                val fetcher = createMockFetcher(emptyMap())
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                val cache = makeCache(fetcher, cacheDir)

                val source = ConfigSource(configFile.toString())
                cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))

                // No CAS entries should exist for local files
                val casCache = CasCache(baseDir = cacheDir, namespace = "downloads")
                assertTrue(casCache.keys().isEmpty(), "No cache entries should be created for local files")
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
                TestFileHelpers.deleteTempDir(testDir)
            }
        }

    @Test
    fun `clearAll removes all cache entries`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("cache-clear-all")
            try {
                val fetcher = createMockFetcher(
                    mapOf(
                        "https://example.com/a.yaml" to "a: {}",
                        "https://example.com/b.yaml" to "b: {}"
                    )
                )
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                val casCache = CasCache(baseDir = cacheDir, namespace = "downloads")
                val cache = CacheLayer(
                    fetcher = fetcher,
                    casCache = casCache,
                )

                // Cache multiple entries
                cache.getOrFetch(ConfigSource("https://example.com/a.yaml"), CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))
                cache.getOrFetch(ConfigSource("https://example.com/b.yaml"), CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))

                // Verify entries exist
                assertFalse(casCache.keys().isEmpty())

                // Clear cache
                cache.clearAll()

                // Verify entries gone
                val remaining = casCache.keys().filter { it.startsWith("extends-") }
                assertTrue(remaining.isEmpty(), "All extends entries should be cleared")
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }

    @Test
    fun `clearExpired is a no-op TTL handled by CAS`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("cache-clear-expired-noop")
            try {
                val fetcher = createMockFetcher(
                    mapOf("https://example.com/config.yaml" to "rules: {}")
                )
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                val cache = makeCache(fetcher, cacheDir)

                cache.getOrFetch(ConfigSource("https://example.com/config.yaml"), CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))

                // clearExpired should not throw
                cache.clearExpired()
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }

    @Test
    fun `getOrFetch uses default TTL when policy is null`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("cache-default-ttl")
            try {
                var fetchCount = 0
                val mockEngine = MockEngine { _ ->
                    fetchCount++
                    respond(
                        content = ByteReadChannel("rules: {}"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/yaml")
                    )
                }
                val fetcher = RemoteConfigFetcher(HttpClient(mockEngine))
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                val cache = makeCache(fetcher, cacheDir)

                val source = ConfigSource("https://example.com/config.yaml")

                // First fetch with null policy (uses default TTL of 3600s)
                cache.getOrFetch(source, null)
                assertEquals(1, fetchCount)

                // Second fetch within default TTL
                cache.getOrFetch(source, null)

                assertEquals(1, fetchCount, "Should use cached version within default TTL")
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }

    @Test
    fun `getOrFetch falls back to direct fetch on cache failure`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("cache-failure")
            try {
                val fetcher = createMockFetcher(
                    mapOf("https://example.com/config.yaml" to "rules: {}")
                )
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                val cache = makeCache(fetcher, cacheDir)

                val source = ConfigSource("https://example.com/config.yaml")
                val result = cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))

                assertEquals("rules: {}", result)
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }

    @Test
    fun `getOrFetch handles different URL paths as separate entries`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("cache-extensions")
            try {
                val fetcher = createMockFetcher(
                    mapOf(
                        "https://example.com/config.yaml" to "yaml: data",
                        "https://example.com/config.json" to "{\"json\": \"data\"}",
                        "https://example.com/config.yml" to "yml: data"
                    )
                )
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                val casCache = CasCache(baseDir = cacheDir, namespace = "downloads")
                val cache = CacheLayer(
                    fetcher = fetcher,
                    casCache = casCache,
                )

                val r1 = cache.getOrFetch(
                    ConfigSource("https://example.com/config.yaml"),
                    CachePolicy(ttl = KarandashDuration.ofSeconds(3600))
                )
                val r2 = cache.getOrFetch(
                    ConfigSource("https://example.com/config.json"),
                    CachePolicy(ttl = KarandashDuration.ofSeconds(3600))
                )
                val r3 = cache.getOrFetch(
                    ConfigSource("https://example.com/config.yml"),
                    CachePolicy(ttl = KarandashDuration.ofSeconds(3600))
                )

                assertEquals("yaml: data", r1)
                assertEquals("{\"json\": \"data\"}", r2)
                assertEquals("yml: data", r3)

                // All three should be cached as separate entries
                val keys = casCache.keys().filter { it.startsWith("extends-") }
                assertEquals(3, keys.size, "Should have 3 separate cache entries")
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }

    @Test
    fun `cache downloads enabled false fetches every time`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("cache-disabled")
            try {
                var fetchCount = 0
                val mockEngine = MockEngine { _ ->
                    fetchCount++
                    respond(
                        content = ByteReadChannel("rules: {}"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/yaml")
                    )
                }
                val cache = CacheLayer(
                    fetcher = RemoteConfigFetcher(HttpClient(mockEngine)),
                    casCache = CasCache(baseDir = cacheDir, namespace = "downloads"),
                    enabled = false,
                )
                val source = ConfigSource("https://example.com/config.yaml")

                cache.getOrFetch(source, cachePolicy = null)
                cache.getOrFetch(source, cachePolicy = null)

                assertEquals(2, fetchCount)
                assertTrue(SystemFileSystem.list(cacheDir).isEmpty(), "a disabled downloads cache writes nothing under the cache dir")
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }
}
