package io.karandash.crashhandler

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** The JVM crash handler is installed as the default and writes a report to stderr. */
class CrashHandlerJvmTest {
    @Test
    fun `installed handler writes the thread and exception to stderr`() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val previousErr = System.err
        val captured = ByteArrayOutputStream()
        try {
            System.setErr(PrintStream(captured, true))
            installCrashHandler()
            val handler = assertNotNull(Thread.getDefaultUncaughtExceptionHandler())
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        } finally {
            System.setErr(previousErr)
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        val text = captured.toString()
        assertContains(text, "[CRASH] karandash JVM uncaught exception in thread '${Thread.currentThread().name}'")
        assertContains(text, "[CRASH] IllegalStateException: boom")
    }

    @Test
    fun `a token in the exception message is redacted from the crash report`() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val previousErr = System.err
        val captured = ByteArrayOutputStream()
        try {
            System.setErr(PrintStream(captured, true))
            installCrashHandler()
            checkNotNull(Thread.getDefaultUncaughtExceptionHandler())
                .uncaughtException(Thread.currentThread(), IllegalStateException("GET https://user:s3cret@host/x failed"))
        } finally {
            System.setErr(previousErr)
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
        assertFalse("s3cret" in captured.toString(), captured.toString())
    }
}
