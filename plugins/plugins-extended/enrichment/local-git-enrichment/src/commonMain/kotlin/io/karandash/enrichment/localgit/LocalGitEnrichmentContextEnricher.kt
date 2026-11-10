package io.karandash.enrichment.localgit

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.api.platform.SubprocessBudget
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.security.TextRedactor
import kotlinx.atomicfu.atomic
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * [AiContextEnricherModule] that exposes local-git file-frequency hotspots to AI prompts.
 *
 * Reads `{"frequencies", "period"}` from [EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY] when populated by
 * [LocalGitEnrichmentProvider] (Path B via the enrichment assembler), shallow clone or not. Only when the provider
 * did not run does it call [FileFrequencyAnalyzer] directly (needs full history).
 *
 * `localGitContext:` (the DSL name) and `enrichment.local-git:` are both live.
 */
class LocalGitEnrichmentContextEnricher : AiContextEnricherModule {
    override val id: String = MODULE_ID
    override val name: String = MODULE_NAME
    override val version: String = MODULE_VERSION
    override val description: String = MODULE_DESCRIPTION
    override val dslName: String = DSL_NAME
    override val enricherPriority: Int = ENRICHER_PRIORITY
    override val defaultEnabled: Boolean = false

    private var logger: Logger = NoOpLogger
    private val workingDirectory = atomic<String>("")
    private val subprocessBudget = atomic<SubprocessBudget>(SubprocessBudget.UNLIMITED)

    override fun handledEnrichmentIds(): List<String> = listOf(EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY)

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        workingDirectory.value = context.workingDirectory
        subprocessBudget.value = context.subprocessBudget
        return true
    }

    override suspend fun shutdown() { /* no resources to release */ }

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
        val providerBlock = providerData(config)
        val counts = providerBlock?.let(::readFrequencies) ?: runLegacyAnalysis(changes) ?: return null
        if (counts.isEmpty()) {
            logger.debug("Local git context enricher: no frequency data, skipping block")
            return null
        }
        val period = resolvePeriod(providerBlock)
        val top = counts.entries.sortedByDescending { it.value }.take(MAX_HOTSPOTS)
        val body = buildString {
            appendLine("Frequently changed files (last $period):")
            for ((path, count) in top) {
                appendLine("  - $path: $count commits")
            }
        }.trim()
        return AiContextBlock(
            heading = BLOCK_HEADING,
            content = TextRedactor.redact(body),
            priority = BLOCK_PRIORITY,
            source = id,
        )
    }

    /** The [LocalGitEnrichmentProvider] value under [EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY], if it ran. */
    private fun providerData(config: JsonElement): JsonObject? =
        (config as? JsonObject)?.get(EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY) as? JsonObject

    /** Reads the `{"frequencies": { path: count }, "period"}` shape of [LocalGitEnrichmentProvider.fileFrequencyJson]. */
    private fun readFrequencies(data: JsonObject): Map<String, Int> {
        val freq = data[LocalGitEnrichmentProvider.KEY_FREQUENCIES] as? JsonObject ?: return emptyMap()
        return freq
            .mapNotNull { (path, value) ->
                val count = (value as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                path to count
            }.toMap()
    }

    private suspend fun runLegacyAnalysis(changes: List<FileChange>): Map<String, Int>? {
        if (workingDirectory.value.isBlank() || changes.isEmpty()) return null
        if (ShallowCloneDetector.isShallow(workingDirectory.value, subprocessBudget.value, logger)) {
            logger.debug("Local git context enricher: shallow clone detected, skipping hotspot analysis")
            return null
        }
        val period = FileFrequencyAnalyzer.DEFAULT_PERIOD
        logger.debug("Local git context enricher: analyzing ${changes.size} file(s) over $period")
        return FileFrequencyAnalyzer.analyze(
            workingDir = workingDirectory.value,
            filePaths = changes.map { it.path },
            period = period,
            budget = subprocessBudget.value,
            logger = logger,
        )
    }

    private fun resolvePeriod(providerBlock: JsonObject?): String {
        val value = (providerBlock?.get(LocalGitEnrichmentProvider.KEY_PERIOD) as? JsonPrimitive)?.contentOrNull
        return if (value.isNullOrBlank()) FileFrequencyAnalyzer.DEFAULT_PERIOD else value
    }

    companion object {
        const val MODULE_ID: String = "local-git-context"
        const val MODULE_NAME: String = "Local Git Context"
        const val MODULE_VERSION: String = "1.0.0"
        const val MODULE_DESCRIPTION: String = "Git file-frequency hotspots as AI review context"
        const val DSL_NAME: String = "localGitContext"
        const val ENRICHER_PRIORITY: Int = 180
        const val BLOCK_PRIORITY: Int = 180
        const val BLOCK_HEADING: String = "#### Git hotspot files"
        const val MAX_HOTSPOTS: Int = 10
    }
}
