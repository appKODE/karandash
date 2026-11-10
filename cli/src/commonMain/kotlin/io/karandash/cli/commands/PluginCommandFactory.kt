package io.karandash.cli.commands

import com.github.ajalt.clikt.core.subcommands
import io.karandash.api.exception.KarandashException
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cache.DownloadsCacheConfig
import io.karandash.cli.pipeline.ConfigLoader
import io.karandash.cli.pipeline.ValidationOptions
import io.karandash.cli.plugin.PluginInfoCommand
import io.karandash.cli.plugin.PluginLoadCommand
import io.karandash.cli.plugin.PluginRebuildCommand
import io.karandash.cli.plugin.PluginRegistryCommand
import io.karandash.cli.plugin.PluginRegistrySetCommand
import io.karandash.cli.plugin.PluginRegistryShowCommand
import io.karandash.cli.plugin.PluginSearchCommand
import io.karandash.cli.plugin.PluginUpdateCommand
import io.karandash.cli.plugin.PluginVerifyCommand
import io.karandash.http.NetworkPolicy
import io.karandash.inspector.InstalledPluginListerImpl
import io.karandash.inspector.createPluginInspector
import io.karandash.loader.DownloadCache
import io.karandash.manifest.MissingPluginBehavior
import io.karandash.registry.PluginInstallUpdateEngine
import kotlinx.coroutines.withContext

/**
 * Creates the plugin management command with all subcommands.
 *
 * Uses [InstalledPluginListerImpl] with a platform-appropriate
 * [io.karandash.inspector.PluginInspector] for plugin file introspection.
 * [downloadCache] is asked once per install/update, so it can honor the local config's `cache.downloads`.
 */
fun pluginCommand(downloadCache: suspend () -> DownloadCache? = { PluginInstallUpdateEngine.downloadCache() },): PluginCommand {
    val inspector = createPluginInspector()
    val lister = InstalledPluginListerImpl(inspector, downloadCache)

    return PluginCommand().subcommands(
        PluginListCommand(lister, inspector),
        PluginInstallCommand(lister),
        PluginRemoveCommand(lister),
        PluginRemoveCommand(lister, name = "uninstall"),
        PluginInfoCommand(lister),
        PluginRebuildCommand(lister),
        PluginLoadCommand(lister, inspector),
        PluginVerifyCommand(inspector = inspector),
        PluginRegistryCommand().subcommands(PluginRegistryShowCommand(), PluginRegistrySetCommand()),
        PluginUpdateCommand(lister),
        PluginCreateCommand(),
        PluginSearchCommand(),
        SignCommand(),
        PluginCacheCommand().subcommands(
            PluginCacheClearCommand()
        )
    )
}

/** The plugin download cache configured by `cache.downloads` in the auto-discovered local config. */
internal suspend fun configuredPluginDownloadCache(bootstrap: PluginBootstrap): DownloadCache? =
    PluginInstallUpdateEngine.downloadCache(localDownloadsCache(bootstrap))

/**
 * `cache.downloads` of the config at [configPath] (auto-discovered when `null`), loaded through [ConfigLoader] like
 * every other command. Missing plugins only warn here: installing them is what the caller is about to do; unknown
 * keys do not warn, since only `cache.downloads` is read. A config that does not load gives the defaults with a
 * warning; the `allow-private-network` the chain sets does not leave this call.
 */
internal suspend fun localDownloadsCache(
    bootstrap: PluginBootstrap,
    configPath: String? = null
): DownloadsCacheConfig {
    val options = ValidationOptions
        .configOnly(configPath, bootstrap)
        .copy(onMissingPlugin = MissingPluginBehavior.WARN.name, skipUnknownKeyWarnings = true)
    // the chain's `allow-private-network` stays inside this load, not in the caller's policy
    return withContext(NetworkPolicy(NetworkPolicy.allowsPrivateNetwork())) {
        try {
            ConfigLoader(options, bootstrap, bootstrap.logger)
                .loadConfig()
                .config.cache
                ?.downloads ?: DownloadsCacheConfig()
        } catch (e: KarandashException) {
            bootstrap.logger.warn("Ignoring local config for 'cache.downloads' (${e.message}); using defaults")
            DownloadsCacheConfig()
        }
    }
}
