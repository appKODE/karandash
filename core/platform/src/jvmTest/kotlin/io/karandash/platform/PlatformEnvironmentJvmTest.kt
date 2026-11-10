package io.karandash.platform

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JVM-only: asserts on `System.err` redirection and the JVM process handle. */
class PlatformEnvironmentJvmTest {
    @Test
    fun `stderrPrintln writes the message and a newline to System err`() {
        val original = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true, "UTF-8"))
        try {
            PlatformEnvironment.stderrPrintln("boom")
        } finally {
            System.setErr(original)
        }

        assertEquals("boom" + System.lineSeparator(), captured.toString("UTF-8"))
    }

    @Test
    fun `executablePath is the running java binary`() {
        val path = PlatformEnvironment.executablePath()

        assertTrue(path != null && path.contains("java"), path.toString())
    }
}
