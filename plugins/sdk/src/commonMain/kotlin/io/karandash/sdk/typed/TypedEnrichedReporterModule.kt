package io.karandash.sdk.typed

import io.karandash.sdk.config.deserializeConfigWarning
import io.karandash.sdk.finding.ValidationReport
import io.karandash.sdk.module.EnrichedReporterModule
import kotlinx.serialization.json.JsonElement

/**
 * A [EnrichedReporterModule] that uses typed configuration.
 *
 * The [reportEnriched] bridge method automatically converts the [JsonElement] to [C]
 * via [configSpec] (if present) or [configSerializer].
 * Implementors override [reportEnrichedTyped] instead.
 *
 * @param C The typed config class for this module.
 */
public interface TypedEnrichedReporterModule<C : Any> :
    EnrichedReporterModule,
    TypedReporterModule<C> {
    /**
     * Report findings using a pre-computed [ValidationReport] and typed configuration.
     *
     * @param report The fully-enriched validation report.
     * @param config Typed configuration deserialized from the reporter block.
     * @return Optional output string (e.g. file path written).
     */
    public suspend fun reportEnrichedTyped(
        report: ValidationReport,
        config: C,
    ): String?

    override suspend fun reportEnriched(
        report: ValidationReport,
        config: JsonElement,
    ): String? {
        val typedConfig = deserializeConfigWarning(configSerializer, config, "reporter '$id'")
        return reportEnrichedTyped(report, typedConfig)
    }
}
