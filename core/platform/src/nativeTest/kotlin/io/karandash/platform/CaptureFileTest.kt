package io.karandash.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** a capture file that cannot be read is reported at warn level instead of silently turning into "". */
class CaptureFileTest {
    @Test
    fun `unreadable capture file warns and yields empty output`() {
        val warnings = mutableListOf<String>()

        val text = readCaptureFile("/nonexistent-karandash-dir/capture.txt") { warnings += it }

        assertEquals("", text)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("/nonexistent-karandash-dir/capture.txt"), warnings.single())
    }
}
