package io.karandash.loader.bridge

import io.karandash.api.support.rethrowCancellation
import io.karandash.loader.GuestExecutor
import io.karandash.sdk.Logger

/**
 * [BridgeTransport] for symbol-based runtimes (WASM, JNA, C interop) that dispatches every logical
 * method through the [BridgeAbi] table, so all three runtimes answer exactly the same operations
 * with the same signatures.
 *
 * Subclasses only know how to invoke one export; result decoding, arity checks and error handling
 * live here. With an [executor], suspend calls run on the guest's own lane under its timeout, and once the
 * guest is hung every call (suspend or not) answers "no answer" at once; property reads stay on the caller.
 * A suspend call made while the host serves this guest's own host call answers "no answer" with a warn: the guest
 * is busy waiting on that host call, so the call would wait on itself.
 */
internal abstract class AbiTableTransport(
    protected val moduleId: String,
    protected val logger: Logger,
    private val runtime: String,
    private val executor: GuestExecutor? = null,
) : BridgeTransport {
    /** True when the guest exports [op]'s symbol (a runtime that cannot tell answers true). */
    protected abstract fun exports(op: BridgeOp): Boolean

    /**
     * Invokes [op] with [args] (already checked against [BridgeOp.params]).
     * Returns the string for [CType.STR] (null for a NULL result), the decimal `int32_t` for
     * [CType.INT]/[CType.BOOL], and null for [CType.VOID].
     */
    protected abstract fun invoke(
        op: BridgeOp,
        args: Array<out String>,
    ): String?

    override suspend fun call(
        method: String,
        vararg args: String,
    ): String? {
        val op = BridgeAbi.find(method) ?: return null
        return decode(op, onGuest(method) { invokeGuarded(method, op, args) })
    }

    /** Non-suspend [call] for synchronous SDK members (e.g. `ConfigFormatModule.parseToJsonElement`); off the guest lane. */
    override fun callSync(
        method: String,
        vararg args: String,
    ): String? {
        val op = BridgeAbi.find(method) ?: return null
        return decode(op, invokeGuarded(method, op, args))
    }

    private fun decode(
        op: BridgeOp,
        raw: String?,
    ): String? = if (op.returns == CType.BOOL) raw?.let { (it.toIntOrNull() == 1).toString() } else raw

    private suspend fun onGuest(
        method: String,
        block: () -> String?,
    ): String? {
        if (host?.isReentrant() == true) {
            logger.warn("[$runtime:$moduleId] $method refused: the plugin is inside its own host call (reentrant)")
            return null
        }
        val lane = executor ?: return block()
        return lane.run(method, block)
    }

    override suspend fun callVoid(
        method: String,
        vararg args: String,
    ) {
        val op = BridgeAbi.find(method) ?: return
        val raw = onGuest(method) { invokeGuarded(method, op, args) }
        if (op.returns == CType.BOOL && raw != null && raw.toIntOrNull() != 1) {
            logger.warn("[$runtime:$moduleId] $method reported failure (status $raw)")
        }
    }

    override fun getProperty(name: String): String {
        val op = BridgeAbi.find(name) ?: return ""
        val raw = invokeGuarded(name, op, emptyArray()) ?: return ""
        return if (op.returns == CType.BOOL) (raw.toIntOrNull() == 1).toString() else raw
    }

    override fun getIntProperty(
        name: String,
        default: Int,
    ): Int {
        val op = BridgeAbi.find(name) ?: return default
        return invokeGuarded(name, op, emptyArray())?.toIntOrNull() ?: default
    }

    override fun getBoolProperty(
        name: String,
        default: Boolean,
    ): Boolean {
        val op = BridgeAbi.find(name) ?: return default
        val raw = invokeGuarded(name, op, emptyArray()) ?: return default
        return if (op.returns == CType.STR) raw.toBooleanStrictOrNull() ?: default else raw.toIntOrNull() == 1
    }

    override fun hasCapability(name: String): Boolean = BridgeAbi.find(name)?.let(::exports) ?: false

    override fun logError(
        method: String,
        exception: Exception,
    ) {
        logger.warn("[$runtime:$moduleId] $method failed: ${exception.message}")
    }

    override fun logInfo(
        method: String,
        message: String,
    ) {
        logger.info("[$runtime:$moduleId] $message")
    }

    @Suppress("TooGenericExceptionCaught") // boundary: guest runtime call (WASM trap, JNA, C interop)
    private fun invokeGuarded(
        method: String,
        op: BridgeOp,
        args: Array<out String>,
    ): String? {
        if (args.size != op.params.size) {
            logger.warn("[$runtime:$moduleId] $method called with ${args.size} argument(s), ABI expects ${op.params.size}")
            return null
        }
        if (executor?.isHung == true || !exports(op)) return null
        return try {
            invoke(op, args)
        } catch (e: Exception) {
            // boundary: guest plugin bridge call
            e.rethrowCancellation()
            logError(method, e)
            null
        }
    }
}
