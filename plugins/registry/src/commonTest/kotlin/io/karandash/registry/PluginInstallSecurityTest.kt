package io.karandash.registry

import io.karandash.platform.exists
import io.karandash.testfixtures.TestFiles
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Install/update downloads go through `ArtifactDownloader` (size cap, HTTPS-only redirects), and a JAR from the
 * registry must carry a valid `<url>.sig` unless the caller allows unsigned plugins.
 *
 */
class PluginInstallSecurityTest {
    private val pluginDir: Path =
        TestFiles.createTestTempDirectory("install-security").also {
            SystemFileSystem.createDirectories(it)
        }

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine) {
            engine { addHandler(handler) }
            install(HttpRedirect) { allowHttpsDowngrade = true }
        }

    private suspend fun install(
        httpClient: HttpClient,
        signatureKeys: List<ByteArray>? = null,
        downloadUrl: String = "https://registry.test/p.jar",
    ) = PluginInstallUpdateEngine.downloadAndUpdate(
        downloadUrl = downloadUrl,
        pluginDirPath = pluginDir.toString(),
        oldSourcePath = null,
        pluginId = "p",
        supportedFormats = setOf("jar"),
        verifyPlugin = { null },
        httpClient = httpClient,
        downloadCache = null,
        signatureKeys = signatureKeys,
    )

    private fun installed() = Path(pluginDir, "p.jar").exists()

    @Test
    fun `a download larger than the size cap installs nothing`() =
        runTest {
            // No Content-Length and one byte over the 50 MB cap: only the streaming cap stops it.
            val oversized = client { respond(ByteReadChannel(ByteArray(50 * 1024 * 1024 + 1)), HttpStatusCode.OK) }
            val result = install(oversized)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("exceeded maximum size"),
                result.toString()
            )
            assertFalse(installed())
        }

    @Test
    fun `a redirect to plain http is rejected by the artifact downloader`() =
        runTest {
            val downgrade = client { request ->
                if (request.url.protocol.name == "https") {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://evil.test/p.jar"))
                } else {
                    respond("evil")
                }
            }
            val result = install(downgrade)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("non-HTTPS"),
                result.toString()
            )
            assertFalse(installed())
        }

    @Test
    fun `an unsigned registry jar is rejected when signatures are required`() =
        runTest {
            val noSig = client { request ->
                if (request.url.encodedPath.endsWith(".sig")) respond("", HttpStatusCode.NotFound) else respond("jar")
            }
            val result = install(noSig, signatureKeys = emptyList())
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("--allow-unsigned"),
                result.toString()
            )
            assertFalse(installed())
            assertEquals(emptyList(), SystemFileSystem.list(pluginDir))
        }

    @Test
    fun `an unsigned registry jar installs when unsigned plugins are allowed`() =
        runTest {
            assertTrue(install(client { respond("jar") }, signatureKeys = null).isSuccess)
            assertTrue(installed())
        }

    @Test
    fun `the signature of a download URL with a query string is fetched at the file path plus sig`() =
        runTest {
            val requested = mutableListOf<String>()
            val recording = client { request ->
                requested += request.url.toString()
                respond("jar")
            }
            install(recording, signatureKeys = emptyList(), downloadUrl = "https://registry.test/p.jar?token=abc")

            assertEquals(listOf("https://registry.test/p.jar?token=abc", "https://registry.test/p.jar.sig?token=abc"), requested)
        }
}
