package io.karandash.size

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KarandashSizeTest {
    @Test
    fun parseBytes() {
        assertEquals(1_024L, KarandashSize.parse("1024b").bytes)
    }

    @Test
    fun parseKilobytes() {
        assertEquals(512L * 1_024, KarandashSize.parse("512kb").bytes)
    }

    @Test
    fun parseMegabytes() {
        assertEquals(10L * 1_048_576, KarandashSize.parse("10mb").bytes)
    }

    @Test
    fun parseGigabytes() {
        assertEquals(1L * 1_073_741_824, KarandashSize.parse("1gb").bytes)
    }

    @Test
    fun parseTerabytes() {
        assertEquals(1L * 1_099_511_627_776, KarandashSize.parse("1tb").bytes)
    }

    @Test
    fun parseCaseInsensitive() {
        assertEquals(KarandashSize.parse("10mb"), KarandashSize.parse("10MB"))
    }

    @Test
    fun rawNumberRejected() {
        assertFailsWith<IllegalStateException> {
            KarandashSize.parse("1024")
        }
    }

    @Test
    fun invalidInputRejected() {
        assertFailsWith<IllegalStateException> {
            KarandashSize.parse("abc")
        }
    }

    @Test
    fun formatBytes() {
        assertEquals("512b", KarandashSize.ofBytes(512).toString())
    }

    @Test
    fun formatKilobytes() {
        assertEquals("1kb", KarandashSize.ofKb(1).toString())
    }

    @Test
    fun formatMegabytes() {
        assertEquals("10mb", KarandashSize.ofMb(10).toString())
    }

    @Test
    fun formatGigabytes() {
        assertEquals("1gb", KarandashSize.ofGb(1).toString())
    }

    @Test
    fun formatZero() {
        assertEquals("0b", KarandashSize.ZERO.toString())
    }

    @Test
    fun formatNonEvenFallsToBytes() {
        assertEquals("1025b", KarandashSize.ofBytes(1025).toString())
    }

    @Test
    fun formatRoundTrip() {
        val original = KarandashSize.ofMb(10)
        assertEquals(original, KarandashSize.parse(original.toString()))
    }

    @Test
    fun compareTo() {
        assertTrue(KarandashSize.ofMb(1) > KarandashSize.ofKb(512))
    }

    @Test
    fun accessors() {
        val size = KarandashSize.ofMb(10)
        assertEquals(10L, size.mb)
        assertEquals(10L * 1_024, size.kb)
        assertEquals(0L, size.gb)
    }

    @Test
    fun `an overflowing size is a parse error`() {
        listOf("99999999999999999999b", "${Long.MAX_VALUE}tb").forEach { input ->
            val e = assertFailsWith<IllegalStateException>(input) { KarandashSize.parse(input) }
            assertTrue("too large" in e.message.orEmpty(), e.message)
        }
    }
}
