package io.karandash.duration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KarandashDurationTest {
    @Test
    fun parseSeconds() {
        assertEquals(30_000L, KarandashDuration.parse("30s").millis)
    }

    @Test
    fun parseMinutes() {
        assertEquals(300_000L, KarandashDuration.parse("5m").millis)
    }

    @Test
    fun parseHours() {
        assertEquals(7_200_000L, KarandashDuration.parse("2h").millis)
    }

    @Test
    fun parseDays() {
        assertEquals(604_800_000L, KarandashDuration.parse("7d").millis)
    }

    @Test
    fun parseWeeks() {
        assertEquals(604_800_000L, KarandashDuration.parse("1w").millis)
    }

    @Test
    fun parseMonths() {
        assertEquals(2_592_000_000L, KarandashDuration.parse("1mo").millis)
    }

    @Test
    fun parseYears() {
        assertEquals(31_536_000_000L, KarandashDuration.parse("1y").millis)
    }

    @Test
    fun parseCompound() {
        // 1h + 30m = 3_600_000 + 1_800_000
        assertEquals(5_400_000L, KarandashDuration.parse("1h 30m").millis)
    }

    @Test
    fun parseMilliseconds() {
        assertEquals(500L, KarandashDuration.parse("500ms").millis)
    }

    @Test
    fun parseNever() {
        assertEquals(KarandashDuration.NEVER, KarandashDuration.parse("never"))
    }

    @Test
    fun parseNeverSentinelString() {
        assertEquals(KarandashDuration.NEVER, KarandashDuration.parse("-1"))
    }

    @Test
    fun rawNumberRejected() {
        assertFailsWith<IllegalStateException> {
            KarandashDuration.parse("60")
        }
    }

    @Test
    fun invalidInputRejected() {
        assertFailsWith<IllegalStateException> {
            KarandashDuration.parse("abc")
        }
    }

    @Test
    fun parseZero() {
        assertEquals(0L, KarandashDuration.parse("0s").millis)
    }

    @Test
    fun formatRoundTrip() {
        // 1d 2h 30m = 86_400_000 + 7_200_000 + 1_800_000 = 95_400_000
        assertEquals("1d 2h 30m", KarandashDuration.ofMillis(95_400_000L).toString())
    }

    @Test
    fun formatNever() {
        assertEquals("never", KarandashDuration.NEVER.toString())
    }

    @Test
    fun formatZero() {
        assertEquals("0s", KarandashDuration.ZERO.toString())
    }

    @Test
    fun compareTo() {
        assertTrue(KarandashDuration.ofSeconds(60) > KarandashDuration.ofSeconds(30))
    }

    @Test
    fun toKotlinDuration() {
        assertEquals(60.seconds, KarandashDuration.ofSeconds(60).toKotlinDuration())
    }

    @Test
    fun `text outside the unit groups is rejected`() {
        listOf("1h30", "5 millis", "-5s", "2hours", "1h x").forEach { input ->
            assertFailsWith<IllegalStateException>(input) { KarandashDuration.parse(input) }
        }
        assertEquals(5_400_000L, KarandashDuration.parse("1h 30m").millis)
        assertEquals(5_400_000L, KarandashDuration.parse("1h30m").millis)
    }

    @Test
    fun `an overflowing duration is a parse error`() {
        listOf("99999999999999999999s", "${Long.MAX_VALUE}y", "${Long.MAX_VALUE}ms 1ms").forEach { input ->
            val e = assertFailsWith<IllegalStateException>(input) { KarandashDuration.parse(input) }
            assertTrue("too large" in e.message.orEmpty(), e.message)
        }
    }
}
