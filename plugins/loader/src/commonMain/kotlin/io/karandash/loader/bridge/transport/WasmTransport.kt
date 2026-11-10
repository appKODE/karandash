package io.karandash.loader.bridge.transport

import io.karandash.loader.bridge.AbiTableTransport
import io.karandash.loader.bridge.BridgeAbi
import io.karandash.loader.bridge.BridgeOp
import io.karandash.loader.bridge.CType
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.loader.wasm.WasmAbi
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.wasm.WasmEngine
import io.karandash.wasm.WasmMemoryProtocol

/**
 * [io.karandash.loader.bridge.BridgeTransport] for WASM plugins running in Chicory/Chasm.
 *
 * Every op resolves through [BridgeAbi] to its [BridgeOp.wasm] export. String parameters are written
 * into linear memory and passed as (ptr, len); `#` parameters are passed as a raw i32. A string result
 * is a pointer to a length-prefixed UTF-8 buffer (0 means null); int/bool results are the raw i32.
 */
internal class WasmTransport(
    /** Read per call: the bridge swaps its engine when it re-initializes after shutdown. */
    private val engineProvider: () -> WasmEngine,
    moduleId: String,
    logger: Logger = NoOpLogger,
    /** Export names read from the binary; null when unknown. */
    private val exportNames: Set<String>? = null,
    /** The guest's `env.karandash_host_call` channel (the engine's import calls it). */
    override val host: HostServiceDispatcher? = null,
) : AbiTableTransport(moduleId, logger, "WASM") {
    private val engine: WasmEngine get() = engineProvider()

    /** Answered from the binary's export section; a guest whose exports are unknown claims no capability. */
    override fun exports(op: BridgeOp): Boolean = exportNames?.contains(op.wasm) ?: false

    override fun invoke(
        op: BridgeOp,
        args: Array<out String>,
    ): String? {
        val pairs = op.params.zip(args)
        val result = callI32(op.wasm, pairs.map { it.second }, raw = pairs.map { (param, _) -> param.startsWith('#') })
        return when (op.returns) {
            CType.STR -> readString(result)?.ifEmpty { null }
            CType.INT, CType.BOOL -> result.toString()
            CType.VOID -> null
        }
    }

    /**
     * Calls [export] with each of [args] written into guest memory as `ptr, len`, or passed as a raw i32 where [raw]
     * says so ([BridgeAbi.intArg]); the written strings are freed even when the guest traps.
     */
    fun callI32(
        export: String,
        args: List<String>,
        raw: List<Boolean> = emptyList(),
    ): Int {
        val engine = engine
        val written = mutableListOf<Pair<Int, Int>>()
        val callArgs = args
            .flatMapIndexed { index, value ->
                if (raw.getOrElse(index) { false }) {
                    listOf(BridgeAbi.intArg(value))
                } else {
                    WasmMemoryProtocol.writeString(engine, value).also { written += it }.toList()
                }
            }.toIntArray()
        return try {
            engine.callI32Array(export, callArgs)
        } finally {
            written.forEach { (ptr, len) -> engine.callI32(WasmAbi.DEALLOC, ptr, len) }
        }
    }

    /** The length-prefixed string a guest returned at [ptr]; null for a null pointer. */
    fun readString(ptr: Int): String? = if (ptr == 0) null else WasmMemoryProtocol.readLengthPrefixedString(engine, ptr)
}
