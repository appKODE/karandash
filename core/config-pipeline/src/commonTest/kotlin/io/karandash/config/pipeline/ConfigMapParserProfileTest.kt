package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigMapParserProfileTest {
    @Test
    fun `string profile parses to ProfileRef with id`() {
        val data = mapOf<String, Any?>("profile" to "conventional-commits")
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNotNull(config.profile)
        assertEquals("conventional-commits", config.profile!!.id)
        assertNull(config.profile!!.severityOverride)
        assertTrue(
            config.profile!!
                .overrides.rules
                .isEmpty()
        )
    }

    @Test
    fun `object profile parses id severity and overrides`() {
        val data = mapOf<String, Any?>(
            "profile" to mapOf(
                "id" to "conventional-commits",
                "severity" to "error",
                "overrides" to mapOf(
                    "rules" to mapOf(
                        "title-format" to mapOf(
                            "max-length" to 50,
                        ),
                    ),
                ),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNotNull(config.profile)
        assertEquals("conventional-commits", config.profile!!.id)
        assertEquals("error", config.profile!!.severityOverride)
        assertEquals(
            50,
            config.profile!!
                .overrides.rules["titleFormat"]
                ?.jsonObject
                ?.get("maxLength")
                ?.jsonPrimitive
                ?.int
        )
    }

    @Test
    fun `null profile parses to null`() {
        val data = mapOf<String, Any?>("rules" to emptyMap<String, Any?>())
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNull(config.profile)
    }

    @Test
    fun `object profile without id throws ConfigException`() {
        val data = mapOf<String, Any?>(
            "profile" to mapOf("severity" to "error"),
        )
        val ex = assertFailsWith<ConfigException> {
            ConfigMapParser.parseFullConfig(data)
        }
        assertTrue(ex.message!!.contains("E0004"))
    }

    @Test
    fun `invalid profile value throws ConfigException`() {
        val data = mapOf<String, Any?>("profile" to 42)
        val ex = assertFailsWith<ConfigException> {
            ConfigMapParser.parseFullConfig(data)
        }
        assertTrue(ex.message!!.contains("E0008"))
    }

    @Test
    fun `string profile trims whitespace`() {
        val data = mapOf<String, Any?>("profile" to "  minimal  ")
        val config = ConfigMapParser.parseFullConfig(data).config

        assertEquals("minimal", config.profile!!.id)
    }

    @Test
    fun `object profile with no overrides defaults to empty`() {
        val data = mapOf<String, Any?>(
            "profile" to mapOf("id" to "minimal"),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNotNull(config.profile)
        assertEquals("minimal", config.profile!!.id)
        assertTrue(
            config.profile!!
                .overrides.rules
                .isEmpty()
        )
    }
}
