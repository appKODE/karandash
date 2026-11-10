package io.karandash.cli.templates

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test demonstrating the complete template system workflow:
 * Load -> Compose -> Render
 */
class TemplateSystemIntegrationTest {
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
    fun `test complete workflow - load compose render`() {
        // Compose base + platform templates
        val composed = PresetComposer.compose(
            baseTemplate = "standard",
            platformTemplate = "github"
        )

        assertNotNull(composed)
        assertTrue(composed.containsKey("rules"))

        // Render to Kotlin DSL
        val rendered = TemplateRenderer.renderToKotlinDsl(composed)

        assertNotNull(rendered)
        assertTrue(rendered.isNotEmpty())

        // Verify DSL structure
        assertTrue(rendered.contains("rules {"))
        assertTrue(rendered.contains("}"))

        // Should be valid Kotlin-like syntax
        val openBraces = rendered.count { it == '{' }
        val closeBraces = rendered.count { it == '}' }
        assertEquals(openBraces, closeBraces)

        // Output for manual verification (visible in test output)
        println("\n=== Rendered Kotlin DSL ===")
        println(rendered)
        println("=== End DSL ===\n")
    }

    @Test
    fun `test all base-platform combinations render successfully`() {
        val baseTemplates = listOf("minimal", "standard", "strict")
        val platformTemplates = listOf("github", "gitlab", "bitbucket", "azure-devops")

        baseTemplates.forEach { base ->
            platformTemplates.forEach { platform ->
                val composed = PresetComposer.compose(base, platform)
                val rendered = TemplateRenderer.renderToKotlinDsl(composed)

                assertTrue(rendered.isNotEmpty(), "$base + $platform should render non-empty DSL")

                // Verify balanced braces
                val openBraces = rendered.count { it == '{' }
                val closeBraces = rendered.count { it == '}' }
                assertEquals(openBraces, closeBraces, "$base + $platform should have balanced braces")
            }
        }
    }

    @Test
    fun `test rendered output structure`() {
        val composed = PresetComposer.compose("standard", "github")
        val rendered = TemplateRenderer.renderToKotlinDsl(composed)

        // Check for expected top-level keys
        assertTrue(rendered.contains("version = "))
        assertTrue(rendered.contains("rules {"))

        // Check for proper indentation (4 spaces)
        val lines = rendered.split("\n")
        val indentedLines = lines.filter { it.startsWith("    ") }
        assertTrue(indentedLines.isNotEmpty(), "Should have indented lines")
    }
}
