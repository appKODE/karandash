package io.karandash.config

import io.karandash.api.support.mapToJsonElement
import io.karandash.config.schema.ConfigSections
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.config.toAnyMap
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigExtTest {
    /** The plain-map view of [toJsonElement] these assertions read. */
    private fun Config.toRawMap(): Map<String, Any?> = toJsonElement().toAnyMap()

    @Test
    fun `toRawMap includes rules and providers`() {
        val config = Config(
            rules = RulesConfig(
                ruleConfigs = mapOf("titleFormat" to mapToJsonElement(mapOf("pattern" to "^feat:")))
            ),
            platforms = mapOf("gitlab" to mapToJsonElement(mapOf("token" to "abc")))
        )

        val map = config.toRawMap()
        assertTrue(map.containsKey(ConfigSections.RULES))
        assertTrue(map.containsKey(ConfigSections.PLATFORMS))
    }

    @Test
    fun `toRawMap includes profile when set`() {
        val config = Config(
            profile = ProfileRef(id = "conventional-commits", severityOverride = "error")
        )

        val map = config.toRawMap()

        @Suppress("UNCHECKED_CAST")
        val profileMap = map["profile"] as Map<String, Any?>
        assertEquals("conventional-commits", profileMap["id"])
        assertEquals("error", profileMap["severity"])
    }

    @Test
    fun `toRawMap includes enrichment nested under ai block`() {
        val config = Config(
            enrichment = EnrichmentConfig(generateDescription = true, model = "claude-3")
        )

        val map = config.toRawMap()

        @Suppress("UNCHECKED_CAST")
        val aiMap = map["ai"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val enrichMap = aiMap["enrichment"] as Map<String, Any?>
        assertEquals(true, enrichMap["generateDescription"])
        assertEquals("claude-3", enrichMap["model"])
    }

    @Test
    fun `toRawMap includes dryRun when true`() {
        val config = Config(dryRun = true, dryRunScope = DryRunScope.AI)

        val map = config.toRawMap()
        assertEquals(true, map["dryRun"])
        assertEquals("ai", map["dryRunScope"])
    }

    @Test
    fun `toRawMap omits default values`() {
        val config = Config()

        val map = config.toRawMap()
        assertFalse(map.containsKey("dryRun"))
        assertFalse(map.containsKey("dryRunScope"))
        assertFalse(map.containsKey("allowLocalhost"))
        assertFalse(map.containsKey("profile"))
        assertFalse(map.containsKey("enrichment"))
        assertFalse(map.containsKey("dangerfile"))
    }

    @Test
    fun `toRawMap includes dangerfile when set`() {
        val config = Config(dangerfile = "Dangerfile.js")

        val map = config.toRawMap()
        assertEquals("Dangerfile.js", map["dangerfile"])
    }

    @Test
    fun `toRawMap includes allowLocalhost when true`() {
        val config = Config(allowLocalhost = true)

        val map = config.toRawMap()
        assertEquals(true, map["allowLocalhost"])
    }

    @Test
    fun `toRawMap includes scripts when set`() {
        val scripts = listOf(ScriptEntryConfig(path = "eslint .", mode = ScriptMode.AUTO))
        val config = Config(scripts = scripts)

        assertEquals(KarandashJson.Plain.encodeToJsonElement(scripts), config.toJsonElement().jsonObject[ConfigSections.SCRIPTS])
    }

    @Test
    fun `toRawMap includes profile overrides`() {
        val config = Config(
            profile = ProfileRef(
                id = "gitflow",
                overrides = ProfileOverrides(
                    rules = mapOf("titleFormat" to mapToJsonElement(mapOf("maxLength" to 50)))
                )
            )
        )

        val map = config.toRawMap()

        @Suppress("UNCHECKED_CAST")
        val profileMap = map["profile"] as Map<String, Any?>
        assertEquals("gitflow", profileMap["id"])
        @Suppress("UNCHECKED_CAST")
        val overrides = profileMap["overrides"] as Map<String, Any?>
        assertTrue(overrides.containsKey("rules"))
    }

    @Test
    fun `toRawMap profile without severity override omits severity`() {
        val config = Config(
            profile = ProfileRef(id = "minimal")
        )

        val map = config.toRawMap()

        @Suppress("UNCHECKED_CAST")
        val profileMap = map["profile"] as Map<String, Any?>
        assertEquals("minimal", profileMap["id"])
        assertNull(profileMap["severity"])
    }
}
