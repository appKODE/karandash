package io.karandash.sdk.typed

import io.karandash.sdk.PluginContext
import kotlinx.serialization.KSerializer

/**
 * Abstract base class for [TypedFormatModule] implementations.
 *
 * @param C The typed config class.
 */
public abstract class AbstractTypedFormatModule<C : Any>(
    override val configSerializer: KSerializer<C>,
) : TypedFormatModule<C> {
    override suspend fun initialize(context: PluginContext): Boolean = true
}
