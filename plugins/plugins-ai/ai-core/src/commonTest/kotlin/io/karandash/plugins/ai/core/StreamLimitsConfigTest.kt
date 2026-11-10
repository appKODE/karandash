package io.karandash.plugins.ai.core

import io.karandash.api.exception.ConfigParseException
import io.karandash.api.support.mapToJsonElement
import io.karandash.duration.KarandashDuration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `ai.streamDeadline` and `ai.streamStall`: defaults, values and bad values. */
class StreamLimitsConfigTest {
    private fun parse(map: Map<String, Any?>) = AiPluginConfig.fromPluginConfig(mapToJsonElement(map))

    @Test
    fun `defaults are a 5m deadline and a 60s stall`() {
        val cfg = parse(emptyMap())

        assertEquals(KarandashDuration.ofMinutes(5), cfg.streamDeadline)
        assertEquals(KarandashDuration.ofSeconds(60), cfg.streamStall)
    }

    @Test
    fun `streamDeadline and streamStall are read as durations`() {
        val cfg = parse(mapOf("streamDeadline" to "10m", "streamStall" to "90s"))

        assertEquals(KarandashDuration.ofMinutes(10), cfg.streamDeadline)
        assertEquals(KarandashDuration.ofSeconds(90), cfg.streamStall)
    }

    @Test
    fun `a bad or non positive value names the key`() {
        val bad = assertFailsWith<ConfigParseException> { parse(mapOf("streamDeadline" to "soon")) }
        val zero = assertFailsWith<ConfigParseException> { parse(mapOf("streamStall" to "0s")) }

        assertContains(bad.message.orEmpty(), "ai.streamDeadline")
        assertContains(zero.message.orEmpty(), "ai.streamStall")
    }
}
