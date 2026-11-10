package io.karandash.api.config

import io.karandash.api.exception.ConfigParseException
import io.karandash.duration.KarandashDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigDurationTest {
    @Test
    fun `a bad config duration is a ConfigParseException naming the key`() {
        val e = assertFailsWith<ConfigParseException> { parseConfigDuration("cache.ttl", "soon") }

        assertTrue("cache.ttl" in e.message.orEmpty(), e.message)
        assertTrue("soon" in e.message.orEmpty(), e.message)
        assertTrue("30s" in e.hint.orEmpty(), e.hint)
    }

    @Test
    fun `a bare number config duration is a ConfigParseException`() {
        assertFailsWith<ConfigParseException> { parseConfigDuration("timeout", "30") }
    }

    @Test
    fun `a valid config duration parses`() {
        assertEquals(KarandashDuration.ofMinutes(90), parseConfigDuration("timeout", "1h 30m"))
    }

    @Test
    fun `a duration with trailing text is a ConfigParseException`() {
        assertFailsWith<ConfigParseException> { parseConfigDuration("timeout", "1h30") }
    }
}
