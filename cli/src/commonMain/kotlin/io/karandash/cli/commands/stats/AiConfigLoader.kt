package io.karandash.cli.commands.stats

import io.karandash.api.errors.ConfigException
import io.karandash.api.exception.ConfigParseException
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.withHint
import io.karandash.cli.pipeline.ConfigLoader
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonElement

/**
 * The merged, normalized `ai:` block of the discovered config, loaded the way `validate` loads it, so
 * `stats` reads the directory the AI pipeline writes to. `null` on zero-config or a config that fails to load.
 */
internal suspend fun loadAiConfig(
    bootstrap: PluginBootstrap,
    configPath: String? = null,
): JsonElement? {
    val notLoaded = { message: String?, hint: String? ->
        bootstrap.logger.warn("config not loaded, using the default statistics directory: ${withHint(message, hint)}")
        null
    }
    return try {
        val loaded = ConfigLoader.forCommand(configPath, bootstrap).loadConfig()
        if (loaded.usingZeroConfig) null else loaded.config.ai
    } catch (e: ConfigException) {
        notLoaded(e.message, e.hint)
    } catch (e: ConfigParseException) {
        notLoaded(e.message, e.hint)
    } catch (e: IOException) {
        notLoaded(e.message, null)
    }
}
