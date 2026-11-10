package io.karandash.registry

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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The default registry is the `tools/registry/registry.json` this repository serves raw from GitHub, so `plugin
 * search` works out of the box. The mock answers only the repo's raw URL, the way the real host 404s anything else.
 *
 */
class DefaultRegistryUrlTest {
    private val cacheDir = TestFiles.createTestTempDirectory("default-registry")

    @AfterTest
    fun cleanup() = TestFiles.deleteRecursively(cacheDir)

    @Test
    fun `the default registry URL serves the in-repo tools registry`() =
        runTest {
            val root = checkNotNull(TestFiles.findRepoRoot()) { "repo root not found" }
            val body = TestFiles.readText(Path(root, "tools", "registry", "registry.json"))
            val engine =
                MockEngine { request ->
                    if (request.url.toString() == RAW_URL) respond(body) else respondError(HttpStatusCode.NotFound)
                }
            val result = RegistryClient(cacheDir = cacheDir, httpClient = HttpClient(engine)).fetchRegistry()
            assertTrue(result.isSuccess, "default registry $REGISTRY_URL: ${result.exceptionOrNull()?.message}")
            assertTrue(SystemFileSystem.exists(cacheDir))
        }

    private companion object {
        const val RAW_URL = "https://raw.githubusercontent.com/appKODE/karandash/main/tools/registry/registry.json"
    }
}
