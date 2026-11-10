package io.karandash.platforms.github

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitHubPlatformServiceTest {
    @Test
    fun `module metadata is correct`() {
        val poster = GitHubPlatformService()
        assertEquals("github", poster.id)
        assertEquals("Github", poster.name)
        assertEquals("1.7.0", poster.version)
    }

    @Test
    fun `initialize without a token starts anonymously`() =
        runTest {
            val poster = GitHubPlatformService()
            assertTrue(poster.initialize(createContext(emptyMap())))
        }

    @Test
    fun `postComment throws when not initialized`() =
        runTest {
            val poster = GitHubPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.postComment("octocat/hello-world", "42", "test comment")
            }
        }

    @Test
    fun `updateComment throws when not initialized`() =
        runTest {
            val poster = GitHubPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.updateComment("octocat/hello-world", "42", "456", "updated comment")
            }
        }

    @Test
    fun `findExistingComment throws when not initialized`() =
        runTest {
            val poster = GitHubPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.findExistingComment("octocat/hello-world", "42", "<!-- marker -->")
            }
        }

    private fun createContext(config: Map<String, Any?>) =
        object : PluginContext {
            override val pluginConfig = mapToJsonElement(config)
            override val hostVersion = "1.0.0-test"
            override val workingDirectory = "/tmp/test"

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}
        }
}
