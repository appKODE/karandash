package io.karandash.cli.commands.calibrate

import io.karandash.cli.commands.stats.audit.ClusterLabel
import io.karandash.cli.commands.stats.audit.ClusterOutcome
import io.karandash.cli.commands.stats.audit.MrAudit
import io.karandash.plugins.ai.core.Learning
import io.karandash.plugins.ai.core.LearningAction
import io.karandash.plugins.ai.core.LearningEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewLearningsTest {
    private fun clusters(
        rule: String,
        accepted: Int,
        rejected: Int,
        path: String = "src/App.kt",
    ) = List(accepted) { ClusterOutcome(ClusterLabel.ACCEPTED, hasReply = true, rule = rule, path = path) } +
        List(rejected) { ClusterOutcome(ClusterLabel.REJECTED, hasReply = true, rule = rule, path = path) }

    private fun mrs(vararg clusters: List<ClusterOutcome>) = clusters.mapIndexed { i, c -> MrAudit(project = "p", iid = i, clusters = c) }

    @Test
    fun `a rule rejected often enough becomes a suppress or downgrade entry`() {
        val learned = ReviewLearnings.learn(
            mrs(clusters("always-wrong", accepted = 0, rejected = 6), clusters("mostly-wrong", accepted = 1, rejected = 5)),
            minRejections = 5,
        )

        assertEquals(
            listOf(
                Learning("always-wrong", action = LearningAction.SUPPRESS, evidence = LearningEvidence(rejected = 6, accepted = 0)),
                Learning("mostly-wrong", action = LearningAction.DOWNGRADE, evidence = LearningEvidence(rejected = 5, accepted = 1)),
            ),
            learned,
        )
    }

    @Test
    fun `evidence below the threshold produces no entry`() {
        val learned = ReviewLearnings.learn(
            mrs(
                clusters("too-few", accepted = 0, rejected = 4),
                clusters("too-mixed", accepted = 2, rejected = 5),
                clusters("useful", accepted = 9, rejected = 1),
            ),
            minRejections = 5,
        )

        assertTrue(learned.isEmpty(), learned.toString())
    }

    @Test
    fun `a rule noisy only in one directory gets an entry for that directory`() {
        val learned = ReviewLearnings.learn(
            mrs(
                clusters("magic-number", accepted = 0, rejected = 5, path = "src/legacy/Old.kt"),
                clusters("magic-number", accepted = 5, rejected = 0, path = "src/core/New.kt"),
            ),
            minRejections = 5,
        )

        assertEquals(listOf("magic-number" to "src/legacy/**"), learned.map { it.rule to it.path })
    }

    @Test
    fun `accepted findings in a subdirectory keep the parent directory from being suppressed`() {
        val learned = ReviewLearnings.learn(
            mrs(
                clusters("magic-number", accepted = 0, rejected = 5, path = "src/legacy/Old.kt"),
                clusters("magic-number", accepted = 5, rejected = 0, path = "src/legacy/deep/Fine.kt"),
            ),
            minRejections = 5,
        )

        // src/legacy/** also matches src/legacy/deep/Fine.kt: 5 rejected of 10 is no entry; the deep dir alone is all accepted
        assertTrue(learned.isEmpty(), learned.toString())
    }

    @Test
    fun `a directory entry counts the whole subtree and a rule in a clean subtree is not dragged along`() {
        val learned = ReviewLearnings.learn(
            mrs(
                clusters("magic-number", accepted = 0, rejected = 3, path = "src/legacy/Old.kt"),
                clusters("magic-number", accepted = 0, rejected = 3, path = "src/legacy/deep/Older.kt"),
                clusters("magic-number", accepted = 5, rejected = 0, path = "src/core/New.kt"),
            ),
            minRejections = 5,
        )

        assertEquals(listOf("src/legacy/**"), learned.map { it.path })
        assertEquals(LearningEvidence(rejected = 6, accepted = 0), learned.single().evidence)
    }

    @Test
    fun `exactly the minimum rejections at exactly the share make an entry and one below either does not`() {
        fun learned(
            accepted: Int,
            rejected: Int,
            min: Int,
        ) = ReviewLearnings.learn(mrs(clusters("r", accepted, rejected)), min)

        assertEquals(1, learned(accepted = 1, rejected = 4, min = 4).size, "4 of 5 is exactly 0.8 and exactly the minimum")
        assertEquals(1, learned(accepted = 2, rejected = 8, min = 8).size, "8 of 10 is exactly 0.8")
        assertTrue(learned(accepted = 1, rejected = 4, min = 5).isEmpty(), "one below the minimum")
        assertTrue(learned(accepted = 3, rejected = 9, min = 5).isEmpty(), "9 of 12 is 0.75, one accepted too many")
        assertTrue(learned(accepted = 2, rejected = 7, min = 5).isEmpty(), "7 of 9 is under 0.8")
    }

    @Test
    fun `merge keeps hand-written entries and replaces generated ones`() {
        val existing =
            """
            |# old header
            |learnings:
            |  - rule: "hand-made"
            |    action: suppress
            |  # a note
            |  - rule: "gone"
            |    path: "src/old/**"
            |    action: suppress
            |    evidence: {rejected: 9, accepted: 0}
            |  - rule: "stays"
            |    action: downgrade
            |    evidence: {rejected: 5, accepted: 1}
            |
            """.trimMargin()
        val fresh = listOf(
            Learning("stays", action = LearningAction.SUPPRESS, evidence = LearningEvidence(rejected = 6, accepted = 0)),
            Learning("new-one", action = LearningAction.SUPPRESS, evidence = LearningEvidence(rejected = 7, accepted = 0)),
        )

        val merged = ReviewLearnings.merge(existing, fresh)

        assertEquals(listOf(1, 1, 1), listOf(merged.kept, merged.added, merged.removed))
        assertTrue("rule: \"hand-made\"" in merged.yaml, merged.yaml)
        assertTrue("\"gone\"" !in merged.yaml, merged.yaml)
        assertTrue(merged.yaml.indexOf("hand-made") < merged.yaml.indexOf("new-one"), merged.yaml)
        assertEquals(1, Regex("\"stays\"").findAll(merged.yaml).count(), merged.yaml)
        val again = ReviewLearnings.merge(merged.yaml, fresh)
        assertEquals(merged.yaml, again.yaml)
        assertEquals(listOf(1, 0, 0), listOf(again.kept, again.added, again.removed))
    }

    @Test
    fun `learnings are written as yaml that matches the golden`() {
        val yaml = ReviewLearnings.yaml(
            listOf(
                Learning("always-wrong", action = LearningAction.SUPPRESS, evidence = LearningEvidence(rejected = 6, accepted = 0)),
                Learning("magic-number", "src/legacy/**", LearningAction.DOWNGRADE, LearningEvidence(rejected = 5, accepted = 1)),
            ),
        )

        assertEquals(
            """
            |# Written by karandash calibrate --learn from review feedback. Review it before merging:
            |# the AI review reads it from the target branch and drops (suppress) or lowers (downgrade) matching findings.
            |learnings:
            |  - rule: "always-wrong"
            |    action: suppress
            |    evidence: {rejected: 6, accepted: 0}
            |  - rule: "magic-number"
            |    path: "src/legacy/**"
            |    action: downgrade
            |    evidence: {rejected: 5, accepted: 1}
            |
            """.trimMargin(),
            yaml,
        )
    }
}
