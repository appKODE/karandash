package io.karandash.cli.config

import io.karandash.config.pipeline.ConfigMapParser
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verification: `projects/base` sets `ai.max-token-budget: "${KARANDASH_AI_MAX_TOKENS}"`; with the variable
 * set, the interpolated string still decodes into the AI run budget.
 */
class AiMaxTokensEnvTest {
    @Test
    fun `KARANDASH_AI_MAX_TOKENS interpolates into the base config ai budget`() =
        runTest {
            val path = listOf(
                Path("projects", "base", ".karandash", "config.yaml"),
                Path("..", "projects", "base", ".karandash", "config.yaml")
            ).first { SystemFileSystem.exists(it) }
            val text = SystemFileSystem.source(path).buffered().use { it.readByteArray().decodeToString() }
            val raw = YamlFormatModule().parseToJsonElement(text)

            val config = ConfigMapParser.parseFullConfig(raw, mapOf(ENV to "12345", CAP_ENV to "50000")).config

            val aiBlock = checkNotNull(config.ai as? JsonObject) { "ai block missing" }
            val ai = AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(aiBlock))
            assertEquals(12345, ai.maxTokenBudget)
        }

    private companion object {
        const val ENV = "KARANDASH_AI_MAX_TOKENS"
        const val CAP_ENV = "KARANDASH_AI_MAX_TOKENS_CAP"
    }
}
