package io.karandash.cli.compat

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.pipeline.ConfigLoader
import io.karandash.cli.pipeline.ValidationOptions
import io.karandash.config.Config
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Loads a config file through the real CLI [ConfigLoader] and snapshots the effective [Config] plus its warnings. */
internal object CompatLoader {
    class Snapshot(
        val effective: String,
        val warnings: String,
    )

    suspend fun load(configPath: String): Snapshot {
        val warnings = mutableListOf<String>()
        val logger = CapturingLogger(warnings)
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        val effective = try {
            val stage = ConfigLoader(options(configPath, bootstrap), bootstrap, logger).loadConfig()
            KarandashJson.PrettyWithDefaults.encodeToString(Config.serializer(), stage.config)
        } catch (e: Exception) {
            "ERROR ${e::class.simpleName}: ${e.message}"
        } finally {
            bootstrap.shutdown()
        }
        val root = SystemFileSystem.resolve(Path(".")).toString()
        return Snapshot(
            effective = effective.replace(root, "<root>"),
            warnings = warnings.joinToString("\n") { it.replace(root, "<root>") },
        )
    }

    private fun options(
        configPath: String,
        bootstrap: PluginBootstrap,
    ) = ValidationOptions(
        configPathStr = configPath,
        mrDataStr = null,
        mrId = null,
        platform = null,
        project = null,
        prUrl = null,
        format = "json",
        outputPathStr = null,
        quiet = true,
        verbose = false,
        strict = false,
        dryRun = false,
        explain = false,
        fix = false,
        yes = false,
        noAutoDetect = true,
        outputWriter = {},
        sharedBootstrap = bootstrap,
    )

    private class CapturingLogger(
        private val sink: MutableList<String>,
    ) : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            sink += "WARN $message"
        }

        override fun error(message: String) {
            sink += "ERROR $message"
        }
    }
}
