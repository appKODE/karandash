@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.validators

import io.karandash.api.platform.MergeRequestField
import io.karandash.models.Approval
import io.karandash.models.User
import io.karandash.models.finding.FixSuggestion
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApprovalRuleModuleTest {
    private val policy =
        """
        {"policy": {"type": "all", "policies": [
          {"type": "groupApproval", "group": "core", "members": ["ann", "bob"], "minCount": 2},
          {"type": "minApprovals", "count": 2},
          {"type": "noSelfApproval"}
        ]}}
        """.trimIndent()

    @Test
    fun `module decodes a composed policy and suggests who to ask for each failing part`() =
        runTest {
            val module = ApprovalRuleModule()
            assertTrue(module.initialize(TestPluginContext()))
            assertEquals(setOf(MergeRequestField.APPROVALS), module.requiredMergeRequestFields(ApprovalRuleModule.RULE_ID))
            val mr =
                createTestMergeRequest(
                    approvals = listOf(Approval(user = User(username = "ann", name = "Ann"))),
                    reviewers = listOf(User(username = "ann", name = "Ann"), User(username = "cat", name = "Cat")),
                )

            val findings = module.validate(ApprovalRuleModule.RULE_ID, mr, Json.parseToJsonElement(policy))

            assertEquals(
                listOf(listOf("bob"), listOf("cat")),
                findings.map { f ->
                    f.fixSuggestions.map {
                        (it as FixSuggestion.Custom)
                            .data.jsonObject
                            .getValue("username")
                            .jsonPrimitive.content
                    }
                },
            )
        }

    @Test
    fun `flat keys build the policy and zero approvals passes`() =
        runTest {
            val module = ApprovalRuleModule()
            module.initialize(TestPluginContext())
            val config = JsonObject(mapOf("minApprovals" to JsonPrimitive(0)))

            assertTrue(module.validate(ApprovalRuleModule.RULE_ID, createTestMergeRequest(), config).isEmpty())
        }
}
