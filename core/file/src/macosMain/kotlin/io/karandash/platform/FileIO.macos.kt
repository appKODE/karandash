@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.karandash.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.posix.mkdir
import platform.posix.stat

private const val MILLIS_PER_SECOND = 1000L
private const val NANOS_PER_MILLI = 1_000_000L

/** 0777 octal, narrowed by the umask like `mkdir -p`. */
private const val DIRECTORY_MODE = 511

actual fun Path.lastModifiedMillis(): Long {
    memScoped {
        val statBuf = alloc<stat>()
        if (stat(this@lastModifiedMillis.toString(), statBuf.ptr) == 0) {
            return statBuf.st_mtimespec.tv_sec * MILLIS_PER_SECOND + statBuf.st_mtimespec.tv_nsec / NANOS_PER_MILLI
        }
        return 0L
    }
}

internal actual fun createDirectoriesRaw(path: Path) = mkdirWalk(path) { mkdir(it, DIRECTORY_MODE.convert()) }
