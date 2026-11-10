package io.karandash.platform

import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** a killed subprocess reports one exit code on every platform, defined once in [ProcessTimeouts]. */
class ProcessTimeoutsTest {
    private val posix: Boolean =
        try {
            PlatformProcess
                .execute(listOf("uname", "-s"))
                .output
                .trim()
                .let { it == "Linux" || it == "Darwin" }
        } catch (ignored: IOException) {
            false
        }

    @Test
    fun `a timed out subprocess exits with the shared timeout code`() {
        if (!posix) return

        val result = PlatformProcess.execute(listOf("sleep", "30"), timeoutSeconds = 1)

        assertEquals(ProcessTimeouts.EXIT_CODE, result.exitCode)
    }

    @Test
    fun `the default subprocess timeout is positive`() {
        assertTrue(ProcessTimeouts.DEFAULT_SECONDS > 0)
    }

    @Test
    fun `the shared timeout values keep their numbers`() {
        assertEquals(137, ProcessTimeouts.EXIT_CODE)
        assertEquals(30, ProcessTimeouts.DEFAULT_SECONDS)
        assertEquals(60, ProcessTimeouts.BRIDGE_SECONDS)
    }
}
