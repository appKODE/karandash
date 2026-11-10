package io.karandash.plugins.ai.core.context

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.sdk.enrichment.EnrichmentKeys
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Formats Confluence design-document content into an AI prompt block.
 *
 * Reads from a [JsonObject] config keyed by [EnrichmentKeys.CONFLUENCE_PAGES].
 * Expected shape (supplied by the assembler):
 * ```json
 * {
 *   "confluence:pages": {
 *     "12345": { "title": "Auth Design", "markdown": "## Overview\n..." }
 *   }
 * }
 * ```
 */
class DesignDocEnricher : AiContextEnricherModule {
    override val id: String = MODULE_ID
    override val name: String = "Design Doc Enricher"
    override val version: String = "1.0.0"
    override val description: String = "Formats Confluence design documents for AI prompt injection"
    override val dslName: String = "designDocEnricher"
    override val enricherPriority: Int = ENRICHER_PRIORITY
    override val defaultEnabled: Boolean = true

    override fun handledEnrichmentIds(): List<String> = listOf(EnrichmentKeys.CONFLUENCE_PAGES)

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
        val pages = data[EnrichmentKeys.CONFLUENCE_PAGES] as? JsonObject ?: return null
        val body = buildString {
            for ((pageId, page) in pages) {
                val obj = page as? JsonObject ?: continue
                val title = (obj["title"] as? JsonPrimitive)?.content ?: pageId
                val markdown = (obj["markdown"] as? JsonPrimitive)?.content ?: continue
                if (markdown.isBlank()) continue
                appendLine("**$title**")
                appendLine(markdown)
            }
        }.trim()
        return KnowledgeContextFormatter.formatBlock(BLOCK_HEADING, body, BLOCK_PRIORITY, id, budget)
    }

    companion object {
        const val MODULE_ID: String = "design-doc-enricher"
        const val ENRICHER_PRIORITY: Int = 280
        const val BLOCK_PRIORITY: Int = 280
        const val BLOCK_HEADING: String = "#### Design documents"
    }
}
