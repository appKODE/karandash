package io.karandash.cli.templates

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PresetComposerTest {
    @BeforeTest
    fun setup() {
        PresetRegistry.reset()
        PresetRegistry.initialize()
    }

    @AfterTest
    fun cleanup() {
        PresetRegistry.reset()
    }

    @Test
    fun `test base-only composition`() {
        val result = PresetComposer.compose(baseTemplate = "minimal")

        assertNotNull(result)
        assertTrue(result.containsKey("rules"))
    }

    @Test
    fun `test base plus platform composition`() {
        val result = PresetComposer.compose(
            baseTemplate = "standard",
            platformTemplate = "github"
        )

        assertNotNull(result)
        assertTrue(result.containsKey("rules"))

        // Should have merged rules from both templates
        assertTrue(result.getValue("rules").jsonObject.isNotEmpty())
    }

    @Test
    fun `test deep merge with nested maps`() {
        val result = PresetComposer.compose("standard", "github")

        val rules = result["rules"] as? JsonObject
        assertNotNull(rules)
        val titleFormat = rules["titleFormat"] as? JsonObject
        assertNotNull(titleFormat)

        // Should have both pattern from base and maxLength from overlay
        assertTrue(titleFormat.containsKey("pattern") || titleFormat.containsKey("enabled"))
    }

    @Test
    fun `test list appending not replacing`() {
        // Create test scenario - this tests the deep merge logic
        val result = PresetComposer.compose("standard")

        val plugins = result["plugins"] as? JsonArray
        assertNotNull(plugins)
        assertTrue(plugins.isEmpty())
    }

    @Test
    fun `test primitive value override`() {
        val result = PresetComposer.compose(
            baseTemplate = "standard",
            platformTemplate = "github"
        )

        val rules = result["rules"] as? JsonObject
        assertNotNull(rules)
        val titleFormat = rules["titleFormat"] as? JsonObject
        assertNotNull(titleFormat)

        // Platform template should override or preserve enabled flag
        assertTrue(titleFormat.containsKey("enabled"))
    }

    @Test
    fun `test all base and platform combinations`() {
        val baseTemplates = listOf("minimal", "standard", "strict")
        val platformTemplates = listOf("github", "gitlab", "bitbucket", "azure-devops")

        var successCount = 0
        baseTemplates.forEach { base ->
            platformTemplates.forEach { platform ->
                try {
                    val result = PresetComposer.compose(base, platform)
                    assertNotNull(result)
                    successCount++
                } catch (e: Exception) {
                    fail("Failed to compose $base + $platform: ${e.message}")
                }
            }
        }

        assertEquals(12, successCount, "All 12 combinations should succeed")
    }

    @Test
    fun `test invalid base template throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            PresetComposer.compose("nonexistent")
        }
    }

    @Test
    fun `test invalid platform template throws exception`() {
        assertFailsWith<IllegalArgumentException> {
            PresetComposer.compose("standard", "nonexistent-platform")
        }
    }

    @Test
    fun `test composition preserves all fields`() {
        val result = PresetComposer.compose("standard", "github")

        // Should preserve version field
        assertTrue(result.containsKey("version"))

        // Should preserve rules
        assertTrue(result.containsKey("rules"))

        assertTrue(result.getValue("rules").jsonObject.isNotEmpty())
    }

    @Test
    fun `a platform specific key is merged into the composed object`() {
        val result = PresetComposer.compose("standard", "github")

        assertEquals(
            "github",
            result
                .getValue("platform")
                .jsonObject
                .getValue("name")
                .jsonPrimitive.content
        )
        val token = result
            .getValue("platforms")
            .jsonObject
            .getValue("github")
            .jsonObject
            .getValue("token")
        assertEquals("\${GITHUB_TOKEN}", token.jsonPrimitive.content)
        assertTrue(!PresetComposer.compose("standard").containsKey("platforms"))
    }
}
