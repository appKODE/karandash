package io.karandash.cli.pipeline

import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

/** live keys are silent - loading a config that uses one logs no deprecation or ignored-key warning. */
class ConfigLoaderLiveAliasTest {
    private class Loaded(
        val ai: JsonElement,
        val messages: List<String>,
    )

    private suspend fun load(yaml: String): Loaded {
        val dir = TestFiles.createTestTempDirectory("live-alias")
        TestFiles.writeText(Path(dir, "config.yaml"), yaml)
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        val messages = mutableListOf<String>()
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
            val stage = ConfigLoader(options, bootstrap, CliLogger(sink = { messages += it })).loadConfig()
            return Loaded(stage.config.ai ?: JsonObject(emptyMap()), messages)
        } finally {
            bootstrap.shutdown()
        }
    }

    private fun Loaded.assertNoAliasWarning(vararg needles: String) {
        val bad = messages.filter { m -> m.contains("deprecated", ignoreCase = true) || needles.any { m.contains(it) } }
        assertTrue(bad.isEmpty(), "no alias warning expected: $bad")
    }

    @Test
    fun `code-suggestions mode separate is live and does not warn`() =
        runTest {
            val loaded = load("ai:\n  enabled: true\n  code-suggestions:\n    mode: separate\n")
            assertTrue(loaded.ai.toString().contains("separate"), "mode separate must survive loading: ${loaded.ai}")
            loaded.assertNoAliasWarning("ignored", "treated as inline")
        }
}
