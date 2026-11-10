package io.karandash.validators

import io.karandash.config.Config
import io.karandash.config.RulesConfig
import io.karandash.config.pipeline.ModuleEnablementResolver
import io.karandash.models.finding.Severity
import io.karandash.patterns.TicketFormat
import io.karandash.testfixtures.createTestEngine
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.toJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JiraTicketRegressionTest {
    @Test
    fun `jira rule is off by default`() {
        assertFalse(JiraTicketRuleModule().defaultEnabled)
    }

    @Test
    fun `empty project keys do not produce an error`() {
        val findings = JiraTicketValidator(JiraTicketConfig(projectKeys = emptyList()))
            .validate(createTestMergeRequest(title = "TEST-123 feature"))
        assertTrue(findings.isEmpty(), findings.toString())
    }

    @Test
    fun `explicitly configured jira stays on`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf("jiraTicket" to JiraTicketConfig(projectKeys = listOf("PROJ")).toJsonElement())
                )
            )
            val enabled = ModuleEnablementResolver().resolveFromConfig(config)
            val result = createTestEngine(config, enabled).validate(createTestMergeRequest(title = "no ticket here"))
            assertEquals(1, result.findings.count { it.rule == "jiraTicket" }, result.findings.toString())
        }

    @Test
    fun `blank project key means no keys`() {
        val findings = JiraTicketValidator(JiraTicketConfig(projectKeys = listOf("")))
            .validate(createTestMergeRequest(title = "TEST-123 feature"))
        assertTrue(findings.isEmpty(), findings.toString())
    }

    @Test
    fun `any valid key among several tickets passes`() {
        val findings = JiraTicketValidator(JiraTicketConfig(projectKeys = listOf("PROJ")))
            .validate(createTestMergeRequest(title = "OTHER-1 PROJ-2 feature"))
        assertTrue(findings.isEmpty(), findings.toString())
    }

    @Test
    fun `custom format without pattern is a config warning`() {
        val findings = JiraTicketValidator(JiraTicketConfig(format = TicketFormat.CUSTOM))
            .validate(createTestMergeRequest(title = "ABC-1 feature"))
        assertTrue(findings.any { it.severity == Severity.WARNING && it.message.contains("pattern") }, findings.toString())
    }
}
