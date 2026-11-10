package io.karandash.plugins.ai.core.context

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.sdk.enrichment.EnrichmentKeys
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Formats linked ticket data (Jira, GitHub, GitLab issues) into an AI prompt block.
 *
 * Reads from a [JsonObject] config keyed by enrichment ID. When the enrichment assembler
 * supplies enrichment data via the `config` parameter of [provideBlock], this enricher
 * formats a unified "#### Linked tickets" block with redacted, budget-bounded content.
 *
 * Expected config shape (supplied by the assembler):
 * ```json
 * {
 *   "jira:tickets": { "PROJ-1": { "summary": "...", "description": "..." }, ... },
 *   "jira:acceptance-criteria": { "PROJ-1": ["AC bullet 1", ...], ... },
 *   "github:issues": { "42": { "title": "...", "body": "..." }, ... },
 *   "gitlab:issues": { "7": { "title": "...", "description": "..." }, ... }
 * }
 * ```
 */
class TicketContentEnricher : AiContextEnricherModule {
    override val id: String = MODULE_ID
    override val name: String = "Ticket Content Enricher"
    override val version: String = "1.0.0"
    override val description: String = "Formats linked ticket data for AI prompt injection"
    override val dslName: String = "ticketContentEnricher"
    override val enricherPriority: Int = ENRICHER_PRIORITY
    override val defaultEnabled: Boolean = true

    override fun handledEnrichmentIds(): List<String> =
        listOf(
            EnrichmentKeys.JIRA_TICKETS,
            EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA,
            EnrichmentKeys.GITHUB_ISSUES,
            EnrichmentKeys.GITLAB_ISSUES,
        )

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun shutdown() = Unit

    override suspend fun enrich(
        mr: MergeRequest,
        context: AiContextEnricherContext,
    ): Map<String, String> = emptyMap()

    override suspend fun provideBlock(
        mr: MergeRequest,
        changes: List<FileChange>,
        config: JsonElement,
        budget: BlockBudget,
    ): AiContextBlock? {
        val data = config as? JsonObject ?: return null
        val body = buildString {
            appendJiraTickets(data)
            appendAcceptanceCriteria(data)
            appendGitHubIssues(data)
            appendGitLabIssues(data)
        }.trim()
        return KnowledgeContextFormatter.formatBlock(BLOCK_HEADING, body, BLOCK_PRIORITY, id, budget)
    }

    private fun StringBuilder.appendJiraTickets(data: JsonObject) {
        val tickets = data[EnrichmentKeys.JIRA_TICKETS] as? JsonObject ?: return
        for ((key, ticket) in tickets) {
            val obj = ticket as? JsonObject ?: continue
            val summary = (obj["summary"] as? JsonPrimitive)?.content ?: continue
            appendLine("**$key**: $summary")
            val desc = (obj["description"] as? JsonPrimitive)?.content ?: continue
            if (desc.isNotBlank()) appendLine(desc)
        }
    }

    private fun StringBuilder.appendAcceptanceCriteria(data: JsonObject) {
        val acMap = data[EnrichmentKeys.JIRA_ACCEPTANCE_CRITERIA] as? JsonObject ?: return
        for ((key, criteria) in acMap) {
            val list = criteria as? JsonArray ?: continue
            if (list.isEmpty()) continue
            appendLine("\n**$key Acceptance Criteria:**")
            list.forEach { item ->
                val text = (item as? JsonPrimitive)?.content ?: return@forEach
                appendLine("- $text")
            }
        }
    }

    private fun StringBuilder.appendGitHubIssues(data: JsonObject) {
        val issues = data[EnrichmentKeys.GITHUB_ISSUES] as? JsonObject ?: return
        for ((num, issue) in issues) {
            val obj = issue as? JsonObject ?: continue
            val title = (obj["title"] as? JsonPrimitive)?.content ?: continue
            appendLine("**GitHub #$num**: $title")
            val body = (obj["body"] as? JsonPrimitive)?.content ?: continue
            if (body.isNotBlank()) appendLine(body)
        }
    }

    private fun StringBuilder.appendGitLabIssues(data: JsonObject) {
        val issues = data[EnrichmentKeys.GITLAB_ISSUES] as? JsonObject ?: return
        for ((iid, issue) in issues) {
            val obj = issue as? JsonObject ?: continue
            val title = (obj["title"] as? JsonPrimitive)?.content ?: continue
            appendLine("**GitLab #$iid**: $title")
            val desc = (obj["description"] as? JsonPrimitive)?.content ?: continue
            if (desc.isNotBlank()) appendLine(desc)
        }
    }

    companion object {
        const val MODULE_ID: String = "ticket-content-enricher"
        const val ENRICHER_PRIORITY: Int = 300
        const val BLOCK_PRIORITY: Int = 300
        const val BLOCK_HEADING: String = "#### Linked tickets"
    }
}
