package io.karandash.platforms.bitbucket

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BitbucketPlatformServiceTest {
    @Test
    fun `module metadata is correct`() {
        val poster = BitbucketPlatformService()
        assertEquals("bitbucket", poster.id)
        assertEquals("Bitbucket", poster.name)
        assertEquals("1.7.0", poster.version)
    }

    @Test
    fun `initialize without a token starts anonymously`() =
        runTest {
            val poster = BitbucketPlatformService()
            assertTrue(poster.initialize(createContext(emptyMap())))
        }

    @Test
    fun `postComment throws when not initialized`() =
        runTest {
            val poster = BitbucketPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.postComment("workspace/repo", "456", "test comment")
            }
        }

    @Test
    fun `updateComment throws when not initialized`() =
        runTest {
            val poster = BitbucketPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.updateComment("workspace/repo", "456", "789", "updated comment")
            }
        }

    @Test
    fun `findExistingComment throws when not initialized`() =
        runTest {
            val poster = BitbucketPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.findExistingComment("workspace/repo", "456", "<!-- karandash-validation-result -->")
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
