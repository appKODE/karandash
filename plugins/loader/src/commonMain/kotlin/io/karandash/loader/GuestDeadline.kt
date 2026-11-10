package io.karandash.loader

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Joins this job within [timeout], extended by however much [hostTime] grows meanwhile: time a guest spends
 * waiting on its own host calls (an AI completion, a content fetch) does not count against its call timeout.
 * True when the job finished in time.
 */
internal suspend fun Job.joinWithin(
    timeout: Duration,
    hostTime: () -> Duration,
): Boolean {
    val started = TimeSource.Monotonic.markNow()
    val hostAtStart = hostTime()
    while (true) {
        val left = timeout + (hostTime() - hostAtStart) - started.elapsedNow()
        if (left <= Duration.ZERO) return isCompleted
        if (withTimeoutOrNull(left) { join() } != null) return true
    }
}
