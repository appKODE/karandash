@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.karandash.platform

import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.EEXIST
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.open

/** Owner read/write (0600 octal). */
private const val OWNER_READ_WRITE = 384

internal actual fun createFileExclusive(path: Path): Boolean {
    val fd = open(path.toString(), O_CREAT or O_EXCL or O_WRONLY, OWNER_READ_WRITE)
    if (fd == -1) {
        if (errno == EEXIST) return false
        error("Cannot create $path: errno $errno")
    }
    close(fd)
    return true
}

actual val Path.safeParent: Path? get() = unixParent(toString())?.let(::Path)

actual val Path.safeName: String get() = unixName(toString())

/**
 * Creates [path] and its missing ancestors with [mkdir]; a sibling creating the same ancestor (`EEXIST`) is fine.
 * kotlinx-io's own walk reads [Path.parent], which races on Apple (see [safeParent]), and on Linux fails on that `EEXIST`.
 * [mkdir] is per platform because `mode_t` differs in width.
 */
internal fun mkdirWalk(
    path: Path,
    mkdir: (String) -> Int,
) {
    val missing = generateSequence(path) { it.safeParent }.takeWhile { !SystemFileSystem.exists(it) }.toList()
    for (dir in missing.asReversed()) {
        if (mkdir(dir.toString()) != 0 && errno != EEXIST) {
            throw IOException("mkdir $dir failed: errno $errno")
        }
    }
}
