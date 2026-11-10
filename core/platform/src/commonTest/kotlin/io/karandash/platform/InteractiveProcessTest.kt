package io.karandash.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class InteractiveProcessTest {
    @Test
    fun `lines round trip through a long lived child until stdin closes`() {
        val process = PlatformProcess.spawnInteractive(listOf("cat"))
        try {
            assertEquals(true, process.writeLine("first"))
            assertEquals("first", process.readLine())
            assertEquals(true, process.writeLine("second ünïcode"))
            assertEquals("second ünïcode", process.readLine())
            process.closeStdin()
            assertNull(process.readLine())
            assertFalse(process.writeLine("after close"))
        } finally {
            process.kill()
        }
    }

    @Test
    fun `stderr is read separately and kill ends a running child`() {
        val process = PlatformProcess.spawnInteractive(listOf("sh", "-c", "echo oops >&2; exec sleep 30"))
        assertEquals("oops", process.readErrorLine())
        process.kill()
        process.kill()
        assertNull(process.readLine())
        assertNull(process.readErrorLine())
    }

    @Test
    fun `a missing program fails to start`() {
        assertFailsWith<IllegalStateException> {
            PlatformProcess.spawnInteractive(listOf("karandash-no-such-program-xyz"))
        }
    }
}
