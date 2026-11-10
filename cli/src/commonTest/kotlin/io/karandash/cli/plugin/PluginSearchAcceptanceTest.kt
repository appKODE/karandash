package io.karandash.cli.plugin

import io.karandash.registry.PluginRegistry
import io.karandash.registry.PluginRegistryEntry
import io.karandash.registry.RegistryClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acceptance tests for Feature 8: Plugin Registry Search
 *
 * Tests verify:
 * - AC-28: karandash plugin search jira returns matching plugins
 * - AC-29: Search is case-insensitive
 * - AC-30: Shows install command for each result
 */
class PluginSearchAcceptanceTest {
    @Test
    fun `plugin search jira returns matching plugins`() {
        val registry = createTestRegistry()
        val client = RegistryClient()

        val results = client.filterPlugins(registry, query = "jira")

        assertTrue(results.isNotEmpty(), "Should find plugins matching 'jira'")
        assertTrue(results.any { it.id == "jira-plugin" }, "Should find jira-plugin")
    }

    @Test
    fun `search is case-insensitive`() {
        val registry = createTestRegistry()
        val client = RegistryClient()

        val lowerResults = client.filterPlugins(registry, query = "jira")
        val upperResults = client.filterPlugins(registry, query = "JIRA")
        val mixedResults = client.filterPlugins(registry, query = "JiRa")

        assertEquals(lowerResults.size, upperResults.size, "Case should not affect results")
        assertEquals(lowerResults.size, mixedResults.size, "Case should not affect results")
        assertEquals(lowerResults.map { it.id }, upperResults.map { it.id }, "Should find same plugins")
    }

    @Test
    fun `filterPlugins returns plugin id for install command`() {
        val registry = createTestRegistry()
        val client = RegistryClient()

        val results = client.filterPlugins(registry, query = "jira")

        assertTrue(results.isNotEmpty(), "Should have results to verify")
        results.forEach { plugin ->
            assertTrue(plugin.id.isNotBlank(), "Plugin should have an ID for install command")
            // The command class will use: karandash plugin install --registry ${plugin.id}
        }
    }

    @Test
    fun `search matches name description and tags`() {
        val registry = createTestRegistry()
        val client = RegistryClient()

        // Match by name
        val nameResults = client.filterPlugins(registry, query = "JIRA")
        assertTrue(nameResults.any { it.name.contains("JIRA", ignoreCase = true) })

        // Match by description
        val descResults = client.filterPlugins(registry, query = "notifications")
        assertTrue(descResults.any { it.description.contains("notifications", ignoreCase = true) })

        // Match by tags
        val tagResults = client.filterPlugins(registry, query = "integration")
        assertTrue(tagResults.any { it.tags.contains("integration") })
    }

    @Test
    fun `search with filters returns correct subset`() {
        val registry = createTestRegistry()
        val client = RegistryClient()

        // Filter by type
        val rulePlugins = client.filterPlugins(registry, type = "rule")
        assertTrue(rulePlugins.all { it.type == "rule" })

        val reporterPlugins = client.filterPlugins(registry, type = "reporter")
        assertTrue(reporterPlugins.all { it.type == "reporter" })

        // Filter by tag
        val ticketPlugins = client.filterPlugins(registry, tag = "tickets")
        assertTrue(ticketPlugins.all { it.tags.contains("tickets") })
    }

    private fun createTestRegistry(): PluginRegistry =
        PluginRegistry(
            version = "1",
            lastUpdated = "2026-02-11T00:00:00Z",
            plugins = listOf(
                PluginRegistryEntry(
                    id = "jira-plugin",
                    name = "JIRA Integration",
                    description = "Validates JIRA ticket references in merge requests",
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
                    description = "Sends notifications to Slack channels",
                    author = "appKODE",
                    version = "0.1.0",
                    type = "reporter",
                    tags = listOf("slack", "notifications", "integration"),
                    url = "https://example.com/slack-plugin.jar",
                    checksum = "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                    minHostVersion = "0.1.0",
                    repository = "https://github.com/appKODE/karandash",
                    documentation = "https://karandash.io/plugins/slack/"
                ),
                PluginRegistryEntry(
                    id = "github-plugin",
                    name = "GitHub Integration",
                    description = "Validates GitHub issue references",
                    author = "appKODE",
                    version = "0.1.0",
                    type = "rule",
                    tags = listOf("github", "issues", "integration"),
                    url = "https://example.com/github-plugin.jar",
                    checksum = "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                    minHostVersion = "0.1.0",
                    repository = "https://github.com/appKODE/karandash",
                    documentation = "https://karandash.io/plugins/github/"
                )
            )
        )
}
