package io.karandash.plugins.ai.core.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConventionExtractorsTest {
    @Test
    fun `editorconfig extracts key rules`() {
        val input = """
            root = true

            [*]
            indent_style = space
            indent_size = 4
            max_line_length = 120
            # comment
            trim_trailing_whitespace = true
        """.trimIndent()
        val extracted = ConventionExtractors.extractEditorConfig(input)
        assertTrue(extracted.contains("indent_style=space"))
        assertTrue(extracted.contains("indent_size=4"))
        assertTrue(extracted.contains("max_line_length=120"))
        assertTrue(extracted.contains("trim_trailing_whitespace=true"))
    }

    @Test
    fun `editorconfig empty input returns empty`() {
        assertEquals("", ConventionExtractors.extractEditorConfig(""))
    }

    @Test
    fun `codeowners keeps only matching patterns`() {
        val content = """
            # Security
            /security/*.kt @security-team
            /docs/  @docs-team
            *.md    @docs-team
        """.trimIndent()
        val extracted = ConventionExtractors.extractCodeOwners(content, listOf("security/Auth.kt"))
        assertTrue(extracted.contains("@security-team"))
        assertTrue(!extracted.contains("@docs-team"))
    }

    @Test
    fun `codeowners empty changed paths returns empty`() {
        val content = "*.kt @team"
        assertEquals("", ConventionExtractors.extractCodeOwners(content, emptyList()))
    }

    @Test
    fun `linter extracts only threshold lines`() {
        val input = """
            complexity:
              LongMethod:
                threshold: 120
              LargeClass:
                threshold: 500
              Indentation:
                something: else
        """.trimIndent()
        val extracted = ConventionExtractors.extractLinterRules(input)
        assertTrue(extracted.contains("LongMethod"))
        assertTrue(extracted.contains("LargeClass"))
        assertTrue(!extracted.contains("Indentation"))
    }

    @Test
    fun `pr template keeps headings and checklist items only`() {
        val input = """
            # Description

            Describe your changes here.

            ## Checklist

            - [ ] Tests added
            - [ ] Docs updated
            Extra prose that should be excluded.
        """.trimIndent()
        val extracted = ConventionExtractors.extractPrTemplate(input, maxChars = 1_000)
        assertTrue(extracted.contains("# Description"))
        assertTrue(extracted.contains("## Checklist"))
        assertTrue(extracted.contains("- [ ] Tests added"))
        assertTrue(!extracted.contains("Extra prose"))
    }

    @Test
    fun `contributing keeps only sections matching keywords`() {
        val input = """
            # Introduction
            Not relevant.
            # Code Review
            Always leave a ship-it comment.
            # Unrelated
            Skip this.
        """.trimIndent()
        val extracted = ConventionExtractors.extractContributing(input, maxChars = 1_000)
        assertTrue(extracted.contains("Code Review"))
        assertTrue(!extracted.contains("Unrelated"))
    }

    @Test
    fun `extract dispatches on type`() {
        val editor = ConventionExtractors.extract(
            content = "indent_style = tab",
            type = ConventionExtractors.TYPE_CODE_STYLE,
            changedPaths = emptyList(),
            maxChars = 100,
        )
        assertEquals("indent_style=tab", editor)
    }

    @Test
    fun `extract zero max chars returns empty`() {
        val result = ConventionExtractors.extract(
            content = "anything",
            type = ConventionExtractors.TYPE_CUSTOM,
            changedPaths = emptyList(),
            maxChars = 0,
        )
        assertEquals("", result)
    }

    @Test
    fun `extract respects max chars cap`() {
        val long = "x".repeat(500)
        val result = ConventionExtractors.extract(
            content = long,
            type = ConventionExtractors.TYPE_CUSTOM,
            changedPaths = emptyList(),
            maxChars = 50,
        )
        assertEquals(50, result.length)
    }
}
