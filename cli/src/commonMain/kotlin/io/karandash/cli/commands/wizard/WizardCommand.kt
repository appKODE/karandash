package io.karandash.cli.commands.wizard

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.mordant.terminal.Terminal
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.SuspendCliktCommand
import io.karandash.config.pipeline.ProfileResolver

/**
 * Native wizard command for interactive Karandash configuration setup.
 *
 * Features:
 * - Platform auto-detection
 * - Profile selection from registered profiles
 * - Interactive output format selection (YAML, JSON, KTS)
 */
class WizardCommand(
    private val bootstrap: PluginBootstrap,
    private val terminal: Terminal = Terminal(),
) : SuspendCliktCommand(
        name = "wizard"
    ) {
    override fun help(context: Context) = "Interactive setup wizard for Karandash configuration"

    private val outputPath by option(
        "--output",
        "-o",
        help = "Output path for generated configuration"
    ).default(bootstrap.formatRegistry.defaultRenderer().defaultOutputPath)

    private val skipDetection by option(
        "--skip-detection",
        help = "Skip automatic platform detection"
    ).flag(default = false)

    private val format by option(
        "--format",
        "-f",
        help = "Output format: yaml, json, or kts (if omitted, wizard will ask)"
    ).choice(
        bootstrap.formatRegistry
            .allModules()
            .map { it.formatId }
            .associateWith { it }
    )

    override suspend fun suspendRun() {
        val registry = bootstrap.registry()
        val profiles = ProfileResolver(registry).allProfiles()
        val wizard = SetupWizard(terminal, bootstrap.formatRegistry)
        wizard.run(outputPath, skipDetection, format, registry.platformServices(), profiles)
    }
}
