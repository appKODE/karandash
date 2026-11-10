package io.karandash.cli.pipeline

import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.config.PreMergeCheck
import io.karandash.plugins.ai.core.config.PreMergeCheckMode
import io.karandash.plugins.ai.core.pipeline.VerifierConfig
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The AI quality keys (`ai.grounding.verifier`, `ai.checks`, `ai.checks-ticket-compliance`) are optional, defaulted, and inherit through `extends`. */
class ConfigLoaderAiQualityTest {
    private suspend fun loadAi(
        yaml: String,
        base: String? = null,
    ): JsonElement {
        val dir = TestFiles.createTestTempDirectory("ai-quality")
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

    private fun configOf(ai: JsonElement): AiPluginConfig = KarandashJson.Default.decodeFromJsonElement(AiPluginConfig.serializer(), ai)

    @Test
    fun `the verifier is off by default with no model`() =
        runTest {
            assertEquals(VerifierConfig(), configOf(loadAi("ai:\n  enabled: true\n")).grounding.verifier)
        }

    @Test
    fun `verifier keys inherit through extends and a child overrides one`() =
        runTest {
            val base = "ai:\n  grounding:\n    verifier:\n      enabled: true\n      model: small\n"
            val ai = loadAi("extends:\n  - base.yaml\nai:\n  grounding:\n    verifier:\n      model: tiny\n", base)
            assertEquals(VerifierConfig(enabled = true, model = "tiny"), configOf(ai).grounding.verifier)
        }

    @Test
    fun `checks are empty and ticket compliance is off by default`() =
        runTest {
            val cfg = configOf(loadAi("ai:\n  enabled: true\n"))
            assertTrue(cfg.checks.isEmpty())
            assertFalse(cfg.checksTicketCompliance)
        }

    @Test
    fun `checks inherit through extends and a child turns on ticket compliance`() =
        runTest {
            val base = "ai:\n  checks:\n    - name: Logs\n      instruction: No secrets logged\n" +
                "      paths: [\"src/**\"]\n      mode: error\n"
            val cfg = configOf(loadAi("extends:\n  - base.yaml\nai:\n  checks-ticket-compliance: true\n", base))
            assertEquals(listOf(PreMergeCheck("Logs", "No secrets logged", listOf("src/**"), PreMergeCheckMode.ERROR)), cfg.checks)
            assertTrue(cfg.checksTicketCompliance)
        }
}
