package io.karandash.download

import io.karandash.api.exception.PluginSecurityException
import io.karandash.platform.createDirectories
import io.karandash.platform.deleteIfExists
import io.karandash.platform.isDirectory
import io.karandash.platform.listFiles
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
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArtifactDownloaderSecurityTest {
    private val dir = TestFiles
        .createTestTempDirectory("artifact-security-test")
        .also { createDirectories(it) }
    private val target = Path(dir, "out", "plugin.jar")
    private val url = "https://example.com/plugin.jar"

    @AfterTest
    fun cleanup() = deleteTree(dir)

    private fun deleteTree(path: Path) {
        if (path.isDirectory()) path.listFiles().orEmpty().forEach { deleteTree(it) }
        deleteIfExists(path)
    }

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine) {
            engine { addHandler(handler) }
            install(HttpRedirect) { allowHttpsDowngrade = true }
        }

    private fun MockRequestHandleScope.ok(body: String = "jar") = respond(ByteReadChannel(body.encodeToByteArray()), HttpStatusCode.OK)

    private suspend fun failure(
        downloader: ArtifactDownloader,
        fetchUrl: String = url,
    ): Throwable {
        val error = downloader.fetch(fetchUrl, target).exceptionOrNull()
        assertTrue(Path(dir, "out").let { !it.isDirectory() || it.listFiles().orEmpty().isEmpty() }, "no file may be left behind")
        return checkNotNull(error) { "fetch must fail" }
    }

    @Test
    fun `plain http urls are rejected before any request`() =
        runTest {
            val requests = mutableListOf<String>()
            val error = failure(
                ArtifactDownloader(
                    client {
                        requests += it.url.toString()
                        ok()
                    }
                ),
                "http://example.com/plugin.jar"
            )

            assertIs<PluginSecurityException>(error)
            assertTrue(error.message.orEmpty().contains("Only HTTPS"))
            assertEquals(emptyList(), requests)
        }

    @Test
    fun `redirect to plain http is rejected`() =
        runTest {
            val downloader = ArtifactDownloader(
                client { request ->
                    if (request.url.protocol.name == "https") {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://evil.com/plugin.jar"))
                    } else {
                        ok()
                    }
                },
            )

            val error = failure(downloader)

            assertIs<PluginSecurityException>(error)
            assertTrue(error.message.orEmpty().contains("http://evil.com"))
        }
}
