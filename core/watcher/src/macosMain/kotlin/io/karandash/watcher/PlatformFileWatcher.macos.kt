@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.watcher

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.darwin.EVFILT_VNODE
import platform.darwin.EV_ADD
import platform.darwin.EV_CLEAR
import platform.darwin.NOTE_ATTRIB
import platform.darwin.NOTE_DELETE
import platform.darwin.NOTE_RENAME
import platform.darwin.NOTE_WRITE
import platform.darwin.kevent
import platform.darwin.kqueue
import platform.posix.EINTR
import platform.posix.O_EVTONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fstat
import platform.posix.open
import platform.posix.stat
import platform.posix.timespec
import platform.posix.usleep
import kotlin.concurrent.AtomicInt

internal actual fun createPlatformFileWatcherDelegate(filePath: String): PlatformFileWatcherDelegate =
    MacosPlatformFileWatcherDelegate(filePath)

private class MacosPlatformFileWatcherDelegate(
    private val filePath: String
) : PlatformFileWatcherDelegate {
    private val stopRequested = AtomicInt(0)

    override fun startWatching(onChange: () -> Unit) {
        stopRequested.value = 0

        val kq = kqueue()
        if (kq == -1) {
            error("Failed to create kqueue")
        }

        // A file missing at start is not an error: the reopen loop below waits for it to be created.
        var fd = open(filePath, O_EVTONLY)

        try {
            memScoped {
                val changeEvent = alloc<kevent>()
                if (fd != -1) register(kq, fd, changeEvent)
                if (fd != -1 && !isStillAtPath(fd)) {
                    close(fd)
                    fd = -1
                }

                val eventBuf = alloc<kevent>()
                val timeout = alloc<timespec>()
                timeout.tv_sec = 1
                timeout.tv_nsec = 0

                while (stopRequested.value == 0) {
                    if (fd == -1) {
                        // The file was deleted or renamed away (atomic save): wait for the new inode.
                        fd = open(filePath, O_EVTONLY)
                        if (fd == -1) {
                            usleep(REOPEN_POLL_US)
                            continue
                        }
                        register(kq, fd, changeEvent)
                        if (!isStillAtPath(fd)) {
                            // Replaced again before the registration took effect: that event is lost, so retry.
                            close(fd)
                            fd = -1
                        }
                        onChange()
                        continue
                    }

                    val nev = kevent(kq, null, 0, eventBuf.ptr, 1, timeout.ptr)

                    if (stopRequested.value != 0) {
                        break
                    }

                    if (nev == -1) {
                        if (errno == EINTR) {
                            continue
                        }
                        error("kevent wait failed")
                    }

                    if (nev > 0) {
                        if ((eventBuf.fflags and (NOTE_DELETE or NOTE_RENAME).toUInt()) != 0u) {
                            // The watched inode is gone; closing the fd drops its kevent registration.
                            close(fd)
                            fd = -1
                        } else {
                            onChange()
                        }
                    }
                }
            }
        } finally {
            if (fd != -1) close(fd)
            close(kq)
        }
    }

    /**
     * kqueue only reports vnode events after registration. If the path was replaced between open(2) and
     * registration, the fd watches an orphaned inode (link count 0) and no event will ever arrive.
     * Link count rather than st_ino: ino_t differs in width across macOS targets, which the shared source set rejects.
     */
    private fun isStillAtPath(fd: Int): Boolean =
        memScoped {
            val byFd = alloc<stat>()
            fstat(fd, byFd.ptr) == 0 && byFd.st_nlink.toInt() > 0
        }

    private fun register(
        kq: Int,
        fd: Int,
        changeEvent: kevent,
    ) {
        changeEvent.ident = fd.toULong()
        changeEvent.filter = EVFILT_VNODE.toShort()
        changeEvent.flags = (EV_ADD or EV_CLEAR).toUShort()
        changeEvent.fflags = (NOTE_WRITE or NOTE_DELETE or NOTE_RENAME or NOTE_ATTRIB).toUInt()
        changeEvent.data = 0
        changeEvent.udata = null
        if (kevent(kq, changeEvent.ptr, 1, null, 0, null) == -1) {
            error("Failed to register kevent")
        }
    }

    override fun stop() {
        stopRequested.value = 1
    }

    override fun isSupported(): Boolean = true
}

private const val REOPEN_POLL_US = 100_000u
