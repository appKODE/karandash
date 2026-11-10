package io.karandash.state

import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.createTestFinding
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleExecutionResultTest {
    @Test
    fun `round trips through json with findings and timing`() {
        val result = RuleExecutionResult(
            ruleId = "title-format",
            findings = listOf(createTestFinding(ruleId = "title-format", file = "a.kt", line = 3)),
            fromCache = true,
            executionDurationNs = 42,
            startOffsetNs = 5,
            endOffsetNs = 47,
        )
        val json = KarandashJson.Default.encodeToString(RuleExecutionResult.serializer(), result)
        assertEquals(result, KarandashJson.Default.decodeFromString(RuleExecutionResult.serializer(), json))
    }

    @Test
    fun `offsets default to zero when absent`() {
        val decoded = KarandashJson.Default.decodeFromString(
            RuleExecutionResult.serializer(),
            """{"ruleId":"r","findings":[],"fromCache":false,"executionDurationNs":9}""",
        )
        assertEquals(RuleExecutionResult("r", emptyList(), fromCache = false, executionDurationNs = 9), decoded)
        assertEquals(0, decoded.startOffsetNs + decoded.endOffsetNs)
    }
}
