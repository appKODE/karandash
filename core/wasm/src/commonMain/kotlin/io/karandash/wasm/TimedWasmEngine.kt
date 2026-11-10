package io.karandash.wasm

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration

/**
 * Runs guest code on a separate thread so the caller can stop waiting after a deadline.
 *
 * JVM: a shared daemon pool; on timeout the guest thread is interrupted, which stops Chicory.
 * Native: one K/N worker per engine; on timeout the engine's [WasmEngine.interrupt] stops Chasm.
 */
internal expect class GuestCallRunner() {
    /**
     * Result of [block], or `null` when it did not finish within [timeout] plus [extension], read while waiting:
     * time the guest spent waiting on the host does not count.
     */
    fun <T> runWithin(
        timeout: Duration,
        extension: () -> Duration = { Duration.ZERO },
        block: () -> T,
    ): Result<T>?

    fun close()
}

/** Runs [block] on the guest thread and carries its failure back to the caller, which rethrows it. */
@Suppress("TooGenericExceptionCaught") // boundary: any guest failure is rethrown on the calling thread
internal fun <T> captureGuestCall(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: Throwable) {
        // boundary: transported to the caller, not swallowed
        Result.failure(e)
    }

/**
 * Creates a [WasmEngine] whose construction and every [WasmEngine.callI32] must finish within
 * [callTimeout]. On the first timeout [onTimeout] gets a user-facing message, the call throws, and
 * the engine is abandoned: later calls fail fast and [WasmEngine.close] leaves the still-running
 * runtime alone (freeing it under a running guest would crash the host).
 *
 * The guest may import `env.karandash_host_call` ([WasmHostImports]), served by [hostCall] (by default every
 * service answers `unsupported`); time spent serving it extends the deadline of the call that made it.
 */
fun createWasmEngine(
    wasmBytes: ByteArray,
    callTimeout: Duration,
    hostCall: WasmHostCall = WasmHostImports.UNSUPPORTED,
    onTimeout: (String) -> Unit = {},
): WasmEngine {
    val runner = GuestCallRunner()
    val imports = WasmHostImports(hostCall)
    val hostTime = { imports.hostTime }
    val start = wasmEngineStart(wasmBytes, imports)
    val created = runner.runWithin(callTimeout, hostTime) { start.start() }
    if (created == null) {
        // Stops a start function that hangs, so the guest thread ends instead of spinning until exit.
        start.interrupt()
        runner.close()
        val message = "WASM module start exceeded $callTimeout; engine abandoned"
        onTimeout(message)
        error(message)
    }
    val engine = created.getOrElse {
        runner.close()
        throw it
    }
    return TimedWasmEngine(engine, runner, callTimeout, hostTime, onTimeout)
}

@OptIn(ExperimentalAtomicApi::class)
internal class TimedWasmEngine(
    private val delegate: WasmEngine,
    private val runner: GuestCallRunner,
    private val callTimeout: Duration,
    private val hostTime: () -> Duration = { Duration.ZERO },
    private val onTimeout: (String) -> Unit,
) : WasmEngine {
    private val abandoned = AtomicBoolean(false)

    override val isAbandoned: Boolean get() = abandoned.load()

    override fun callI32Array(
        funcName: String,
        args: IntArray,
    ): Int {
        checkUsable()
        val result = runner.runWithin(callTimeout, hostTime) { delegate.callI32Array(funcName, args) }
        if (result == null) {
            abandoned.store(true)
            delegate.interrupt()
            val message = "WASM guest call '$funcName' exceeded $callTimeout; plugin engine abandoned"
            onTimeout(message)
            error(message)
        }
        return result.getOrThrow()
    }

    override fun readMemory(
        offset: Int,
        length: Int,
    ): ByteArray {
        checkUsable()
        return delegate.readMemory(offset, length)
    }

    override fun writeMemory(
        offset: Int,
        data: ByteArray,
    ) {
        checkUsable()
        delegate.writeMemory(offset, data)
    }

    override fun close() {
        // An abandoned runtime is not freed: the interrupted guest call may still be unwinding on its thread.
        if (!abandoned.load()) delegate.close()
        runner.close()
    }

    private fun checkUsable() = check(!abandoned.load()) { "WASM engine abandoned after a call exceeded $callTimeout" }
}
