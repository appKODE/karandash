package io.karandash.platforms.bitbucket

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BitbucketMutatorTest {
    @Test
    fun testModuleMetadata() {
        val service = BitbucketPlatformService()
        assertEquals("bitbucket", service.id)
        assertEquals("Bitbucket", service.name)
        assertEquals("1.7.0", service.version)
    }

    @Test
    fun testInitializeWithToken() =
        runTest {
            val service = BitbucketPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            assertTrue(service.initialize(context))
        }

    @Test
    fun testInitializeWithoutTokenStartsAnonymously() =
        runTest {
            val service = BitbucketPlatformService()
            val context = createContext(emptyMap())
            val result = service.initialize(context)
            assertEquals(true, result)
        }

    @Test
    fun testAddLabelsThrowsUnsupportedOperation() =
        runTest {
            val service = BitbucketPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            assertFailsWith<UnsupportedOperationException> {
                service.addLabels("workspace/repo", "123", listOf("bug", "enhancement"))
            }
        }

    @Test
    fun testRemoveLabelsThrowsUnsupportedOperation() =
        runTest {
            val service = BitbucketPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            assertFailsWith<UnsupportedOperationException> {
                service.removeLabels("workspace/repo", "123", listOf("bug"))
            }
        }

    @Test
    fun testSetDraftRequiresInitialization() =
        runTest {
            val service = BitbucketPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.setDraft("workspace/repo", "123", true)
            }
            assertEquals("Bitbucket not initialized", exception.message)
        }

    @Test
    fun testParseProjectIdInvalidFormat() =
        runTest {
            val service = BitbucketPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.setDraft("invalid-project-id", "123", true)
            }
            assertTrue(exception.message!!.contains("Invalid project ID format"))
        }

    @Test
    fun testUpdateTitleRequiresInitialization() =
        runTest {
            val service = BitbucketPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.updateTitle("workspace/repo", "123", "New Title")
            }
            assertEquals("Bitbucket not initialized", exception.message)
        }

    @Test
    fun testUpdateDescriptionRequiresInitialization() =
        runTest {
            val service = BitbucketPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.updateDescription("workspace/repo", "123", "New description")
            }
            assertEquals("Bitbucket not initialized", exception.message)
        }

    @Test
    fun testUpdateTitleWithInvalidMrIdFails() =
        runTest {
            val service = BitbucketPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.updateTitle("workspace/repo", "invalid-mr-id", "New Title")
            }
            assertEquals("Invalid PR ID: invalid-mr-id", exception.message)
            service.shutdown()
        }

    @Test
    fun testUpdateDescriptionWithInvalidMrIdFails() =
        runTest {
            val service = BitbucketPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.updateDescription("workspace/repo", "invalid-mr-id", "New description")
            }
            assertEquals("Invalid PR ID: invalid-mr-id", exception.message)
            service.shutdown()
        }

    @Test
    fun testShutdown() =
        runTest {
            val service = BitbucketPlatformService()
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
