package io.karandash.api.module

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.facet.PluginCommandSpec

/**
 * A module that contributes CLI commands to the Karandash command-line interface.
 *
 * Implementations are Clikt-free - they return [PluginCommandSpec] descriptors.
 * The `cli` module bridges these specs to Clikt commands via `PluginCommandBridge`.
 *
 * @see PluginCommandSpec
 */
public interface CliCommandModule : Module {
    override val type: ModuleType get() = ModuleType.CLI_COMMAND

    /**
     * Returns the CLI command specifications contributed by this module.
     * Called once during startup after [initialize] returns `true`.
     */
    public suspend fun cliCommands(): List<PluginCommandSpec>
}
