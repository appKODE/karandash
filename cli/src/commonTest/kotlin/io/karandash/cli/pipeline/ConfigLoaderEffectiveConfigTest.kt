package io.karandash.cli.pipeline

import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The run parses the cleaned config, and unknown nested keys only warn. */
class ConfigLoaderEffectiveConfigTest {
    private suspend fun load(
        yaml: String,
        extraFiles: Map<String, String> = emptyMap(),
    ): Pair<ConfigLoadStage, List<String>> {
        val dir = TestFiles.createTestTempDirectory("effective-config")
        TestFiles.writeText(Path(dir, "config.yaml"), yaml)
        extraFiles.forEach { (name, text) -> TestFiles.writeText(Path(dir, name), text) }
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        val lines = mutableListOf<String>()
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
            return ConfigLoader(options, bootstrap, CliLogger(sink = { lines += it })).loadConfig() to lines
        } finally {
            bootstrap.shutdown()
        }
    }

    @Test
    fun `sections stripped by on-missing warn are gone from the parsed config`() =
        runTest {
            val (stage, _) = load("plugins:\n  on-missing: warn\nrules:\n  acme-rule:\n    level: 3\n")
            assertFalse(
                "acmeRule" in stage.config.rules.ruleConfigs,
                stage.config.rules.ruleConfigs.keys
                    .toString()
            )
        }

    @Test
    fun `an unknown key inside a config section warns and the run continues`() =
        runTest {
            val (stage, lines) = load("cache:\n  enabled: true\n  max-entires: 5\n")
            assertTrue(stage.config.cache?.enabled == true)
            assertTrue(lines.any { "'cache.maxEntires'" in it }, lines.toString())
        }
}
