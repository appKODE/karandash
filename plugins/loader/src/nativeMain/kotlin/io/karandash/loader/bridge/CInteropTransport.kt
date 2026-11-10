@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.loader.bridge

import io.karandash.loader.GuestExecutor
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.platform.closeNativeLibrary
import io.karandash.platform.findNativeSymbol
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong

/**
 * [BridgeTransport] for native plugins loaded via C interop (dlopen).
 *
 * Every op resolves through [BridgeAbi] to its C symbol. String results are read via `toKString()`
 * and freed via `karandash_free_string`.
 *
 * Calls go through a function pointer typed only by arity, with every argument and the result widened
 * to a 64-bit integer: on all supported targets (arm64, x86-64 SysV and Windows x64) `const char*`
 * and `int32_t` arguments both travel in 64-bit integer registers or 8-byte stack slots, and an
 * `int32_t` result is the low half of the pointer-sized return register.
 */
internal class CInteropTransport(
    private val handle: COpaquePointer,
    moduleId: String,
    logger: Logger = NoOpLogger,
    executor: GuestExecutor? = null,
    override val host: HostServiceDispatcher? = null,
) : AbiTableTransport(moduleId, logger, "Native", executor) {
    override fun exports(op: BridgeOp): Boolean = findNativeSymbol(handle, op.symbol) != null

    override fun invoke(
        op: BridgeOp,
        args: Array<out String>,
    ): String? {
        val fn = checkNotNull(findNativeSymbol(handle, op.symbol)) { "Missing ${op.symbol}" }
        val raw = memScoped {
            val words = op.params.zip(args).map { (param, value) ->
                if (param.startsWith('#')) BridgeAbi.intArg(value).toLong() else value.cstr.getPointer(this).toLong()
            }
            callByArity(fn, words)
        }
        return when (op.returns) {
            CType.STR -> raw.toCPointer<ByteVar>()?.let { ptr -> ptr.toKString().also { freeString(ptr) } }
            CType.INT, CType.BOOL -> raw.toInt().toString()
            CType.VOID -> null
        }
    }

    /**
     * Closes the underlying native library handle.
     * Called from [io.karandash.loader.bridge.TransportModuleBridge.close] when the loader unloads the module.
     */
    fun closeHandle() {
        closeNativeLibrary(handle)
    }

    /**
     * Reads the module ID directly from the native library symbol `karandash_module_id`.
     * Used during bootstrap before the transport's module ID is known.
     */
    internal fun readNativeModuleId(): String = getProperty(BridgeConstants.PROP_ID)

    // looked up once: every string result is freed through it
    private val freeStringFn by lazy {
        findNativeSymbol(handle, FREE_STRING)?.reinterpret<CFunction<(CPointer<ByteVar>?) -> Unit>>()
    }

    private fun freeString(ptr: CPointer<ByteVar>) {
        freeStringFn?.invoke(ptr)
    }

    private companion object {
        private const val FREE_STRING = "karandash_free_string"

        /** Parameter count of the widest C ABI op (`karandash_post_inline_comment`). */
        private const val WIDEST_ARITY = 7
        private const val ARITY_5 = 5

        /** Covers every arity in [BridgeAbi.OPS]; the ops plugin compile test calls each one. */
        fun callByArity(
            fn: COpaquePointer,
            a: List<Long>,
        ): Long =
            when (a.size) {
                0 -> {
                    fn.reinterpret<CFunction<() -> Long>>()()
                }

                1 -> {
                    fn.reinterpret<CFunction<(Long) -> Long>>()(a[0])
                }

                2 -> {
                    fn.reinterpret<CFunction<(Long, Long) -> Long>>()(a[0], a[1])
                }

                3 -> {
                    fn.reinterpret<CFunction<(Long, Long, Long) -> Long>>()(a[0], a[1], a[2])
                }

                4 -> {
                    fn.reinterpret<CFunction<(Long, Long, Long, Long) -> Long>>()(a[0], a[1], a[2], a[3])
                }

                ARITY_5 -> {
                    fn.reinterpret<CFunction<(Long, Long, Long, Long, Long) -> Long>>()(a[0], a[1], a[2], a[3], a[4])
                }

                WIDEST_ARITY -> {
                    val p = a.iterator()
                    fn.reinterpret<CFunction<(Long, Long, Long, Long, Long, Long, Long) -> Long>>()(
                        p.next(),
                        p.next(),
                        p.next(),
                        p.next(),
                        p.next(),
                        p.next(),
                        p.next(),
                    )
                }

                else -> {
                    error("No C interop call shape for ${a.size} parameters")
                }
            }
    }
}
