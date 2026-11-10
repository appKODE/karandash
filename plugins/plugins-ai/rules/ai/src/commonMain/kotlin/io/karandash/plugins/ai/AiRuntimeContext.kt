package io.karandash.plugins.ai

import io.karandash.api.PluginContext
import io.karandash.api.cache.CacheProvider
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Scoped runtime context extracted from [PluginContext] during [AiRuleModule.initialize].
 *
 * Holds only the fields that the AI subsystem actually needs, avoiding storing the
 * full [PluginContext] reference (which is forbidden by module lifecycle rules).
 *
 * The [logger] delegates `warn`/`info` calls through the original [PluginContext.warn]
 * and [PluginContext.log] methods so that host-side logging callbacks are honoured.
 */
internal class AiRuntimeContext(
    val logger: Logger,
    val pluginConfig: JsonElement,
    val configDir: String,
    val cacheProvider: CacheProvider,
    private val envAccessor: (String) -> String?,
    private val logCallback: (String) -> Unit = { logger.info(it) },
    private val warnCallback: (String) -> Unit = { logger.warn(it) },
) {
    fun env(name: String): String? = envAccessor(name)

    fun log(message: String) {
        logCallback(message)
    }

    fun warn(message: String) {
        warnCallback(message)
    }

    companion object {
        fun from(ctx: PluginContext): AiRuntimeContext {
            val bridgedLogger = ContextBridgeLogger(ctx.logger, ctx::warn)
            return AiRuntimeContext(
                logger = bridgedLogger,
                pluginConfig = ctx.pluginConfig,
                configDir = ctx.configDir,
                cacheProvider = ctx.cacheProvider,
                envAccessor = { name -> ctx.env(name) },
                logCallback = { msg -> ctx.log(msg) },
                warnCallback = { msg -> ctx.warn(msg) },
            )
        }

        val NOOP: AiRuntimeContext = AiRuntimeContext(
            logger = NoOpLogger,
            pluginConfig = JsonObject(emptyMap()),
            configDir = "",
            cacheProvider = CacheProvider.NOOP,
            envAccessor = { null },
        )
    }
}

/**
 * Logger that delegates `debug`/`info`/`error` to [delegate] but routes `warn`
 * through the [PluginContext.warn] callback so host-side logging hooks receive
 * warning-level messages from extracted subsystem classes.
 */
private class ContextBridgeLogger(
    private val delegate: Logger,
    private val warnCallback: (String) -> Unit,
) : Logger {
    override fun debug(message: String) = delegate.debug(message)

    override fun info(message: String) = delegate.info(message)

    override fun warn(message: String) = warnCallback(message)

    override fun error(message: String) = delegate.error(message)

    override fun error(
        message: String,
        throwable: Throwable,
    ) = delegate.error(message, throwable)

    override val isDebugEnabled: Boolean get() = delegate.isDebugEnabled
}
