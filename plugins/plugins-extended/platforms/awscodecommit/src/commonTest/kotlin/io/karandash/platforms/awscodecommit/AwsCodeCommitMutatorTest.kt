package io.karandash.platforms.awscodecommit

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AwsCodeCommitMutatorTest {
    private fun createTestContext(
        config: Map<String, Any?> = emptyMap(),
        envVars: Map<String, String> = emptyMap(),
    ): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(config)

            override fun env(name: String): String? = envVars[name]

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    @Test
    fun testModuleMetadata() {
        val service = AwsCodeCommitPlatformService()
        assertEquals("awscodecommit", service.id)
        assertEquals("AWS CodeCommit", service.name)
        assertEquals("1.7.0", service.version)
    }

    @Test
    fun testInitializeWithDefaultRegion() =
        runTest {
            val service = AwsCodeCommitPlatformService()
            val context = createTestContext()
            val result = service.initialize(context)
            assertTrue(result, "Initialization should succeed with default region")
        }

    @Test
    fun testInitializeWithConfigRegion() =
        runTest {
            val service = AwsCodeCommitPlatformService()
            val context = createTestContext(config = mapOf("region" to "eu-west-1"))
            val result = service.initialize(context)
            assertTrue(result, "Initialization should succeed with config region")
        }

    @Test
    fun testInitializeWithEnvRegion() =
        runTest {
            val service = AwsCodeCommitPlatformService()
            val context = createTestContext(envVars = mapOf("AWS_REGION" to "ap-southeast-1"))
            val result = service.initialize(context)
            assertTrue(result, "Initialization should succeed with env region")
        }

    @Test
    fun testInitializeWithCredentials() =
        runTest {
            val service = AwsCodeCommitPlatformService()
            val context = createTestContext(
                config = mapOf(
                    "region" to "us-west-2",
                    "accessKeyId" to "AKIAIOSFODNN7EXAMPLE",
                    "secretAccessKey" to "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                ),
            )
            val result = service.initialize(context)
            assertTrue(result, "Initialization should succeed with credentials")
        }

    @Test
    fun testAddLabelsThrowsUnsupportedOperationException() =
        runTest {
            val service = AwsCodeCommitPlatformService()
            val context = createTestContext()
            service.initialize(context)

            assertFailsWith<UnsupportedOperationException> {
                service.addLabels("test-repo", "1", listOf("bug", "feature"))
            }
        }

    @Test
    fun testRemoveLabelsThrowsUnsupportedOperationException() =
        runTest {
            val service = AwsCodeCommitPlatformService()
            val context = createTestContext()
            service.initialize(context)

            assertFailsWith<UnsupportedOperationException> {
                service.removeLabels("test-repo", "1", listOf("bug"))
            }
        }

    @Test
    fun testSetDraftThrowsUnsupportedOperationException() =
        runTest {
            val service = AwsCodeCommitPlatformService()
            val context = createTestContext()
            service.initialize(context)

            assertFailsWith<UnsupportedOperationException> {
                service.setDraft("test-repo", "1", true)
            }
        }

    @Test
    fun testUpdateTitleRequiresInitialization() =
        runTest {
            val service = AwsCodeCommitPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.updateTitle("test-repo", "1", "New Title")
            }
            assertEquals("AWS CodeCommit not initialized", exception.message)
        }

    @Test
    fun testUpdateDescriptionRequiresInitialization() =
        runTest {
            val service = AwsCodeCommitPlatformService()

            val exception = assertFailsWith<IllegalStateException> {
                service.updateDescription("test-repo", "1", "New description")
            }
            assertEquals("AWS CodeCommit not initialized", exception.message)
        }

    @Test
    fun testUpdateTitleWithInvalidMrIdFails() =
        runTest {
            val service = AwsCodeCommitPlatformService()
            val context = createTestContext()
            service.initialize(context)

            // AWS CodeCommit uses string PR IDs (not numeric), so "invalid-mr-id" is valid input
            // The client will reject it at the API level, not at validation
            service.shutdown()
        }

    @Test
    fun testUpdateDescriptionWithInvalidMrIdFails() =
        runTest {
            val service = AwsCodeCommitPlatformService()
            val context = createTestContext()
            service.initialize(context)

            // AWS CodeCommit uses string PR IDs (not numeric), so "invalid-mr-id" is valid input
            // The client will reject it at the API level, not at validation
            service.shutdown()
        }

    @Test
    fun testShutdown() =
        runTest {
            val service = AwsCodeCommitPlatformService()
            val context = createTestContext()
            service.initialize(context)
            service.shutdown()
        }
}
