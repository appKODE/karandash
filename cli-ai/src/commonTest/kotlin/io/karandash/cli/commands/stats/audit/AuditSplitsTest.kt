package io.karandash.cli.commands.stats.audit

import io.karandash.cli.commands.stats.audit.AuditFixtures.mr
import kotlin.test.Test
import kotlin.test.assertEquals

class AuditSplitsTest {
    private fun ids(mrs: List<CorpusMr>) = mrs.map { "${it.project}!${it.iid}" }

    private val early = mr("alpha", 1, "2026-01-01T00:00:00Z", emptyList())
    private val late = mr("alpha", 2, "2026-05-01T00:00:00Z", emptyList())
    private val other = mr("beta", 3, "2026-01-01T00:00:00Z", emptyList())

    @Test
    fun `cutoff splits tune before and test after`() {
        val splits = AuditSplits.split(listOf(early, late), cutoff = "2026-03-01", holdoutProject = null)
        assertEquals(listOf("alpha!1"), ids(splits.tune))
        assertEquals(listOf("alpha!2"), ids(splits.test))
    }

    @Test
    fun `seed case after the cutoff stays tune only`() {
        val seed = mr("group/app-delta", 3082, "2026-05-01T00:00:00Z", emptyList())
        val splits = AuditSplits.split(listOf(seed, late), cutoff = "2026-03-01", holdoutProject = null)
        assertEquals(listOf("group/app-delta!3082"), ids(splits.tune))
        assertEquals(listOf("alpha!2"), ids(splits.test))
    }

    @Test
    fun `holdout project is excluded from tune and kept in holdout`() {
        val splits = AuditSplits.split(listOf(early, other), cutoff = "2026-03-01", holdoutProject = "beta")
        assertEquals(listOf("alpha!1"), ids(splits.tune))
        assertEquals(listOf("beta!3"), ids(splits.holdout))
    }

    @Test
    fun `duplicate diff hash is dropped and the first survives`() {
        val dup = mr("beta", 9, "2026-01-02T00:00:00Z", emptyList(), diffHash = early.diffHash)
        val blankA = mr("beta", 10, "2026-01-02T00:00:00Z", emptyList(), diffHash = "")
        val blankB = mr("beta", 11, "2026-01-02T00:00:00Z", emptyList(), diffHash = "")
        val splits = AuditSplits.split(listOf(early, dup, blankA, blankB), cutoff = null, holdoutProject = null)
        assertEquals(listOf("alpha!1", "beta!10", "beta!11"), ids(splits.all))
        assertEquals(1, splits.duplicatesDropped)
    }

    @Test
    fun `seed case matches by group namespace not only the last path segment`() {
        val byGroup = mr("app-foxtrot/demo-android", 597, "2026-05-01T00:00:00Z", emptyList())
        val byLast = mr("group/sub/app-delta", 3082, "2026-05-01T00:00:00Z", emptyList())
        val lookalike = mr("app-foxtrotx/demo-android", 597, "2026-05-01T00:00:00Z", emptyList())
        val splits = AuditSplits.split(listOf(byGroup, byLast, lookalike), cutoff = "2026-03-01", holdoutProject = null)
        assertEquals(listOf("app-foxtrot/demo-android!597", "group/sub/app-delta!3082"), ids(splits.tune))
        assertEquals(listOf("app-foxtrotx/demo-android!597"), ids(splits.test))
    }

    @Test
    fun `holdout project matches by group namespace or full path`() {
        val grouped = mr("gamma/gamma-android", 5, "2026-01-01T00:00:00Z", emptyList())
        assertEquals(listOf("gamma/gamma-android!5"), ids(AuditSplits.split(listOf(grouped), null, "gamma").holdout))
        assertEquals(listOf("gamma/gamma-android!5"), ids(AuditSplits.split(listOf(grouped), null, "gamma/gamma-android").holdout))
        assertEquals(emptyList(), ids(AuditSplits.split(listOf(grouped), null, "gam").holdout))
    }

    @Test
    fun `holdout project after the cutoff stays out of test`() {
        val lateHoldout = mr("beta", 4, "2026-05-01T00:00:00Z", emptyList())
        val splits = AuditSplits.split(listOf(early, late, lateHoldout), cutoff = "2026-03-01", holdoutProject = "beta")
        assertEquals(listOf("alpha!2"), ids(splits.test))
        assertEquals(listOf("beta!4"), ids(splits.holdout))
        assertEquals(listOf("alpha!1"), ids(splits.tune))
    }
}
