package io.karandash.platforms.gitea

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GiteaMutatorTest {
    @Test
    fun moduleHasCorrectMetadata() {
        val service = GiteaPlatformService()
        assertEquals("gitea", service.id)
        assertEquals("Gitea", service.name)
        assertEquals("1.7.0", service.version)
    }

    @Test
    fun initializeSucceedsEvenWhenCredentialsNotProvided() =
        runTest {
            val service = GiteaPlatformService()
            val context = createMockContext(emptyMap(), emptyMap())
            val result = service.initialize(context)
            assertTrue(result)
        }

    @Test
    fun initializeReturnsTrueWithValidCredentials() =
        runTest {
            val service = GiteaPlatformService()
            val context = createMockContext(
                emptyMap(),
                mapOf("GITEA_URL" to "https://gitea.example.com", "GITEA_TOKEN" to "test-token"),
            )
            val result = service.initialize(context)
            assertTrue(result)
            service.shutdown()
        }

    @Test
    fun initializeUsesConfigCredentialsOverEnvironment() =
        runTest {
            val service = GiteaPlatformService()
            val context = createMockContext(
                mapOf("url" to "https://config.gitea.com", "token" to "config-token"),
                mapOf("GITEA_URL" to "https://env.gitea.com", "GITEA_TOKEN" to "env-token"),
            )
            val result = service.initialize(context)
            assertTrue(result)
            service.shutdown()
        }

    @Test
    fun testUpdateTitleRequiresInitialization() =
        runTest {
            val service = GiteaPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.updateTitle("owner/repo", "123", "New Title")
            }
            assertEquals("Gitea not initialized", exception.message)
        }

    @Test
    fun testUpdateDescriptionRequiresInitialization() =
        runTest {
            val service = GiteaPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.updateDescription("owner/repo", "123", "New description")
            }
            assertEquals("Gitea not initialized", exception.message)
        }

    @Test
    fun testUpdateTitleWithInvalidMrIdFails() =
        runTest {
            val service = GiteaPlatformService()
            val context = createMockContext(
                mapOf("url" to "https://gitea.example.com", "token" to "test-token"),
                emptyMap(),
            )
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.updateTitle("owner/repo", "invalid-mr-id", "New Title")
            }
            assertEquals("Invalid pull request ID: invalid-mr-id", exception.message)
            service.shutdown()
        }

    @Test
    fun testUpdateDescriptionWithInvalidMrIdFails() =
        runTest {
            val service = GiteaPlatformService()
            val context = createMockContext(
                mapOf("url" to "https://gitea.example.com", "token" to "test-token"),
                emptyMap(),
            )
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.updateDescription("owner/repo", "invalid-mr-id", "New description")
            }
            assertEquals("Invalid pull request ID: invalid-mr-id", exception.message)
            service.shutdown()
        }

    @Test
    fun shutdownClosesClient() =
        runTest {
            val service = GiteaPlatformService()
            val context = createMockContext(
                mapOf("url" to "https://gitea.example.com", "token" to "test-token"),
                emptyMap(),
            )
            service.initialize(context)
            service.shutdown()
        }

    private fun createMockContext(
        config: Map<String, Any?>,
        env: Map<String, String>,
    ): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(config)
            override val hostVersion: String = "test-1.0.0"
            override val workingDirectory: String = "/tmp"

            override fun env(name: String): String? = env[name]

            override fun log(message: String) {}

            override fun warn(message: String) {}
        }
}
