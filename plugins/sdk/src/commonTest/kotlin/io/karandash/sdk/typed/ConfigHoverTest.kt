package io.karandash.sdk.typed

import io.karandash.api.RuleConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
private data class HoverNested(
    val allowed: List<String> = listOf("feat", "fix"),
) : RuleConfig

@Serializable
private data class HoverFixture(
    val maxLength: Int = 72,
    val format: String = "any",
    val pattern: String? = null,
    @SerialName("noPeriod") val noPeriod: Boolean = false,
    val type: HoverNested? = null,
) : RuleConfig

@Serializable
private data class HoverRequired(
    val name: String,
    val limit: Int = 3,
) : RuleConfig

@Serializable
private data class HoverRejectsEmpty(
    val limit: Int = 0,
) : RuleConfig {
    init {
        require(limit > 0) { "limit must be positive" }
    }
}

/** The generated rule hover keeps the key casing users type and what the old texts said. */
class ConfigHoverTest {
    private val hover =
        configHoverMarkdown(
            "hoverFixture",
            "Checks fixtures.",
            HoverFixture.serializer().descriptor,
            configDefaults(HoverFixture.serializer()),
        )

    @Test
    fun `hover starts with rule id and description`() {
        assertTrue(hover.startsWith("# hoverFixture\n\nChecks fixtures.\n\n## Properties\n"), hover)
    }

    @Test
    fun `hover lists keys in the camelCase users type`() {
        assertTrue("- `maxLength` (int)" in hover, hover)
        assertTrue("- `noPeriod` (boolean)" in hover, hover)
        assertFalse("max_length" in hover || "max-length" in hover, hover)
    }

    @Test
    fun `hover shows non-null defaults and omits null ones`() {
        assertTrue("- `maxLength` (int) - default: `72`" in hover, hover)
        assertTrue("- `format` (string) - default: `\"any\"`" in hover, hover)
        assertTrue("- `noPeriod` (boolean) - default: `false`" in hover, hover)
        assertTrue(hover.lines().any { it == "- `pattern` (string)" }, "null default must not be shown: $hover")
    }

    @Test
    fun `hover nests the keys of a nested class under its key`() {
        val lines = hover.lines()
        val at = lines.indexOfFirst { it.startsWith("- `type`") }
        assertTrue(at >= 0, hover)
        assertEquals("  - `allowed` (list)", lines[at + 1])
    }

    @Test
    fun `a config with a required key shows it as required and no defaults`() {
        val text =
            configHoverMarkdown(
                "req",
                "d",
                HoverRequired.serializer().descriptor,
                configDefaults(HoverRequired.serializer()),
            )
        assertTrue("- `name` (string, required)" in text, text)
        assertTrue("- `limit` (int)" in text && "default" !in text, text)
    }

    @Test
    fun `a config whose initializer rejects an empty config shows no defaults`() {
        assertEquals(0, configDefaults(HoverRejectsEmpty.serializer()).size)
        val text = configHoverMarkdown("rej", "d", HoverRejectsEmpty.serializer().descriptor)
        assertTrue("- `limit` (int)" in text && "default" !in text, text)
    }
}
