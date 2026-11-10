@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.crashhandler

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import platform.posix.SIGABRT
import platform.posix.SIGILL
import platform.posix.SIGSEGV
import platform.posix.SIG_DFL
import platform.posix.raise
import platform.posix.signal

// ASCII only, so the character count is the byte count. Offsets and lengths are compile-time constants.
private const val PREFIX_TEXT = "\n[CRASH] karandash native binary received "
private const val SEGV_TEXT = "SIGSEGV (segmentation fault)"
private const val ABRT_TEXT = "SIGABRT (abort)"
private const val ILL_TEXT = "SIGILL (illegal instruction)"
private const val UNKNOWN_TEXT = "unknown signal"
private const val NEWLINE_TEXT = "\n"
private const val ALL_TEXT = PREFIX_TEXT + SEGV_TEXT + ABRT_TEXT + ILL_TEXT + UNKNOWN_TEXT + NEWLINE_TEXT

private const val PREFIX_OFF = 0
private const val SEGV_OFF = PREFIX_OFF + PREFIX_TEXT.length
private const val ABRT_OFF = SEGV_OFF + SEGV_TEXT.length
private const val ILL_OFF = ABRT_OFF + ABRT_TEXT.length
private const val UNKNOWN_OFF = ILL_OFF + ILL_TEXT.length
private const val NEWLINE_OFF = UNKNOWN_OFF + UNKNOWN_TEXT.length

// The nativeHeap buffer holding every message back to back; null until installCrashHandler() ran.
// A literal null initialiser is static data: reading it in the handler runs no initialiser.
private var messageBuffer: CPointer<ByteVar>? = null

/** write(2) to fd 2; per-family actual because size_t/ssize_t widths differ between mingw and POSIX. */
internal expect fun writeStderr(
    buffer: CPointer<ByteVar>?,
    length: Int,
)

private fun emit(
    offset: Int,
    length: Int
) {
    writeStderr(messageBuffer + offset, length)
}

/**
 * Writes the crash report to fd 2; the signal handler body.
 *
 * Allocation-free: it only reads the preinitialised [messageBuffer] address and calls write(2) with
 * compile-time offsets and lengths. No Kotlin object, string, array or lazy property is created here.
 */
internal fun writeCrashReport(sig: Int) {
    if (messageBuffer == null) return
    emit(PREFIX_OFF, PREFIX_TEXT.length)
    when (sig) {
        SIGSEGV -> emit(SEGV_OFF, SEGV_TEXT.length)
        SIGABRT -> emit(ABRT_OFF, ABRT_TEXT.length)
        SIGILL -> emit(ILL_OFF, ILL_TEXT.length)
        else -> emit(UNKNOWN_OFF, UNKNOWN_TEXT.length)
    }
    emit(NEWLINE_OFF, NEWLINE_TEXT.length)
}

internal fun copyMessagesToNativeHeap() {
    if (messageBuffer != null) return
    val bytes = ALL_TEXT.encodeToByteArray()
    val buffer = nativeHeap.allocArray<ByteVar>(bytes.size)
    for (i in bytes.indices) buffer[i] = bytes[i]
    messageBuffer = buffer
}

/**
 * Native crash handler - installs POSIX signal handlers that write crash
 * diagnostics to stderr via write(2) before re-raising the signal to preserve the default
 * termination behaviour. Called once at native binary startup.
 *
 * All messages are copied into one nativeHeap buffer here, at install time, so the handler body
 * performs no Kotlin allocation (an allocation or lazy initialiser inside a signal handler can deadlock).
 */
actual fun installCrashHandler() {
    copyMessagesToNativeHeap()
    val handler = staticCFunction<Int, Unit> { sig ->
        writeCrashReport(sig)
        signal(sig, SIG_DFL)
        raise(sig)
    }
    signal(SIGSEGV, handler)
    signal(SIGABRT, handler)
    signal(SIGILL, handler)
}
