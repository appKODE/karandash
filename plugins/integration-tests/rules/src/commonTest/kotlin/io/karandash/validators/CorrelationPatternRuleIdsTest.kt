package io.karandash.validators

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.normalize.ModuleIdNormalizer
import io.karandash.plugins.ai.core.pipeline.FindingCorrelationEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The built-in correlation patterns name rules this tier cannot import, so their ids are plain strings: each must be the
 * id a real rule module contributes, or the pattern silently never fires (`size-limits` against `sizeLimits`).
 */
class CorrelationPatternRuleIdsTest {
    private suspend fun contributed(): List<String> =
        listOf(
            ApprovalRuleModule(),
            SizeLimitsRuleModule(),
            SecurityRuleModule(),
            DescriptionFormatRuleModule(),
        ).flatMap { it.contributedRules() }

    @Test
    fun `every rule a default pattern names is a contributed rule id`() =
        runTest {
            val contributed = contributed()
            for (pattern in FindingCorrelationEngine.DEFAULT_PATTERNS) {
                for (rule in pattern.requiredRules + pattern.absentRules) {
                    assertTrue(
                        contributed.any { ModuleIdNormalizer.matches(it, rule) },
                        "Pattern ${pattern.name} names rule '$rule', which no rule module contributes ($contributed)",
                    )
                }
            }
        }

    @Test
    fun `every default pattern fires on findings with the real rule ids`() =
        runTest {
            val contributed = contributed()
            for (pattern in FindingCorrelationEngine.DEFAULT_PATTERNS) {
                val findings = pattern.requiredRules.map { required ->
                    val rule = contributed.first { ModuleIdNormalizer.matches(it, required) }
                    Finding(severity = Severity.WARNING, message = "m", rule = rule)
                }
                val result = FindingCorrelationEngine(listOf(pattern)).correlate(findings)
                assertEquals(1, result.metaFindingsCount, "Pattern ${pattern.name} did not fire on $findings")
            }
        }
}
