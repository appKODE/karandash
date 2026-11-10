package io.karandash.sdk.config

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BlockModeTest {
    private val finding = Finding(Severity.INFO, "m", "r")

    @Test
    fun `a mode re-severs a finding or drops it`() {
        assertEquals(Severity.ERROR, BlockMode.FAIL.applyTo(finding)?.severity)
        assertEquals(Severity.WARNING, BlockMode.WARN.applyTo(finding)?.severity)
        assertNull(BlockMode.OFF.applyTo(finding))
    }

    @Test
    fun `parse reads names in any case and boolean aliases`() {
        assertEquals(BlockMode.FAIL, BlockMode.parse(" Fail ", BlockMode.OFF))
        assertEquals(BlockMode.WARN, BlockMode.parse("warn", BlockMode.OFF))
        assertEquals(BlockMode.OFF, BlockMode.parse("OFF", BlockMode.WARN))
        assertEquals(BlockMode.FAIL, BlockMode.parse("true", BlockMode.OFF))
        assertEquals(BlockMode.WARN, BlockMode.parse("false", BlockMode.WARN))
        assertNull(BlockMode.parse("maybe", BlockMode.OFF))
    }

    @Test
    fun `serializers read booleans per their false meaning and write lowercase names`() {
        val json = KarandashJson.Default
        assertEquals(BlockMode.OFF, json.decodeFromString(BlockModeFalseOffSerializer, "false"))
        assertEquals(BlockMode.WARN, json.decodeFromString(BlockModeFalseWarnSerializer, "false"))
        assertEquals(BlockMode.FAIL, json.decodeFromString(BlockModeFalseOffSerializer, "true"))
        assertEquals(BlockMode.WARN, json.decodeFromString(BlockModeFalseOffSerializer, "\"warn\""))
        assertEquals("\"fail\"", json.encodeToString(BlockModeFalseOffSerializer, BlockMode.FAIL))
        assertEquals("io.karandash.sdk.config.BlockMode", BlockModeFalseOffSerializer.descriptor.serialName)
        assertEquals(BlockMode.OFF, json.decodeFromJsonElement(BlockModeSerializer(BlockMode.OFF), JsonPrimitive("off")))
    }

    @Test
    fun `an unknown value fails to decode`() {
        val failure = assertFailsWith<SerializationException> {
            KarandashJson.Default.decodeFromString(BlockModeFalseOffSerializer, "\"maybe\"")
        }
        assertEquals(true, failure.message?.contains("'maybe'"))
    }
}
