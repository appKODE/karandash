package io.karandash.wasm

/**
 * Abstraction over a WASM runtime instance.
 *
 * Implementations: Chicory (JVM) and Chasm (native targets); both are interpreters with no
 * system library.
 * Created via [createWasmEngine].
 */
interface WasmEngine {
    /**
     * Call an exported i32-returning function by name.
     * All arguments must be i32; the return value is i32.
     */
    fun callI32Array(
        funcName: String,
        args: IntArray,
    ): Int

    /** [callI32Array] with the arguments listed inline. */
    fun callI32(
        funcName: String,
        vararg args: Int,
    ): Int = callI32Array(funcName, args)

    /**
     * Read bytes from the WASM module's linear memory.
     */
    fun readMemory(
        offset: Int,
        length: Int,
    ): ByteArray

    /**
     * Write bytes into the WASM module's linear memory at [offset].
     * The caller must have allocated the memory (e.g. via karandash_alloc).
     */
    fun writeMemory(
        offset: Int,
        data: ByteArray,
    )

    /**
     * Release all WASM runtime resources.
     */
    fun close()

    /**
     * Ask a call running on another thread to stop; it then fails. A no-op where the runtime stops on a
     * thread interrupt instead (Chicory) or when no call is running.
     */
    fun interrupt() = Unit

    /** `true` once a guest call timed out and the engine stopped accepting calls (see the timed `createWasmEngine`). */
    val isAbandoned: Boolean get() = false
}

/**
 * Platform factory -- creates the appropriate WasmEngine implementation.
 * JVM: Chicory.
 * Native: Chasm 2.2.0; macOS x64 on Chasm 1.7.0, the last release with a build for it (no store interrupt).
 *
 * Calls on the returned engine have no time limit; production callers use the overload taking a call timeout.
 */
expect fun createWasmEngine(wasmBytes: ByteArray): WasmEngine

/**
 * Engine construction split from its interrupt, so a module whose start function hangs can be stopped before
 * any [WasmEngine] exists: [start] runs on the guest thread, [interrupt] from the caller after a timeout.
 */
internal class WasmEngineStart(
    val start: () -> WasmEngine,
    val interrupt: () -> Unit,
)

/**
 * [WasmEngineStart] for [wasmBytes]: Chasm stops on an interrupt of its store, Chicory on a thread interrupt.
 * With [imports] the guest may import the host-service functions ([WasmHostImports]).
 */
internal expect fun wasmEngineStart(
    wasmBytes: ByteArray,
    imports: WasmHostImports? = null,
): WasmEngineStart

/**
 * Whether [WasmEngine.interrupt] and [WasmEngineStart.interrupt] stop a running guest. `false` only on macOS x64
 * (Chasm 1.7.0): there a timed-out guest is abandoned and its thread keeps running until the process exits.
 */
internal expect val wasmEngineSupportsInterrupt: Boolean

/** Public view of [wasmEngineSupportsInterrupt], for the loader's timeout watchdog. */
val wasmEngineInterruptsGuests: Boolean get() = wasmEngineSupportsInterrupt
