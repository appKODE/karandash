package io.karandash.cli.commands.validate

import io.karandash.api.ModuleRegistry
import io.karandash.bootstrap.DelegatingLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.config.renderers.registerBuiltInRenderers
import io.karandash.cli.plugin.loadPluginsWithManifest
import io.karandash.platform.absolutePathOrSelf
import io.karandash.sdk.Logger
import kotlinx.coroutines.coroutineScope
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Handles CLI plugin/bootstrap runtime wiring for validate command flows.
 */
internal class ValidateRuntimeBootstrap(
    private val logger: Logger,
) {
    suspend fun bootstrapMain(registry: ModuleRegistry): PluginBootstrap =
        bootstrap(
            registry = registry,
            reportLoadResult = true,
        )

    suspend fun bootstrapWatch(registry: ModuleRegistry): PluginBootstrap =
        bootstrap(
            registry = registry,
            reportLoadResult = false,
        )

    private suspend fun bootstrap(
        registry: ModuleRegistry,
        reportLoadResult: Boolean,
    ): PluginBootstrap =
        coroutineScope {
            val bootstrap = PluginBootstrap(
                registry = registry,
                logger = DelegatingLogger(logger),
                onFormatRegistryCreated = ::registerBuiltInRenderers,
            )

            val result = bootstrap.loadPluginsWithManifest(extraDirs = resolvePluginDirectories())

            if (reportLoadResult) {
                reportLoadResult(result)
            }

            bootstrap
        }

    private fun reportLoadResult(result: io.karandash.loader.LoadResult) {
        if (result.loaded.isNotEmpty()) {
            logger.info("Loaded ${result.loaded.size} plugin(s): ${result.loaded.joinToString(", ")}")
        }

        if (result.failed.isNotEmpty()) {
            result.failed.forEach { failure ->
                logger.warn(failure.message)
            }
        }
    }

    private fun resolvePluginDirectories(): List<Path> =
        buildList {
            val localPluginsDir = Path("./plugins").absolutePathOrSelf()
            if (SystemFileSystem.exists(localPluginsDir) &&
                SystemFileSystem.metadataOrNull(localPluginsDir)?.isDirectory == true
            ) {
                add(localPluginsDir)
            }
        }
}
