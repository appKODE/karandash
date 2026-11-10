package io.karandash.plugins.ai.core.config

import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GracePeriodEvaluatorTest {
    private fun makeMr(
        labels: List<String> = emptyList(),
        sourceBranch: String = "feature/my-feature",
        draft: Boolean = false,
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = "Test MR",
        description = null,
        sourceBranch = sourceBranch,
        targetBranch = "main",
        author = User(username = "dev", name = "Dev"),
        state = MergeRequestState.OPENED,
        draft = draft,
        labels = labels,
        commits = emptyList(),
        changes = emptyList(),
    )

    @Test
    fun `empty grace periods returns null`() {
        val mr = makeMr()
        assertNull(GracePeriodEvaluator.findActiveGracePeriod(mr, emptyList()))
    }

    @Test
    fun `no matching condition returns null`() {
        val mr = makeMr(labels = listOf("feature"), sourceBranch = "feature/auth", draft = false)
        val gracePeriods = listOf(
            GracePeriodConfig(name = "incident", condition = "labels.contains('incident')"),
        )
        assertNull(GracePeriodEvaluator.findActiveGracePeriod(mr, gracePeriods))
    }

    @Test
    fun `label condition matches when MR carries label`() {
        val mr = makeMr(labels = listOf("incident", "hotfix"))
        val gracePeriods = listOf(
            GracePeriodConfig(name = "incident-grace", condition = "labels.contains('incident')"),
        )
        val result = GracePeriodEvaluator.findActiveGracePeriod(mr, gracePeriods)
        assertEquals("incident-grace", result?.name)
    }

    @Test
    fun `label condition does not match when label absent`() {
        val mr = makeMr(labels = listOf("feature"))
        val gracePeriods = listOf(
            GracePeriodConfig(name = "incident-grace", condition = "labels.contains('incident')"),
        )
        assertNull(GracePeriodEvaluator.findActiveGracePeriod(mr, gracePeriods))
    }

    @Test
    fun `branch condition matches when source branch contains pattern`() {
        val mr = makeMr(sourceBranch = "hotfix/auth-crash")
        val gracePeriods = listOf(
            GracePeriodConfig(name = "hotfix-grace", condition = "branch.matches('hotfix/')"),
        )
        val result = GracePeriodEvaluator.findActiveGracePeriod(mr, gracePeriods)
        assertEquals("hotfix-grace", result?.name)
    }

    @Test
    fun `branch condition does not match when branch differs`() {
        val mr = makeMr(sourceBranch = "feature/new-feature")
        val gracePeriods = listOf(
            GracePeriodConfig(name = "hotfix-grace", condition = "branch.matches('hotfix/')"),
        )
        assertNull(GracePeriodEvaluator.findActiveGracePeriod(mr, gracePeriods))
    }

    @Test
    fun `draft condition matches when MR is draft`() {
        val mr = makeMr(draft = true)
        val gracePeriods = listOf(
            GracePeriodConfig(name = "draft-grace", condition = "draft"),
        )
        val result = GracePeriodEvaluator.findActiveGracePeriod(mr, gracePeriods)
        assertEquals("draft-grace", result?.name)
    }

    @Test
    fun `draft condition does not match when MR is not draft`() {
        val mr = makeMr(draft = false)
        val gracePeriods = listOf(
            GracePeriodConfig(name = "draft-grace", condition = "draft"),
        )
        assertNull(GracePeriodEvaluator.findActiveGracePeriod(mr, gracePeriods))
    }

    @Test
    fun `unknown condition returns null`() {
        val mr = makeMr()
        val gracePeriods = listOf(
            GracePeriodConfig(name = "unknown-grace", condition = "unknownCondition()"),
        )
        assertNull(GracePeriodEvaluator.findActiveGracePeriod(mr, gracePeriods))
    }

    @Test
    fun `a double-quoted labels contains matches`() {
        val mr = makeMr(labels = listOf("incident"))
        val gracePeriods = listOf(GracePeriodConfig(name = "g", condition = "labels.contains(\"incident\")"))
        assertEquals("g", GracePeriodEvaluator.findActiveGracePeriod(mr, gracePeriods)?.name)
    }

    @Test
    fun `a conditions-engine expression activates the grace period`() {
        val gracePeriods = listOf(GracePeriodConfig(name = "g", condition = "is_draft && label_exists(\"hotfix\")"))
        assertEquals("g", GracePeriodEvaluator.findActiveGracePeriod(makeMr(labels = listOf("hotfix"), draft = true), gracePeriods)?.name)
        assertNull(GracePeriodEvaluator.findActiveGracePeriod(makeMr(labels = listOf("hotfix")), gracePeriods))
    }

    @Test
    fun `a condition that does not parse warns and does not apply`() {
        val warnings = mutableListOf<String>()
        val gracePeriods = listOf(GracePeriodConfig(name = "g", condition = "labels.contains('x' &&"))
        assertNull(GracePeriodEvaluator.findActiveGracePeriod(makeMr(labels = listOf("x")), gracePeriods) { warnings += it })
        assertEquals(1, warnings.size, "warnings: $warnings")
        assertTrue(warnings.single().contains("'g'"), warnings.single())
    }

    @Test
    fun `first matching grace period is returned when multiple match`() {
        val mr = makeMr(labels = listOf("incident"), draft = true)
        val gracePeriods = listOf(
            GracePeriodConfig(name = "first", condition = "labels.contains('incident')"),
            GracePeriodConfig(name = "second", condition = "draft"),
        )
        val result = GracePeriodEvaluator.findActiveGracePeriod(mr, gracePeriods)
        assertEquals("first", result?.name)
    }

    @Test
    fun `override rules are accessible on matched grace period`() {
        val mr = makeMr(labels = listOf("incident"))
        val gracePeriods = listOf(
            GracePeriodConfig(
                name = "incident-grace",
                condition = "labels.contains('incident')",
                overrideRules = mapOf("approval" to "skip", "cicd-status" to "warning"),
            ),
        )
        val result = GracePeriodEvaluator.findActiveGracePeriod(mr, gracePeriods)
        assertEquals("skip", result?.overrideRules?.get("approval"))
        assertEquals("warning", result?.overrideRules?.get("cicd-status"))
    }
}
