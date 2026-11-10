package io.karandash.sdk.typed

import io.karandash.api.module.PlatformServiceModule
import io.karandash.sdk.PluginContext
import io.karandash.sdk.config.deserializeConfigReporting
import kotlinx.serialization.json.JsonNull

/**
 * A [PlatformServiceModule] that uses typed configuration.
 *
 * Configuration is deserialized from [PluginContext.pluginConfig] once during [initialize].
 * Implementors override [initializeTyped] instead of [initialize].
 *
 * @param C The typed config class for this module.
 */
public interface TypedPlatformServiceModule<C : Any> :
    PlatformServiceModule,
    TypedModule<C> {
    /**
     * Initialize the comment poster with a typed configuration object.
     *
     * @param context plugin execution context (env vars, logging)
     * @param config typed configuration deserialized from the comment-poster block
     * @return true if initialization succeeded, false to abort
     */
    public suspend fun initializeTyped(
        context: PluginContext,
        config: C
    ): Boolean

    override suspend fun initialize(context: PluginContext): Boolean {
        val (config, error) = deserializeConfigReporting(configSerializer, context.pluginConfig)
        if (error != null && context.pluginConfig !is JsonNull) {
            context.warn("$id: invalid config, using defaults: ${error.lineSequence().first()}")
        }
        return try {
            initializeTyped(context, config)
        } catch (e: IllegalArgumentException) {
            // A wrong-typed credential field (SerializationException) or a rejected base URL is bad config:
            // initialize() reports it with false and never throws (module contract rule 2).
            context.warn("$id: invalid config: ${e.message}")
            false
        }
    }
}
