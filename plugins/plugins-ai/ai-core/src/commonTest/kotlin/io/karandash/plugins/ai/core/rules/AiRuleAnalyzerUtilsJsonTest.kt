package io.karandash.plugins.ai.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiRuleAnalyzerUtilsJsonTest {
    @Test
    fun `extractJson strips markdown json fence`() {
        val input = "```json\n{\"a\": 1}\n```"
        assertEquals("{\"a\": 1}", AiRuleAnalyzerUtils.extractJson(input))
    }

    @Test
    fun `extractJson strips bare markdown fence`() {
        val input = "```\n{\"a\": 1}\n```"
        assertEquals("{\"a\": 1}", AiRuleAnalyzerUtils.extractJson(input))
    }

    @Test
    fun `extractJson extracts first JSON object from prose`() {
        val input = """Here is the result: {"comments": []} Hope this helps"""
        assertEquals("""{"comments": []}""", AiRuleAnalyzerUtils.extractJson(input))
    }

    @Test
    fun `extractJson extracts JSON array from prose`() {
        val input = """Result: [{"a":1}] done"""
        assertEquals("""[{"a":1}]""", AiRuleAnalyzerUtils.extractJson(input))
    }

    @Test
    fun `extractJson removes trailing commas`() {
        val input = """{"a": 1, "b": 2,}"""
        assertEquals("""{"a": 1, "b": 2}""", AiRuleAnalyzerUtils.extractJson(input))
    }

    @Test
    fun `extractJson removes trailing comma before array close`() {
        val input = "[1, 2, 3,]"
        assertEquals("[1, 2, 3]", AiRuleAnalyzerUtils.extractJson(input))
    }

    @Test
    fun `extractJson handles nested braces in strings`() {
        val input = """{"msg": "use {braces}"}"""
        assertEquals("""{"msg": "use {braces}"}""", AiRuleAnalyzerUtils.extractJson(input))
    }

    @Test
    fun `extractJson returns raw content when no JSON found`() {
        val input = "no json here"
        assertEquals("no json here", AiRuleAnalyzerUtils.extractJson(input))
    }

    @Test
    fun `extractJson combined fence with trailing comma`() {
        val input = "```json\n{\"a\": 1,}\n```"
        assertEquals("{\"a\": 1}", AiRuleAnalyzerUtils.extractJson(input))
    }

    @Test
    fun `extractJson recovers truncated JSON by closing brackets`() {
        val input = """{"findings": [{"message": "bug", "severity": "error"""
        val result = AiRuleAnalyzerUtils.extractJson(input)
        // Should close the open braces/brackets
        assertTrue(result.endsWith("}]}"))
    }

    @Test
    fun `extractJson recovers truncated array`() {
        val input = """[{"a": 1}, {"b": 2"""
        val result = AiRuleAnalyzerUtils.extractJson(input)
        assertTrue(result.endsWith("}]"))
    }

    @Test
    fun `extractJson recovers truncated JSON inside string value`() {
        val input = """{"comments": [{"file": "ci/core.yml", "message": "The regex is overly broad and matches su"""
        val result = AiRuleAnalyzerUtils.extractJson(input)
        assertTrue(result.endsWith("}]}"), "Expected closed brackets, got: ...${result.takeLast(30)}")
        // The truncated string must be closed with a quote so the JSON is parseable
        assertTrue(result.contains("matches su\""), "Expected truncated string to be closed: $result")
    }

    @Test
    fun `extractJson recovers truncated JSON with backticks in string`() {
        val input = """{"comments": [{"message": "Use `map` instead of `forEach"""
        val result = AiRuleAnalyzerUtils.extractJson(input)
        assertTrue(result.endsWith("}]}"), "Expected closed brackets, got: ...${result.takeLast(20)}")
    }

    @Test
    fun `extractJson strips thinking blocks before JSON`() {
        val input = """<think>Let me analyze this code carefully...</think>
{"comments": [{"file": "test.kt", "line": 1}]}"""
        val result = AiRuleAnalyzerUtils.extractJson(input)
        assertEquals("""{"comments": [{"file": "test.kt", "line": 1}]}""", result)
    }

    @Test
    fun `extractJson strips reasoning blocks case insensitive`() {
        val input = """<Reasoning>Some internal thoughts</Reasoning>
{"result": true}"""
        val result = AiRuleAnalyzerUtils.extractJson(input)
        assertEquals("""{"result": true}""", result)
    }

    @Test
    fun `extractJson handles truncated think block followed by JSON`() {
        // If think block is not closed (truncated), extractFirstJsonBlock should still find the JSON
        val input = """<think>analyzing...
{"comments": []}"""
        val result = AiRuleAnalyzerUtils.extractJson(input)
        assertEquals("""{"comments": []}""", result)
    }

    @Test
    fun `extractJson strips single-line comments`() {
        val input = """
{
  // This is a comment
  "a": 1
}
        """.trimIndent()
        val result = AiRuleAnalyzerUtils.extractJson(input)
        assertFalse(result.contains("//"))
        assertTrue(result.contains("\"a\": 1"))
    }

    @Test
    fun `extractJson does not strip double slash inside strings`() {
        val input = """{"url": "https://example.com"}"""
        val result = AiRuleAnalyzerUtils.extractJson(input)
        assertTrue(result.contains("https://example.com"))
    }

    @Test
    fun `extractJsonArrayElements extracts all elements from valid array`() {
        val input = """{"comments": [{"a": 1}, {"b": 2}, {"c": 3}]}"""
        val elements = AiRuleAnalyzerUtils.extractJsonArrayElements(input, "comments")
        assertEquals(3, elements.size)
        assertEquals("""{"a": 1}""", elements[0])
        assertEquals("""{"b": 2}""", elements[1])
        assertEquals("""{"c": 3}""", elements[2])
    }

    @Test
    fun `extractJsonArrayElements returns empty for missing key`() {
        val input = """{"results": [{"a": 1}]}"""
        val elements = AiRuleAnalyzerUtils.extractJsonArrayElements(input, "comments")
        assertTrue(elements.isEmpty())
    }

    @Test
    fun `extractJsonArrayElements handles nested objects`() {
        val input = """{"comments": [{"nested": {"x": [1, 2]}}, {"simple": true}]}"""
        val elements = AiRuleAnalyzerUtils.extractJsonArrayElements(input, "comments")
        assertEquals(2, elements.size)
        assertEquals("""{"nested": {"x": [1, 2]}}""", elements[0])
        assertEquals("""{"simple": true}""", elements[1])
    }

    @Test
    fun `extractJsonArrayElements handles strings with braces`() {
        val input = """{"comments": [{"msg": "use {braces} and [brackets]"}, {"ok": true}]}"""
        val elements = AiRuleAnalyzerUtils.extractJsonArrayElements(input, "comments")
        assertEquals(2, elements.size)
        assertTrue(elements[0].contains("{braces}"))
        assertEquals("""{"ok": true}""", elements[1])
    }

    @Test
    fun `extractJsonArrayElements returns empty for empty array`() {
        val input = """{"comments": []}"""
        val elements = AiRuleAnalyzerUtils.extractJsonArrayElements(input, "comments")
        assertTrue(elements.isEmpty())
    }

    @Test
    fun `extractJsonArrayElements handles whitespace and newlines`() {
        val input = """{
            "comments": [
                {"a": 1},
                {"b": 2}
            ]
        }"""
        val elements = AiRuleAnalyzerUtils.extractJsonArrayElements(input, "comments")
        assertEquals(2, elements.size)
    }
}
