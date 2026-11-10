@file:OptIn(ObsoleteWorkersApi::class)

package io.karandash.wasm

import platform.posix.usleep
import kotlin.native.concurrent.FutureState
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.time.Duration
import kotlin.time.TimeSource

internal actual class GuestCallRunner actual constructor() {
    private val worker = Worker.start(name = "karandash-wasm-guest")

    actual fun <T> runWithin(
        timeout: Duration,
        extension: () -> Duration,
        block: () -> T,
    ): Result<T>? {
        val future = worker.execute(TransferMode.SAFE, { block }) { job -> captureGuestCall(job) }
        val started = TimeSource.Monotonic.markNow()
        var pause = MIN_POLL_MICROS
        while (future.state == FutureState.SCHEDULED) {
            if (started.elapsedNow() > timeout + extension()) return null
            usleep(pause)
            pause = minOf(pause * 2u, MAX_POLL_MICROS)
        }
        return future.result
    }

    actual fun close() {
        // Non-blocking: an idle worker exits, one stuck in guest code is abandoned.
        worker.requestTermination(processScheduledJobs = false)
    }

    private companion object {
        // Back off so a long guest call does not burn a core polling.
        const val MIN_POLL_MICROS = 100u
        const val MAX_POLL_MICROS = 10_000u
    }
}
