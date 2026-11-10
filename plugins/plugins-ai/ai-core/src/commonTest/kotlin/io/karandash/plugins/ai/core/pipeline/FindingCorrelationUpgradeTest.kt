package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FindingCorrelationUpgradeTest {
    private fun finding(
        rule: String,
        severity: Severity = Severity.INFO,
    ) = Finding(severity, "m", rule)

    @Test
    fun `configured pattern raises matched findings and keeps higher ones`() {
        val pattern = CorrelationPatternConfig(name = "combo", findings = listOf("ai-a", "ai-b"), severity = "error").toPattern()
        val findings = listOf(finding("ai-a"), finding("ai-b", Severity.ERROR), finding("other"))

        val result = FindingCorrelationEngine(listOf(pattern)).correlate(findings)

        val (a, b, other, meta) = result.findings
        assertEquals(Severity.ERROR, a.severity)
        assertEquals("combo", a.metadata[FindingCorrelationEngine.UPGRADED_BY])
        assertNull(b.metadata[FindingCorrelationEngine.UPGRADED_BY])
        assertEquals(Severity.INFO, other.severity)
        assertEquals("correlation:combo", meta.rule)
        assertEquals("Correlated findings: ai-a + ai-b", meta.message)
        assertEquals("ai-a,ai-b", meta.metadata["matchedRules"])
    }

    @Test
    fun `configured pattern message and default severity are used`() {
        val pattern = CorrelationPatternConfig(name = "p", findings = listOf("x"), message = "Custom").toPattern()

        assertEquals("Custom", pattern.message)
        assertEquals(FindingCorrelationEngine.DEFAULT_SEVERITY, pattern.severity)
        assertTrue(pattern.upgradeMatched)
    }

    @Test
    fun `minSeverity requires one finding at or above it`() {
        val pattern = CorrelationPattern(name = "sev", requiredRules = listOf("r"), message = "m", minSeverity = "warning")
        val engine = FindingCorrelationEngine(listOf(pattern))

        assertEquals(0, engine.correlate(listOf(finding("r"))).metaFindingsCount)
        assertEquals(1, engine.correlate(listOf(finding("r"), finding("q", Severity.WARNING))).metaFindingsCount)
    }

    @Test
    fun `merge keeps the most severe then most confident finding`() {
        fun at(
            rule: String,
            severity: Severity,
            confidence: String?,
        ) = Finding(
            severity,
            rule,
            rule,
            location = Location.FileRef("A.kt", 3),
            metadata = listOfNotNull(
                AiFindingMetadataKeys.AI_MODEL to "m",
                confidence?.let { AiFindingMetadataKeys.CONFIDENCE to it },
            ).toMap(),
        )
        val findings = listOf(at("ai-a", Severity.WARNING, "0.9"), at("ai-b", Severity.ERROR, null), at("ai-c", Severity.ERROR, "0.6"))

        val merged = FindingCorrelationEngine().mergeSameLocation(findings).single()

        assertEquals("ai-c", merged.rule)
        assertEquals("ai-a,ai-b,ai-c", merged.metadata[AiFindingMetadataKeys.CORRELATED_SOURCES])
    }
}
