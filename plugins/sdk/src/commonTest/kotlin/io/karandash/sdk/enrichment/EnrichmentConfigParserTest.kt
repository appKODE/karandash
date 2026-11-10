package io.karandash.sdk.enrichment

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnrichmentConfigParserTest {
    private val knownSubKeys = setOf("tickets", "acceptance-criteria", "linked-issues", "ticket-keys")

    // -- extractSubBlocks --

    @Test
    fun `extractSubBlocks returns known sub-blocks`() {
        val config = buildJsonObject {
            put("url", "https://jira.example.com")
            put("tickets", buildJsonObject { put("enabled", true) })
            put("acceptance-criteria", buildJsonObject { put("enabled", true) })
        }
        val warnings = mutableListOf<String>()
        val result = EnrichmentConfigParser.extractSubBlocks(config, knownSubKeys, "jira", warnings)
        assertEquals(setOf("tickets", "acceptance-criteria"), result.keys)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `extractSubBlocks excludes reserved keys`() {
        val config = buildJsonObject {
            put("url", "https://jira.example.com")
            put("auth", "atlassian")
            put("token", "secret")
            put("cache", buildJsonObject { put("ttl", "10m") })
            put("timeout", "15s")
        }
        val warnings = mutableListOf<String>()
        val result = EnrichmentConfigParser.extractSubBlocks(config, knownSubKeys, "jira", warnings)
        assertTrue(result.isEmpty())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `extractSubBlocks warns and excludes unknown keys`() {
        val config = buildJsonObject {
            put("ticketss", buildJsonObject { put("enabled", true) }) // typo
        }
        val warnings = mutableListOf<String>()
        val result = EnrichmentConfigParser.extractSubBlocks(config, knownSubKeys, "jira", warnings)
        assertTrue(result.isEmpty())
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("enrichment.jira.ticketss"))
    }

    @Test
    fun `extractSubBlocks suggests closest match for typo`() {
        val config = buildJsonObject {
            put("ticketss", buildJsonObject {}) // typo - edit distance 1 from "tickets"
        }
        val warnings = mutableListOf<String>()
        EnrichmentConfigParser.extractSubBlocks(config, knownSubKeys, "jira", warnings)
        assertTrue(warnings[0].contains("did you mean 'tickets'?"))
    }

    @Test
    fun `extractSubBlocks no suggestion when too far from known keys`() {
        val config = buildJsonObject {
            put("xyz123", buildJsonObject {})
        }
        val warnings = mutableListOf<String>()
        EnrichmentConfigParser.extractSubBlocks(config, knownSubKeys, "jira", warnings)
        assertFalse(warnings[0].contains("did you mean"))
    }

    @Test
    fun `enrich list merges with sub-blocks without a warning`() {
        val config = buildJsonObject {
            put(
                "enrich",
                buildJsonArray {
                    add(JsonPrimitive("jira:tickets"))
                    add(JsonPrimitive("jira:linked-issues"))
                }
            )
            put("tickets", buildJsonObject { put("enabled", false) })
        }
        val warnings = mutableListOf<String>()
        val result = EnrichmentConfigParser.extractSubBlocks(config, knownSubKeys, "jira", warnings)
        assertTrue(warnings.isEmpty(), "no warning expected: $warnings")
        // the explicit sub-block wins per key
        assertEquals(buildJsonObject { put("enabled", false) }, result["tickets"])
        // a list entry without a sub-block enables it with defaults
        assertEquals(buildJsonObject { put("enabled", true) }, result["linked-issues"])
    }

    @Test
    fun `enrich list alone enables its sub-blocks without a warning`() {
        val config = buildJsonObject {
            put(
                "enrich",
                buildJsonArray {
                    add(JsonPrimitive("jira:tickets"))
                    add(JsonPrimitive("acceptance-criteria"))
                }
            )
        }
        val warnings = mutableListOf<String>()
        val result = EnrichmentConfigParser.extractSubBlocks(config, knownSubKeys, "jira", warnings)
        assertTrue(warnings.isEmpty())
        assertEquals(setOf("tickets", "acceptance-criteria"), result.keys)
        assertTrue(EnrichmentConfigParser.configWarnings(config, knownSubKeys, "jira").isEmpty())
    }

    // -- closestMatch --

    @Test
    fun `closestMatch finds edit-distance-1 typo`() {
        val result = EnrichmentConfigParser.closestMatch("ticketss", knownSubKeys)
        assertEquals("tickets", result)
    }

    @Test
    fun `closestMatch finds edit-distance-2 transposition`() {
        val result = EnrichmentConfigParser.closestMatch("tikcets", knownSubKeys)
        assertEquals("tickets", result)
    }

    @Test
    fun `closestMatch returns null when too far`() {
        val result = EnrichmentConfigParser.closestMatch("xyz123", knownSubKeys)
        assertNull(result)
    }

    @Test
    fun `configWarnings flags unknown keys only`() {
        val unknown = buildJsonObject { put("tikcets", buildJsonObject { put("enabled", true) }) }
        val unknownWarnings = EnrichmentConfigParser.configWarnings(unknown, knownSubKeys, "jira")
        assertEquals(listOf("enrichment.jira.tikcets: unknown sub-block key (did you mean 'tickets'?)"), unknownWarnings)
    }
}
