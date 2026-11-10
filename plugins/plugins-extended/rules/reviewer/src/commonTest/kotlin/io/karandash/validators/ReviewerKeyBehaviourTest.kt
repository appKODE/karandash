package io.karandash.validators

import io.karandash.models.User
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewerKeyBehaviourTest {
    private fun count(
        config: ReviewerConfig,
        vararg reviewers: String,
    ): Int =
        ReviewerValidator(config)
            .validate(createTestMergeRequest(reviewers = reviewers.map { User(it, it) }))
            .size

    @Test
    fun `requiredReviewers flags a missing reviewer`() {
        val config = ReviewerConfig(requiredReviewers = listOf("alice"))
        assertEquals(1, count(config, "bob"))
        assertEquals(0, count(config, "alice"))
    }

    @Test
    fun `requiredTeams flags an MR without a reviewer from the team`() {
        val config = ReviewerConfig(requiredTeams = listOf("core"))
        assertEquals(1, count(config, "bob"))
        assertEquals(0, count(config, "core"))
    }

    @Test
    fun `excludedReviewers flags a forbidden reviewer`() {
        val config = ReviewerConfig(excludedReviewers = listOf("bot"))
        assertEquals(1, count(config, "bot"))
        assertEquals(0, count(config, "alice"))
    }

    @Test
    fun `teamMatchMode contains matches a username part`() {
        val exact = ReviewerConfig(requiredTeams = listOf("core"), teamMatchMode = ReviewerConfig.TEAM_MATCH_EXACT)
        val contains = ReviewerConfig(requiredTeams = listOf("core"), teamMatchMode = ReviewerConfig.TEAM_MATCH_CONTAINS)
        assertEquals(1, count(exact, "core-alice"))
        assertEquals(0, count(contains, "core-alice"))
    }
}
