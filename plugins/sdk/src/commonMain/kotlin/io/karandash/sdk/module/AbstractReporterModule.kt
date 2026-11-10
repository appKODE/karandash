package io.karandash.sdk.module

import io.karandash.sdk.PluginContext

/**
 * Abstract base class for [ReporterModule] implementations that do not use typed config.
 *
 * Provides a default [initialize] implementation that returns `true`, allowing
 * subclasses to skip initialization when no setup is required.
 *
 * For modules with typed configuration, use [AbstractTypedReporterModule] instead.
 *
 * Usage:
 * ```kotlin
 * class MyReporterModule : AbstractReporterModule() {
 *     override val id = "my-reporter"
 *     override val name = "My Reporter"
 *     override val version = "1.7.0"
 *     override val formatId = "my-format"
 *     override suspend fun report(findings: List<Finding>, passed: Boolean, config: JsonElement) = null
 * }
 * ```
 */
public abstract class AbstractReporterModule : ReporterModule {
    override suspend fun initialize(context: PluginContext): Boolean = true
}
