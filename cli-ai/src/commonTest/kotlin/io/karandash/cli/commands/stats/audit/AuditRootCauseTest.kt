package io.karandash.cli.commands.stats.audit

import kotlin.test.Test
import kotlin.test.assertEquals

class AuditRootCauseTest {
    private val options = AuditOptions(bots = setOf(AuditFixtures.BOT), seed = 7)

    @Test
    fun `root cause breakdown of the fixture corpus matches the golden`() {
        val run = StatsAuditRunner.run(AuditFixtures.writeCorpus(AuditFixtures.rootCauseCorpus()), options)

        val golden =
            """
            cause           analyzer        context               size  accepted  rejected precision
            context-cut     naming-style    unknown               XL           0         1      0.0%
            enricher-failed magic-number    codeowners            M            0         1      0.0%
            enricher-failed unused-import   codeowners            M            1         0    100.0%
            none            null-safety     codeowners+jira       S            1         0    100.0%
            """.trimIndent()
        assertEquals(golden, run.rootCauseTable)
    }

    @Test
    fun `left out files or skipped chunks make a diff cut`() {
        val ctx = AuditRootCause.context(
            AuditFixtures.provenanceSummary(1, "reviewed 2/9 files, 2 chunks skipped, 0 truncated", emptyList()).body
        )

        assertEquals(ClusterContext(cause = "diff-cut", context = "unknown", size = "M"), ctx)
    }

    @Test
    fun `russian summary joins with its coverage and context`() {
        val ru = listOf("jira" to "ок", "codeowners" to "ошибка")
        val body = AuditFixtures.provenanceSummary(1, "reviewed 3/4 files, 0 chunks skipped, 0 truncated", ru, label = "Покрытие").body

        assertEquals(ClusterContext(cause = "enricher-failed", context = "jira", size = "S"), AuditRootCause.context(body))
    }

    @Test
    fun `polish summary joins with its coverage`() {
        val body = AuditFixtures.provenanceSummary(1, "reviewed 3/4 files", listOf("jira" to "ok"), label = "Pokrycie").body

        assertEquals(ClusterContext(cause = "none", context = "jira", size = "S"), AuditRootCause.context(body))
    }

    @Test
    fun `coverage line without an enricher table has unknown context`() {
        val body = AuditFixtures.provenanceSummary(1, "reviewed 3/4 files", emptyList()).body

        assertEquals(ClusterContext(cause = "none", context = "unknown", size = "S"), AuditRootCause.context(body))
    }

    @Test
    fun `mr without a provenance summary joins as unknown`() {
        assertEquals(ClusterContext(), AuditRootCause.context(null))
        assertEquals(ClusterContext(), AuditRootCause.context("Reviewed 5/5 files."))
    }
}
