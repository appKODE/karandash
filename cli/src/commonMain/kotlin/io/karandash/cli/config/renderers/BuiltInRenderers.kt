package io.karandash.cli.config.renderers

import io.karandash.config.pipeline.ConfigFormatRegistry

/**
 * Registers the built-in config renderers (YAML, JSON, KTS) with the given
 * [ConfigFormatRegistry].
 *
 * Called via [io.karandash.bootstrap.PluginBootstrap.onFormatRegistryCreated]
 * so that renderers are available immediately after plugin loading completes.
 */
internal fun registerBuiltInRenderers(registry: ConfigFormatRegistry) {
    registry.registerRenderer(YamlConfigRenderer())
    registry.registerRenderer(JsonConfigRenderer())
    registry.registerRenderer(KtsConfigRenderer())
}
