package io.karandash.cli.pipeline

import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.config.Config
import io.karandash.config.DryRunScope
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `dry-run-scope` turns on AI dry-run; `dry-run: true` alone keeps its old meaning and warns once. */
class ConfigLoaderDryRunScopeTest {
    private suspend fun load(yaml: String): Pair<JsonObject?, List<String>> {
        val (config, lines) = loadConfig(yaml)
        return config.ai as? JsonObject to lines.filter { "dry-run without dry-run-scope" in it }
    }

    private suspend fun loadConfig(
        yaml: String,
        showPrompt: Boolean = false,
    ): Pair<Config, List<String>> {
        val dir = TestFiles.createTestTempDirectory("dry-run-scope")
        TestFiles.writeText(Path(dir, "config.yaml"), yaml)
        val lines = mutableListOf<String>()
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
                dryRun = showPrompt,
                explain = false,
                fix = false,
                yes = false,
                noAutoDetect = true,
                outputWriter = {},
                sharedBootstrap = bootstrap,
                showPrompt = showPrompt,
            )
            val stage = ConfigLoader(options, bootstrap, CliLogger(sink = { lines += it })).loadConfig()
            return stage.config to lines
        } finally {
            bootstrap.shutdown()
        }
    }

    @Test
    fun `scope ai sets dryRun in the ai block`() =
        runTest {
            val (ai, warnings) = load("dry-run: true\ndry-run-scope: ai\nai:\n  enabled: true\n")
            assertEquals(JsonPrimitive(true), ai?.get("dryRun"))
            assertEquals(emptyList(), warnings)
        }

    @Test
    fun `dry-run without scope makes an ai dry run and warns once`() =
        runTest {
            val (config, lines) = loadConfig("dry-run: true\nai:\n  enabled: true\n")
            assertEquals(JsonPrimitive(true), (config.ai as? JsonObject)?.get("dryRun"))
            assertEquals(DryRunScope.AI, config.activeDryRunScope, "outputs still run")
            assertEquals(1, lines.count { "dry-run without dry-run-scope" in it })
        }

    @Test
    fun `each dry-run-scope value loads as its own active scope`() =
        runTest {
            val all = loadConfig("dry-run: true\ndry-run-scope: all\nai:\n  enabled: true\n").first
            val ai = loadConfig("dry-run: true\ndry-run-scope: ai\nai:\n  enabled: true\n").first

            assertEquals(DryRunScope.ALL, all.activeDryRunScope)
            assertEquals(DryRunScope.AI, ai.activeDryRunScope)
            assertEquals(JsonPrimitive(true), (all.ai as? JsonObject)?.get("dryRun"))
        }

    @Test
    fun `dry-run without scope and without an ai block adds no ai block and no warning`() =
        runTest {
            val (config, lines) = loadConfig("dry-run: true\n")
            assertNull(config.ai)
            assertEquals(0, lines.count { "dry-run without dry-run-scope" in it })
        }

    @Test
    fun `scope without dry-run does nothing`() =
        runTest {
            val (ai, warnings) = load("dry-run-scope: all\nai:\n  enabled: true\n")
            assertNull(ai?.get("dryRun"))
            assertEquals(emptyList(), warnings)
        }

    @Test
    fun `show-prompt runs an ai dry run that dumps every prompt and keeps the dump directory`() =
        runTest {
            val (config, _) = loadConfig("ai:\n  enabled: true\n  debug:\n    prompt-dir: out/prompts\n", showPrompt = true)
            val ai = checkNotNull(config.ai as? JsonObject)

            assertEquals(DryRunScope.ALL, config.activeDryRunScope, "nothing leaves the process")
            assertEquals(JsonPrimitive(true), ai["dryRun"])
            assertEquals(JsonPrimitive("artifact"), ai["debug"]?.jsonObject?.get("prompt"))
            assertEquals(JsonPrimitive("out/prompts"), ai["debug"]?.jsonObject?.get("promptDir"))
        }

    @Test
    fun `without show-prompt the debug block is left as configured`() =
        runTest {
            val (config, _) = loadConfig("ai:\n  enabled: true\n  debug:\n    prompt: off\n")

            assertNull(config.activeDryRunScope)
            assertEquals(JsonPrimitive("off"), (config.ai as? JsonObject)?.get("debug")?.jsonObject?.get("prompt"))
        }
}
