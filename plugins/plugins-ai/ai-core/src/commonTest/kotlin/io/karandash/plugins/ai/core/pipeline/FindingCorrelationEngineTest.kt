package io.karandash.plugins.ai.core.pipeline

import io.karandash.helpers.RuleIds
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindingCorrelationEngineTest {
    private fun finding(
        rule: String,
        severity: Severity = Severity.WARNING,
    ) = Finding(severity = severity, message = "msg", rule = rule)

    // ── Empty findings ────────────────────────────────────────────────────

    @Test
    fun `empty findings produces no meta-findings`() {
        val result = FindingCorrelationEngine().correlate(emptyList())
        assertEquals(0, result.metaFindingsCount)
        assertTrue(result.findings.isEmpty())
    }

    // ── Pattern matching - requiredRules ──────────────────────────────────

    @Test
    fun `pattern matches when all required rules have findings`() {
        val pattern = CorrelationPattern(
            name = "test-pattern",
            requiredRules = listOf("rule-a", "rule-b"),
            message = "Compound risk detected",
        )
        val engine = FindingCorrelationEngine(listOf(pattern))
        val findings = listOf(finding("rule-a"), finding("rule-b"))
        val result = engine.correlate(findings)
        assertEquals(1, result.metaFindingsCount)
    }

    @Test
    fun `pattern does not match when a required rule is missing`() {
        val pattern = CorrelationPattern(
            name = "test-pattern",
            requiredRules = listOf("rule-a", "rule-b"),
            message = "Compound risk detected",
        )
        val engine = FindingCorrelationEngine(listOf(pattern))
        val findings = listOf(finding("rule-a"))
        val result = engine.correlate(findings)
        assertEquals(0, result.metaFindingsCount)
    }

    @Test
    fun `pattern matches when required rule id is a substring of actual rule id`() {
        val pattern = CorrelationPattern(
            name = "test-pattern",
            requiredRules = listOf("size"),
            message = "Size issue",
        )
        val engine = FindingCorrelationEngine(listOf(pattern))
        val findings = listOf(finding("size-limits"))
        val result = engine.correlate(findings)
        assertEquals(1, result.metaFindingsCount)
    }

    // ── Pattern matching - absentRules ────────────────────────────────────

    @Test
    fun `pattern matches when absent rules have no findings`() {
        val pattern = CorrelationPattern(
            name = "test-pattern",
            requiredRules = listOf("rule-a"),
            absentRules = listOf("rule-b"),
            message = "A without B",
        )
        val engine = FindingCorrelationEngine(listOf(pattern))
        val findings = listOf(finding("rule-a"))
        val result = engine.correlate(findings)
        assertEquals(1, result.metaFindingsCount)
    }

    @Test
    fun `pattern does not match when absent rules have findings`() {
        val pattern = CorrelationPattern(
            name = "test-pattern",
            requiredRules = listOf("rule-a"),
            absentRules = listOf("rule-b"),
            message = "A without B",
        )
        val engine = FindingCorrelationEngine(listOf(pattern))
        val findings = listOf(finding("rule-a"), finding("rule-b"))
        val result = engine.correlate(findings)
        assertEquals(0, result.metaFindingsCount)
    }

    // ── minTotalFindings ──────────────────────────────────────────────────

    @Test
    fun `pattern matches when finding count meets minTotalFindings threshold`() {
        val pattern = CorrelationPattern(
            name = "test-pattern",
            requiredRules = listOf("rule-a"),
            message = "Enough findings",
            minTotalFindings = 2,
        )
        val engine = FindingCorrelationEngine(listOf(pattern))
        val findings = listOf(finding("rule-a"), finding("rule-a"))
        val result = engine.correlate(findings)
        assertEquals(1, result.metaFindingsCount)
    }

    @Test
    fun `pattern does not match when finding count is below minTotalFindings`() {
        val pattern = CorrelationPattern(
            name = "test-pattern",
            requiredRules = listOf("rule-a"),
            message = "Not enough findings",
            minTotalFindings = 3,
        )
        val engine = FindingCorrelationEngine(listOf(pattern))
        val findings = listOf(finding("rule-a"), finding("rule-a"))
        val result = engine.correlate(findings)
        assertEquals(0, result.metaFindingsCount)
    }

    // ── Default patterns ──────────────────────────────────────────────────

    @Test
    fun `default pattern high-risk-unreviewed fires on size-limits without approval findings`() {
        val engine = FindingCorrelationEngine()
        val findings = listOf(finding("size-limits", Severity.ERROR))
        val result = engine.correlate(findings)
        val meta = result.findings.filter { it.rule.startsWith("correlation:") }
        assertTrue(meta.any { it.rule == "correlation:high-risk-unreviewed" })
    }

    @Test
    fun `default pattern high-risk-unreviewed does not fire when approval findings exist`() {
        val engine = FindingCorrelationEngine()
        val findings = listOf(finding("size-limits", Severity.ERROR), finding("approval"))
        val result = engine.correlate(findings)
        assertFalse(result.findings.any { it.rule == "correlation:high-risk-unreviewed" })
    }

    @Test
    fun `default pattern large-undocumented fires on size-limits and description-format`() {
        val engine = FindingCorrelationEngine()
        val findings = listOf(finding("size-limits"), finding("description-format"))
        val result = engine.correlate(findings)
        assertTrue(result.findings.any { it.rule == "correlation:large-undocumented" })
    }

    @Test
    fun `default pattern security-without-review fires on security without approval`() {
        val engine = FindingCorrelationEngine()
        val findings = listOf(finding("security", Severity.ERROR))
        val result = engine.correlate(findings)
        assertTrue(result.findings.any { it.rule == "correlation:security-without-review" })
    }

    @Test
    fun `every default pattern meta-finding carries a suggestion`() {
        val findings = listOf(
            finding("size-limits", Severity.ERROR),
            finding("description-format"),
            finding("security", Severity.ERROR),
        )
        val metas = FindingCorrelationEngine().correlate(findings).findings.filter { it.rule.startsWith("correlation:") }

        assertEquals(3, metas.size)
        metas.forEach { assertFalse(it.suggestion.isNullOrBlank(), "no suggestion on ${it.rule}") }
    }

    @Test
    fun `a configured pattern meta-finding carries a suggestion`() {
        val pattern = CorrelationPatternConfig(name = "combo", findings = listOf("rule-a", "rule-b")).toPattern()
        val result = FindingCorrelationEngine(listOf(pattern)).correlate(listOf(finding("rule-a"), finding("rule-b")))

        val meta = result.findings.single { it.rule == "correlation:combo" }
        assertFalse(meta.suggestion.isNullOrBlank())
    }

    // ── Meta-finding structure ────────────────────────────────────────────

    @Test
    fun `meta-findings have correlation rule prefix`() {
        val pattern = CorrelationPattern(
            name = "my-pattern",
            requiredRules = listOf("rule-x"),
            message = "Risk detected",
            severity = "error",
        )
        val engine = FindingCorrelationEngine(listOf(pattern))
        val result = engine.correlate(listOf(finding("rule-x")))
        val meta = result.findings.first { it.rule.startsWith("correlation:") }
        assertEquals("correlation:my-pattern", meta.rule)
        assertEquals(Severity.ERROR, meta.severity)
    }

    @Test
    fun `meta-findings include matchedRules metadata`() {
        val pattern = CorrelationPattern(
            name = "multi-rule",
            requiredRules = listOf("rule-a", "rule-b"),
            message = "Both triggered",
        )
        val engine = FindingCorrelationEngine(listOf(pattern))
        val result = engine.correlate(listOf(finding("rule-a"), finding("rule-b")))
        val meta = result.findings.first { it.rule.startsWith("correlation:") }
        val matchedRules = meta.metadata["matchedRules"] ?: ""
        assertTrue(matchedRules.contains("rule-a"))
        assertTrue(matchedRules.contains("rule-b"))
    }

    @Test
    fun `meta-findings include correlationType metadata`() {
        val pattern = CorrelationPattern(
            name = "type-check",
            requiredRules = listOf("rule-a"),
            message = "Risk",
        )
        val engine = FindingCorrelationEngine(listOf(pattern))
        val result = engine.correlate(listOf(finding("rule-a")))
        val meta = result.findings.first { it.rule.startsWith("correlation:") }
        assertEquals("compound-risk", meta.metadata["correlationType"])
    }

    // ── Custom patterns override defaults ─────────────────────────────────

    @Test
    fun `custom patterns are used instead of defaults when provided`() {
        val customPattern = CorrelationPattern(
            name = "custom-only",
            requiredRules = listOf("custom-rule"),
            message = "Custom risk",
        )
        val engine = FindingCorrelationEngine(listOf(customPattern))
        // Default patterns would fire on size-limits, but we only have custom patterns
        val findings = listOf(finding("size-limits"), finding("custom-rule"))
        val result = engine.correlate(findings)
        val metaRules = result.findings.filter { it.rule.startsWith("correlation:") }.map { it.rule }
        // Only the custom pattern fires, not the defaults
        assertEquals(listOf("correlation:custom-only"), metaRules)
    }

    // ── Multiple patterns ─────────────────────────────────────────────────

    @Test
    fun `multiple patterns can match simultaneously`() {
        val patternA = CorrelationPattern(
            name = "pattern-a",
            requiredRules = listOf("rule-x"),
            message = "Risk A",
        )
        val patternB = CorrelationPattern(
            name = "pattern-b",
            requiredRules = listOf("rule-y"),
            message = "Risk B",
        )
        val engine = FindingCorrelationEngine(listOf(patternA, patternB))
        val findings = listOf(finding("rule-x"), finding("rule-y"))
        val result = engine.correlate(findings)
        assertEquals(2, result.metaFindingsCount)
        assertTrue(result.findings.any { it.rule == "correlation:pattern-a" })
        assertTrue(result.findings.any { it.rule == "correlation:pattern-b" })
    }

    // ── Original findings preserved ───────────────────────────────────────

    @Test
    fun `original findings are preserved when meta-findings are appended`() {
        val pattern = CorrelationPattern(
            name = "appender",
            requiredRules = listOf("rule-a"),
            message = "Meta",
        )
        val engine = FindingCorrelationEngine(listOf(pattern))
        val original = listOf(finding("rule-a"), finding("rule-b"))
        val result = engine.correlate(original)
        // 2 original + 1 meta
        assertEquals(3, result.findings.size)
        assertTrue(result.findings.containsAll(original))
    }

    // ── patternsEvaluated ─────────────────────────────────────────────────

    @Test
    fun `patternsEvaluated reflects the number of configured patterns`() {
        val patterns = listOf(
            CorrelationPattern(name = "p1", requiredRules = listOf("x"), message = "M1"),
            CorrelationPattern(name = "p2", requiredRules = listOf("y"), message = "M2"),
            CorrelationPattern(name = "p3", requiredRules = listOf("z"), message = "M3"),
        )
        val result = FindingCorrelationEngine(patterns).correlate(emptyList())
        assertEquals(3, result.patternsEvaluated)
    }

    // ── Same-location merge ───────────────────────────────────────────────

    private fun at(
        rule: String,
        line: Int?,
    ) = finding(rule).copy(location = Location.FileRef("A.kt", line))

    @Test
    fun `merge leaves deterministic and file-level findings alone`() {
        val findings = listOf(at("size-limits", 3), at("ai-review", 3), at("ai-security", null), at("ai-review", null))

        assertEquals(findings, FindingCorrelationEngine().mergeSameLocation(findings))
    }

    @Test
    fun `merge keeps one finding per line and names every source`() {
        val findings = listOf(at("ai-review", 3), at("ai-security", 3), at("ai-quality", 3), at("ai-review", 9))

        val merged = FindingCorrelationEngine().mergeSameLocation(findings)

        assertEquals(2, merged.size)
        assertEquals("ai-quality,ai-review,ai-security", merged.first().metadata[AiFindingMetadataKeys.CORRELATED_SOURCES])
    }
}

