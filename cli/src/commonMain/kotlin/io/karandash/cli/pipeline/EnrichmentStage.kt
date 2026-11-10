package io.karandash.cli.pipeline

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.errors.ProviderException
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.FetchOptions
import io.karandash.api.support.rethrowCancellation
import io.karandash.cli.commands.validate.DynamicModuleSelector
import io.karandash.config.EnrichmentConfig
import io.karandash.models.MergeRequest
import io.karandash.sdk.Logger

/**
 * Pre-validation enrichment stage.
 *
 * When enrichment is configured, uses the registered rule module that contributes
 * the `generate-description` rule to generate a suggested title and/or description.
 * In non-dry-run mode, applies the suggestions through the mutator and re-fetches the
 * MR so the validation stage receives the enriched copy.
 *
 * Enrichment is silently skipped when:
 * - [EnrichmentConfig] is null or both [EnrichmentConfig.generateTitle] and
 *   [EnrichmentConfig.generateDescription] are false
 * - [EnrichmentConfig.skipIfDescriptionExists] is true and the MR has a non-blank description
 * - No `RuleModule` contributing the `generate-description` rule is registered
 */
internal class EnrichmentStage(
    private val config: EnrichmentConfig,
    private val registry: ModuleRegistry,
    private val provider: PlatformServiceModule?,
    private val projectId: String,
    private val mrId: String,
    private val dryRun: Boolean,
    private val logger: Logger,
    private val runtimeModuleInitializer: suspend (Module) -> Unit = {},
    private val fetchOptions: FetchOptions = FetchOptions(),
    private val platformHint: String? = null,
    private val preferredPluginIds: Set<String> = emptySet(),
) {
    suspend fun enrich(mr: MergeRequest): MergeRequest {
        if (!config.generateTitle && !config.generateDescription) {
            logger.debug("[enrichment] Skipped: neither generateTitle nor generateDescription enabled")
            return mr
        }

        if (config.skipIfDescriptionExists && !mr.description.isNullOrBlank()) {
            logger.info("[enrichment] Skipping: MR already has a description.")
            return mr
        }

        val aiModule = resolveAiModule() ?: return mr

        logger.info("Running pre-validation enrichment ($RULE_GENERATE_DESCRIPTION)...")

        val finding = runAiGeneration(aiModule, mr) ?: run {
            logger.debug("[enrichment] AI generation returned no findings")
            return mr
        }
        val suggestedTitle = finding.metadata[METADATA_SUGGESTED_TITLE]
        val suggestedDescription = finding.metadata[METADATA_SUGGESTED_DESCRIPTION]

        if (suggestedTitle == null && suggestedDescription == null) {
            logger.debug("[enrichment] AI generation produced no title or description suggestions")
            return mr
        }

        if (dryRun) {
            logDryRunSuggestions(suggestedTitle, suggestedDescription)
            return mr
        }

        val mutator = resolveMutator() ?: run {
            logger.warn("Enrichment: no mutator available to apply suggestions.")
            return mr
        }
        runtimeModuleInitializer(mutator)

        applySuggestions(mutator, suggestedTitle, suggestedDescription)
        return refetchMr(mr)
    }

    private suspend fun resolveAiModule(): io.karandash.api.module.RuleModule? {
        val module = registry
            .ruleModules()
            .firstOrNull { RULE_GENERATE_DESCRIPTION in it.contributedRules() }
        if (module == null) {
            logger.warn("Enrichment enabled but no module supporting '$RULE_GENERATE_DESCRIPTION' is registered.")
        }
        return module
    }

    private suspend fun runAiGeneration(
        aiModule: io.karandash.api.module.RuleModule,
        mr: MergeRequest,
    ): io.karandash.models.finding.Finding? {
        @Suppress("TooGenericExceptionCaught")
        val findings = try {
            aiModule.validate(ruleId = RULE_GENERATE_DESCRIPTION, mr = mr, configElement = buildRuleConfig())
        } catch (e: Exception) {
            // boundary: platform service and AI module calls are plugin code
            e.rethrowCancellation()
            logger.warn("Enrichment failed: ${e.message}")
            return null
        }
        if (findings.isEmpty()) logger.debug("[enrichment] Rule '$RULE_GENERATE_DESCRIPTION' returned 0 findings")
        return findings.firstOrNull()
    }

    private fun logDryRunSuggestions(
        suggestedTitle: String?,
        suggestedDescription: String?
    ) {
        suggestedTitle?.let { logger.info("[enrichment:dry-run] Suggested title: $it") }
        suggestedDescription?.let { logger.info("[enrichment:dry-run] Suggested description:\n$it") }
    }

    private suspend fun applySuggestions(
        mutator: PlatformServiceModule,
        suggestedTitle: String?,
        suggestedDescription: String?,
    ) {
        if (config.generateTitle && suggestedTitle != null) {
            applyTitle(mutator, suggestedTitle)
        }
        if (config.generateDescription && suggestedDescription != null) {
            applyDescription(mutator, suggestedDescription)
        }
    }

    private suspend fun applyTitle(
        mutator: PlatformServiceModule,
        title: String
    ) {
        @Suppress("TooGenericExceptionCaught")
        try {
            mutator.updateTitle(projectId, mrId, title)
            logger.info("[enrichment] Title updated.")
        } catch (e: Exception) {
            // boundary: platform service and AI module calls are plugin code
            e.rethrowCancellation()
            logger.warn("Enrichment: failed to update title: ${e.message}")
        }
    }

    private suspend fun applyDescription(
        mutator: PlatformServiceModule,
        description: String
    ) {
        @Suppress("TooGenericExceptionCaught")
        try {
            mutator.updateDescription(projectId, mrId, description)
            logger.info("[enrichment] Description updated.")
        } catch (e: Exception) {
            // boundary: platform service and AI module calls are plugin code
            e.rethrowCancellation()
            logger.warn("Enrichment: failed to update description: ${e.message}")
        }
    }

    private suspend fun refetchMr(mr: MergeRequest): MergeRequest {
        if (provider == null) logger.debug("[enrichment] No live provider available for MR re-fetch")
        val liveProvider = provider ?: return mr
        @Suppress("TooGenericExceptionCaught")
        return try {
            liveProvider.fetchMergeRequest(projectId, mrId, fetchOptions)
        } catch (e: Exception) {
            // boundary: platform service and AI module calls are plugin code
            e.rethrowCancellation()
            logger.warn("Enrichment: re-fetch failed: ${e.message}")
            mr
        }
    }

    /** The live provider, else the platform service auto-approve would pick (CI platform hint, `plugins.enabled`). */
    private fun resolveMutator(): PlatformServiceModule? =
        provider ?: try {
            DynamicModuleSelector.resolve<PlatformServiceModule>(registry, ModuleType.PLATFORM_SERVICE, platformHint, preferredPluginIds)
        } catch (e: ProviderException) {
            logger.warn("Enrichment: ${e.message}")
            null
        }

    private fun buildRuleConfig(): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.JsonObject(
            buildMap {
                config.model?.let { put("model", kotlinx.serialization.json.JsonPrimitive(it)) }
                config.maxTokens?.let { put("maxTokens", kotlinx.serialization.json.JsonPrimitive(it)) }
            },
        )

    companion object {
        const val RULE_GENERATE_DESCRIPTION = "generate-description"
        private const val METADATA_SUGGESTED_TITLE = "suggestedTitle"
        private const val METADATA_SUGGESTED_DESCRIPTION = "suggestedDescription"
    }
}
