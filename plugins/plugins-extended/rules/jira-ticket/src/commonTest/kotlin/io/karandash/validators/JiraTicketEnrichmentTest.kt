package io.karandash.validators

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.models.finding.Severity
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JiraTicketEnrichmentTest {
    private val config = JiraTicketConfig(projectKeys = listOf("PROJ"), validateStatus = listOf("In Progress"), validateAssignee = true)
    private val mr = createTestMergeRequest(title = "PROJ-1: feature")

    private fun tickets(
        status: String,
        assignee: String?,
    ): JsonObject =
        buildJsonObject {
            put(
                "PROJ-1",
                buildJsonObject {
                    put("status", status)
                    put("assignee", assignee?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        }

    @Test
    fun ticketInAllowedStatusWithAssigneePasses() {
        val findings = JiraTicketValidator(config, tickets = tickets("in progress", "Alice")).validate(mr)
        assertTrue(findings.isEmpty(), findings.toString())
    }

    @Test
    fun wrongStatusAndMissingAssigneeAreErrors() {
        val findings = JiraTicketValidator(config, tickets = tickets("Done", null)).validate(mr)
        assertEquals(2, findings.size, findings.toString())
        assertTrue(findings.all { it.severity == Severity.ERROR })
        assertTrue(findings.any { it.message.contains("status 'Done'") })
        assertTrue(findings.any { it.message.contains("no assignee") })
    }

    @Test
    fun jiraUrlWithoutEnrichmentDataWarns() {
        val findings = JiraTicketValidator(config.copy(jiraUrl = "https://jira.example.com")).validate(mr)
        assertEquals(1, findings.size, findings.toString())
        assertEquals(Severity.WARNING, findings[0].severity)
        assertTrue(findings[0].message.contains("No Jira data"))
    }

    @Test
    fun moduleValidatesWithEnrichmentDeliveredAtInitialize() =
        runTest {
            val module = JiraTicketRuleModule()
            val delivered = EnrichmentContext(mapOf(EnrichmentKeys.JIRA_TICKETS to tickets("Done", "Alice")))
            val context = object : PluginContext by TestPluginContext() {
                override val enrichment: EnrichmentContext = delivered
            }
            assertTrue(EnrichmentKeys.JIRA_TICKETS in module.supportedEnrichment)
            assertTrue(module.initialize(context))

            val ruleConfig = buildJsonObject {
                put("projectKeys", JsonArray(listOf(JsonPrimitive("PROJ"))))
                put("validateStatus", JsonArray(listOf(JsonPrimitive("In Progress"))))
            }
            val findings = module.validate(JiraTicketRuleModule.RULE_ID, mr, ruleConfig)
            assertEquals(listOf("Ticket PROJ-1 has status 'Done', which is not allowed."), findings.map { it.message })
        }
}
