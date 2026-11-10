package io.karandash.registry

import io.karandash.cache.cas.CasCache
import io.karandash.crypto.ChecksumVerifier
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The registry checksum is enforced - a mismatching download installs nothing. */
class PluginInstallChecksumTest {
    private val payload = "plugin-bytes".encodeToByteArray()

    private fun tempDir(name: String): Path =
        TestFiles.createTestTempDirectory("$name").also {
            SystemFileSystem.createDirectories(it)
        }

    private fun cache() = DownloadCache(CasCache(baseDir = tempDir("cache"), namespace = "downloads"))

    private suspend fun install(
        pluginDir: Path,
        cache: DownloadCache,
        checksum: String,
    ) = PluginInstallUpdateEngine.downloadAndUpdate(
        downloadUrl = URL,
        pluginDirPath = pluginDir.toString(),
        oldSourcePath = null,
        pluginId = "p",
        supportedFormats = setOf("jar"),
        verifyPlugin = { null },
        httpClient = HttpClient(MockEngine { respond(payload) }),
        downloadCache = cache,
        expectedChecksum = checksum,
    )

    @Test
    fun `checksum mismatch installs and caches nothing`() =
        runTest {
            val pluginDir = tempDir("plugins")
            val cache = cache()

            val result = install(pluginDir, cache, "sha256:" + "0".repeat(SHA256_HEX_LENGTH))

            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("Checksum mismatch")
            )
            assertEquals(emptyList(), SystemFileSystem.list(pluginDir))
            assertNull(cache.get(URL))
        }

    @Test
    fun `matching checksum installs the plugin`() =
        runTest {
            val probe = Path(tempDir("probe"), "p.jar")
            SystemFileSystem.sink(probe).use { sink ->
                sink.write(Buffer().apply { write(payload) }, payload.size.toLong())
            }

            val result = install(tempDir("plugins"), cache(), "sha256:" + ChecksumVerifier.computeSha256(probe))

            assertEquals(null to "p.jar", result.getOrThrow())
        }

    private companion object {
        const val URL = "https://registry.test/p.jar"
        const val SHA256_HEX_LENGTH = 64
    }
}
