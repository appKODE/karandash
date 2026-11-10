package io.karandash.config.pipeline

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnknownTopLevelKeysTest {
    @Test
    fun `known keys in any case style stay silent`() {
        val root = JsonObject(
            listOf("rules", "rule-enrichment", "profile_overrides", "dryRun", "extends").associateWith { JsonPrimitive(1) },
        )
        assertTrue(UnknownTopLevelKeys.warnings(root).isEmpty())
    }

    @Test
    fun `unknown key warns with E0007 and a suggestion`() {
        val warning = UnknownTopLevelKeys.warnings(JsonObject(mapOf("rulez" to JsonObject(emptyMap())))).single()
        assertTrue("[E0007]" in warning && "'rulez'" in warning && "'rules'" in warning, warning)
    }

    @Test
    fun `singular platform key warns and suggests platforms`() {
        val warning = UnknownTopLevelKeys.warnings(JsonObject(mapOf("platform" to JsonPrimitive("gitlab")))).single()
        assertTrue("'platform'" in warning && "'platforms'" in warning, warning)
    }

    @Test
    fun `non-object root yields no warnings`() {
        assertEquals(emptyList(), UnknownTopLevelKeys.warnings(JsonPrimitive("x")))
    }

    @Test
    fun `key claimed by a module configKey is not reported as unknown`() {
        val root = JsonObject(mapOf("my-probe" to JsonObject(emptyMap()), "rulez" to JsonPrimitive(1)))
        val warnings = UnknownTopLevelKeys.warnings(root, claimedKeys = setOf("my-probe"))
        assertTrue(warnings.single().contains("'rulez'"), warnings.toString())
    }

    @Test
    fun `claimed returns module blocks keyed by configKey and never built-in sections`() {
        val block = JsonObject(mapOf("maxTitleLength" to JsonPrimitive(10)))
        val root = JsonObject(mapOf("myProbe" to block, "rules" to JsonObject(emptyMap())))
        assertEquals(
            mapOf("my-probe" to block),
            UnknownTopLevelKeys.claimed(root, setOf("my-probe", "rules", "absent")),
        )
    }

    @Test
    fun `parseFullConfig puts claimed top-level blocks into extensions`() {
        val root: JsonElement = JsonObject(
            mapOf(
                "my-probe" to JsonObject(mapOf("max-title-length" to JsonPrimitive(10))),
                "unclaimed" to JsonPrimitive(1),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(root, extensionKeys = setOf("my-probe")).config
        assertEquals(
            mapOf("my-probe" to JsonObject(mapOf("maxTitleLength" to JsonPrimitive(10)))),
            config.extensions,
        )
        assertTrue(
            ConfigMapParser
                .parseFullConfig(root)
                .config.extensions
                .isEmpty()
        )
    }
}
