package io.karandash.cli.commands.config

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.compat.Golden
import io.karandash.cli.pipeline.ConfigLoader
import io.karandash.cli.pipeline.EffectiveConfigDescriber
import io.karandash.cli.pipeline.ValidationOptions
import io.karandash.config.Config
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** OBS 6: `config describe --effective` prints each value with its source plus the AI provider chain. */
class ConfigDescribeEffectiveTest {
    @Test
    fun `values carry the extends-chain entry that set them`() =
        runTest {
            val bootstrap = PluginBootstrap(envReader = { null })
            bootstrap.loadPluginsFromDirs(emptyList())
            val result = try {
                ConfigDescribeCommand(bootstrap).test(listOf("--effective", "-c", "projects/wallet/.karandash/config.yaml"))
            } finally {
                bootstrap.shutdown()
            }
            val lines = result.stdout.lines()

            assertEquals(0, result.statusCode, result.output)
            assertTrue(
                lines.any { it.startsWith("rules.ruleConfigs.titleFormat.pattern = ") && "(extends:" in it && "base-kmp" in it },
                result.stdout
            )
            assertTrue(lines.any { it.startsWith("allowLocalhost = false (default)") }, result.stdout)
            assertTrue(lines.any { it.startsWith("ai chain: main=bifrost ") }, result.stdout)
        }

    @Test
    fun `rule meta and a secret key keep their path and the source that set them`() =
        runTest {
            val dir = TestFileHelpers.createTempDir("describe-meta")
            val bootstrap = PluginBootstrap(envReader = { null })
            bootstrap.loadPluginsFromDirs(emptyList())
            val result = try {
                val yaml = "rules:\n  title-format:\n    suggestion: Use a tag\n    explanation: Routes the MR\n" +
                    "platforms:\n  gitlab:\n    token: glpat-effsecret9\n"
                val config = Path(dir, "config.yaml").also { TestFileHelpers.writeFile(it, yaml) }
                ConfigDescribeCommand(bootstrap).test(listOf("--effective", "-c", config.toString()))
            } finally {
                bootstrap.shutdown()
                TestFileHelpers.deleteTempDir(dir)
            }
            val lines = result.stdout.lines()

            assertEquals(0, result.statusCode, result.output)
            assertTrue(lines.any { it.startsWith("rules.ruleMeta.titleFormat.suggestion = ") && "(config:" in it }, result.stdout)
            assertTrue(lines.any { it.startsWith("rules.ruleMeta.titleFormat.explanation = ") && "(config:" in it }, result.stdout)
            assertTrue(lines.any { it.startsWith("platforms.gitlab.token = [REDACTED] (config:") }, result.stdout)
            assertTrue("effsecret9" !in result.stdout, result.stdout)
        }

    @Test
    fun `a config that fails to load prints its how to fix hint`() =
        runTest {
            val dir = TestFileHelpers.createTempDir("describe-hint")
            val bootstrap = PluginBootstrap(envReader = { null })
            bootstrap.loadPluginsFromDirs(emptyList())
            val result = try {
                val config = Path(dir, "config.yaml").also { TestFileHelpers.writeFile(it, "policies: x\n") }
                ConfigDescribeCommand(bootstrap).test(listOf("--effective", "-c", config.toString()))
            } finally {
                bootstrap.shutdown()
                TestFileHelpers.deleteTempDir(dir)
            }

            assertTrue("→ How to fix: Write 'policies' as a list" in result.stderr, result.output)
        }

    @Test
    fun `verbose run logs the effective config once`() =
        runTest {
            val out = mutableListOf<String>()
            val bootstrap = PluginBootstrap(envReader = { null })
            bootstrap.loadPluginsFromDirs(emptyList())
            try {
                val options = ValidationOptions(
                    configPathStr = "projects/wallet/.karandash/config.yaml",
                    mrDataStr = null,
                    mrId = null,
                    platform = null,
                    project = null,
                    prUrl = null,
                    format = "json",
                    outputPathStr = null,
                    quiet = false,
                    verbose = true,
                    strict = false,
                    dryRun = false,
                    explain = false,
                    fix = false,
                    yes = false,
                    noAutoDetect = true,
                    outputWriter = {},
                    sharedBootstrap = bootstrap,
                )
                ConfigLoader(options, bootstrap, CliLogger(verbose = true, sink = out::add)).loadConfig()
            } finally {
                bootstrap.shutdown()
            }
            val effective = out.filter { "Effective config:" in it }

            assertEquals(1, effective.size, out.joinToString("\n"))
            assertTrue("ai chain: main=bifrost" in effective.single(), effective.single())
        }

    @Test
    fun `secrets never print`() {
        val config = Config(
            ai = JsonObject(
                mapOf(
                    "provider" to JsonPrimitive("custom"),
                    "apiKey" to JsonPrimitive("sk-effsecret1"),
                    "baseUrl" to JsonPrimitive("https://u:effpass2@llm.example.com/v1?token=efftok3"),
                    "consensusModels" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
                    "analyzerConsensus" to JsonPrimitive(true),
                ),
            ),
        )
        val output = EffectiveConfigDescriber.describe(config, emptyList()).joinToString("\n")

        listOf("effsecret1", "effpass2", "efftok3").forEach { assertTrue(it !in output, "'$it' leaked:\n$output") }
        assertTrue("ai chain: consensus=[a, b] strategy=intersection analyzerConsensus=true" in output, output)
    }
}
