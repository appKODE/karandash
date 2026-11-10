package io.karandash.plugins.ai.core.config

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.sdk.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeverityReclassifierTest {
    private class CapturingLogger : Logger {
        val messages = mutableListOf<Pair<String, String>>()

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            messages.add("debug" to message)
        }

        override fun info(message: String) {
            messages.add("info" to message)
        }

        override fun warn(message: String) {
            messages.add("warn" to message)
        }

        override fun error(message: String) {
            messages.add("error" to message)
        }
    }

    private fun finding(
        rule: String = "title-format",
        severity: Severity = Severity.ERROR,
        message: String = "Title does not match pattern",
    ) = Finding(severity = severity, message = message, rule = rule)

    @Test
    fun `empty overrides returns findings unchanged`() {
        val findings = listOf(finding(), finding(severity = Severity.WARNING))
        val result = SeverityReclassifier.apply(findings, emptyList())
        assertEquals(findings, result.findings)
        assertEquals(0, result.reclassifiedCount)
    }

    @Test
    fun `reclassify ERROR to WARNING`() {
        val findings = listOf(finding(rule = "title-format", severity = Severity.ERROR))
        val overrides = listOf(
            SeverityOverride(rule = "title-format", reclassify = mapOf("error" to "warning")),
        )
        val result = SeverityReclassifier.apply(findings, overrides)
        assertEquals(1, result.findings.size)
        assertEquals(Severity.WARNING, result.findings[0].severity)
        assertEquals(1, result.reclassifiedCount)
    }

    @Test
    fun `ai prefixed override rule matches the bare finding rule`() {
        val findings = listOf(finding(rule = "review-comments", severity = Severity.ERROR))
        val overrides = listOf(
            SeverityOverride(rule = "ai-review-comments", reclassify = mapOf("error" to "warning")),
        )
        val result = SeverityReclassifier.apply(findings, overrides)
        assertEquals(Severity.WARNING, result.findings[0].severity)
    }

    @Test
    fun `suppress removes finding from results`() {
        val findings = listOf(finding(rule = "draft"), finding(rule = "title-format"))
        val overrides = listOf(SeverityOverride(rule = "draft", suppress = true))
        val result = SeverityReclassifier.apply(findings, overrides)
        assertEquals(1, result.findings.size)
        assertEquals("title-format", result.findings[0].rule)
        assertEquals(1, result.reclassifiedCount)
    }

    @Test
    fun `whenMessageContains filter only matches on message substring`() {
        val matched = finding(rule = "size-limits", message = "Too many additions in generated file")
        val unmatched = finding(rule = "size-limits", message = "Too many additions")
        val overrides = listOf(
            SeverityOverride(
                rule = "size-limits",
                suppress = true,
                whenMessageContains = "generated file",
            ),
        )
        val result = SeverityReclassifier.apply(listOf(matched, unmatched), overrides)
        assertEquals(1, result.findings.size)
        assertEquals("Too many additions", result.findings[0].message)
        assertEquals(1, result.reclassifiedCount)
    }

    @Test
    fun `audit metadata added when severity reclassified`() {
        val findings = listOf(finding(rule = "commit-messages", severity = Severity.ERROR))
        val overrides = listOf(
            SeverityOverride(
                rule = "commit-messages",
                reclassify = mapOf("error" to "info"),
                reason = "legacy repo exemption",
            ),
        )
        val result = SeverityReclassifier.apply(findings, overrides)
        val f = result.findings[0]
        assertEquals("ERROR", f.metadata["reclassifiedFrom"])
        assertEquals("legacy repo exemption", f.metadata["reclassifiedReason"])
    }

    @Test
    fun `non-matching rule is not changed`() {
        val findings = listOf(finding(rule = "branch-naming", severity = Severity.ERROR))
        val overrides = listOf(
            SeverityOverride(rule = "title-format", reclassify = mapOf("error" to "warning")),
        )
        val result = SeverityReclassifier.apply(findings, overrides)
        assertEquals(Severity.ERROR, result.findings[0].severity)
        assertEquals(0, result.reclassifiedCount)
    }

    @Test
    fun `same severity reclassification does not increment count`() {
        val findings = listOf(finding(rule = "title-format", severity = Severity.WARNING))
        val overrides = listOf(
            SeverityOverride(rule = "title-format", reclassify = mapOf("error" to "info")),
        )
        val result = SeverityReclassifier.apply(findings, overrides)
        assertEquals(Severity.WARNING, result.findings[0].severity)
        assertEquals(0, result.reclassifiedCount)
    }

    @Test
    fun `suppress increments count even when no reclassify map`() {
        val findings = listOf(finding(rule = "labels"))
        val overrides = listOf(SeverityOverride(rule = "labels", suppress = true, reason = "known skip"))
        val result = SeverityReclassifier.apply(findings, overrides)
        assertTrue(result.findings.isEmpty())
        assertEquals(1, result.reclassifiedCount)
    }

    @Test
    fun `multiple findings with mixed rules processed correctly`() {
        val findings = listOf(
            finding(rule = "title-format", severity = Severity.ERROR),
            finding(rule = "draft", severity = Severity.WARNING),
            finding(rule = "branch-naming", severity = Severity.INFO),
        )
        val overrides = listOf(
            SeverityOverride(rule = "title-format", reclassify = mapOf("error" to "warning")),
            SeverityOverride(rule = "draft", suppress = true),
        )
        val result = SeverityReclassifier.apply(findings, overrides)
        assertEquals(2, result.findings.size)
        assertFalse(result.findings.any { it.rule == "draft" })
        assertEquals(Severity.WARNING, result.findings.first { it.rule == "title-format" }.severity)
        assertEquals(2, result.reclassifiedCount)
    }

    // ── Logging tests ────────────────────────────────────────────────────

    @Test
    fun `logs suppression decisions`() {
        val logger = CapturingLogger()
        val findings = listOf(finding(rule = "draft"))
        val overrides = listOf(SeverityOverride(rule = "draft", suppress = true))

        SeverityReclassifier.apply(findings, overrides, logger)

        val debugMessages = logger.messages.filter { it.first == "debug" }.map { it.second }
        assertTrue(debugMessages.any { it.contains("suppressed") && it.contains("draft") })
        assertTrue(debugMessages.any { it.contains("0 reclassified, 1 suppressed") })
    }

    @Test
    fun `logs reclassification decisions`() {
        val logger = CapturingLogger()
        val findings = listOf(finding(rule = "title-format", severity = Severity.ERROR))
        val overrides = listOf(
            SeverityOverride(
                rule = "title-format",
                reclassify = mapOf("error" to "warning"),
                reason = "team policy",
            ),
        )

        SeverityReclassifier.apply(findings, overrides, logger)

        val debugMessages = logger.messages.filter { it.first == "debug" }.map { it.second }
        assertTrue(debugMessages.any { it.contains("ERROR") && it.contains("WARNING") && it.contains("team policy") })
        assertTrue(debugMessages.any { it.contains("1 reclassified, 0 suppressed") })
    }

    @Test
    fun `no logging when no overrides match`() {
        val logger = CapturingLogger()
        val findings = listOf(finding(rule = "branch-naming"))
        val overrides = listOf(
            SeverityOverride(rule = "title-format", reclassify = mapOf("error" to "warning")),
        )

        SeverityReclassifier.apply(findings, overrides, logger)

        assertTrue(logger.messages.isEmpty())
    }

    @Test
    fun `wildcard minimumSeverity drops findings below it from every rule`() {
        val overrides = listOf(SeverityOverride(rule = "*", minimumSeverity = "warning"))
        val findings = listOf(
            finding(rule = "a", severity = Severity.INFO),
            finding(rule = "b", severity = Severity.WARNING),
            finding(rule = "c", severity = Severity.ERROR),
        )

        val result = SeverityReclassifier.apply(findings, overrides)

        assertEquals(listOf("b", "c"), result.findings.map { it.rule })
        assertEquals(1, result.reclassifiedCount)
    }

    @Test
    fun `minimumSeverity applies after reclassification by an earlier override`() {
        val overrides = listOf(
            SeverityOverride(rule = "size", reclassify = mapOf("warning" to "info")),
            SeverityOverride(rule = "*", minimumSeverity = "warning"),
        )

        val result = SeverityReclassifier.apply(listOf(finding(rule = "size", severity = Severity.WARNING)), overrides)

        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `minimum-only override does not shadow a later reclassify override`() {
        val overrides = listOf(
            SeverityOverride(rule = "*", minimumSeverity = "info"),
            SeverityOverride(rule = "size", reclassify = mapOf("error" to "warning")),
        )

        val result = SeverityReclassifier.apply(listOf(finding(rule = "size")), overrides)

        assertEquals(Severity.WARNING, result.findings.single().severity)
    }

    @Test
    fun `a code-suggestions twin follows its review comment through an override`() {
        fun ai(
            rule: String,
            line: Int,
        ) = Finding(Severity.ERROR, "msg $rule $line", rule, Location.FileRef("src/A.kt", line))
        val findings = listOf(
            ai(AiRuleIds.REVIEW_COMMENTS, 1),
            ai(AiRuleIds.CODE_SUGGESTIONS, 1),
            ai(AiRuleIds.REVIEW_COMMENTS, 2),
            ai(AiRuleIds.CODE_SUGGESTIONS, 2),
        )
        val overrides = listOf(
            SeverityOverride(rule = AiRuleIds.REVIEW_COMMENTS, whenMessageContains = " 1", suppress = true),
            SeverityOverride(rule = AiRuleIds.REVIEW_COMMENTS, whenMessageContains = " 2", reclassify = mapOf("error" to "info")),
        )

        val result = SeverityReclassifier.apply(findings, overrides)

        assertEquals(listOf(AiRuleIds.REVIEW_COMMENTS, AiRuleIds.CODE_SUGGESTIONS), result.findings.map { it.rule })
        assertTrue(result.findings.all { it.severity == Severity.INFO })
    }
}
