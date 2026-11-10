package io.karandash.config.pipeline

import io.karandash.api.exception.ConfigParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigMapParserAutoApproveTest {
    @Test
    fun `auto-approve section parses with defaults`() {
        val data = mapOf<String, Any?>(
            "auto-approve" to mapOf(
                "enabled" to true,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNotNull(config.autoApprove)
        assertTrue(config.autoApprove!!.enabled)
        assertEquals("info", config.autoApprove!!.maxSeverity)
    }

    @Test
    fun `auto-approve section parses max-severity warning`() {
        val data = mapOf<String, Any?>(
            "auto-approve" to mapOf(
                "enabled" to true,
                "max-severity" to "warning",
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNotNull(config.autoApprove)
        assertTrue(config.autoApprove!!.enabled)
        assertEquals("warning", config.autoApprove!!.maxSeverity)
    }

    @Test
    fun `auto-approve section parses enabled false`() {
        val data = mapOf<String, Any?>(
            "auto-approve" to mapOf(
                "enabled" to false,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNotNull(config.autoApprove)
        assertEquals(false, config.autoApprove!!.enabled)
    }

    @Test
    fun `missing auto-approve section results in null`() {
        val data = mapOf<String, Any?>(
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to ".*"),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNull(config.autoApprove)
    }

    @Test
    fun `auto-approve with snake_case keys normalizes correctly`() {
        val data = mapOf<String, Any?>(
            "auto_approve" to mapOf(
                "max_severity" to "warning",
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNotNull(config.autoApprove)
        assertEquals("warning", config.autoApprove!!.maxSeverity)
    }

    @Test
    fun `max-severity error or an unknown value is rejected`() {
        listOf("error", "ERROR", "bogus").forEach { value ->
            val data = mapOf<String, Any?>("auto-approve" to mapOf("max-severity" to value))
            val e = assertFailsWith<ConfigParseException>(value) { ConfigMapParser.parseFullConfig(data) }
            assertTrue(e.message.orEmpty().contains("maxSeverity"), e.message)
        }
    }
}
