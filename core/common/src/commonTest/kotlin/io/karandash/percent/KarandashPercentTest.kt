package io.karandash.percent

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KarandashPercentTest {
    @Test
    fun parsePercentNotation() {
        val p = KarandashPercent.parse("70%")
        assertEquals(0.70, p.fraction, 0.001)
        assertEquals(70, p.percentInt)
    }

    @Test
    fun parseFractionNotation() {
        val p = KarandashPercent.parse("0.7")
        assertEquals(0.70, p.fraction, 0.001)
        assertEquals(70, p.percentInt)
    }

    @Test
    fun parseHundredPercent() {
        val p = KarandashPercent.parse("100%")
        assertEquals(1.0, p.fraction, 0.001)
        assertEquals(100, p.percentInt)
    }

    @Test
    fun parseZeroPercent() {
        val p = KarandashPercent.parse("0%")
        assertEquals(0.0, p.fraction, 0.001)
        assertEquals(0, p.percentInt)
    }

    @Test
    fun outOfRangePercentRejected() {
        assertFailsWith<IllegalArgumentException> {
            KarandashPercent.parse("150%")
        }
    }

    @Test
    fun negativePercentRejected() {
        assertFailsWith<IllegalArgumentException> {
            KarandashPercent.parse("-10%")
        }
    }

    @Test
    fun bareIntegerRejected() {
        assertFailsWith<IllegalArgumentException> {
            KarandashPercent.parse("70")
        }
    }

    @Test
    fun emptyStringRejected() {
        assertFailsWith<IllegalArgumentException> {
            KarandashPercent.parse("")
        }
    }

    @Test
    fun factoryOfPercent() {
        val p = KarandashPercent.ofPercent(50)
        assertEquals(0.5, p.fraction, 0.001)
        assertEquals(50, p.percentInt)
    }

    @Test
    fun factoryOfFraction() {
        val p = KarandashPercent.ofFraction(0.25)
        assertEquals(0.25, p.fraction, 0.001)
        assertEquals(25, p.percentInt)
    }

    @Test
    fun compareTo() {
        assertTrue(KarandashPercent.ofPercent(80) > KarandashPercent.ofPercent(50))
    }

    @Test
    fun toStringFormat() {
        assertEquals("70%", KarandashPercent.ofPercent(70).toString())
        assertEquals("0%", KarandashPercent.ZERO.toString())
        assertEquals("100%", KarandashPercent.HUNDRED.toString())
    }

    @Test
    fun serializationRoundTrip() {
        val original = KarandashPercent.ofPercent(42)
        val serialized = Json.encodeToString(KarandashPercentSerializer, original)
        val deserialized = Json.decodeFromString(KarandashPercentSerializer, serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun constants() {
        assertEquals(0.0, KarandashPercent.ZERO.fraction, 0.001)
        assertEquals(1.0, KarandashPercent.HUNDRED.fraction, 0.001)
    }
}
