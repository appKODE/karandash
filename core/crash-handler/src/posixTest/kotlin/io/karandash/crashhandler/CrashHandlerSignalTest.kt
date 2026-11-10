@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.crashhandler

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.SIGABRT
import platform.posix.SIGSEGV
import platform.posix.STDERR_FILENO
import platform.posix._exit
import platform.posix.close
import platform.posix.dup2
import platform.posix.fork
import platform.posix.pipe
import platform.posix.raise
import platform.posix.read
import platform.posix.waitpid
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A forked child installs the handler and raises a real signal; the parent reads its stderr. */
class CrashHandlerSignalTest {
    private data class Outcome(
        val stderr: String,
        val terminatedBySignal: Int
    )

    private fun crashChild(signal: Int): Outcome =
        memScoped {
            val fds = allocArray<IntVar>(2)
            check(pipe(fds) == 0)
            val pid = fork()
            check(pid >= 0) { "fork failed" }
            if (pid == 0) {
                dup2(fds[1], STDERR_FILENO)
                close(fds[0])
                installCrashHandler()
                raise(signal)
                _exit(0)
            }
            close(fds[1])
            val buf = allocArray<ByteVar>(512)
            val n = read(fds[0], buf, 511.convert()).toInt()
            close(fds[0])
            buf[n.coerceAtLeast(0)] = 0
            val status = alloc<IntVar>()
            waitpid(pid, status.ptr, 0)
            // POSIX wait status: low 7 bits are the terminating signal, 0 for a normal exit
            Outcome(buf.toKString(), status.value and 0x7f)
        }

    @Test
    fun `SIGSEGV in a child prints the report then dies by the same signal`() {
        val outcome = crashChild(SIGSEGV)
        assertContains(outcome.stderr, "[CRASH] karandash native binary received SIGSEGV (segmentation fault)")
        assertEquals(SIGSEGV, outcome.terminatedBySignal)
    }

    @Test
    fun `SIGABRT in a child prints the report then dies by the same signal`() {
        val outcome = crashChild(SIGABRT)
        assertContains(outcome.stderr, "SIGABRT (abort)")
        assertTrue(outcome.stderr.endsWith("\n"))
        assertEquals(SIGABRT, outcome.terminatedBySignal)
    }
}
