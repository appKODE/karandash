package io.karandash.sdk.typed

import io.karandash.sdk.PluginContext
import kotlinx.serialization.KSerializer

/**
 * Abstract base class for [TypedReporterModule] implementations.
 *
 * @param C The typed config class.
 */
public abstract class AbstractTypedReporterModule<C : Any>(
    override val configSerializer: KSerializer<C>,
) : TypedReporterModule<C> {
    override suspend fun initialize(context: PluginContext): Boolean = true
}
