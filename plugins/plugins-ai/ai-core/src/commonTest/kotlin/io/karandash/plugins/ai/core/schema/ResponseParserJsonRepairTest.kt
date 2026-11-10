package io.karandash.plugins.ai.core.schema

import io.karandash.plugins.ai.core.rules.AiRuleAnalyzerUtils
import kotlin.test.Test
import kotlin.test.assertEquals

/** [ResponseParser] owns JSON repair, and repair runs only when the reply is not already valid JSON. */
class ResponseParserJsonRepairTest {
    @Test
    fun `a valid JSON reply is returned untouched even when a string holds repair-looking text`() {
        val reply = """{"message":"list ends with a, ]","note":"see\n// not a comment"}"""

        assertEquals(reply, ResponseParser.extractJson(reply))
    }

    @Test
    fun `a broken reply is repaired as before`() {
        val reply = "Here you go:\n```json\n{\"findings\": [{\"a\": 1},],}\n```"

        assertEquals("""{"findings": [{"a": 1}]}""", ResponseParser.extractJson(reply))
    }

    @Test
    fun `a truncated reply gets its brackets closed`() {
        assertEquals("""{"findings": [{"a": "b"}]}""", ResponseParser.extractJson("""{"findings": [{"a": "b"}"""))
    }

    @Test
    fun `the analyzer helper delegates to the parser`() {
        val reply = "prose {\"a\": [1,]} more prose"

        assertEquals(ResponseParser.extractJson(reply), AiRuleAnalyzerUtils.extractJson(reply))
    }
}
