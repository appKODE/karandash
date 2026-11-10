package io.karandash.sdk.module

import io.karandash.sdk.PluginContext

/**
 * Abstract base class for [OutputModule] implementations that do not use typed config.
 *
 * Provides a default [initialize] implementation that returns `true`.
 * For modules with typed configuration, use [AbstractTypedOutputModule][io.karandash.sdk.typed.AbstractTypedOutputModule] instead.
 */
public abstract class AbstractOutputModule : OutputModule {
    override suspend fun initialize(context: PluginContext): Boolean = true
}
