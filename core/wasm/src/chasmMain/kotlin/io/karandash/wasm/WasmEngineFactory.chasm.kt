package io.karandash.wasm

import io.github.charlietap.chasm.config.StoreConfig
import io.github.charlietap.chasm.embedding.interrupt
import io.github.charlietap.chasm.embedding.store

/** Native factory: Chasm, a pure-Kotlin interpreter compiled into the binary (no system library). */
actual fun createWasmEngine(wasmBytes: ByteArray): WasmEngine = ChasmEngine(wasmBytes)

/** The store exists before the module is instantiated, so a start function that hangs can be interrupted. */
internal actual fun wasmEngineStart(
    wasmBytes: ByteArray,
    imports: WasmHostImports?,
): WasmEngineStart {
    val store = store(StoreConfig(interruptible = true))
    return WasmEngineStart(start = { ChasmEngine(wasmBytes, store, imports) }, interrupt = { interrupt(store) })
}

internal actual val wasmEngineSupportsInterrupt: Boolean = true
