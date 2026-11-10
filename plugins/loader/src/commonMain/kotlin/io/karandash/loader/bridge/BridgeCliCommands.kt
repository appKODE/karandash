package io.karandash.loader.bridge

import io.karandash.api.facet.PluginCommandContext
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.support.suspendCatching
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** What a guest's `runCliCommand` op answers: the exit code and the lines to print. */
@Serializable
internal data class BridgeCliResult(
    val exitCode: Int = 1,
    val stdout: List<String> = emptyList(),
    val stderr: List<String> = emptyList(),
)

/** Run path for the CLI commands a guest lists through `cliCommands`, shared by every bridge runtime. */
internal object BridgeCliCommands {
    const val RUN_OP = "runCliCommand"

    /**
     * [specs] with a run handler on every leaf command that calls the guest's `runCliCommand` op with the command path
     * (names joined by a space, e.g. `"example greet"`); unchanged (listed, not runnable) when the guest does not export
     * the op.
     */
    fun runnable(
        specs: List<PluginCommandSpec>,
        transport: BridgeTransport,
    ): List<PluginCommandSpec> = if (transport.hasCapability(RUN_OP)) withRun(specs, transport, "") else specs

    private fun withRun(
        specs: List<PluginCommandSpec>,
        transport: BridgeTransport,
        parent: String,
    ): List<PluginCommandSpec> =
        specs.map { spec ->
            val path = if (parent.isEmpty()) spec.name else "$parent ${spec.name}"
            if (spec.subcommands.isEmpty()) {
                spec.copy(run = { run(transport, path, it) })
            } else {
                spec.copy(subcommands = withRun(spec.subcommands, transport, path))
            }
        }

    private suspend fun run(
        transport: BridgeTransport,
        command: String,
        context: PluginCommandContext,
    ): Int {
        val args = KarandashJson.Wire.encodeToString(ListSerializer(String.serializer()), context.rawArgs)
        val result = transport.call(RUN_OP, command, args)?.let { raw ->
            suspendCatching { KarandashJson.Wire.decodeFromString(BridgeCliResult.serializer(), raw) }.getOrNull()
        }
        if (result == null) {
            context.echoError("Plugin command '$command' failed: the plugin returned no result")
            return 1
        }
        result.stdout.forEach(context.echo)
        result.stderr.forEach(context.echoError)
        return result.exitCode
    }
}
