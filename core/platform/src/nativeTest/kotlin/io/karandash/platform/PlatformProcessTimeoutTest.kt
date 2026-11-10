package io.karandash.platform

import kotlin.experimental.ExperimentalNativeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** A subprocess run with `timeoutSeconds` is killed at the bound (macOS has no `timeout(1)`). */
@OptIn(ExperimentalNativeApi::class)
class PlatformProcessTimeoutTest {
    private val posix = Platform.osFamily != OsFamily.WINDOWS

    @Test
    fun `command over the timeout is killed and fails`() {
        if (!posix) return
        val elapsed = measureTime {
            val result = PlatformProcess.execute(listOf("sleep", "30"), timeoutSeconds = 1)
            assertNotEquals(0, result.exitCode)
        }
        assertTrue(elapsed < 15.seconds, "subprocess outlived its timeout: $elapsed")
    }

    @Test
    fun `command within the timeout keeps its output and stdin and exit code`() {
        if (!posix) return
        val result = PlatformProcess.execute(listOf("cat"), stdin = "hello", timeoutSeconds = 10)
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.output)
        assertEquals(3, PlatformProcess.execute(listOf("sh", "-c", "exit 3"), timeoutSeconds = 10).exitCode)
    }
}
