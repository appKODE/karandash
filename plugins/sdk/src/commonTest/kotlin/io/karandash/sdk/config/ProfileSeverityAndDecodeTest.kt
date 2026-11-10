package io.karandash.sdk.config

import io.karandash.sdk.enrichment.EnrichmentConfigParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfileSeverityAndDecodeTest {
    @Serializable
    private data class Cfg(
        val limit: Int = 1,
    )

    @Test
    fun `a rule declared with a severity carries it under the reserved key`() {
        val configs = profileRuleConfigs { rule("size", Cfg(5), "warning") }
        val config = configs.getValue("size") as JsonObject
        assertEquals(JsonPrimitive("warning"), config[PROFILE_RULE_SEVERITY_KEY])
        assertEquals(JsonPrimitive(5), config["limit"])
    }

    @Test
    fun `a rule config that is not an object cannot carry a severity`() {
        assertFailsWith<IllegalStateException> { withMetaSeverity("r", JsonPrimitive(1), "error") }
    }

    @Test
    fun `decodeOrDefault warns about unknown sub-blocks and decodes the rest`() {
        val warnings = mutableListOf<String>()
        val config = buildJsonObject {
            put("limit", 3)
            put("bogus", true)
        }
        val result = EnrichmentConfigParser.decodeOrDefault(
            config,
            Cfg.serializer(),
            setOf("limit"),
            "jira",
            warnings::add,
        ) { Cfg(-1) }
        assertEquals(Cfg(3), result)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().startsWith("enrichment.jira.bogus: unknown sub-block key"))
    }

    @Test
    fun `decodeOrDefault falls back to the default for an undecodable config`() {
        val warnings = mutableListOf<String>()
        val result = EnrichmentConfigParser.decodeOrDefault(
            JsonPrimitive("nope"),
            Cfg.serializer(),
            emptySet(),
            "jira",
            warnings::add,
        ) { Cfg(-1) }
        assertEquals(Cfg(-1), result)
        assertTrue(warnings.single().startsWith("enrichment.jira: invalid config, using defaults:"))
    }

    @Test
    fun `camelCase config keys match kebab-case sub-blocks`() {
        val warnings = mutableListOf<String>()
        val config = buildJsonObject { put("ticketKeys", buildJsonObject { put("enabled", true) }) }
        val blocks = EnrichmentConfigParser.extractSubBlocks(config, setOf("ticket-keys"), "jira", warnings)
        assertEquals(setOf("ticket-keys"), blocks.keys)
        assertTrue(warnings.isEmpty())
    }
}
