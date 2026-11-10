package io.karandash.cli.commands.stats.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AuditMetricsTest {
    private fun stats(
        project: String,
        botNotes: Int,
        labels: List<ClusterLabel>,
        findingNotes: Int = labels.size,
        pushes: Int = 1,
    ) = MrAudit(
        project = project,
        iid = botNotes,
        botNotes = botNotes,
        findingNotes = findingNotes,
        pushes = pushes,
        clusters = labels.map { ClusterOutcome(it, hasReply = it == ClusterLabel.ACCEPTED) },
    )

    private val a = ClusterLabel.ACCEPTED
    private val r = ClusterLabel.REJECTED
    private val u = ClusterLabel.UNKNOWN
    private val o = ClusterLabel.OUTDATED

    @Test
    fun `per mr and per project weighting`() {
        val mrs = listOf(stats("p", 2, listOf(a)), stats("p", 4, listOf(a)), stats("q", 9, listOf(a)))
        val m = AuditMetrics.compute(mrs, seed = 1, resamples = 50)
        assertEquals(5.0, m.notesPerMr.perMr)
        assertEquals(6.0, m.notesPerMr.perProject)
        assertEquals(3, m.notesPerMr.n)
    }

    @Test
    fun `duplicate rate and notes per push`() {
        val m = AuditMetrics.compute(listOf(stats("p", 4, listOf(a, r), findingNotes = 4, pushes = 2)), seed = 1, resamples = 10)
        assertEquals(0.5, m.duplicateRate.perMr)
        assertEquals(2.0, m.notesPerPush.perMr)
    }

    @Test
    fun `precision proxy with bounds reply rate and outdated rate`() {
        val m = AuditMetrics.compute(listOf(stats("p", 4, listOf(a, r, u, o))), seed = 1, resamples = 10)
        assertEquals(0.5, m.precisionProxy.perMr)
        assertEquals(0.25, m.precisionLower.perMr)
        assertEquals(0.5, m.precisionUpper.perMr)
        assertEquals(0.25, m.replyRate.perMr)
        assertEquals(0.25, m.outdatedRate.perMr)
        assertEquals(1, m.labels.accepted)
    }

    @Test
    fun `precision proxy is null without labelled clusters`() {
        val m = AuditMetrics.compute(listOf(stats("p", 1, listOf(u))), seed = 1, resamples = 10)
        assertNull(m.precisionProxy.perMr)
        assertEquals(0, m.precisionProxy.n)
    }

    @Test
    fun `bootstrap is deterministic for a seed and varies across seeds`() {
        val values = listOf(1.0, 5.0, null, 9.0, 2.0, 7.0)
        assertEquals(Bootstrap.meanCi(values, seed = 3, resamples = 200), Bootstrap.meanCi(values, seed = 3, resamples = 200))
        assertNotEquals(Bootstrap.meanCi(values, seed = 3, resamples = 200), Bootstrap.meanCi(values, seed = 4, resamples = 200))
    }

    @Test
    fun `bootstrap of a constant collapses to it`() {
        assertEquals(2.0 to 2.0, Bootstrap.meanCi(listOf(2.0, 2.0, 2.0), seed = 1, resamples = 100))
        assertNull(Bootstrap.meanCi(listOf(null, null), seed = 1, resamples = 100))
    }

    @Test
    fun `percentiles of summary sizes`() {
        val m = AuditMetrics.compute(
            listOf(MrAudit(project = "p", iid = 1, summaryBytes = (1..100).toList())),
            seed = 1,
            resamples = 10,
        )
        assertEquals(50, m.summaryBytesP50)
        assertEquals(95, m.summaryBytesP95)
    }
}
