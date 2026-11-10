package io.karandash.cli.plugin

import com.github.ajalt.clikt.testing.test
import io.karandash.api.PluginContext
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.module.CliCommandModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/** a plugin CLI command's module gets the section of the loaded config under its `configKey`. */
class PluginCommandConfigTest {
    private class EchoConfigModule : CliCommandModule {
        override val id: String = "echo-config-cli-command"
        override val name: String = "Echo Config"
        override val version: String = "1.0.0"
        override val configKey: String = "ai"

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun cliCommands(): List<PluginCommandSpec> =
            listOf(
                PluginCommandSpec(name = "echo-config", help = "", run = { ctx ->
                    val config = ctx.pluginContext.pluginConfig as JsonObject
                    ctx.echo(
                        listOf(
                            "provider",
                            "model",
                            "baseUrl"
                        ).joinToString(" ") { (config[it] as? JsonPrimitive)?.contentOrNull.toString() }
                    )
                    0
                }),
            )
    }

    @Test
    fun `a plugin command runs with the ai block of the loaded config`() =
        runTest {
            val dir = TestFileHelpers.createTempDir("plugin-command-config")
            val bootstrap = PluginBootstrap(envReader = { null }).also { it.loadPluginsFromDirs(emptyList()) }
            try {
                val config = Path(dir, "config.yaml")
                TestFileHelpers.writeFile(config, "ai:\n  provider: ollama\n  model: llama3.2\n  base-url: http://localhost:11434/v1\n")
                bootstrap.registry().register(EchoConfigModule())

                val command = buildPluginCliCommands(bootstrap.registry(), bootstrap, emptySet(), configPath = config.toString())
                    .single { it.commandName == "echo-config" }
                val result = command.test(emptyList())

                assertEquals(0, result.statusCode, result.output)
                assertEquals("ollama llama3.2 http://localhost:11434/v1", result.stdout.trim())
            } finally {
                bootstrap.shutdown()
                TestFileHelpers.deleteTempDir(dir)
            }
        }
}
