package io.karandash.validators

import io.karandash.config.Config
import io.karandash.config.RulesConfig
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.sdk.config.BlockMode
import io.karandash.testfixtures.createTestEngine
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.toJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiRuleIntegrationTest {
    @Test
    fun testBothValidatorsTogether() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        BranchNamingRuleModule.RULE_ID to BranchNamingConfig(
                            pattern = "feature/.*",
                            requireSourceMatch = true
                        ).toJsonElement(),
                        FileChangesRuleModule.RULE_ID to FileChangesConfig(
                            blockOnDraft = BlockMode.FAIL
                        ).toJsonElement()
                    )
                )
            )
            val engine = createTestEngine(config)

            val mr = createTestMergeRequest(
                sourceBranch = "invalid-branch",
                draft = true
            )

            val result = engine.validate(mr)
            assertFalse(result.passed)
            assertEquals(2, result.findings.size)
            assertTrue(result.findings.any { it.rule == "branchNaming" })
            assertTrue(result.findings.any { it.rule == "fileChanges" })
        }

    @Test
    fun `validate with multiple rules produces multiple findings`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        TitleFormatRuleModule.RULE_ID to TitleFormatConfig(
                            pattern = "^(feat|fix): .*",
                            minLength = 100
                        ).toJsonElement(),
                        CommitMessagesRuleModule.RULE_ID to CommitMessagesConfig(format = "conventional").toJsonElement(),
                        JiraTicketRuleModule.RULE_ID to JiraTicketConfig(projectKeys = listOf("PROJ")).toJsonElement()
                    )
                )
            )

            val commit = Commit(
                sha = "abc123",
                message = "invalid commit",
                author = CommitAuthor("user", "user@example.com"),
                timestamp = "2024-01-01T00:00:00Z"
            )

            val mr = createTestMergeRequest(
                title = "invalid title",
                commits = listOf(commit)
            )

            // jira-ticket is off by default; a configured block enables it, as the config loader does
            val engine = createTestEngine(config, enabledModules = mapOf(JiraTicketRuleModule.RULE_ID to true))
            val result = engine.validate(mr)

            assertTrue(result.findings.size >= 3)
            assertTrue(result.findings.any { it.rule == "titleFormat" })
            assertTrue(result.findings.any { it.rule == "commitMessages" })
            assertTrue(result.findings.any { it.rule == "jiraTicket" })
        }
}
