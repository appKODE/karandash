package io.karandash.platform

import kotlin.time.Duration

/** The subprocess timeout constants every platform and every caller of [PlatformProcess] shares. */
object ProcessTimeouts {
    /** Exit code of a subprocess killed at its timeout (128 + SIGKILL), identical on JVM, POSIX and Windows. */
    const val EXIT_CODE = 137

    /** Bound, in seconds, for helper subprocesses (git, ast-grep, scripts) that give no timeout of their own. */
    const val DEFAULT_SECONDS = 30

    /** Bound, in seconds, for one request/response round trip to the JAR plugin bridge subprocess. */
    const val BRIDGE_SECONDS = 60

    /** [timeout] in whole seconds, rounded up so a sub-second bound never becomes 0 (no wait at all). */
    fun ceilSeconds(timeout: Duration): Int {
        val millis = timeout.inWholeMilliseconds.coerceAtLeast(0)
        return ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private const val MILLIS_PER_SECOND = 1000L
}
