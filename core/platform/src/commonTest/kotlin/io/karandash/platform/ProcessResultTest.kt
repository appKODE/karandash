package io.karandash.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessResultTest {
    private val posix = PlatformEnvironment.getEnv("OS") != "Windows_NT"

    @Test
    fun `success exposes output and flags`() {
        val result = ProcessResult(0, "out", "")

        assertTrue(result.isSuccess)
        assertFalse(result.isFailed)
        assertTrue(result.hasOutput)
        assertFalse(result.hasError)
        assertEquals("out", result.outputOrNull())
        assertEquals("out", result.outputOrThrow())
    }

    @Test
    fun `failure hides output and throws with the best available message`() {
        val withError = ProcessResult(2, "", "boom")
        val silent = ProcessResult(3, "", "")

        assertTrue(withError.isFailed)
        assertTrue(withError.hasError)
        assertFalse(withError.hasOutput)
        assertNull(withError.outputOrNull())
        assertEquals("boom", assertFailsWith<IllegalStateException> { withError.outputOrThrow() }.message)
        assertEquals("custom", assertFailsWith<IllegalStateException> { withError.outputOrThrow("custom") }.message)
        assertEquals(
            "Process failed with exit code: 3",
            assertFailsWith<IllegalStateException> { silent.outputOrThrow() }.message,
        )
    }

    @Test
    fun `execute captures stdout stderr exit code stdin and working dir`() =
        runTest {
            if (!posix) return@runTest
            val result = PlatformProcess.executeAsync(
                listOf("sh", "-c", "cat; echo err 1>&2; pwd; exit 4"),
                workingDir = "/",
                stdin = "in\n",
                timeoutSeconds = 30,
            )

            assertEquals(4, result.exitCode)
            assertEquals("in\n/\n", result.output)
            assertEquals("err\n", result.error)
            assertEquals("ok\n", PlatformProcess.execute(listOf("sh", "-c", "echo ok")).output)
        }

    @Test
    fun `execute fails a command that outlives its timeout`() {
        if (!posix) return
        val result = PlatformProcess.execute(listOf("sleep", "20"), timeoutSeconds = 1)

        assertTrue(result.isFailed)
    }

    @Test
    fun `timestamp is formatted as hours minutes seconds`() {
        assertTrue(Regex("""\d{2}:\d{2}:\d{2}""").matches(getCurrentTimestamp()))
    }
}
