package io.karandash.loader.js

import com.dokar.quickjs.QuickJsInterruptedException
import io.karandash.loader.GuestCallTimeout
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.loader.joinWithin
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
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
import kotlin.time.Duration.Companion.seconds

/**
 * Time limit for calls into one QuickJS engine.
 *
 * The deadline is [timeout] plus the time the [host] spends serving this guest's host calls. When it passes, the
 * guard calls [interrupt] (the engine's `interruptEvaluation`), so a guest that spins in JS is stopped by QuickJS
 * itself ([QuickJsInterruptedException]): the call fails, is logged, and the engine stays usable. Only a call still
 * running [INTERRUPT_GRACE] after that (stuck in a blocking host function, where the interrupt handler never runs)
 * is left on a detached IO thread: the engine is marked hung, later calls fail fast, and [isHung] tells the owner
 * not to close an engine that is still executing.
 *
 * A call made while the [host] serves one of this guest's own host calls is refused: the engine is busy in that
 * host function, so the call would wait on itself.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class JsCallGuard(
    private val timeout: Duration = GuestCallTimeout.DEFAULT,
    private val logger: Logger = NoOpLogger,
    internal val host: HostServiceDispatcher? = null,
    private val interrupt: () -> Unit = {},
) {
    private val hung = AtomicBoolean(false)

    val isHung: Boolean get() = hung.load()

    suspend fun <T> run(
        label: String,
        block: suspend () -> T,
    ): T {
        check(!hung.load()) { "JS engine abandoned after a call exceeded $timeout" }
        check(host?.isReentrant() != true) { "JS plugin call '$label' refused: the plugin is inside its own host call (reentrant)" }
        // limit: an abandoned call (blocked outside JS) keeps its thread busy until process exit.
        val call = guestScope.async { block() }
        // Wall-clock deadline whatever the caller's dispatcher (a test scheduler would skip virtual time).
        // NonCancellable: a running QuickJS call cannot be interrupted, so a cancelled caller still waits for it
        // (at most [timeout]); otherwise the owner could close the engine under a live call, or start the next one.
        val finished =
            withContext(NonCancellable + Dispatchers.Default) {
                if (!call.joinWithin(timeout, ::hostTime)) interrupt()
                call.joinWithin(INTERRUPT_GRACE, ::hostTime)
            }
        if (!finished) {
            hung.store(true)
            val message = "JS plugin call '$label' exceeded $timeout; plugin engine abandoned " +
                "(raise ${GuestCallTimeout.ENV_VAR} if the plugin is just slow)"
            logger.warn(message)
            error(message)
        }
        try {
            return call.await()
        } catch (e: QuickJsInterruptedException) {
            logger.warn(
                "JS plugin call '$label' exceeded $timeout and was interrupted " +
                    "(raise ${GuestCallTimeout.ENV_VAR} if the plugin is just slow)",
            )
            throw e
        }
    }

    private fun hostTime(): Duration = host?.hostTime ?: Duration.ZERO

    private companion object {
        /** How long past the timeout the QuickJS interrupt may take before the engine is abandoned. */
        val INTERRUPT_GRACE = 1.seconds
        val guestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
