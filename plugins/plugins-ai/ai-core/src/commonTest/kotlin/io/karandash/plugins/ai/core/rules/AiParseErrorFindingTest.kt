package io.karandash.plugins.ai.core.rules

import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An unparseable model reply becomes an INFO finding that carries none of the model text. */
class AiParseErrorFindingTest {
    @Test
    fun `parse error finding has no raw reply text`() {
        val reply = "Sure! Here is my review: the password is hunter2 and the code looks fine"
        val finding = AiRuleAnalyzerUtils.parseErrorFinding("ai-review", reply, "m", 10).single()

        assertEquals(Severity.INFO, finding.severity)
        assertTrue(finding.message.contains("unparseable response"), finding.message)
        assertFalse("hunter2" in finding.message, finding.message)
        assertFalse(finding.metadata.values.any { "hunter2" in it }, finding.metadata.toString())
        assertEquals(reply.length.toString(), finding.metadata["rawResponseLength"])
    }
}
