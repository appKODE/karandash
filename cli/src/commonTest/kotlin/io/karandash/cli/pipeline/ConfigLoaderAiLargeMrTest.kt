package io.karandash.cli.pipeline

import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.config.AiLargeMrConfig
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every `ai.large-mr` key is optional, defaulted, and inherits through `extends`. */
class ConfigLoaderAiLargeMrTest {
    private suspend fun loadAi(
        yaml: String,
        base: String? = null,
    ): JsonElement {
        val dir = TestFiles.createTestTempDirectory("ai-large-mr")
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

    private fun largeMrOf(ai: JsonElement): AiLargeMrConfig =
        KarandashJson.Default.decodeFromJsonElement(AiPluginConfig.serializer(), ai).largeMr

    @Test
    fun `ai large-mr keys inherit through extends and a child overrides one`() =
        runTest {
            val base = "ai:\n  large-mr:\n    max-units: 3\n    max-parallel: 2\n    incremental: false\n"
            val ai = loadAi("extends:\n  - base.yaml\nai:\n  large-mr:\n    max-parallel: 6\n", base)
            assertEquals(AiLargeMrConfig(maxUnits = 3, maxParallel = 6, incremental = false), largeMrOf(ai))
        }

    @Test
    fun `ai large-mr test-gaps false inherits through extends`() =
        runTest {
            val base = "ai:\n  large-mr:\n    test-gaps: false\n"
            val ai = loadAi("extends:\n  - base.yaml\nai:\n  large-mr:\n    max-units: 4\n", base)
            assertEquals(AiLargeMrConfig(maxUnits = 4, testGaps = false), largeMrOf(ai))
        }

    @Test
    fun `an ai block without large-mr gets every large-mr default`() =
        runTest {
            assertEquals(AiLargeMrConfig(), largeMrOf(loadAi("ai:\n  enabled: true\n")))
        }
}
