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
 * Formats structural symbol context (class/function signatures from changed files) into an AI prompt block.
 *
 * Reads from a [JsonObject] config keyed by [EnrichmentKeys.SYMBOLS_CHANGED]. Expected shape:
 * ```json
 * {
 *   "symbols:changed": {
 *     "src/Auth.kt": [
 *       { "kind": "fun", "name": "authenticate", "signature": "fun authenticate(token: String): User?" }
 *     ]
 *   }
 * }
 * ```
 */
class SymbolContextEnricher : AiContextEnricherModule {
    override val id: String = MODULE_ID
    override val name: String = "Symbol Context Enricher"
    override val version: String = "1.0.0"
    override val description: String = "Formats changed symbol signatures for AI prompt injection"
    override val dslName: String = "symbolContextEnricher"
    override val enricherPriority: Int = ENRICHER_PRIORITY
    override val defaultEnabled: Boolean = true

    override fun handledEnrichmentIds(): List<String> = listOf(EnrichmentKeys.SYMBOLS_CHANGED)

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
        val symbols = data[EnrichmentKeys.SYMBOLS_CHANGED] as? JsonObject ?: return null
        val body = buildString {
            for ((path, symList) in symbols) {
                val list = symList as? JsonArray ?: continue
                if (list.isEmpty()) continue
                appendLine("**$path**:")
                list.take(MAX_SYMBOLS_PER_FILE).forEach { sym ->
                    val obj = sym as? JsonObject ?: return@forEach
                    val kind = (obj["kind"] as? JsonPrimitive)?.content ?: return@forEach
                    val sig = (obj["signature"] as? JsonPrimitive)?.content
                        ?: (obj["name"] as? JsonPrimitive)?.content
                        ?: return@forEach
                    appendLine("  - $kind `$sig`")
                }
            }
        }.trim()
        return KnowledgeContextFormatter.formatBlock(BLOCK_HEADING, body, BLOCK_PRIORITY, id, budget)
    }

    companion object {
        const val MODULE_ID: String = "symbol-context-enricher"
        const val ENRICHER_PRIORITY: Int = 200
        const val BLOCK_PRIORITY: Int = 200
        const val BLOCK_HEADING: String = "#### Changed symbols"
        private const val MAX_SYMBOLS_PER_FILE: Int = 10
    }
}
