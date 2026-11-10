package io.karandash.cli.pipeline

import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.config.ThreadCommandsConfig
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** `ai.thread-commands.ask` is optional, defaults to off, and inherits through `extends`. */
class ConfigLoaderAiThreadCommandsTest {
    private suspend fun loadAi(
        yaml: String,
        base: String? = null,
    ): JsonElement {
        val dir = TestFiles.createTestTempDirectory("ai-thread-commands")
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

    private fun commandsOf(ai: JsonElement): ThreadCommandsConfig =
        KarandashJson.Default.decodeFromJsonElement(AiPluginConfig.serializer(), ai).threadCommands

    @Test
    fun `ai thread-commands ask inherits through extends`() =
        runTest {
            val base = "ai:\n  thread-commands:\n    ask: true\n"
            val ai = loadAi("extends:\n  - base.yaml\nai:\n  enabled: true\n", base)
            assertEquals(ThreadCommandsConfig(ask = true), commandsOf(ai))
        }

    @Test
    fun `a child can switch thread-commands ask back off`() =
        runTest {
            val base = "ai:\n  thread-commands:\n    ask: true\n"
            val ai = loadAi("extends:\n  - base.yaml\nai:\n  thread-commands:\n    ask: false\n", base)
            assertEquals(ThreadCommandsConfig(ask = false), commandsOf(ai))
        }

    @Test
    fun `an ai block without thread-commands leaves ask off`() =
        runTest {
            assertEquals(ThreadCommandsConfig(), commandsOf(loadAi("ai:\n  enabled: true\n")))
            assertEquals(false, ThreadCommandsConfig().ask)
        }
}
