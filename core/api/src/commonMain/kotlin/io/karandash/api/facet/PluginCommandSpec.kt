package io.karandash.api.facet

import io.karandash.api.PluginContext

/**
 * Descriptor for a plugin-contributed CLI command.
 *
 * Either [subcommands] OR [run] may be set, not both.
 * A spec with subcommands acts as a container (group command with no handler).
 * A spec with [run] is a leaf command that executes logic.
 *
 * @property name Command name as it appears in the CLI (e.g. "ai").
 * @property help Help text shown in `--help` output.
 * @property subcommands Child command specs (mutually exclusive with [run]).
 * @property run Suspend handler invoked when this command is executed.
 *
 * Not @Serializable: runtime context holder, carries the command's suspend lambda.
 */
public data class PluginCommandSpec(
    val name: String,
    val help: String,
    val subcommands: List<PluginCommandSpec> = emptyList(),
    val run: (suspend (PluginCommandContext) -> Int)? = null,
) {
    init {
        require(subcommands.isEmpty() || run == null) {
            "PluginCommandSpec '$name': cannot have both subcommands and a run handler."
        }
    }
}

/**
 * Context passed to a [PluginCommandSpec.run] handler when a plugin command executes.
 *
 * @property rawArgs Remaining args passed to this command after Clikt option parsing.
 * @property pluginContext The [PluginContext] for this module (with env allowlist expanded).
 * @property echo Function to print a line to stdout.
 * @property echoError Function to print a line to stderr.
 *
 * Not @Serializable: runtime context holder, carries the [PluginContext] and echo lambdas.
 */
public data class PluginCommandContext(
    val rawArgs: List<String>,
    val pluginContext: PluginContext,
    val echo: (String) -> Unit,
    val echoError: (String) -> Unit,
)
