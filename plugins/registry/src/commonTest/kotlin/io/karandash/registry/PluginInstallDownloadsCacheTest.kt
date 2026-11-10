package io.karandash.registry

import io.karandash.cache.DownloadsCacheConfig
import io.karandash.cache.cas.CasCache
import io.karandash.loader.DownloadCache
import io.karandash.testfixtures.TestFiles
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** `cache.downloads` governs plugin install/update: `enabled: false` always downloads and caches nothing. */
class PluginInstallDownloadsCacheTest {
    private val payload = "fresh-bytes".encodeToByteArray()
    private val cas = CasCache(baseDir = tempDir("cache"), namespace = "downloads")

    private fun tempDir(name: String): Path =
        TestFiles.createTestTempDirectory("dl-cache-$name").also {
            SystemFileSystem.createDirectories(it)
        }

    /** Seeds the shared CAS with a cached copy of [URL], as an earlier install would have left it. */
    private suspend fun seedCache() {
        val seed = Path(tempDir("seed"), "p.jar")
        val bytes = "cached-bytes".encodeToByteArray()
        SystemFileSystem.sink(seed).use { it.write(Buffer().apply { write(bytes) }, bytes.size.toLong()) }
        DownloadCache(cas).put(URL, seed, extension = "jar")
    }

    private suspend fun install(cache: DownloadCache?): Int {
        val requests = mutableListOf<String>()
        PluginInstallUpdateEngine
            .downloadAndUpdate(
                downloadUrl = URL,
                pluginDirPath = tempDir("plugins").toString(),
                oldSourcePath = null,
                pluginId = "p",
                supportedFormats = setOf("jar"),
                verifyPlugin = { null },
                httpClient = HttpClient(
                    MockEngine {
                        requests += it.url.toString()
                        respond(payload)
                    }
                ),
                downloadCache = cache,
            ).getOrThrow()
        return requests.size
    }

    @Test
    fun disabledDownloadsCacheBypassesCachedEntries() =
        runTest {
            seedCache()
            val cache = PluginInstallUpdateEngine.downloadCache(DownloadsCacheConfig(enabled = false)) { cas }

            assertNull(cache)
            assertEquals(1, install(cache))
            assertEquals(1, install(cache))
        }

    @Test
    fun enabledDownloadsCacheServesCachedEntries() =
        runTest {
            seedCache()
            val cache = PluginInstallUpdateEngine.downloadCache(DownloadsCacheConfig(enabled = true)) { cas }

            assertNotNull(cache)
            assertEquals(0, install(cache))
        }

    private companion object {
        const val URL = "https://registry.test/p.jar"
    }
}
