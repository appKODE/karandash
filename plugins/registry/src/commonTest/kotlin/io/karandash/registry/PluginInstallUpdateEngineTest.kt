package io.karandash.registry

import io.karandash.platform.readText
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginInstallUpdateEngineTest {
    private val pluginDir: Path =
        TestFiles.createTestTempDirectory("install-engine").also {
            SystemFileSystem.createDirectories(it)
        }

    private suspend fun update(
        url: String = "https://registry.test/p.jar",
        oldSourcePath: String? = null,
        status: HttpStatusCode = HttpStatusCode.OK,
        verify: suspend (String) -> String? = { null },
    ) = PluginInstallUpdateEngine.downloadAndUpdate(
        downloadUrl = url,
        pluginDirPath = pluginDir.toString(),
        oldSourcePath = oldSourcePath,
        pluginId = "p",
        supportedFormats = setOf("JAR", "wasm"),
        verifyPlugin = verify,
        httpClient = HttpClient(MockEngine { if (status == HttpStatusCode.OK) respond("new") else respondError(status) }),
        downloadCache = null,
    )

    private fun message(result: Result<*>): String = result.exceptionOrNull()?.message.orEmpty()

    @Test
    fun `plain http download url is rejected`() =
        runTest {
            assertTrue(message(update(url = "http://registry.test/p.jar")).contains("Only HTTPS"))
        }

    @Test
    fun `unsupported extension is rejected with the supported list`() =
        runTest {
            val msg = message(update(url = "https://registry.test/p.dll?x=1"))
            assertTrue(msg.contains("Cannot determine compatible plugin extension"))
            assertTrue(msg.contains(".jar, .wasm"))
        }

    @Test
    fun `http failure leaves no temp file behind`() =
        runTest {
            assertTrue(message(update(status = HttpStatusCode.BadGateway)).contains("HTTP 502"))
            assertEquals(emptyList(), SystemFileSystem.list(pluginDir))
        }

    @Test
    fun `verification failure installs nothing`() =
        runTest {
            assertTrue(message(update(verify = { "not a plugin" })).contains("failed verification: not a plugin"))
            assertEquals(emptyList(), SystemFileSystem.list(pluginDir))
        }

    @Test
    fun `fresh install refuses to overwrite an existing file`() =
        runTest {
            Path(pluginDir, "p.jar").writeText("old")
            assertTrue(message(update()).contains("already exists"))
            assertEquals("old", Path(pluginDir, "p.jar").readText())
        }

    @Test
    fun `update backs up the old file and keeps its extension`() =
        runTest {
            val old = Path(pluginDir, "custom.wasm")
            old.writeText("old")
            val (backup, installed) = update(url = "https://registry.test/latest", oldSourcePath = old.toString()).getOrThrow()
            assertEquals("custom.wasm.bak", backup)
            assertEquals("custom.wasm", installed)
            assertEquals("new", old.readText())
            assertEquals("old", Path(pluginDir, "custom.wasm.bak").readText())
        }

    @Test
    fun `update whose old file is gone installs without a backup`() =
        runTest {
            val old = Path(pluginDir, "gone.jar")
            val (backup, installed) = update(oldSourcePath = old.toString()).getOrThrow()
            assertNull(backup)
            assertEquals("gone.jar", installed)
        }

    @Test
    fun `extension is inferred from the url path only`() {
        assertEquals("jar", PluginInstallUpdateEngine.inferExtensionFromUrl("https://x/p.JAR?sig=a.b#frag.c"))
        assertNull(PluginInstallUpdateEngine.inferExtensionFromUrl("https://x/p."))
    }
}
