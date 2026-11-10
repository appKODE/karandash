package io.karandash.sdk.typed

import io.karandash.sdk.PluginContext
import kotlinx.serialization.KSerializer

/**
 * Abstract base class for [TypedOutputModule] implementations.
 *
 * @param C The typed config class.
 */
public abstract class AbstractTypedOutputModule<C : Any>(
    override val configSerializer: KSerializer<C>,
) : TypedOutputModule<C> {
    override suspend fun initialize(context: PluginContext): Boolean = true
}
