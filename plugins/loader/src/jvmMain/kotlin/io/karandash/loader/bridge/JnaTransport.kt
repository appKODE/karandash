package io.karandash.loader.bridge

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import io.karandash.loader.GuestExecutor
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.loader.native.JnaHostChannel
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger

/** Calls the C [symbol] with [args] (each a `String` or an `Int`) and reads the result as [returns]. */
internal typealias JnaCall = (symbol: String, returns: Class<*>, args: Array<Any>) -> Any?

/**
 * [BridgeTransport] for native plugins loaded via JNA on the JVM.
 *
 * Every op resolves through [BridgeAbi] to its C symbol ([BridgeOp.symbol]) and is invoked by arity and
 * [BridgeOp.returns], like the Kotlin/Native `CInteropTransport`: no hand-written JNA interface to keep in sync.
 * String results are read as UTF-8 and freed via `karandash_free_string`. A symbol missing from the loaded library
 * is not exported ([hasSymbol]); one that still surfaces as `UnsatisfiedLinkError` at call time reads as a null result.
 */
internal class JnaTransport(
    private val call: JnaCall,
    moduleId: String,
    logger: Logger = NoOpLogger,
    private val hasSymbol: (String) -> Boolean = { true },
    executor: GuestExecutor? = null,
    private val hostChannel: JnaHostChannel? = null,
) : AbiTableTransport(moduleId, logger, "JNA", executor) {
    /** The guest's host-service channel; [hostChannel] also keeps its JNA callbacks reachable. */
    override val host: HostServiceDispatcher? get() = hostChannel?.host

    /** Whether the loaded library really exports the op's symbol ([hasSymbol], looked up by the loader). */
    override fun exports(op: BridgeOp): Boolean = hasSymbol(op.symbol)

    override fun invoke(
        op: BridgeOp,
        args: Array<out String>,
    ): String? {
        val jnaArgs = op.params.zip(args).map<Pair<String, String>, Any> { (param, value) ->
            if (param.startsWith('#')) BridgeAbi.intArg(value) else value
        }
        val result = try {
            call(op.symbol, returnType(op.returns), jnaArgs.toTypedArray())
        } catch (e: UnsatisfiedLinkError) {
            logger.debug("[JNA:$moduleId] ${op.symbol} symbol missing: ${e.message}")
            return null
        }
        return when (result) {
            is Pointer -> result.getString(0, CHARSET_UTF8).also { call(FREE_STRING, Void.TYPE, arrayOf(result)) }
            is Int -> result.toString()
            else -> null
        }
    }

    companion object {
        private const val CHARSET_UTF8 = "UTF-8"
        private const val FREE_STRING = "karandash_free_string"

        /** [JnaCall] over [library]'s exports; string arguments use the library's encoding option. */
        fun callsInto(library: NativeLibrary): JnaCall = { symbol, returns, args -> library.getFunction(symbol).invoke(returns, args) }

        private fun returnType(type: CType): Class<*> =
            when (type) {
                CType.STR -> Pointer::class.java
                CType.INT, CType.BOOL -> Int::class.javaPrimitiveType ?: Int::class.java
                CType.VOID -> Void.TYPE
            }
    }
}
