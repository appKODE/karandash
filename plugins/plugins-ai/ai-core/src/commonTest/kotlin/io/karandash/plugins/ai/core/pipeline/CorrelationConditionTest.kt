package io.karandash.plugins.ai.core.pipeline

import io.karandash.logger.Logger
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorrelationConditionTest {
    private class CapturingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private val plain = CorrelationPatternConfig(name = "plain", findings = listOf("a", "b"))

    private fun names(patterns: List<CorrelationPatternConfig>) = patterns.map { it.name }

    @Test
    fun `patterns without when are returned as is`() {
        val patterns = listOf(plain)

        assertEquals(patterns, CorrelationCondition.filter(patterns, null, emptyList(), CapturingLogger()))
    }

    @Test
    fun `without a merge request only patterns without when survive`() {
        val gated = CorrelationPatternConfig(name = "gated", whenCondition = "is_draft")

        assertEquals(listOf("plain"), names(CorrelationCondition.filter(listOf(plain, gated), null, emptyList(), CapturingLogger())))
    }

    @Test
    fun `when condition keeps matching patterns and drops the rest`() {
        val draft = CorrelationPatternConfig(name = "draft", whenCondition = "is_draft")
        val hotfix = CorrelationPatternConfig(name = "hotfix", whenCondition = "branch *= \"hotfix-*\"")
        val mr = createTestMergeRequest(sourceBranch = "hotfix-login", draft = false)

        val kept = CorrelationCondition.filter(listOf(plain, draft, hotfix), mr, emptyList(), CapturingLogger())

        assertEquals(listOf("plain", "hotfix"), names(kept))
    }

    @Test
    fun `invalid when expression drops the pattern with a warning`() {
        val logger = CapturingLogger()
        val broken = CorrelationPatternConfig(name = "broken", whenCondition = "no_such_predicate((")

        val kept = CorrelationCondition.filter(listOf(plain, broken), createTestMergeRequest(), emptyList(), logger)

        assertEquals(listOf("plain"), names(kept))
        assertTrue(logger.warnings.single().startsWith("Correlation pattern 'broken': invalid when"), logger.warnings.toString())
    }
}
