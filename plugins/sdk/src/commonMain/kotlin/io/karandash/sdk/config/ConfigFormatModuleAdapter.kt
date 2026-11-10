package io.karandash.sdk.config

import io.karandash.sdk.Module
import io.karandash.sdk.ModuleType
import io.karandash.sdk.PluginContext
import io.karandash.sdk.module.ConfigFormatModule

/**
 * Wraps a [ConfigFormatModule] as a [Module] so format parsers participate in
 * the standard plugin lifecycle (registration, loading, discovery).
 *
 * Format parsers are stateless -- [initialize] always returns true and [shutdown] is a no-op.
 */
public open class ConfigFormatModuleAdapter(
    public val delegate: ConfigFormatModule,
) : Module,
    ConfigFormatModule by delegate {
    override val id: String = "io.karandash.config-format.${delegate.formatId}"
    override val name: String = "${delegate.formatId.replaceFirstChar { it.uppercaseChar() }} Config Format Parser"
    override val version: String = "1.7.0"
    override val type: ModuleType = ModuleType.CONFIG_FORMAT
    override val description: String = "Parses .${delegate.fileExtensions.firstOrNull() ?: delegate.formatId} config files."

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun shutdown() {}
}
