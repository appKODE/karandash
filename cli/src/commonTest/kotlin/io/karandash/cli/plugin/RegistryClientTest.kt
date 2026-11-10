package io.karandash.cli.plugin

import io.karandash.api.exception.RegistrySecurityException
import io.karandash.cli.platform.exists
import io.karandash.cli.platform.listDirectory
import io.karandash.cli.platform.writeText
import io.karandash.registry.PluginRegistry
import io.karandash.registry.PluginRegistryEntry
import io.karandash.registry.RegistryClient
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistryClientTest {
    private lateinit var tempDir: Path
    private var tempDirCounter = 0

    @BeforeTest
    fun setup() {
        val base = TestFiles.createTestTempDirectory("registry-client")
        tempDir = Path(base, "registry-test-${tempDirCounter++}-${hashCode()}")
        SystemFileSystem.createDirectories(tempDir)
    }

    @AfterTest
    fun cleanup() {
        // Clean up files in temp dir
        try {
            val files = tempDir.listDirectory()
            for (file in files) {
                try {
                    SystemFileSystem.delete(file)
                } catch (_: Exception) {
                    // best-effort temp cleanup
                }
            }
            SystemFileSystem.delete(tempDir)
        } catch (_: Exception) {
            // best-effort temp cleanup
        }
    }

    @Test
    fun filterPlugins_by_query_matches_name() {
        val registry = createTestRegistry()
        val client = RegistryClient(tempDir)

        val results = client.filterPlugins(registry, query = "JIRA")

        assertEquals(1, results.size)
        assertEquals("jira-plugin", results[0].id)
    }

    @Test
    fun filterPlugins_by_query_matches_description() {
        val registry = createTestRegistry()
        val client = RegistryClient(tempDir)

        val results = client.filterPlugins(registry, query = "notifications")

        assertEquals(1, results.size)
        assertEquals("slack-plugin", results[0].id)
    }

    @Test
    fun filterPlugins_by_query_matches_tags() {
        val registry = createTestRegistry()
        val client = RegistryClient(tempDir)

        val results = client.filterPlugins(registry, query = "integration")

        assertEquals(2, results.size)
        assertTrue(results.any { it.id == "jira-plugin" })
        assertTrue(results.any { it.id == "slack-plugin" })
    }

    @Test
    fun filterPlugins_by_type() {
        val registry = createTestRegistry()
        val client = RegistryClient(tempDir)

        val results = client.filterPlugins(registry, type = "reporter")

        assertEquals(1, results.size)
        assertEquals("slack-plugin", results[0].id)
    }

    @Test
    fun filterPlugins_by_platform_service_flavour() {
        val base = createTestRegistry()
        val service = base.plugins[1].copy(id = "forge-plugin", type = "PLATFORM_SERVICE")
        val registry = base.copy(plugins = base.plugins + service)
        val client = RegistryClient(tempDir)

        listOf("platform-service", "provider", "mutator", "comment-poster", "comment_poster").forEach { type ->
            assertEquals(listOf("forge-plugin"), client.filterPlugins(registry, type = type).map { it.id }, type)
        }
    }

    @Test
    fun filterPlugins_by_tag() {
        val registry = createTestRegistry()
        val client = RegistryClient(tempDir)

        val results = client.filterPlugins(registry, tag = "tickets")

        assertEquals(1, results.size)
        assertEquals("jira-plugin", results[0].id)
    }

    @Test
    fun filterPlugins_with_multiple_filters() {
        val registry = createTestRegistry()
        val client = RegistryClient(tempDir)

        val results = client.filterPlugins(
            registry,
            query = "integration",
            type = "rule"
        )

        assertEquals(1, results.size)
        assertEquals("jira-plugin", results[0].id)
    }

    @Test
    fun filterPlugins_with_no_matches_returns_empty_list() {
        val registry = createTestRegistry()
        val client = RegistryClient(tempDir)

        val results = client.filterPlugins(registry, query = "nonexistent")

        assertTrue(results.isEmpty())
    }

    @Test
    fun filterPlugins_with_no_filters_returns_all_plugins() {
        val registry = createTestRegistry()
        val client = RegistryClient(tempDir)

        val results = client.filterPlugins(registry)

        assertEquals(2, results.size)
    }

    @Test
    fun cache_file_is_created_after_successful_fetch() {
        val cacheFile = Path(tempDir, "registry.json")
        SystemFileSystem.createDirectories(tempDir)
        cacheFile.writeText(
            """{"version":"1","lastUpdated":"2026-02-10T00:00:00Z","plugins":[]}"""
        )

        assertTrue(cacheFile.exists())
    }

    @Test
    fun cache_is_considered_invalid_if_file_does_not_exist() {
        val cacheFile = Path(tempDir, "registry.json")

        assertFalse(cacheFile.exists())
    }

    @Test
    fun fetchRegistry_rejects_HTTP_URL() =
        runTest {
            val client = RegistryClient(
                cacheDir = tempDir,
                registryUrl = "http://insecure.example.com/registry.json"
            )

            val result = client.fetchRegistry()

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is RegistrySecurityException)
        }

    private fun createTestRegistry(): PluginRegistry =
        PluginRegistry(
            version = "1",
            lastUpdated = "2026-02-10T00:00:00Z",
            plugins = listOf(
                PluginRegistryEntry(
                    id = "jira-plugin",
                    name = "JIRA Integration",
                    description = "Validates JIRA ticket references",
                    author = "appKODE",
                    version = "0.1.0",
                    type = "rule",
                    tags = listOf("jira", "tickets", "integration"),
                    url = "https://example.com/jira-plugin.jar",
                    checksum = "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                    minHostVersion = "0.1.0",
                    repository = "https://github.com/appKODE/karandash",
                    documentation = "https://karandash.io/plugins/jira/"
                ),
                PluginRegistryEntry(
                    id = "slack-plugin",
                    name = "Slack Notifications",
                    description = "Sends notifications to Slack",
                    author = "appKODE",
                    version = "0.1.0",
                    type = "reporter",
                    tags = listOf("slack", "notifications", "integration"),
                    url = "https://example.com/slack-plugin.jar",
                    checksum = "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                    minHostVersion = "0.1.0",
                    repository = "https://github.com/appKODE/karandash",
                    documentation = "https://karandash.io/plugins/slack/"
                )
            )
        )
}
