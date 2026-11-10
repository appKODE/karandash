package io.karandash.wasm

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.TimeSource

internal actual class GuestCallRunner actual constructor() {
    actual fun <T> runWithin(
        timeout: Duration,
        extension: () -> Duration,
        block: () -> T,
    ): Result<T>? {
        val started = TimeSource.Monotonic.markNow()
        val future = pool.submit(Callable { captureGuestCall(block) })
        while (true) {
            val left = timeout + extension() - started.elapsedNow()
            if (!left.isPositive()) break
            try {
                return future.get(left.inWholeMilliseconds.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // re-check: host time may have moved the deadline while waiting
            }
        }
        // Interrupting the guest thread stops Chicory, which polls the interrupt flag while executing.
        future.cancel(true)
        return null
    }

    actual fun close() = Unit

    private companion object {
        val pool: ExecutorService = Executors.newCachedThreadPool { task ->
            Thread(task, "karandash-wasm-guest").apply { isDaemon = true }
        }
    }
}