class FindingCorrelationDefaultsTest {
    @Test
    fun `no default pattern raises an error on its own`() {
        FindingCorrelationEngine.DEFAULT_PATTERNS.forEach {
            assertEquals(FindingCorrelationEngine.DEFAULT_SEVERITY, it.severity, "default pattern ${it.name} is stricter than a warning")
        }
    }

    @Test
    fun `a warning security finding without approval gives a warning meta-finding`() {
        val findings = listOf(Finding(severity = Severity.WARNING, message = "msg", rule = "security"))
        val meta = FindingCorrelationEngine().correlate(findings).findings.single { it.rule == "correlation:security-without-review" }
        assertEquals(Severity.WARNING, meta.severity)
    }

    @Test
    fun `a default pattern matches the camelCase rule id`() {
        val findings = listOf(Finding(severity = Severity.WARNING, message = "msg", rule = "sizeLimits"))
        val metas = FindingCorrelationEngine().correlate(findings).findings.map { it.rule }
        assertTrue("correlation:high-risk-unreviewed" in metas)
    }

    @Test
    fun `fix texts name the approval rule by its config key`() {
        listOf(CorrelationText.HIGH_RISK_UNREVIEWED_FIX, CorrelationText.SECURITY_WITHOUT_REVIEW_FIX).forEach { text ->
            listOf(text.en).forEach {
                assertTrue("(${RuleIds.APPROVAL})" in it, "$text does not name the approval rule key: $it")
                assertFalse("rules." in it, "$text names a config path that does not exist: $it")
            }
        }
    }
}
