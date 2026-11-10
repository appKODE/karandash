package io.karandash.plugins.ai.core

import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporalRuleTrackerTest {
    private fun finding(
        rule: String = "ai-review",
        severity: Severity = Severity.WARNING,
        message: String = "test finding",
    ) = Finding(
        severity = severity,
        message = message,
        rule = rule,
    )

    @Test
    fun `no prior violations produces no escalation`() {
        val tracker = TemporalRuleTracker()
        val findings = listOf(finding(severity = Severity.INFO))
        val result = tracker.applyEscalation(findings, "dev")

        assertEquals(0, result.escalatedCount)
        assertEquals(Severity.INFO, result.findings.first().severity)
    }

    @Test
    fun `two or more violations escalates INFO to WARNING`() {
        val tracker = TemporalRuleTracker()
        val rule = "title-format"

        // Record 2 prior violations
        repeat(2) {
            tracker.recordViolations("dev", listOf(finding(rule = rule, severity = Severity.WARNING)))
        }

        val findings = listOf(finding(rule = rule, severity = Severity.INFO))
        val result = tracker.applyEscalation(findings, "dev")

        assertEquals(1, result.escalatedCount)
        assertEquals(Severity.WARNING, result.findings.first().severity)
        assertEquals("true", result.findings.first().metadata["temporalEscalation"])
        assertEquals("2", result.findings.first().metadata["priorViolations"])
    }

    @Test
    fun `five or more violations escalates to ERROR`() {
        val tracker = TemporalRuleTracker()
        val rule = "commit-messages"

        repeat(5) {
            tracker.recordViolations("dev", listOf(finding(rule = rule, severity = Severity.WARNING)))
        }

        val findings = listOf(finding(rule = rule, severity = Severity.WARNING))
        val result = tracker.applyEscalation(findings, "dev")

        assertEquals(1, result.escalatedCount)
        assertEquals(Severity.ERROR, result.findings.first().severity)
    }

    @Test
    fun `recordViolations tracks correctly`() {
        val tracker = TemporalRuleTracker()

        tracker.recordViolations("dev", listOf(finding(rule = "rule-a", severity = Severity.ERROR)))
        tracker.recordViolations("dev", listOf(finding(rule = "rule-a", severity = Severity.WARNING)))

        val result = tracker.applyEscalation(listOf(finding(rule = "rule-a", severity = Severity.INFO)), "dev")
        assertEquals(Severity.WARNING, result.findings.first().severity)
    }

    @Test
    fun `window size respected — old violations trimmed`() {
        val windowSize = 3
        val tracker = TemporalRuleTracker(escalationWindow = windowSize)
        val rule = "branch-naming"

        // Record 10 violations; only last 3 should be retained
        repeat(10) {
            tracker.recordViolations("dev", listOf(finding(rule = rule, severity = Severity.ERROR)))
        }

        // Should have exactly windowSize violations recorded
        val result = tracker.applyEscalation(listOf(finding(rule = rule, severity = Severity.INFO)), "dev")
        val priorViolations = result.findings
            .first()
            .metadata["priorViolations"]
            ?.toInt() ?: 0
        assertEquals(windowSize, priorViolations)
    }

    @Test
    fun `serialization roundtrip preserves state`() {
        val tracker = TemporalRuleTracker()
        tracker.recordViolations("alice", listOf(finding(rule = "rule-a", severity = Severity.ERROR)))
        tracker.recordViolations("alice", listOf(finding(rule = "rule-b", severity = Severity.WARNING)))

        val serialized = tracker.serializeState()
        val restored = TemporalRuleTracker.deserializeState(serialized)
        val restoredTracker = TemporalRuleTracker(state = restored)

        // alice has 1 violation for rule-a → at threshold 2 no escalation yet
        val result = restoredTracker.applyEscalation(
            listOf(finding(rule = "rule-a", severity = Severity.INFO)),
            "alice",
        )
        // 1 violation < REPEAT_THRESHOLD(2) → no escalation
        assertEquals(0, result.escalatedCount)

        // Add one more violation and re-check
        restoredTracker.recordViolations("alice", listOf(finding(rule = "rule-a", severity = Severity.ERROR)))
        val result2 = restoredTracker.applyEscalation(
            listOf(finding(rule = "rule-a", severity = Severity.INFO)),
            "alice",
        )
        assertEquals(1, result2.escalatedCount)
    }

    @Test
    fun `corrupt JSON returns empty state`() {
        val state = TemporalRuleTracker.deserializeState("not valid json {{{{")
        assertTrue(state.authors.isEmpty())
    }

    @Test
    fun `different authors tracked independently`() {
        val tracker = TemporalRuleTracker()
        val rule = "ai-review"

        // alice has 5 violations, bob has 0
        repeat(5) {
            tracker.recordViolations("alice", listOf(finding(rule = rule, severity = Severity.WARNING)))
        }

        val aliceResult = tracker.applyEscalation(listOf(finding(rule = rule, severity = Severity.INFO)), "alice")
        val bobResult = tracker.applyEscalation(listOf(finding(rule = rule, severity = Severity.INFO)), "bob")

        assertEquals(Severity.ERROR, aliceResult.findings.first().severity)
        assertEquals(Severity.INFO, bobResult.findings.first().severity)
        assertEquals(0, bobResult.escalatedCount)
    }

    @Test
    fun `INFO findings not recorded as violations`() {
        val tracker = TemporalRuleTracker()
        val rule = "draft"

        // Record 10 INFO findings - none should count as violations
        repeat(10) {
            tracker.recordViolations("dev", listOf(finding(rule = rule, severity = Severity.INFO)))
        }

        val result = tracker.applyEscalation(listOf(finding(rule = rule, severity = Severity.INFO)), "dev")
        assertEquals(0, result.escalatedCount)
        assertFalse(
            result.findings
                .first()
                .metadata
                .containsKey("temporalEscalation")
        )
    }

    @Test
    fun `WARNING finding stays WARNING below habitual threshold`() {
        val tracker = TemporalRuleTracker()
        val rule = "title-format"

        // 3 violations - above REPEAT_THRESHOLD(2) but below HABITUAL_THRESHOLD(5)
        repeat(3) {
            tracker.recordViolations("dev", listOf(finding(rule = rule, severity = Severity.ERROR)))
        }

        val result = tracker.applyEscalation(listOf(finding(rule = rule, severity = Severity.WARNING)), "dev")
        // WARNING stays WARNING (not INFO, so REPEAT branch doesn't change it; not at HABITUAL yet)
        assertEquals(Severity.WARNING, result.findings.first().severity)
        assertEquals(0, result.escalatedCount)
    }

    @Test
    fun `violationMemory ignores violations older than the window`() {
        var now = 0L
        val tracker = TemporalRuleTracker(config = TemporalConfig(violationMemory = 30)) { now }
        repeat(2) { tracker.recordViolations("dev", listOf(finding())) }

        now = 31L * 24 * 60 * 60 * 1000
        val result = tracker.applyEscalation(listOf(finding(severity = Severity.INFO)), "dev")
        assertEquals(0, result.escalatedCount)
    }

    @Test
    fun `escalationFactor divides the repeat and habitual thresholds`() {
        val tracker = TemporalRuleTracker(config = TemporalConfig(escalationFactor = 2.0))
        tracker.recordViolations("dev", listOf(finding()))
        assertEquals(
            Severity.WARNING,
            tracker
                .applyEscalation(listOf(finding(severity = Severity.INFO)), "dev")
                .findings
                .single()
                .severity
        )

        repeat(2) { tracker.recordViolations("dev", listOf(finding())) }
        assertEquals(
            Severity.ERROR,
            tracker
                .applyEscalation(listOf(finding()), "dev")
                .findings
                .single()
                .severity
        )
    }
}
