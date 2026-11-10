@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.watcher

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.stat
import platform.posix.usleep
import kotlin.concurrent.AtomicInt

internal actual fun createPlatformFileWatcherDelegate(filePath: String): PlatformFileWatcherDelegate = PollingFileWatcherDelegate(filePath)

/**
 * Polls the file's modification time and size once per interval.
 *
 * limit: polling, up to one interval of latency; ReadDirectoryChangesW if Windows watch mode needs it faster.
 */
private class PollingFileWatcherDelegate(
    private val filePath: String,
) : PlatformFileWatcherDelegate {
    private val stopRequested = AtomicInt(0)

    override fun startWatching(onChange: () -> Unit) {
        stopRequested.value = 0
        generateSequence(fingerprint()) {
            usleep(POLL_INTERVAL_US)
            fingerprint()
        }.takeWhile { stopRequested.value == 0 }
            .zipWithNext()
            .filter { (previous, current) -> previous != current }
            .forEach { onChange() }
    }

    /** `null` while the file is missing (mid-replace); a reappearing file counts as a change. */
    private fun fingerprint(): Pair<Long, Long>? =
        memScoped {
            val st = alloc<stat>()
            if (stat(filePath, st.ptr) == 0) st.st_mtime to st.st_size.toLong() else null
        }

    override fun stop() {
        stopRequested.value = 1
    }

    override fun isSupported(): Boolean = true

    private companion object {
        const val POLL_INTERVAL_US = 500_000u
    }
}
