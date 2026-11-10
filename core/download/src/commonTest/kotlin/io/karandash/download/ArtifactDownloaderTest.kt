package io.karandash.download

import io.karandash.platform.createDirectories
import io.karandash.platform.deleteIfExists
import io.karandash.platform.exists
import io.karandash.platform.listFiles
import io.karandash.platform.readText
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtifactDownloaderTest {
    private val dir = TestFiles
        .createTestTempDirectory("artifact-downloader-test")
        .also { createDirectories(it) }
    private val requested = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        dir.listFiles().orEmpty().forEach { deleteIfExists(it) }
        deleteIfExists(dir)
    }

    private fun client(
        body: String = "new-content",
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                requested += request.url.toString()
                respond(ByteReadChannel(body.encodeToByteArray()), status)
            }
        }
    }

    @Test
    fun `existing target is overwritten and no temp file is left behind`() =
        runTest {
            val target = Path(dir, "plugin.jar").also { it.writeText("old-content") }

            val result = ArtifactDownloader(client()).fetch("https://example.com/plugin.jar", target)

            assertEquals(target, result.getOrThrow())
            assertEquals("new-content", target.readText())
            assertEquals(listOf("plugin.jar"), dir.listFiles().orEmpty().map { it.name })
        }

    @Test
    fun `failed download keeps the existing target untouched`() =
        runTest {
            val target = Path(dir, "plugin.jar").also { it.writeText("old-content") }

            val result = ArtifactDownloader(client(status = HttpStatusCode.NotFound))
                .fetch("https://example.com/plugin.jar", target)

            assertTrue(result.isFailure)
            assertEquals("old-content", target.readText())
            assertEquals(listOf("plugin.jar"), dir.listFiles().orEmpty().map { it.name })
        }

    @Test
    fun `malformed url is a failure result not an exception`() =
        runTest {
            val result = ArtifactDownloader(client()).fetch("https://exa mple.com:notaport/x.jar", Path(dir, "x.jar"))

            assertTrue(result.isFailure)
            assertTrue(requested.isEmpty(), "no request may be sent for a malformed url")
        }

    @Test
    fun `only the artifact is fetched and no signature file is written`() =
        runTest {
            val target = Path(dir, "plugin.jar")

            ArtifactDownloader(client()).fetch("https://example.com/plugin.jar", target).getOrThrow()

            assertEquals(listOf("https://example.com/plugin.jar"), requested)
            assertTrue(!Path("$target.sig").exists(), "signatures are verified by PluginInstallUpdateEngine, not the downloader")
        }
}
