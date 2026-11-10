package io.karandash.cli.plugin

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.errors.ExitCodes
import io.karandash.registry.REGISTRY_URL
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginSearchCommandTest {
    private val tempDir = TestFileHelpers.createTempDir("plugin-search-command")

    private val engine = MockEngine {
        respond(
            """
            {"version":"1","lastUpdated":"now","plugins":[{
              "id":"forge-plugin","name":"Forge","description":"Forge platform service","author":"acme","version":"1.0.0",
              "type":"platform-service","tags":[],"url":"https://example.com/forge.jar","checksum":"sha256:00",
              "minHostVersion":"0.1.0","repository":"https://example.com","documentation":"https://example.com"
            }]}
            """.trimIndent(),
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(tempDir)

    @Test
    fun typeFilterAcceptsPlatformServiceFlavours() {
        listOf("provider", "mutator", "comment-poster", "platform-service").forEach { type ->
            val command = PluginSearchCommand(registryClient = { it.registryClient(cacheDir = tempDir, engine = engine) })

            val result = command.test(listOf("--type", type, "--refresh"))

            assertEquals(0, result.statusCode, result.output)
            assertTrue("forge-plugin" in result.output, "--type $type:\n${result.output}")
        }
    }

    @Test
    fun fetchFailureHintNamesManifestRegistryUrl() {
        val manifest = Path(tempDir, "manifest.yaml")
        TestFileHelpers.writeFile(manifest, "plugins:\n  registry-url: https://registry.example.com/index.json\n")
        val failing = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val command = PluginSearchCommand(
            registryClient = { it.registryClient(cacheDir = tempDir, engine = failing) },
            manifestPath = manifest,
        )

        val result = command.test(listOf("--refresh"))

        assertTrue("unreachable: https://registry.example.com/index.json" in result.output, result.output)
        assertFalse(REGISTRY_URL in result.output, result.output)
    }

    @Test
    fun fetchFailureFallsBackToStaleCacheWithWarning() {
        val warm = PluginSearchCommand(registryClient = { it.registryClient(cacheDir = tempDir, engine = engine) })
        assertEquals(0, warm.test(listOf("--refresh")).statusCode)
        val failing = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val command = PluginSearchCommand(registryClient = { it.registryClient(cacheDir = tempDir, engine = failing) })

        val result = command.test(listOf("--refresh"))

        assertEquals(0, result.statusCode, result.output)
        assertTrue("forge-plugin" in result.output, result.output)
        assertTrue(Regex("using cached registry from \\S+ ago").containsMatchIn(result.output), result.output)
        assertFalse("Possible causes" in result.output, result.output)
    }

    @Test
    fun `search on an empty registry says the registry lists no plugins yet`() {
        val empty = MockEngine {
            respond(
                """{"version":"1","lastUpdated":"now","plugins":[]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val command = PluginSearchCommand(registryClient = { it.registryClient(cacheDir = tempDir, engine = empty) })

        val result = command.test(listOf("jira", "--refresh"))

        assertEquals(0, result.statusCode, result.output)
        assertTrue("lists no plugins yet" in result.output, result.output)
        assertTrue("karandash plugin install <file>" in result.output, result.output)
        assertFalse("No plugins found matching" in result.output, result.output)
    }

    @Test
    fun `a private registry-url from the manifest is rejected before any request`() {
        val manifest = Path(tempDir, "manifest.yaml")
        TestFileHelpers.writeFile(manifest, "plugins:\n  registry-url: https://169.254.169.254/latest/index.json\n")
        val requests = mutableListOf<String>()
        val recording = MockEngine { request ->
            requests += request.url.toString()
            respond("{}")
        }
        val command = PluginSearchCommand(
            registryClient = { it.registryClient(cacheDir = tempDir, engine = recording) },
            manifestPath = manifest,
        )

        val result = command.test(listOf("--refresh"))

        assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode, result.output)
        assertTrue("SSRF" in result.output, result.output)
        assertEquals(emptyList(), requests)
    }
}
