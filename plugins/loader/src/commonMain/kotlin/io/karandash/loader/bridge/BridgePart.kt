package io.karandash.loader.bridge

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.support.rethrowCancellation

/**
 * The shared core of every per-type bridge part: identity read through [transport], its [type], and no lifecycle
 * of its own (the guest is initialized once by the bridge that owns it).
 */
internal abstract class BridgePart(
    protected val transport: BridgeTransport,
) : Module {
    /** Declared by each part: the SDK sub-interfaces each carry a default, so Kotlin needs the override spelled out. */
    abstract override val type: ModuleType

    override val id: String get() = transport.getProperty(BridgeConstants.PROP_ID)
    override val name: String get() = transport.getProperty(BridgeConstants.PROP_NAME)
    override val version: String get() = transport.getProperty(BridgeConstants.PROP_VERSION)

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun shutdown() {}
}

/**
 * Runs [block], a call into the guest; an exception is logged at warn through [BridgeTransport.logError] and reads as
 * [fallback]. Cancellation is rethrown.
 */
internal inline fun <T> BridgeTransport.guarded(
    method: String,
    fallback: T,
    block: () -> T,
): T =
    try {
        block()
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception
    ) {
        // boundary: plugin bridge
        e.rethrowCancellation()
        logError(method, e)
        fallback
    }
