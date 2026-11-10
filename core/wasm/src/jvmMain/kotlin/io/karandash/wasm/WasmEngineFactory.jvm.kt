package io.karandash.wasm

/** Creates a WASM engine for JVM targets: Chicory, a pure-Java interpreter. */
actual fun createWasmEngine(wasmBytes: ByteArray): WasmEngine = ChicoryJvmEngine(wasmBytes)

/** Chicory polls the thread interrupt flag, which [GuestCallRunner] sets on timeout: nothing more to interrupt. */
internal actual fun wasmEngineStart(
    wasmBytes: ByteArray,
    imports: WasmHostImports?,
): WasmEngineStart = WasmEngineStart(start = { ChicoryJvmEngine(wasmBytes, imports) }, interrupt = {})

internal actual val wasmEngineSupportsInterrupt: Boolean = true
