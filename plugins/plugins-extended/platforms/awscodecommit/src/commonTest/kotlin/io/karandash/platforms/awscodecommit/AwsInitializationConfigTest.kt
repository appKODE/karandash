package io.karandash.platforms.awscodecommit

import io.karandash.api.support.mapToJsonElement
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class AwsInitializationConfigTest {
    @Test
    fun `aws provider accepts explicit credentials from plugin config`() =
        runTest {
            val provider = AwsCodeCommitPlatformService()
            val initialized = provider.initialize(
                TestPluginContext(
                    pluginConfig = mapToJsonElement(
                        mapOf(
                            "accessKeyId" to "ctx-key",
                            "secretAccessKey" to "ctx-secret"
                        )
                    )
                )
            )
            assertTrue(initialized)
            provider.shutdown()
        }
}
