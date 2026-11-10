package io.karandash.wasm

/**
 * macOS x64 factory: Chasm 1.7.0, the last Chasm with a macosX64 build, compiled into the binary.
 * Its store cannot be interrupted, so a hanging guest call or start function is abandoned at its timeout
 * rather than stopped (see [ChasmEngine]).
 */
actual fun createWasmEngine(wasmBytes: ByteArray): WasmEngine = ChasmEngine(wasmBytes)

internal actual fun wasmEngineStart(
    wasmBytes: ByteArray,
    imports: WasmHostImports?,
): WasmEngineStart = WasmEngineStart(start = { ChasmEngine(wasmBytes, imports) }, interrupt = {})

internal actual val wasmEngineSupportsInterrupt: Boolean = false
