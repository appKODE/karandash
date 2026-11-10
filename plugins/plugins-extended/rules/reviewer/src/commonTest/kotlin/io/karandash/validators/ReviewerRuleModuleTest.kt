@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.validators

import io.karandash.api.platform.MergeRequestField
import io.karandash.models.User
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewerRuleModuleTest {
    private fun strings(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })

    @Test
    fun `module needs reviewers and reports every broken requirement from json config`() =
        runTest {
            val module = ReviewerRuleModule()
            assertTrue(module.initialize(TestPluginContext()))
            assertEquals(setOf(MergeRequestField.REVIEWERS), module.requiredMergeRequestFields(ReviewerRuleModule.RULE_ID))
            val config =
                JsonObject(
                    mapOf(
                        "requiredReviewers" to strings("alice"),
                        "requiredTeams" to strings("platform"),
                        "excludedReviewers" to strings("intern"),
                        "teamMatchMode" to JsonPrimitive("contains"),
                    ),
                )
            val reviewers = listOf(User(username = "intern", name = "I"))

            assertEquals(
                listOf(
                    "Missing required reviewers: alice",
                    "Missing reviewer from required teams: platform",
                    "Excluded reviewers present: intern",
                ),
                module.validate(ReviewerRuleModule.RULE_ID, createTestMergeRequest(reviewers = reviewers), config).map { it.message },
            )
            val good = listOf(User(username = "alice", name = "A"), User(username = "Platform-Bob", name = "B"))
            assertTrue(module.validate(ReviewerRuleModule.RULE_ID, createTestMergeRequest(reviewers = good), config).isEmpty())
        }
}
