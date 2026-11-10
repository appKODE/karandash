package io.karandash.sdk.module

import io.karandash.sdk.PluginContext

/**
 * Abstract base class for [ProfileModule] implementations.
 *
 * Provides default implementations for:
 * - [id]: derived from [profileId] as `"io.karandash.profile.<profileId>"`
 * - [version]: defaults to `"1.0.0"`
 * - [initialize]: returns `true` (no-op)
 *
 * Subclasses must still implement [profileId], [name], [summary], and [ruleConfigs].
 */
public abstract class AbstractProfileModule : ProfileModule {
    override val id: String get() = "io.karandash.profile.$profileId"
    override val version: String = "1.7.0"

    override suspend fun initialize(context: PluginContext): Boolean = true
}
