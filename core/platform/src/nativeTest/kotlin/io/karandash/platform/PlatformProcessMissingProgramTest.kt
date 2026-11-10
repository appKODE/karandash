package io.karandash.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** With `unsetEnv` the program is resolved up front, so a missing program must fail with a readable message. */
class PlatformProcessMissingProgramTest {
    private val missing = "karandash-no-such-program"

    private fun isPosix(): Boolean =
        PlatformProcess
            .execute(listOf("uname", "-s"))
            .output
            .trim()
            .let { it == "Linux" || it == "Darwin" }

    @Test
    fun `missing program fails with a not found message and exit 127`() {
        if (!isPosix()) return

        val result = PlatformProcess.execute(listOf(missing), unsetEnv = setOf("PATH"))

        assertEquals(127, result.exitCode)
        assertTrue(result.error.contains("$missing: not found"), result.error)
    }

    @Test
    fun `missing program with a timeout and a working directory fails the same way`() {
        if (!isPosix()) return

        val result = PlatformProcess.execute(listOf(missing), workingDir = "/", timeoutSeconds = 10, unsetEnv = setOf("PATH"))

        assertEquals(127, result.exitCode)
        assertTrue(result.error.contains("$missing: not found"), result.error)
    }
}
