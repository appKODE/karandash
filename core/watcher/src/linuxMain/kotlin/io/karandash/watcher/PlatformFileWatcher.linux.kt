@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.watcher

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.linux.IN_ATTRIB
import platform.linux.IN_CLOSE_WRITE
import platform.linux.IN_DELETE_SELF
import platform.linux.IN_MODIFY
import platform.linux.IN_MOVE_SELF
import platform.linux.inotify_add_watch
import platform.linux.inotify_init
import platform.posix.EINTR
import platform.posix.POLLIN
import platform.posix.close
import platform.posix.errno
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import kotlin.concurrent.AtomicInt

internal actual fun createPlatformFileWatcherDelegate(filePath: String): PlatformFileWatcherDelegate =
    LinuxPlatformFileWatcherDelegate(filePath)

/** inotify watch on the file itself; re-armed after every event so editors that replace the file by rename keep firing. */
private class LinuxPlatformFileWatcherDelegate(
    private val filePath: String,
) : PlatformFileWatcherDelegate {
    private val stopRequested = AtomicInt(0)

    override fun startWatching(onChange: () -> Unit) {
        stopRequested.value = 0
        val fd = inotify_init()
        check(fd != -1) { "Failed to initialize inotify" }
        try {
            // A file missing at start is not an error: the unarmed loop below waits for it to be created.
            var armed = addWatch(fd) != -1
            memScoped {
                val pfd = alloc<pollfd>()
                pfd.fd = fd
                pfd.events = POLLIN.toShort()
                val buf = allocArray<ByteVar>(EVENT_BUFFER_SIZE)
                while (stopRequested.value == 0) {
                    val ready = poll(pfd.ptr, 1u, POLL_TIMEOUT_MS)
                    check(ready != -1 || errno == EINTR) { "inotify poll failed" }
                    if (ready > 0 && stopRequested.value == 0 && read(fd, buf, EVENT_BUFFER_SIZE.toULong()) > 0) {
                        // the file may have been replaced; a missing file is retried on every poll timeout
                        armed = addWatch(fd) != -1
                        onChange()
                    } else if (ready == 0 && !armed && stopRequested.value == 0) {
                        armed = addWatch(fd) != -1
                        if (armed) onChange()
                    }
                }
            }
        } finally {
            close(fd)
        }
    }

    private fun addWatch(fd: Int): Int =
        inotify_add_watch(fd, filePath, (IN_MODIFY or IN_CLOSE_WRITE or IN_ATTRIB or IN_MOVE_SELF or IN_DELETE_SELF).toUInt())

    override fun stop() {
        stopRequested.value = 1
    }

    override fun isSupported(): Boolean = true

    private companion object {
        const val EVENT_BUFFER_SIZE = 4096
        const val POLL_TIMEOUT_MS = 1000
    }
}
