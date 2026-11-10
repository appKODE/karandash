package io.karandash.platforms.github

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GithubMutatorTest {
    @Test
    fun testModuleMetadata() {
        val service = GitHubPlatformService()
        assertEquals("github", service.id)
        assertEquals("Github", service.name)
        assertEquals("1.7.0", service.version)
    }

    @Test
    fun testInitializeWithToken() =
        runTest {
            val service = GitHubPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            assertTrue(service.initialize(context))
            service.shutdown()
        }

    @Test
    fun testInitializeWithUrl() =
        runTest {
            val service = GitHubPlatformService()
            val context = createContext(mapOf("url" to "https://api.github.com", "token" to "test-token"))
            assertTrue(service.initialize(context))
            service.shutdown()
        }

    @Test
    fun testInitializeWithoutTokenStartsAnonymously() =
        runTest {
            val service = GitHubPlatformService()
            val context = createContext(emptyMap())
            val result = service.initialize(context)
            assertEquals(true, result)
        }

    @Test
    fun testAddLabelsRequiresInitialization() =
        runTest {
            val service = GitHubPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.addLabels("owner/repo", "123", listOf("bug", "enhancement"))
            }
            assertEquals("Github not initialized", exception.message)
        }

    @Test
    fun testRemoveLabelsRequiresInitialization() =
        runTest {
            val service = GitHubPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.removeLabels("owner/repo", "123", listOf("bug"))
            }
            assertEquals("Github not initialized", exception.message)
        }

    @Test
    fun testSetDraftRequiresInitialization() =
        runTest {
            val service = GitHubPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.setDraft("owner/repo", "123", true)
            }
            assertEquals("Github not initialized", exception.message)
        }

    @Test
    fun testAddLabelsWithInvalidProjectIdFails() =
        runTest {
            val service = GitHubPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.addLabels("invalid-project-id", "123", listOf("bug"))
            }
            assertTrue(exception.message!!.contains("Invalid project ID format"))
            service.shutdown()
        }

    @Test
    fun testRemoveLabelsWithInvalidProjectIdFails() =
        runTest {
            val service = GitHubPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.removeLabels("invalid-project-id", "123", listOf("bug"))
            }
            assertTrue(exception.message!!.contains("Invalid project ID format"))
            service.shutdown()
        }

    @Test
    fun testSetDraftWithInvalidProjectIdFails() =
        runTest {
            val service = GitHubPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.setDraft("invalid-project-id", "123", true)
            }
            assertTrue(exception.message!!.contains("Invalid project ID format"))
            service.shutdown()
        }

    @Test
    fun testAddLabelsWithInvalidPrNumberFails() =
        runTest {
            val service = GitHubPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.addLabels("owner/repo", "invalid-pr-number", listOf("bug"))
            }
            assertEquals("Invalid PR number: invalid-pr-number", exception.message)
            service.shutdown()
        }

    @Test
    fun testRemoveLabelsWithInvalidPrNumberFails() =
        runTest {
            val service = GitHubPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.removeLabels("owner/repo", "invalid-pr-number", listOf("bug"))
            }
            assertEquals("Invalid PR number: invalid-pr-number", exception.message)
            service.shutdown()
        }

    @Test
    fun testSetDraftWithInvalidPrNumberFails() =
        runTest {
            val service = GitHubPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.setDraft("owner/repo", "invalid-pr-number", true)
            }
            assertEquals("Invalid PR number: invalid-pr-number", exception.message)
            service.shutdown()
        }

    @Test
    fun testUpdateTitleRequiresInitialization() =
        runTest {
            val service = GitHubPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.updateTitle("owner/repo", "123", "New Title")
            }
            assertEquals("Github not initialized", exception.message)
        }

    @Test
    fun testUpdateDescriptionRequiresInitialization() =
        runTest {
            val service = GitHubPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.updateDescription("owner/repo", "123", "New description")
            }
            assertEquals("Github not initialized", exception.message)
        }

    @Test
    fun testUpdateTitleWithInvalidMrIdFails() =
        runTest {
            val service = GitHubPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.updateTitle("owner/repo", "invalid-mr-id", "New Title")
            }
            assertEquals("Invalid PR number: invalid-mr-id", exception.message)
            service.shutdown()
        }

    @Test
    fun testUpdateDescriptionWithInvalidMrIdFails() =
        runTest {
            val service = GitHubPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.updateDescription("owner/repo", "invalid-mr-id", "New description")
            }
            assertEquals("Invalid PR number: invalid-mr-id", exception.message)
            service.shutdown()
        }

    @Test
    fun testShutdown() =
        runTest {
            val service = GitHubPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)
            service.shutdown() // Should not throw
        }

    private fun createContext(config: Map<String, Any?>): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(config)
            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}
        }
}
