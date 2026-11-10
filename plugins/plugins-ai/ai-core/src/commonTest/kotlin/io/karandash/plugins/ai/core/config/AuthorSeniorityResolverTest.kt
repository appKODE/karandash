package io.karandash.plugins.ai.core.config

import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthorSeniorityResolverTest {
    private fun mr(
        username: String = "dev",
        labels: List<String> = emptyList(),
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = "Test MR",
        description = null,
        sourceBranch = "feature/test",
        targetBranch = "main",
        author = User(username = username, name = "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = labels,
        commits = emptyList(),
        changes = emptyList(),
    )

    @Test
    fun `no matching tier returns null`() {
        val tiers = listOf(
            SeniorityTier(level = SeniorityLevel.JUNIOR, matchTeams = listOf("interns")),
        )
        val result = AuthorSeniorityResolver.resolve(mr(username = "dev"), tiers)
        assertNull(result)
    }

    @Test
    fun `empty tiers returns null`() {
        val result = AuthorSeniorityResolver.resolve(mr(), emptyList())
        assertNull(result)
    }

    @Test
    fun `junior tier matched by label`() {
        val tier = SeniorityTier(level = SeniorityLevel.JUNIOR, matchTeams = listOf("interns"))
        val result = AuthorSeniorityResolver.resolve(mr(labels = listOf("interns")), listOf(tier))
        assertEquals(tier, result)
    }

    @Test
    fun `junior tier matched by author username`() {
        val tier = SeniorityTier(level = SeniorityLevel.JUNIOR, matchTeams = listOf("junior-dev"))
        val result = AuthorSeniorityResolver.resolve(mr(username = "junior-dev"), listOf(tier))
        assertEquals(tier, result)
    }

    @Test
    fun `senior tier matched by label`() {
        val tier = SeniorityTier(level = SeniorityLevel.SENIOR, matchTeams = listOf("senior-engineers"))
        val result = AuthorSeniorityResolver.resolve(mr(labels = listOf("senior-engineers")), listOf(tier))
        assertEquals(tier, result)
    }

    @Test
    fun `matching is case-insensitive for labels`() {
        val tier = SeniorityTier(level = SeniorityLevel.JUNIOR, matchTeams = listOf("Interns"))
        val result = AuthorSeniorityResolver.resolve(mr(labels = listOf("interns")), listOf(tier))
        assertEquals(tier, result)
    }

    @Test
    fun `matching is case-insensitive for username`() {
        val tier = SeniorityTier(level = SeniorityLevel.JUNIOR, matchTeams = listOf("Junior-Dev"))
        val result = AuthorSeniorityResolver.resolve(mr(username = "junior-dev"), listOf(tier))
        assertEquals(tier, result)
    }

    @Test
    fun `first matching tier is returned`() {
        val junior = SeniorityTier(level = SeniorityLevel.JUNIOR, matchTeams = listOf("team-a"))
        val senior = SeniorityTier(level = SeniorityLevel.SENIOR, matchTeams = listOf("team-a"))
        val result = AuthorSeniorityResolver.resolve(mr(labels = listOf("team-a")), listOf(junior, senior))
        assertEquals(junior, result)
    }

    @Test
    fun `adjustSeverity returns unchanged severity when tier is null`() {
        assertEquals(Severity.ERROR, AuthorSeniorityResolver.adjustSeverity(Severity.ERROR, null))
        assertEquals(Severity.WARNING, AuthorSeniorityResolver.adjustSeverity(Severity.WARNING, null))
        assertEquals(Severity.INFO, AuthorSeniorityResolver.adjustSeverity(Severity.INFO, null))
    }

    @Test
    fun `junior tier downgrades ERROR to WARNING`() {
        val tier = SeniorityTier(level = SeniorityLevel.JUNIOR)
        assertEquals(Severity.WARNING, AuthorSeniorityResolver.adjustSeverity(Severity.ERROR, tier))
    }

    @Test
    fun `level junior downgrades an error that level senior keeps`() {
        val junior = AuthorSeniorityResolver.adjustSeverity(Severity.ERROR, SeniorityTier(level = SeniorityLevel.JUNIOR))
        val senior = AuthorSeniorityResolver.adjustSeverity(Severity.ERROR, SeniorityTier(level = SeniorityLevel.SENIOR))

        assertEquals(Severity.WARNING, junior)
        assertEquals(Severity.ERROR, senior)
    }

    @Test
    fun `junior tier leaves WARNING unchanged`() {
        val tier = SeniorityTier(level = SeniorityLevel.JUNIOR)
        assertEquals(Severity.WARNING, AuthorSeniorityResolver.adjustSeverity(Severity.WARNING, tier))
    }

    @Test
    fun `junior tier leaves INFO unchanged`() {
        val tier = SeniorityTier(level = SeniorityLevel.JUNIOR)
        assertEquals(Severity.INFO, AuthorSeniorityResolver.adjustSeverity(Severity.INFO, tier))
    }

    @Test
    fun `standard tier leaves ERROR unchanged`() {
        val tier = SeniorityTier(level = SeniorityLevel.STANDARD)
        assertEquals(Severity.ERROR, AuthorSeniorityResolver.adjustSeverity(Severity.ERROR, tier))
    }

    @Test
    fun `senior tier leaves ERROR unchanged`() {
        val tier = SeniorityTier(level = SeniorityLevel.SENIOR)
        assertEquals(Severity.ERROR, AuthorSeniorityResolver.adjustSeverity(Severity.ERROR, tier))
    }

    @Test
    fun `senior tier leaves WARNING unchanged`() {
        val tier = SeniorityTier(level = SeniorityLevel.SENIOR)
        assertEquals(Severity.WARNING, AuthorSeniorityResolver.adjustSeverity(Severity.WARNING, tier))
    }

    @Test
    fun `matchAuthors matches a normalized username`() {
        val tier = SeniorityTier(level = SeniorityLevel.SENIOR, matchAuthors = listOf(" @Alice "))

        assertEquals(tier, AuthorSeniorityResolver.resolve(mr(username = "alice"), listOf(tier)))
        assertNull(AuthorSeniorityResolver.resolve(mr(username = "alicia"), listOf(tier)))
    }

    @Test
    fun `severityAdjustment replaces the level rule`() {
        val tier = SeniorityTier(
            level = SeniorityLevel.JUNIOR,
            severityAdjustment = mapOf("warning" to "error", "info" to "warning"),
        )

        assertEquals(Severity.ERROR, AuthorSeniorityResolver.adjustSeverity(Severity.WARNING, tier))
        assertEquals(Severity.WARNING, AuthorSeniorityResolver.adjustSeverity(Severity.INFO, tier))
        assertEquals(Severity.ERROR, AuthorSeniorityResolver.adjustSeverity(Severity.ERROR, tier))
    }
}
