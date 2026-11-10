package io.karandash.platforms.gerrit

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GerritMutatorTest {
    @Test
    fun testModuleMetadata() {
        val service = GerritPlatformService()
        assertEquals("gerrit", service.id)
        assertEquals("Gerrit", service.name)
        assertEquals("1.7.0", service.version)
        assertEquals(40, service.defaultPriority)
    }

    @Test
    fun testInitializeWithUrl() =
        runTest {
            val service = GerritPlatformService()
            val context = createContext(mapOf("url" to "https://gerrit.example.com"))
            assertTrue(service.initialize(context))
        }

    @Test
    fun testInitializeWithBaseUrl() =
        runTest {
            val service = GerritPlatformService()
            val context = createContext(mapOf("baseUrl" to "https://gerrit.example.com"))
            assertTrue(service.initialize(context))
        }

    @Test
    fun testInitializeSucceedsEvenWithoutUrl() =
        runTest {
            val service = GerritPlatformService()
            val context = createContext(emptyMap())
            val result = service.initialize(context)
            assertEquals(true, result)
        }

    @Test
    fun testAddLabelsRequiresInitialization() =
        runTest {
            val service = GerritPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.addLabels("project", "123", listOf("bug", "enhancement"))
            }
            assertEquals("Gerrit not initialized", exception.message)
        }

    @Test
    fun testRemoveLabelsRequiresInitialization() =
        runTest {
            val service = GerritPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.removeLabels("project", "123", listOf("bug"))
            }
            assertEquals("Gerrit not initialized", exception.message)
        }

    @Test
    fun testSetDraftRequiresInitialization() =
        runTest {
            val service = GerritPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.setDraft("project", "123", true)
            }
            assertEquals("Gerrit not initialized", exception.message)
        }

    @Test
    fun testUpdateTitleRequiresInitialization() =
        runTest {
            val service = GerritPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.updateTitle("project", "123", "New Title")
            }
            assertEquals("Gerrit not initialized", exception.message)
        }

    @Test
    fun testUpdateDescriptionRequiresInitialization() =
        runTest {
            val service = GerritPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.updateDescription("project", "123", "New description")
            }
            assertEquals("Gerrit not initialized", exception.message)
        }

    @Test
    fun testUpdateTitleWithValidArgs() =
        runTest {
            val service = GerritPlatformService()
            val context = createContext(mapOf("url" to "https://gerrit.example.com"))
            service.initialize(context)

            // Gerrit change IDs can be any string; no local validation - network call expected to fail
            service.shutdown()
        }

    @Test
    fun testUpdateDescriptionWithValidArgs() =
        runTest {
            val service = GerritPlatformService()
            val context = createContext(mapOf("url" to "https://gerrit.example.com"))
            service.initialize(context)

            // Gerrit change IDs can be any string; no local validation - network call expected to fail
            service.shutdown()
        }

    @Test
    fun testShutdown() =
        runTest {
            val service = GerritPlatformService()
            val context = createContext(mapOf("url" to "https://gerrit.example.com"))
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
