package io.karandash.config.pipeline

import io.karandash.api.exception.ConfigParseException
import io.karandash.sdk.config.anyToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** the top-level `version` key declares the config schema version and is checked at load. */
class ConfigSchemaVersionTest {
    private fun load(version: Any?) = ConfigMapParser.parseFullConfig(anyToJsonElement(mapOf("version" to version)))

    @Test
    fun `a version newer than the supported one fails the load naming karandash`() {
        val error = assertFailsWith<ConfigParseException> { load("99.0") }

        assertTrue("99.0" in error.message.orEmpty() && "karandash" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a newer minor of the same major fails the load`() {
        assertFailsWith<ConfigParseException> { load("1.99") }
    }

    @Test
    fun `an equal or lower version loads without warnings`() {
        listOf<Any>(ConfigSchemaVersion.CURRENT, "0.2", 0.2, 1, "1.0").forEach { load(it) }
        assertEquals(emptyList(), UnknownTopLevelKeys.warnings(anyToJsonElement(mapOf("version" to "0.2"))))
    }

    @Test
    fun `a non numeric version fails the load`() {
        assertFailsWith<ConfigParseException> { load("latest") }
    }
}
