package io.karandash.cli.templates

import io.karandash.api.support.mapToJsonElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateRendererTest {
    @Test
    fun `test simple map rendering to DSL`() {
        val config = mapOf(
            "enabled" to true,
            "name" to "test"
        )

        val result = TemplateRenderer.renderToKotlinDsl(mapToJsonElement(config))

        assertTrue(result.contains("enabled = true"))
        assertTrue(result.contains("name = \"test\""))
    }

    @Test
    fun `test nested map rendering`() {
        val config = mapOf(
            "rules" to mapOf(
                "titleFormat" to mapOf(
                    "pattern" to "^feat: .+",
                    "enabled" to true
                )
            )
        )

        val result = TemplateRenderer.renderToKotlinDsl(mapToJsonElement(config))

        assertTrue(result.contains("rules {"))
        assertTrue(result.contains("titleFormat {"))
        assertTrue(result.contains("pattern = \"^feat: .+\""))
        assertTrue(result.contains("enabled = true"))
        assertTrue(result.contains("}"))
    }

    @Test
    fun `test string value escaping`() {
        val config = mapOf(
            "message" to "Hello \"World\"",
            "path" to "C:\\Users\\test",
            "multiline" to "Line1\nLine2"
        )

        val result = TemplateRenderer.renderToKotlinDsl(mapToJsonElement(config))

        assertTrue(result.contains("message = \"Hello \\\"World\\\"\""))
        assertTrue(result.contains("path = \"C:\\\\Users\\\\test\""))
        assertTrue(result.contains("multiline = \"Line1\\nLine2\""))
    }

    @Test
    fun `test boolean rendering`() {
        val config = mapOf(
            "enabled" to true,
            "disabled" to false
        )

        val result = TemplateRenderer.renderToKotlinDsl(mapToJsonElement(config))

        assertTrue(result.contains("enabled = true"))
        assertTrue(result.contains("disabled = false"))
    }

    @Test
    fun `test number rendering`() {
        val config = mapOf(
            "maxLength" to 100,
            "ratio" to 0.75,
            "count" to 42L
        )

        val result = TemplateRenderer.renderToKotlinDsl(mapToJsonElement(config))

        assertTrue(result.contains("maxLength = 100"))
        assertTrue(result.contains("ratio = 0.75"))
        assertTrue(result.contains("count = 42"))
    }

    @Test
    fun `test list rendering`() {
        val config = mapOf(
            "labels" to listOf("bug", "feature", "urgent"),
            "counts" to listOf(1, 2, 3),
            "flags" to listOf(true, false)
        )

        val result = TemplateRenderer.renderToKotlinDsl(mapToJsonElement(config))

        assertTrue(result.contains("labels = listOf(\"bug\", \"feature\", \"urgent\")"))
        assertTrue(result.contains("counts = listOf(1, 2, 3)"))
        assertTrue(result.contains("flags = listOf(true, false)"))
    }

    @Test
    fun `test empty list rendering`() {
        val config = mapOf(
            "items" to emptyList<String>()
        )

        val result = TemplateRenderer.renderToKotlinDsl(mapToJsonElement(config))

        assertTrue(result.contains("items = listOf()"))
    }

    @Test
    fun `test full config rendering`() {
        val config = mapOf(
            "version" to "1.0",
            "rules" to mapOf(
                "titleFormat" to mapOf(
                    "enabled" to true,
                    "pattern" to "^(feat|fix): .+",
                    "maxLength" to 72
                ),
                "requireLabels" to mapOf(
                    "enabled" to false,
                    "labels" to listOf("reviewed", "tested")
                )
            ),
            "reporters" to mapOf(
                "console" to true,
                "markdown" to true
            )
        )

        val result = TemplateRenderer.renderToKotlinDsl(mapToJsonElement(config))

        // Verify structure
        assertTrue(result.contains("version = \"1.0\""))
        assertTrue(result.contains("rules {"))
        assertTrue(result.contains("titleFormat {"))
        assertTrue(result.contains("enabled = true"))
        assertTrue(result.contains("pattern = \"^(feat|fix): .+\""))
        assertTrue(result.contains("maxLength = 72"))
        assertTrue(result.contains("requireLabels {"))
        assertTrue(result.contains("labels = listOf(\"reviewed\", \"tested\")"))
        assertTrue(result.contains("reporters {"))
        assertTrue(result.contains("console = true"))

        // Verify proper closing braces
        val openBraces = result.count { it == '{' }
        val closeBraces = result.count { it == '}' }
        assertEquals(openBraces, closeBraces, "Should have matching braces")
    }

    @Test
    fun `test proper indentation`() {
        val config = mapOf(
            "outer" to mapOf(
                "middle" to mapOf(
                    "inner" to "value"
                )
            )
        )

        val result = TemplateRenderer.renderToKotlinDsl(mapToJsonElement(config))

        // Check indentation levels (4 spaces per level)
        assertTrue(result.contains("outer {"))
        assertTrue(result.contains("    middle {"))
        assertTrue(result.contains("        inner = \"value\""))
        assertTrue(result.contains("    }"))
        assertTrue(result.contains("}"))
    }

    @Test
    fun `test blank lines between top-level blocks`() {
        val config = mapOf(
            "rules" to mapOf("enabled" to true),
            "reporters" to mapOf("console" to true),
            "plugins" to emptyList<String>()
        )

        val result = TemplateRenderer.renderToKotlinDsl(mapToJsonElement(config))

        // Top-level blocks should be separated by blank lines
        val lines = result.split("\n")
        assertTrue(lines.any { it.isBlank() }, "Should have blank lines between top-level blocks")
    }

    @Test
    fun `test rendering from composed YAML`() {
        PresetRegistry.reset()
        PresetRegistry.initialize()

        val composed = PresetComposer.compose("standard", "github")
        val rendered = TemplateRenderer.renderToKotlinDsl(composed)

        // Should contain expected structure
        assertTrue(rendered.contains("version = "))
        assertTrue(rendered.contains("rules {"))
        assertTrue(rendered.isNotEmpty())

        // Verify it's valid Kotlin-like syntax (basic structural check)
        val openBraces = rendered.count { it == '{' }
        val closeBraces = rendered.count { it == '}' }
        assertEquals(openBraces, closeBraces, "Should have balanced braces")
    }

    @Test
    fun `test list of maps rendering`() {
        val config = mapOf(
            "items" to listOf(
                mapOf("name" to "item1", "value" to 1),
                mapOf("name" to "item2", "value" to 2)
            )
        )

        val result = TemplateRenderer.renderToKotlinDsl(mapToJsonElement(config))

        // Lists of maps should render as multiple blocks
        assertTrue(result.contains("items {"))
        assertTrue(result.contains("name = \"item1\""))
        assertTrue(result.contains("value = 1"))
        assertTrue(result.contains("name = \"item2\""))
        assertTrue(result.contains("value = 2"))
    }

    @Test
    fun `test mixed type list rendering`() {
        val config = mapOf(
            "mixed" to listOf("string", 42, true)
        )

        val result = TemplateRenderer.renderToKotlinDsl(mapToJsonElement(config))

        // Should render as listOf with mixed types
        assertTrue(result.contains("mixed = listOf("))
        assertTrue(result.contains("\"string\""))
        assertTrue(result.contains("42"))
        assertTrue(result.contains("true"))
    }

    @AfterTest
    fun cleanup() {
        PresetRegistry.reset()
    }
}
