package io.karandash.wasm

import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.InvalidException
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.ExternalType
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.MemoryLimits
import com.dylibso.chicory.wasm.types.ValType
import kotlin.math.min

/**
 * JVM implementation of [WasmEngine] using Chicory, a pure-Java WASM runtime.
 *
 * Requires zero native dependencies -- no JNI, no JNA, no system-installed libraries.
 * This is the only WASM engine on JVM targets.
 */
internal class ChicoryJvmEngine(
    wasmBytes: ByteArray,
    imports: WasmHostImports? = null,
) : WasmEngine {
    private val instance: Instance

    /** Exported function name to function index, so a call hands its argument array to the machine as is. */
    private val functions: Map<String, Int>

    init {
        val module = Parser.parse(wasmBytes)
        val builder = Instance.builder(module).withMemoryLimits(cappedMemoryLimits(module))
        if (imports != null) builder.withImportValues(hostImportValues(imports))
        instance = builder.build()
        val exports = module.exportSection()
        functions =
            (0 until exports.exportCount())
                .map { exports.getExport(it) }
                .filter { it.exportType() == ExternalType.FUNCTION }
                .associate { it.name() to it.index() }
    }

    override fun callI32Array(
        funcName: String,
        args: IntArray,
    ): Int {
        val index = functions[funcName] ?: throw InvalidException("Unknown export with name $funcName")
        val longArgs = LongArray(args.size) { args[it].toLong() }

        val results: LongArray? = instance.machine.call(index, longArgs)
        // Chicory returns null (not an empty array) for a void export such as Rust's `karandash_dealloc`.
        return results?.firstOrNull()?.toInt() ?: 0
    }

    override fun readMemory(
        offset: Int,
        length: Int,
    ): ByteArray = instance.memory().readBytes(offset, length)

    override fun writeMemory(
        offset: Int,
        data: ByteArray,
    ) {
        instance.memory().write(offset, data)
    }

    private companion object {
        /** [WasmHostImports] as Chicory host functions; a module that does not import them ignores them. */
        fun hostImportValues(imports: WasmHostImports): ImportValues {
            val i32 = ValType.I32
            val call =
                HostFunction(
                    WasmHostImports.MODULE,
                    WasmHostImports.HOST_CALL,
                    FunctionType.of(List(WasmHostImports.HOST_CALL_PARAMS) { i32 }, listOf(i32)),
                ) { instance, args ->
                    val read = { offset: Int, length: Int -> instance.memory().readBytes(offset, length) }
                    longArrayOf(imports.call(read, IntArray(args.size) { args[it].toInt() }).toLong())
                }
            val response =
                HostFunction(
                    WasmHostImports.MODULE,
                    WasmHostImports.HOST_RESPONSE,
                    FunctionType.of(listOf(i32), listOf(i32))
                ) { instance, args ->
                    val write = { offset: Int, data: ByteArray -> instance.memory().write(offset, data) }
                    longArrayOf(imports.response(write, args[0].toInt()).toLong())
                }
            return ImportValues.builder().addFunction(call, response).build()
        }

        /** Linear-memory cap in 64 KiB pages (64 MiB), so a guest cannot grow without bound. */
        const val MAX_PAGES = 1024

        /** Keeps the module's declared minimum, caps its maximum at [MAX_PAGES]; rejects a minimum above the cap. */
        fun cappedMemoryLimits(module: WasmModule): MemoryLimits {
            val declared =
                module
                    .memorySection()
                    .orElse(null)
                    ?.takeIf { it.memoryCount() > 0 }
                    ?.getMemory(0)
                    ?.limits()
                    ?: return MemoryLimits(1, MAX_PAGES)
            require(declared.initialPages() <= MAX_PAGES) {
                "WASM module declares ${declared.initialPages()} initial memory pages, the limit is $MAX_PAGES"
            }
            return MemoryLimits(declared.initialPages(), min(declared.maximumPages(), MAX_PAGES))
        }
    }

    override fun close() {
        // Chicory instances are GC'd; no explicit close needed.
    }
}
