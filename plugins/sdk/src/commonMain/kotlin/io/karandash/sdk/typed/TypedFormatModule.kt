package io.karandash.sdk.typed

import io.karandash.sdk.config.deserializeConfigWarning
import io.karandash.sdk.finding.FormattedReport
import io.karandash.sdk.finding.ValidationReport
import io.karandash.sdk.module.FormatModule
import kotlinx.serialization.json.JsonElement

/**
 * A [FormatModule] that uses typed configuration.
 *
 * The [format] bridge method automatically converts the [JsonElement] to [C]
 * via [configSerializer]. Implementors override [formatTyped] instead.
 *
 * @param C The typed config class for this module.
 */
public interface TypedFormatModule<C : Any> :
    FormatModule,
    TypedModule<C> {
    /**
     * Transform a [ValidationReport] into a [FormattedReport] using typed configuration.
     *
     * @param report The fully-enriched validation report.
     * @param config Typed configuration deserialized from the format block.
     * @return The formatted report ready for delivery.
     */
    public suspend fun formatTyped(
        report: ValidationReport,
        config: C,
    ): FormattedReport

    override suspend fun format(
        report: ValidationReport,
        config: JsonElement,
    ): FormattedReport {
        val typedConfig = deserializeConfigWarning(configSerializer, config, "format '$formatId'")
        return formatTyped(report, typedConfig)
    }
}
