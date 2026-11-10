package io.karandash.api.module

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.context.EnrichmentContext
import io.karandash.models.MergeRequest
import kotlinx.serialization.json.JsonElement

/**
 * Enrichment providers fetch additional data from external services.
 *
 * Unlike primary providers ([PlatformServiceModule]), enrichment providers
 * contribute typed data accessible to rules via [EnrichmentContext].
 *
 * Providers may declare dependencies on other providers via [requiredEnrichment].
 * The engine executes providers in dependency order, passing upstream results
 * through [EnrichmentContext] so dependent providers can consume earlier output.
 *
 * Lifecycle: [Module.initialize] -> [enrich] (once per run) -> [Module.shutdown].
 * Failures are non-fatal: logged and skipped, validation continues with
 * whatever enrichment data was successfully collected.
 */
public interface EnrichmentProviderModule : Module {
    override val type: ModuleType get() = ModuleType.ENRICHMENT_PROVIDER

    /**
     * Unique enrichment IDs this provider can contribute.
     *
     * Convention: `"provider-id:data-type"` (e.g., `"jira:ticket-details"`).
     * Rules declare which enrichment IDs they consume via
     * [RuleModule.supportedEnrichment]; the pipeline matches providers to
     * rules using these IDs. Default: empty.
     */
    @ExperimentalKarandashApi
    public val providedEnrichment: Set<String> get() = emptySet()

    /**
     * Enrichment IDs this provider depends on from other providers.
     *
     * The engine runs dependent providers first and passes their
     * output via [EnrichmentContext] in [enrich]. If a required provider
     * fails, the engine still invokes this provider but marks the missing
     * IDs in [EnrichmentContext.isMissing] so providers can degrade gracefully.
     *
     * @return List of enrichment IDs this provider requires. Empty by default.
     */
    public fun requiredEnrichment(): List<String> = emptyList()

    /**
     * Why this provider is off or only partly on for the run (a missing token, a binary not on PATH), read
     * after [Module.initialize]. The run shows it in the review provenance as a skipped enricher instead of
     * leaving it to a log line. Null (the default) when the provider runs fully, or when it is off because its
     * own config turned it off.
     */
    public fun skipReason(): String? = null

    /**
     * Fetch enrichment data for the given merge request.
     *
     * Called once per validation run. Results are cached in CAS by input hash.
     *
     * @param mr The merge request being validated.
     * @param config Provider-specific configuration element.
     * @param upstreamContext Enrichment data from providers that ran earlier
     *        in the dependency chain. Empty for providers with no dependencies.
     * @return Map of enrichment ID to serialized data ([JsonElement]).
     *         Keys must be a subset of [enrichmentIds].
     */
    public suspend fun enrich(
        mr: MergeRequest,
        config: JsonElement,
        upstreamContext: EnrichmentContext = EnrichmentContext.EMPTY,
    ): Map<String, JsonElement>
}
