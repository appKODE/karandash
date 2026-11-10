package io.karandash.ai.pipeline

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiTraceTest {
    @AfterTest
    fun reset() = AiTrace.enable(false)

    @Test
    fun `outside GitLab CI a section is the header over the body`() {
        assertEquals("head\nbody", AiTrace.section("x", "head", "body") { null })
    }

    @Test
    fun `in GitLab CI a section folds into a collapsed log section`() {
        val text = AiTrace.section("ai_diff_Rule 1", "head", "body") { if (it == "GITLAB_CI") "true" else null }
        val start = Regex("^\u001B\\[0Ksection_start:\\d+:ai_diff_rule_1\\[collapsed=true]\r\u001B\\[0Khead\nbody\n")
        val end = Regex("\u001B\\[0Ksection_end:\\d+:ai_diff_rule_1\r\u001B\\[0K$")
        assertTrue(start.containsMatchIn(text), text)
        assertTrue(end.containsMatchIn(text), text)
    }

    @Test
    fun `enable turns trace on`() {
        AiTrace.enable()
        assertTrue(AiTrace.enabled)
    }
}
