package io.karandash.cli.commands.calibrate

import io.karandash.cli.commands.stats.audit.ClusterLabel
import io.karandash.cli.commands.stats.audit.ClusterOutcome
import io.karandash.cli.commands.stats.audit.MrAudit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyzerCalibrationTest {
    private fun clusters(
        rule: String,
        severity: String?,
        accepted: Int,
        rejected: Int,
    ) = List(accepted) { ClusterOutcome(ClusterLabel.ACCEPTED, hasReply = true, rule = rule, severity = severity) } +
        List(rejected) { ClusterOutcome(ClusterLabel.REJECTED, hasReply = true, rule = rule, severity = severity) }

    private val mrs = listOf(
        MrAudit(project = "p", iid = 1, clusters = clusters("noisy-rule", "ERROR", accepted = 1, rejected = 3)),
        MrAudit(project = "p", iid = 2, clusters = clusters("sharp-rule", "WARNING", accepted = 4, rejected = 1)),
    )

    @Test
    fun `low precision analyzer gets a downgrade and high precision analyzer is kept`() {
        val byRule = AnalyzerCalibration.proposals(mrs).associateBy { it.analyzer }

        assertEquals(0.25, byRule.getValue("noisy-rule").precision)
        assertEquals("WARNING", byRule.getValue("noisy-rule").proposedSeverity)
        assertEquals(0.8, byRule.getValue("sharp-rule").precision)
        assertEquals(null, byRule.getValue("sharp-rule").proposedSeverity)
        assertEquals(AnalyzerAction.KEEP, byRule.getValue("sharp-rule").action)
    }

    @Test
    fun `info analyzer with low precision is told to raise min confidence`() {
        val info = listOf(MrAudit(project = "p", iid = 1, clusters = clusters("quiet-rule", "INFO", accepted = 0, rejected = 4)))

        val proposal = AnalyzerCalibration.proposals(info).single()

        assertEquals(AnalyzerAction.RAISE_MIN_CONFIDENCE, proposal.action)
        assertEquals(null, proposal.proposedSeverity)
    }

    @Test
    fun `proposal is printed as a config snippet only for downgrades`() {
        val text = AnalyzerCalibration.render(AnalyzerCalibration.proposals(mrs))

        assertTrue("Proposal (not applied)" in text, text)
        assertTrue("- rule: noisy-rule" in text, text)
        assertTrue("reclassify: {error: warning}" in text, text)
        assertTrue("- rule: sharp-rule" !in text, text)
    }
}
