package io.karandash.platforms.gitea

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GiteaPlatformServiceTest {
    @Test
    fun `module metadata is correct`() {
        val poster = GiteaPlatformService()
        assertEquals("gitea", poster.id)
        assertEquals("Gitea", poster.name)
        assertEquals("1.7.0", poster.version)
    }

    @Test
    fun `initialize succeeds even when credentials missing`() =
        runTest {
            val poster = GiteaPlatformService()
            assertTrue(poster.initialize(createContext(emptyMap())))
        }

    @Test
    fun `postComment throws when not initialized`() =
        runTest {
            val poster = GiteaPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.postComment("owner/myrepo", "42", "test comment")
            }
        }

    @Test
    fun `updateComment throws when not initialized`() =
        runTest {
            val poster = GiteaPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.updateComment("owner/myrepo", "42", "456", "updated comment")
            }
        }

    @Test
    fun `findExistingComment throws when not initialized`() =
        runTest {
            val poster = GiteaPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.findExistingComment("owner/myrepo", "42", "<!-- marker -->")
            }
        }

    private fun createContext(config: Map<String, Any?>): PluginContext =
        object : PluginContext {
            override val pluginConfig = mapToJsonElement(config)
            override val hostVersion = "1.0.0-test"
            override val workingDirectory = "/tmp/test"

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}
        }
}
