package io.karandash.config.pipeline

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReporterDisableTest {
    @Test
    fun `reporter with false value is marked as disabled`() {
        val raw = mapOf(
            "reporters" to mapOf(
                "console" to false,
                "json" to true
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val reporters = config.reporters
        assertNotNull(reporters)
        assertTrue(isModuleDisabled(reporters["console"]))
        assertEquals(JsonObject(emptyMap()), reporters["json"])
    }

    @Test
    fun `reporter with enabled false in map is marked as disabled`() {
        val raw = mapOf(
            "reporters" to mapOf(
                "slack" to mapOf("enabled" to false, "webhook-url" to "https://example.com")
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val reporters = config.reporters
        assertNotNull(reporters)
        assertTrue(isModuleDisabled(reporters["slack"]))
    }

    @Test
    fun `reporter with enabled true in map is not disabled`() {
        val raw = mapOf(
            "reporters" to mapOf(
                "slack" to mapOf("enabled" to true, "webhook-url" to "https://example.com")
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val reporters = config.reporters
        assertNotNull(reporters)
        assertTrue(!isModuleDisabled(reporters["slack"]))
        assertEquals(
            "https://example.com",
            reporters["slack"]
                ?.jsonObject
                ?.get("webhookUrl")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `reporter with true value is enabled with empty config`() {
        val raw = mapOf(
            "reporters" to mapOf(
                "console" to true
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val reporters = config.reporters
        assertNotNull(reporters)
        assertTrue(!isModuleDisabled(reporters["console"]))
        assertEquals(JsonObject(emptyMap()), reporters["console"])
    }

    @Test
    fun `all reporters false results in map with only disabled entries`() {
        val raw = mapOf(
            "reporters" to mapOf(
                "console" to false,
                "json" to false
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val reporters = config.reporters
        assertNotNull(reporters)
        assertTrue(isModuleDisabled(reporters["console"]))
        assertTrue(isModuleDisabled(reporters["json"]))
    }

    @Test
    fun `null reporters config means not disabled`() {
        assertTrue(!isModuleDisabled(null as JsonElement?))
    }

    @Test
    fun `empty reporters config means not disabled`() {
        assertTrue(!isModuleDisabled(JsonObject(emptyMap()) as JsonElement))
    }

    @Test
    fun `comment-posters with false value is marked as disabled`() {
        val raw = mapOf(
            "platforms" to mapOf(
                "gitlab" to false
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val posters = config.platforms
        assertNotNull(posters)
        assertTrue(isModuleDisabled(posters["gitlab"]))
    }

    @Test
    fun `mutators with false value is marked as disabled`() {
        val raw = mapOf(
            "platforms" to mapOf(
                "gitlab" to false
            )
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        val mutators = config.platforms
        assertNotNull(mutators)
        assertTrue(isModuleDisabled(mutators["gitlab"]))
    }

    @Test
    fun `null reporters block returns null config`() {
        val raw = mapOf(
            "rules" to mapOf("title-format" to mapOf("pattern" to ".*"))
        )
        val config = ConfigMapParser.parseFullConfig(raw).config
        assertNull(config.reporters)
    }
}
