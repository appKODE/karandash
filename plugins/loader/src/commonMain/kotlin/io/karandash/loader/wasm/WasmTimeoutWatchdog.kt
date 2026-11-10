package io.karandash.loader.wasm

import io.karandash.logger.Logger
import io.karandash.platform.platformExit
import io.karandash.wasm.wasmEngineInterruptsGuests
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Handles a WASM guest call timeout. Where the engine can stop the guest it only warns. Where it cannot (macOS x64,
 * Chasm 1.7.0) the abandoned guest keeps a thread spinning, so after [grace] it logs an error and exits the process
 * with [EXIT_CODE] instead of running on with a runaway guest.
 */
internal class WasmTimeoutWatchdog(
    private val logger: Logger,
    private val engineInterruptsGuests: Boolean = wasmEngineInterruptsGuests,
    private val grace: Duration = DEFAULT_GRACE,
    private val schedule: (Duration, () -> Unit) -> Unit = ::runAfter,
    private val exit: (Int) -> Unit = { platformExit(it) },
) {
    fun onTimeout(message: String) {
        logger.warn(message)
        if (engineInterruptsGuests) return
        schedule(grace) {
            logger.error(
                "$message. This WASM engine (macOS x64) cannot stop the guest, which still runs; " +
                    "exiting with code $EXIT_CODE after a $grace grace period",
            )
            exit(EXIT_CODE)
        }
    }

    companion object {
        /** `ExitCodes.EXECUTION_ERROR` (cli-ai), which the loader cannot depend on. */
        const val EXIT_CODE = 6
        val DEFAULT_GRACE = 5.seconds
    }
}

/** Runs [action] once on a background thread after [delay]; never blocks the caller. */
internal expect fun runAfter(
    delay: Duration,
    action: () -> Unit,
)
