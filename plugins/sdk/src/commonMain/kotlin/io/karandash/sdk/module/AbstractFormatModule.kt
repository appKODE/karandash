package io.karandash.sdk.module

import io.karandash.sdk.PluginContext

/**
 * Abstract base class for [FormatModule] implementations that do not use typed config.
 *
 * Provides a default [initialize] implementation that returns `true`.
 * For modules with typed configuration, use [AbstractTypedFormatModule][io.karandash.sdk.typed.AbstractTypedFormatModule] instead.
 */
public abstract class AbstractFormatModule : FormatModule {
    override suspend fun initialize(context: PluginContext): Boolean = true
}
