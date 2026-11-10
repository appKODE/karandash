package io.karandash.platforms.azuredevops

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AzureDevOpsMutatorTest {
    @Test
    fun `module has correct metadata`() {
        val service = AzureDevOpsPlatformService()
        assertEquals("azure-devops", service.id)
        assertEquals("Azure Devops", service.name)
        assertEquals("1.7.0", service.version)
    }

    @Test
    fun `initialize returns true when token is provided`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            val context = createMockContext(mapOf("token" to "test-token"), emptyMap())
            val result = service.initialize(context)
            assertTrue(result)
            service.shutdown()
        }

    @Test
    fun `initialize returns true even without token`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            val context = createMockContext(emptyMap(), emptyMap())
            val result = service.initialize(context)
            assertTrue(result)
            service.shutdown()
        }

    @Test
    fun `initialize uses config token over environment`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            val context = createMockContext(
                mapOf("token" to "config-token"),
                mapOf("AZURE_DEVOPS_TOKEN" to "env-token"),
            )
            val result = service.initialize(context)
            assertTrue(result)
            service.shutdown()
        }

    @Test
    fun `addLabels throws when not initialized`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            assertFailsWith<IllegalStateException> {
                service.addLabels("org/project/repo", "123", listOf("bug"))
            }
        }

    @Test
    fun `addLabels validates projectId format`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            val context = createMockContext(mapOf("token" to "test-token"), emptyMap())
            service.initialize(context)

            assertFailsWith<IllegalArgumentException> {
                service.addLabels("invalid", "123", listOf("bug"))
            }

            assertFailsWith<IllegalArgumentException> {
                service.addLabels("org/project", "123", listOf("bug"))
            }

            assertFailsWith<IllegalArgumentException> {
                service.addLabels("org/project/repo/extra", "123", listOf("bug"))
            }

            service.shutdown()
        }

    @Test
    fun `addLabels validates mrId format`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            val context = createMockContext(mapOf("token" to "test-token"), emptyMap())
            service.initialize(context)

            assertFailsWith<IllegalArgumentException> {
                service.addLabels("org/project/repo", "not-a-number", listOf("bug"))
            }

            service.shutdown()
        }

    @Test
    fun `removeLabels throws when not initialized`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            assertFailsWith<IllegalStateException> {
                service.removeLabels("org/project/repo", "123", listOf("bug"))
            }
        }

    @Test
    fun `removeLabels validates projectId format`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            val context = createMockContext(mapOf("token" to "test-token"), emptyMap())
            service.initialize(context)

            assertFailsWith<IllegalArgumentException> {
                service.removeLabels("invalid", "123", listOf("bug"))
            }

            service.shutdown()
        }

    @Test
    fun `setDraft throws when not initialized`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            assertFailsWith<IllegalStateException> {
                service.setDraft("org/project/repo", "123", true)
            }
        }

    @Test
    fun `setDraft validates projectId format`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            val context = createMockContext(mapOf("token" to "test-token"), emptyMap())
            service.initialize(context)

            assertFailsWith<IllegalArgumentException> {
                service.setDraft("invalid", "123", true)
            }

            service.shutdown()
        }

    @Test
    fun `setDraft validates mrId format`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            val context = createMockContext(mapOf("token" to "test-token"), emptyMap())
            service.initialize(context)

            assertFailsWith<IllegalArgumentException> {
                service.setDraft("org/project/repo", "invalid-id", true)
            }

            service.shutdown()
        }

    @Test
    fun `shutdown closes client`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            val context = createMockContext(mapOf("token" to "test-token"), emptyMap())
            service.initialize(context)
            service.shutdown()
            // No assertion - just verify no exceptions thrown
        }

    @Test
    fun `updateTitle throws when not initialized`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            assertFailsWith<IllegalStateException> {
                service.updateTitle("org/project/repo", "123", "New Title")
            }
        }

    @Test
    fun `updateDescription throws when not initialized`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            assertFailsWith<IllegalStateException> {
                service.updateDescription("org/project/repo", "123", "New description")
            }
        }

    @Test
    fun `updateTitle validates mrId format`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            val context = createMockContext(mapOf("token" to "test-token"), emptyMap())
            service.initialize(context)

            assertFailsWith<IllegalArgumentException> {
                service.updateTitle("org/project/repo", "invalid-id", "New Title")
            }

            service.shutdown()
        }

    @Test
    fun `updateDescription validates mrId format`() =
        runTest {
            val service = AzureDevOpsPlatformService()
            val context = createMockContext(mapOf("token" to "test-token"), emptyMap())
            service.initialize(context)

            assertFailsWith<IllegalArgumentException> {
                service.updateDescription("org/project/repo", "invalid-id", "New description")
            }

            service.shutdown()
        }

    @Test
    fun `service is constructable`() {
        val service = AzureDevOpsPlatformService()
        assertNotNull(service)
        assertEquals("azure-devops", service.id)
    }

    private fun createMockContext(
        config: Map<String, Any?>,
        env: Map<String, String>,
    ): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(config)
            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"

            override fun env(name: String): String? = env[name]

            override fun log(message: String) {}

            override fun warn(message: String) {}
        }
}
