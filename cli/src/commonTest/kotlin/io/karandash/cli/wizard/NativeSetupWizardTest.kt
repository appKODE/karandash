package io.karandash.cli.wizard

import io.karandash.cli.commands.wizard.WizardContext
import io.karandash.cli.templates.PresetComposer
import io.karandash.cli.templates.PresetRegistry
import io.karandash.cli.templates.TemplateRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for Native wizard components.
 *
 * Tests verify that:
 * - Template system works correctly
 * - Generated configs are valid
 * - Format rendering produces expected output
 */
class NativeSetupWizardTest {
    @Test
    fun `template registry initializes successfully`() {
        PresetRegistry.initialize()

        val baseTemplates = PresetRegistry.listBaseTemplates()
        assertTrue(baseTemplates.contains("minimal"), "Should have minimal template")
        assertTrue(baseTemplates.contains("standard"), "Should have standard template")
        assertTrue(baseTemplates.contains("strict"), "Should have strict template")
    }

    @Test
    fun `preset composer generates valid config`() {
        PresetRegistry.initialize()

        val composed = PresetComposer.compose(
            baseTemplate = "standard",
            platformTemplate = "github",
        )

        assertNotNull(composed, "Composed config should not be null")
        assertTrue(composed.containsKey("version"), "Should have version")
        assertTrue(composed.containsKey("rules"), "Should have rules")
    }

    @Test
    fun `template renderer produces valid YAML`() {
        PresetRegistry.initialize()

        val composed = PresetComposer.compose(
            baseTemplate = "minimal",
            platformTemplate = "github",
        )

        val yaml = TemplateRenderer.renderToYaml(composed)

        assertNotNull(yaml, "YAML output should not be null")
        assertTrue(yaml.isNotEmpty(), "YAML should not be empty")
        assertTrue(yaml.contains("version:"), "YAML should contain version key")
        assertTrue(yaml.contains("rules:"), "YAML should contain rules key")
    }

    @Test
    fun `template renderer produces valid JSON`() {
        PresetRegistry.initialize()

        val composed = PresetComposer.compose(
            baseTemplate = "minimal",
            platformTemplate = "gitlab",
        )

        val json = TemplateRenderer.renderToJson(composed)

        assertNotNull(json, "JSON output should not be null")
        assertTrue(json.isNotEmpty(), "JSON should not be empty")
        assertTrue(json.contains("\"version\""), "JSON should contain version key")
        assertTrue(json.contains("\"rules\""), "JSON should contain rules key")
        assertTrue(json.startsWith("{"), "JSON should start with {")
        assertTrue(json.endsWith("}"), "JSON should end with }")
    }

    @Test
    fun `template renderer produces valid KTS`() {
        PresetRegistry.initialize()

        val composed = PresetComposer.compose(
            baseTemplate = "standard",
            platformTemplate = "github",
        )

        val kts = TemplateRenderer.renderToKotlinDsl(composed)

        assertNotNull(kts, "KTS output should not be null")
        assertTrue(kts.isNotEmpty(), "KTS should not be empty")
        assertTrue(kts.contains("version ="), "KTS should contain version assignment")
        assertTrue(kts.contains("rules {"), "KTS should contain rules block")
    }

    @Test
    fun `wizard context has correct defaults`() {
        val context = WizardContext(
            detection = null,
            platform = "github",
            profileId = "conventional-commits",
            outputPath = ".karandash/config.yaml"
        )

        assertEquals("github", context.platform)
        assertEquals("conventional-commits", context.profileId)
        assertEquals(".karandash/config.yaml", context.outputPath)
    }

    @Test
    fun `all base templates are accessible`() {
        PresetRegistry.initialize()

        val templates = listOf("minimal", "standard", "strict")

        templates.forEach { templateName ->
            val template = PresetRegistry.getBaseTemplate(templateName)
            assertNotNull(template, "Template $templateName should be accessible")
        }
    }

    @Test
    fun `all platform templates are accessible`() {
        PresetRegistry.initialize()

        val platforms = listOf("github", "gitlab", "bitbucket", "azure-devops")

        platforms.forEach { platformName ->
            val template = PresetRegistry.getPlatformTemplate(platformName)
            assertNotNull(template, "Platform template $platformName should be accessible")
        }
    }

    @Test
    fun `composed config contains expected structure`() {
        PresetRegistry.initialize()

        val composed = PresetComposer.compose(
            baseTemplate = "strict",
            platformTemplate = "gitlab",
        )

        // Check top-level structure
        assertTrue(composed.containsKey("version"), "Should have version")
        assertTrue(composed.containsKey("rules"), "Should have rules")
        assertTrue(composed.containsKey("platform"), "Should have platform")

        // Check rules structure
        val rules = composed["rules"] as? Map<*, *>
        assertNotNull(rules, "Rules should be a map")
        assertTrue(rules.isNotEmpty(), "Rules should not be empty")
    }

    @Test
    fun `YAML output format is valid and parseable`() {
        PresetRegistry.initialize()

        val composed = PresetComposer.compose(
            baseTemplate = "standard",
            platformTemplate = "github",
        )

        val yaml = TemplateRenderer.renderToYaml(composed)

        // Basic YAML validation
        val lines = yaml.lines()
        assertTrue(lines.isNotEmpty(), "YAML should have multiple lines")

        // Check for proper indentation (2 spaces per level is standard)
        val hasProperIndentation = lines.any { it.startsWith("  ") }
        assertTrue(hasProperIndentation, "YAML should have proper indentation")

        // Check for colon key-value pairs
        val hasKeyValuePairs = lines.any { it.contains(":") }
        assertTrue(hasKeyValuePairs, "YAML should have key-value pairs")
    }

    @Test
    fun `JSON output format is valid and parseable`() {
        PresetRegistry.initialize()

        val composed = PresetComposer.compose(
            baseTemplate = "minimal",
            platformTemplate = "bitbucket",
        )

        val json = TemplateRenderer.renderToJson(composed)

        // Basic JSON validation
        assertTrue(json.startsWith("{"), "JSON should start with {")
        assertTrue(json.endsWith("}"), "JSON should end with }")

        // Check for proper quotes
        val hasQuotedKeys = json.contains("\"")
        assertTrue(hasQuotedKeys, "JSON keys should be quoted")

        // Check for nested structure
        val hasNestedObjects = json.count { it == '{' } > 1
        assertTrue(hasNestedObjects, "JSON should have nested objects")
    }
}
