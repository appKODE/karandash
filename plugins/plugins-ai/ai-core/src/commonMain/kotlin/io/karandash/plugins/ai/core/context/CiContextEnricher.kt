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
 * Formats CI pipeline signal (failed jobs and coverage gaps) into an AI prompt block.
 *
 * Reads from a [JsonObject] config keyed by enrichment ID. Expected shape:
 * ```json
 * {
 *   "ci:failed-jobs": [
 *     { "name": "unit-test", "stage": "test", "errorSnippet": "AssertionError..." }
 *   ],
 *   "ci:coverage-gaps": {
 *     "src/Auth.kt": [{ "startLine": 42, "endLine": 55 }]
 *   }
 * }
 * ```
 */
class CiContextEnricher : AiContextEnricherModule {
    override val id: String = MODULE_ID
    override val name: String = "CI Context Enricher"
    override val version: String = "1.0.0"
    override val description: String = "Formats CI failure and coverage data for AI prompt injection"
    override val dslName: String = "ciContextEnricher"
    override val enricherPriority: Int = ENRICHER_PRIORITY
    override val defaultEnabled: Boolean = true

    override fun handledEnrichmentIds(): List<String> =
        listOf(
            EnrichmentKeys.CI_FAILED_JOBS,
            EnrichmentKeys.CI_COVERAGE_GAPS,
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
            appendFailedJobs(data)
            appendCoverageGaps(data)
        }.trim()
        return KnowledgeContextFormatter.formatBlock(BLOCK_HEADING, body, BLOCK_PRIORITY, id, budget)
    }

    private fun StringBuilder.appendFailedJobs(data: JsonObject) {
        val jobs = data[EnrichmentKeys.CI_FAILED_JOBS] as? JsonArray ?: return
        if (jobs.isEmpty()) return
        appendLine("**Failed CI jobs:**")
        jobs.take(MAX_FAILED_JOBS).forEach { job ->
            val obj = job as? JsonObject ?: return@forEach
            val name = (obj["name"] as? JsonPrimitive)?.content ?: return@forEach
            val snippet = (obj["errorSnippet"] as? JsonPrimitive)?.content
            if (snippet != null) appendLine("- $name: $snippet") else appendLine("- $name")
        }
    }

    private fun StringBuilder.appendCoverageGaps(data: JsonObject) {
        val gaps = data[EnrichmentKeys.CI_COVERAGE_GAPS] as? JsonObject ?: return
        if (gaps.isEmpty()) return
        appendLine("\n**Uncovered lines in changed files:**")
        gaps.entries.take(MAX_COVERAGE_FILES).forEach { (path, ranges) ->
            val list = ranges as? JsonArray ?: return@forEach
            val summary = list.take(MAX_RANGES_PER_FILE).joinToString(", ") { r ->
                val obj = r as? JsonObject ?: return@joinToString "?"
                val start = (obj["startLine"] as? JsonPrimitive)?.content ?: "?"
                val end = (obj["endLine"] as? JsonPrimitive)?.content ?: "?"
                if (start == end) "L$start" else "L$start-L$end"
            }
            appendLine("- $path: $summary")
        }
    }

    companion object {
        const val MODULE_ID: String = "ci-context-enricher"
        const val ENRICHER_PRIORITY: Int = 220
        const val BLOCK_PRIORITY: Int = 220
        const val BLOCK_HEADING: String = "#### CI signal"
        private const val MAX_FAILED_JOBS: Int = 10
        private const val MAX_COVERAGE_FILES: Int = 20
        private const val MAX_RANGES_PER_FILE: Int = 5
    }
}
