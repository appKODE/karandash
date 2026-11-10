package io.karandash.platforms.gerrit

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GerritPlatformServiceTest {
    @Test
    fun `module metadata is correct`() {
        val poster = GerritPlatformService()
        assertEquals("gerrit", poster.id)
        assertEquals("Gerrit", poster.name)
        assertEquals("1.7.0", poster.version)
        assertEquals(40, poster.defaultPriority)
    }

    @Test
    fun `initialize succeeds even when credentials missing`() =
        runTest {
            val poster = GerritPlatformService()
            assertTrue(poster.initialize(createContext(emptyMap())))
        }

    @Test
    fun `initialize returns true with url configured`() =
        runTest {
            val poster = GerritPlatformService()
            assertTrue(poster.initialize(createContext(mapOf("url" to "https://gerrit.example.com"))))
        }

    @Test
    fun `postComment throws when not initialized`() =
        runTest {
            val poster = GerritPlatformService()
            val exception = assertFailsWith<IllegalStateException> {
                poster.postComment("project", "123", "message")
            }
            assertTrue(exception.message?.contains("not initialized") == true)
        }

    @Test
    fun `updateComment throws when not initialized`() =
        runTest {
            val poster = GerritPlatformService()
            val exception = assertFailsWith<IllegalStateException> {
                poster.updateComment("project", "123", "456", "message")
            }
            assertTrue(exception.message?.contains("not initialized") == true)
        }

    @Test
    fun `findExistingComment throws when not initialized`() =
        runTest {
            val poster = GerritPlatformService()
            val exception = assertFailsWith<IllegalStateException> {
                poster.findExistingComment("project", "123", "marker")
            }
            assertTrue(exception.message?.contains("not initialized") == true)
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
