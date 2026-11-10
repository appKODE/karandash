package io.karandash.wasm

import io.github.charlietap.chasm.config.StoreConfig
import io.github.charlietap.chasm.embedding.dropInstance
import io.github.charlietap.chasm.embedding.dropStore
import io.github.charlietap.chasm.embedding.function
import io.github.charlietap.chasm.embedding.instance
import io.github.charlietap.chasm.embedding.interrupt
import io.github.charlietap.chasm.embedding.invoke
import io.github.charlietap.chasm.embedding.memory.readBytes
import io.github.charlietap.chasm.embedding.memory.writeBytes
import io.github.charlietap.chasm.embedding.module
import io.github.charlietap.chasm.embedding.moduleInfo
import io.github.charlietap.chasm.embedding.shapes.ChasmResult
import io.github.charlietap.chasm.embedding.shapes.Function
import io.github.charlietap.chasm.embedding.shapes.Import
import io.github.charlietap.chasm.embedding.shapes.Memory
import io.github.charlietap.chasm.embedding.shapes.Store
import io.github.charlietap.chasm.embedding.store
import io.github.charlietap.chasm.host.HostFunction
import io.github.charlietap.chasm.host.HostModuleInstance
import io.github.charlietap.chasm.host.HostResources
import io.github.charlietap.chasm.host.HostStack
import io.github.charlietap.chasm.host.readI32
import io.github.charlietap.chasm.host.writeI32
import io.github.charlietap.chasm.runtime.value.NumberValue
import io.github.charlietap.chasm.type.FunctionType
import io.github.charlietap.chasm.type.NumberType
import io.github.charlietap.chasm.type.ResultType
import io.github.charlietap.chasm.type.ValueType

/**
 * [WasmEngine] backed by Chasm, a pure-Kotlin interpreter: the engine on native targets (no system library).
 *
 * The store is interruptible, so a call that overruns its time limit is stopped by [interrupt] instead of
 * spinning on its thread. The only imports served are the host-service functions ([WasmHostImports]), when
 * [imports] is given; other imports (e.g. WASI) fail instantiation.
 */
internal class ChasmEngine(
    wasmBytes: ByteArray,
    private val store: Store = store(StoreConfig(interruptible = true)),
    imports: WasmHostImports? = null,
) : WasmEngine {
    private val instance =
        module(wasmBytes).orFail("decode").let { module ->
            val declared = moduleInfo(module).imports.map { it.moduleName to it.entityName }
            instance(store, module, imports?.let { hostImports(it, declared) }.orEmpty()).orFail("instantiate")
        }
    private val functions: Map<String, Function> =
        instance.exports.mapNotNull { export -> (export.value as? Function)?.let { export.name to it } }.toMap()
    private val memory: Memory? = instance.exports.firstNotNullOfOrNull { it.value as? Memory }

    override fun callI32Array(
        funcName: String,
        args: IntArray,
    ): Int {
        val function = functions[funcName] ?: error("chasm: function '$funcName' not found")
        val results = invoke(store, instance, function, args.map { NumberValue.I32(it) }).orFail("call '$funcName'")
        // A void export (Rust's `karandash_dealloc`) yields no results.
        return (results.firstOrNull() as? NumberValue.I32)?.value ?: 0
    }

    override fun readMemory(
        offset: Int,
        length: Int,
    ): ByteArray = readBytes(store, linearMemory(), ByteArray(length), offset, length).orFail("read memory")

    override fun writeMemory(
        offset: Int,
        data: ByteArray,
    ) {
        writeBytes(store, linearMemory(), offset, data).orFail("write memory")
    }

    override fun interrupt() {
        interrupt(store)
    }

    override fun close() {
        dropInstance(store, instance)
        dropStore(store)
    }

    /**
     * The [WasmHostImports] functions the module declares, in its import order (Chasm matches the list against the
     * module's imports, so an extra entry would fail instantiation).
     */
    private fun hostImports(
        imports: WasmHostImports,
        declared: List<Pair<String, String>>,
    ): List<Import> =
        declared.mapNotNull { (module, name) ->
            when {
                module != WasmHostImports.MODULE -> {
                    null
                }

                name == WasmHostImports.HOST_CALL -> {
                    hostImport(name, WasmHostImports.HOST_CALL_PARAMS) { imports.call(::readMemory, it) }
                }

                name == WasmHostImports.HOST_RESPONSE -> {
                    hostImport(name, 1) { imports.response(::writeMemory, it[0]) }
                }

                else -> {
                    null
                }
            }
        }

    private fun hostImport(
        name: String,
        count: Int,
        body: (IntArray) -> Int,
    ): Import {
        val i32 = ValueType.Number(NumberType.I32)
        val type = FunctionType(ResultType(List(count) { i32 }), ResultType(listOf(i32)))
        val host =
            object : HostFunction {
                context(stack: HostStack, module: HostModuleInstance, resources: HostResources)
                override fun invoke(
                    params: Int,
                    results: Int,
                ) {
                    // read every parameter before writing the result: the two stack ranges may overlap
                    val args = IntArray(count) { params.readI32(it) }
                    results.writeI32(0, body(args))
                }
            }
        return Import(WasmHostImports.MODULE, name, function(store, type, host))
    }

    private fun linearMemory(): Memory = memory ?: error("chasm: module exports no linear memory")

    private fun <S> ChasmResult<S, *>.orFail(what: String): S =
        when (this) {
            is ChasmResult.Success -> result
            is ChasmResult.Error -> error("chasm: $what failed: ${error.error}")
        }
}
