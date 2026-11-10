package io.karandash.cli.pipeline

import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.config.AiBudgetConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Every `ai.budget` key is optional, defaulted, and inherits through `extends`. */
class ConfigLoaderAiBudgetTest {
    private suspend fun loadAi(
        yaml: String,
        base: String? = null,
    ): JsonElement {
        val dir = TestFiles.createTestTempDirectory("ai-budget")
        TestFiles.writeText(Path(dir, "config.yaml"), yaml)
        base?.let { TestFiles.writeText(Path(dir, "base.yaml"), it) }
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        try {
            val options = ValidationOptions(
                configPathStr = "$dir/config.yaml",
                mrDataStr = null,
                mrId = null,
                platform = null,
                project = null,
                prUrl = null,
                format = "json",
                outputPathStr = null,
                quiet = false,
                verbose = false,
                strict = false,
                dryRun = false,
                explain = false,
                fix = false,
                yes = false,
                noAutoDetect = true,
                outputWriter = {},
                sharedBootstrap = bootstrap,
            )
            val stage = ConfigLoader(options, bootstrap, CliLogger(sink = {})).loadConfig()
            return stage.config.ai ?: JsonObject(emptyMap())
        } finally {
            bootstrap.shutdown()
        }
    }

    private fun budgetOf(ai: JsonElement): AiBudgetConfig =
        KarandashJson.Default.decodeFromJsonElement(AiPluginConfig.serializer(), ai).budget

    @Test
    fun `ai budget keys inherit through extends and a child overrides one`() =
        runTest {
            val base = """
                ai:
                  budget:
                    max-input-tokens: 5000
                    reserve-output-tokens: 100
                    weights:
                      cross-file-context: 300
            """.trimIndent()
            val ai = loadAi("extends:\n  - base.yaml\nai:\n  budget:\n    reserve-output-tokens: 200\n", base)
            val budget = budgetOf(ai)
            assertEquals(5000, budget.maxInputTokens)
            assertEquals(200, budget.reserveOutputTokens)
            assertEquals(300, budget.priorityOf("crossFileContext", 0))
            assertEquals(19_200, AiRuleConfig.fromPluginConfig(ai).maxDiffCharsTotal)
        }

    @Test
    fun `an ai block without budget gets every budget default`() =
        runTest {
            val ai = loadAi("ai:\n  enabled: true\n")
            assertEquals(AiBudgetConfig(), budgetOf(ai))
            assertEquals(256_000, AiRuleConfig.fromPluginConfig(ai).maxDiffCharsPerFile)
            assertNull(AiPluginConfig.fromPluginConfig(ai).contextBudget)
        }

    @Test
    fun `ai context budget reads a token count through extends`() =
        runTest {
            val ai = loadAi("extends:\n  - base.yaml\nai:\n  enabled: true\n", "ai:\n  context:\n    budget: 48000\n")
            assertEquals(48_000, AiPluginConfig.fromPluginConfig(ai).contextBudget)
            assertEquals(256_000, AiRuleConfig.fromPluginConfig(ai).maxDiffCharsTotal)
        }
}
