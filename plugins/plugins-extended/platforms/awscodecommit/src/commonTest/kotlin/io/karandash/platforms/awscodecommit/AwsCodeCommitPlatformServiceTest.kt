package io.karandash.platforms.awscodecommit

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AwsCodeCommitPlatformServiceTest {
    @Test
    fun `module metadata is correct`() {
        val poster = AwsCodeCommitPlatformService()
        assertEquals("awscodecommit", poster.id)
        assertEquals("AWS CodeCommit", poster.name)
        assertEquals("1.7.0", poster.version)
    }

    @Test
    fun `initialize succeeds with default AWS credentials`() =
        runTest {
            val poster = AwsCodeCommitPlatformService()
            assertTrue(poster.initialize(createContext(emptyMap())))
        }

    @Test
    fun `postComment throws when not initialized`() =
        runTest {
            val poster = AwsCodeCommitPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.postComment("my-repo", "123", "test comment")
            }
        }

    @Test
    fun `updateComment throws when not initialized`() =
        runTest {
            val poster = AwsCodeCommitPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.updateComment("my-repo", "123", "comment-789", "updated comment")
            }
        }

    @Test
    fun `findExistingComment throws when not initialized`() =
        runTest {
            val poster = AwsCodeCommitPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.findExistingComment("my-repo", "123", "<!-- karandash-validation-result -->")
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
