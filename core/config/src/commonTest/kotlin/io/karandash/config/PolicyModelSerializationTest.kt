package io.karandash.config

import io.karandash.models.ActivePolicy
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Serialization round-trips for policy data models.
 * Verifies that [PolicyConfig], [WhenConfig], and [ActivePolicy] are serializable
 * and round-trip correctly through JSON.
 */
class PolicyModelSerializationTest {
    @Test
    fun `PolicyConfig serializer is resolvable`() {
        assertNotNull(serializer<PolicyConfig>())
    }

    @Test
    fun `WhenConfig serializer is resolvable`() {
        assertNotNull(serializer<WhenConfig>())
    }

    @Test
    fun `ActivePolicy serializer is resolvable`() {
        assertNotNull(serializer<ActivePolicy>())
    }

    @Test
    fun `PolicyConfig round-trips through JSON`() {
        val original = PolicyConfig(
            condition = "is_draft",
            modules = listOf("title-format", "commit-messages"),
            enabled = false,
            severity = "warning",
            override = JsonObject(mapOf("pattern" to JsonPrimitive("^fix:"))),
        )
        val json = KarandashJson.Default.encodeToString(original)
        val decoded = KarandashJson.Default.decodeFromString<PolicyConfig>(json)
        assertEquals(original, decoded)
    }

    @Test
    fun `PolicyConfig with name round-trips through JSON`() {
        val original = PolicyConfig(
            name = "changelog-requirement",
            condition = "title_matches(\"@any-ticket\")",
            modules = listOf("commit-body"),
            override = JsonObject(mapOf("pattern" to JsonPrimitive("^CHANGELOG:"))),
        )
        val json = KarandashJson.Default.encodeToString(original)
        val decoded = KarandashJson.Default.decodeFromString<PolicyConfig>(json)
        assertEquals(original, decoded)
        assertEquals("changelog-requirement", decoded.name)
    }

    @Test
    fun `PolicyConfig minimal round-trips`() {
        val original = PolicyConfig(condition = "has_ticket(PROJ)")
        val json = KarandashJson.Default.encodeToString(original)
        val decoded = KarandashJson.Default.decodeFromString<PolicyConfig>(json)
        assertEquals(original, decoded)
        assertNull(decoded.name)
        assertNull(decoded.modules)
        assertNull(decoded.enabled)
        assertNull(decoded.severity)
        assertNull(decoded.override)
    }

    @Test
    fun `WhenConfig string form serializes as string`() {
        val config = WhenConfig(expression = "is_draft")
        val json = KarandashJson.Default.encodeToString(config)
        assertEquals("\"is_draft\"", json)
    }

    @Test
    fun `WhenConfig object form round-trips`() {
        val config = WhenConfig(
            expression = "has_ticket(PROJ)",
            override = JsonObject(mapOf("required" to JsonPrimitive(true))),
        )
        val json = KarandashJson.Default.encodeToString(config)
        val decoded = KarandashJson.Default.decodeFromString<WhenConfig>(json)
        assertEquals(config, decoded)
    }

    @Test
    fun `WhenConfig deserializes from string`() {
        val decoded = KarandashJson.Default.decodeFromString<WhenConfig>("\"is_draft\"")
        assertEquals("is_draft", decoded.expression)
        assertNull(decoded.override)
    }

    @Test
    fun `WhenConfig fromJsonElement with string primitive`() {
        val element = JsonPrimitive("is_draft")
        val config = WhenConfig.fromJsonElement(element)
        assertNotNull(config)
        assertEquals("is_draft", config.expression)
        assertNull(config.override)
    }

    @Test
    fun `WhenConfig fromJsonElement with object`() {
        val element = JsonObject(
            mapOf(
                "expression" to JsonPrimitive("has_ticket(PROJ)"),
                "override" to JsonObject(mapOf("required" to JsonPrimitive(true))),
            ),
        )
        val config = WhenConfig.fromJsonElement(element)
        assertNotNull(config)
        assertEquals("has_ticket(PROJ)", config.expression)
        assertNotNull(config.override)
        assertTrue(config.override.containsKey("required"))
    }

    @Test
    fun `WhenConfig fromJsonElement returns null for non-string primitive`() {
        val element = JsonPrimitive(42)
        assertNull(WhenConfig.fromJsonElement(element))
    }

    @Test
    fun `ActivePolicy round-trips through JSON`() {
        val original = ActivePolicy(
            condition = "is_draft",
            matched = true,
            affectedModules = listOf("title-format"),
            forcedEnabled = false,
            forcedSeverity = "error",
            configOverrides = JsonObject(mapOf("pattern" to JsonPrimitive("^fix:"))),
        )
        val json = KarandashJson.Default.encodeToString(original)
        val decoded = KarandashJson.Default.decodeFromString<ActivePolicy>(json)
        assertEquals(original, decoded)
    }

    @Test
    fun `ActivePolicy minimal round-trips`() {
        val original = ActivePolicy(
            condition = "is_draft",
            matched = false,
            affectedModules = null,
            forcedEnabled = null,
            forcedSeverity = null,
            configOverrides = null,
        )
        val json = KarandashJson.Default.encodeToString(original)
        val decoded = KarandashJson.Default.decodeFromString<ActivePolicy>(json)
        assertEquals(original, decoded)
    }
}
