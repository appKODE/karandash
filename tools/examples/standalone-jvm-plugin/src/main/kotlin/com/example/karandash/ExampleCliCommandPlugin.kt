package com.example.karandash

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.facet.PluginCommandContext
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.module.CliCommandModule

/**
 * Standalone example Karandash CLI command plugin.
 *
 * Demonstrates implementing [CliCommandModule] to contribute new top-level commands
 * to the Karandash CLI. This example adds an `example` command with a `greet`
 * subcommand that prints a greeting message.
 *
 * Build: ./gradlew build
 * Install: cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
 */
class ExampleCliCommandPlugin : CliCommandModule {
    override val id: String = "example-jvm-cli-command"
    override val name: String = "Example JVM CLI Command Plugin"
    override val version: String = "1.0.0"
    override val type: ModuleType get() = ModuleType.CLI_COMMAND

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun cliCommands(): List<PluginCommandSpec> {
        return listOf(
            PluginCommandSpec(
                name = "example",
                help = "Example plugin commands contributed by the standalone JVM plugin.",
                subcommands = listOf(
                    PluginCommandSpec(
                        name = "greet",
                        help = "Print a greeting from the example plugin.",
                        run = ::runGreet,
                    )
                )
            )
        )
    }

    private suspend fun runGreet(context: PluginCommandContext): Int {
        val target = context.rawArgs.firstOrNull() ?: "world"
        context.echo("Hello, $target! Greetings from the Example JVM CLI Command Plugin.")
        return 0
    }
}
