@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.crashhandler

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.STDERR_FILENO
import platform.posix.write

internal actual fun writeStderr(
    buffer: CPointer<ByteVar>?,
    length: Int,
) {
    write(STDERR_FILENO, buffer, length.convert())
}
