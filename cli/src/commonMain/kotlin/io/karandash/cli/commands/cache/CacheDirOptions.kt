package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.option
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cache.CacheManager
import io.karandash.cli.CliEnvVars
import io.karandash.cli.pipeline.ConfigLoadStage
import io.karandash.cli.pipeline.ConfigLoader
import io.karandash.cli.pipeline.loadOrReport
import io.karandash.config.Config
import io.karandash.platform.getUserHomeDirectoryString
import kotlinx.io.files.Path

private const val HOME_PREFIX = "~/"

/** Expands a leading `~` or `~/` to the home directory; the one resolver of the `cache` subcommands and of pipeline preparation. */
internal fun expandCacheDir(dir: String): Path =
    Path(if (dir == "~" || dir.startsWith(HOME_PREFIX)) getUserHomeDirectoryString() + dir.substring(1) else dir)

/** The local cache directory: `--cache-dir`, else `cache.dir` of the config, else the default. */
internal fun resolveCacheBaseDir(
    flag: String?,
    configured: String?,
): Path = expandCacheDir(flag ?: configured ?: CacheManager.DEFAULT_BASE_DIR)

/** `--cache-dir` and `--config` of the `cache` subcommands that work on the local cache directory. */
internal class CacheDirOptions : OptionGroup() {
    private val cacheDir by option(
        "--cache-dir",
        help = "Local cache base directory (default: cache.dir of the config, else ${CacheManager.DEFAULT_BASE_DIR})",
    )

    private val configPath by option("--config", "-c", help = "Config file path (default: auto-discover)", envvar = CliEnvVars.CONFIG)

    /** The directory to work on, or null after [report]ing a config that does not load. The config is read only without `--cache-dir`. */
    suspend fun resolve(
        bootstrap: PluginBootstrap,
        report: (String) -> Unit,
    ): Path? {
        val flag = cacheDir
        if (flag != null) return resolveCacheBaseDir(flag, null)
        val config = load(bootstrap, report) ?: return null
        return resolveCacheBaseDir(null, config.cache?.dir)
    }

    /**
     * [resolve] plus the loaded config (for `cache.remote`), from one config load; null after [report]ing a config
     * that does not load. `--cache-dir` without `--config` skips the config, as in [resolve] (the stage is then
     * null), unless [alwaysLoad].
     */
    suspend fun resolveWithStage(
        bootstrap: PluginBootstrap,
        alwaysLoad: Boolean,
        report: (String) -> Unit,
    ): Pair<Path, ConfigLoadStage?>? {
        val flag = cacheDir
        if (!alwaysLoad && flag != null && configPath == null) return resolveCacheBaseDir(flag, null) to null
        val stage = ConfigLoader.forCommand(configPath, bootstrap, bootstrap.logger).loadOrReport(report) ?: return null
        return resolveCacheBaseDir(flag, stage.config.cache?.dir) to stage
    }

    private suspend fun load(
        bootstrap: PluginBootstrap,
        report: (String) -> Unit,
    ): Config? = ConfigLoader.forCommand(configPath, bootstrap, bootstrap.logger).loadOrReport(report)?.config
}
