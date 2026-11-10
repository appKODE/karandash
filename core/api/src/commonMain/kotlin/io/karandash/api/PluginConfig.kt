package io.karandash.api

import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule

/**
 * Marker interface for all plugin config data classes.
 *
 * Each module type has a sub-interface ([RuleConfig], [ReporterConfig], etc.)
 * that config data classes implement. This enables generic deserialization
 * and schema extraction via [kotlinx.serialization.SerialDescriptor].
 */
public interface PluginConfig

/** Config marker for [RuleModule] implementations. */
public interface RuleConfig : PluginConfig

/** Config marker for [ReporterModule] implementations. */
public interface ReporterConfig : PluginConfig

/** Config marker for [PlatformServiceModule] implementations. */
public interface PlatformServiceConfig : PluginConfig

/** Config marker for [PipelineExtension] implementations. */
public interface PipelineExtensionConfig : PluginConfig
