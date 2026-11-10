package io.karandash.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Whole-run timeout (`--global-timeout`, `timeouts.global`), measured from CLI start.
 *
 * Starts with [initial]; the loaded config can re-arm it via [rearmActive] unless [fixedByCli]
 * (the `--global-timeout` flag wins). A non-positive timeout disables the watchdog.
 */
internal class GlobalWatchdog(
    initial: Duration,
    private val fixedByCli: Boolean,
) {
    private val timeout = MutableStateFlow(initial)
    private val start = TimeSource.Monotonic.markNow()

    /** The timeout currently in force. */
    val current: Duration get() = timeout.value

    fun rearm(configured: Duration) {
        if (!fixedByCli) timeout.value = configured
    }

    /** Runs [block]; returns null when the timeout fired first (the block is cancelled). */
    suspend fun <T> run(block: suspend () -> T): T? {
        active.value = this
        val fired = CompletableDeferred<Unit>()
        return try {
            coroutineScope {
                val work = async { block() }
                val watcher = launch {
                    timeout.collectLatest { t ->
                        if (t.isPositive()) {
                            delay(t - start.elapsedNow())
                            fired.complete(Unit)
                            work.cancel()
                        }
                    }
                }
                try {
                    work.await()
                } catch (e: CancellationException) {
                    if (fired.isCompleted && isActive) null else throw e
                } finally {
                    watcher.cancel()
                }
            }
        } finally {
            active.compareAndSet(this, null)
        }
    }

    companion object {
        private val active = MutableStateFlow<GlobalWatchdog?>(null)

        /** Re-arms the watchdog of the running CLI invocation, if any, to [configured]. */
        fun rearmActive(configured: Duration) {
            active.value?.rearm(configured)
        }
    }
}
