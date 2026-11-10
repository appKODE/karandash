@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.crashhandler

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.SIGABRT
import platform.posix.SIGILL
import platform.posix.SIGSEGV
import platform.posix.STDERR_FILENO
import platform.posix.close
import platform.posix.dup
import platform.posix.dup2
import platform.posix.pipe
import platform.posix.read
import kotlin.test.Test
import kotlin.test.assertContains

/** The signal handler body writes through write(2), not buffered stdio. */
class CrashReportTest {
    private fun captureStderr(block: () -> Unit): String =
        memScoped {
            val fds = allocArray<IntVar>(2)
            check(pipe(fds) == 0)
            val saved = dup(STDERR_FILENO)
            dup2(fds[1], STDERR_FILENO)
            block()
            dup2(saved, STDERR_FILENO)
            close(saved)
            close(fds[1])
            val buf = allocArray<ByteVar>(512)
            val n = read(fds[0], buf, 511.convert()).toInt()
            close(fds[0])
            buf[n] = 0
            buf.toKString()
        }

    @Test
    fun `crash report names the signal on fd 2`() {
        copyMessagesToNativeHeap()
        assertContains(captureStderr { writeCrashReport(SIGSEGV) }, "SIGSEGV")
        assertContains(captureStderr { writeCrashReport(SIGABRT) }, "SIGABRT")
        assertContains(captureStderr { writeCrashReport(SIGILL) }, "SIGILL")
        assertContains(captureStderr { writeCrashReport(0) }, "unknown signal")
    }
}
