package io.karandash.platforms.github

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `url` and `baseUrl` are aliases: url > baseUrl > GITHUB_API_URL > default. */
class GitHubUrlAliasTest {
    private val env = mapOf("GITHUB_TOKEN" to "t", "GITHUB_API_URL" to "https://env.example/api/v3")

    private fun resolve(config: Map<String, String>): String = GitHubCredentials.resolve(mapToJsonElement(config), env::get).baseUrl

    @Test
    fun `baseUrl and url both set the API base and url wins`() {
        assertEquals("https://b.example/api/v3", resolve(mapOf("baseUrl" to "https://b.example/api/v3/")))
        assertEquals("https://u.example/api/v3", resolve(mapOf("url" to "https://u.example/api/v3")))
        assertEquals("https://u.example/api/v3", resolve(mapOf("url" to "https://u.example/api/v3", "baseUrl" to "https://b.example")))
        assertEquals("https://env.example/api/v3", resolve(emptyMap()))
    }

    @Test
    fun `conflicting url and baseUrl log a warning`() =
        runTest {
            val warnings = mutableListOf<String>()
            val config = mapOf("token" to "t", "url" to "https://u.example", "baseUrl" to "https://b.example")
            GitHubPlatformService().initialize(context(config, warnings))
            assertTrue(warnings.any { "'baseUrl'" in it && "https://u.example" in it }, warnings.toString())
        }

    @Test
    fun `baseUrl alone is an alias of url and warns about nothing`() =
        runTest {
            val warnings = mutableListOf<String>()
            GitHubPlatformService().initialize(context(mapOf("token" to "t", "baseUrl" to "https://b.example"), warnings))
            assertTrue(warnings.none { "baseUrl" in it }, warnings.toString())
        }

    @Test
    fun `url alone does not warn`() =
        runTest {
            val warnings = mutableListOf<String>()
            GitHubPlatformService().initialize(context(mapOf("token" to "t", "url" to "https://u.example"), warnings))
            assertTrue(warnings.none { "'baseUrl'" in it }, warnings.toString())
        }

    private fun context(
        config: Map<String, String>,
        warnings: MutableList<String>,
    ): PluginContext =
        object : PluginContext {
            override val pluginConfig = mapToJsonElement(config)
            override val hostVersion = "1.0.0-test"
            override val workingDirectory = "/tmp/test"

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {
                warnings += message
            }
        }
}
