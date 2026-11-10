package io.karandash.loader.bridge

import io.karandash.loader.bridge.host.HostServiceDispatcher
import kotlinx.coroutines.runBlocking

/**
 * Platform-agnostic transport for plugin bridge communication.
 * Each runtime (JS/QuickJS, WASM/Chicory+Chasm, JNA, C interop) implements this.
 */
interface BridgeTransport {
    /** The guest's host-service channel; null for a transport that has none (its guests get no host services). */
    val host: HostServiceDispatcher? get() = null

    /** Call a method with string arguments, return JSON string result or null. */
    suspend fun call(
        method: String,
        vararg args: String
    ): String?

    /** [call] for the non-suspend SDK members (`RuleModule.tokensConsumed`, `PipelineExtension.costSummary`, ...). */
    fun callSync(
        method: String,
        vararg args: String
    ): String? {
        // bridge-boundary: a non-suspend SDK member answered by a guest call
        return runBlocking { call(method, *args) }
    }

    /** Call a method with no return value (fire-and-forget). */
    suspend fun callVoid(
        method: String,
        vararg args: String
    )

    /** Read a string property from the plugin. */
    fun getProperty(name: String): String

    /** Read an int property, with default. */
    fun getIntProperty(
        name: String,
        default: Int
    ): Int

    /** Read a boolean property, with default. */
    fun getBoolProperty(
        name: String,
        default: Boolean
    ): Boolean

    /** Check if the plugin implements a given capability/method. */
    fun hasCapability(name: String): Boolean

    /** Log an error from a bridge call. */
    fun logError(
        method: String,
        exception: Exception
    )

    /** Log a line the guest handed the host to log (`logCostSummary`) at info; a transport without a logger drops it. */
    fun logInfo(
        method: String,
        message: String
    ) {
        // default: no logger, dropped
    }
}
