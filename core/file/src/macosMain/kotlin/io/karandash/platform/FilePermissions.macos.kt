@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.karandash.platform

import kotlinx.cinterop.convert
import kotlinx.io.files.Path
import platform.posix.chmod

/**
 * POSIX permission bits: owner=rwx, group=r-x, other=r-x (0755 octal = 493 decimal).
 * Kept per target: `mode_t` is 16-bit on macOS and 32-bit on Linux, so posixMain cannot call `chmod`.
 */
private const val POSIX_EXECUTABLE_MODE = 493u

actual fun setExecutable(path: Path) {
    chmod(path.toString(), POSIX_EXECUTABLE_MODE.convert())
}
