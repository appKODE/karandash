package io.karandash.platforms.gitlab

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import io.karandash.sdk.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitlabMutatorTest {
    @Test
    fun testModuleMetadata() {
        val service = GitLabPlatformService()
        assertEquals("gitlab", service.id)
        assertEquals("GitLab", service.name)
        assertEquals("1.7.0", service.version)
    }

    @Test
    fun testInitializeWithToken() =
        runTest {
            val service = GitLabPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            assertTrue(service.initialize(context))
            service.shutdown()
        }

    @Test
    fun testInitializeWithUrl() =
        runTest {
            val service = GitLabPlatformService()
            val context = createContext(mapOf("url" to "https://gitlab.example.com", "token" to "test-token"))
            assertTrue(service.initialize(context))
            service.shutdown()
        }

    @Test
    fun testInitializeWithoutTokenStartsAnonymously() =
        runTest {
            val service = GitLabPlatformService()
            val context = createContext(emptyMap())
            val result = service.initialize(context)
            assertEquals(true, result)
        }

    @Test
    fun testAddLabelsRequiresInitialization() =
        runTest {
            val service = GitLabPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.addLabels("project/repo", "123", listOf("bug", "enhancement"))
            }
            assertEquals("Gitlab not initialized", exception.message)
        }

    @Test
    fun testRemoveLabelsRequiresInitialization() =
        runTest {
            val service = GitLabPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.removeLabels("project/repo", "123", listOf("bug"))
            }
            assertEquals("Gitlab not initialized", exception.message)
        }

    @Test
    fun testSetDraftRequiresInitialization() =
        runTest {
            val service = GitLabPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.setDraft("project/repo", "123", true)
            }
            assertEquals("Gitlab not initialized", exception.message)
        }

    @Test
    fun testAddLabelsWithInvalidMrIdFails() =
        runTest {
            val service = GitLabPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.addLabels("project/repo", "invalid-mr-id", listOf("bug"))
            }
            assertEquals("Invalid merge request ID: invalid-mr-id", exception.message)
            service.shutdown()
        }

    @Test
    fun testRemoveLabelsWithInvalidMrIdFails() =
        runTest {
            val service = GitLabPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.removeLabels("project/repo", "invalid-mr-id", listOf("bug"))
            }
            assertEquals("Invalid merge request ID: invalid-mr-id", exception.message)
            service.shutdown()
        }

    @Test
    fun testSetDraftWithInvalidMrIdFails() =
        runTest {
            val service = GitLabPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.setDraft("project/repo", "invalid-mr-id", true)
            }
            assertEquals("Invalid merge request ID: invalid-mr-id", exception.message)
            service.shutdown()
        }

    @Test
    fun testUpdateTitleRequiresInitialization() =
        runTest {
            val service = GitLabPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.updateTitle("project/repo", "123", "New Title")
            }
            assertEquals("Gitlab not initialized", exception.message)
        }

    @Test
    fun testUpdateDescriptionRequiresInitialization() =
        runTest {
            val service = GitLabPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.updateDescription("project/repo", "123", "New description")
            }
            assertEquals("Gitlab not initialized", exception.message)
        }

    @Test
    fun testUpdateTitleWithInvalidMrIdFails() =
        runTest {
            val service = GitLabPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.updateTitle("project/repo", "invalid-mr-id", "New Title")
            }
            assertEquals("Invalid merge request ID: invalid-mr-id", exception.message)
            service.shutdown()
        }

    @Test
    fun testUpdateDescriptionWithInvalidMrIdFails() =
        runTest {
            val service = GitLabPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)

            val exception = assertFailsWith<IllegalArgumentException> {
                service.updateDescription("project/repo", "invalid-mr-id", "New description")
            }
            assertEquals("Invalid merge request ID: invalid-mr-id", exception.message)
            service.shutdown()
        }

    @Test
    fun testInitializeWithApproveToken() =
        runTest {
            val service = GitLabPlatformService()
            val context = createContext(
                mapOf("token" to "project-token", "approveToken" to "user-pat"),
            )
            assertTrue(service.initialize(context))
            service.shutdown()
        }

    @Test
    fun testInitializeWithApproveTokenFromEnv() =
        runTest {
            val service = GitLabPlatformService()
            val context = createContext(
                mapOf("token" to "project-token"),
                envVars = mapOf("GITLAB_APPROVE_TOKEN" to "env-user-pat"),
            )
            assertTrue(service.initialize(context))
            service.shutdown()
        }

    @Test
    fun testApproveWithoutApproveTokenLogsWarningAndSkips() =
        runTest {
            val warnings = mutableListOf<String>()
            val service = GitLabPlatformService()
            val context = createContext(mapOf("token" to "test-token"), warnings = warnings)
            service.initialize(context)

            // Should not throw and should not make any API call
            service.approve("project/repo", "123")

            assertTrue(warnings.any { it.contains("no approve token configured") })
            service.shutdown()
        }

    @Test
    fun testUnapproveWithoutApproveTokenLogsWarningAndSkips() =
        runTest {
            val warnings = mutableListOf<String>()
            val service = GitLabPlatformService()
            val context = createContext(mapOf("token" to "test-token"), warnings = warnings)
            service.initialize(context)

            // Should not throw and should not make any API call
            service.unapprove("project/repo", "123")

            assertTrue(warnings.any { it.contains("no approve token configured") })
            service.shutdown()
        }

    @Test
    fun testShutdown() =
        runTest {
            val service = GitLabPlatformService()
            val context = createContext(mapOf("token" to "test-token"))
            service.initialize(context)
            service.shutdown() // Should not throw
        }

    private fun createContext(
        config: Map<String, Any?>,
        envVars: Map<String, String> = emptyMap(),
        warnings: MutableList<String> = mutableListOf(),
    ): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(config)
            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
            override val logger: Logger = object : Logger {
                override fun debug(message: String) {}

                override fun info(message: String) {}

                override fun warn(message: String) {
                    warnings.add(message)
                }

                override fun error(message: String) {}
            }

            override fun env(name: String): String? = envVars[name]

            override fun log(message: String) {}

            override fun warn(message: String) {
                warnings.add(message)
            }
        }
}
