package io.karandash.cli

import com.github.ajalt.clikt.core.CliktCommand
import io.karandash.http.NetworkPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Base class for CLI commands that need to call suspend functions.
 *
 * Bridges Clikt's synchronous [run] with a [suspendRun] method that runs
 * inside a single top-level [runBlocking] coroutine. This consolidates the
 * sync/async boundary to one location per command.
 *
 * When the root command carries the caller's [Job] as its context object (see `dispatchCli` in cli),
 * the command's coroutine is a child of that job, so the global watchdog cancelling the run
 * cancels the command at its next suspension point instead of waiting for it to finish. Each command gets its
 * own [NetworkPolicy], which the config it loads sets.
 */
abstract class SuspendCliktCommand(
    name: String? = null,
) : CliktCommand(name = name) {
    abstract suspend fun suspendRun()

    // bridge-boundary: Clikt's run() is non-suspend; parent Job keeps the global watchdog able to cancel
    override fun run() = runBlocking((currentContext.findObject<Job>() ?: EmptyCoroutineContext) + NetworkPolicy()) { suspendRun() }
}
