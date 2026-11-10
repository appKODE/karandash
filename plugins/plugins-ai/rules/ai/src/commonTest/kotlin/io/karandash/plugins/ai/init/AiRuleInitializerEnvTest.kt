package io.karandash.plugins.ai.init

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.DiffMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the documented `KARANDASH_*` AI env vars are honoured.
 */
class AiRuleInitializerEnvTest {
    private fun createContext(envVars: Map<String, String>): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(
                mapOf(
                    "provider" to "custom",
                    "baseUrl" to "http://localhost:1234/v1",
                    "model" to "test-model",
                ),
            )

            override fun env(name: String): String? = envVars[name]

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    private suspend fun resolve(envVars: Map<String, String>): AiPluginConfig {
        val result = AiRuleInitializer(buildTestRegistry(), null).initialize(createContext(envVars))
        assertNotNull(result, "Expected successful initialization")
        return result.pluginCfg
    }

    @Test
    fun `documented KARANDASH env vars are applied`() =
        runTest {
            val cfg = resolve(
                mapOf(
                    "KARANDASH_AI_DRY_RUN" to "true",
                    "KARANDASH_AI_MAX_TOKEN_BUDGET" to "1234",
                    "KARANDASH_REVIEW_MODE" to "patch",
                ),
            )

            assertTrue(cfg.dryRunAi)
            assertEquals(1234, cfg.maxTokenBudget)
            assertEquals(DiffMode.PATCH, cfg.reviewMode)
        }
}
