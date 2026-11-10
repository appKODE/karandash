package io.karandash.loader

import io.karandash.api.exception.PluginSecurityException
import io.karandash.cache.cas.CasCache
import io.karandash.crypto.ChecksumVerifier
import io.karandash.download.ArtifactDownloader
import io.karandash.testfixtures.TestFiles
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Integration test: fetch remote plugin, verify checksum, cache it.
 */
class RemotePluginIntegrationTest {
    private lateinit var tempDir: Path
    private lateinit var cacheBaseDir: Path

    private val pluginContent = "fake-plugin-jar-content-for-integration-test"

    @BeforeTest
    fun setup() {
        val base = TestFiles.createTestTempDirectory("remote-integration-base")
        val timestamp = Clock.System.now().toEpochMilliseconds()
        tempDir = Path("$base/remote-integration-test-$timestamp")
        createDirectories(tempDir)
        cacheBaseDir = Path("$tempDir/cache")
        createDirectories(cacheBaseDir)
    }

    @AfterTest
    fun cleanup() {
        cleanupDir(tempDir)
    }

    private fun cleanupDir(dir: Path) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory()) {
                cleanupDir(file)
            } else {
                deleteIfExists(file)
            }
        }
        deleteIfExists(dir)
    }

    private fun makeCache(ttlMillis: Long = DownloadCache.DEFAULT_TTL_MILLIS): DownloadCache {
        val casCache = CasCache(baseDir = cacheBaseDir, namespace = "downloads")
        return DownloadCache(casCache = casCache, ttlMillis = ttlMillis)
    }

    private fun createMockClient(): HttpClient =
        HttpClient(MockEngine) {
            engine {
                addHandler { _ ->
                    respond(
                        content = ByteReadChannel(pluginContent.encodeToByteArray()),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/java-archive")
                    )
                }
            }
        }

    @Test
    fun `full workflow fetch verify checksum cache`() =
        runTest {
            val client = createMockClient()
            val fetcher = ArtifactDownloader(client)
            val cache = makeCache()
            val url = "https://plugins.example.com/my-plugin-1.0.jar"

            // Check cache (should miss)
            assertNull(cache.get(url))

            // Fetch remote plugin
            val downloadPath = Path("$tempDir/downloaded.jar")
            val fetchResult = fetcher.fetch(url, downloadPath)
            assertTrue(fetchResult.isSuccess)

            // Compute and verify checksum
            val actualChecksum = "sha256:" + ChecksumVerifier.computeSha256(downloadPath)
            assertTrue(ChecksumVerifier.verify(downloadPath, actualChecksum))

            // Cache the downloaded JAR
            val cacheEntry = cache.put(url, downloadPath, actualChecksum)
            assertNotNull(cacheEntry)

            // Subsequent fetch should hit cache
            val cachedEntry = cache.get(url)
            assertNotNull(cachedEntry)
            assertTrue(cachedEntry.filePath.exists())

            // Verify cached content matches
            val cachedContent = cachedEntry.filePath.readBytes().decodeToString()
            assertTrue(cachedContent == pluginContent)
        }

    @Test
    fun `checksum mismatch rejects plugin`() =
        runTest {
            val client = createMockClient()
            val fetcher = ArtifactDownloader(client)
            val url = "https://plugins.example.com/tampered.jar"

            val downloadPath = Path("$tempDir/downloaded.jar")
            val fetchResult = fetcher.fetch(url, downloadPath)
            assertTrue(fetchResult.isSuccess)

            // Use a wrong checksum
            val wrongChecksum = "sha256:0000000000000000000000000000000000000000000000000000000000000000"
            assertFalse(ChecksumVerifier.verify(downloadPath, wrongChecksum))
        }

    @Test
    fun `HTTP URL rejected in full workflow`() =
        runTest {
            val client = createMockClient()
            val fetcher = ArtifactDownloader(client)
            val downloadPath = Path("$tempDir/downloaded.jar")

            val result = fetcher.fetch("http://insecure.example.com/plugin.jar", downloadPath)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is PluginSecurityException)
            assertFalse(downloadPath.exists())
        }

    @Test
    fun `cache TTL prevents stale entries`() =
        runTest {
            val client = createMockClient()
            val fetcher = ArtifactDownloader(client)
            val url = "https://plugins.example.com/plugin.jar"

            // First: verify entry is accessible with normal TTL
            val longTtlCache = makeCache(ttlMillis = 600_000L)
            val downloadPath = Path("$tempDir/downloaded.jar")
            fetcher.fetch(url, downloadPath)
            longTtlCache.put(url, downloadPath)
            assertNotNull(longTtlCache.get(url))

            // Second: create cache with already-expired TTL (negative = instant expiry)
            val expiredCache = makeCache(ttlMillis = -1_000L)
            val downloadPath2 = Path("$tempDir/downloaded2.jar")
            fetcher.fetch(url, downloadPath2)
            expiredCache.put(url, downloadPath2)

            // Should be expired immediately
            assertNull(expiredCache.get(url))
        }
}
