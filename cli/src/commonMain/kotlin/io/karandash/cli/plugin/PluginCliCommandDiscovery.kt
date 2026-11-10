package io.karandash.cli.plugin

import com.github.ajalt.clikt.core.CliktCommand
import io.karandash.api.ModuleRegistry
import io.karandash.api.exception.KarandashException
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.pipeline.ConfigLoader
import io.karandash.config.schema.ConfigSections
import io.karandash.platform.PlatformEnvironment
import kotlinx.serialization.json.JsonElement

/**
 * Discovers plugin-contributed CLI commands from registered [io.karandash.api.module.CliCommandModule] instances.
 *
 * Filters out commands whose names conflict with built-in reserved command names; the conflict
 * warning is suppressed when [quiet]. when a command runs, its module's plugin config is the
 * section of the config at [configPath] (auto-discovered when `null`) under the module's `configKey`.
 */
suspend fun buildPluginCliCommands(
    registry: ModuleRegistry,
    bootstrap: PluginBootstrap,
    reservedNames: Set<String>,
    quiet: Boolean = false,
    configPath: String? = null,
): List<CliktCommand> {
    val takenNames = reservedNames.toMutableSet()
    val result = mutableListOf<CliktCommand>()
    for (module in registry.cliCommandModules()) {
        val contextAtRun = suspend {
            configSection(bootstrap, configPath, module.configKey)
                ?.let { bootstrap.contextWithConfig(module.id, it) }
                ?: bootstrap.contextForModule(module)
        }
        for (spec in module.cliCommands()) {
            if (!takenNames.add(spec.name)) {
                if (!quiet) {
                    PlatformEnvironment.stderrPrintln(
                        "[warn] CLI command '${spec.name}' from ${module.id} conflicts with an existing command — skipped."
                    )
                }
            } else {
                result.add(PluginCommandBridge.buildCommand(spec, module, contextAtRun))
            }
        }
    }
    return result
}

/**
 * The [configKey] section of the loaded config: the typed `ai` block, else the module-owned top-level block.
 * `null` on zero-config, a missing section or a config that does not load (with a warning).
 */
private suspend fun configSection(
    bootstrap: PluginBootstrap,
    configPath: String?,
    configKey: String,
): JsonElement? {
    val loaded = try {
        ConfigLoader.forCommand(configPath, bootstrap).loadConfig()
    } catch (e: KarandashException) {
        bootstrap.logger.warn("Config not loaded for plugin command config '$configKey' (${e.message}); using an empty config")
        return null
    }
    if (loaded.usingZeroConfig) return null
    return if (configKey == ConfigSections.AI) loaded.config.ai else loaded.config.extensions[configKey]
}
