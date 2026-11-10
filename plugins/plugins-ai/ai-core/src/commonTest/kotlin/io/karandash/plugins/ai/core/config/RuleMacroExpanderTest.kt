package io.karandash.plugins.ai.core.config

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleMacroExpanderTest {
    @Test
    fun `unknown macro reference returns empty list`() {
        val macros = emptyMap<String, MacroDefinition>()
        val refs = listOf(MacroReference(whenFilesMatch = listOf("src/**"), useMacro = "strict"))
        val result = RuleMacroExpander.expand(macros, refs)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty references returns empty list`() {
        val macros = mapOf("strict" to MacroDefinition(rules = mapOf("title-format" to JsonPrimitive(true))))
        val result = RuleMacroExpander.expand(macros, emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `valid macro reference is expanded`() {
        val macros = mapOf(
            "strict" to MacroDefinition(rules = mapOf("title-format" to JsonPrimitive(true))),
        )
        val refs = listOf(MacroReference(whenFilesMatch = listOf("src/**"), useMacro = "strict"))
        val result = RuleMacroExpander.expand(macros, refs)
        assertEquals(1, result.size)
        assertEquals("strict", result[0].macroName)
        assertEquals(listOf("src/**"), result[0].filePattern)
        assertEquals(JsonPrimitive(true), result[0].rules["title-format"])
    }

    @Test
    fun `multiple references expand independently`() {
        val macros = mapOf(
            "strict" to MacroDefinition(rules = mapOf("title-format" to JsonPrimitive(true))),
            "lenient" to MacroDefinition(rules = mapOf("commit-messages" to JsonPrimitive(false))),
        )
        val refs = listOf(
            MacroReference(whenFilesMatch = listOf("src/**"), useMacro = "strict"),
            MacroReference(whenFilesMatch = listOf("test/**"), useMacro = "lenient"),
        )
        val result = RuleMacroExpander.expand(macros, refs)
        assertEquals(2, result.size)
        assertEquals("strict", result[0].macroName)
        assertEquals("lenient", result[1].macroName)
    }

    @Test
    fun `whenFilesMatch is a GlobMatcher path glob matched against the whole path`() {
        val expanded = listOf(
            ExpandedMacro(
                filePattern = listOf("*.kt", "src/?.kt"),
                rules = mapOf("title-format" to JsonPrimitive(true)),
                macroName = "kt",
            ),
        )

        assertTrue(RuleMacroExpander.findApplicableRules("src/Foo.kts", expanded).isEmpty())
        assertTrue(RuleMacroExpander.findApplicableRules("lib/src/A.kt", expanded).isEmpty())
        assertEquals(JsonPrimitive(true), RuleMacroExpander.findApplicableRules("src/A.kt", expanded)["title-format"])
        assertEquals(JsonPrimitive(true), RuleMacroExpander.findApplicableRules("Build.kt", expanded)["title-format"])
    }

    @Test
    fun `findApplicableRules matches file path against pattern`() {
        val expanded = listOf(
            ExpandedMacro(
                filePattern = listOf("src/main/**"),
                rules = mapOf("title-format" to JsonPrimitive(true)),
                macroName = "strict",
            ),
        )
        val result = RuleMacroExpander.findApplicableRules("src/main/kotlin/MyClass.kt", expanded)
        assertEquals(JsonPrimitive(true), result["title-format"])
    }

    @Test
    fun `findApplicableRules returns empty for non-matching path`() {
        val expanded = listOf(
            ExpandedMacro(
                filePattern = listOf("src/main/**"),
                rules = mapOf("title-format" to JsonPrimitive(true)),
                macroName = "strict",
            ),
        )
        val result = RuleMacroExpander.findApplicableRules("test/kotlin/MyTest.kt", expanded)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `findApplicableRules strips glob wildcards before matching`() {
        val expanded = listOf(
            ExpandedMacro(
                filePattern = listOf("src/**/*.kt"),
                rules = mapOf("commit-messages" to JsonPrimitive(true)),
                macroName = "kt-strict",
            ),
        )
        val result = RuleMacroExpander.findApplicableRules("src/main/kotlin/Foo.kt", expanded)
        assertEquals(JsonPrimitive(true), result["commit-messages"])
    }

    @Test
    fun `findApplicableRules later macros override earlier ones for same key`() {
        val expanded = listOf(
            ExpandedMacro(
                filePattern = listOf("src/**"),
                rules = mapOf("title-format" to JsonPrimitive(false)),
                macroName = "lenient",
            ),
            ExpandedMacro(
                filePattern = listOf("src/**"),
                rules = mapOf("title-format" to JsonPrimitive(true)),
                macroName = "strict",
            ),
        )
        val result = RuleMacroExpander.findApplicableRules("src/main/Foo.kt", expanded)
        assertEquals(JsonPrimitive(true), result["title-format"])
    }

    @Test
    fun `findApplicableRules merges rules from multiple matching macros`() {
        val expanded = listOf(
            ExpandedMacro(
                filePattern = listOf("src/**"),
                rules = mapOf("title-format" to JsonPrimitive(true)),
                macroName = "macro-a",
            ),
            ExpandedMacro(
                filePattern = listOf("src/**"),
                rules = mapOf("commit-messages" to JsonPrimitive(false)),
                macroName = "macro-b",
            ),
        )
        val result = RuleMacroExpander.findApplicableRules("src/main/Foo.kt", expanded)
        assertEquals(2, result.size)
        assertEquals(JsonPrimitive(true), result["title-format"])
        assertEquals(JsonPrimitive(false), result["commit-messages"])
    }
}
