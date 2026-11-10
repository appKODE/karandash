package io.karandash.validators

import io.karandash.models.User
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertTrue

class ReviewerRegressionTest {
    @Test
    fun `required teams finding says teams are matched against usernames`() {
        val config = ReviewerConfig(requiredTeams = listOf("platform", "infra"))
        val mr = createTestMergeRequest(reviewers = listOf(User(username = "dev", name = "Dev")))
        val finding = ReviewerValidator(config).validate(mr).single()
        assertTrue(finding.explanation.orEmpty().contains("any of the listed names"), finding.explanation.orEmpty())
        assertTrue(finding.explanation.orEmpty().contains("not team membership"), finding.explanation.orEmpty())
    }
}
