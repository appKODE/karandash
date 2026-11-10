@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.karandash.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.EEXIST
import platform.posix._close
import platform.posix._open
import platform.posix.errno
import platform.posix.stat

private const val MILLIS_PER_SECOND = 1000L
private const val WINDOWS_O_CREAT = 0x0100
private const val WINDOWS_O_EXCL = 0x0400
private const val WINDOWS_O_RDWR = 0x0002
private const val WINDOWS_S_IREAD = 0x0100
private const val WINDOWS_S_IWRITE = 0x0080

actual fun Path.lastModifiedMillis(): Long {
    memScoped {
        val statBuf = alloc<platform.posix.stat>()
        if (stat(this@lastModifiedMillis.toString(), statBuf.ptr) == 0) {
            return statBuf.st_mtime * MILLIS_PER_SECOND
        }
        return 0L
    }
}

internal actual fun createFileExclusive(path: Path): Boolean {
    val fd = _open(path.toString(), WINDOWS_O_CREAT or WINDOWS_O_EXCL or WINDOWS_O_RDWR, WINDOWS_S_IREAD or WINDOWS_S_IWRITE)
    if (fd == -1) {
        if (errno == EEXIST) return false
        error("Cannot create $path: errno $errno")
    }
    _close(fd)
    return true
}

actual val Path.safeParent: Path? get() = parent

actual val Path.safeName: String get() = name

internal actual fun createDirectoriesRaw(path: Path) = SystemFileSystem.createDirectories(path)
