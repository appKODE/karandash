package io.karandash.loader

import io.karandash.sdk.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration

/**
 * One guest's call lane for runtimes that cannot interrupt a guest (C interop, JNA): every call runs on the
 * guest's own one-at-a-time dispatcher, off the caller's thread, and must finish within [timeout]. A call that
 * does not is abandoned on its thread, the guest is marked hung, and every later call answers null at once.
 *
 * The caller only suspends while the guest runs, so a host callback the guest makes is served off the caller's
 * thread (the host-call channel relies on that).
 */
@OptIn(ExperimentalAtomicApi::class)
internal class GuestExecutor(
    private val label: String,
    private val timeout: Duration,
    private val logger: Logger,
    private val hostTime: () -> Duration = { Duration.ZERO },
) {
    // limit: one-at-a-time, not pinned to one thread; use a dedicated thread if a guest needs thread affinity
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val hung = AtomicBoolean(false)

    val isHung: Boolean get() = hung.load()

    /** [block]'s result, or null when the guest is hung or [block] did not finish within [timeout]. */
    suspend fun <T> run(
        method: String,
        block: () -> T,
    ): T? {
        if (hung.load()) return null
        val call = scope.async { block() }
        // Wall-clock deadline whatever the caller's dispatcher; NonCancellable so a cancelled caller never
        // starts the next guest call while this one still runs.
        // Time the guest spends in its own host calls ([hostTime]) extends the deadline.
        val finished = withContext(NonCancellable + Dispatchers.Default) { call.joinWithin(timeout, hostTime) }
        if (!finished) {
            if (hung.compareAndSet(expectedValue = false, newValue = true)) {
                logger.warn(
                    "[$label] '$method' exceeded $timeout; plugin marked hung and its later calls are skipped " +
                        "(raise ${GuestCallTimeout.ENV_VAR} if the plugin is just slow)",
                )
            }
            return null
        }
        return call.await()
    }
}
