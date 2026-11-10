package io.karandash.platforms.azuredevops

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AzureDevOpsPlatformServiceTest {
    @Test
    fun `module metadata is correct`() {
        val poster = AzureDevOpsPlatformService()
        assertEquals("azure-devops", poster.id)
        assertEquals("Azure Devops", poster.name)
        assertEquals("1.7.0", poster.version)
        assertEquals(50, poster.defaultPriority)
    }

    @Test
    fun `initialize succeeds even without token`() =
        runTest {
            val poster = AzureDevOpsPlatformService()
            assertTrue(poster.initialize(createContext(emptyMap())))
        }

    @Test
    fun `postComment throws when not initialized`() =
        runTest {
            val poster = AzureDevOpsPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.postComment("myorg/myproject/repo123", "42", "test comment")
            }
        }

    @Test
    fun `updateComment throws when not initialized`() =
        runTest {
            val poster = AzureDevOpsPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.updateComment("myorg/myproject/repo123", "42", "100:200", "updated comment")
            }
        }

    @Test
    fun `findExistingComment throws when not initialized`() =
        runTest {
            val poster = AzureDevOpsPlatformService()
            assertFailsWith<IllegalStateException> {
                poster.findExistingComment("myorg/myproject/repo123", "42", "<!-- marker -->")
            }
        }

    @Test
    fun `fetchFileBlame logs a warning when blame fails`() =
        runTest {
            val warnings = mutableListOf<String>()
            val service = AzureDevOpsPlatformService()
            val logger = object : Logger {
                override fun debug(message: String) = Unit

                override fun info(message: String) = Unit

                override fun warn(message: String) {
                    warnings += message
                }

                override fun error(message: String) = Unit
            }
            assertTrue(service.initialize(createContext(mapOf("token" to "t"), logger)))

            assertEquals(emptyList(), service.fetchFileBlame("not-a-valid-project-id", "a.kt", "main"))

            assertTrue(warnings.any { it.contains("fetchFileBlame failed") })
            service.shutdown()
        }

    private fun createContext(
        config: Map<String, Any?>,
        contextLogger: Logger = NoOpLogger,
    ): PluginContext =
        object : PluginContext {
            override val logger: Logger = contextLogger
            override val pluginConfig = mapToJsonElement(config)
            override val hostVersion = "1.0.0-test"
            override val workingDirectory = "/tmp/test"

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}
        }
}
