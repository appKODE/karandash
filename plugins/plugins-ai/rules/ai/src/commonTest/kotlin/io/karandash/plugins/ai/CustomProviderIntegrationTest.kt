package io.karandash.plugins.ai

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CustomProviderIntegrationTest {
    private fun createMockContext(
        pluginConfig: Map<String, Any?>,
        envVars: Map<String, String> = emptyMap()
    ): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(pluginConfig)

            override fun env(name: String): String? = envVars[name]

            override fun log(message: String) {
                println("[TEST] $message")
            }

            override fun warn(message: String) {
                println("[TEST WARN] $message")
            }

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    @Test
    fun `initialize succeeds with custom provider and baseUrl`() =
        runTest {
            val context = createMockContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model"
                )
            )

            val module = AiRuleModule(buildTestRegistry())
            val result = module.initialize(context)

            assertTrue(result, "initialize should succeed with valid custom provider config")
            assertEquals(
                setOf(
                    "risk-scoring",
                    "review-comments",
                ),
                module.contributedRules()
            )
        }

    @Test
    fun `initialize succeeds with custom provider without apiKey`() =
        runTest {
            val context = createMockContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:8000/v1",
                    "model" to "llama-3"
                )
            )

            val module = AiRuleModule(buildTestRegistry())
            val result = module.initialize(context)

            assertTrue(result, "initialize should succeed for custom provider without apiKey")
        }

    @Test
    fun `initialize fails with custom provider without baseUrl`() =
        runTest {
            val context = createMockContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "model" to "test-model"
                )
            )

            val module = AiRuleModule(buildTestRegistry())
            val result = module.initialize(context)

            assertFalse(result, "initialize should fail when baseUrl is missing for custom provider")
        }

    @Test
    fun `initialize uses configured timeout and maxRetries`() =
        runTest {
            val context = createMockContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                    "timeout" to "90s",
                    "maxRetries" to 5
                )
            )

            val module = AiRuleModule(buildTestRegistry())
            val result = module.initialize(context)

            assertTrue(result, "initialize should succeed with custom timeout and retries")
            // Note: We cannot directly verify the provider's timeout/retry config without reflection
            // This test validates that the config values don't cause initialization failure
        }

    @Test
    fun `initialize with custom provider and apiKey from env`() =
        runTest {
            val context = createMockContext(
                pluginConfig = mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model"
                ),
                envVars = mapOf(
                    "CUSTOM_AI_API_KEY" to "test-key-12345"
                )
            )

            val module = AiRuleModule(buildTestRegistry())
            val result = module.initialize(context)

            assertTrue(result, "initialize should succeed with apiKey from environment")
        }
}
