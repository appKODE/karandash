package io.karandash.platforms.gitlab

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitLabPlatformServiceTest {
    @Test
    fun `module metadata is correct`() {
        val poster = GitLabPlatformService()
        assertEquals("gitlab", poster.id)
        assertEquals("GitLab", poster.name)
        assertEquals("1.7.0", poster.version)
    }

    @Test
    fun `initialize without a token starts anonymously`() =
        runTest {
            val poster = GitLabPlatformService()
            assertTrue(poster.initialize(createContext(emptyMap())))
        }

    @Test
    fun `postComment throws when not initialized`() =
        runTest {
            val poster = GitLabPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.postComment("group/project", "456", "test comment")
            }
        }

    @Test
    fun `updateComment throws when not initialized`() =
        runTest {
            val poster = GitLabPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.updateComment("group/project", "456", "789", "updated comment")
            }
        }

    @Test
    fun `findExistingComment throws when not initialized`() =
        runTest {
            val poster = GitLabPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.findExistingComment("group/project", "456", "<!-- karandash-validation-result -->")
            }
        }

    @Test
    fun `deleteMarkedInlineComments throws when not initialized`() =
        runTest {
            val poster = GitLabPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.deleteMarkedInlineComments("group/project", "456", "<!-- karandash-inline -->")
            }
        }

    @Test
    fun `default deleteInlineStrategy is discussion`() =
        runTest {
            val poster = GitLabPlatformService()
            poster.initialize(createContext(emptyMap()))
            assertEquals(DeleteInlineStrategy.DISCUSSION, poster.deleteInlineStrategy)
        }

    @Test
    fun `deleteInlineStrategy note is stored after initialize`() =
        runTest {
            val poster = GitLabPlatformService()
            poster.initialize(createContext(mapOf("deleteInlineStrategy" to "note")))
            assertEquals(DeleteInlineStrategy.NOTE, poster.deleteInlineStrategy)
        }

    @Test
    fun `an unknown deleteInlineStrategy fails initialize`() =
        runTest {
            val poster = GitLabPlatformService()
            assertFalse(poster.initialize(createContext(mapOf("deleteInlineStrategy" to "notes"))))
        }

    @Test
    fun `resolveMarkedInlineComments throws when not initialized`() =
        runTest {
            val poster = GitLabPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.resolveMarkedInlineComments("group/project", "456", "<!-- karandash-inline -->")
            }
        }

    @Test
    fun `findAllMatchingComments throws when not initialized`() =
        runTest {
            val poster = GitLabPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.findAllMatchingComments("group/project", "456", "<!-- karandash-validation-result -->")
            }
        }

    @Test
    fun `deleteComment throws when not initialized`() =
        runTest {
            val poster = GitLabPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.deleteComment("group/project", "456", "789")
            }
        }

    @Test
    fun `deleteInlineStrategy discussion is stored after initialize`() =
        runTest {
            val poster = GitLabPlatformService()
            poster.initialize(createContext(mapOf("deleteInlineStrategy" to "discussion")))
            assertEquals(DeleteInlineStrategy.DISCUSSION, poster.deleteInlineStrategy)
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
