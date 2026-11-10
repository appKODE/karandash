package io.karandash.utils

import io.karandash.duration.KarandashDuration
import kotlin.test.Test
import kotlin.test.assertEquals

class DurationFormatterTest {
    @Test
    fun `a logged duration prints like a config duration`() {
        listOf(0L, 1L, 5_230L, 60_000L, 293_595L, 3_661_000L, 90_061_001L).forEach { ms ->
            assertEquals(KarandashDuration.ofMillis(ms).toString(), DurationFormatter.formatMs(ms))
        }
    }

    @Test
    fun zeroMilliseconds() {
        assertEquals("0s", DurationFormatter.formatMs(0))
    }

    @Test
    fun subSecondMilliseconds() {
        assertEquals("500ms", DurationFormatter.formatMs(500))
        assertEquals("999ms", DurationFormatter.formatMs(999))
        assertEquals("1ms", DurationFormatter.formatMs(1))
    }

    @Test
    fun exactlyOneSecond() {
        assertEquals("1s", DurationFormatter.formatMs(1_000))
    }

    @Test
    fun subMinuteSeconds() {
        assertEquals("5s 230ms", DurationFormatter.formatMs(5_230))
        assertEquals("59s 999ms", DurationFormatter.formatMs(59_999))
    }

    @Test
    fun exactlyOneMinute() {
        assertEquals("1m", DurationFormatter.formatMs(60_000))
    }

    @Test
    fun subHourMinutes() {
        assertEquals("4m 53s 595ms", DurationFormatter.formatMs(293_595))
        assertEquals("59m 59s 999ms", DurationFormatter.formatMs(3_599_999))
    }

    @Test
    fun exactlyOneHour() {
        assertEquals("1h", DurationFormatter.formatMs(3_600_000))
    }

    @Test
    fun hoursKeepEveryUnit() {
        assertEquals("1h 1m 1s", DurationFormatter.formatMs(3_661_000))
        assertEquals("2h 30m 5s", DurationFormatter.formatMs(9_005_000))
    }

    @Test
    fun negativeValuesUseAbsolute() {
        assertEquals("500ms", DurationFormatter.formatMs(-500))
        assertEquals("1m", DurationFormatter.formatMs(-60_000))
    }
}
