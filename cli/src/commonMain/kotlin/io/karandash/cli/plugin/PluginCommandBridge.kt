package io.karandash.cli.plugin

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import io.karandash.api.Module
import io.karandash.api.PluginContext
import io.karandash.api.facet.PluginCommandContext
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.errors.ExitCodes

/**
 * Bridges [PluginCommandSpec] descriptors to Clikt [CliktCommand] instances.
 *
 * Keeps `plugin-sdk` free of Clikt dependency - plugins declare specs,
 * this bridge converts them at CLI startup.
 */
object PluginCommandBridge {
    /**
     * Convert a [PluginCommandSpec] and its [pluginContext] into a Clikt [CliktCommand].
     * When [module] is given, it is initialized with [pluginContext] before the handler runs and shut
     * down afterwards.
     */
    fun buildCommand(
        spec: PluginCommandSpec,
        pluginContext: PluginContext,
        module: Module? = null,
    ): CliktCommand = PluginCliktCommand(spec, { pluginContext }, module)

    /**
     * Like [buildCommand], but the context is created by [contextAtRun] when a leaf command runs, so `--help`
     * and command discovery never load the config the context is built from.
     */
    internal fun buildCommand(
        spec: PluginCommandSpec,
        module: Module,
        contextAtRun: suspend () -> PluginContext,
    ): CliktCommand = PluginCliktCommand(spec, contextAtRun, module)
}

private class PluginCliktCommand(
    private val spec: PluginCommandSpec,
    private val contextAtRun: suspend () -> PluginContext,
    private val module: Module?,
) : SuspendCliktCommand(name = spec.name) {
    override fun help(context: Context) = spec.help

    // Leaf handlers parse their own flags (e.g. `ai fix --rule x`) from rawArgs, so unknown options must
    // reach them instead of failing Clikt option parsing.
    override val treatUnknownOptionsAsArgs: Boolean = spec.subcommands.isEmpty()

    private val rawArgs: List<String> by argument(help = "").multiple()

    init {
        if (spec.subcommands.isNotEmpty()) {
            subcommands(spec.subcommands.map { PluginCliktCommand(it, contextAtRun, module) })
        } else {
            configureContext { allowInterspersedArgs = false }
        }
    }

    override suspend fun suspendRun() {
        val handler = spec.run ?: return
        val pluginContext = contextAtRun()
        if (module != null && !module.initialize(pluginContext)) {
            echo("Error: plugin module '${module.id}' failed to initialize", err = true)
            throw ProgramResult(ExitCodes.EXECUTION_ERROR)
        }
        val exitCode = try {
            handler(
                PluginCommandContext(
                    rawArgs = rawArgs,
                    pluginContext = pluginContext,
                    echo = ::echo,
                    echoError = { echo(it, err = true) }
                )
            )
        } finally {
            module?.shutdown()
        }
        if (exitCode != ExitCodes.SUCCESS) throw ProgramResult(exitCode)
    }
}
